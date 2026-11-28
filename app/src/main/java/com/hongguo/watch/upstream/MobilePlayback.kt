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
 * Playback against Fanqie model endpoints — N3urda 红果 TV **0.16.14** contour:
 * device_register → Medusa V5 (Khronos/Helios/Medusa, F21=312) →
 * POST `reading.snssdk.com/.../preload/v1` → GET video_tab fallback → stream.
 * Identity notes: **74532 / 7.4.5.32**. No MetaSec / unidbg on the default path;
 * optional [PlaybackProxyClient] remains off by default. Empty HTTP 200 surfaces as
 * user-facing「官方接口限流」— not “play fixed”.
 */
internal object MobilePlayback {
    private val v5Signer = Signer(mobileV5 = true)

    /**
     * Default signer: pure Kotlin Medusa V5 (TV 0.16.14). Keeps Khronos/Helios/Medusa;
     * does **not** replace them with Argus/Ladon [PlayerSigner] (that path empty-200s).
     */
    val signer = MobilePlaybackSigner { url, body, _ ->
        val parsed = url.toHttpUrl()
        val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
        val now = values.getValue("_rticket").toLong()
        val signed =
            v5Signer.signPlayback(parsed.newBuilder().query(null).build().toString(), values, body, now)
        MobileSignedRequest(signed.url, signed.headers)
    }

    /** Upstream identity notes (74532 / 7.4.5.32); matches TV currentApp protocol. */
    private const val VERSION_CODE = VendorConstants.OFFICIAL_VERSION_CODE
    private const val VERSION_NAME = VendorConstants.OFFICIAL_VERSION_NAME
    private const val PLAYBACK_UA =
        "com.phoenix.read/$VERSION_CODE (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
    private const val TAB_UA =
        "com.phoenix.read/$VERSION_CODE (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"

    /** TV 0.16.14 primary model host for preload/v1. Proxy: `PROXY_PRELOAD_HOST`. */
    internal const val PRELOAD_HOST = UpstreamEndpoints.DEFAULT_PRELOAD_HOST

    internal fun preloadHost(): String = UpstreamEndpoints.preloadHost()

    private val PLACEHOLDER_HEADERS = setOf(
        "x-gorgon", "x-helios", "x-medusa", "x-argus", "x-ladon", "x-khronos",
        "x-ss-stub", "x-ss-req-ticket", "x-medias", "x-tt-dt",
    )

