package com.familytime.child.utils

import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility functions for working with time.
 */
object TimeUtils {
    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * Get today's date in YYYY-MM-DD format.
     */
    fun getTodayDate(): String {
        return DATE_FORMAT.format(Date())
    }

    /**
     * Check if the given date is today.
     */
    fun isToday(dateString: String): Boolean {
        return dateString == getTodayDate()
    }

    /**
     * Format minutes to human-readable string.
     */
    fun formatMinutes(minutes: Double): String {
        val totalMinutes = minutes.toInt()
        val hours = totalMinutes / 60
        val remainingMinutes = totalMinutes % 60

        return when {
            hours > 0 && remainingMinutes > 0 -> "${hours}h ${remainingMinutes}m"
            hours > 0 -> "${hours}h"
            else -> "${remainingMinutes}m"
        }
    }

    /**
     * Get seconds elapsed since a timestamp.
     */
    fun getSecondsElapsed(timestampMillis: Long): Int {
        val elapsed = System.currentTimeMillis() - timestampMillis
        return (elapsed / 1000).toInt().coerceAtLeast(0)
    }
}
