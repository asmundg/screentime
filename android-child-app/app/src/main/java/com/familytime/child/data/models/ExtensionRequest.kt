package com.familytime.child.data.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

/**
 * Extension request model.
 * Firestore collection: extensionRequests/{requestId}
 *
 * Matches Python model in shared/src/screentime_shared/models.py
 */
data class ExtensionRequest(
    @PropertyName("familyId")
    val familyId: String = "",

    @PropertyName("userId")
    val userId: String = "",

    @PropertyName("deviceId")
    val deviceId: String = "",

    @PropertyName("deviceName")
    val deviceName: String = "",

    val platform: String = Platform.ANDROID.value,

    @PropertyName("requestedMinutes")
    val requestedMinutes: Int = 0,

    val reason: String? = null,

    val status: String = RequestStatus.PENDING.value,

    @PropertyName("createdAt")
    val createdAt: Timestamp? = null,

    @PropertyName("respondedAt")
    val respondedAt: Timestamp? = null
) {
    fun getStatusEnum(): RequestStatus {
        return RequestStatus.fromString(status)
    }

    fun getPlatformEnum(): Platform {
        return Platform.fromString(platform)
    }
}
