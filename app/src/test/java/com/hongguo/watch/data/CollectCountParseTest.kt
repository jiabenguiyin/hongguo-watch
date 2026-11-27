package com.hongguo.watch.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectCountParseTest {
    @Test
    fun prefersFollowedCntFromLiveDetailShape() {
        val videoData = JSONObject()
            .put("followed_cnt", 1_990_052)
            .put("digg_cnt", 0)
            .put("series_play_cnt", 3_044_529)
            .put("hot_score", 43_115_352)
        assertEquals(1_990_052L, CollectCountParser.parse(videoData))
        assertEquals("199万", ChaseUpdatePolicy.formatCollectCount(1_990_052))
    }

    @Test
    fun fallsBackToFavoriteCntWhenFollowedMissing() {
        val videoData = JSONObject().put("favorite_cnt", 12_000)
        assertEquals(12_000L, CollectCountParser.parse(videoData))
    }

    @Test
    fun readsSecondaryInfosCollectLabel() {
        val videoData = JSONObject().put(
            "secondary_infos",
            JSONArray().put(JSONObject().put("content", "98.8万收藏")),
        )
        assertEquals(988_000L, CollectCountParser.parse(videoData))
    }
}
