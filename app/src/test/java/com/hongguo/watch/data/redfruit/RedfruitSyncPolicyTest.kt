// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.data.redfruit

import com.hongguo.watch.data.db.FavoriteEntity
import com.hongguo.watch.data.db.WatchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedfruitSyncPolicyTest {
    @Test
    fun newerCloudProgressWinsAndLocalOnlyOrNewerRowsUpload() {
        val local = listOf(
            history("older-cloud", updatedAt = 100),
            history("newer-local", updatedAt = 300),
            history("local-only", updatedAt = 200),
        )
        val remote = listOf(
            history("older-cloud", updatedAt = 150),
            history("newer-local", updatedAt = 250),
        )

        assertEquals(
            listOf("local-only", "newer-local"),
            RedfruitSyncPolicy.progressToUpload(local, remote).map { it.seriesId },
        )
    }

    @Test
    fun furthestEpisodeWinsEvenWhenItsTimestampIsOlder() {
        val local = history("series", updatedAt = 900, episodeNumber = 300)
        val remote = history("series", updatedAt = 100, episodeNumber = 350)

        assertEquals(
            350,
            RedfruitSyncPolicy.mergedHistory(listOf(local), listOf(remote)).single().episodeNumber,
        )
        assertEquals(
            emptyList<String>(),
            RedfruitSyncPolicy.progressToUpload(listOf(local), listOf(remote))
                .map { it.seriesId },
        )
    }

    @Test
    fun duplicateCloudRowsAreReducedByEpisodeBeforeTimestamp() {
        val olderEpisode = history("series", updatedAt = 200, episodeNumber = 300)
        val furthestEpisode = history("series", updatedAt = 100, episodeNumber = 350)

        assertEquals(
            350,
            RedfruitSyncPolicy.mergedHistory(
                local = emptyList(),
                remote = listOf(olderEpisode, furthestEpisode),
                replaceLocal = true,
            ).single().episodeNumber,
        )
        assertEquals(
            emptyList<String>(),
            RedfruitSyncPolicy.progressToUpload(
                local = listOf(history("series", updatedAt = 50, episodeNumber = 340)),
                remote = listOf(olderEpisode, furthestEpisode),
            ).map { it.seriesId },
        )
    }

    @Test
    fun initialFavoriteMergeAddsMissingItemsWithoutRemovingCloudItems() {
        val local = listOf(favorite("local-item"), favorite("shared-item"))
        val remote = listOf(favorite("shared-item"), favorite("cloud-only"))

        assertEquals(
            setOf("local-item", "shared-item", "cloud-only"),
            RedfruitSyncPolicy.mergedFavorites(local, remote).map { it.seriesId }.toSet(),
        )
        assertEquals(
            listOf("local-item"),
            RedfruitSyncPolicy.favoritesToAdd(local, remote).map { it.seriesId },
        )
    }

    @Test
    fun cloudFavoriteRefreshRemovesPhoneDeletedItemsButPreservesPendingWatchAdds() {
        val local = listOf(favorite("deleted-on-phone"), favorite("pending-watch-add"))
        val remote = listOf(favorite("cloud-only"))

        assertEquals(
            setOf("cloud-only", "pending-watch-add"),
            RedfruitSyncPolicy.mergedFavorites(
                local = local,
                remote = remote,
                replaceLocal = true,
                preserveLocalIds = setOf("pending-watch-add"),
            ).map { it.seriesId }.toSet(),
        )
    }

    @Test
    fun libraryFavoritesUseCloudAndPreservePendingWatchChanges() {
        val local = listOf(
            favorite("deleted-on-phone"),
            favorite("pending-watch-add"),
            favorite("stale-local-only"),
        )
        val remote = listOf(
            favorite("deleted-on-phone"),
            favorite("cloud-only"),
        )

        assertEquals(
            setOf("cloud-only", "pending-watch-add"),
            RedfruitSyncPolicy.favoritesForLibrary(
                local = local,
                remote = remote,
                pending = mapOf("deleted-on-phone" to false, "pending-watch-add" to true),
            ).map { it.seriesId }.toSet(),
        )
    }

    @Test
    fun libraryFavoritesUseLocalSnapshotUntilCloudHasBeenRead() {
        val local = listOf(favorite("local-cache"))

        assertEquals(
            local,
            RedfruitSyncPolicy.favoritesForLibrary(local, remote = null, pending = emptyMap()),
        )
    }

    @Test
    fun readBackMustShowSameProgressOrAnewerCloudCursor() {
        val local = history("item", updatedAt = 200, positionMs = 10_000)

        assertTrue(RedfruitSyncPolicy.confirmsAtLeastAsNew(local, local.copy(positionMs = 9_000)))
        assertTrue(RedfruitSyncPolicy.confirmsAtLeastAsNew(local, local.copy(updatedAt = 201)))
        assertTrue(
            RedfruitSyncPolicy.confirmsAtLeastAsNew(
                local.copy(episodeNumber = 300, updatedAt = 900),
                local.copy(episodeNumber = 350, updatedAt = 100),
            ),
        )
        assertFalse(RedfruitSyncPolicy.confirmsAtLeastAsNew(local, local.copy(updatedAt = 199)))
        assertFalse(RedfruitSyncPolicy.confirmsAtLeastAsNew(local, null))
    }

    private fun history(
        seriesId: String,
        updatedAt: Long,
        positionMs: Long = 10_000,
        episodeNumber: Int = 1,
    ) = WatchHistoryEntity(
        seriesId = seriesId,
        seriesTitle = seriesId,
        cover = "",
        episodeId = "episode-$seriesId",
        episodeNumber = episodeNumber,
        positionMs = positionMs,
        durationMs = 20_000,
        updatedAt = updatedAt,
        source = "APP",
    )

    private fun favorite(seriesId: String) = FavoriteEntity(
        seriesId = seriesId,
        seriesTitle = seriesId,
        cover = "",
        source = "APP",
        updatedAt = 1,
    )
}
