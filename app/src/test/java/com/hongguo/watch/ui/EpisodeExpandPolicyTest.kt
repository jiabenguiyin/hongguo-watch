package com.hongguo.watch.ui

import com.hongguo.watch.data.model.Episode
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
    fun pagesChunkByListPositionNotSizeFilter() {
        val eps = episodes(1..208)
        val pages = EpisodeExpandPolicy.pages(eps)
        assertEquals(5, pages.size)
        assertEquals(1, pages[0].startNumber)
        assertEquals(50, pages[0].endNumber)
        assertEquals(50, pages[0].episodes.size)
        assertEquals(51, pages[1].startNumber)
        assertEquals(100, pages[1].endNumber)
        assertEquals(201, pages[4].startNumber)
        assertEquals(208, pages[4].endNumber)
        assertEquals(8, pages[4].episodes.size)
        // 全季分集不丢
        assertEquals(208, pages.sumOf { it.episodes.size })
    }

    @Test
    fun pagesUseActualNumbersWhenGapsExist() {
        // 缺集：列表 100 条但集号到 120 → 按列表切块，标签跟真实集号
        val numbers = (1..120).filter { it % 6 != 0 } // 100 集
        assertEquals(100, numbers.size)
        val pages = EpisodeExpandPolicy.pages(episodes(numbers))
        assertEquals(2, pages.size)
        assertEquals(50, pages[0].episodes.size)
        assertEquals(50, pages[1].episodes.size)
        assertEquals(numbers.first(), pages[0].startNumber)
        assertEquals(numbers[49], pages[0].endNumber)
        assertEquals(numbers[50], pages[1].startNumber)
        assertEquals(numbers.last(), pages[1].endNumber)
        // 旧的 1..size 过滤会漏掉集号>100 的分集；分页按列表则全部覆盖
        assertEquals(numbers.toSet(), pages.flatMap { it.episodes.map { e -> e.number } }.toSet())
    }

    @Test
    fun pageIndexFollowsEpisodeNumber() {
        val pages = EpisodeExpandPolicy.pages(episodes(1..208))
        assertEquals(0, EpisodeExpandPolicy.pageIndexForEpisode(1, pages))
        assertEquals(1, EpisodeExpandPolicy.pageIndexForEpisode(51, pages))
        assertEquals(4, EpisodeExpandPolicy.pageIndexForEpisode(208, pages))
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
    fun visibleEpisodesRespectsOrder() {
        val page = EpisodeExpandPolicy.pages(episodes(1..3)).single()
        assertEquals(listOf(1, 2, 3), EpisodeExpandPolicy.visibleEpisodes(page, ascending = true).map { it.number })
        assertEquals(listOf(3, 2, 1), EpisodeExpandPolicy.visibleEpisodes(page, ascending = false).map { it.number })
    }

    @Test
    fun currentPageEnqueueTargetsMatchChipLabel() {
        val pages = EpisodeExpandPolicy.pages(episodes(1..100))
        val second = pages[1]
        assertEquals(51, second.startNumber)
        assertEquals(100, second.endNumber)
        assertEquals((51..100).map { "id-$it" }, second.ids)
    }

    private fun episodes(numbers: IntProgression): List<Episode> =
        episodes(numbers.toList())

    private fun episodes(numbers: List<Int>): List<Episode> =
        numbers.map { n ->
            Episode(
                id = "id-$n",
                seriesId = "s",
                seriesTitle = "demo",
                number = n,
                title = "第${n}集",
            )
        }
}
