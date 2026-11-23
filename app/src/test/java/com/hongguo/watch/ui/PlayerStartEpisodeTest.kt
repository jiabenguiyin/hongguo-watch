package com.hongguo.watch.ui

import com.hongguo.watch.data.cache.VideoCacheEntry
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

    @Test
    fun offlineEpisodesAreReadyEntriesForTheSeriesInEpisodeOrder() {
        fun cached(seriesId: String, episodeId: String, number: Int, ready: Boolean = true) =
            VideoCacheEntry(
                seriesId = seriesId,
                episodeId = episodeId,
                seriesTitle = seriesId,
                episodeNumber = number,
                quality = "480p",
                codec = "h264",
                fileName = "$episodeId.mp4",
                sizeBytes = 100,
                lastAccessMs = 0,
                ready = ready,
            )

        val entries = listOf(
            cached("series", "ep-3", 3),
            cached("other", "ep-1", 1),
            cached("series", "ep-2", 2),
            cached("series", "ep-pending", 4, ready = false),
        )

        assertEquals(
            listOf("ep-2", "ep-3"),
            offlineCacheEpisodes(entries, "series").map { it.episodeId },
        )
    }
}
