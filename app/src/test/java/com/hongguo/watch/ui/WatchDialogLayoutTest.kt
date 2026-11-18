package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDialogLayoutTest {
    // CD12 Max 量级：约 408×502 方屏大圆角
    private val cd12W = 408
    private val cd12H = 502
    private val cornerInset = (cd12W * 0.10f).toInt() // ~40

    @Test
    fun contentWidth_nearFullMinusCornerInsets() {
        val width = WatchDialogLayout.contentWidthPx(cd12W, cornerInset, minWidthPx = 200)
        assertEquals(cd12W - cornerInset * 2, width)
        // 明显宽于旧版 Material 默认窄弹窗（约屏宽 60–70%）
        assertTrue(width > (cd12W * 0.75f).toInt())
    }

    @Test
    fun contentWidth_respectsMinimum() {
        assertEquals(200, WatchDialogLayout.contentWidthPx(220, 40, minWidthPx = 200))
    }

    @Test
    fun notesMaxHeight_leavesRoomForActionStack() {
        val max = WatchDialogLayout.notesMaxHeightPx(
            screenHeightPx = cd12H,
            reservedPx = 220,
            minHeightPx = 72,
        )
        // 502 - 220 = 282，但上限 42%≈210
        assertEquals((cd12H * 0.42f).toInt(), max)
        assertTrue(max + 220 <= cd12H + 40) // 说明 + 预留仍塞进屏高附近
    }

    @Test
    fun notesMaxHeight_shortScreensUseMinimum() {
        assertEquals(
            72,
            WatchDialogLayout.notesMaxHeightPx(
                screenHeightPx = 200,
                reservedPx = 220,
                minHeightPx = 72,
            ),
        )
    }
}
