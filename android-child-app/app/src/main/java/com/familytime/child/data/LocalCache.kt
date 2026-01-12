package com.familytime.child.data

import android.content.Context
import android.util.Log
import com.familytime.child.data.local.*
import com.familytime.child.data.models.Platform
import com.familytime.child.data.models.User
import com.familytime.child.data.models.WhitelistItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local cache manager using Room database.
 *
 * Provides offline support by caching:
 * - Whitelist items
 * - User state (daily limit, used time, reset date)
 * - Pending time updates to sync when reconnected
 */
class LocalCache(context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val whitelistDao = database.whitelistDao()
    private val userStateDao = database.userStateDao()
    private val pendingTimeDao = database.pendingTimeDao()

    companion object {
        private const val TAG = "LocalCache"
    }

    // ============================================================
    // Whitelist Cache
    // ============================================================

    /**
     * Cache whitelist items from Firestore.
     */
    suspend fun cacheWhitelist(items: List<WhitelistItem>) {
        try {
            val cachedItems = items.map { item ->
                CachedWhitelistItem(
                    identifier = item.identifier,
                    familyId = item.familyId,
                    platform = item.platform,
                    displayName = item.displayName,
                    addedAt = item.addedAt?.toDate()?.time ?: 0L,
                    lastSynced = System.currentTimeMillis()
                )
            }

            whitelistDao.insertAll(cachedItems)
            Log.d(TAG, "Cached ${cachedItems.size} whitelist items")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache whitelist", e)
        }
    }

    /**
     * Get cached whitelist for a family.
     */
    suspend fun getCachedWhitelist(familyId: String): List<WhitelistItem> {
        return try {
            val cached = whitelistDao.getWhitelistForFamily(familyId)

            cached.map { item ->
                WhitelistItem(
                    familyId = item.familyId,
                    platform = item.platform,
                    identifier = item.identifier,
                    displayName = item.displayName,
                    addedAt = com.google.firebase.Timestamp(item.addedAt / 1000, 0)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cached whitelist", e)
            emptyList()
        }
    }

    /**
     * Check if a package is whitelisted (from cache).
     */
    suspend fun isWhitelistedCached(packageName: String, familyId: String): Boolean {
        return try {
            val items = getCachedWhitelist(familyId)
            items.any { it.matches(packageName, Platform.ANDROID) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check whitelist cache", e)
            false
        }
    }

    // ============================================================
    // User State Cache
    // ============================================================

    /**
     * Cache user state from Firestore.
     */
    suspend fun cacheUserState(user: User, userId: String) {
        try {
            val cached = CachedUserState(
                userId = userId,
                familyId = user.familyId,
                name = user.name,
                dailyLimitMinutes = user.dailyLimitMinutes,
                todayUsedMinutes = user.todayUsedMinutes,
                lastResetDate = user.lastResetDate,
                lastSynced = System.currentTimeMillis()
            )

            userStateDao.insert(cached)
            Log.d(TAG, "Cached user state for $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache user state", e)
        }
    }

    /**
     * Get cached user state.
     */
    suspend fun getCachedUserState(userId: String): CachedUserState? {
        return try {
            userStateDao.getUserState(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cached user state", e)
            null
        }
    }

    /**
     * Get cached user state as Flow (for reactive updates).
     */
    fun getCachedUserStateFlow(userId: String): Flow<CachedUserState?> {
        return userStateDao.getUserStateFlow(userId)
    }

    /**
     * Update cached used time.
     */
    suspend fun updateCachedUsedTime(userId: String, newUsedMinutes: Double) {
        try {
            userStateDao.updateUsedTime(userId, newUsedMinutes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update cached used time", e)
        }
    }

    /**
     * Reset cached daily time.
     */
    suspend fun resetCachedDailyTime(userId: String, newDate: String) {
        try {
            userStateDao.resetDailyTime(userId, newDate)
            Log.d(TAG, "Reset cached daily time for $userId to $newDate")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset cached daily time", e)
        }
    }

    // ============================================================
    // Pending Time Updates (for offline mode)
    // ============================================================

    /**
     * Queue a time update to sync later when online.
     */
    suspend fun queuePendingTimeUpdate(userId: String, secondsToAdd: Int) {
        try {
            val pending = PendingTimeUpdate(
                userId = userId,
                secondsToAdd = secondsToAdd,
                timestamp = System.currentTimeMillis()
            )

            pendingTimeDao.insert(pending)
            Log.d(TAG, "Queued pending time update: +$secondsToAdd sec for $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue pending time update", e)
        }
    }

    /**
     * Get all pending time updates for a user.
     */
    suspend fun getPendingTimeUpdates(userId: String): List<PendingTimeUpdate> {
        return try {
            pendingTimeDao.getPendingForUser(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get pending time updates", e)
            emptyList()
        }
    }

    /**
     * Clear pending time updates after syncing.
     */
    suspend fun clearPendingTimeUpdates(updates: List<PendingTimeUpdate>) {
        try {
            updates.forEach { pendingTimeDao.delete(it) }
            Log.d(TAG, "Cleared ${updates.size} pending time updates")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear pending time updates", e)
        }
    }

    /**
     * Clear all pending time updates for a user.
     */
    suspend fun clearPendingTimeUpdatesForUser(userId: String) {
        try {
            pendingTimeDao.clearUser(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear pending time updates for user", e)
        }
    }

    // ============================================================
    // Utility
    // ============================================================

    /**
     * Clear all caches (for logout or reset).
     */
    suspend fun clearAll() {
        try {
            whitelistDao.clearAll()
            userStateDao.clearAll()
            pendingTimeDao.clearAll()
            Log.d(TAG, "Cleared all caches")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all caches", e)
        }
    }
}
