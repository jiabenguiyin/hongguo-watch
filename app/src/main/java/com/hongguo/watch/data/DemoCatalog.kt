package com.hongguo.watch.data

import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.data.model.RelatedWork
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesDetail
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.data.model.StreamInfo

/**
 * 选项 C：公开演示流，用于验证 Media3 硬解通路与历史续播。
 * 不依赖红果上游；接口挂了仍可测播放器。
 */
object DemoCatalog {
    const val SERIES_ID = "demo-bbb"

    const val SERIES_ID_S2 = "demo-bbb-s2"

    private val series = Series(
        id = SERIES_ID,
        title = "演示：Big Buck Bunny",
        cover = "https://peach.blender.org/wp-content/uploads/bbb-splash.png",
        description = "公开 CC 演示片，用于手表端 Media3 通路验证。",
        episodeCount = 3,
        tags = listOf("演示", "H.264"),
        source = SeriesSource.DEMO,
    )

    private val seriesS2 = Series(
        id = SERIES_ID_S2,
        title = "演示：Big Buck Bunny第二季",
        cover = "https://peach.blender.org/wp-content/uploads/bbb-splash.png",
        description = "演示跨季连播：第 1 季末集结束后自动进本季第 1 集。",
        episodeCount = 2,
        tags = listOf("演示", "第2季"),
        source = SeriesSource.DEMO,
    )

    private val streams = listOf(
        StreamInfo(
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            quality = "demo",
        ),
        StreamInfo(
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            quality = "demo",
        ),
        StreamInfo(
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
            quality = "demo",
        ),
    )

    fun homeItems(): List<Series> = listOf(series, seriesS2)

    fun detail(seriesId: String = SERIES_ID): SeriesDetail {
        return if (seriesId == SERIES_ID_S2) detailS2() else detailS1()
    }

    private fun detailS1(): SeriesDetail {
        val episodes = streams.mapIndexed { index, _ ->
            Episode(
                id = "demo-ep-${index + 1}",
                number = index + 1,
                title = "演示第 ${index + 1} 集",
                seriesId = SERIES_ID,
                seriesTitle = series.title,
                cover = series.cover,
            )
        }
        return SeriesDetail(series = series.copy(episodeCount = episodes.size), episodes = episodes)
    }

    private fun detailS2(): SeriesDetail {
        val episodes = listOf(streams[0], streams[1]).mapIndexed { index, _ ->
            Episode(
                id = "demo-s2-ep-${index + 1}",
                number = index + 1,
                title = "第二季第 ${index + 1} 集",
                seriesId = SERIES_ID_S2,
                seriesTitle = seriesS2.title,
                cover = seriesS2.cover,
            )
        }
        return SeriesDetail(series = seriesS2.copy(episodeCount = episodes.size), episodes = episodes)
    }

    fun streamFor(episodeId: String): StreamInfo? {
        when {
            episodeId.startsWith("demo-s2-ep-") -> {
                val index = episodeId.removePrefix("demo-s2-ep-").toIntOrNull()?.minus(1) ?: return null
                return streams.getOrNull(index)
            }
            episodeId.startsWith("demo-ep-") -> {
                val index = episodeId.removePrefix("demo-ep-").toIntOrNull()?.minus(1) ?: return null
                return streams.getOrNull(index)
            }
            else -> return null
        }
    }

    fun related(): List<RelatedWork> = listOf(
        RelatedWork(series = seriesS2, seasonLabel = "第2季", upcoming = false, seasonIndex = 2),
    )

    fun nextSeason(): RelatedWork? = related().firstOrNull()
}
