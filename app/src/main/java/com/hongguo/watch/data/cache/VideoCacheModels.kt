package com.hongguo.watch.data.cache

/**
 * 本地短剧集缓存条目（解密后的可播文件）。
 * [listReady] 供 T-57 离线列表复用；本 PR 仅展示用量与清空。
 */
data class VideoCacheEntry(
    val seriesId: String,
    val episodeId: String,
    val seriesTitle: String,
    val episodeNumber: Int,
    val quality: String,
    val codec: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastAccessMs: Long,
    val ready: Boolean,
) {
    val id: String get() = cacheId(seriesId, episodeId)

    companion object {
        fun cacheId(seriesId: String, episodeId: String): String =
            "${seriesId.trim()}::${episodeId.trim()}"
    }
}

/** 发起缓存所需的剧集元数据（不含流地址）。 */
data class VideoCacheRequest(
    val seriesId: String,
    val episodeId: String,
    val seriesTitle: String,
    val episodeNumber: Int,
)

enum class VideoCachePriority {
    /** 详情页用户发起的下载（单集 / 多选 / 全季） */
    CURRENT,
    /** 保留枚举兼容；播放器不再预取 */
    NEXT,
}

/** 离线列表头 / 设置页空间摘要。 */
data class VideoCacheSpaceSummary(
    val usedBytes: Long,
    val budgetBytes: Long,
    val freeBytes: Long,
    val entryCount: Int,
) {
    val usedLabel: String get() = VideoCacheManager.formatBytes(usedBytes)
    val budgetLabel: String get() = VideoCacheManager.formatBytes(budgetBytes)
    val freeLabel: String get() = VideoCacheManager.formatBytes(freeBytes.coerceAtLeast(0L))
    /** 0–100，相对预算占用。 */
    val usedPercentOfBudget: Int =
        if (budgetBytes <= 0L) 0
        else ((usedBytes * 100L) / budgetBytes).toInt().coerceIn(0, 100)
}
