package com.familytime.child.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for caching whitelist items locally.
 */
@Entity(tableName = "whitelist_cache")
data class CachedWhitelistItem(
    @PrimaryKey
    val identifier: String,
    val familyId: String,
    val platform: String,
    val displayName: String,
    val addedAt: Long, // Timestamp in millis
    val lastSynced: Long = System.currentTimeMillis()
)
