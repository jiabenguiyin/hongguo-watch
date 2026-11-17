package com.hongguo.watch.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val seriesId: String,
    val seriesTitle: String,
    val cover: String,
    val episodeId: String,
    val episodeNumber: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val source: String,
)

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE seriesId = :seriesId LIMIT 1")
    suspend fun find(seriesId: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchHistoryEntity)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}
