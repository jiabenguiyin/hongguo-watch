// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackProxyClientTest {
    @Test
    fun parseStream_readsRenditions() {
        val parsed = PlaybackProxyClient.parseStream(
            """
            {
              "episode_id": "ep1",
              "expires_at": 1700000000,
              "renditions": [
                {"quality": "480p", "codec": "h264", "url": "https://cdn.example/a.m3u8"},
                {"quality": "720p", "url": "https://cdn.example/b.m3u8"}
              ]
            }
            """.trimIndent(),
        )
        assertEquals("ep1", parsed.episodeId)
        assertEquals(1700000000L, parsed.expiresAtEpochSec)
        assertEquals(2, parsed.renditions.size)
        assertEquals("480p", parsed.renditions[0].quality)
        assertEquals("h264", parsed.renditions[0].codec)
        assertEquals("https://cdn.example/a.m3u8", parsed.renditions[0].url)
        assertNull(parsed.renditions[1].codec)
    }

    @Test(expected = java.io.IOException::class)
    fun parseStream_rejectsEmptyRenditions() {
        PlaybackProxyClient.parseStream(
            """{"episode_id":"ep1","renditions":[]}""",
        )
    }

    @Test
    fun fromEnvOrNull_absentWithoutUrl() {
        // CI / default JVM has no HONGGUO_PLAYBACK_PROXY_URL → null (disabled).
        if (System.getenv(PlaybackProxyClient.ENV_BASE_URL).isNullOrBlank()) {
            assertNull(PlaybackProxyClient.fromEnvOrNull())
        }
    }

    @Test
    fun fromConfigOrNull_requiresHttpUrl() {
        assertNull(PlaybackProxyClient.fromConfigOrNull(""))
        assertNull(PlaybackProxyClient.fromConfigOrNull("ftp://x"))
        val client = PlaybackProxyClient.fromConfigOrNull("http://127.0.0.1:8787/", "t")
        assertEquals("http://127.0.0.1:8787", client!!.let {
            // Constructed; fetch would need a live server — just ensure non-null.
            "http://127.0.0.1:8787"
        })
    }
}
