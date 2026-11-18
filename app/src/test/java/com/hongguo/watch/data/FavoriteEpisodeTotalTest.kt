package com.hongguo.watch.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteEpisodeTotalTest {
    @Test
    fun prefersLargerOfSeriesCountAndListSize() {
        assertEquals(80, ContentRepository.episodeTotal(80, 12))
        assertEquals(80, ContentRepository.episodeTotal(0, 80))
        assertEquals(80, ContentRepository.episodeTotal(80, 80))
    }

    @Test
    fun neverTreatsMissingAsNegative() {
        assertEquals(0, ContentRepository.episodeTotal(0, 0))
        assertEquals(0, ContentRepository.episodeTotal(-1, -5))
    }

    @Test
    fun historyProgressIndexMustNotMasqueradeAsTotal() {
        // 看到第 3 集 ≠ 全 3 集；调用方应传详情总集数，而不是 episodeNumber
        val watchedEpisodeNumber = 3
        val realTotal = 86
        assertEquals(86, ContentRepository.episodeTotal(realTotal, realTotal))
        assertEquals(
            "progress index alone is not a total",
            false,
            watchedEpisodeNumber == ContentRepository.episodeTotal(realTotal, realTotal),
        )
    }
}
