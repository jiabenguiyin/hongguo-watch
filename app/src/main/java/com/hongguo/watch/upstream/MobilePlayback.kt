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
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Signing returns the final URL because ticket selection may adjust `_rticket`. */
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
 * Playback against Fanqie model endpoints (aligned with jycao2/hongguoTV kotlin-v0.33.1).
 *
 * Official APIs silently drop v5 placeholder helios/medusa headers (empty HTTP 200).
 * Working path: pick `_rticket` via v5 branch search, then attach **PlayerSigner**
 * (real x-argus / x-ladon). Prefer GET video_tab, then POST `/novel/player/multi_video_model/v1`
 * (no `/player/`, no `dr_scene`, version **74532**).
 */
internal object MobilePlayback {
    private val v5Signer = Signer(mobileV5 = true)

    /**
     * Default signer: v5 ticket selection + PlayerSigner real headers
     * (hongguoTV `realSignedHeaders` / 0.33.1).
     */
    val signer = MobilePlaybackSigner { url, body, _ ->
        val parsed = url.toHttpUrl()
        val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
        val now = values.getValue("_rticket").toLong()
        val provisional =
            v5Signer.signPlayback(parsed.newBuilder().query(null).build().toString(), values, body, now)
        realSigned(provisional.url, provisional.headers, body)
    }

    /** Official 7.4.5.32 playback identity (hongguoTV 0.33.1 MobilePlayback). */
    private const val VERSION_CODE = "74532"
    private const val VERSION_NAME = "7.4.5.32"
    private const val PLAYBACK_UA =
        "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
    private const val TAB_UA =
        "com.phoenix.read/73532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"

    private val PLACEHOLDER_HEADERS = setOf(
        "x-gorgon", "x-helios", "x-medusa", "x-argus", "x-ladon", "x-khronos",
        "x-ss-stub", "x-ss-req-ticket", "x-medias", "x-tt-dt",
    )

    /** Replace v5 placeholder signing headers with PlayerSigner (true argus/ladon). */
    internal fun realSigned(
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): MobileSignedRequest {
        val kept = headers.filterKeys { it.lowercase(Locale.ROOT) !in PLACEHOLDER_HEADERS }
        val query = url.substringAfter('?', "")
        val ticket = query.split('&')
            .firstOrNull { it.startsWith("_rticket=") }
            ?.substringAfter('=')
            ?.toLongOrNull()
        val real = if (ticket != null) {
            PlayerSigner.headers(query, body, ts = ticket / 1000, ticket = ticket)
        } else {
            PlayerSigner.headers(query, body)
        }
        return MobileSignedRequest(url, kept + real)
    }

    private enum class Route(
        val preference: PlaybackRoutePreference,
        val tag: String,
        val timeoutMs: Long,
        val hostIndex: Int? = null,
    ) {
        // Official POST v1 (no /player/, no dr_scene) — hosts ordered a→b→…
        PRIMARY(PlaybackRoutePreference.PRIMARY, "primary-a", 2500, 0),
        PRIMARY_B(PlaybackRoutePreference.PRIMARY_A, "primary-b", 1250, 1),
        PRIMARY_HL(PlaybackRoutePreference.PRIMARY_HL, "primary-hl", 1250, 2),
        PRIMARY_LF(PlaybackRoutePreference.PRIMARY_LF, "primary-lf", 1250, 3),
        VIDEO_TAB(PlaybackRoutePreference.VIDEO_TAB, "video-tab", 3000),
        // Legacy empties kept only as last-resort / explicit preference.
        VIDEO_MODEL(PlaybackRoutePreference.VIDEO_MODEL, "video-model", 2000),
        PRELOAD(PlaybackRoutePreference.PRELOAD, "preload", 1750),
    }

    internal fun modelHosts() = PlayRetryPlan.hosts

    internal fun videoModelHosts() =
        listOf(
            "api5-normal-sinfonlinea.fqnovel.com",
            "api5-normal-sinfonlinec.fqnovel.com",
        )

