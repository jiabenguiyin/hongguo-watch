package com.hongguo.watch.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Coalesced local changes. The journal contains keys and deletion markers, not report payloads. */
@Entity(tableName = "library_changes", indices = [Index(value = ["kind", "seriesId"], unique = true)])
data class LibraryChangeEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val kind: String,
    val seriesId: String,
    val deleted: Boolean,
    @ColumnInfo(defaultValue = "0") val baseCursor: Long = 0L,
) {
    companion object {
        const val HISTORY = "history"
        const val FAVORITE = "favorite"
        const val HISTORY_CLEAR = "history_clear"
    }
}

@Dao
interface LibraryChangeDao {
    // REPLACE inserts a new sequence even when this key is already in the journal.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(change: LibraryChangeEntity)

    @Query("DELETE FROM library_changes WHERE kind = :kind")
    suspend fun discardKind(kind: String)

    @Query("DELETE FROM library_changes WHERE kind = :kind AND seriesId IN (:seriesIds)")
    suspend fun discardSeries(kind: String, seriesIds: List<String>)

    @Query("SELECT * FROM library_changes ORDER BY sequence ASC")
    suspend fun allInOrder(): List<LibraryChangeEntity>

    @Query("SELECT * FROM library_changes ORDER BY sequence ASC LIMIT :limit")
    suspend fun next(limit: Int): List<LibraryChangeEntity>

    @Query("SELECT baseCursor FROM library_changes WHERE kind = :kind AND seriesId = :seriesId")
    suspend fun baseCursorFor(kind: String, seriesId: String): Long?

    @Query("SELECT COUNT(*) FROM library_changes")
    fun observePending(): Flow<Long>

    @Query("DELETE FROM library_changes WHERE sequence IN (:sequences)")
    suspend fun acknowledge(sequences: List<Long>)

    @Query("UPDATE library_changes SET baseCursor = max(baseCursor, :cursor) WHERE kind = :kind AND seriesId = :seriesId AND sequence > :sentSequence")
    suspend fun rebaseNewer(kind: String, seriesId: String, sentSequence: Long, cursor: Long)

    @Query(
        """
        UPDATE library_changes SET baseCursor = max(baseCursor, :cursor)
        WHERE kind IN ('history', 'history_clear') AND sequence > :sentSequence
        """,
    )
    suspend fun rebaseAfterClear(sentSequence: Long, cursor: Long)
}
