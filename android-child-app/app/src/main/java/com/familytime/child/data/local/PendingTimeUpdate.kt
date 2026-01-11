package com.familytime.child.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for storing pending time updates to sync when online.
 */
@Entity(tableName = "pending_time_updates")
data class PendingTimeUpdate(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val userId: String,
    val secondsToAdd: Int,
    val timestamp: Long = System.currentTimeMillis()
)
