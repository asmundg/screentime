package com.familytime.child.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for user state cache operations.
 */
@Dao
interface UserStateDao {
    @Query("SELECT * FROM user_state_cache WHERE userId = :userId LIMIT 1")
    suspend fun getUserState(userId: String): CachedUserState?

    @Query("SELECT * FROM user_state_cache WHERE userId = :userId LIMIT 1")
    fun getUserStateFlow(userId: String): Flow<CachedUserState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(userState: CachedUserState)

    @Update
    suspend fun update(userState: CachedUserState)

    @Delete
    suspend fun delete(userState: CachedUserState)

    @Query("DELETE FROM user_state_cache")
    suspend fun clearAll()

    @Query("UPDATE user_state_cache SET todayUsedMinutes = :newUsedMinutes WHERE userId = :userId")
    suspend fun updateUsedTime(userId: String, newUsedMinutes: Double)

    @Query("UPDATE user_state_cache SET todayUsedMinutes = 0.0, lastResetDate = :newDate WHERE userId = :userId")
    suspend fun resetDailyTime(userId: String, newDate: String)
}
