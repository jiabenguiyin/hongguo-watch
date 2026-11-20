package com.hongguo.watch.upstream

import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
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
    fun emptyPrimaryHostFailsOverToNextHost() {
        val hosts = mutableListOf<String>()
        val rows = MobilePlayback.ladder(
            "123",
            device,
            http(listOf("", model())) { request ->
                hosts += request.url.host
            },
            passthroughSigner,
            1000,
        ).renditions
        assertEquals(1, rows.size)
        assertEquals("https://example.org/video", rows.single().url)
        assertTrue(hosts.size >= 2)
        assertEquals(MobilePlayback.modelHosts().take(hosts.size), hosts)
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

        for (status in listOf(403, 429)) {
            val hosts = mutableListOf<String>()
            val error = assertThrows(PlaybackSourceException::class.java) {
                MobilePlayback.ladder(
                    "123",
                    device,
                    http(listOf("rejected"), listOf(status)) { request -> hosts += request.url.host },
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
    fun emptyLegacyHostsFallbackToSignedVideoTabRequest() {
        val hosts = mutableListOf<String>()
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
                http(List(MobilePlayback.modelHosts().size) { "" } + model(stringModel = true, videoTab = true)) { request ->
                    hosts += request.url.encodedPath
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
        assertEquals(MobilePlayback.modelHosts().size + 1, hosts.size)
        assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/", hosts.last())
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
        assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/", paths[MobilePlayback.modelHosts().size])
        assertEquals("/novel/player/multi_video_model/preload/v1", paths.last())
    }

    @Test
    fun fallbackServerFailureIsSurfacedWithoutTryingMoreRoutes() {
        val paths = mutableListOf<String>()
        val statuses = List(MobilePlayback.modelHosts().size) { 200 } + 503
        val bodies = List(MobilePlayback.modelHosts().size) { "" } + "temporarily unavailable"
        val error = assertThrows(PlaybackSourceException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(bodies, statuses) { request -> paths += request.url.encodedPath },
                passthroughSigner,
                1000,
            )
        }
        assertEquals(503, error.upstreamCode)
        assertEquals(MobilePlayback.modelHosts().size + 1, paths.size)
        assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/", paths.last())
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

    @Test
    fun noContentStatusAndMalformedBodyDoNotTriggerEmptyResponseFallback() {
        for ((status, body) in listOf(204 to "", 200 to " ")) {
            var attempts = 0
            val error = assertThrows(PlaybackSourceException::class.java) {
                MobilePlayback.ladder(
                    "123",
                    device,
                    http(listOf(body), listOf(status)) { attempts++ },
                    passthroughSigner,
                    1000,
                )
            }
            assertEquals(1, attempts)
            assertEquals(
                if (status == 204) PlaybackSourceException.Reason.HTTP_REJECTED
                else PlaybackSourceException.Reason.INVALID_RESPONSE,
                error.reason,
            )
        }
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
    fun modelHostsPreferOfficialBThenFallbacks() {
        val hosts = MobilePlayback.modelHosts()
        assertEquals("api5-normal-sinfonlineb.fqnovel.com", hosts.first())
        assertTrue(hosts.contains("api5-normal-sinfonlinea.fqnovel.com"))
        assertEquals(hosts.distinct(), hosts)
    }
}
