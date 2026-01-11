package com.familytime.child.data.models

import com.google.firebase.firestore.PropertyName

/**
 * User (child) document model.
 * Firestore collection: users/{userId}
 *
 * Represents a child user in the family.
 * Matches Python model in shared/src/screentime_shared/models.py
 */
data class User(
    @PropertyName("familyId")
    val familyId: String = "",

    val name: String = "",

    @PropertyName("dailyLimitMinutes")
    val dailyLimitMinutes: Int = 120,

    @PropertyName("todayUsedMinutes")
    val todayUsedMinutes: Double = 0.0,

    @PropertyName("lastResetDate")
    val lastResetDate: String = "", // YYYY-MM-DD

    @PropertyName("windowsUsername")
    val windowsUsername: String? = null
) {
    /**
     * Check if time limit has been exceeded.
     */
    fun isLimitExceeded(): Boolean {
        return todayUsedMinutes >= dailyLimitMinutes
    }

    /**
     * Get remaining minutes.
     */
    fun getRemainingMinutes(): Double {
        val remaining = dailyLimitMinutes - todayUsedMinutes
        return if (remaining > 0) remaining else 0.0
    }
}
