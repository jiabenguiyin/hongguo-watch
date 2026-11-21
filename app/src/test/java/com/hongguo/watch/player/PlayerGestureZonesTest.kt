package com.hongguo.watch.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureZonesTest {
    private val w = 372 // 典型方表：466 外宽 − 左右各 ~47 inset

    @Test
    fun contentWidth_stripsHorizontalPadding() {
        // 外框 466、左右各 47 → 可用 372；误用 466 会让「半宽」偏右
        assertEquals(372, PlayerGestureZones.contentWidth(466, 47, 47))
        assertEquals(1, PlayerGestureZones.contentWidth(80, 50, 50))
    }

    @Test
    fun landscape_vertical_splitsExactlyHalf() {
        val midLeft = PlayerGestureZones.classify(
            startXInContent = w / 2f - 1f,
            dx = 0f,
            dy = -40f,
            contentWidth = w,
            landscape = true,
        )
        val midRight = PlayerGestureZones.classify(
            startXInContent = w / 2f,
            dx = 0f,
            dy = -40f,
            contentWidth = w,
            landscape = true,
        )
        assertEquals(PlayerGestureZones.Mode.BRIGHTNESS, midLeft)
        assertEquals(PlayerGestureZones.Mode.VOLUME, midRight)
    }

    @Test
    fun landscape_horizontal_isSeekAcrossFullWidth() {
        assertEquals(
            PlayerGestureZones.Mode.SEEK,
            PlayerGestureZones.classify(20f, 50f, 5f, w, landscape = true),
        )
        assertEquals(
            PlayerGestureZones.Mode.SEEK,
            PlayerGestureZones.classify(w - 20f, -50f, 5f, w, landscape = true),
        )
    }

    @Test
    fun portrait_keepsThirdsEdges() {
        assertEquals(
            PlayerGestureZones.Mode.BRIGHTNESS,
            PlayerGestureZones.classify(w / 3f - 1f, 0f, -30f, w, landscape = false),
        )
        assertEquals(
            PlayerGestureZones.Mode.VOLUME,
            PlayerGestureZones.classify(w * 2f / 3f + 1f, 0f, -30f, w, landscape = false),
        )
    }

    @Test
    fun portrait_middleHorizontalPrefersSeek() {
        assertEquals(
            PlayerGestureZones.Mode.SEEK,
            PlayerGestureZones.classify(w / 2f, 40f, 10f, w, landscape = false),
        )
    }

    @Test
    fun zoomedVerticalSwipesAtTheSidesStillAdjust() {
        for (landscape in listOf(false, true)) {
            assertEquals(
                PlayerGestureZones.Mode.BRIGHTNESS,
                PlayerGestureZones.classify(w * 0.2f, 8f, -40f, w, landscape, zoomed = true),
            )
            assertEquals(
                PlayerGestureZones.Mode.VOLUME,
                PlayerGestureZones.classify(w * 0.8f, -6f, 40f, w, landscape, zoomed = true),
            )
        }
    }

    @Test
    fun zoomedMiddleAndDiagonalDragsPanWithoutAdjusting() {
        assertEquals(
            PlayerGestureZones.Mode.PAN,
            PlayerGestureZones.classify(w / 2f, 0f, 60f, w, landscape = false, zoomed = true),
        )
        assertEquals(
            PlayerGestureZones.Mode.PAN,
            PlayerGestureZones.classify(w * 0.2f, 42f, 48f, w, landscape = true, zoomed = true),
        )
        assertEquals(
            PlayerGestureZones.Mode.PAN,
            PlayerGestureZones.classify(w * 0.8f, -50f, 3f, w, landscape = false, zoomed = true),
        )
    }

    @Test
    fun bugRepro_outerWidthHalfLooksLikeSeventyThirty() {
        // 旧逻辑：用外框半宽 233 对比内容区坐标 → 亮度约占 233/372 ≈ 62.6%
        // 更大圆屏 inset 时会逼近体感 ~70%/30%
        val outer = 466
        val pad = 47
        val content = PlayerGestureZones.contentWidth(outer, pad, pad)
        val bogusHalf = outer / 2f
        val bogusBrightnessShare = bogusHalf / content
        assertEquals(true, bogusBrightnessShare > 0.60f)

        val fixed = PlayerGestureZones.classify(
            startXInContent = content / 2f - 0.5f,
            dx = 0f,
            dy = -20f,
            contentWidth = content,
            landscape = true,
        )
        assertEquals(PlayerGestureZones.Mode.BRIGHTNESS, fixed)
        assertEquals(
            PlayerGestureZones.Mode.VOLUME,
            PlayerGestureZones.classify(content / 2f, 0f, -20f, content, true),
        )
    }
}
