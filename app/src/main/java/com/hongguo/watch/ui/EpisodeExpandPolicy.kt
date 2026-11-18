package com.hongguo.watch.ui

/**
 * 详情选集：横滑收起 / 网格展开 + 分段跳转。
 * 手表屏按宽度估算列数；分段默认每 50 集。
 */
object EpisodeExpandPolicy {
    const val RANGE_SIZE = 50
    /** 超过一行可见量时显示「展开」。 */
    const val EXPAND_THRESHOLD = 12

    fun shouldShowExpand(episodeCount: Int): Boolean =
        episodeCount > EXPAND_THRESHOLD

    fun spanCount(widthDp: Int): Int = when {
        widthDp >= 280 -> 6
        widthDp >= 220 -> 5
        else -> 4
    }.coerceAtLeast(3)

    /** 1-based inclusive ranges，如 1–50、51–100。 */
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

    fun rangeIndexForEpisode(episodeNumber: Int, total: Int): Int {
        if (total <= 0 || episodeNumber <= 0) return 0
        val ranges = ranges(total)
        val idx = ranges.indexOfFirst { episodeNumber in it }
        return idx.coerceAtLeast(0)
    }

    fun sliceForRange(episodes: List<com.hongguo.watch.data.model.Episode>, range: IntRange): List<com.hongguo.watch.data.model.Episode> =
        episodes.filter { it.number in range }
}
