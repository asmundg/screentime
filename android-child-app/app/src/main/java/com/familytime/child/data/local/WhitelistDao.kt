package com.familytime.child.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for whitelist cache operations.
 */
@Dao
interface WhitelistDao {
    @Query("SELECT * FROM whitelist_cache WHERE familyId = :familyId")
    suspend fun getWhitelistForFamily(familyId: String): List<CachedWhitelistItem>

    @Query("SELECT * FROM whitelist_cache WHERE familyId = :familyId")
    fun getWhitelistForFamilyFlow(familyId: String): Flow<List<CachedWhitelistItem>>

    @Query("SELECT * FROM whitelist_cache WHERE identifier = :identifier LIMIT 1")
    suspend fun getWhitelistItem(identifier: String): CachedWhitelistItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedWhitelistItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CachedWhitelistItem)

    @Delete
    suspend fun delete(item: CachedWhitelistItem)

    @Query("DELETE FROM whitelist_cache WHERE familyId = :familyId")
    suspend fun clearFamily(familyId: String)

    @Query("DELETE FROM whitelist_cache")
    suspend fun clearAll()
}
