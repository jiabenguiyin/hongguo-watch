package com.hongguo.watch.data.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCacheSpaceSummaryTest {
    @Test
    fun usedPercentOfBudget() {
        val summary = VideoCacheSpaceSummary(
            usedBytes = 256L * 1024 * 1024,
            budgetBytes = 512L * 1024 * 1024,
            freeBytes = 1_000_000_000,
            entryCount = 3,
        )
        assertEquals(50, summary.usedPercentOfBudget)
    }

    @Test
    fun usedPercentClampedWhenOverBudget() {
        val summary = VideoCacheSpaceSummary(
            usedBytes = 800,
            budgetBytes = 100,
            freeBytes = 0,
            entryCount = 1,
        )
        assertEquals(100, summary.usedPercentOfBudget)
    }

    @Test
    fun usedPercentZeroBudget() {
        val summary = VideoCacheSpaceSummary(10, 0, 0, 0)
        assertEquals(0, summary.usedPercentOfBudget)
    }
}
