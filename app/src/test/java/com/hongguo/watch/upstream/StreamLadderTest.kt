package com.hongguo.watch.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLadderTest {
    private fun r(q: Int, codec: String) =
        Rendition(q, codec, 854, 480, "https://example.test/$q-$codec", null)

    @Test
    fun preferAvcOverHevcAtSameQuality() {
        val ladder = StreamLadder(
            listOf(
                r(480, "bytevc1"),
                r(480, "h264"),
            ),
        )
        val selected = ladder.select(480, preferAvc = true)
        assertNotNull(selected)
        assertTrue(selected!!.isAvc)
        assertEquals(480, selected.quality)
    }

    @Test
    fun preferAvcEvenIfSlightlyLowerQuality() {
        val ladder = StreamLadder(
            listOf(
                r(720, "hevc"),
                r(480, "avc1"),
            ),
        )
        val selected = ladder.select(720, preferAvc = true)
        assertNotNull(selected)
        assertEquals("avc1", selected!!.codec)
        assertEquals(480, selected.quality)
    }

    @Test
    fun avcOnlyReturnsNullWhenMissing() {
        val ladder = StreamLadder(listOf(r(480, "bytevc1"), r(720, "h265")))
        assertNull(ladder.select(720, avcOnly = true))
    }

    @Test
    fun fallsBackToHevcWhenNoAvc() {
        val ladder = StreamLadder(listOf(r(480, "bytevc1"), r(360, "hevc")))
        val selected = ladder.select(480, preferAvc = true)
        assertNotNull(selected)
        assertTrue(selected!!.isHevc)
        assertEquals(480, selected.quality)
    }

    @Test
    fun codecLabelsRecognizeBytevc1AsHevc() {
        assertTrue(CodecLabels.isHevc("bytevc1"))
        assertTrue(CodecLabels.isHevc("hev1.1.6"))
        assertTrue(CodecLabels.isAvc("avc1.64001F"))
        assertFalse(CodecLabels.isAvc("bytevc1"))
    }

    @Test
    fun fragileSocDetectsUnisoc() {
        assertTrue(
            com.hongguo.watch.player.WatchDecodePolicy.isFragileSoc(
                manufacturer = "Unisoc",
                brand = "spreadtrum",
                hardware = "ums512",
                board = "w527",
                model = "CD12 Max",
            ),
        )
        assertFalse(
            com.hongguo.watch.player.WatchDecodePolicy.isFragileSoc(
                manufacturer = "Google",
                brand = "google",
                hardware = "ranchu",
                board = "goldfish",
                model = "sdk_gphone64_arm64",
            ),
        )
    }
}
