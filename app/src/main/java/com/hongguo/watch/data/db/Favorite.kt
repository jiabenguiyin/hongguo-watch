package com.hongguo.watch.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val seriesId: String,
    val seriesTitle: String,
    val cover: String,
    val source: String,
    val episodeCount: Int = 0,
    val updatedAt: Long,
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun list(limit: Int = 100): List<FavoriteEntity>

    @Query("SELECT * FROM favorites ORDER BY updatedAt DESC, seriesId ASC")
    suspend fun allForSync(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE seriesId = :seriesId LIMIT 1")
    suspend fun find(seriesId: String): FavoriteEntity?

    @Query("SELECT * FROM favorites WHERE seriesId IN (:seriesIds)")
    suspend fun findAll(seriesIds: List<String>): List<FavoriteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE seriesId = :seriesId)")
    suspend fun exists(seriesId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FavoriteEntity>)

    @Query(
        """
        UPDATE favorites
        SET seriesTitle = :seriesTitle, cover = :cover, source = :source, episodeCount = :episodeCount
        WHERE seriesId = :seriesId AND updatedAt = :expectedUpdatedAt
        """,
    )
    suspend fun updateMetadataIfCurrent(
        seriesId: String,
        expectedUpdatedAt: Long,
        seriesTitle: String,
        cover: String,
        source: String,
        episodeCount: Int,
    ): Int

    @Query("DELETE FROM favorites WHERE seriesId = :seriesId")
    suspend fun delete(seriesId: String)

    @Query("DELETE FROM favorites WHERE seriesId IN (:seriesIds)")
    suspend fun deleteAll(seriesIds: List<String>)

    @Query("DELETE FROM favorites")
    suspend fun clear()
}
