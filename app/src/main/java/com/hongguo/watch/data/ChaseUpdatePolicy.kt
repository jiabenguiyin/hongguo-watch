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

    /**
     * 详情「收藏」按钮旁人数：官方 `favorite_cnt` / `collect_count` 等。
     * 返回纯数字文案（如 `1.2万`）；≤0 返回空。
     */
    fun formatCollectCount(count: Long): String {
        if (count <= 0L) return ""
        return when {
            count >= 100_000_000L -> String.format("%.1f亿", count / 100_000_000.0)
            count >= 10_000L -> {
                val wan = count / 10_000.0
                if (wan >= 100) String.format("%.0f万", wan) else String.format("%.1f万", wan)
            }
            else -> count.toString()
        }
    }

    /**
     * 预约卡热度文案。优先官方 `hot_score_text`；否则用数值拼 `N万热度`。
     * 无真实字段返回空（勿造假火焰）。
     */
    fun formatHeatLabel(hotValue: Long, hotText: String = ""): String {
        val text = hotText.trim()
        if (text.isNotEmpty()) {
            return if (text.contains("热度")) text else "${text}热度"
        }
        val n = formatCollectCount(hotValue)
        return if (n.isEmpty()) "" else "${n}热度"
    }

    /** 从「98.8万收藏」类标签抽出人数；解析失败返回 0。 */
    fun parseCollectCountLabel(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty() || !text.contains("收藏")) return 0L
        val body = text.replace("收藏", "").replace("人", "").trim()
        if (body.isEmpty()) return 0L
        return runCatching {
            when {
                body.endsWith("亿") -> {
                    val n = body.dropLast(1).toDoubleOrNull() ?: return@runCatching 0L
                    (n * 100_000_000.0).toLong()
                }
                body.endsWith("万") -> {
                    val n = body.dropLast(1).toDoubleOrNull() ?: return@runCatching 0L
                    (n * 10_000.0).toLong()
                }
                else -> body.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }
}
