package com.hongguo.watch.ui

import com.hongguo.watch.data.model.HomeCategory
import com.hongguo.watch.data.model.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNavPolicyTest {
    private val all = listOf(
        HomeCategory.recommend(),
    ) + HomeCategory.ranks() + listOf(
        HomeCategory(id = "genre:都市", label = "都市", filters = mapOf("theme" to "都市")),
        HomeCategory(id = "genre:古装", label = "古装", filters = mapOf("theme" to "古装")),
    )

    @Test
    fun searchRowOnlyOnSearchNav() {
        assertFalse(HomeNavPolicy.showSearchRow(HomeNav.RECOMMEND))
        assertTrue(HomeNavPolicy.showSearchRow(HomeNav.SEARCH))
    }

    @Test
    fun subChipsOnlyOnRankOrCategory() {
        assertFalse(HomeNavPolicy.showSubChips(HomeNav.RECOMMEND))
        assertTrue(HomeNavPolicy.showSubChips(HomeNav.RANK))
        assertTrue(HomeNavPolicy.showSubChips(HomeNav.CATEGORY))
        assertFalse(HomeNavPolicy.showSubChips(HomeNav.SEARCH))
    }

    @Test
    fun chipsSplitRanksAndGenres() {
        assertEquals(4, HomeNavPolicy.rankCategories(all).size)
        assertEquals(2, HomeNavPolicy.genreCategories(all).size)
        assertEquals(0, HomeNavPolicy.chipsFor(HomeNav.RECOMMEND, all).size)
    }

    @Test
    fun resolveSelectedCategoryKeepsRankWhenStillValid() {
        val id = "rank:hot-comic-drama"
        assertEquals(
            id,
            HomeNavPolicy.resolveSelectedCategoryId(HomeNav.RANK, id, all),
        )
        assertEquals(
            HomeCategory.ID_RECOMMEND,
            HomeNavPolicy.resolveSelectedCategoryId(HomeNav.RECOMMEND, id, all),
        )
        assertEquals(
            "genre:都市",
            HomeNavPolicy.resolveSelectedCategoryId(HomeNav.CATEGORY, id, all),
        )
    }

    @Test
    fun bannerTakesLeadingItemsForCarousel() {
        val items = listOf(
            Series(id = "1", title = "A"),
            Series(id = "2", title = "B"),
            Series(id = "3", title = "C"),
            Series(id = "4", title = "D"),
        )
        assertEquals(listOf("1", "2", "3"), HomeNavPolicy.bannerSeriesList(items).map { it.id })
        assertEquals(listOf("4"), HomeNavPolicy.listSeriesAfterBanner(items).map { it.id })
        assertEquals(listOf("1"), HomeNavPolicy.bannerSeriesList(listOf(items.first())).map { it.id })
        assertTrue(HomeNavPolicy.listSeriesAfterBanner(listOf(items.first())).isEmpty())
    }
}
