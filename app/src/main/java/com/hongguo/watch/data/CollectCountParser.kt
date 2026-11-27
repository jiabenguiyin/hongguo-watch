package com.hongguo.watch.data

import org.json.JSONObject

/**
 * 详情社交收藏人数解析。
 *
 * 实测 `multi_video_detail` → `video_data.followed_cnt`（如 1990052）。
 * 旧文档字段 `favorite_cnt` / `collect_*` 作兼容；`secondary_infos`「X万收藏」兜底。
 */
object CollectCountParser {
    private val DIRECT_KEYS = listOf(
        "followed_cnt",
        "followedCnt",
        "favorite_cnt",
        "favoriteCnt",
        "collect_cnt",
        "collectCnt",
        "collect_count",
        "collectCount",
        "all_bookshelf_count",
        "allBookshelfCount",
        "bookshelf_count",
        "bookshelfCount",
        "collectors_count",
        "collectorsCount",
    )

    fun parse(node: JSONObject): Long {
        for (key in DIRECT_KEYS) {
            val n = positiveLongField(node, key)
            if (n > 0L) return n
        }
        val nested = listOfNotNull(
            node.optJSONObject("series_data"),
            node.optJSONObject("book_data"),
            node.optJSONObject("video_detail"),
            node.optJSONObject("key_metrics"),
            node.optJSONObject("stat_info"),
        )
        for (parent in nested) {
            for (key in DIRECT_KEYS) {
                val n = positiveLongField(parent, key)
                if (n > 0L) return n
            }
        }
        val stats = node.optJSONArray("video_cover_stat_infos")
        if (stats != null) {
            for (i in 0 until stats.length()) {
                val row = stats.optJSONObject(i) ?: continue
                val type = row.optString("video_cover_stat_type")
                    .ifBlank { row.optString("stat_type") }
                val valueText = row.optString("video_cover_stat_value")
                    .ifBlank { row.optString("stat_value") }
                val typeNum = row.optInt("video_cover_stat_type", row.optInt("stat_type", -1))
                val looksCollect = type.contains("collect", ignoreCase = true) ||
                    type.contains("favorite", ignoreCase = true) ||
                    type.contains("bookshelf", ignoreCase = true) ||
                    type.contains("收藏") ||
                    typeNum == 1 || typeNum == 2
                if (!looksCollect && valueText.isBlank()) continue
                val fromValue = ChaseUpdatePolicy.parseCollectCountLabel(valueText)
                    .takeIf { it > 0L }
                    ?: ChaseUpdatePolicy.parseCountText(valueText)
                if (fromValue > 0L) return fromValue
                val raw = positiveLongField(row, "video_cover_stat_value")
                    .takeIf { it > 0L }
                    ?: positiveLongField(row, "stat_value")
                if (raw > 0L && looksCollect) return raw
            }
        }
        for (arrayKey in listOf("secondary_infos", "secondary_info_list")) {
            val secondary = node.optJSONArray(arrayKey) ?: continue
            for (i in 0 until secondary.length()) {
                val content = secondary.optJSONObject(i)?.optString("content").orEmpty()
                val n = ChaseUpdatePolicy.parseCollectCountLabel(content)
                if (n > 0L) return n
            }
        }
        return 0L
    }

    private fun positiveLongField(obj: JSONObject, key: String): Long {
        if (!obj.has(key) || obj.isNull(key)) return -1L
        when (val raw = obj.opt(key)) {
            is Number -> {
                val n = raw.toLong()
                return if (n > 0L) n else -1L
            }
            is String -> {
                val text = raw.trim()
                if (text.isEmpty()) return -1L
                text.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
                ChaseUpdatePolicy.parseCollectCountLabel(text).takeIf { it > 0L }?.let { return it }
                ChaseUpdatePolicy.parseCountText(text).takeIf { it > 0L }?.let { return it }
            }
        }
        val fallback = obj.optLong(key, -1L)
        return if (fallback > 0L) fallback else -1L
    }
}
