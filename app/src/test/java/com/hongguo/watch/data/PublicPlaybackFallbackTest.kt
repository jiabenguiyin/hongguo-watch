package com.hongguo.watch.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PublicPlaybackFallbackTest {
    @Test
    fun readsEpisodeIdentityFromPublicJsonLd() {
        val html = """
            <script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[
              {"@type":"TVSeason","@id":"https://hongguoduanju.com/detail?series_id=1234567890123456789#season",
               "episode":[
                 {"@type":"TVEpisode","episodeNumber":2,
                  "@id":"https://hongguoduanju.com/player/1234567890123456789/9876543210987654321#episode",
                  "url":"https://hongguoduanju.com/player/1234567890123456789/9876543210987654321",
                  "partOfSeason":{"@id":"https://hongguoduanju.com/detail?series_id=1234567890123456789#season"}}
               ]}
            ]}
            </script>
        """.trimIndent()

        assertEquals(
            2,
            PublicPlaybackFallback.metadataEpisodeNumber(
                html,
                "1234567890123456789",
                "9876543210987654321",
            ),
        )
        assertNull(
            PublicPlaybackFallback.metadataEpisodeNumber(
                html,
                "1234567890123456789",
                "1111111111111111111",
            ),
        )
    }

    @Test
    fun acceptsOnlyMp4HeaderWithExactRangeSize() {
        val bytes = ByteArray(32)
        bytes[4] = 'f'.code.toByte()
        bytes[5] = 't'.code.toByte()
        bytes[6] = 'y'.code.toByte()
        bytes[7] = 'p'.code.toByte()
        assertEquals(true, PublicPlaybackFallback.isMp4Header(bytes))
        assertEquals(false, PublicPlaybackFallback.isMp4Header(ByteArray(31)))
    }

    @Test
    fun resolvesOnlyAnIdentityMatchedEpisodeAndValidatedMp4Range() {
        val server = MockWebServer()
        server.start()
        try {
            val seriesId = "1234567890123456789"
            val episodeId = "9876543210987654321"
            val metadata = """
                <script type="application/ld+json">
                {"@context":"https://schema.org","@graph":[
                  {"@type":"TVSeason","@id":"https://hongguoduanju.com/detail?series_id=$seriesId#season",
                   "episode":[
                     {"@type":"TVEpisode","episodeNumber":2,
                      "@id":"https://hongguoduanju.com/player/$seriesId/$episodeId#episode",
                      "url":"https://hongguoduanju.com/player/$seriesId/$episodeId",
                      "partOfSeason":{"@id":"https://hongguoduanju.com/detail?series_id=$seriesId#season"}}
                   ]}
                ]}
                </script>
            """.trimIndent()
            val mp4Header = ByteArray(32).apply {
                this[4] = 'f'.code.toByte()
                this[5] = 't'.code.toByte()
                this[6] = 'y'.code.toByte()
                this[7] = 'p'.code.toByte()
            }
            server.enqueue(MockResponse().setResponseCode(200).setBody(metadata))
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes 0-31/671498")
                    .setBody(okio.Buffer().write(mp4Header)),
            )

            val result = PublicPlaybackFallback(
                baseUrl = server.url("/").toString().toHttpUrl(),
                client = OkHttpClient(),
            ).resolve(seriesId, episodeId)

            assertNotNull(result)
            assertEquals("360p", result?.quality)
            assertEquals(PublicPlaybackFallback.SOURCE_LABEL, result?.sourceLabel)
            assertEquals("/player/$seriesId/$episodeId", server.takeRequest().path)
            val mediaRequest = server.takeRequest()
            assertEquals("bytes=0-31", mediaRequest.getHeader("Range"))
            assertEquals("/video/public/$episodeId/360p.mp4", mediaRequest.path)
        } finally {
            server.shutdown()
        }
    }
}
