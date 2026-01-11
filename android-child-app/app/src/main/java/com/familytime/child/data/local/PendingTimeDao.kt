package com.familytime.child.data.local

import androidx.room.*

/**
 * DAO for pending time updates.
 */
@Dao
interface PendingTimeDao {
    @Query("SELECT * FROM pending_time_updates ORDER BY timestamp ASC")
    suspend fun getAllPending(): List<PendingTimeUpdate>

    @Query("SELECT * FROM pending_time_updates WHERE userId = :userId ORDER BY timestamp ASC")
    suspend fun getPendingForUser(userId: String): List<PendingTimeUpdate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(update: PendingTimeUpdate)

    @Delete
    suspend fun delete(update: PendingTimeUpdate)

    @Query("DELETE FROM pending_time_updates WHERE userId = :userId")
    suspend fun clearUser(userId: String)

    @Query("DELETE FROM pending_time_updates")
    suspend fun clearAll()
}
