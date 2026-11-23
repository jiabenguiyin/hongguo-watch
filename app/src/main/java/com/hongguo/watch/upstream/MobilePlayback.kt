// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

import com.hongguo.watch.data.PlaybackRoutePreference
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Signing returns the final URL because v5 selects its digest branch through the request ticket. */
internal data class MobileSignedRequest(val url: String, val headers: Map<String, String>)

internal fun interface MobilePlaybackSigner {
    fun sign(url: String, body: ByteArray?, device: Map<String, String>): MobileSignedRequest
}

/** Safe for diagnostic uploads: no request URLs, identifiers, or upstream response body. */
internal data class PlaybackRouteAttempt(
    val route: String,
    val outcome: String,
    val elapsedMs: Long,
    val remainingMs: Long?,
    val code: Int? = null,
    val modelIssue: PlaybackSourceException.ModelIssue? = null,
)

/**
 * Cookie jar that only replays anonymous device-register cookies onto fqnovel hosts
 * (Win spare style). Never stores account session cookies.
 */
internal class RegisterCookieJar(
    private val cookies: Map<String, String>,
) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (cookies.isEmpty() || !url.host.endsWith("fqnovel.com")) return emptyList()
        return cookies.mapNotNull { (name, value) ->
            if (name.isBlank() || value.isBlank()) return@mapNotNull null
            Cookie.Builder().hostOnlyDomain(url.host).path("/").name(name).value(value).build()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
}

internal object MobilePlayback {
    private val v5Signer = Signer(mobileV5 = true)
    val signer = MobilePlaybackSigner { url, body, _ ->
        val parsed = url.toHttpUrl()
        val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
        val now = values.getValue("_rticket").toLong()
        val request = v5Signer.signPlayback(parsed.newBuilder().query(null).build().toString(), values, body, now)
        MobileSignedRequest(request.url, request.headers)
    }

    /** TV 0.5.12 coherent player identity (desktop-releases arm64 DEX). */
    private const val VERSION_CODE = "73532"
    private const val VERSION_NAME = "7.3.5.32"
    private const val PLAYBACK_UA =
        "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; " +
            "Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    /** Official single-episode spare hosts (drama 8.1 / Win spare endpoint family). */
    internal fun videoModelHosts() =
        listOf(
            "api5-normal-sinfonlinea.fqnovel.com",
            "api5-normal-sinfonlinec.fqnovel.com",
        )

    private enum class Route(
        val preference: PlaybackRoutePreference,
        val tag: String,
        val timeoutMs: Long,
        val hostIndex: Int? = null,
    ) {
        PRIMARY(PlaybackRoutePreference.PRIMARY, "primary-b", 2500, 0),
        PRIMARY_A(PlaybackRoutePreference.PRIMARY_A, "primary-a", 1250, 1),
        PRIMARY_HL(PlaybackRoutePreference.PRIMARY_HL, "primary-hl", 1250, 2),
        PRIMARY_LF(PlaybackRoutePreference.PRIMARY_LF, "primary-lf", 1250, 3),
        VIDEO_MODEL(PlaybackRoutePreference.VIDEO_MODEL, "video-model", 2500),
        VIDEO_TAB(PlaybackRoutePreference.VIDEO_TAB, "video-tab", 3000),
        PRELOAD(PlaybackRoutePreference.PRELOAD, "preload", 1750),
    }

    internal fun modelHosts() = PlayRetryPlan.hosts

