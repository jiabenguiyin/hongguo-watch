// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional **playback signing proxy** client (disabled until a base URL is configured).
 *
 * Watch asks the proxy for a stream URL only; the proxy owns
 * `device_register` → MetaSec/sign → `video_tab` / `multi_video_model`.
 * See store doc `docs/playback-proxy-plan.md` and repo `proxy/`.
 */
internal class PlaybackProxyClient(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    private val authToken: String? = null,
) {
    data class ProxyRendition(
        val quality: String,
        val codec: String?,
        val url: String,
    )

    data class ProxyStream(
        val episodeId: String,
        val expiresAtEpochSec: Long?,
        val renditions: List<ProxyRendition>,
    )

    fun fetchStream(episodeId: String, quality: String = "auto"): ProxyStream {
        val root = baseUrl.trimEnd('/')
        val url = "$root/v1/stream?episode_id=${episodeId.encodeUrl()}&quality=${quality.encodeUrl()}"
        val req = Request.Builder().url(url).get().apply {
            val token = authToken?.trim().orEmpty()
            if (token.isNotEmpty()) header("Authorization", "Bearer $token")
        }.build()
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when (resp.code) {
                    401 -> throw IOException("playback proxy unauthorized")
                    429 -> throw PlaybackSourceException(
                        PlaybackSourceException.Reason.HTTP_REJECTED,
                        429,
                    )
                    502, 503 -> throw EmptyPlaybackResponseException(
                        "stage=playback-proxy http=${resp.code} ${body.take(120)}",
                    )
                }
                if (!resp.isSuccessful) {
                    throw IOException("playback proxy HTTP ${resp.code}: ${body.take(200)}")
                }
                return parseStream(body)
            }
        } catch (e: EmptyPlaybackResponseException) {
            throw e
        } catch (e: PlaybackSourceException) {
            throw e
        } catch (e: IOException) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            throw IOException("playback proxy $detail", e)
        }
    }

    companion object {
        const val ENV_BASE_URL = "HONGGUO_PLAYBACK_PROXY_URL"
        const val ENV_TOKEN = "HONGGUO_PLAYBACK_PROXY_TOKEN"

        fun fromConfigOrNull(
            baseUrl: String?,
            token: String? = null,
            client: OkHttpClient = defaultClient(),
        ): PlaybackProxyClient? {
            val base = baseUrl?.trim().orEmpty()
            if (base.isEmpty()) return null
            if (!base.startsWith("http://") && !base.startsWith("https://")) return null
            return PlaybackProxyClient(
                baseUrl = base.trimEnd('/'),
                client = client,
                authToken = token?.trim()?.ifEmpty { null },
            )
        }

        /** Null when proxy URL is unset — callers keep the local Medusa V5 path. */
        fun fromEnvOrNull(client: OkHttpClient = defaultClient()): PlaybackProxyClient? =
            fromConfigOrNull(
                baseUrl = System.getenv(ENV_BASE_URL),
                token = System.getenv(ENV_TOKEN),
                client = client,
            )

        fun parseStream(json: String): ProxyStream {
            val o = JSONObject(json)
            val episodeId = o.optString("episode_id").ifEmpty {
                throw IOException("playback proxy: missing episode_id")
            }
            val expires = if (o.has("expires_at") && !o.isNull("expires_at")) {
                o.optLong("expires_at")
            } else {
                null
            }
            val arr = o.optJSONArray("renditions")
                ?: throw IOException("playback proxy: missing renditions")
            val list = ArrayList<ProxyRendition>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val media = r.optString("url").trim()
                if (media.isEmpty()) continue
                list += ProxyRendition(
                    quality = r.optString("quality").ifEmpty { "auto" },
                    codec = r.optString("codec").takeIf { it.isNotEmpty() },
                    url = media,
                )
            }
            if (list.isEmpty()) {
                throw IOException("playback proxy: empty renditions")
            }
            return ProxyStream(episodeId = episodeId, expiresAtEpochSec = expires, renditions = list)
        }

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // Proxy may warm MetaSec then sign several URLs (~60s on small VPS).
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(100, TimeUnit.SECONDS)
                .build()

        private fun String.encodeUrl(): String =
            java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
    }
}
