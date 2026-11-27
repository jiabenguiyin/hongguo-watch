package com.hongguo.watch.ui

import com.hongguo.watch.data.model.Episode

/**
 * 详情选集：横滑收起 / 网格展开 + 分段跳转。
 * 手表屏按宽度估算列数；分段默认每 50 集。
 *
 * 分段按**已排序分集列表下标**切块（每块最多 [RANGE_SIZE] 集），
 * 标签用块内真实集号（如 1–50、51–100），避免用 `1..size` 按号过滤时
 * 与 vid_index / 缺集错位。
 */
object EpisodeExpandPolicy {
    const val RANGE_SIZE = 50
    /** 超过一行可见量时显示「展开」。 */
    const val EXPAND_THRESHOLD = 12

    data class Page(
        /** 块内最小集号（展示用）。 */
        val startNumber: Int,
        /** 块内最大集号（展示用）。 */
        val endNumber: Int,
        /** 该段分集（已按集号升序）。 */
        val episodes: List<Episode>,
    ) {
        val ids: List<String> get() = episodes.map { it.id }
        val labelRange: IntRange get() = startNumber..endNumber
    }

    fun shouldShowExpand(episodeCount: Int): Boolean =
        episodeCount > EXPAND_THRESHOLD

    fun spanCount(widthDp: Int): Int = when {
        widthDp >= 280 -> 6
        widthDp >= 220 -> 5
        else -> 4
    }.coerceAtLeast(3)

    /**
     * 将升序分集列表切成每段最多 [RANGE_SIZE] 集的页。
     * 标签为块内首尾集号（不必等于下标 1..N）。
     */
    fun pages(episodes: List<Episode>, pageSize: Int = RANGE_SIZE): List<Page> {
        if (episodes.isEmpty() || pageSize <= 0) return emptyList()
        val sorted = episodes.sortedBy { it.number }
        val out = ArrayList<Page>((sorted.size + pageSize - 1) / pageSize)
        var i = 0
        while (i < sorted.size) {
            val end = (i + pageSize).coerceAtMost(sorted.size)
            val chunk = sorted.subList(i, end).toList()
            out += Page(
                startNumber = chunk.first().number,
                endNumber = chunk.last().number,
                episodes = chunk,
            )
            i = end
        }
        return out
    }

    /** @deprecated 仅兼容旧测试；新逻辑请用 [pages]。 */
    fun ranges(total: Int): List<IntRange> {
        if (total <= 0) return emptyList()
        val out = mutableListOf<IntRange>()
        var start = 1
        while (start <= total) {
            val end = (start + RANGE_SIZE - 1).coerceAtMost(total)
            out += start..end
            start = end + 1
        }
        return out
    }

    fun pageIndexForEpisode(episodeNumber: Int, pages: List<Page>): Int {
        if (pages.isEmpty() || episodeNumber <= 0) return 0
        val idx = pages.indexOfFirst { page ->
            page.episodes.any { it.number == episodeNumber }
        }
        return idx.coerceAtLeast(0)
    }

    fun rangeIndexForEpisode(episodeNumber: Int, total: Int): Int {
        if (total <= 0 || episodeNumber <= 0) return 0
        val ranges = ranges(total)
        val idx = ranges.indexOfFirst { episodeNumber in it }
        return idx.coerceAtLeast(0)
    }

    fun sliceForRange(episodes: List<Episode>, range: IntRange): List<Episode> =
        episodes.filter { it.number in range }

    fun visibleEpisodes(page: Page, ascending: Boolean): List<Episode> =
        if (ascending) page.episodes else page.episodes.asReversed()

    /**
     * 分段多选：若该段已全部勾选则整段取消，否则整段勾选。
     * 返回是否变为「全选」状态。
     */
    fun toggleRangeSelection(
        selected: MutableSet<String>,
        rangeIds: Collection<String>,
    ): Boolean {
        if (rangeIds.isEmpty()) return false
        val idSet = rangeIds as? Set<String> ?: rangeIds.toSet()
        val allSelected = idSet.isNotEmpty() && idSet.all { it in selected }
        if (allSelected) {
            selected.removeAll(idSet)
            return false
        }
        selected.addAll(idSet)
        return true
    }

    /** 仅勾选（不取消），供「选本段」语义更直观时可选用。 */
    fun selectRange(
        selected: MutableSet<String>,
        rangeIds: Collection<String>,
    ) {
        if (rangeIds.isEmpty()) return
        selected.addAll(rangeIds)
    }
}
