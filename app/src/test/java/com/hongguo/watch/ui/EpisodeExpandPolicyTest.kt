package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeExpandPolicyTest {
    @Test
    fun expandOnlyWhenLongEnough() {
        assertFalse(EpisodeExpandPolicy.shouldShowExpand(8))
        assertTrue(EpisodeExpandPolicy.shouldShowExpand(13))
        assertTrue(EpisodeExpandPolicy.shouldShowExpand(208))
    }

    @Test
    fun rangesSplitByFifty() {
        assertEquals(emptyList<IntRange>(), EpisodeExpandPolicy.ranges(0))
        assertEquals(listOf(1..20), EpisodeExpandPolicy.ranges(20))
        assertEquals(
            listOf(1..50, 51..100, 101..150, 151..200, 201..208),
            EpisodeExpandPolicy.ranges(208),
        )
    }

    @Test
    fun rangeIndexFollowsEpisodeNumber() {
        assertEquals(0, EpisodeExpandPolicy.rangeIndexForEpisode(1, 208))
        assertEquals(1, EpisodeExpandPolicy.rangeIndexForEpisode(51, 208))
        assertEquals(4, EpisodeExpandPolicy.rangeIndexForEpisode(208, 208))
    }

    @Test
    fun spanCountFitsWatchWidths() {
        assertEquals(4, EpisodeExpandPolicy.spanCount(200))
        assertEquals(5, EpisodeExpandPolicy.spanCount(240))
        assertEquals(6, EpisodeExpandPolicy.spanCount(300))
    }

    @Test
    fun toggleRangeSelectionSelectsThenClears() {
        val selected = linkedSetOf<String>()
        val rangeIds = listOf("e1", "e2", "e3")
        assertTrue(EpisodeExpandPolicy.toggleRangeSelection(selected, rangeIds))
        assertEquals(setOf("e1", "e2", "e3"), selected.toSet())
        selected.add("other")
        assertFalse(EpisodeExpandPolicy.toggleRangeSelection(selected, rangeIds))
        assertEquals(setOf("other"), selected.toSet())
    }

    @Test
    fun idsInRangeFollowEpisodeNumbers() {
        val episodes = (1..60).map { n ->
            com.hongguo.watch.data.model.Episode(
                id = "id-$n",
                seriesId = "s",
                seriesTitle = "demo",
                number = n,
                title = "第${n}集",
            )
        }
        assertEquals(
            (51..60).map { "id-$it" },
            EpisodeExpandPolicy.idsInRange(episodes, 51..100),
        )
    }
}
