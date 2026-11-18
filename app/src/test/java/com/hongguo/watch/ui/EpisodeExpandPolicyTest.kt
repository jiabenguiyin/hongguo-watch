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
}
