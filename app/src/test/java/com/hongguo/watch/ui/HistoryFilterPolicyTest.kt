package com.hongguo.watch.ui

import com.hongguo.watch.data.db.WatchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HistoryFilterPolicyTest {
    private fun item(
        id: String = "1",
        episode: Int = 1,
        positionMs: Long = 0L,
        durationMs: Long = 60_000L,
        updatedAt: Long = 1_000L,
    ) = WatchHistoryEntity(
        seriesId = id,
        seriesTitle = "t",
        cover = "",
        episodeId = "e1",
        episodeNumber = episode,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
        source = "APP",
    )

    @Test
    fun estimatedWatchedMsUsesPriorEpisodesPlusPosition() {
        val row = item(episode = 3, positionMs = 10_000L, durationMs = 60_000L)
        assertEquals(130_000L, HistoryFilterPolicy.estimatedWatchedMs(row))
    }

    @Test
    fun coverProgressBlendsEpisodeAndIntra() {
        val row = item(episode = 2, positionMs = 30_000L, durationMs = 60_000L)
        // (1 + 0.5) / 10 = 15%
        assertEquals(15, HistoryFilterPolicy.coverProgressPercent(row, totalEpisodes = 10))
    }

    @Test
    fun coverProgressFallsBackToEpisodePosition() {
        val row = item(episode = 0, positionMs = 25_000L, durationMs = 100_000L)
        assertEquals(25, HistoryFilterPolicy.coverProgressPercent(row, totalEpisodes = null))
        assertNull(
            HistoryFilterPolicy.coverProgressPercent(
                item(episode = 0, positionMs = 0L, durationMs = 0L),
                totalEpisodes = null,
            ),
        )
    }

    @Test
    fun completedRequiresLastEpisodeNearEnd() {
        val last = item(episode = 10, positionMs = 58_000L, durationMs = 60_000L)
        assertTrue(HistoryFilterPolicy.isCompleted(last, totalEpisodes = 10))
        val midLast = item(episode = 10, positionMs = 10_000L, durationMs = 60_000L)
        assertFalse(HistoryFilterPolicy.isCompleted(midLast, totalEpisodes = 10))
        val early = item(episode = 3, positionMs = 59_000L, durationMs = 60_000L)
        assertFalse(HistoryFilterPolicy.isCompleted(early, totalEpisodes = 10))
        assertFalse(HistoryFilterPolicy.isCompleted(last, totalEpisodes = null))
    }

    @Test
    fun durationAndEpisodeFilters() {
        val rows = listOf(
            item(id = "a", episode = 1, positionMs = 3_000L, durationMs = 60_000L),
            item(id = "b", episode = 12, positionMs = 0L, durationMs = 60_000L),
        )
        val over5s = HistoryFilterPolicy.Criteria(
            duration = HistoryFilterPolicy.DurationMin.OVER_5_SEC,
        )
        assertEquals(
            listOf("b"),
            HistoryFilterPolicy.filter(rows, emptyMap(), over5s).map { it.seriesId },
        )
        val ep10 = HistoryFilterPolicy.Criteria(
            episodes = HistoryFilterPolicy.EpisodesMin.OVER_10,
        )
        assertEquals(
            listOf("b"),
            HistoryFilterPolicy.filter(rows, emptyMap(), ep10).map { it.seriesId },
        )
    }

    @Test
    fun timeAndCompleteFilters() {
        val now = 1_700_000_000_000L
        val today = item(id = "today", updatedAt = now - 1_000L)
        val old = item(
            id = "old",
            episode = 20,
            positionMs = 59_000L,
            durationMs = 60_000L,
            updatedAt = now - TimeUnit.DAYS.toMillis(120),
        )
        val totals = mapOf("old" to 20, "today" to 40)
        val earlier = HistoryFilterPolicy.Criteria(
            time = HistoryFilterPolicy.TimeRange.EARLIER,
        )
        assertEquals(
            listOf("old"),
            HistoryFilterPolicy.filter(listOf(today, old), totals, earlier, nowMs = now)
                .map { it.seriesId },
        )
        val done = HistoryFilterPolicy.Criteria(
            complete = HistoryFilterPolicy.CompleteState.COMPLETED,
        )
        assertEquals(
            listOf("old"),
            HistoryFilterPolicy.filter(listOf(today, old), totals, done, nowMs = now)
                .map { it.seriesId },
        )
        val notDone = HistoryFilterPolicy.Criteria(
            complete = HistoryFilterPolicy.CompleteState.NOT_COMPLETED,
        )
        assertEquals(
            listOf("today"),
            HistoryFilterPolicy.filter(listOf(today, old), totals, notDone, nowMs = now)
                .map { it.seriesId },
        )
    }

    @Test
    fun completeFilterNeedsTotalsOtherwiseTreatsAsNotDone() {
        val finished = item(id = "fin", episode = 130, positionMs = 59_000L, durationMs = 60_000L)
        val mid = item(id = "mid", episode = 66, positionMs = 10_000L, durationMs = 60_000L)
        val done = HistoryFilterPolicy.Criteria(
            complete = HistoryFilterPolicy.CompleteState.COMPLETED,
        )
        // 无总集数时无法判定已看完 → 「已看完」为空
        assertEquals(
            emptyList<String>(),
            HistoryFilterPolicy.filter(listOf(finished, mid), emptyMap(), done)
                .map { it.seriesId },
        )
        // 有总集数后「已看完」只保留末集近片尾
        assertEquals(
            listOf("fin"),
            HistoryFilterPolicy.filter(
                listOf(finished, mid),
                mapOf("fin" to 130, "mid" to 200),
                done,
            ).map { it.seriesId },
        )
        val notDone = HistoryFilterPolicy.Criteria(
            complete = HistoryFilterPolicy.CompleteState.NOT_COMPLETED,
        )
        assertEquals(
            listOf("mid"),
            HistoryFilterPolicy.filter(
                listOf(finished, mid),
                mapOf("fin" to 130, "mid" to 200),
                notDone,
            ).map { it.seriesId },
        )
    }

    @Test
    fun clearingCompleteCriteriaReturnsAll() {
        val rows = listOf(
            item(id = "a", episode = 10, positionMs = 59_000L, durationMs = 60_000L),
            item(id = "b", episode = 1),
        )
        val totals = mapOf("a" to 10, "b" to 40)
        val filtered = HistoryFilterPolicy.filter(
            rows,
            totals,
            HistoryFilterPolicy.Criteria(complete = HistoryFilterPolicy.CompleteState.COMPLETED),
        )
        assertEquals(listOf("a"), filtered.map { it.seriesId })
        assertEquals(
            listOf("a", "b"),
            HistoryFilterPolicy.filter(rows, totals, HistoryFilterPolicy.Criteria())
                .map { it.seriesId },
        )
    }
}
