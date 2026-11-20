package com.hongguo.watch.upstream

import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModelFollowupTest {
    private val originalId = "opaque-video-identity"
    private val url = "https://api5-normal-sinfonlineb.fqnovel.com/video/model/v1/?p=%2Fa%2Bb&p=2"
    private val mediaUrl = "https://v1.qznovelvod.com/episode.mp4"
    private val episodeId = "1234567890123456789"
    private fun fplayUrl(pathId: String) =
        "https://vas-lf-x.snssdk.com/video/fplay/1/1234ABcd5678ef00/$pathId?p=%2Fa%2Bb&p=2"

    private fun stub(address: Any = url) = JSONObject()
        .put("video_id", originalId)
        .put("video_duration", 15.0)
        .put("fallback_api", address)

    private fun result(videoId: Any? = originalId, duration: Double = 15.0, code: Int = 0): String {
        val data = JSONObject()
            .put("video_id", videoId)
            .put("video_duration", duration)
            .put("video_list", JSONArray().put(JSONObject()
                .put("main_url", mediaUrl)
                .put("codec_type", "h264")
                .put("quality_desc", "480p")))
        return JSONObject().put("video_info", JSONObject().put("code", code).put("data", data)).toString()
    }

    private fun reply(chain: Interceptor.Chain, code: Int = 200, text: String = result()): Response.Builder =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(text.toResponseBody())

    private fun read(
        model: JSONObject,
        interceptor: Interceptor,
        expectedEpisodeId: String? = null,
        claim: () -> Boolean = { true },
    ) = PlaybackModelFollowup.read(
        model,
        OkHttpClient.Builder().addInterceptor(interceptor).build(),
        System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
        episodeId = expectedEpisodeId,
        claimAttempt = claim,
    )

    @Test
    fun sendsOnePlainGetToTheExactSuppliedAddressWithinTheOuterDeadline() {
        var attempts = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        val parsed = RequestScope.withDeadline(deadline) {
            read(stub(JSONObject().put("fallback_api", url).toString()), { chain ->
                val request = chain.request()
                assertEquals("GET", request.method)
                assertEquals(url, request.url.toString())
                assertEquals(deadline, chain.call().timeout().deadlineNanoTime())
                assertNull(request.body)
                assertNull(request.header("Authorization"))
                assertNull(request.header("Cookie"))
                reply(chain).build()
            }) { ++attempts == 1 }
        }
        assertEquals(1, attempts)
        assertEquals(mediaUrl, parsed?.renditions?.single()?.url)
        assertTrue(requireNotNull(parsed).renditions.single().isCdnBackup)
    }

    @Test
    fun doesNotRequestUntrustedAddressesOrReplaceInlineRenditions() {
        var attempts = 0
        val blocked = listOf(
            stub(url.replace("https:", "http:")),
            stub(url.replace(".com/", ".com:8443/")),
            stub(url.replace("://", "://user@")),
            stub(url.replace("://", "://@")),
            stub(url.replace(".com/", ".com.example.test/")),
            stub(url.replace("/video/model/", "/video/other/../model/")),
            stub(url.replace("/video/model/", "/unrelated/path/")),
            stub("$url#fragment"),
            stub(JSONArray().put("https://localhost/").put(url)),
            stub().removeVideoIdentity(),
            stub().put("video_list", JSONArray().put(JSONObject().put("codec_type", "bytevc2"))),
            stub().put("video_list", JSONArray().put(JSONObject().put("main_url", "https://example.test/inline.mp4"))),
        )
        for (model in blocked) {
            assertNull(read(model, { chain -> reply(chain).build() }) { attempts++; true })
        }
        assertEquals(0, attempts)
        assertNull(read(stub(), { error("Follow-up budget already spent") }) { false })
    }

    @Test
    fun rejectsDifferentOrUnverifiableVideoIdentityAndUnexpectedDuration() {
        for (text in listOf(result("other-video"), result(null), result(42), result(duration = 10.0))) {
            assertNull(read(stub(JSONArray().put(url)), { chain -> reply(chain, text = text).build() }))
        }
    }

    @Test
    fun acceptsOnlyKnownFplayEnvelopesAndPreservesTheSuppliedAddress() {
        val model = JSONObject(result()).getJSONObject("video_info").getJSONObject("data")
        val wrappers = listOf(
            JSONObject().put("data", JSONObject().put("video_info", model)),
            JSONObject().put("video_info", model),
            JSONObject().put("data", model),
            JSONObject(result()),
            JSONObject().put("video_info", JSONObject().put("data", JSONObject()))
                .put("data", JSONObject().put("video_info", model)),
        )
        for ((index, response) in wrappers.withIndex()) {
            val address = fplayUrl(if (index == 0) originalId else episodeId)
            val parsed = read(stub(address), { chain ->
                assertEquals(address, chain.request().url.toString())
                reply(chain, text = response.toString()).build()
            }, expectedEpisodeId = episodeId)
            assertEquals(mediaUrl, parsed?.renditions?.single()?.url)
        }
    }

    @Test
    fun requiresFplayPathAndReplyIdentitiesToMatch() {
        val exact = fplayUrl(episodeId)
        val wrongAddresses = listOf(
            fplayUrl("other-episode"),
            exact.replace("vas-lf-x.snssdk.com/", "another.snssdk.com/"),
            exact.replace("fplay/1/", "fplay/2/"),
            exact.replace("1234ABcd5678ef00/", "1234ABcd5678ef0/"),
            exact.replace("1234ABcd5678ef00/", "x234ABcd5678ef00/"),
            exact.replace("$episodeId?", "$episodeId/extra?"),
            exact.replace("$episodeId?", "$episodeId/?"),
        )
        for (address in wrongAddresses) {
            assertNull(read(stub(address), { error("Invalid fplay path reached the transport") }, episodeId))
        }
        assertNull(read(stub(exact), { error("No matching episode identity") }))
        val wrongModel = JSONObject(result(videoId = episodeId))
            .getJSONObject("video_info")
            .getJSONObject("data")
        assertNull(read(stub(exact), { chain ->
            reply(chain, text = JSONObject().put("data", wrongModel).toString()).build()
        }, episodeId))
        // The extra wrappers do not loosen parsing on existing fqnovel endpoints.
        val unwrappedModel = JSONObject(result()).getJSONObject("video_info").getJSONObject("data")
        assertNull(read(stub(), { chain ->
            reply(chain, text = JSONObject().put("data", unwrappedModel).toString()).build()
        }))
    }

    @Test
    fun rejectsAmbiguousModelsAndUnapprovedMediaAddresses() {
        val info = JSONObject(result()).getJSONObject("video_info")
        val duplicated = JSONObject().put("video_info", info)
            .put("data", JSONObject().put("video_info", info.getJSONObject("data")))
        assertNull(read(stub(fplayUrl(originalId)), { chain ->
            reply(chain, text = duplicated.toString()).build()
        }))
        assertNull(read(stub(), { chain ->
            reply(chain, text = result().replace(mediaUrl, "http://localhost/")).build()
        }))
    }

    @Test
    fun reportsHttpAndBusinessRejectionsToTheCaller() {
        for (status in listOf(401, 403, 429)) {
            val error = assertThrows(PlaybackSourceException::class.java) {
                read(stub(), { chain -> reply(chain, code = status).build() })
            }
            assertEquals(PlaybackSourceException.Reason.HTTP_REJECTED, error.reason)
            assertEquals(status, error.upstreamCode)
        }
        val error = assertThrows(PlaybackSourceException::class.java) {
            read(stub(), { chain -> reply(chain, text = result(code = 110001)).build() })
        }
        assertEquals(PlaybackSourceException.Reason.REJECTED, error.reason)
        assertEquals(110001, error.upstreamCode)
        val fplayResultWithDeniedNestedInfo = JSONObject()
            .put("code", "unknown")
            .put("data", JSONObject().put("video_info", JSONObject().put("Code", 110001)))
            .toString()
        val fplayError = assertThrows(PlaybackSourceException::class.java) {
            read(stub(fplayUrl(originalId)), { chain ->
                reply(chain, text = fplayResultWithDeniedNestedInfo).build()
            })
        }
        assertEquals(PlaybackSourceException.Reason.REJECTED, fplayError.reason)
        assertEquals(110001, fplayError.upstreamCode)
    }

    @Test
    fun stopsOnRedirectAndOnAnOversizeBodyWithoutAContentLength() {
        assertNull(read(stub(), { chain ->
            reply(chain, code = 302).header("Location", url).build()
        }))
        assertNull(read(stub(), { chain ->
            val oversize = Buffer().writeUtf8(result() + " ".repeat(256 * 1024))
            reply(chain).body(oversize.asResponseBody()).build()
        }))
    }

    @Test
    fun aRetryAfterResponseDoesNotReplayTheFollowup() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody(result()))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val transport = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).addInterceptor { chain ->
                assertEquals(url, chain.request().url.toString())
                val localRequest = chain.request().newBuilder().url(server.url("/")).build()
                chain.proceed(localRequest)
            }.build()
            val error = assertThrows(PlaybackSourceException::class.java) {
                PlaybackModelFollowup.read(
                    stub(), transport, System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
                    episodeId = episodeId, claimAttempt = { true },
                )
            }
            assertEquals(PlaybackSourceException.Reason.TEMPORARY_UNAVAILABLE, error.reason)
            assertEquals(503, error.upstreamCode)
            assertEquals(1, server.requestCount)
        }
    }

    private fun JSONObject.removeVideoIdentity(): JSONObject = apply { remove("video_id") }
}
