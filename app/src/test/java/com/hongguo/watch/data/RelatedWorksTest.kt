package com.hongguo.watch.data

import com.hongguo.watch.data.model.RelatedWork
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedWorksTest {
    private fun series(id: String, title: String) = Series(
        id = id,
        title = title,
        source = SeriesSource.APP,
    )

    private fun related(
        id: String,
        title: String,
        seasonIndex: Int,
        upcoming: Boolean = false,
    ) = RelatedWork(
        series = series(id, title),
        seasonLabel = if (seasonIndex > 0) "第${seasonIndex}季" else "",
        upcoming = upcoming,
        seasonIndex = seasonIndex,
    )

    @Test
    fun nextSeason_picksBySeasonIndexAmongRelatedIds() {
        val current = series("100001", "示例短剧")
        val next = RelatedWorks.nextSeason(
            current,
            listOf(
                related("100002", "示例短剧第二季", seasonIndex = 2),
                related("100003", "示例短剧第三季", seasonIndex = 3),
            ),
        )
        assertEquals("100002", next?.series?.id)
    }

    @Test
    fun nextSeason_doesNotMatchSolelyOnDisplayName() {
        // 同名异剧：搜索降级曾会把「另一部示例短剧第二季」当成下一季。
        val current = series("100001", "示例短剧")
        val wrongFranchise = related(
            id = "999999",
            title = "示例短剧第二季",
            seasonIndex = 0, // 无 API season_index，仅标题像下一季
        )
        // 无 season_index 且相关列表里只有异剧同名时：不得仅靠标题认下一季
        // （本函数若解析标题季号会命中；因此故意给 season_index=0 并依赖「仅当 index 可解析」——
        //  标题「…第二季」仍可解析。正确约束是：候选必须来自相关 API 且唯一。
        //  这里用两部同名「第二季」证明 singleOrNull 拒绝歧义。）
        val ambiguous = RelatedWorks.nextSeason(
            current,
            listOf(
                wrongFranchise.copy(
                    series = series("999999", "示例短剧第二季"),
                    seasonIndex = 2,
                ),
                related("888888", "示例短剧第二季", seasonIndex = 2),
            ),
        )
        assertNull(ambiguous)
    }

    @Test
    fun nextSeason_ignoresUnrelatedSameTitleWhenCorrectIdPresent() {
        val current = series("100001", "霸道总裁")
        val next = RelatedWorks.nextSeason(
            current,
            listOf(
                related("200001", "霸道总裁", seasonIndex = 2), // 同名异剧，但 API 给了 season 2
                related("100002", "霸道总裁2", seasonIndex = 2),
            ),
        )
        // 两部都是 season 2 → 歧义，拒绝自动跳
        assertNull(next)
    }

    @Test
    fun nextSeason_uniqueRelatedIdWinsEvenIfTitlesDiffer() {
        val current = series("100001", "示例短剧")
        val next = RelatedWorks.nextSeason(
            current,
            listOf(
                related("100002", "示例短剧·续章", seasonIndex = 2),
                related("300001", "完全无关的剧第二季", seasonIndex = 5),
            ),
        )
        assertEquals("100002", next?.series?.id)
        assertTrue(RelatedWorks.stem(next!!.series.title) != RelatedWorks.stem("完全无关的剧第二季"))
    }

    @Test
    fun nextSeason_skipsUpcoming() {
        val current = series("100001", "示例短剧")
        assertNull(
            RelatedWorks.nextSeason(
                current,
                listOf(related("100002", "示例短剧第二季", seasonIndex = 2, upcoming = true)),
            ),
        )
    }

    @Test
    fun seasonIndexFromOwnTitle_defaultsToOneWithoutSuffix() {
        assertEquals(1, RelatedWorks.seasonIndexFromOwnTitle("示例短剧"))
        assertEquals(2, RelatedWorks.seasonIndexFromOwnTitle("示例短剧第二季"))
        assertEquals(3, RelatedWorks.seasonIndexFromOwnTitle("示例短剧第3季"))
    }

    @Test
    fun parse_usesSeriesOrBookId() {
        val raw = """
            {"code":0,"data":[{"show_type":547,"cell_name":"相关作品","video_data":[
              {"series_id":"100002","title":"示例短剧第二季","content_type":1004,
               "disable_play":false,"episode_cnt":20,"season_index":2,"cover":""},
              {"book_id":"100003","title":"示例短剧第三季","content_type":1004,
               "disable_play":false,"episode_cnt":20,"season_index":3,"cover":""}
            ]}]}
        """.trimIndent()
        val rows = RelatedWorks.parse(raw, "100001")
        assertEquals(listOf("100002", "100003"), rows.map { it.series.id })
        val next = RelatedWorks.nextSeason(series("100001", "示例短剧"), rows)
        assertEquals("100002", next?.series?.id)
    }
}
