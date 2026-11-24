package com.hongguo.watch.data

import com.hongguo.watch.data.model.RelatedWork
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.upstream.objects
import com.hongguo.watch.upstream.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 相关作品 / 同 IP 季表解析。
 *
 * 官方自动续季对照 aston314 `SeriesSeasons`：走
 * `from=single_col_same_ip_series` 完整同 IP 选择器，按 **season_index + 1**
 * 找下一季；**不**用 `detail_page_more_related`（会混入跨工作室同名/同 IP 推荐）。
 * 列表展示仍可用 related cell。
 */
object RelatedWorks {
    private val seasonSuffix = Regex("第?([0-9零〇一二两三四五六七八九十百]+)季$")
    private val officialSeasonLabel = Regex("^第([1-9][0-9]*)季$")
    private val seriesIdRegex = Regex("^[1-9][0-9]{5,29}$")

    private fun seasonNumber(value: String): Int? {
        value.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
        val digits = "零一二三四五六七八九"
        var total = 0
        var digit = 0
        for (c in value) {
            when (c) {
                '十', '百' -> {
                    total += maxOf(1, digit) * if (c == '十') 10 else 100
                    digit = 0
                }
                '两' -> digit = 2
                '〇' -> digit = 0
                else -> {
                    digit = digits.indexOf(c)
                    if (digit < 0) return null
                }
            }
        }
        return (total + digit).takeIf { it > 0 }
    }

    /** 展示排序用。 */
    fun stem(title: String): String = title.trim()
        .replace(seasonSuffix, "")
        .replace(Regex("[\\p{P}\\p{Z}\\s]"), "")

    fun seasonIndexFromOwnTitle(title: String): Int? {
        val trimmed = title.trim()
        val suffix = seasonSuffix.find(trimmed) ?: return 1
        return seasonNumber(suffix.groupValues[1])
    }

    fun ordered(current: Series, rows: List<RelatedWork>): List<RelatedWork> = rows.sortedWith(
        compareBy<RelatedWork> { if (stem(it.series.title) == stem(current.title)) 0 else 1 }
            .thenBy { stem(it.series.title) }
            .thenBy {
                it.seasonIndex.takeIf { n -> n > 0 }
                    ?: seasonSuffix.find(it.series.title.trim())?.groupValues?.get(1)?.let(::seasonNumber)
                    ?: 1
            },
    )

    /**
     * 自动连播下一季：只认 **同 IP 季表**（[parseSameIpSeries]）里，
     * 以当前 [seriesId] 的官方 `season_index` 为基准、唯一连续 +1 的可播季。
     *
     * - 当前剧不在同 IP 表里 → null（不强猜）
     * - 仅有「相关推荐 / 他工作室同题材」而无同 IP 后继 → null
     * - 多个 season_index 冲突 → null
     */
    fun nextSeasonFromSameIp(currentId: String, sameIpRows: List<RelatedWork>): RelatedWork? {
        val id = currentId.trim()
        if (id.isEmpty()) return null
        val current = sameIpRows.firstOrNull { it.series.id == id } ?: return null
        val currentIndex = current.seasonIndex.takeIf { it > 0 } ?: return null
        val target = currentIndex + 1
        return sameIpRows.filter { row ->
            !row.upcoming &&
                row.series.id != id &&
                row.seasonIndex == target
        }.singleOrNull()
    }

    /**
     * @deprecated 仅保留给相关列表启发式；自动连播请用 [nextSeasonFromSameIp]。
     * 对 `detail_page_more_related` 若只按 season_index 挑选，会把 A 公司第3季
     * 误接到 B 公司同题材第4季——官方不会这么做。
     */
    fun nextSeason(current: Series, rows: List<RelatedWork>): RelatedWork? {
        // 相关 cell 不做自动跳：避免跨工作室同题材误跳。
        // 若调用方误把 related 当 same-IP 传入且含 current id，可走 same-IP 路径。
        if (rows.any { it.series.id == current.id }) {
            return nextSeasonFromSameIp(current.id, rows)
        }
        return null
    }