    fun ladder(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        preference: PlaybackRoutePreference = PlaybackRoutePreference.AUTO,
        registerCookies: Map<String, String> = emptyMap(),
        onAttempt: (PlaybackRouteAttempt) -> Unit = {},
        onRoute: (String) -> Unit = {},
    ): StreamLadder {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureWithinDeadline()
        val hosts = modelHosts()
        val pending = ArrayDeque(Route.entries)
        val preferred = Route.entries.firstOrNull { it.preference == preference }
        if (preferred != null) {
            pending.remove(preferred)
            pending.addFirst(preferred)
        }
        var lastTransient: IOException? = null
        val emptyRoutes = linkedMapOf<Route, EmptyPlaybackResponseException>()
        var secondaryCanFallBack = false
        var followupAvailable = true
        val claimFollowup = {
            followupAvailable.also { followupAvailable = false }
        }
        var attempt = 0
        fun report(
            route: Route,
            outcome: String,
            start: Long,
            code: Int? = null,
            modelIssue: PlaybackSourceException.ModelIssue? = null,
        ) {
            val finished = System.nanoTime()
            val remaining = RequestScope.remainingNanos(finished)
            onAttempt(
                PlaybackRouteAttempt(
                    route.tag,
                    outcome,
                    TimeUnit.NANOSECONDS.toMillis(finished - start),
                    remaining.takeUnless { it == Long.MAX_VALUE }
                        ?.let { TimeUnit.NANOSECONDS.toMillis(it.coerceAtLeast(0)) },
                    code,
                    modelIssue,
                ),
            )
        }
        while (pending.isNotEmpty()) {
            RequestScope.ensureWithinDeadline()
            val route = pending.removeFirst()
            val start = System.nanoTime()
            if (route == Route.PRELOAD && preferred != Route.PRELOAD && !secondaryCanFallBack) {
                report(route, "skipped", start)
                continue
            }
            val remaining = RequestScope.remainingNanos(start)
            val firstChoice = route == preferred && attempt == 0
            val reserve = if (Route.VIDEO_TAB in pending || Route.VIDEO_MODEL in pending) {
                // Leave room for known official spares, but still try an explicit first choice
                // if device registration already took most of the overall budget.
                val backupBudget = TimeUnit.MILLISECONDS.toNanos(
                    Route.VIDEO_MODEL.timeoutMs + Route.VIDEO_TAB.timeoutMs + 100,
                )
                if (firstChoice) minOf(backupBudget, remaining / 2) else backupBudget
            } else {
                0L
            }
            val attemptMs = if (firstChoice && route.hostIndex != null) Route.PRIMARY.timeoutMs else route.timeoutMs
            val budget = minOf(TimeUnit.MILLISECONDS.toNanos(attemptMs), remaining - reserve)
            if (budget <= 0L) {
                report(route, "budget", start)
                if (lastTransient == null) lastTransient = PlaybackRouteTimeoutException()
                continue
            }
            val routeDeadline = start + budget
            val ticket = now + attempt++
            try {
                val ladder = when (route) {
                    Route.VIDEO_TAB ->
                        ladderOnVideoTab(
                            id, device, http, signer, ticket, routeDeadline, claimFollowup, registerCookies,
                        )
                    Route.VIDEO_MODEL ->
                        ladderOnVideoModel(
                            id, device, http, signer, ticket, routeDeadline, claimFollowup, registerCookies,
                        )
                    else ->
                        ladderOnHost(
                            host = hosts[route.hostIndex ?: 0],
                            id = id,
                            device = device,
                            http = http,
                            signer = signer,
                            now = ticket,
                            routeDeadline = routeDeadline,
                            preload = route == Route.PRELOAD,
                            claimFollowup = claimFollowup,
                            registerCookies = registerCookies,
                        )
                }
                report(route, "ok", start, 200)
                onRoute(route.tag)
                return ladder
            } catch (e: EmptyPlaybackResponseException) {
                emptyRoutes[route] = e
                report(route, "empty", start, 200)
                if (route == Route.VIDEO_TAB || route == Route.VIDEO_MODEL) secondaryCanFallBack = true
                if (preferred == null && route == Route.PRIMARY) {
                    // Official single-episode spare before another mirror of the empty primary POST.
                    if (pending.remove(Route.VIDEO_TAB)) pending.addFirst(Route.VIDEO_TAB)
                    if (pending.remove(Route.VIDEO_MODEL)) pending.addFirst(Route.VIDEO_MODEL)
                }
            } catch (e: IOException) {
                val sourceFailure = e as? PlaybackSourceException
                report(route, outcome(e), start, sourceFailure?.upstreamCode, sourceFailure?.modelIssue)
                RequestScope.ensureWithinDeadline()
                val routeUnavailable = e is PlaybackSourceException && when (e.reason) {
                    PlaybackSourceException.Reason.NO_CONTENT,
                    PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE,
                    -> true
                    PlaybackSourceException.Reason.HTTP_REJECTED -> e.upstreamCode == 404 || e.upstreamCode == 405
                    else -> false
                }
                if (!PlayRetryPlan.temporary(e) && !routeUnavailable) throw e
                lastTransient = e
                if (route == Route.VIDEO_TAB || route == Route.VIDEO_MODEL) secondaryCanFallBack = true
                if (route.hostIndex != null && (e is SocketTimeoutException || routeUnavailable)) {
                    if (pending.remove(Route.VIDEO_TAB)) pending.addFirst(Route.VIDEO_TAB)
                    if (pending.remove(Route.VIDEO_MODEL)) pending.addFirst(Route.VIDEO_MODEL)
                }
            }
        }
        val emptyFailure = if (emptyRoutes.isEmpty()) {
            null
        } else {
            val detail = buildString {
                val emptyHosts = emptyRoutes.keys.mapNotNull { it.hostIndex?.let(hosts::get) }
                append("stage=mobile-model empty_hosts=").append(emptyHosts.joinToString(",") { PlayRetryPlan.tag(it) })
                emptyRoutes[Route.VIDEO_MODEL]?.detail?.let { append(" video_model=[$it]") }
                emptyRoutes[Route.VIDEO_TAB]?.detail?.let { append(" video_tab=[$it]") }
                emptyRoutes[Route.PRELOAD]?.detail?.let { append(" preload=[$it]") }
            }
            EmptyPlaybackResponseException(
                detail,
                allCompatibilityModelsEmpty = emptyRoutes.size == Route.entries.size,
            )
        }
        lastTransient?.let { failure ->
            emptyFailure?.let(failure::addSuppressed)
            throw failure
        }
        throw emptyFailure ?: IOException("播放服务暂时不可用，请稍后重试")
    }

