package com.familytime.child.data.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

/**
 * Device document model.
 * Firestore collection: devices/{deviceId}
 *
 * Matches Python model in shared/src/screentime_shared/models.py
 */
data class Device(
    @PropertyName("familyId")
    val familyId: String = "",

    @PropertyName("userId")
    val userId: String = "",

    val name: String = "",

    val platform: String = Platform.ANDROID.value,

    @PropertyName("lastSeen")
    val lastSeen: Timestamp? = null,

    @PropertyName("currentApp")
    val currentApp: String? = null,

    @PropertyName("currentAppPackage")
    val currentAppPackage: String? = null,

    @PropertyName("isLocked")
    val isLocked: Boolean = false,

    @PropertyName("fcmToken")
    val fcmToken: String? = null
) {
    fun getPlatformEnum(): Platform {
        return Platform.fromString(platform)
    }
}
