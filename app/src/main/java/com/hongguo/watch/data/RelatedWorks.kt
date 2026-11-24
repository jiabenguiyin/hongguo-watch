package com.hongguo.watch.data

import com.hongguo.watch.data.model.RelatedWork
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.upstream.objects
import com.hongguo.watch.upstream.str
import org.json.JSONObject

/**
 * 解析 App 族 plan 相关作品，并按稳定 ID / season_index 挑选下一季。
 * 自动连播不得仅靠标题字符串相等——同名异剧会误跳。
 */
object RelatedWorks {
    private val seasonSuffix = Regex("第?([0-9零〇一二两三四五六七八九十百]+)季$")
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

    /** 展示排序用；自动连播不依赖此 stem。 */
    fun stem(title: String): String = title.trim()
        .replace(seasonSuffix, "")
        .replace(Regex("[\\p{P}\\p{Z}\\s]"), "")

    /** 仅解析「当前剧」自身标题上的季号，不当作跨剧匹配键。 */
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
     * 下一季：只认相关作品接口已按 book/series id 关联出的候选，
     * 用 [RelatedWork.seasonIndex]（或候选自身标题季号）对齐连续季；
     * **不**用标题 stem 过滤，避免同名异剧误命中。
     */
    fun nextSeason(current: Series, rows: List<RelatedWork>): RelatedWork? {
        val currentIndex = seasonIndexFromOwnTitle(current.title) ?: return null
        val target = currentIndex + 1
        return rows.filter { row ->
            if (row.upcoming || row.series.id == current.id) return@filter false
            val index = row.seasonIndex.takeIf { it > 0 }
                ?: seasonSuffix.find(row.series.title.trim())?.groupValues?.get(1)?.let(::seasonNumber)
                ?: return@filter false
            index == target
        }.singleOrNull()
    }

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
            .mapNotNull { row ->
                val id = row.str("series_id").ifBlank { row.str("book_id") }
                val title = row.str("title")
                val type = row.optInt("content_type")
                if (
                    id == currentId ||
                    !seriesIdRegex.matches(id) ||
                    title.isBlank() ||
                    type !in listOf(1004, 1007)
                ) {
                    return@mapNotNull null
                }
                val upcoming = type == 1007
                if (!upcoming && (row.optBoolean("disable_play") || row.optInt("episode_cnt") <= 0)) {
                    return@mapNotNull null
                }
                val seasonIndex = row.optInt("season_index").coerceAtLeast(0)
                val season = row.optJSONObject("tag_info")?.str("text").orEmpty().ifBlank {
                    if (seasonIndex > 0) "第${seasonIndex}季" else ""
                }
                RelatedWork(
                    series = Series(
                        id = id,
                        title = title,
                        cover = safeCover(row.str("cover").ifEmpty { row.str("cover_url") }),
                        description = row.str("video_desc"),
                        episodeCount = row.optInt("episode_cnt").coerceAtLeast(0),
                        tags = listOfNotNull(season.takeIf { it.isNotBlank() }),
                        source = SeriesSource.APP,
                    ),
                    seasonLabel = season,
                    upcoming = upcoming,
                    seasonIndex = seasonIndex,
                )
            }
            .distinctBy { it.series.id }
            .sortedWith(compareBy { it.seasonIndex.takeIf { n -> n > 0 } ?: Int.MAX_VALUE })
    }

    private fun safeCover(value: String): String = CoverUrls.normalize(value)
}
