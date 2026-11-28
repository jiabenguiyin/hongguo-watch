package com.hongguo.watch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WatchHistoryEntity::class, FavoriteEntity::class, LibraryChangeEntity::class, LibrarySyncState::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun libraryChangeDao(): LibraryChangeDao
    abstract fun librarySyncStateDao(): LibrarySyncStateDao

    companion object {
        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorites` (
                        `seriesId` TEXT NOT NULL,
                        `seriesTitle` TEXT NOT NULL,
                        `cover` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `episodeCount` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`seriesId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `library_changes` (
                        `sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `seriesId` TEXT NOT NULL,
                        `deleted` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_changes_kind_seriesId` " +
                        "ON `library_changes` (`kind`, `seriesId`)",
                )
            }
        }

        private val migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE library_changes ADD COLUMN baseCursor INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS library_sync_state (" +
                        "id INTEGER NOT NULL PRIMARY KEY, cursor INTEGER NOT NULL, " +
                        "initialized INTEGER NOT NULL, lastSyncAt INTEGER NOT NULL)",
                )
            }
        }

        private val migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE watch_history ADD COLUMN episodeCount INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun create(context: Context, name: String = "hongguo_watch.db"): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(migration1To2, migration2To3, migration3To4, migration4To5)
                .build()
        }
    }
}
