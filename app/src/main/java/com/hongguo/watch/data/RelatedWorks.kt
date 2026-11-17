package com.hongguo.watch.data

import com.hongguo.watch.data.model.RelatedWork
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.upstream.cleanUrl
import com.hongguo.watch.upstream.objects
import com.hongguo.watch.upstream.str
import org.json.JSONObject

/**
 * 对照 N3urda/hongguoTV `RelatedWorks`：解析 App 族 plan 相关作品，并保守挑选下一季。
 * 不猜无关标题；待上线 / 禁播 / 非唯一候选一律不自动跳。
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

    fun stem(title: String): String = title.trim()
        .replace(seasonSuffix, "")
        .replace(Regex("[\\p{P}\\p{Z}\\s]"), "")

    fun ordered(current: Series, rows: List<RelatedWork>): List<RelatedWork> = rows.sortedWith(
        compareBy<RelatedWork> { if (stem(it.series.title) == stem(current.title)) 0 else 1 }
            .thenBy { stem(it.series.title) }
            .thenBy {
                it.seasonIndex.takeIf { n -> n > 0 }
                    ?: seasonSuffix.find(it.series.title.trim())?.groupValues?.get(1)?.let(::seasonNumber)
                    ?: 1
            },
    )

    /** 保守：同 stem、连续季号、唯一可播候选。 */
    fun nextSeason(current: Series, rows: List<RelatedWork>): RelatedWork? {
        val title = current.title.trim()
        val suffix = seasonSuffix.find(title)
        val number = suffix?.groupValues?.get(1)?.let(::seasonNumber)
            ?: if (suffix == null) 1 else return null
        val currentStem = stem(title)
        if (currentStem.isBlank()) return null
        return rows.filter { row ->
            val candidateTitle = row.series.title.trim()
            val candidateSuffix = seasonSuffix.find(candidateTitle)
            val fromTitle = candidateSuffix?.groupValues?.get(1)?.let(::seasonNumber)
            val index = row.seasonIndex.takeIf { it > 0 } ?: fromTitle
            !row.upcoming &&
                row.series.id != current.id &&
                index == number + 1 &&
                (fromTitle == null || fromTitle == index) &&
                stem(candidateTitle) == currentStem
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
                val id = row.str("series_id")
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

    /** 标题搜索降级：仅当唯一命中「下一季」时返回。 */
    fun nextFromSearchFallback(current: Series, candidates: List<Series>): RelatedWork? {
        val title = current.title.trim()
        val suffix = seasonSuffix.find(title)
        val number = suffix?.groupValues?.get(1)?.let(::seasonNumber)
            ?: if (suffix == null) 1 else return null
        val currentStem = stem(title)
        if (currentStem.isBlank()) return null
        val rows = candidates.mapNotNull { series ->
            if (series.id == current.id) return@mapNotNull null
            val candidateSuffix = seasonSuffix.find(series.title.trim())
            val fromTitle = candidateSuffix?.groupValues?.get(1)?.let(::seasonNumber) ?: return@mapNotNull null
            if (stem(series.title) != currentStem || fromTitle != number + 1) return@mapNotNull null
            RelatedWork(
                series = series,
                seasonLabel = "第${fromTitle}季",
                upcoming = false,
                seasonIndex = fromTitle,
            )
        }
        return rows.singleOrNull()
    }

    private fun safeCover(value: String): String {
        val url = cleanUrl(value)
        return if (url.startsWith("http://") || url.startsWith("https://")) url else ""
    }
}
