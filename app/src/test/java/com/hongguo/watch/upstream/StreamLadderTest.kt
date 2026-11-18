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
    fun avcOnlyNeverReturnsBytevc1WhenAvcExistsAtLowerRung() {
        // 真机日志形态：720p 仅 bytevc1，480/360 有 AVC → avcOnly 必须降档拿 AVC
        val ladder = StreamLadder(
            listOf(
                r(720, "bytevc1"),
                r(480, "bytevc1"),
                r(480, "h264"),
                r(360, "avc1"),
            ),
        )
        val selected = ladder.select(720, avcOnly = true)
        assertNotNull(selected)
        assertTrue(selected!!.isAvc)
        assertFalse(CodecLabels.isHevc(selected.codec))
        assertEquals(480, selected.quality)
        assertEquals("h264", selected.codec)
    }

    @Test
    fun avcOnlyNeverReturnsBytevc1EvenIfOnlyHevcInCap() {
        val ladder = StreamLadder(
            listOf(
                r(720, "bytevc1"),
                r(1080, "h264"),
            ),
        )
        // 上限 720 内无 AVC → 取超过上限最近的 AVC（1080），仍绝非 bytevc1
        val selected = ladder.select(720, avcOnly = true)
        assertNotNull(selected)
        assertTrue(selected!!.isAvc)
        assertEquals(1080, selected.quality)
    }

    @Test
    fun avcOnlyWithOnlyBytevc1ReturnsNull() {
        val ladder = StreamLadder(
            listOf(
                r(360, "bytevc1"),
                r(480, "bytevc1"),
                r(720, "bytevc1"),
            ),
        )
        assertNull(ladder.select(720, avcOnly = true))
        assertNull(ladder.select(480, avcOnly = true))
    }

    @Test
    fun fallsBackToHevcWhenNoAvcAndPreferOnly() {
        val ladder = StreamLadder(listOf(r(480, "bytevc1"), r(360, "hevc")))
        val selected = ladder.select(480, preferAvc = true, avcOnly = false)
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

    @Test
    fun noPermanentHevcBanByPolicyFlag() {
        // 0.4.6：不再因 Unisoc/手表 alone 永久 avcOnly；IJK 与 Media3 初始均不硬禁 HEVC
        assertFalse(
            com.hongguo.watch.player.WatchDecodePolicy.shouldForceAvcOnlyInitially(
                kernel = com.hongguo.watch.player.PlayerKernel.IJK,
            ),
        )
        assertFalse(
            com.hongguo.watch.player.WatchDecodePolicy.shouldForceAvcOnlyInitially(
                kernel = com.hongguo.watch.player.PlayerKernel.MEDIA3,
            ),
        )
    }

    @Test
    fun fragileSocClampsQualityTo480() {
        val clamped = com.hongguo.watch.player.WatchDecodePolicy.effectiveMaxQuality(
            userMaxQuality = 720,
            fragileSoc = true,
            memClassMb = 192,
        )
        assertEquals(480, clamped)
        val untouched = com.hongguo.watch.player.WatchDecodePolicy.effectiveMaxQuality(
            userMaxQuality = 720,
            fragileSoc = false,
            memClassMb = 192,
        )
        assertEquals(720, untouched)
        val fragilePlentyRam = com.hongguo.watch.player.WatchDecodePolicy.effectiveMaxQuality(
            userMaxQuality = 1080,
            fragileSoc = true,
            memClassMb = 256,
        )
        assertEquals(1080, fragilePlentyRam)
    }
}
