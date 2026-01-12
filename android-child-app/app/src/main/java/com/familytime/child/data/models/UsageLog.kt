package com.familytime.child.data.models

import com.google.firebase.firestore.PropertyName

/**
 * Usage log model.
 * Firestore collection: usageLogs/{logId}
 *
 * Historical usage data for analytics.
 * Matches Python model in shared/src/screentime_shared/models.py
 */
data class UsageLog(
    @PropertyName("familyId")
    val familyId: String = "",

    @PropertyName("userId")
    val userId: String = "",

    @PropertyName("deviceId")
    val deviceId: String = "",

    val platform: String = Platform.ANDROID.value,

    val date: String = "", // YYYY-MM-DD

    @PropertyName("appIdentifier")
    val appIdentifier: String = "", // package name for Android

    @PropertyName("appDisplayName")
    val appDisplayName: String = "",

    val minutes: Double = 0.0,

    @PropertyName("wasWhitelisted")
    val wasWhitelisted: Boolean = false
) {
    fun getPlatformEnum(): Platform {
        return Platform.fromString(platform)
    }
}
