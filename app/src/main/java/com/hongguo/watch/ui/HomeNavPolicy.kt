package com.hongguo.watch.ui

import com.hongguo.watch.data.model.HomeCategory

/** 首页顶栏：推荐 / 榜单 / 分类 / 搜索。 */
enum class HomeNav {
    RECOMMEND,
    RANK,
    CATEGORY,
    SEARCH,
}

/**
 * 首页顶栏与二级芯片可见性、默认选中分类。
 * 底栏：首页 / 库（收藏·历史分段）/ 我的。
 */
object HomeNavPolicy {
    const val BANNER_PAGE_COUNT = 3
    fun showSearchRow(nav: HomeNav): Boolean = nav == HomeNav.SEARCH

    fun showSubChips(nav: HomeNav): Boolean =
        nav == HomeNav.RANK || nav == HomeNav.CATEGORY

    fun showHomeNavChrome(modeIsHome: Boolean): Boolean = modeIsHome

    fun rankCategories(all: List<HomeCategory>): List<HomeCategory> =
        all.filter { it.isRank }

    fun genreCategories(all: List<HomeCategory>): List<HomeCategory> =
        all.filter { !it.isRecommend && !it.isRank }

    fun chipsFor(nav: HomeNav, all: List<HomeCategory>): List<HomeCategory> = when (nav) {
        HomeNav.RANK -> rankCategories(all)
        HomeNav.CATEGORY -> genreCategories(all)
        HomeNav.RECOMMEND, HomeNav.SEARCH -> emptyList()
    }

    /**
     * 切换顶栏后应选中的分类 id。
     * 已在对应分组内则保留；否则取该组第一项（推荐固定 recommend）。
     */
    fun resolveSelectedCategoryId(
        nav: HomeNav,
        currentId: String,
        all: List<HomeCategory>,
    ): String {
        val chips = chipsFor(nav, all)
        return when (nav) {
            HomeNav.RECOMMEND -> HomeCategory.ID_RECOMMEND
            HomeNav.SEARCH -> currentId
            HomeNav.RANK, HomeNav.CATEGORY -> {
                if (chips.any { it.id == currentId }) currentId
                else chips.firstOrNull()?.id ?: currentId
            }
        }
    }

    /** 推荐流：前几条作可滑动 Banner，其余进「为你推荐」。 */
    fun bannerSeriesList(
        items: List<com.hongguo.watch.data.model.Series>,
        max: Int = BANNER_PAGE_COUNT,
    ): List<com.hongguo.watch.data.model.Series> =
        items.take(max.coerceAtLeast(0))

    fun listSeriesAfterBanner(
        items: List<com.hongguo.watch.data.model.Series>,
        bannerCount: Int = BANNER_PAGE_COUNT,
    ): List<com.hongguo.watch.data.model.Series> {
        val drop = bannerCount.coerceAtLeast(0).coerceAtMost(items.size)
        return if (drop >= items.size) emptyList() else items.drop(drop)
    }
}
