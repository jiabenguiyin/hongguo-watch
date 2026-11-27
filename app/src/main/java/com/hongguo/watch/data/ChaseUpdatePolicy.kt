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
     * 返回纯数字文案（如 `1.2万` / `198.9万`）；≤0 返回空。
     * 按钮最终文案用资源 `%1$s收藏` 拼成「198.9万收藏」，勿再前置「收藏」。
     */
    fun formatCollectCount(count: Long): String {
        if (count <= 0L) return ""
        return when {
            count >= 100_000_000L -> formatScaledCount(count / 100_000_000.0, "亿")
            count >= 10_000L -> formatScaledCount(count / 10_000.0, "万")
            else -> count.toString()
        }
    }

    /** 官方风格：`198.9万` 保留一位；整万去掉 `.0`（`12万`）。用 US locale 固定小数点。 */
    private fun formatScaledCount(value: Double, suffix: String): String {
        val one = String.format(java.util.Locale.US, "%.1f", value)
        val body = if (one.endsWith(".0")) one.dropLast(2) else one
        return body + suffix
    }

    /**
     * 解析「198.9万」「1.2亿」「1989000」等纯数量文案（可不含「收藏」）。
     */
    fun parseCountText(raw: String): Long {
        val text = raw.trim().replace(",", "").replace("人", "")
        if (text.isEmpty()) return 0L
        text.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        return runCatching {
            when {
                text.endsWith("亿") -> {
                    val n = text.dropLast(1).toDoubleOrNull() ?: return@runCatching 0L
                    (n * 100_000_000.0).toLong()
                }
                text.endsWith("万") -> {
                    val n = text.dropLast(1).toDoubleOrNull() ?: return@runCatching 0L
                    (n * 10_000.0).toLong()
                }
                else -> 0L
            }
        }.getOrDefault(0L).coerceAtLeast(0L)
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
        return parseCountText(body)
    }
}
