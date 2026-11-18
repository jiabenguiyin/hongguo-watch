package com.hongguo.watch.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCacheLruTest {
    private fun entry(
        id: String,
        size: Long,
        access: Long,
    ): VideoCacheEntry = VideoCacheEntry(
        seriesId = "s$id",
        episodeId = "e$id",
        seriesTitle = "t",
        episodeNumber = 1,
        quality = "480p",
        codec = "h264",
        fileName = "$id.mp4",
        sizeBytes = size,
        lastAccessMs = access,
        ready = true,
    )

    @Test
    fun noEvictionWhenUnderBudget() {
        val entries = listOf(entry("a", 100, 1), entry("b", 100, 2))
        val victims = VideoCacheLru.pickVictims(
            entries = entries,
            usedBytes = 200,
            budgetBytes = 500,
            neededBytes = 100,
        )
        assertTrue(victims.isEmpty())
    }

    @Test
    fun evictsOldestFirst() {
        val a = entry("a", 100, access = 10)
        val b = entry("b", 100, access = 20)
        val c = entry("c", 100, access = 30)
        val victims = VideoCacheLru.pickVictims(
            entries = listOf(a, b, c),
            usedBytes = 300,
            budgetBytes = 250,
            neededBytes = 80,
        )
        // target = 250-80 = 170 → need to drop until used<=170 → drop a then b
        assertEquals(listOf(a, b), victims)
    }

    @Test
    fun totalReadyBytesIgnoresIncomplete() {
        val ready = entry("a", 40, 1)
        val pending = ready.copy(seriesId = "s2", episodeId = "e2", fileName = "x.mp4", ready = false)
        assertEquals(40L, VideoCacheLru.totalReadyBytes(listOf(ready, pending)))
    }

}
