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
import org.junit.Assert.assertNull
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
                    PlaybackRoutePreference.PRIMARY,
                ).renditions
                assertEquals(1, rows.size)
                assertEquals(720, rows.single().quality)
                assertEquals("https://example.org/video", rows.single().url)
            }
        }
        assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(listOf(model("456"))),
                passthroughSigner,
                1000,
                PlaybackRoutePreference.PRIMARY,
            )
        }
    }

    @Test
    fun emptyVideoTabFallsBackToOfficialV1BeforeLegacyRoutes() {
        val requests = mutableListOf<Pair<String, String>>()
        val hosts = MobilePlayback.modelHosts()
        val rows = MobilePlayback.ladder(
            "123",
            device,
            http(listOf("", model(typed = true))) { request ->
                requests += request.url.host to request.method
            },
            passthroughSigner,
            1000,
        ).renditions
        assertEquals(1, rows.size)
        assertEquals("https://example.org/video", rows.single().url)
        assertEquals(
            listOf(
                "api5-normal-sinfonlineb.fqnovel.com" to "GET",
                hosts[0] to "POST",
            ),
            requests,
        )
    }

    @Test
    fun serverFailureAdvancesHostButAuthAndRateLimitDoNot() {
        val advancedHosts = mutableListOf<String>()
        MobilePlayback.ladder(
            "123",
            device,
            http(listOf("", model(typed = true)), listOf(503, 200)) { request ->
                advancedHosts += request.url.host
            },
            passthroughSigner,
            1000,
        )
        // Tab base1 503 → tab base2 may accept plain model shape; otherwise POST primary-a.
        assertTrue(advancedHosts.size >= 2)
        assertEquals("api5-normal-sinfonlineb.fqnovel.com", advancedHosts.first())
        assertTrue(
            advancedHosts.last() == "reading.snssdk.com" ||
                advancedHosts.last() == MobilePlayback.modelHosts().first(),
        )

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
            assertEquals(listOf("api5-normal-sinfonlineb.fqnovel.com"), hosts)
        }
    }

    @Test
    fun allHostsEmptySurfacesAggregatedDiagnostic() {
        val hosts = MobilePlayback.modelHosts()
        val modelHosts = MobilePlayback.videoModelHosts()
        // video_tab + primary hosts + video_model hosts + preload
        val error = assertThrows(EmptyPlaybackResponseException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(List(1 + hosts.size + modelHosts.size + 1) { "" }),
                passthroughSigner,
                1000,
            )
        }
        assertTrue(error.detail.orEmpty().contains("empty_hosts="))
        assertTrue(error.detail.orEmpty().contains("stage=video-model") || error.detail.orEmpty().contains("video_model="))
        assertTrue(error.detail.orEmpty().contains("stage=video-tab"))
        assertTrue(error.detail.orEmpty().contains("stage=preload"))
        assertTrue(error.allCompatibilityModelsEmpty)
    }

    @Test
    fun signsAndSendsIdenticalCompressedBytesWithMatchingIdentityAndDigest() {
        var signed: ByteArray? = null
        var url: String? = null
        val signing = MobilePlaybackSigner { target, bytes, identity ->
            assertEquals("123456", identity["device_id"])
            assertEquals("654321", identity["iid"])
            assertEquals("74532", identity["version_code"])
            signed = requireNotNull(bytes).copyOf()
            url = target
            MobileSignedRequest(target, mapOf("X-Argus" to "test-signature"))
        }
        MobilePlayback.ladder(
            "123",
            device,
            http(listOf(model(typed = true))) { request ->
                val bytes = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
                org.junit.Assert.assertArrayEquals(signed, bytes)
                assertEquals(url, request.url.toString())
                assertEquals("/novel/player/multi_video_model/v1", request.url.encodedPath)
                assertEquals("123456", request.url.queryParameter("device_id"))
                assertEquals("654321", request.url.queryParameter("iid"))
                assertEquals("74532", request.url.queryParameter("version_code"))
                assertEquals("7.4.5.32", request.url.queryParameter("version_name"))
                assertEquals("vivo_8662_64", request.url.queryParameter("channel"))
                assertEquals("gzip", request.header("Content-Encoding"))
                assertEquals("test-signature", request.header("X-Argus"))
                assertTrue(request.header("User-Agent")!!.startsWith("com.phoenix.read/74532 "))
                assertNull(request.header("Cookie"))
                assertEquals(
                    MessageDigest.getInstance("MD5").digest(bytes)
                        .joinToString("") { "%02X".format(it) },
                    request.header("x-ss-stub"),
                )
                val inflated = GZIPInputStream(bytes.inputStream()).reader().readText()
                assertTrue(inflated.contains("\"device_level\":1"))
                assertTrue(inflated.contains("\"1004\":[\"123\"]"))
                assertTrue(!inflated.contains("dr_scene"))
            },
            signing,
            1000,
            PlaybackRoutePreference.PRIMARY,
        )
    }

    @Test
    fun registerCookiesAreNotAttachedToModelTransport() {
        val cookies = mapOf("install_id" to "654321", "store-region" to "cn", "ttreq" to "1")
        MobilePlayback.ladder(
            "123",
            device,
            http(listOf(model(videoTab = true))) { request ->
                assertNull(request.header("Cookie"))
            },
            passthroughSigner,
            1000,
            registerCookies = cookies,
        )
    }

    @Test
    fun emptyVideoTabThenOfficialHostsThenLegacyVideoModel() {
        val paths = mutableListOf<String>()
        var signedVideoModel = false
        var signedVideoTab = false
        var fallbackSuccessCount = 0
        val signer = MobilePlaybackSigner { target, body, profile ->
            when {
                target.contains("/novel/player/video_model/v1/") -> {
                    assertTrue(body != null && body.isNotEmpty())
                    assertEquals("74532", profile["version_code"])
                    assertEquals("123456", profile["device_id"])
                    signedVideoModel = true
                }
                target.contains("/reading/bookapi/video_tab/video_model/v1/") ||
                    target.contains("/reading/bookapi/video_tab/multi_video_model/v1/") -> {
                    assertEquals(null, body)
                    assertEquals("74532", profile["version_code"])
                    assertEquals("7.4.5.32", profile["version_name"])
                    assertEquals("123456", profile["device_id"])
                    signedVideoTab = true
                }
            }
            MobileSignedRequest(target, mapOf("X-Test-Signature" to "signed"))
        }
        val hostEmpties = List(MobilePlayback.modelHosts().size) { "" }
        val modelEmpties = List(MobilePlayback.videoModelHosts().size) { "" }
        val rows =
            MobilePlayback.ladder(
                "123",
                device,
                http(listOf("") + hostEmpties + modelEmpties.dropLast(1) + model()) { request ->
                    paths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/novel/player/video_model/v1/" -> {
                            assertEquals("POST", request.method)
                            assertEquals("74532", request.url.queryParameter("version_code"))
                            assertEquals("signed", request.header("X-Test-Signature"))
                            val compressed = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
                            val payload = GZIPInputStream(compressed.inputStream()).reader().readText()
                            assertTrue(payload.contains("\"video_id\":\"123\""))
                            assertTrue(payload.contains("\"content_type\":1004"))
                            assertTrue(payload.contains("\"video_platform\":3"))
                        }
                        "/reading/bookapi/video_tab/video_model/v1/",
                        "/reading/bookapi/video_tab/multi_video_model/v1/" -> {
                            assertEquals("GET", request.method)
                            assertEquals(null, request.body)
                            assertEquals("123", request.url.queryParameter("video_id"))
                            assertEquals("74532", request.url.queryParameter("version_code"))
                            assertEquals("74532", request.url.queryParameter("manifest_version_code"))
                            assertEquals("74532", request.url.queryParameter("update_version_code"))
                            assertTrue(request.header("User-Agent")!!.startsWith("com.phoenix.read/74532 "))
                            assertEquals("signed", request.header("X-Test-Signature"))
                        }
                        "/novel/player/multi_video_model/v1" -> {
                            assertEquals("POST", request.method)
                            assertEquals("74532", request.url.queryParameter("version_code"))
                        }
                    }
                },
                signer,
                1000,
            ) { _ -> fallbackSuccessCount++ }.renditions
        assertEquals(1, rows.size)
        assertEquals("https://example.org/video", rows.single().url)
        assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/", paths.first())
        assertTrue(paths.any { it == "/novel/player/multi_video_model/v1" })
        assertTrue(paths.any { it == "/novel/player/video_model/v1/" })
        assertEquals("/novel/player/video_model/v1/", paths.last())
        assertTrue(signedVideoModel)
        assertTrue(signedVideoTab)
        assertEquals(1, fallbackSuccessCount)
    }

    @Test
    fun preloadCompatibilityRouteRunsOnlyAfterSecondaryRoutesAreExplicitlyEmpty() {
        val paths = mutableListOf<String>()
        val emptyCount = 1 + MobilePlayback.modelHosts().size + MobilePlayback.videoModelHosts().size
        val bodies = List(emptyCount) { "" } + model(typed = true, stringModel = true)
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
        assertEquals(emptyCount + 1, paths.size)
        assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/", paths.first())
        assertTrue(paths.contains("/novel/player/multi_video_model/v1"))
        assertEquals("/novel/player/multi_video_model/preload/v1", paths.last())
    }

    @Test
    fun temporaryFailureOnVideoTabStillAllowsOnePreloadAttempt() {
        val paths = mutableListOf<String>()
        val hosts = MobilePlayback.modelHosts().size
        val modelHosts = MobilePlayback.videoModelHosts().size
        // first video_tab base 503, second succeeds? No — all tab transient then primary/model empty → preload
        // three tab bases all 503 → primary hosts empty → video_model empty → preload ok
        val statuses = listOf(503, 503, 503) + List(hosts + modelHosts) { 200 } + listOf(200)
        val bodies = listOf("temporarily unavailable", "temporarily unavailable", "temporarily unavailable") +
            List(hosts + modelHosts) { "" } + model(typed = true)
        val ladder = MobilePlayback.ladder(
            "123",
            device,
            http(bodies, statuses) { request -> paths += request.url.encodedPath },
            passthroughSigner,
            1000,
        )
        assertEquals("https://example.org/video", ladder.renditions.single().url)
        assertTrue(paths.contains("/reading/bookapi/video_tab/multi_video_model/v1/"))
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
        assertEquals("/novel/player/multi_video_model/v1", paths.last())
    }

    @Test
    fun fetchesAnEpisodeBoundFollowupOnceBeforeMovingToAnotherRoute() {
        val followupUrl = "https://${MobilePlayback.modelHosts().first()}/video/model/v1/?v=1"
        val stub =
            """{"code":0,"data":{"video_model_data":{"123":{"video_model":{"video_id":"media-123","fallback_api":"$followupUrl"}}}}}"""
        val emptyFollowup = """{"video_info":{"data":{"video_id":"media-123","video_list":[]}}}"""
        val paths = mutableListOf<String>()
        // After empty followup, video_tab retries its alt host — keep that empty too, then official v1.
        val ladder = MobilePlayback.ladder(
            "123", device,
            http(listOf(stub, emptyFollowup, "", model(typed = true))) { paths += it.url.encodedPath },
            passthroughSigner, 1000,
        )
        assertEquals("https://example.org/video", ladder.renditions.single().url)
        assertEquals(
            listOf(
                "/reading/bookapi/video_tab/multi_video_model/v1/",
                "/video/model/v1/",
                "/reading/bookapi/video_tab/video_model/v1/",
                "/novel/player/multi_video_model/v1",
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
            Triple(PlaybackRoutePreference.PRIMARY, "/novel/player/multi_video_model/v1", """{"1004":$entries}"""),
            Triple(
                PlaybackRoutePreference.VIDEO_TAB,
                "/reading/bookapi/video_tab/multi_video_model/v1/",
                """{"video_model_data":$entries}""",
            ),
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
            http(listOf(stub, model(typed = true))) { paths += it.url.encodedPath },
            passthroughSigner, 1000, PlaybackRoutePreference.VIDEO_TAB,
        )
        assertEquals(
            listOf(
                "/reading/bookapi/video_tab/multi_video_model/v1/",
                "/reading/bookapi/video_tab/video_model/v1/",
                "/reading/bookapi/video_tab/multi_video_model/v1/",
                "/novel/player/multi_video_model/v1",
            ),
            paths,
        )
        assertEquals("https://example.org/video", ladder.renditions.single().url)
    }

    @Test
    fun laterEmptyResponsesDoNotHideAnEarlierServiceFailure() {
        // All video_tab bases must see 503; an empty alt host would mask the service failure.
        val afterTab = MobilePlayback.modelHosts().size + MobilePlayback.videoModelHosts().size + 1
        val paths = mutableListOf<String>()
        val statuses = listOf(503, 503, 503) + List(afterTab) { 200 }
        val bodies = listOf(
            "temporarily unavailable",
            "temporarily unavailable",
            "temporarily unavailable",
        ) + List(afterTab) { "" }
        val error = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(bodies, statuses) { paths += it.url.encodedPath },
                passthroughSigner,
                1000,
            )
        }
        assertEquals(PlaybackSourceException.Reason.TEMPORARY_UNAVAILABLE, error.reason)
        assertEquals(503, error.upstreamCode)
        assertEquals(3 + afterTab, paths.size)
        val empty = error.suppressed.single() as EmptyPlaybackResponseException
        assertTrue(empty.allCompatibilityModelsEmpty)
        assertTrue(empty.detail.orEmpty().contains("stage=preload"))
    }

    @Test
    fun laterEmptyResponsesDoNotHideAnEarlierSocketTimeout() {
        val afterTab = MobilePlayback.modelHosts().size + MobilePlayback.videoModelHosts().size + 1
        var attempts = 0
        val error = assertThrows(SocketTimeoutException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(List(3 + afterTab) { "" }) {
                    if (++attempts <= 3) throw SocketTimeoutException("upstream read timeout")
                },
                passthroughSigner,
                1000,
            )
        }
        assertEquals(3 + afterTab, attempts)
        assertTrue((error.suppressed.single() as EmptyPlaybackResponseException).allCompatibilityModelsEmpty)
    }

    @Test(timeout = 8000)
    fun aHungVideoTabCallIsCancelledAndOfficialV1GetsTheNextAttempt() {
        val requests = mutableListOf<String>()
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().method
            paths += chain.request().url.encodedPath
            if (requests.size == 1) {
                val stopWaitingAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!chain.call().isCanceled() && System.nanoTime() < stopWaitingAt) {
                    Thread.sleep(10)
                }
                assertTrue("route deadline did not cancel the stalled call", chain.call().isCanceled())
                throw IOException("Canceled")
            }
            httpResponse(chain.request(), model(typed = true))
        }.build()
        val result = RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(12)) {
            MobilePlayback.ladder("123", device, client, passthroughSigner, 1000)
        }
        assertEquals(listOf("GET", "POST"), requests)
        assertEquals("/novel/player/multi_video_model/v1", paths.last())
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
                "123", device, http(listOf(model(typed = true))) { hosts += it.url.host },
                passthroughSigner, 1000, PlaybackRoutePreference.PRIMARY_A,
            )
        }
        assertEquals(listOf(MobilePlayback.modelHosts()[1]), hosts)
    }

    @Test
    fun cancellingPlaybackDoesNotStartAnAlternateRequest() {
        val scope = RequestScope()
        var attempts = 0
        val client = http(listOf(model(videoTab = true))) {
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
                "123", device, http(listOf(model(typed = true))) { hosts += it.url.host },
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
            "123", device, http(listOf("", model(typed = true)), listOf(404, 200)) {
                preload += it.url.encodedPath
            },
            passthroughSigner, 1000, PlaybackRoutePreference.PRELOAD,
        )
        assertEquals(
            listOf("/novel/player/multi_video_model/preload/v1", "/novel/player/multi_video_model/v1"),
            preload,
        )

        val videoModel = mutableListOf<String>()
        MobilePlayback.ladder(
            "123", device, http(listOf(model())) { videoModel += it.url.encodedPath },
            passthroughSigner, 1000, PlaybackRoutePreference.VIDEO_MODEL,
        )
        assertEquals(listOf("/novel/player/video_model/v1/"), videoModel)
    }

    @Test
    fun temporaryFailureOnVideoTabStillTriesOfficialPosts() {
        val methods = mutableListOf<String>()
        val result = MobilePlayback.ladder(
            "123",
            device,
            http(
                listOf(
                    "temporarily unavailable",
                    "temporarily unavailable",
                    "temporarily unavailable",
                    model(typed = true),
                ),
                listOf(503, 503, 503, 200),
            ) {
                methods += it.method
            },
            passthroughSigner,
            1000,
        )
        assertEquals(listOf("GET", "GET", "GET", "POST"), methods)
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
            // Only NO_PLAYABLE_SOURCE retries video_tab alt bases; 204/404/blank stop the tab route.
            val retryTab = outcome == "no_playable_source"
            val bodies = if (retryTab) {
                listOf(body, body, body, model(typed = true))
            } else {
                listOf(body, model(typed = true))
            }
            val statuses = if (retryTab) listOf(status, status, status, 200) else listOf(status, 200)
            val ladder = MobilePlayback.ladder(
                "123",
                device,
                http(bodies, statuses) { methods += it.method },
                passthroughSigner,
                1000,
                onAttempt = { reports += it },
            )
            assertEquals("https://example.org/video", ladder.renditions.single().url)
            if (retryTab) {
                assertEquals(listOf("GET", "GET", "GET", "POST"), methods)
            } else {
                assertEquals(listOf("GET", "POST"), methods)
            }
            assertEquals(listOf("video-tab", "primary-a"), reports.map { it.route })
            assertEquals(listOf(outcome, "ok"), reports.map { it.outcome })
            assertEquals(if (outcome == "no_playable_source") null else status, reports.first().code)
        }
    }

    @Test
    fun allNoContentStatusesDoNotMasqueradeAsEmpty200IdentityFailure() {
        val attempts = 1 + MobilePlayback.modelHosts().size + MobilePlayback.videoModelHosts().size + 1
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
        val expectedRoutes = listOf(
            "video-tab", "primary-a", "primary-b", "primary-hl", "primary-lf", "video-model", "preload",
        )
        val empties = List(MobilePlayback.modelHosts().size + MobilePlayback.videoModelHosts().size + 1) { "" }
        for ((videoTabBody, issue) in cases) {
            val reports = mutableListOf<PlaybackRouteAttempt>()
            val error = assertThrows(PlaybackSourceException::class.java) {
                MobilePlayback.ladder(
                    "123",
                    device,
                    // All video_tab bases must see the unusable model (alt bases are retried).
                    http(listOf(videoTabBody, videoTabBody, videoTabBody) + empties),
                    passthroughSigner,
                    1000,
                    onAttempt = { reports += it },
                )
            }
            assertEquals(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE, error.reason)
            assertEquals(issue, error.modelIssue)
            assertEquals(expectedRoutes, reports.map { it.route })
            assertEquals(
                listOf("no_playable_source", "empty", "empty", "empty", "empty", "empty", "empty"),
                reports.map { it.outcome },
            )
            assertEquals(issue, reports.single { it.route == "video-tab" }.modelIssue)
            assertEquals(6, reports.count { it.outcome == "empty" && it.code == 200 && it.modelIssue == null })
            assertTrue((error.suppressed.single() as EmptyPlaybackResponseException).allCompatibilityModelsEmpty)
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
                    preference = if (videoTab) PlaybackRoutePreference.VIDEO_TAB else PlaybackRoutePreference.PRIMARY,
                )
            }
            assertEquals(1, requests.size)
            assertEquals(PlaybackSourceException.Reason.REJECTED, error.reason)
            assertEquals(110001, error.upstreamCode)
        }
    }

    @Test
    fun modelHostsPreferOfficialAThenFallbacks() {
        val hosts = MobilePlayback.modelHosts()
        assertEquals("api5-normal-sinfonlinea.fqnovel.com", hosts.first())
        assertTrue(hosts.contains("api5-normal-sinfonlineb.fqnovel.com"))
        assertEquals(hosts.distinct(), hosts)
    }

    @Test
    fun realSignedReplacesPlaceholderHeadersWithPlayerSigner() {
        val signed = MobilePlayback.realSigned(
            "https://example.org/path?aid=8662&_rticket=1000000",
            mapOf(
                "User-Agent" to "ua",
                "x-helios" to "placeholder",
                "x-medusa" to "placeholder",
                "x-argus" to "old",
            ),
            null,
        )
        assertEquals("ua", signed.headers["User-Agent"])
        assertNull(signed.headers["x-helios"])
        assertNull(signed.headers["x-medusa"])
        assertTrue(signed.headers["x-argus"].orEmpty().isNotEmpty())
        assertTrue(signed.headers["x-ladon"].orEmpty().isNotEmpty())
        assertTrue(signed.headers.containsKey("x-khronos"))
        assertTrue(signed.headers.containsKey("x-ss-req-ticket"))
    }
}
