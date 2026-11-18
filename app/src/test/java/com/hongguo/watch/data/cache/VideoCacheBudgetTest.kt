package com.hongguo.watch.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCacheBudgetTest {
    private val mb = 1024L * 1024L
    private val gb = 1024L * mb

    @Test
    fun largerThanOldFixed512OnTypicalWatchFree() {
        // 16GB 表，约 4GB 空闲，无已缓存 → 预算应明显高于旧固定 512MB
        val budget = VideoCacheBudget.compute(
            freeBytes = 4 * gb,
            totalBytes = 16 * gb,
            usedCacheBytes = 0,
        )
        assertTrue("budget=$budget", budget > 512 * mb)
        assertTrue("budget=$budget", budget <= 8 * gb)
    }

    @Test
    fun lowFreeShrinksBelowFloorWhenUsableTight() {
        // 头寸 max(128MB, 5% of 8GB=400MB)=400MB；usable=300+50-400=0 → 0
        val budget = VideoCacheBudget.compute(
            freeBytes = 300 * mb,
            totalBytes = 8 * gb,
            usedCacheBytes = 50 * mb,
        )
        assertEquals(0L, budget)
    }

    @Test
    fun includesUsedCacheInUsable() {
        // 若不把 used 加回，free 变小会虚压预算；加回后应接近「清空前」容量
        val withUsed = VideoCacheBudget.compute(
            freeBytes = 2 * gb,
            totalBytes = 16 * gb,
            usedCacheBytes = 1 * gb,
        )
        val empty = VideoCacheBudget.compute(
            freeBytes = 3 * gb,
            totalBytes = 16 * gb,
            usedCacheBytes = 0,
        )
        assertEquals(empty, withUsed)
    }

    @Test
    fun neverExceedsUsable() {
        val free = 900 * mb
        val total = 4 * gb
        val used = 100 * mb
        val headroom = maxOf(128 * mb, (total * 0.05).toLong())
        val usable = (free + used - headroom).coerceAtLeast(0)
        val budget = VideoCacheBudget.compute(free, total, used)
        assertTrue(budget <= usable)
    }

    @Test
    fun capsAtCeiling() {
        val budget = VideoCacheBudget.compute(
            freeBytes = 64 * gb,
            totalBytes = 128 * gb,
            usedCacheBytes = 0,
        )
        assertEquals(VideoCacheBudget.CEILING_BYTES, budget)
    }
}
