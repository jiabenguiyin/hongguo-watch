// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

import java.net.URI
import java.net.URISyntaxException
import kotlin.math.abs
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object PlaybackModelFollowup {
    private const val MAX_BODY_BYTES = 256 * 1024L
    private const val MAX_ADDRESS_LENGTH = 8 * 1024
    private const val FPLAY_HOST = "vas-lf-x.snssdk.com"
    private val fplayPath = Regex("/video/fplay/1/[a-fA-F0-9]{16,64}/([a-zA-Z0-9_-]{1,128})")

    /**
     * Reads one metadata link supplied by an already accepted response for the requested episode.
     * The caller owns the follow-up budget across its routes.
     */
    fun read(
        model: JSONObject,
        http: OkHttpClient,
        routeDeadlineNanos: Long,
        episodeId: String?,
        claimAttempt: () -> Boolean,
    ): StreamLadder? {
        if (!PlaybackModels.statusIsValid(model)) return null
        // Do not replace inline renditions, including ones rejected for incompatible codec/key.
        when (val list = model.opt("video_list")) {
            null, JSONObject.NULL -> Unit
            is JSONObject -> if (list.length() != 0) return null
            is JSONArray -> if (list.length() != 0) return null
            else -> return null
        }
        val originalId = videoId(model) ?: return null
        val address = fallbackAddress(model.opt("fallback_api")) ?: return null
        val url = verifiedEndpoint(address, originalId, episodeId) ?: return null
        val originalDuration = if (model.has("video_duration")) {
            duration(model) ?: return null
        } else {
            null
        }
        RequestScope.ensureWithinDeadline()
        if (System.nanoTime() >= routeDeadlineNanos) throw PlaybackRouteTimeoutException()
        if (!claimAttempt()) return null

        val transport = http.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cache(null)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // OkHttp can replay 503 or a coalesced 421 even with connection retries disabled.
                if (response.code == 503 || response.code == 421) {
                    response.close()
                    throw PlaybackSourceException(
                        PlaybackSourceException.Reason.TEMPORARY_UNAVAILABLE,
                        response.code,
                    )
                }
                response
            }
            .build()
        val request = Request.Builder().url(url).get()
            .header("User-Agent", VendorConstants.PLAYER_UA)
            .header("Accept", "application/json")
            .build()
        return RequestScope.execute(transport.newCall(request), routeDeadlineNanos) { response ->
            when (response.code) {
                401, 403, 429 ->
                    throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
                200 -> Unit
                else -> {
                    if (PlayRetryPlan.retryableStatus(response.code)) {
                        throw PlaybackSourceException(
                            PlaybackSourceException.Reason.TEMPORARY_UNAVAILABLE,
                            response.code,
                        )
                    }
                    return@execute null
                }
            }
            val body = response.body ?: return@execute null
            if (body.contentLength() > MAX_BODY_BYTES) return@execute null
            val source = body.source()
            if (source.request(MAX_BODY_BYTES + 1)) return@execute null
            val root = parseObject(source.readUtf8()) ?: return@execute null
            val data = responseModel(root, fplay = url.host == FPLAY_HOST) ?: return@execute null
            if (videoId(data) != originalId) return@execute null
            if (originalDuration != null &&
                abs(originalDuration - (duration(data) ?: return@execute null)) > 0.5
            ) {
                return@execute null
            }
            val parsed = PlaybackModels.parseLadder(data, requireOfficialMedia = true)
            if (parsed.accepted.isEmpty()) null else StreamLadder(parsed.accepted, parsed.diag)
        }
    }

    private fun fallbackAddress(raw: Any?): String? {
        var value = raw
        repeat(4) {
            value = when (val field = value) {
                is JSONObject -> field.opt("fallback_api")
                is JSONArray -> field.opt(0)
                is String -> {
                    if (field.length > MAX_ADDRESS_LENGTH) return null
                    if (!field.startsWith("{")) return field
                    parseObject(field)?.opt("fallback_api")
                }
                else -> return null
            }
        }
        return null
    }

    private fun verifiedEndpoint(address: String, originalId: String, episodeId: String?): HttpUrl? {
        val original = try {
            URI(address)
        } catch (_: URISyntaxException) {
            return null
        }
        val url = address.toHttpUrlOrNull() ?: return null
        // Reject addresses that OkHttp would rewrite; a signed query must be used as provided.
        if (!url.isHttps || url.port != 443 ||
            original.rawUserInfo != null || original.rawFragment != null || original.host == null ||
            url.encodedPath != original.rawPath || url.encodedQuery != original.rawQuery
        ) {
            return null
        }
        if (url.host in PlayRetryPlan.hosts) return url.takeIf { it.encodedPath == "/video/model/v1/" }
        if (url.host != FPLAY_HOST) return null
        val pathId = fplayPath.matchEntire(url.encodedPath)?.groupValues?.get(1) ?: return null
        return url.takeIf { pathId == originalId || pathId == episodeId }
    }

    private fun responseModel(root: JSONObject, fplay: Boolean): JSONObject? {
        val info = root.optJSONObject("video_info")
        val data = if (fplay) root.optJSONObject("data") else null
        val nestedInfo = data?.optJSONObject("video_info")
        val infoData = info?.optJSONObject("data")
        val nestedInfoData = nestedInfo?.optJSONObject("data")
        if (!PlaybackModels.statusIsValid(root, info, data, nestedInfo, infoData, nestedInfoData)) {
            return null
        }
        val candidates = if (fplay) listOfNotNull(nestedInfoData, nestedInfo, infoData, info, data)
        else listOfNotNull(infoData)
        return candidates.singleOrNull { candidate ->
            when (val list = candidate.opt("video_list")) {
                is JSONObject -> list.length() > 0
                is JSONArray -> list.length() > 0
                else -> false
            }
        }
    }

    private fun videoId(value: JSONObject): String? =
        (value.opt("video_id") as? String)?.takeIf { it.isNotBlank() }

    private fun duration(value: JSONObject): Double? =
        value.optDouble("video_duration").takeIf { it.isFinite() && it > 0 }

    private fun parseObject(value: String): JSONObject? = try {
        JSONObject(value)
    } catch (_: JSONException) {
        null
    }
}
