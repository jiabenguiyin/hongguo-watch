// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.proxy

import com.hongguo.watch.data.PlaybackRoutePreference
import com.hongguo.watch.upstream.DeviceRegister
import com.hongguo.watch.upstream.EmptyPlaybackResponseException
import com.hongguo.watch.upstream.MobilePlayback
import com.hongguo.watch.upstream.MobilePlaybackSigner
import com.hongguo.watch.upstream.MobileSignedRequest
import com.hongguo.watch.upstream.PlaybackSourceException
import com.hongguo.watch.upstream.PlayerSigner
import com.hongguo.watch.upstream.RequestScope
import com.hongguo.watch.upstream.VendorConstants
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Playback signing proxy: watch gets stream URLs only; this process owns
 * device_register → sign (MetaSec/unidbg when METASEC_HOME ready, else PlayerSigner) →
 * video_tab / multi_video_model.
 *
 * Run: `./gradlew :proxy:run` or `PORT=8787 HONGGUO_PLAYBACK_PROXY_TOKEN=secret ./gradlew :proxy:run`
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787
    val bind = System.getenv("BIND")?.trim().orEmpty().ifEmpty { "127.0.0.1" }
    val token = System.getenv("HONGGUO_PLAYBACK_PROXY_TOKEN")?.trim().orEmpty()
    val service = StreamService(http = defaultHttp())
    val server = HttpServer.create(InetSocketAddress(bind, port), 0)
    server.executor = Executors.newCachedThreadPool()
    server.createContext("/health") { ex ->
        writeJson(ex, 200, JSONObject().put("ok", true).put("signer", service.signerLabel()))
    }
    server.createContext("/v1/stream") { ex ->
        if (ex.requestMethod != "GET") {
            writeJson(ex, 405, JSONObject().put("error", "method_not_allowed"))
            return@createContext
        }
        if (!authorize(ex, token)) {
            writeJson(ex, 401, JSONObject().put("error", "unauthorized"))
            return@createContext
        }
        val q = queryMap(ex.requestURI.rawQuery)
        val episodeId = q["episode_id"]?.trim().orEmpty()
        if (!EPISODE.matches(episodeId)) {
            writeJson(ex, 400, JSONObject().put("error", "invalid_episode_id"))
            return@createContext
        }
        val quality = q["quality"]?.trim().orEmpty().ifEmpty { "auto" }
        try {
            val body = service.resolveStream(episodeId, quality)
            writeJson(ex, 200, body)
        } catch (empty: EmptyPlaybackResponseException) {
            writeJson(
                ex,
                502,
                JSONObject()
                    .put("error", "upstream_empty")
                    .put("detail", empty.message ?: "empty")
                    .put(
                        "hint",
                        "empty upstream; ensure METASEC_HOME has libmetasec_ml.so + license.b64 " +
                            "(PlayerSigner often empty-200)",
                    ),
            )
        } catch (src: PlaybackSourceException) {
            val code = when (src.reason) {
                PlaybackSourceException.Reason.HTTP_REJECTED ->
                    if (src.upstreamCode == 429) 429 else 502
                PlaybackSourceException.Reason.TEMPORARY_UNAVAILABLE -> 503
                PlaybackSourceException.Reason.NO_CONTENT -> 502
                else -> 502
            }
            writeJson(
                ex,
                code,
                JSONObject()
                    .put("error", src.reason.name.lowercase())
                    .put("upstream_code", src.upstreamCode),
            )
        } catch (io: IOException) {
            writeJson(ex, 502, JSONObject().put("error", "upstream_io").put("detail", io.message))
        } catch (boom: Exception) {
            writeJson(ex, 500, JSONObject().put("error", "internal").put("detail", boom.message))
        }
    }
    server.start()
    val auth = if (token.isEmpty()) "open (no token)" else "Bearer token required"
    println("hongguo playback proxy on http://$bind:$port  auth=$auth  signer=${service.signerLabel()}")
    println("GET /v1/stream?episode_id=…&quality=auto")
    println(
        "MetaSec local=${MetaSecBridge.isLocalReady()} home=${MetaSecBridge.home().absolutePath} " +
            "http=${!System.getenv(MetaSecBridge.ENV_URL).isNullOrBlank()}",
    )
}

private val EPISODE = Regex("[0-9]{1,30}")

