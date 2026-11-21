package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerStartEpisodeTest {
    private val ids = listOf("first", "fourth", "eighth")
    private val numbers = listOf(1, 4, 8)

    @Test
    fun idTakesPriorityOverRecordedNumber() {
        assertEquals(InitialEpisode(0, 2_500), selectInitialEpisode(ids, numbers, "first", 4, 2_500))
    }

    @Test
    fun resumeUsesActualEpisodeNumberWithStaleOrMissingId() {
        assertEquals(InitialEpisode(1, 2_500), selectInitialEpisode(ids, numbers, "old-id", 4, 2_500))
        assertEquals(InitialEpisode(2, 2_500), selectInitialEpisode(ids, numbers, null, 8, 2_500))
    }

    @Test
    fun unavailableEpisodeFallsBackWithoutReusingItsPosition() {
        assertEquals(InitialEpisode(0, 0), selectInitialEpisode(ids, numbers, "old-id", 2, 2_500))
        assertEquals(InitialEpisode(1, 0), selectInitialEpisode(listOf("", "fourth"), listOf(1), null, 1, 2_500))
        assertNull(selectInitialEpisode(emptyList(), listOf(8), null, 8, 2_500))
    }
}
