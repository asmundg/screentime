package com.familytime.child.data.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

/**
 * Whitelist item model.
 * Firestore collection: whitelist/{id}
 *
 * Apps in the whitelist don't count toward time budget.
 * Matches Python model in shared/src/screentime_shared/models.py
 */
data class WhitelistItem(
    @PropertyName("familyId")
    val familyId: String = "",

    val platform: String = Platform.BOTH.value,

    val identifier: String = "", // package name for Android, exe name for Windows

    @PropertyName("displayName")
    val displayName: String = "",

    @PropertyName("addedAt")
    val addedAt: Timestamp? = null
) {
    fun getPlatformEnum(): Platform {
        return Platform.fromString(platform)
    }

    /**
     * Check if this whitelist item applies to the given platform.
     */
    fun appliesTo(targetPlatform: Platform): Boolean {
        val itemPlatform = getPlatformEnum()
        return itemPlatform == Platform.BOTH || itemPlatform == targetPlatform
    }

    /**
     * Check if this whitelist item matches the given package name.
     */
    fun matches(packageName: String, targetPlatform: Platform): Boolean {
        return appliesTo(targetPlatform) && identifier.equals(packageName, ignoreCase = true)
    }
}
