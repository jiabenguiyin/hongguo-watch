package com.hongguo.watch.data

import androidx.room.withTransaction
import com.hongguo.watch.data.db.AppDatabase
import com.hongguo.watch.data.db.FavoriteEntity
import com.hongguo.watch.data.db.LibraryChangeEntity
import com.hongguo.watch.data.db.LibraryChangeEntity.Companion.FAVORITE
import com.hongguo.watch.data.db.LibraryChangeEntity.Companion.HISTORY
import com.hongguo.watch.data.db.LibraryChangeEntity.Companion.HISTORY_CLEAR
import com.hongguo.watch.data.db.LibrarySyncState
import com.hongguo.watch.data.db.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

/** Keeps the visible library and the unsent local changes in the same Room transaction. */
internal class LocalLibrary(private val database: AppDatabase) {
    val history = database.watchHistoryDao()
    val favorites = database.favoriteDao()
    private val changes = database.libraryChangeDao()
    private val state = database.librarySyncStateDao()

    suspend fun saveProgress(record: WatchHistoryEntity) = database.withTransaction {
        history.upsert(record)
        recordChange(HISTORY, record.seriesId, deleted = false)
    }

    suspend fun clearHistory() = database.withTransaction {
        history.clear()
        changes.discardKind(HISTORY)
        recordChange(HISTORY_CLEAR, "", deleted = true)
    }

    suspend fun toggleFavorite(record: FavoriteEntity): Boolean = database.withTransaction {
        val isFavorite = !favorites.exists(record.seriesId)
        if (isFavorite) favorites.upsert(record) else favorites.delete(record.seriesId)
        recordChange(FAVORITE, record.seriesId, deleted = !isFavorite)
        isFavorite
    }

    private suspend fun recordChange(kind: String, seriesId: String, deleted: Boolean) {
        val base = changes.baseCursorFor(kind, seriesId) ?: syncState().cursor
        changes.record(LibraryChangeEntity(kind = kind, seriesId = seriesId, deleted = deleted, baseCursor = base))
    }

    fun observePending(): Flow<Long> = changes.observePending()

    suspend fun syncState(): LibrarySyncState = state.read() ?: LibrarySyncState()

    /** Reads the entire local library and the journal together for an explicit import. */
    suspend fun snapshot(): Snapshot = database.withTransaction {
        Snapshot(history.allForSync(), favorites.allForSync(), changes.allInOrder())
    }

    suspend fun batch(limit: Int): Batch = database.withTransaction {
        require(limit > 0)
        val pending = changes.next(limit)
        val historyIds = pending.filter { it.kind == HISTORY && !it.deleted }.map { it.seriesId }
        val favoriteIds = pending.filter { it.kind == FAVORITE && !it.deleted }.map { it.seriesId }
        Batch(
            cursor = syncState().cursor,
            changes = pending,
            history = historyIds.chunked(KEY_CHUNK_SIZE).flatMap { history.findAll(it) },
            favorites = favoriteIds.chunked(KEY_CHUNK_SIZE).flatMap { favorites.findAll(it) },
        )
    }

    suspend fun applySync(
        cursor: Long,
        history: List<WatchHistoryEntity>,
        favorites: List<FavoriteEntity>,
        sent: List<LibraryChangeEntity>,
        accepted: Map<Long, Long>,
        initialized: Boolean,
        syncedAt: Long,
    ) = database.withTransaction {
        for (change in sent) {
            val revision = accepted[change.sequence] ?: continue
            if (change.kind == HISTORY_CLEAR) {
                changes.rebaseAfterClear(change.sequence, revision)
            } else {
                changes.rebaseNewer(change.kind, change.seriesId, change.sequence, revision)
            }
        }
        // A conflict consumes the sent operation. A local edit after capture has a different sequence.
        for (sequences in sent.map { it.sequence }.chunked(KEY_CHUNK_SIZE)) {
            changes.acknowledge(sequences)
        }

        val pending = changes.allInOrder()
        val visibleHistory = if (pending.any { it.kind == HISTORY_CLEAR }) emptyList() else history
        mergeHistory(visibleHistory, pending.filter { it.kind == HISTORY }.map { it.seriesId }.toSet())
        mergeFavorites(favorites, pending.filter { it.kind == FAVORITE }.map { it.seriesId }.toSet())
        state.save(LibrarySyncState(cursor = cursor, initialized = initialized, lastSyncAt = syncedAt))
    }

    private suspend fun mergeHistory(remote: List<WatchHistoryEntity>, pending: Set<String>) {
        val local = history.allForSync().associateBy { it.seriesId }
        val removed = local.keys - remote.map { it.seriesId }.toSet() - pending
        for (keys in removed.chunked(KEY_CHUNK_SIZE)) {
            history.deleteAll(keys)
        }
        val changed = remote.filter { it.seriesId !in pending && local[it.seriesId] != it }
        if (changed.isNotEmpty()) history.upsertAll(changed)
    }

    private suspend fun mergeFavorites(remote: List<FavoriteEntity>, pending: Set<String>) {
        val local = favorites.allForSync().associateBy { it.seriesId }
        val removed = local.keys - remote.map { it.seriesId }.toSet() - pending
        for (keys in removed.chunked(KEY_CHUNK_SIZE)) {
            favorites.deleteAll(keys)
        }
        // Enriched display metadata is local. updatedAt changes when the favorite is added again.
        val changed = remote.filter { it.seriesId !in pending && local[it.seriesId]?.updatedAt != it.updatedAt }
        if (changed.isNotEmpty()) favorites.upsertAll(changed)
    }

    data class Snapshot(
        val history: List<WatchHistoryEntity>,
        val favorites: List<FavoriteEntity>,
        val changes: List<LibraryChangeEntity>,
    )

    data class Batch(
        val cursor: Long,
        val changes: List<LibraryChangeEntity>,
        val history: List<WatchHistoryEntity>,
        val favorites: List<FavoriteEntity>,
    )

    private companion object {
        const val KEY_CHUNK_SIZE = 500
    }
}
