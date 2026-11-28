package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFilterSheetLayoutTest {
    @Test
    fun expandedTopOffset_matchesLegacyEightyEightPercentSheet() {
        // 旧实现 sheet 高 = 0.88 * H → 顶留白 ≈ 0.12 * H
        assertEquals(60, HistoryFilterSheetLayout.expandedTopOffsetPx(500))
        assertEquals(48, HistoryFilterSheetLayout.expandedTopOffsetPx(408))
    }

    @Test
    fun expandedTopOffset_clampsTinyAndHugeScreens() {
        assertEquals(0, HistoryFilterSheetLayout.expandedTopOffsetPx(0))
        assertEquals(1, HistoryFilterSheetLayout.expandedTopOffsetPx(10))
        val tall = HistoryFilterSheetLayout.expandedTopOffsetPx(2000)
        assertTrue(tall <= 500) // ≤ 25%
        assertEquals(240, tall)
    }
}