    /**
     * Demoted: replace V5 headers with PlayerSigner Argus/Ladon.
     * Kept for diagnostics / optional proxy fallbacks; **not** used by [signer].
     */
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
        // TV 0.16.14 primary: POST preload/v1 on reading.snssdk.com.
        PRELOAD(PlaybackRoutePreference.PRELOAD, "preload", 2500),
        VIDEO_TAB(PlaybackRoutePreference.VIDEO_TAB, "video-tab", 3000),
        // Spare POST v1 hosts (no /player/, no dr_scene) — a→b→hl→lf.
        PRIMARY(PlaybackRoutePreference.PRIMARY, "primary-a", 2500, 0),
        PRIMARY_B(PlaybackRoutePreference.PRIMARY_A, "primary-b", 1250, 1),
        PRIMARY_HL(PlaybackRoutePreference.PRIMARY_HL, "primary-hl", 1250, 2),
        PRIMARY_LF(PlaybackRoutePreference.PRIMARY_LF, "primary-lf", 1250, 3),
        // Legacy empties kept only as last-resort / explicit preference.
        VIDEO_MODEL(PlaybackRoutePreference.VIDEO_MODEL, "video-model", 2000),
    }

    internal fun modelHosts() = UpstreamEndpoints.modelHosts()

    internal fun videoModelHosts() = UpstreamEndpoints.videoModelHosts()

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
        val cookies = sanitizeRegisterCookies(registerCookies)
        val hosts = modelHosts()
        val pending = ArrayDeque(Route.entries)
        // AUTO: TV 0.16.14 order — preload first, then video_tab, then spare v1 hosts.
        if (preference == PlaybackRoutePreference.AUTO) {
            pending.remove(Route.PRELOAD)
            pending.remove(Route.VIDEO_TAB)
            pending.addFirst(Route.VIDEO_TAB)
            pending.addFirst(Route.PRELOAD)
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
            // video_model only after preload/tab/v1 (or explicit pref). PRELOAD is AUTO primary.
            if (route == Route.VIDEO_MODEL) {
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
                            id, device, http, signer, ticket, routeDeadline, claimFollowup, cookies,
                        )
                    Route.VIDEO_MODEL ->
                        ladderOnVideoModel(
                            id, device, http, signer, ticket, routeDeadline, claimFollowup, cookies,
                        )
                    Route.PRELOAD ->
                        ladderOnHost(
                            host = preloadHost(),
                            id = id,
                            device = device,
                            http = http,
                            signer = signer,
                            now = ticket,
                            routeDeadline = routeDeadline,
                            preload = true,
                            claimFollowup = claimFollowup,
                            registerCookies = cookies,
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
                            registerCookies = cookies,
                        )
                }
                report(route, "ok", start, 200)
                onRoute(route.tag)
                return ladder
            } catch (e: EmptyPlaybackResponseException) {
                emptyRoutes[route] = e
                report(route, "empty", start, 200)
                if (route == Route.PRELOAD || route == Route.VIDEO_TAB || route.hostIndex != null) {
                    secondaryCanFallBack = true
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
                if (route == Route.PRELOAD || route == Route.VIDEO_TAB || route.hostIndex != null) {
                    secondaryCanFallBack = true
                }
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

    /** TV 0.16.14 currentApp query contour (74532 / update_64). */
    private fun playbackQuery(device: Map<String, String>, now: Long): LinkedHashMap<String, String> =
        linkedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to VERSION_CODE,
            "version_name" to VERSION_NAME,
            "update_version_code" to VERSION_CODE,
            "manifest_version_code" to VERSION_CODE,
            "os" to "android",
            "ssmix" to "a",
            "channel" to "update_64",
            "device_platform" to "android",
            "device_type" to "25053RT47C",
            "device_brand" to "Redmi",
            "os_version" to "16",
            "os_api" to "36",
            "ac" to "wifi",
            "language" to "zh",
            "host_abi" to "arm64-v8a",
            "iid" to device.getValue("iid"),
            "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id"),
            "_rticket" to now.toString(),
        )

    /** Anonymous device_register cookies only (install_id / store-region / ttreq …). */
    internal fun sanitizeRegisterCookies(raw: Map<String, String>): Map<String, String> {
        val allow = setOf(
            "install_id", "ttreq", "store-region", "store-region-src", "odin_tt", "uid_tt",
        )
        return raw.filter { (name, value) ->
            name in allow && value.isNotBlank() && value.length <= 512 &&
                value.all { it in ' '..'~' && it != ';' && it != ',' }
        }
    }

    private fun Request.Builder.attachRegisterSession(
        registerCookies: Map<String, String>,
    ): Request.Builder {
        if (registerCookies.isEmpty()) return this
        header("Cookie", registerCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        registerCookies["store-region"]?.let { header("x-tt-store-region", it) }
        registerCookies["store-region-src"]?.let { header("x-tt-store-region-src", it) }
            ?: registerCookies["store-region"]?.let { header("x-tt-store-region-src", "did") }
        return this
    }

    private fun playTransport(http: OkHttpClient): OkHttpClient =
        http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

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
        registerCookies: Map<String, String>,
    ): StreamLadder {
        val query = playbackQuery(device, now)
        val path = if (preload) {
            "/novel/player/multi_video_model/preload/v1"
        } else {
            // Spare v1 path (no /player/ segment, no dr_scene).
            "/novel/player/multi_video_model/v1"
        }
        val url = "https://$host$path"
            .toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        // TV 0.16.14: preload uses device_level=2 + dr_scene=preload.
        val payload = if (preload) {
            JSONObject(
                """{"biz_param":{"detail_page_version":0,"device_level":2,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"preload"}""",
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
            .attachRegisterSession(registerCookies)
        val signed = signer.sign(url.toString(), bytes, query)
        request.url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        RequestScope.ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        // CookieJar.NO_COOKIES: register session is on Cookie header only (no account session).
        val transport = playTransport(http)
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
        registerCookies: Map<String, String>,
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
                    .attachRegisterSession(registerCookies)
                val signed = signer.sign(url.toString(), bytes, query)
                request.url(signed.url)
                signed.headers.forEach { (k, v) -> request.header(k, v) }
                RequestScope.ensureActive()
                request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                val transport = playTransport(http)
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
        registerCookies: Map<String, String>,
    ): StreamLadder {
        // Same 74532 contour as POST; sign MD5(empty bytes), not a null/zero digest.
        val query = playbackQuery(device, now).apply {
            put("video_id", id)
            put("video_id_type", "0")
            put("device_level", "1")
            put("need_all_video_definition", "1")
            put("need_mp4_align", "0")
            put("use_os_player", "0")
            put("video_platform", "1024")
            put("source", "0")
            put("from_video_id", "")
        }
        val bases = UpstreamEndpoints.videoTabBases()
        var lastEmpty: EmptyPlaybackResponseException? = null
        var lastTransient: IOException? = null
        for (base in bases) {
            RequestScope.ensureWithinDeadline()
            val url =
                base.toHttpUrl().newBuilder().apply {
                    query.forEach { (key, value) -> addQueryParameter(key, value) }
                }.build()
            try {
                val signed = signer.sign(url.toString(), ByteArray(0), query)
                val request = Request.Builder().url(signed.url)
                    .header("User-Agent", TAB_UA)
                    .header("Accept", "application/json; charset=utf-8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .attachRegisterSession(registerCookies)
                signed.headers.forEach { (key, value) -> request.header(key, value) }
                // Empty bytes are signing input only: GET has no body or POST headers.
                request.removeHeader("x-ss-stub")
                request.removeHeader("Content-Type")
                request.removeHeader("Content-Encoding")
                request.get()
                val transport = playTransport(http)
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
                // Blank body is treated as global throttle — do not burn the deadline on alt tab URLs.
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
        lastTransient?.let { failure ->
            lastEmpty?.let(failure::addSuppressed)
            throw failure
        }
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
