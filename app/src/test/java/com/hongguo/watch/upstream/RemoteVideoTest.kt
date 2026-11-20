// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteVideoTest {
    private val media = byteArrayOf(1, 2, 3, 4)
    private val officialUrl = "https://v1.qznovelvod.com/media"

    @Test
    fun partialResponseWithMissingMalformedOrInvalidRangeHasTypedFailure() {
        val invalidHeaders = listOf(
            null,
            "bytes 0-3/*",
            "bytes 0-3/0",
            "bytes 1-3/4",
            "bytes 0-2/4",
            "bytes 0-3/9223372036854775808",
            "bytes 9223372036854775808-3/4",
            "bytes 0-9223372036854775808/4",
        )
        for (header in invalidHeaders) {
            val client = fixture { response(it, 206, header) }
            RemoteVideo(client, stream()).use {
                assertThrows("Content-Range: $header", MediaRangeFormatException::class.java) { it.prepare() }
            }
        }
    }

    @Test
    fun nonPartialResponseRetainsHttpStatus() {
        for (status in listOf(200, 403, 404, 429)) {
            val client = fixture { response(it, status) }
            RemoteVideo(client, stream()).use {
                val error = assertThrows(MediaRangeHttpException::class.java) { it.prepare() }
                assertEquals(status, error.statusCode)
            }
        }
    }

    @Test
    fun deliberateMediaRejectionCarriesToThePlayerAndStopsFurtherRangeCalls() {
        for (status in listOf(401, 403, 429)) {
            var requests = 0
            val client = fixture { request ->
                if (++requests == 1) response(request, 206, "bytes 0-3/4") else response(request, status)
            }
            RemoteVideo(client, stream()).use { video ->
                video.prepare()
                assertThrows(MediaRangeHttpException::class.java) { video.openRead(2, 2).close() }
                assertEquals(status, video.upstreamRejection()?.statusCode)
                assertThrows(MediaRangeHttpException::class.java) { video.openRead(2, 2).close() }
            }
            assertEquals(2, requests)
        }
    }

    @Test
    fun rejectsChangedTotalWhenOpeningLaterRange() {
        val ranges = mutableListOf<String?>()
        val client = fixture { request ->
            ranges += request.header("Range")
            if (ranges.size == 1) response(request, 206, "bytes 0-3/4")
            else response(request, 206, "bytes 2-3/5")
        }
        RemoteVideo(client, stream()).use { video ->
            video.prepare()
            assertEquals(4L, video.total)
            assertArrayEquals(byteArrayOf(2, 3), video.read(1, 2))
            assertThrows(MediaRangeFormatException::class.java) { video.openRead(2, 2).close() }
        }
        assertEquals(listOf("bytes=0-262143", "bytes=2-3"), ranges)
    }

    @Test
    fun backupRejectsUnapprovedHostBeforeRequest() {
        val url = "https://not-qznovelvod.com.invalid/media?token=fixture-token"
        val client = fixture { throw AssertionError("Rejected backup reached the HTTP client") }
        val error = assertThrows(IOException::class.java) {
            RemoteVideo(client, stream(url, isBackup = true)).use { it.prepare() }
        }
        assertFalse(error.message.orEmpty().contains("fixture-token"))
    }

    @Test
    fun backupDoesNotFollowRedirectWhilePrimaryStillDoes() {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/media"))
            }
            server.enqueue(
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/4")
                    .setBody(Buffer().write(media)),
            )
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val loopbackUrl = server.url("/redirect")
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    // RemoteVideo validates the original URL before OkHttp. Rewrite it here so
                    // OkHttp's own redirect handling runs against a local server only.
                    assertEquals(officialUrl, chain.request().url.toString())
                    chain.proceed(chain.request().newBuilder().url(loopbackUrl).build())
                }
                .build()

            RemoteVideo(client, stream(isBackup = true)).use {
                val error = assertThrows(MediaRangeHttpException::class.java) { it.prepare() }
                assertEquals(302, error.statusCode)
            }
            assertEquals(1, server.requestCount)
            RemoteVideo(client, stream()).use {
                assertArrayEquals(media, it.prepare().read(0, media.size))
            }
            assertEquals(3, server.requestCount)
        }
    }

    private fun stream(url: String = officialUrl, isBackup: Boolean = false): StreamInfo =
        StreamInfo(url, key = null, quality = "480p", isCdnBackup = isBackup)

    private fun fixture(respond: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()

    private fun response(request: Request, status: Int, range: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .apply { if (range != null) header("Content-Range", range) }
            .body(media.toResponseBody())
            .build()
}
