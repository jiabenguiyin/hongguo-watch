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
    /** 系列总集数；播放/云端带回。筛选「是否看完」与封面 x集/y集 依赖此字段，不能只靠收藏。 */
    val episodeCount: Int = 0,
)

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC, seriesId ASC")
    suspend fun allForSync(): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE seriesId = :seriesId LIMIT 1")
    suspend fun find(seriesId: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE seriesId IN (:seriesIds)")
    suspend fun findAll(seriesIds: List<String>): List<WatchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WatchHistoryEntity>)

    /** 仅抬高总集数，不改进度、不记 sync change（展示/筛选用元数据）。 */
    @Query(
        """
        UPDATE watch_history
        SET episodeCount = :episodeCount
        WHERE seriesId = :seriesId AND episodeCount < :episodeCount
        """,
    )
    suspend fun bumpEpisodeCount(seriesId: String, episodeCount: Int): Int

    @Query("DELETE FROM watch_history WHERE seriesId IN (:seriesIds)")
    suspend fun deleteAll(seriesIds: List<String>)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}
