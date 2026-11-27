package com.hongguo.watch.data

/**
 * 追更 / 收藏进度判定。
 *
 * - 追更：当前总集数高于 `last_serial_count`（官方本地列同名语义）。
 * - 收藏卡数量：官方 UI 是进度 `已看集/总集`，不是社交收藏人数。
 */
object ChaseUpdatePolicy {
    fun hasUpdate(currentEpisodeCount: Int, lastSerialCount: Int, seeded: Boolean): Boolean {
        if (!seeded) return false
        val current = currentEpisodeCount.coerceAtLeast(0)
        val seen = lastSerialCount.coerceAtLeast(0)
        return current > seen
    }

    fun newEpisodeDelta(currentEpisodeCount: Int, lastSerialCount: Int): Int {
        val current = currentEpisodeCount.coerceAtLeast(0)
        val seen = lastSerialCount.coerceAtLeast(0)
        return (current - seen).coerceAtLeast(0)
    }

    /**
     * 官方收藏卡进度：`currentPlayIndex` / `episodeCnt` → `7集/130集`。
     */
    fun progressLabel(watchedEpisode: Int?, totalEpisodes: Int): String {
        val total = totalEpisodes.coerceAtLeast(0)
        val watched = watchedEpisode?.coerceAtLeast(0)
        return when {
            total > 0 && watched != null -> "${watched}集/${total}集"
            total > 0 -> "全${total}集"
            watched != null && watched > 0 -> "看到第${watched}集"
            else -> ""
        }
    }
}
