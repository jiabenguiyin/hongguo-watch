package com.hongguo.watch.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "library_sync_state")
data class LibrarySyncState(
    @PrimaryKey val id: Int = 1,
    val cursor: Long = 0L,
    val initialized: Boolean = false,
    val lastSyncAt: Long = 0L,
)

@Dao
interface LibrarySyncStateDao {
    @Query("SELECT * FROM library_sync_state WHERE id = 1")
    suspend fun read(): LibrarySyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: LibrarySyncState)
}
