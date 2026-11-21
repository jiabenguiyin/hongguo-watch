package com.hongguo.watch.upstream

import com.hongguo.watch.data.PlaybackRoutePreference
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePlaybackTest {
    private val device = mapOf("device_id" to "123456", "iid" to "654321", "cdid" to "test-device")

    private fun model(
        id: String = "123",
        typed: Boolean = false,
        stringModel: Boolean = false,
        videoTab: Boolean = false,
    ): String {
        val modelObj =
            """{"video_list":[{"video_meta":{"codec_type":"bytevc2","vwidth":720,"vheight":1280},"main_url":"https://example.org/unsupported"},{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://example.org/video"}]}"""
        val videoModel = if (stringModel) {
            "\"" + modelObj.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            modelObj
        }
        val entries = """{"$id":{"video_model":$videoModel}}"""
        val data = when {
            videoTab -> """{"video_model_data":$entries}"""
            typed -> """{"1004":$entries}"""
            else -> entries
        }
        return """{"code":0,"data":$data}"""
    }

    private fun http(
        bodies: List<String>,
        statusCodes: List<Int> = emptyList(),
        inspect: (okhttp3.Request) -> Unit = {},
    ): OkHttpClient {
        val index = AtomicInteger(0)
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                inspect(chain.request())
                val responseIndex = index.getAndIncrement()
                val body = bodies.getOrElse(responseIndex) { bodies.last() }
                val status = statusCodes.getOrElse(responseIndex) { 200 }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(if (status == 200) "OK" else "upstream failure")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()
    }

    private val passthroughSigner = MobilePlaybackSigner { url, _, _ -> MobileSignedRequest(url, emptyMap()) }

    @Test
    fun supportsBothLiveResponseShapesWithoutSelectingOtherEpisodes() {
        for (typed in listOf(false, true)) {
            for (stringModel in listOf(false, true)) {
                val rows = MobilePlayback.ladder(
                    "123",
                    device,
                    http(listOf(model(typed = typed, stringModel = stringModel))),
                    passthroughSigner,
                    1000,
                ).renditions
                assertEquals(1, rows.size)
                assertEquals(720, rows.single().quality)
                assertEquals("https://example.org/video", rows.single().url)
            }
        }
        assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder("123", device, http(listOf(model("456"))), passthroughSigner, 1000)
        }
    }

    @Test
    fun emptyPrimaryAndVideoTabThenTryTheNextMirror() {
        val requests = mutableListOf<Pair<String, String>>()
        val hosts = MobilePlayback.modelHosts()
        val rows = MobilePlayback.ladder(
            "123",
            device,
            http(listOf("", "", model())) { request ->
                requests += request.url.host to request.method
            },
            passthroughSigner,
            1000,
        ).renditions
        assertEquals(1, rows.size)
        assertEquals("https://example.org/video", rows.single().url)
        assertEquals(listOf(hosts[0] to "POST", hosts[0] to "GET", hosts[1] to "POST"), requests)
    }

    @Test
    fun serverFailureAdvancesHostButAuthAndRateLimitDoNot() {
        val advancedHosts = mutableListOf<String>()
        MobilePlayback.ladder(
            "123",
            device,
            http(listOf("", model()), listOf(503, 200)) { request -> advancedHosts += request.url.host },
            passthroughSigner,
            1000,
        )
        assertEquals(2, advancedHosts.size)
        assertEquals(MobilePlayback.modelHosts().take(2), advancedHosts)

        for (status in listOf(401, 403, 429)) {
            val hosts = mutableListOf<String>()
            val error = assertThrows(PlaybackSourceException::class.java) {
                MobilePlayback.ladder(
                    "123",
                    device,
                    http(listOf(""), listOf(status)) { request -> hosts += request.url.host },
                    passthroughSigner,
                    1000,
                )
            }
            assertEquals(status, error.upstreamCode)
            assertEquals(listOf(MobilePlayback.modelHosts().first()), hosts)
        }
    }

    @Test
    fun allHostsEmptySurfacesAggregatedDiagnostic() {
        val hosts = MobilePlayback.modelHosts()
        val error = assertThrows(EmptyPlaybackResponseException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(List(hosts.size + 2) { "" }),
                passthroughSigner,
                1000,
            )
        }
        assertTrue(error.detail.orEmpty().contains("empty_hosts="))
        assertTrue(error.detail.orEmpty().contains("stage=video-tab"))
        assertTrue(error.detail.orEmpty().contains("stage=preload"))
        assertTrue(error.allCompatibilityModelsEmpty)
    }

    @Test
    fun signsAndSendsIdenticalCompressedBytesWithMatchingIdentityAndDigest() {
        var signed: ByteArray? = null
        var url: String? = null
        val signing = MobilePlaybackSigner { target, bytes, identity ->
            assertEquals(device, identity)
            signed = requireNotNull(bytes).copyOf()
            url = target
            MobileSignedRequest(target, mapOf("X-Argus" to "test-signature"))
        }
        MobilePlayback.ladder(
            "123",
            device,
            http(listOf(model())) { request ->
                val bytes = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
                org.junit.Assert.assertArrayEquals(signed, bytes)
                assertEquals(url, request.url.toString())
                assertEquals("/novel/player/multi_video_model/player/v1", request.url.encodedPath)
                assertEquals("123456", request.url.queryParameter("device_id"))
                assertEquals("654321", request.url.queryParameter("iid"))
                assertEquals("gzip", request.header("Content-Encoding"))
                assertEquals("test-signature", request.header("X-Argus"))
                org.junit.Assert.assertNull(request.header("Cookie"))
                assertEquals(
                    MessageDigest.getInstance("MD5").digest(bytes)
                        .joinToString("") { "%02X".format(it) },
                    request.header("x-ss-stub"),
                )
                val inflated = GZIPInputStream(bytes.inputStream()).reader().readText()
                assertTrue(inflated.contains("\"device_level\":1"))
                assertTrue(inflated.contains("\"1004\":[\"123\"]"))
            },
            signing,
            1000,
        )
    }

    @Test
    fun emptyPrimaryFallsBackToSignedVideoTabBeforeMirrors() {
        val paths = mutableListOf<String>()
        var signedVideoTab = false
        var fallbackSuccessCount = 0
        val signer = MobilePlaybackSigner { target, body, profile ->
            if (target.contains("/reading/bookapi/video_tab/multi_video_model/v1/")) {
                assertEquals(null, body)
                assertEquals("73932", profile["version_code"])
                assertEquals("7.3.9.32", profile["version_name"])
                assertEquals("123456", profile["device_id"])
                signedVideoTab = true
            }
            MobileSignedRequest(target, mapOf("X-Test-Signature" to "signed"))
        }
        val rows =
            MobilePlayback.ladder(
                "123",
                device,
                http(listOf("", model(stringModel = true, videoTab = true))) { request ->
                    paths += request.url.encodedPath
                    if (request.url.encodedPath == "/reading/bookapi/video_tab/multi_video_model/v1/") {
                        assertEquals("GET", request.method)
                        assertEquals(null, request.body)
                        assertEquals("123", request.url.queryParameter("video_id"))
                        assertEquals("0", request.url.queryParameter("video_id_type"))
                        assertEquals("1", request.url.queryParameter("device_level"))
                        assertEquals("1", request.url.queryParameter("need_all_video_definition"))
                        assertEquals("0", request.url.queryParameter("need_mp4_align"))
                        assertEquals("0", request.url.queryParameter("use_os_player"))
                        assertEquals("1024", request.url.queryParameter("video_platform"))
                        assertEquals("0", request.url.queryParameter("source"))
                        assertEquals("", request.url.queryParameter("from_video_id"))
                        assertEquals("73932", request.url.queryParameter("version_code"))
                        assertEquals("73932", request.url.queryParameter("manifest_version_code"))
                        assertEquals("73932", request.url.queryParameter("update_version_code"))
                        assertEquals(VendorConstants.PLAYER_UA, request.header("User-Agent"))
                        assertEquals("application/json; charset=utf-8,application/x-protobuf", request.header("Accept"))
                        assertEquals(null, request.header("Content-Encoding"))
                        assertEquals(null, request.header("x-ss-stub"))
                        assertEquals("signed", request.header("X-Test-Signature"))
                    }
                },
                signer,
                1000,
            ) { _ -> fallbackSuccessCount++ }.renditions
        assertEquals(1, rows.size)
        assertEquals("https://example.org/video", rows.single().url)
        assertEquals(
            listOf("/novel/player/multi_video_model/player/v1", "/reading/bookapi/video_tab/multi_video_model/v1/"),
            paths,
        )
        assertTrue(signedVideoTab)
        assertEquals(1, fallbackSuccessCount)
    }

    @Test
    fun preloadCompatibilityRouteRunsOnlyAfterVideoTabIsExplicitlyEmpty() {
        val paths = mutableListOf<String>()
        val bodies = List(MobilePlayback.modelHosts().size + 1) { "" } + model(typed = true, stringModel = true)
        val rows = MobilePlayback.ladder(
            "123",
            device,
            http(bodies) { request ->
                paths += request.url.encodedPath
                if (request.url.encodedPath == "/novel/player/multi_video_model/preload/v1") {
                    assertEquals("POST", request.method)
                    val compressed = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
                    val payload = GZIPInputStream(compressed.inputStream()).reader().readText()
                    assertTrue(payload.contains("\"dr_scene\":\"preload\""))
                    assertTrue(payload.contains("\"1004\":[\"123\"]"))
                }
            },
            passthroughSigner,
            1000,
        ) { _ -> }
        assertEquals("https://example.org/video", rows.renditions.single().url)
        assertEquals(MobilePlayback.modelHosts().size + 2, paths.size)
        assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/", paths[1])
        assertEquals("/novel/player/multi_video_model/preload/v1", paths.last())
    }

    @Test
    fun temporaryFailureOnVideoTabStillAllowsOnePreloadAttempt() {
        val paths = mutableListOf<String>()
        val hosts = MobilePlayback.modelHosts().size
        val statuses = listOf(200, 503) + List(hosts) { 200 }
        val bodies = listOf("", "temporarily unavailable") + List(hosts - 1) { "" } + model(typed = true)
        val ladder = MobilePlayback.ladder(
            "123",
            device,
            http(bodies, statuses) { request -> paths += request.url.encodedPath },
            passthroughSigner,
            1000,
        )
        assertEquals("https://example.org/video", ladder.renditions.single().url)
        assertEquals(hosts + 2, paths.size)
        assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/", paths[1])
        assertEquals("/novel/player/multi_video_model/preload/v1", paths.last())
    }

    @Test
    fun fallbackRateLimitDoesNotTryPreload() {
        val paths = mutableListOf<String>()
        val error = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(listOf("", ""), listOf(200, 429)) { request -> paths += request.url.encodedPath },
                passthroughSigner,
                1000,
            )
        }
        assertEquals(429, error.upstreamCode)
        assertEquals(2, paths.size)
        assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/", paths.last())
    }

    @Test
    fun fetchesAnEpisodeBoundFollowupOnceBeforeMovingToAnotherRoute() {
        val followupUrl = "https://${MobilePlayback.modelHosts().first()}/video/model/v1/?v=1"
        val stub = """{"code":0,"data":{"123":{"video_model":{"video_id":"media-123","fallback_api":"$followupUrl"}}}}"""
        val emptyFollowup = """{"video_info":{"data":{"video_id":"media-123","video_list":[]}}}"""
        val paths = mutableListOf<String>()
        val ladder = MobilePlayback.ladder(
            "123", device,
            http(listOf(stub, emptyFollowup, stub, model())) { paths += it.url.encodedPath },
            passthroughSigner, 1000,
        )
        assertEquals("https://example.org/video", ladder.renditions.single().url)
        assertEquals(
            listOf(
                "/novel/player/multi_video_model/player/v1", "/video/model/v1/",
                "/reading/bookapi/video_tab/multi_video_model/v1/",
                "/novel/player/multi_video_model/player/v1",
            ),
            paths,
        )
    }

    @Test
    fun keyedPrimaryAndVideoTabFollowTheExactFplayUrlForTheRequestedEpisode() {
        val url = "https://vas-lf-x.snssdk.com/video/fplay/1/1234ABcd5678ef00/123?v=%2Fa%2Bb&v=2"
        val entries = """{"123":{"video_model":{"video_id":"media-123","fallback_api":"$url"}}}"""
        val mediaUrl = "https://v1.qznovelvod.com/episode.mp4"
        val reply = """{"data":{"video_info":{"video_id":"media-123","video_list":[{"main_url":"$mediaUrl","codec_type":"h264"}]}}}"""
        val cases = listOf(
            Triple(PlaybackRoutePreference.AUTO, "/novel/player/multi_video_model/player/v1", """{"1004":$entries}"""),
            Triple(PlaybackRoutePreference.VIDEO_TAB, "/reading/bookapi/video_tab/multi_video_model/v1/", """{"video_model_data":$entries}"""),
        )
        for ((preference, firstPath, data) in cases) {
            val paths = mutableListOf<String>()
            val ladder = MobilePlayback.ladder(
                "123", device,
                http(listOf("""{"code":0,"data":$data}""", reply)) { request ->
                    paths += request.url.encodedPath
                    if (paths.size == 2) {
                        assertEquals("GET", request.method)
                        assertEquals(url, request.url.toString())
                    }
                },
                passthroughSigner, 1000, preference,
            )
            assertEquals(listOf(firstPath, "/video/fplay/1/1234ABcd5678ef00/123"), paths)
            assertEquals(mediaUrl, ladder.renditions.single().url)
            assertTrue(ladder.renditions.single().isCdnBackup)
        }
    }

    @Test
    fun unkeyedVideoTabModelNeverFollowsItEvenWhenTheUrlMatchesItsInternalId() {
        val url = "https://vas-lf-x.snssdk.com/video/fplay/1/1234ABcd5678ef00/media-123"
        val stub = """{"code":0,"data":{"video_model":{"video_id":"media-123","fallback_api":"$url"}}}"""
        val paths = mutableListOf<String>()
        val ladder = MobilePlayback.ladder(
            "123", device,
            http(listOf(stub, model())) { paths += it.url.encodedPath },
            passthroughSigner, 1000, PlaybackRoutePreference.VIDEO_TAB,
        )
        assertEquals(
            listOf("/reading/bookapi/video_tab/multi_video_model/v1/", "/novel/player/multi_video_model/player/v1"),
            paths,
        )
        assertEquals("https://example.org/video", ladder.renditions.single().url)
    }

    @Test
    fun laterEmptyResponsesDoNotHideAnEarlierServiceFailure() {
        val attemptCount = MobilePlayback.modelHosts().size + 2
        val paths = mutableListOf<String>()
        val error = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(List(attemptCount) { "" }, listOf(503)) { paths += it.url.encodedPath },
                passthroughSigner,
                1000,
            )
        }
        assertEquals(PlaybackSourceException.Reason.TEMPORARY_UNAVAILABLE, error.reason)
        assertEquals(503, error.upstreamCode)
        assertEquals(attemptCount, paths.size)
        val empty = error.suppressed.single() as EmptyPlaybackResponseException
        assertEquals(false, empty.allCompatibilityModelsEmpty)
        assertTrue(empty.detail.orEmpty().contains("stage=preload"))
    }

    @Test
    fun laterEmptyResponsesDoNotHideAnEarlierSocketTimeout() {
        val attemptCount = MobilePlayback.modelHosts().size + 2
        var attempts = 0
        val error = assertThrows(SocketTimeoutException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(List(attemptCount) { "" }) {
                    if (++attempts == 1) throw SocketTimeoutException("upstream read timeout")
                },
                passthroughSigner,
                1000,
            )
        }
        assertEquals(attemptCount, attempts)
        assertEquals(false, (error.suppressed.single() as EmptyPlaybackResponseException).allCompatibilityModelsEmpty)
    }

    @Test(timeout = 8000)
    fun aHungPrimaryCallIsCancelledAndVideoTabGetsTheNextAttempt() {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().method
            if (requests.size == 1) {
                // The first call has no response at all. OkHttp's own per-call timer must cancel it.
                val stopWaitingAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!chain.call().isCanceled() && System.nanoTime() < stopWaitingAt) {
                    Thread.sleep(10)
                }
                assertTrue("route deadline did not cancel the stalled call", chain.call().isCanceled())
                throw IOException("Canceled")
            }
            httpResponse(chain.request(), model(videoTab = true))
        }.build()
        val result = RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(12)) {
            MobilePlayback.ladder("123", device, client, passthroughSigner, 1000)
        }
        assertEquals(listOf("POST", "GET"), requests)
        assertEquals("https://example.org/video", result.renditions.single().url)
    }

    @Test
    fun lowRemainingBudgetSkipsPostToLeaveTimeForVideoTab() {
        val methods = mutableListOf<String>()
        RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)) {
            MobilePlayback.ladder(
                "123",
                device,
                http(listOf(model(videoTab = true))) { methods += it.method },
                passthroughSigner,
                1000,
            )
        }
        assertEquals(listOf("GET"), methods)
    }

    @Test
    fun lowRemainingBudgetStillHonorsAnExplicitFirstChoice() {
        val hosts = mutableListOf<String>()
        RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)) {
            MobilePlayback.ladder(
                "123", device, http(listOf(model())) { hosts += it.url.host },
                passthroughSigner, 1000, PlaybackRoutePreference.PRIMARY_A,
            )
        }
        assertEquals(listOf(MobilePlayback.modelHosts()[1]), hosts)
    }

    @Test
    fun cancellingPlaybackDoesNotStartAnAlternateRequest() {
        val scope = RequestScope()
        var attempts = 0
        val client = http(listOf(model())) {
            attempts++
            scope.cancel()
            throw InterruptedIOException("request cancelled while taking source")
        }
        assertThrows(InterruptedIOException::class.java) {
            scope.run { MobilePlayback.ladder("123", device, client, passthroughSigner, 1000) }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun routePreferenceSelectsThatExactHostOrInterfaceFirst() {
        val primaryPreferences = listOf(
            PlaybackRoutePreference.PRIMARY,
            PlaybackRoutePreference.PRIMARY_A,
            PlaybackRoutePreference.PRIMARY_HL,
            PlaybackRoutePreference.PRIMARY_LF,
        )
        for ((index, preference) in primaryPreferences.withIndex()) {
            val hosts = mutableListOf<String>()
            MobilePlayback.ladder(
                "123", device, http(listOf(model())) { hosts += it.url.host },
                passthroughSigner, 1000, preference,
            )
            assertEquals(listOf(MobilePlayback.modelHosts()[index]), hosts)
        }
        val videoTab = mutableListOf<String>()
        MobilePlayback.ladder(
            "123", device, http(listOf(model(videoTab = true))) { videoTab += it.method },
            passthroughSigner, 1000, PlaybackRoutePreference.VIDEO_TAB,
        )
        assertEquals(listOf("GET"), videoTab)

        val preload = mutableListOf<String>()
        MobilePlayback.ladder(
            "123", device, http(listOf("", model()), listOf(404, 200)) { preload += it.url.encodedPath },
            passthroughSigner, 1000, PlaybackRoutePreference.PRELOAD,
        )
        assertEquals(
            listOf("/novel/player/multi_video_model/preload/v1", "/novel/player/multi_video_model/player/v1"),
            preload,
        )
    }

    @Test
    fun allPostHostsTemporarilyUnavailableStillTriesVideoTab() {
        val methods = mutableListOf<String>()
        val hostCount = MobilePlayback.modelHosts().size
        val result = MobilePlayback.ladder(
            "123",
            device,
            http(List(hostCount) { "" } + model(videoTab = true), List(hostCount) { 503 }) {
                methods += it.method
            },
            passthroughSigner,
            1000,
        )
        assertEquals(List(hostCount) { "POST" } + "GET", methods)
        assertEquals("https://example.org/video", result.renditions.single().url)
    }

    private fun httpResponse(request: okhttp3.Request, body: String) =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody())
            .build()

    @Test
    fun noContentAndUnusableModelTryAnAlternateEndpointWithDiagnostic() {
        val cases = listOf(
            Triple(204, "", "no_content"),
            Triple(200, " ", "no_content"),
            Triple(200, model("456"), "no_playable_source"),
            Triple(404, "not found", "http_rejected"),
        )
        for ((status, body, outcome) in cases) {
            val reports = mutableListOf<PlaybackRouteAttempt>()
            val methods = mutableListOf<String>()
            val ladder = MobilePlayback.ladder(
                "123",
                device,
                http(listOf(body, model(videoTab = true)), listOf(status, 200)) { methods += it.method },
                passthroughSigner,
                1000,
                onAttempt = { reports += it },
            )
            assertEquals("https://example.org/video", ladder.renditions.single().url)
            assertEquals(listOf("POST", "GET"), methods)
            assertEquals(listOf("primary-b", "video-tab"), reports.map { it.route })
            assertEquals(listOf(outcome, "ok"), reports.map { it.outcome })
            assertEquals(if (outcome == "no_playable_source") null else status, reports.first().code)
        }
    }

    @Test
    fun allNoContentStatusesDoNotMasqueradeAsEmpty200IdentityFailure() {
        val attempts = MobilePlayback.modelHosts().size + 2
        val failure = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder(
                "123", device, http(List(attempts) { "" }, List(attempts) { 204 }),
                passthroughSigner, 1000,
            )
        }
        assertEquals(PlaybackSourceException.Reason.NO_CONTENT, failure.reason)
        assertEquals(204, failure.upstreamCode)
    }

    @Test
    fun emptyModelsAndUnusableVideoTabRetainDistinctDiagnosticCauses() {
        val cases = listOf(
            """{"code":0,"data":{"video_model_data":{}}}""" to
                PlaybackSourceException.ModelIssue.MODEL_MISSING,
            """{"code":0,"data":{"video_model_data":{"123":{"video_model":"not a model"}}}}""" to
                PlaybackSourceException.ModelIssue.MODEL_INVALID,
            """{"code":0,"data":{"video_model_data":{"123":{"video_model":{"video_list":[]}}}}}""" to
                PlaybackSourceException.ModelIssue.NO_RENDITIONS,
            """{"code":0,"data":{"video_model_data":{"123":{"video_model":{"video_list":[{"codec_type":"bytevc2"}]}}}}}""" to
                PlaybackSourceException.ModelIssue.UNUSABLE_RENDITIONS,
        )
        val expectedRoutes = listOf("primary-b", "video-tab", "primary-a", "primary-hl", "primary-lf", "preload")
        for ((videoTabBody, issue) in cases) {
            val reports = mutableListOf<PlaybackRouteAttempt>()
            val error = assertThrows(PlaybackSourceException::class.java) {
                MobilePlayback.ladder(
                    "123",
                    device,
                    http(listOf("", videoTabBody, "", "", "", "")),
                    passthroughSigner,
                    1000,
                    onAttempt = { reports += it },
                )
            }
            assertEquals(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE, error.reason)
            assertEquals(issue, error.modelIssue)
            assertEquals(expectedRoutes, reports.map { it.route })
            assertEquals(listOf("empty", "no_playable_source", "empty", "empty", "empty", "empty"), reports.map { it.outcome })
            assertEquals(issue, reports.single { it.route == "video-tab" }.modelIssue)
            assertEquals(5, reports.count { it.outcome == "empty" && it.code == 200 && it.modelIssue == null })
            assertEquals(false, (error.suppressed.single() as EmptyPlaybackResponseException).allCompatibilityModelsEmpty)
        }
    }

    @Test
    fun malformedOrUnrecognizedBodyStopsWithoutRotating() {
        val paths = mutableListOf<String>()
        val failure = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder(
                "123", device, http(listOf("<html>not a playback response</html>")) { paths += it.url.encodedPath },
                passthroughSigner, 1000,
            )
        }
        assertEquals(1, paths.size)
        assertEquals(PlaybackSourceException.Reason.INVALID_RESPONSE, failure.reason)
    }

    @Test
    fun rejectsBusinessErrorsAndOversizeResponse() {
        val error = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(listOf("""{"code":110001}""")),
                passthroughSigner,
                1000,
            )
        }
        assertEquals(110001, error.upstreamCode)
        assertThrows(IOException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(listOf(" ".repeat(2 * 1024 * 1024 + 1))),
                passthroughSigner,
                1000,
            )
        }
    }

    @Test
    fun rejectsEpisodeModelWithBusinessDenialEvenWhenItHasPlayableMedia() {
        for (videoTab in listOf(false, true)) {
            val requests = mutableListOf<String>()
            val denied = model(videoTab = videoTab)
                .replace("\"video_model\":{", "\"video_model\":{\"Code\":0,\"code\":110001,")
            val error = assertThrows(PlaybackSourceException::class.java) {
                MobilePlayback.ladder(
                    "123", device, http(listOf(denied)) { requests += it.url.encodedPath },
                    passthroughSigner, 1000,
                    preference = if (videoTab) PlaybackRoutePreference.VIDEO_TAB else PlaybackRoutePreference.AUTO,
                )
            }
            assertEquals(1, requests.size)
            assertEquals(PlaybackSourceException.Reason.REJECTED, error.reason)
            assertEquals(110001, error.upstreamCode)
        }
    }

    @Test
    fun modelHostsPreferOfficialBThenFallbacks() {
        val hosts = MobilePlayback.modelHosts()
        assertEquals("api5-normal-sinfonlineb.fqnovel.com", hosts.first())
        assertTrue(hosts.contains("api5-normal-sinfonlinea.fqnovel.com"))
        assertEquals(hosts.distinct(), hosts)
    }
}
