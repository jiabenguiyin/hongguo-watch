package com.hongguo.watch.data.sync

import com.hongguo.watch.data.db.FavoriteEntity
import com.hongguo.watch.data.db.LibraryChangeEntity
import com.hongguo.watch.data.db.WatchHistoryEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LibrarySyncWireTest {
    @Test
    fun requestUsesStableMutationIdsAndRemovesCoverQuery() {
        val favorite = favorite()
        val change = LibraryChangeEntity(
            sequence = 7,
            kind = LibraryChangeEntity.FAVORITE,
            seriesId = favorite.seriesId,
            deleted = false,
            baseCursor = 4,
        )

        val request = LibrarySyncWire.request(
            cursor = 4,
            installationId = "watch-install",
            changes = listOf(change),
            history = emptyList(),
            favorites = listOf(favorite),
        )
        val mutation = request.getJSONArray("changes").getJSONObject(0)

        assertEquals("watch-install:7", mutation.getString("id"))
        assertEquals(4, mutation.getLong("baseCursor"))
        assertEquals("https://cdn.example/poster.jpg", mutation.getJSONObject("value").getString("cover"))
    }

    @Test
    fun parseMapsAcceptedReceiptsToLocalSequencesAndReadsSnapshot() {
        val change = LibraryChangeEntity(
            sequence = 7,
            kind = LibraryChangeEntity.HISTORY,
            seriesId = "series",
            deleted = false,
            baseCursor = 4,
        )
        val response = JSONObject()
            .put("cursor", 5)
            .put("accepted", JSONObject().put("watch-install:7", 5))
            .put("conflicts", JSONArray())
            .put("history", JSONArray().put(historyJson()))
            .put("favorites", JSONArray())
            .put("imported", false)

        val result = LibrarySyncWire.parse(response, 4, "watch-install", listOf(change))

        assertEquals(5, result.cursor)
        assertEquals(mapOf(7L to 5L), result.accepted)
        assertEquals("series", result.history.single().seriesId)
        assertEquals(false, result.imported)
    }

    @Test
    fun parseRejectsReceiptForAnUnsentMutation() {
        val response = JSONObject()
            .put("cursor", 5)
            .put("accepted", JSONObject().put("watch-install:999", 5))
            .put("conflicts", JSONArray())
            .put("history", JSONArray())
            .put("favorites", JSONArray())
            .put("imported", false)

        assertThrows(SyncServiceException::class.java) {
            LibrarySyncWire.parse(response, 4, "watch-install", emptyList())
        }
    }

    private fun favorite() = FavoriteEntity(
        seriesId = "series",
        seriesTitle = "剧集",
        cover = "https://cdn.example/poster.jpg?token=private",
        source = "APP",
        episodeCount = 12,
        updatedAt = 1234,
    )

    private fun historyJson() = JSONObject()
        .put("seriesId", "series")
        .put("seriesTitle", "剧集")
        .put("cover", "")
        .put("source", "APP")
        .put("updatedAt", 1234)
        .put("episodeId", "episode-1")
        .put("episodeNumber", 1)
        .put("positionMs", 120)
        .put("durationMs", 1000)
}
