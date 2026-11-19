package com.hongguo.watch.upstream

import java.io.IOException
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

    private fun model(id: String = "123", typed: Boolean = false, stringModel: Boolean = false): String {
        val modelObj =
            """{"video_list":[{"video_meta":{"codec_type":"bytevc2","vwidth":720,"vheight":1280},"main_url":"https://example.org/unsupported"},{"video_meta":{"codec_type":"h264","vwidth":720,"vheight":1280},"main_url":"https://example.org/video"}]}"""
        val videoModel = if (stringModel) {
            "\"" + modelObj.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            modelObj
        }
        val entries = """{"$id":{"video_model":$videoModel}}"""
        val data = if (typed) """{"1004":$entries}""" else entries
        return """{"code":0,"data":$data}"""
    }

    private fun http(
        bodies: List<String>,
        inspect: (okhttp3.Request) -> Unit = {},
    ): OkHttpClient {
        val index = AtomicInteger(0)
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                inspect(chain.request())
                val body = bodies.getOrElse(index.getAndIncrement()) { bodies.last() }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
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
    fun allHostsEmptySurfacesAggregatedDiagnostic() {
        val hosts = MobilePlayback.modelHosts()
        val error = assertThrows(EmptyPlaybackResponseException::class.java) {
            MobilePlayback.ladder(
                "123",
                device,
                http(List(hosts.size) { "" }),
                passthroughSigner,
                1000,
            )
        }
        assertTrue(error.detail.orEmpty().contains("hosts="))
        assertTrue(error.allCompatibilityModelsEmpty)
    }

    @Test
    fun signsAndSendsIdenticalCompressedBytesWithMatchingIdentityAndDigest() {
        var signed: ByteArray? = null
        var url: String? = null
        val signing = MobilePlaybackSigner { target, bytes, identity ->
            assertEquals(device, identity)
            signed = bytes.copyOf()
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
