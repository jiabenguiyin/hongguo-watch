package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFilterSheetLayoutTest {
    @Test
    fun scrollViewport_leavesRoomAboveFixedActions() {
        // CD12 Max 量级：顶栏 44+、底栏 44+、四角安全边后，滚动区仍为正
        val viewport = HistoryFilterSheetLayout.scrollViewportHeightPx(
            screenHeightPx = 466,
            topChromePx = 48,
            bottomChromePx = 48,
            topSafePx = 28,
            bottomSafePx = 36,
        )
        assertEquals(306, viewport)
        assertTrue(viewport > 48) // 大于底栏，芯片可完整露出
    }

    @Test
    fun scrollViewport_neverNegativeWhenChromeOversized() {
        assertEquals(
            0,
            HistoryFilterSheetLayout.scrollViewportHeightPx(
                screenHeightPx = 200,
                topChromePx = 120,
                bottomChromePx = 120,
                topSafePx = 40,
                bottomSafePx = 40,
            ),
        )
    }
}
