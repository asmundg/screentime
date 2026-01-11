package com.familytime.child.data.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

/**
 * Family document model.
 * Firestore collection: families/{familyId}
 *
 * Matches Python model in shared/src/screentime_shared/models.py
 */
data class Family(
    val name: String = "",

    @PropertyName("createdAt")
    val createdAt: Timestamp? = null,

    @PropertyName("ownerEmail")
    val ownerEmail: String = "",

    val members: Map<String, String> = emptyMap(), // email -> role mapping

    @PropertyName("timeTrackingMode")
    val timeTrackingMode: String = TimeTrackingMode.UNIFIED.value
) {
    fun getTimeTrackingModeEnum(): TimeTrackingMode {
        return TimeTrackingMode.fromString(timeTrackingMode)
    }
}