    /** `from=detail_page_more_related`：详情「相关作品」展示用。 */
    fun parse(raw: String, currentId: String): List<RelatedWork> {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException("相关作品接口暂不可用")
        }
        val cells = root.optJSONArray("data")
            ?: throw IllegalStateException("相关作品数据格式已变化")
        return cells.objects()
            .filter { it.optInt("show_type") == 547 || it.str("cell_name") == "相关作品" }
            .flatMap { cell -> cell.optJSONArray("video_data")?.objects().orEmpty() }
            .mapNotNull { row -> toRelatedWork(row, currentId, includeCurrent = false) }
            .distinctBy { it.series.id }
            .sortedWith(compareBy { it.seasonIndex.takeIf { n -> n > 0 } ?: Int.MAX_VALUE })
    }

    /**
     * `from=single_col_same_ip_series`：官方同 IP 完整季表（含当前季）。
     * 对照 aston314 `parseSeriesSeasons`：扫全部 `video_data`，季标签优先
     * `secondary_info_list[].content`（「第N季」）。
     */
    fun parseSameIpSeries(raw: String, currentId: String): List<RelatedWork> {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException("同系列季表接口暂不可用")
        }
        val blocks = root.optJSONArray("data")
            ?: throw IllegalStateException("同系列季表数据格式已变化")
        val rows = ArrayList<RelatedWork>()
        val seen = HashSet<String>()
        for (block in blocks.objects()) {
            val videoData = block.optJSONArray("video_data") ?: continue
            for (row in videoData.objects()) {
                val work = toRelatedWork(row, currentId, includeCurrent = true) ?: continue
                if (!seen.add(work.series.id)) continue
                rows.add(work)
            }
        }
        return rows.sortedWith(
            compareBy { it.seasonIndex.takeIf { n -> n > 0 } ?: Int.MAX_VALUE },
        )
    }

    private fun toRelatedWork(
        row: JSONObject,
        currentId: String,
        includeCurrent: Boolean,
    ): RelatedWork? {
        val id = row.str("series_id").ifBlank { row.str("book_id") }
        val title = row.str("title")
        if (!seriesIdRegex.matches(id) || title.isBlank()) return null
        if (!includeCurrent && id == currentId) return null

        val type = row.optInt("content_type")
        // related cell：只要 1004/1007；same-IP 选择器偶发缺 content_type，仍收
        val typeOk = type in listOf(1004, 1007) || (includeCurrent && type == 0)
        if (!typeOk) return null

        val subscribe = row.optJSONObject("subscribe_item")
        val episodeCnt = row.optInt("episode_cnt").coerceAtLeast(0)
        val upcoming = type == 1007 ||
            subscribe != null ||
            (includeCurrent && episodeCnt <= 0)
        if (!upcoming && (row.optBoolean("disable_play") || episodeCnt <= 0)) {
            return null
        }

        val fromSecondary = seasonLabelFromSecondary(row.optJSONArray("secondary_info_list"))
        val seasonIndexField = row.optInt("season_index").coerceAtLeast(0)
        val seasonIndex = when {
            fromSecondary != null -> fromSecondary
            seasonIndexField > 0 -> seasonIndexField
            else -> seasonSuffix.find(title.trim())?.groupValues?.get(1)?.let(::seasonNumber) ?: 0
        }
        val season = when {
            fromSecondary != null -> "第${fromSecondary}季"
            seasonIndexField > 0 -> row.optJSONObject("tag_info")?.str("text").orEmpty()
                .ifBlank { "第${seasonIndexField}季" }
            seasonIndex > 0 -> "第${seasonIndex}季"
            else -> row.optJSONObject("tag_info")?.str("text").orEmpty()
        }
        return RelatedWork(
            series = Series(
                id = id,
                title = title,
                cover = safeCover(row.str("cover").ifEmpty { row.str("cover_url") }),
                description = row.str("video_desc"),
                episodeCount = episodeCnt,
                tags = listOfNotNull(season.takeIf { it.isNotBlank() }),
                source = SeriesSource.APP,
            ),
            seasonLabel = season,
            upcoming = upcoming,
            seasonIndex = seasonIndex,
        )
    }

    private fun seasonLabelFromSecondary(list: JSONArray?): Int? {
        if (list == null) return null
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val content = item.str("content")
            val match = officialSeasonLabel.find(content) ?: continue
            return match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
        }
        return null
    }

    private fun safeCover(value: String): String = CoverUrls.normalize(value)
}
