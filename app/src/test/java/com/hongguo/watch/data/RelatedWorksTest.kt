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
    fun nextSeasonFromSameIp_picksConsecutiveIndex() {
        val rows = listOf(
            related("100001", "示例短剧", seasonIndex = 1),
            related("100002", "示例短剧第二季", seasonIndex = 2),
            related("100003", "示例短剧第三季", seasonIndex = 3),
        )
        val next = RelatedWorks.nextSeasonFromSameIp("100001", rows)
        assertEquals("100002", next?.series?.id)
    }

    @Test
    fun nextSeasonFromSameIp_requiresCurrentInList() {
        // same-IP 表缺当前剧 → 不强猜
        assertNull(
            RelatedWorks.nextSeasonFromSameIp(
                "100001",
                listOf(related("100002", "示例短剧第二季", seasonIndex = 2)),
            ),
        )
    }

    @Test
    fun nextSeason_relatedOnlyWithoutCurrent_returnsNull() {
        // detail_page_more_related 不含当前 id：不得当自动续季源
        val current = series("100001", "示例短剧")
        assertNull(
            RelatedWorks.nextSeason(
                current,
                listOf(related("100002", "示例短剧第二季", seasonIndex = 2)),
            ),
        )
    }

    @Test
    fun nextSeason_studioA_s3_doesNotAutoJumpToStudioB_s4() {
        // A 公司第3季：同 IP 表只有自家 1–3；related 却推了 B 公司同题材第4季
        val studioASeason3 = "200003"
        val studioBSeason4 = "900004"
        val sameIp = listOf(
            related("200001", "霸道总裁的隐婚娇妻", seasonIndex = 1),
            related("200002", "霸道总裁的隐婚娇妻第二季", seasonIndex = 2),
            related("200003", "霸道总裁的隐婚娇妻第三季", seasonIndex = 3),
        )
        val relatedOtherStudio = listOf(
            related(
                studioBSeason4,
                "霸道总裁的隐婚娇妻第四季", // 同题材、他工作室
                seasonIndex = 4,
            ),
            related("777777", "别的相关短剧", seasonIndex = 1),
        )

        // 同 IP 表无第4季 → 不自动跳
        assertNull(RelatedWorks.nextSeasonFromSameIp(studioASeason3, sameIp))
        // related-only（无当前 id）→ 不自动跳
        assertNull(
            RelatedWorks.nextSeason(
                series(studioASeason3, "霸道总裁的隐婚娇妻第三季"),
                relatedOtherStudio,
            ),
        )
        // resolveNextSeason 只喂 sameIp，绝不把 related 并进去（见 ContentRepository）
    }

    @Test
    fun nextSeasonFromSameIp_ambiguousDuplicateIndex_returnsNull() {
        val rows = listOf(
            related("100001", "示例短剧", seasonIndex = 1),
            related("100002", "示例短剧第二季", seasonIndex = 2),
            related("100099", "示例短剧贰季", seasonIndex = 2),
        )
        assertNull(RelatedWorks.nextSeasonFromSameIp("100001", rows))
    }

    @Test
    fun nextSeasonFromSameIp_skipsUpcoming() {
        val rows = listOf(
            related("100001", "示例短剧", seasonIndex = 1),
            related("100002", "示例短剧第二季", seasonIndex = 2, upcoming = true),
        )
        assertNull(RelatedWorks.nextSeasonFromSameIp("100001", rows))
    }

    @Test
    fun nextSeason_doesNotMatchSolelyOnDisplayName() {
        val current = series("100001", "示例短剧")
        val ambiguous = RelatedWorks.nextSeason(
            current,
            listOf(
                related("999999", "示例短剧第二季", seasonIndex = 2),
                related("888888", "示例短剧第二季", seasonIndex = 2),
            ),
        )
        assertNull(ambiguous)
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
        // related 解析结果不含当前 id → 自动续季拒绝
        assertNull(RelatedWorks.nextSeason(series("100001", "示例短剧"), rows))
    }

    @Test
    fun parseSameIpSeries_includesCurrentAndSecondaryLabel() {
        val raw = """
            {"code":0,"data":[{"video_data":[
              {"series_id":"100001","title":"示例短剧","content_type":1004,
               "disable_play":false,"episode_cnt":20,"cover":"",
               "secondary_info_list":[{"content":"第1季"}]},
              {"series_id":"100002","title":"示例短剧续","content_type":1004,
               "disable_play":false,"episode_cnt":20,"cover":"",
               "secondary_info_list":[{"content":"第2季"}]},
              {"series_id":"900004","title":"他工作室同题材第四季","content_type":1004,
               "disable_play":false,"episode_cnt":20,"cover":"",
               "secondary_info_list":[{"content":"第4季"}]}
            ]}]}
        """.trimIndent()
        val rows = RelatedWorks.parseSameIpSeries(raw, "100001")
        assertEquals(listOf("100001", "100002", "900004"), rows.map { it.series.id })
        assertEquals(1, rows[0].seasonIndex)
        assertEquals(2, rows[1].seasonIndex)
        // 同 IP 表里 1→2 唯一；4 不是连续后继
        assertEquals("100002", RelatedWorks.nextSeasonFromSameIp("100001", rows)?.series?.id)
        assertNull(RelatedWorks.nextSeasonFromSameIp("100002", rows))
    }

    @Test
    fun stem_stripsSeasonSuffix() {
        assertEquals(
            RelatedWorks.stem("示例短剧"),
            RelatedWorks.stem("示例短剧第二季"),
        )
        assertTrue(RelatedWorks.stem("示例短剧").isNotEmpty())
    }
}
