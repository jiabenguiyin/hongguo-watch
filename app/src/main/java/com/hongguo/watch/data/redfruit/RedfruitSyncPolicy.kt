// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.data.redfruit

import com.hongguo.watch.data.db.FavoriteEntity
import com.hongguo.watch.data.db.WatchHistoryEntity
import kotlin.math.abs

internal object RedfruitSyncPolicy {
    fun favoritesForLibrary(
        local: List<FavoriteEntity>,
        remote: List<FavoriteEntity>?,
        pending: Map<String, Boolean>,
    ): List<FavoriteEntity> {
        if (remote == null) return local
        return mergedFavorites(
            local = local,
            remote = remote.filter { pending[it.seriesId] != false },
            replaceLocal = true,
            preserveLocalIds = pending.filterValues { it }.keys,
        )
    }

    /**
     * Redfruit's cursor is a per-series row. Prefer the furthest episode so a
     * phone that has reached episode 350 cannot be replaced by a newer
     * timestamp from a watch that is still on episode 300. Within the same
     * episode, the latest write wins; position breaks an equal-timestamp tie.
     */
    fun compareProgress(
        first: WatchHistoryEntity,
        second: WatchHistoryEntity,
    ): Int {
        val episode = first.episodeNumber.compareTo(second.episodeNumber)
        if (episode != 0) return episode
        val updated = first.updatedAt.compareTo(second.updatedAt)
        if (updated != 0) return updated
        return first.positionMs.compareTo(second.positionMs)
    }

    fun mergedHistory(
        local: List<WatchHistoryEntity>,
        remote: List<WatchHistoryEntity>,
        replaceLocal: Boolean = false,
    ): List<WatchHistoryEntity> = latestBySeries(
        if (replaceLocal) remote else local + remote,
    )

    fun latestBySeries(rows: List<WatchHistoryEntity>): List<WatchHistoryEntity> =
        rows
            .groupBy { it.seriesId }
            .values
            .mapNotNull { entries ->
                val best = entries.maxWithOrNull(
                    compareBy<WatchHistoryEntity> { it.episodeNumber }
                        .thenBy { it.updatedAt }
                        .thenBy { it.positionMs },
                ) ?: return@mapNotNull null
                val maxCount = entries.maxOf { it.episodeCount }
                if (maxCount > best.episodeCount) best.copy(episodeCount = maxCount) else best
            }

    fun mergedFavorites(
        local: List<FavoriteEntity>,
        remote: List<FavoriteEntity>,
        replaceLocal: Boolean = false,
        preserveLocalIds: Set<String> = emptySet(),
    ): List<FavoriteEntity> {
        val candidates = if (replaceLocal) {
            remote + local.filter { it.seriesId in preserveLocalIds }
        } else {
            local + remote
        }
        return candidates
            .groupBy { it.seriesId }
            .values
            .map { rows ->
                val preferred = rows.maxByOrNull { it.updatedAt } ?: rows.first()
                val other = rows.firstOrNull { it !== preferred }
                preferred.copy(
                    seriesTitle = preferred.seriesTitle.ifBlank { other?.seriesTitle.orEmpty() },
                    cover = preferred.cover.ifBlank { other?.cover.orEmpty() },
                    episodeCount = maxOf(preferred.episodeCount, other?.episodeCount ?: 0),
                )
            }
    }

    fun progressToUpload(
        local: List<WatchHistoryEntity>,
        remote: List<WatchHistoryEntity>,
    ): List<WatchHistoryEntity> {
        val remoteBySeries = latestBySeries(remote).associateBy { it.seriesId }
        return local
            .filter { row ->
                remoteBySeries[row.seriesId]?.let { compareProgress(row, it) > 0 } ?: true
            }
            .sortedBy { it.updatedAt }
    }

    fun favoritesToAdd(
        local: List<FavoriteEntity>,
        remote: List<FavoriteEntity>,
    ): List<FavoriteEntity> {
        val remoteIds = remote.mapTo(mutableSetOf()) { it.seriesId }
        return local.filter { it.seriesId !in remoteIds }
    }

    fun confirmsAtLeastAsNew(
        local: WatchHistoryEntity,
        remote: WatchHistoryEntity?,
        positionToleranceMs: Long = 2_000L,
    ): Boolean {
        if (remote == null) return false
        if (compareProgress(remote, local) >= 0) return true
        return remote.episodeNumber == local.episodeNumber &&
            remote.updatedAt == local.updatedAt &&
            remote.episodeId == local.episodeId &&
            abs(remote.positionMs - local.positionMs) <= positionToleranceMs
    }
}