private fun defaultHttp(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

private fun authorize(ex: HttpExchange, expected: String): Boolean {
    if (expected.isEmpty()) return true
    val header = ex.requestHeaders.getFirst("Authorization").orEmpty()
    val bearer = if (header.startsWith("Bearer ", ignoreCase = true)) {
        header.substring(7).trim()
    } else {
        ""
    }
    return bearer == expected
}

private fun queryMap(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split('&').mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq < 0) return@mapNotNull null
        val k = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8)
        val v = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8)
        k to v
    }.toMap()
}

private fun writeJson(ex: HttpExchange, code: Int, body: JSONObject) {
    val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
    ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

internal class StreamService(
    private val http: OkHttpClient,
) {
    private val lock = Any()
    private var deviceId: String = ""
    private var installId: String = ""
    private var cdid: String = UUID.randomUUID().toString()
    private var cookies: Map<String, String> = emptyMap()

    fun signerLabel(): String = MetaSecBridge.statusLabel()

    private val signer = MobilePlaybackSigner { url, body, _ ->
        val meta = runCatching { MetaSecBridge.signHeaders(url, body) }.getOrNull()
        if (!meta.isNullOrEmpty()) {
            // Keep non-MetaSec transport headers from the v5 path, replace digests.
            val base = MobilePlayback.signer.sign(url, body, emptyMap())
            val drop = setOf(
                "x-gorgon", "x-helios", "x-medusa", "x-argus", "x-ladon", "x-khronos",
                "x-ss-stub", "x-ss-req-ticket", "x-perseus",
            )
            val kept = base.headers.filterKeys { it.lowercase() !in drop }
            val merged = kept + meta
            return@MobilePlaybackSigner MobileSignedRequest(base.url, merged)
        }
        MobilePlayback.signer.sign(url, body, emptyMap())
    }

    fun resolveStream(episodeId: String, quality: String): JSONObject {
        ensureRegistered()
        MetaSecBridge.bindDevice(deviceId, installId)
        val identity = VendorConstants.device + mapOf(
            "device_id" to deviceId,
            "iid" to installId,
            "cdid" to cdid,
        )
        val ladder = RequestScope.withDeadline(
            System.nanoTime() + TimeUnit.SECONDS.toNanos(14),
        ) {
            MobilePlayback.ladder(
                id = episodeId,
                device = identity,
                http = http,
                signer = signer,
                now = System.currentTimeMillis(),
                preference = PlaybackRoutePreference.AUTO,
                registerCookies = cookies,
            )
        }
        val renditions = ladder.renditions
        if (renditions.isEmpty()) {
            throw EmptyPlaybackResponseException("stage=proxy no renditions")
        }
        val filtered = when {
            quality.equals("auto", ignoreCase = true) -> renditions
            else -> {
                val h = quality.lowercase().removeSuffix("p").toIntOrNull()
                if (h == null) renditions
                else renditions.filter { it.quality == h }.ifEmpty { renditions }
            }
        }
        val arr = JSONArray()
        for (r in filtered) {
            arr.put(
                JSONObject()
                    .put("quality", if (r.quality > 0) "${r.quality}p" else "auto")
                    .put("codec", r.codec)
                    .put("url", r.url),
            )
            for (backup in r.backupUrls) {
                arr.put(
                    JSONObject()
                        .put("quality", if (r.quality > 0) "${r.quality}p" else "auto")
                        .put("codec", r.codec)
                        .put("url", backup),
                )
            }
        }
        return JSONObject()
            .put("episode_id", episodeId)
            .put("expires_at", System.currentTimeMillis() / 1000 + 3600)
            .put("renditions", arr)
            .put("signer", signerLabel())
    }

    private fun ensureRegistered() {
        synchronized(lock) {
            if (deviceId.isNotBlank() && installId.isNotBlank() && cookies.isNotEmpty()) return
            registerLocked()
        }
    }

    private fun registerLocked() {
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (k, v) -> request.header(k, v) }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        http.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("device_register HTTP ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            val registered = DeviceRegister.parse(text, response.headers("Set-Cookie"))
                ?: throw IOException("device_register invalid body")
            deviceId = registered.deviceId
            installId = registered.installId
            cdid = UUID.randomUUID().toString()
            cookies = registered.cookies
        }
    }
}
