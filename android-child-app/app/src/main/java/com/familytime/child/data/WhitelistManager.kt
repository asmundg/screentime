package com.familytime.child.data

import android.content.Context
import android.util.Log
import com.familytime.child.data.models.WhitelistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manager for whitelist operations with caching.
 *
 * Coordinates between Firebase and local cache for whitelist data.
 */
class WhitelistManager(
    private val context: Context,
    private val firebaseRepository: FirebaseRepository = FirebaseRepository(),
    private val localCache: LocalCache = LocalCache(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedWhitelist: List<WhitelistItem> = emptyList()

    companion object {
        private const val TAG = "WhitelistManager"
    }

    /**
     * Refresh whitelist from Firestore and update cache.
     */
    suspend fun refreshWhitelist(familyId: String): Result<List<WhitelistItem>> {
        return try {
            val result = firebaseRepository.getWhitelist(familyId)

            result.onSuccess { items ->
                cachedWhitelist = items
                // Update local database cache
                localCache.cacheWhitelist(items)
                Log.d(TAG, "Refreshed whitelist: ${items.size} items")
            }.onFailure { error ->
                Log.e(TAG, "Failed to refresh whitelist from Firebase, using cached", error)
                // Fall back to cached whitelist
                cachedWhitelist = localCache.getCachedWhitelist(familyId)
            }

            Result.success(cachedWhitelist)
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing whitelist", e)
            // Return cached data as fallback
            cachedWhitelist = localCache.getCachedWhitelist(familyId)
            Result.success(cachedWhitelist)
        }
    }

    /**
     * Check if a package name is whitelisted.
     */
    fun isWhitelisted(packageName: String): Boolean {
        return firebaseRepository.isWhitelisted(packageName, cachedWhitelist)
    }

    /**
     * Get current cached whitelist.
     */
    fun getCachedWhitelist(): List<WhitelistItem> {
        return cachedWhitelist
    }

    /**
     * Load whitelist from local cache on startup.
     */
    suspend fun loadFromCache(familyId: String) {
        try {
            cachedWhitelist = localCache.getCachedWhitelist(familyId)
            Log.d(TAG, "Loaded ${cachedWhitelist.size} whitelist items from cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load whitelist from cache", e)
        }
    }
}