    fun ladder(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        preference: PlaybackRoutePreference = PlaybackRoutePreference.AUTO,
        @Suppress("UNUSED_PARAMETER")
        registerCookies: Map<String, String> = emptyMap(),
        onAttempt: (PlaybackRouteAttempt) -> Unit = {},
        onRoute: (String) -> Unit = {},
    ): StreamLadder {
        // registerCookies ignored: hongguoTV 0.33.1 model transport uses CookieJar.NO_COOKIES.
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureWithinDeadline()
        val hosts = modelHosts()
        val pending = ArrayDeque(Route.entries)
        // hongguoTV 0.33.1: GET video_tab (real sign) first; POST v1 is spare.
        if (preference == PlaybackRoutePreference.AUTO) {
            pending.remove(Route.VIDEO_TAB)
            pending.addFirst(Route.VIDEO_TAB)
        }
        val preferred = Route.entries.firstOrNull { it.preference == preference }
        if (preferred != null && preference != PlaybackRoutePreference.AUTO) {
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
            // Legacy empties only after official tab + v1 hosts have been tried (or explicit pref).
            if (route == Route.PRELOAD || route == Route.VIDEO_MODEL) {
                val explicit = preferred == route
                if (!explicit && !secondaryCanFallBack) {
                    report(route, "skipped", start)
                    continue
                }
            }
            val remaining = RequestScope.remainingNanos(start)
            val firstChoice = route == preferred && attempt == 0
            // Prefer video_tab on AUTO: do not starve it by reserving POST backup budget first.
            val reserve = when {
                route == Route.VIDEO_TAB -> 0L
                Route.PRIMARY in pending || Route.VIDEO_TAB in pending -> {
                    val backupBudget = TimeUnit.MILLISECONDS.toNanos(Route.PRIMARY.timeoutMs + 100)
                    if (firstChoice) minOf(backupBudget, remaining / 2) else backupBudget
                }
                else -> 0L
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
                            id, device, http, signer, ticket, routeDeadline, claimFollowup,
                        )
                    Route.VIDEO_MODEL ->
                        ladderOnVideoModel(
                            id, device, http, signer, ticket, routeDeadline, claimFollowup,
                        )
                    Route.PRELOAD ->
                        ladderOnHost(
                            host = hosts[0],
                            id = id,
                            device = device,
                            http = http,
                            signer = signer,
                            now = ticket,
                            routeDeadline = routeDeadline,
                            preload = true,
                            claimFollowup = claimFollowup,
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
                            preload = false,
                            claimFollowup = claimFollowup,
                        )
                }
                report(route, "ok", start, 200)
                onRoute(route.tag)
                return ladder
            } catch (e: EmptyPlaybackResponseException) {
                emptyRoutes[route] = e
                report(route, "empty", start, 200)
                if (route == Route.VIDEO_TAB || route.hostIndex != null) secondaryCanFallBack = true
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
                if (route == Route.VIDEO_TAB || route.hostIndex != null) secondaryCanFallBack = true
            }
        }
        val emptyFailure = if (emptyRoutes.isEmpty()) {
            null
        } else {
            val detail = buildString {
                val emptyHosts = emptyRoutes.keys.mapNotNull { it.hostIndex?.let(hosts::get) }
                append("stage=mobile-model empty_hosts=")
                    .append(emptyHosts.joinToString(",") { PlayRetryPlan.tag(it) })
                emptyRoutes[Route.VIDEO_TAB]?.detail?.let { append(" video_tab=[$it]") }
                emptyRoutes[Route.VIDEO_MODEL]?.detail?.let { append(" video_model=[$it]") }
                emptyRoutes[Route.PRELOAD]?.detail?.let { append(" preload=[$it]") }
            }
            EmptyPlaybackResponseException(
                detail,
                allCompatibilityModelsEmpty = emptyRoutes.size >= 2,
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

    private fun playbackQuery(device: Map<String, String>, now: Long): LinkedHashMap<String, String> =
        linkedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to VERSION_CODE,
            "version_name" to VERSION_NAME,
            "channel" to "vivo_8662_64",
            "device_platform" to "android",
            "device_type" to "PGT-AN10",
            "os_version" to "12",
            "update_version_code" to VERSION_CODE,
            "iid" to device.getValue("iid"),
            "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id"),
            "_rticket" to now.toString(),
        )

    /** Official POST `/novel/player/multi_video_model/v1` (or legacy preload scene). */
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
    ): StreamLadder {
        val query = playbackQuery(device, now)
        val path = if (preload) {
            "/novel/player/multi_video_model/preload/v1"
        } else {
            // hongguoTV 0.33.1: official path has no /player/ segment and no dr_scene.
            "/novel/player/multi_video_model/v1"
        }
        val url = "https://$host$path"
            .toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val payload = if (preload) {
            JSONObject(
                """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"preload"}""",
            )
        } else {
            JSONObject(
                """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""",
            )
        }
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
        val signed = signer.sign(url.toString(), bytes, query)
        request.url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        RequestScope.ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        // Match working TV: model posts do not attach account/register cookies.
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build()), routeDeadline) { response ->
            val stage = if (preload) "preload" else "mobile-model"
            val data = modelData(response, stage, host)
            directModel(data)?.let { return@execute ladderFromModel(it, http, routeDeadline, id, claimFollowup) }
            val entries = data.optJSONObject("1004") ?: data
            val model = PlaybackModels.requireModel(entries, entries.optJSONObject(id))
            ladderFromModel(model, http, routeDeadline, id, claimFollowup)
        }
    }

    private fun directModel(data: JSONObject): JSONObject? = when (val value = data.opt("video_model")) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    private fun ladderOnVideoModel(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        routeDeadline: Long,
        claimFollowup: () -> Boolean,
    ): StreamLadder {
        var lastEmpty: EmptyPlaybackResponseException? = null
        var lastTransient: IOException? = null
        for ((index, host) in videoModelHosts().withIndex()) {
            RequestScope.ensureWithinDeadline()
            val remaining = RequestScope.remainingNanos()
            val perHost = (routeDeadline - System.nanoTime()).coerceAtMost(
                TimeUnit.MILLISECONDS.toNanos(if (index == 0) 1200 else 800),
            )
            if (perHost <= 0L || remaining <= 0L) break
            val hostDeadline = System.nanoTime() + minOf(perHost, remaining)
            try {
                val query = playbackQuery(device, now + index)
                val url = "https://$host/novel/player/video_model/v1/"
                    .toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
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
                val signed = signer.sign(url.toString(), bytes, query)
                request.url(signed.url)
                signed.headers.forEach { (k, v) -> request.header(k, v) }
                RequestScope.ensureActive()
                request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
                return RequestScope.execute(transport.newCall(request.build()), hostDeadline) { response ->
                    val data = modelData(response, "video-model", host)
                    directModel(data)?.let {
                        return@execute ladderFromModel(it, http, routeDeadline, id, claimFollowup)
                    }
                    val entries = data.optJSONObject("1004") ?: data
                    val model = PlaybackModels.requireModel(entries, entries.optJSONObject(id))
                    ladderFromModel(model, http, routeDeadline, id, claimFollowup)
                }
            } catch (e: EmptyPlaybackResponseException) {
                lastEmpty = e
            } catch (e: IOException) {
                if (!PlayRetryPlan.temporary(e)) throw e
                lastTransient = e
            }
        }
        lastTransient?.let { failure ->
            lastEmpty?.let(failure::addSuppressed)
            throw failure
        }
        throw lastEmpty ?: EmptyPlaybackResponseException("stage=video-model hosts=a,c http=200 bytes=0")
    }

    private fun ladderOnVideoTab(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        routeDeadline: Long,
        claimFollowup: () -> Boolean,
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
                    "channel" to "vivo_8662_64",
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
        val bases = listOf(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/",
            "https://reading.snssdk.com/reading/bookapi/video_tab/multi_video_model/v1/",
        )
        var lastEmpty: EmptyPlaybackResponseException? = null
        var lastTransient: IOException? = null
        for (base in bases) {
            RequestScope.ensureWithinDeadline()
            val url =
                base.toHttpUrl().newBuilder().apply {
                    query.forEach { (key, value) -> addQueryParameter(key, value) }
                }.build()
            try {
                val signed = signer.sign(url.toString(), null, query)
                val request = Request.Builder().url(signed.url)
                    .header("User-Agent", TAB_UA)
                    .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
                signed.headers.forEach { (key, value) -> request.header(key, value) }
                request.get()
                val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
                return RequestScope.execute(transport.newCall(request.build()), routeDeadline) { response ->
                    val data = modelData(response, "video-tab", url.host)
                    val entries = data.optJSONObject("video_model_data") ?: data
                    val entry = entries.optJSONObject(id)
                    val model =
                        PlaybackModels.requireModel(entries, entry ?: entries.takeIf { it.has("video_model") })
                    val episodeId = id.takeIf { entry != null }
                    ladderFromModel(model, http, routeDeadline, episodeId, claimFollowup)
                }
            } catch (e: EmptyPlaybackResponseException) {
                // Blank body is global throttle signal — do not probe the alt host.
                throw e
            } catch (e: IOException) {
                if (!PlayRetryPlan.temporary(e) &&
                    (e as? PlaybackSourceException)?.reason != PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE
                ) {
                    throw e
                }
                lastTransient = e
            }
        }
        lastTransient?.let { throw it }
        throw lastEmpty ?: EmptyPlaybackResponseException("stage=video-tab http=200 bytes=0")
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
