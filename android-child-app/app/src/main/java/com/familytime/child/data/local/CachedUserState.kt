package com.familytime.child.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for caching user state locally.
 */
@Entity(tableName = "user_state_cache")
data class CachedUserState(
    @PrimaryKey
    val userId: String,
    val familyId: String,
    val name: String,
    val dailyLimitMinutes: Int,
    val todayUsedMinutes: Double,
    val lastResetDate: String, // YYYY-MM-DD
    val lastSynced: Long = System.currentTimeMillis()
)
