package com.familytime.child.data.models

/**
 * Platform types for devices and whitelist items.
 * Matches Python enum in shared/src/screentime_shared/models.py
 */
enum class Platform(val value: String) {
    WINDOWS("windows"),
    ANDROID("android"),
    BOTH("both");

    companion object {
        fun fromString(value: String): Platform {
            return values().find { it.value == value }
                ?: throw IllegalArgumentException("Unknown platform: $value")
        }
    }
}

/**
 * Time tracking mode for families.
 * - UNIFIED: shared time budget across all devices
 * - PER_DEVICE: separate budget for each device
 */
enum class TimeTrackingMode(val value: String) {
    PER_DEVICE("per_device"),
    UNIFIED("unified");

    companion object {
        fun fromString(value: String): TimeTrackingMode {
            return values().find { it.value == value }
                ?: throw IllegalArgumentException("Unknown time tracking mode: $value")
        }
    }
}

/**
 * Status of extension requests.
 */
enum class RequestStatus(val value: String) {
    PENDING("pending"),
    APPROVED("approved"),
    DENIED("denied");

    companion object {
        fun fromString(value: String): RequestStatus {
            return values().find { it.value == value }
                ?: throw IllegalArgumentException("Unknown request status: $value")
        }
    }
}

/**
 * Family member roles.
 */
enum class MemberRole(val value: String) {
    OWNER("owner"),
    ADMIN("admin"),
    VIEWER("viewer");

    companion object {
        fun fromString(value: String): MemberRole {
            return values().find { it.value == value }
                ?: throw IllegalArgumentException("Unknown member role: $value")
        }
    }
}
