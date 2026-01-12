package com.familytime.child.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for local caching.
 */
@Database(
    entities = [
        CachedWhitelistItem::class,
        CachedUserState::class,
        PendingTimeUpdate::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun whitelistDao(): WhitelistDao
    abstract fun userStateDao(): UserStateDao
    abstract fun pendingTimeDao(): PendingTimeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "screentime_database"
                )
                    .fallbackToDestructiveMigration() // For development; use migrations in production
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