    private fun outcome(error: IOException): String = when (error) {
        is PlaybackSourceException -> error.reason.name.lowercase(Locale.ROOT)
        is SocketTimeoutException -> "timeout"
        is UnknownHostException -> "dns"
        is SocketException -> "socket"
        is InterruptedIOException -> "interrupted"
        else -> "io"
    }

    private fun modelTransport(http: OkHttpClient, registerCookies: Map<String, String>): OkHttpClient =
        http.newBuilder().cookieJar(RegisterCookieJar(registerCookies)).build()

    /** Win spare style: replay anonymous register cookies on model requests. */
    private fun Request.Builder.withRegisterCookies(cookies: Map<String, String>): Request.Builder {
        if (cookies.isEmpty()) return this
        val value = cookies.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .joinToString("; ") { "${it.key}=${it.value}" }
        if (value.isNotEmpty()) header("Cookie", value)
        return this
    }

    private fun playbackQuery(device: Map<String, String>, now: Long): LinkedHashMap<String, String> =
        linkedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to VERSION_CODE,
            "version_name" to VERSION_NAME,
            "manifest_version_code" to VERSION_CODE,
            "update_version_code" to VERSION_CODE,
            "channel" to "update_64",
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "device_type" to (device["device_type"] ?: "25053RT47C"),
            "device_brand" to (device["device_brand"] ?: "Redmi"),
            "language" to "zh",
            "os_api" to (device["os_api"] ?: "36"),
            "os_version" to (device["os_version"] ?: "16"),
            "iid" to device.getValue("iid"),
            "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id"),
            "_rticket" to now.toString(),
        )

    private fun ladderOnHost(
        host: String,
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        routeDeadline: Long,
        preload: Boolean,
        claimFollowup: () -> Boolean,
        registerCookies: Map<String, String>,
    ): StreamLadder {
        val scene = if (preload) "preload" else "player"
        val query = playbackQuery(device, now)
        val url = "https://$host/novel/player/multi_video_model/$scene/v1"
            .toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        // TV uses platform decoders, not the official app's bytevc2 decoder.
        // Level 3 can return only bytevc2; level 1 negotiates compatible renditions.
        val payload = JSONObject(
            """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"$scene"}""",
        )
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val request = Request.Builder().url(url)
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json").header("Content-Encoding", "gzip")
            .header(
                "x-ss-stub",
                MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) },
            )
            .withRegisterCookies(registerCookies)
        val signed = signer.sign(url.toString(), bytes, query)
        request.url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        RequestScope.ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = modelTransport(http, registerCookies)
        return RequestScope.execute(transport.newCall(request.build()), routeDeadline) { response ->
            val stage = if (preload) "preload" else "mobile-model"
            val data = modelData(response, stage, host)
            val entries = data.optJSONObject("1004") ?: data
            val model = PlaybackModels.requireModel(entries, entries.optJSONObject(id))
            ladderFromModel(model, http, routeDeadline, id, claimFollowup)
        }
    }

    /** Official single-episode spare: POST /novel/player/video_model/v1/ (Win spare / drama 8.1). */
    private fun ladderOnVideoModel(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        routeDeadline: Long,
        claimFollowup: () -> Boolean,
        registerCookies: Map<String, String>,
    ): StreamLadder {
        var lastEmpty: EmptyPlaybackResponseException? = null
        var lastTransient: IOException? = null
        for ((index, host) in videoModelHosts().withIndex()) {
            RequestScope.ensureWithinDeadline()
            val remaining = RequestScope.remainingNanos()
            val perHost = (routeDeadline - System.nanoTime()).coerceAtMost(
                TimeUnit.MILLISECONDS.toNanos(if (index == 0) 1500 else 1000),
            )
            if (perHost <= 0L || remaining <= 0L) break
            val hostDeadline = System.nanoTime() + minOf(perHost, remaining)
            try {
                return postVideoModel(
                    host, id, device, http, signer, now + index, hostDeadline, claimFollowup, registerCookies,
                )
            } catch (e: EmptyPlaybackResponseException) {
                lastEmpty = e
            } catch (e: IOException) {
                val routeUnavailable = e is PlaybackSourceException && when (e.reason) {
                    PlaybackSourceException.Reason.NO_CONTENT,
                    PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE,
                    -> true
                    PlaybackSourceException.Reason.HTTP_REJECTED -> e.upstreamCode == 404 || e.upstreamCode == 405
                    else -> false
                }
                if (!PlayRetryPlan.temporary(e) && !routeUnavailable) throw e
                lastTransient = e
            }
        }
        lastTransient?.let { failure ->
            lastEmpty?.let(failure::addSuppressed)
            throw failure
        }
        throw lastEmpty ?: EmptyPlaybackResponseException("stage=video-model hosts=a,c http=200 bytes=0")
    }

    private fun postVideoModel(
        host: String,
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        routeDeadline: Long,
        claimFollowup: () -> Boolean,
        registerCookies: Map<String, String>,
    ): StreamLadder {
        val query = playbackQuery(device, now)
        val url = "https://$host/novel/player/video_model/v1/"
            .toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        // Match drama single-episode body; keep device_level=1 for platform decoder compatibility.
        val payload = JSONObject(
            """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"from_video_id":"","need_all_video_definition":true,"need_mp4_align":false,"source":9,"use_os_player":false,"use_server_dns":false,"video_platform":3},"content_type":1004,"video_id":"$id"}""",
        )
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val request = Request.Builder().url(url)
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json")
            .header("Content-Encoding", "gzip")
            .header(
                "x-ss-stub",
                MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) },
            )
            .withRegisterCookies(registerCookies)
        val signed = signer.sign(url.toString(), bytes, query)
        request.url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        RequestScope.ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = modelTransport(http, registerCookies)
        return RequestScope.execute(transport.newCall(request.build()), routeDeadline) { response ->
            val data = modelData(response, "video-model", host)
            val entries = data.optJSONObject("1004") ?: data
            val model = PlaybackModels.requireModel(entries, entries.optJSONObject(id))
            ladderFromModel(model, http, routeDeadline, id, claimFollowup)
        }
    }

    private fun ladderOnVideoTab(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        routeDeadline: Long,
        claimFollowup: () -> Boolean,
        registerCookies: Map<String, String>,
    ): StreamLadder {
        val query = linkedMapOf<String, String>().apply {
            putAll(VendorConstants.device)
            putAll(device)
            putAll(
                mapOf(
                    "version_code" to VERSION_CODE,
                    "version_name" to VERSION_NAME,
                    "manifest_version_code" to VERSION_CODE,
                    "update_version_code" to VERSION_CODE,
                    "pv_player" to VERSION_CODE,
                    "channel" to "update_64",
                    "video_id" to id,
                    "video_id_type" to "0",
                    "device_level" to "1",
                    "need_all_video_definition" to "1",
                    "need_mp4_align" to "0",
                    "use_os_player" to "0",
                    "video_platform" to "1024",
                    "source" to "0",
                    "from_video_id" to "",
                    "_rticket" to now.toString(),
                ),
            )
        }
        val base =
            "https://${PlayRetryPlan.hosts.first()}/reading/bookapi/video_tab/multi_video_model/v1/"
        val url =
            base.toHttpUrl().newBuilder().apply {
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
        val signed = signer.sign(url.toString(), null, query)
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .withRegisterCookies(registerCookies)
        signed.headers.forEach { (key, value) -> request.header(key, value) }
        request.get()
        val transport = modelTransport(http, registerCookies)
        return RequestScope.execute(transport.newCall(request.build()), routeDeadline) { response ->
            val data = modelData(response, "video-tab", PlayRetryPlan.hosts.first())
            val entries = data.optJSONObject("video_model_data") ?: data
            val entry = entries.optJSONObject(id)
            val model = PlaybackModels.requireModel(entries, entry ?: entries.takeIf { it.has("video_model") })
            // A direct unkeyed model can be played as before, but doesn't prove which episode
            // an additional model request belongs to.
            val episodeId = id.takeIf { entry != null }
            ladderFromModel(model, http, routeDeadline, episodeId, claimFollowup)
        }
    }

    private fun ladderFromModel(
        model: JSONObject,
        http: OkHttpClient,
        routeDeadline: Long,
        episodeId: String?,
        claimFollowup: () -> Boolean,
    ): StreamLadder {
        val parsed = PlaybackModels.parseLadder(model)
        if (parsed.accepted.isNotEmpty()) return StreamLadder(parsed.accepted, parsed.diag)
        val start = System.nanoTime()
        if (episodeId != null && routeDeadline - start >= TimeUnit.MILLISECONDS.toNanos(200)) {
            val end = minOf(routeDeadline, start + TimeUnit.MILLISECONDS.toNanos(850))
            PlaybackModelFollowup.read(model, http, end, episodeId = episodeId, claimAttempt = claimFollowup)
                ?.let { return it }
        }
        throw PlaybackSourceException(
            PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE,
            modelIssue = if (parsed.diag.isEmpty()) {
                PlaybackSourceException.ModelIssue.NO_RENDITIONS
            } else {
                PlaybackSourceException.ModelIssue.UNUSABLE_RENDITIONS
            },
        )
    }

    private fun modelData(response: Response, stage: String, host: String): JSONObject {
        if (response.code == 204) {
            throw PlaybackSourceException(PlaybackSourceException.Reason.NO_CONTENT, 204)
        }
        if (response.code != 200) {
            val reason = if (PlayRetryPlan.retryableStatus(response.code)) {
                PlaybackSourceException.Reason.TEMPORARY_UNAVAILABLE
            } else {
                PlaybackSourceException.Reason.HTTP_REJECTED
            }
            throw PlaybackSourceException(reason, response.code)
        }
        val body = response.body
            ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        val limit = 2 * 1024 * 1024L
        if (body.contentLength() > limit) throw IOException("播放信息过大")
        val source = body.source()
        source.request(limit + 1)
        if (source.buffer.size > limit) throw IOException("播放信息过大")
        val text = source.readUtf8()
        if (text.isEmpty()) {
            val hostTag = PlayRetryPlan.tag(host)
            throw EmptyPlaybackResponseException("stage=$stage host=$hostTag http=200 bytes=0")
        }
        if (text.isBlank()) {
            throw PlaybackSourceException(PlaybackSourceException.Reason.NO_CONTENT, 200)
        }
        val root = try {
            JSONObject(text)
        } catch (_: org.json.JSONException) {
            throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        }
        val data = root.optJSONObject("data")
        PlaybackModels.checkStatus(root, data)
        return data ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
    }
}
