package com.familytime.child.data

import android.util.Log
import com.familytime.child.data.models.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repository for Firebase Firestore operations.
 *
 * Handles all backend communication for the Android child app,
 * including time tracking, whitelist management, extension requests, and logging.
 */
class FirebaseRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    companion object {
        private const val TAG = "FirebaseRepository"
        private const val COLLECTION_USERS = "users"
        private const val COLLECTION_DEVICES = "devices"
        private const val COLLECTION_WHITELIST = "whitelist"
        private const val COLLECTION_EXTENSION_REQUESTS = "extensionRequests"
        private const val COLLECTION_USAGE_LOGS = "usageLogs"
        private const val COLLECTION_FAMILIES = "families"

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    init {
        // Enable offline persistence
        firestore.firestoreSettings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .build()
    }

    // ============================================================
    // Time Tracking
    // ============================================================

    /**
     * Atomically increment the user's today_used_minutes.
     * Uses Firestore transaction to prevent race conditions when multiple devices update simultaneously.
     */
    suspend fun incrementUsedTime(userId: String, secondsToAdd: Int): Result<Double> {
        return try {
            val minutesToAdd = secondsToAdd / 60.0
            val userRef = firestore.collection(COLLECTION_USERS).document(userId)

            val newTotal = firestore.runTransaction { transaction ->
                val snapshot = transaction.get(userRef)
                val currentMinutes = snapshot.getDouble("todayUsedMinutes") ?: 0.0
                val newMinutes = currentMinutes + minutesToAdd

                transaction.update(userRef, mapOf(
                    "todayUsedMinutes" to newMinutes
                ))

                newMinutes
            }.await()

            Log.d(TAG, "Incremented time: +$minutesToAdd min, total=$newTotal")
            Result.success(newTotal)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to increment used time", e)
            Result.failure(e)
        }
    }

    /**
     * Get the user document.
     */
    suspend fun getUser(userId: String): Result<User> {
        return try {
            val snapshot = firestore.collection(COLLECTION_USERS)
                .document(userId)
                .get()
                .await()

            val user = snapshot.toObject(User::class.java)
            if (user != null) {
                Result.success(user)
            } else {
                Result.failure(Exception("User not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user", e)
            Result.failure(e)
        }
    }

    /**
     * Check if daily counter needs reset (new day).
     * If today's date != lastResetDate, reset todayUsedMinutes to 0.
     */
    suspend fun checkAndResetDaily(userId: String): Result<Boolean> {
        return try {
            val today = DATE_FORMAT.format(Date())
            val userRef = firestore.collection(COLLECTION_USERS).document(userId)

            val wasReset = firestore.runTransaction { transaction ->
                val snapshot = transaction.get(userRef)
                val lastResetDate = snapshot.getString("lastResetDate") ?: ""

                if (lastResetDate != today) {
                    // Reset for new day
                    transaction.update(userRef, mapOf(
                        "todayUsedMinutes" to 0.0,
                        "lastResetDate" to today
                    ))
                    Log.d(TAG, "Reset daily counter for user $userId")
                    true
                } else {
                    false
                }
            }.await()

            Result.success(wasReset)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check/reset daily counter", e)
            Result.failure(e)
        }
    }

    // ============================================================
    // Whitelisting
    // ============================================================

    /**
     * Get all whitelist items for a family.
     * Filters for items that apply to Android platform.
     */
    suspend fun getWhitelist(familyId: String): Result<List<WhitelistItem>> {
        return try {
            val snapshot = firestore.collection(COLLECTION_WHITELIST)
                .whereEqualTo("familyId", familyId)
                .get()
                .await()

            val items = snapshot.documents.mapNotNull { doc ->
                doc.toObject(WhitelistItem::class.java)
            }.filter { item ->
                item.appliesTo(Platform.ANDROID)
            }

            Log.d(TAG, "Fetched ${items.size} whitelist items for family $familyId")
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get whitelist", e)
            Result.failure(e)
        }
    }

    /**
     * Check if a package name is whitelisted.
     */
    fun isWhitelisted(packageName: String, whitelistItems: List<WhitelistItem>): Boolean {
        return whitelistItems.any { item ->
            item.matches(packageName, Platform.ANDROID)
        }
    }

    // ============================================================
    // Extension Requests
    // ============================================================

    /**
     * Create a new extension request.
     */
    suspend fun createExtensionRequest(
        familyId: String,
        userId: String,
        deviceId: String,
        deviceName: String,
        requestedMinutes: Int,
        reason: String?
    ): Result<String> {
        return try {
            val request = ExtensionRequest(
                familyId = familyId,
                userId = userId,
                deviceId = deviceId,
                deviceName = deviceName,
                platform = Platform.ANDROID.value,
                requestedMinutes = requestedMinutes,
                reason = reason,
                status = RequestStatus.PENDING.value,
                createdAt = Timestamp.now()
            )

            val docRef = firestore.collection(COLLECTION_EXTENSION_REQUESTS)
                .add(request)
                .await()

            Log.d(TAG, "Created extension request: ${docRef.id}")
            Result.success(docRef.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create extension request", e)
            Result.failure(e)
        }
    }

    /**
     * Get and clear approved extension requests for this device.
     * Returns the total minutes approved and marks requests as processed.
     */
    suspend fun getAndClearApprovedExtensions(deviceId: String): Result<Int> {
        return try {
            val snapshot = firestore.collection(COLLECTION_EXTENSION_REQUESTS)
                .whereEqualTo("deviceId", deviceId)
                .whereEqualTo("status", RequestStatus.APPROVED.value)
                .get()
                .await()

            var totalMinutes = 0
            val requestIds = mutableListOf<String>()

            snapshot.documents.forEach { doc ->
                val request = doc.toObject(ExtensionRequest::class.java)
                if (request != null) {
                    totalMinutes += request.requestedMinutes
                    requestIds.add(doc.id)
                }
            }

            // Delete processed requests
            requestIds.forEach { id ->
                firestore.collection(COLLECTION_EXTENSION_REQUESTS)
                    .document(id)
                    .delete()
                    .await()
            }

            if (totalMinutes > 0) {
                Log.d(TAG, "Found $totalMinutes approved extension minutes")
            }

            Result.success(totalMinutes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get approved extensions", e)
            Result.failure(e)
        }
    }

    /**
     * Apply approved extension minutes to user's time budget.
     */
    suspend fun applyExtensionMinutes(userId: String, minutes: Int): Result<Unit> {
        return try {
            val userRef = firestore.collection(COLLECTION_USERS).document(userId)

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(userRef)
                val currentUsed = snapshot.getDouble("todayUsedMinutes") ?: 0.0
                val newUsed = (currentUsed - minutes).coerceAtLeast(0.0)

                transaction.update(userRef, "todayUsedMinutes", newUsed)
            }.await()

            Log.d(TAG, "Applied extension: -$minutes minutes")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply extension minutes", e)
            Result.failure(e)
        }
    }

    // ============================================================
    // Device Status
    // ============================================================

    /**
     * Update device status fields: currentApp, currentAppPackage, lastSeen, fcmToken.
     */
    suspend fun updateDeviceStatus(
        deviceId: String,
        currentApp: String?,
        currentAppPackage: String?,
        fcmToken: String? = null
    ): Result<Unit> {
        return try {
            val updates = mutableMapOf<String, Any>(
                "lastSeen" to FieldValue.serverTimestamp()
            )

            currentApp?.let { updates["currentApp"] = it }
            currentAppPackage?.let { updates["currentAppPackage"] = it }
            fcmToken?.let { updates["fcmToken"] = it }

            firestore.collection(COLLECTION_DEVICES)
                .document(deviceId)
                .update(updates)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update device status", e)
            Result.failure(e)
        }
    }

    /**
     * Get device document.
     */
    suspend fun getDevice(deviceId: String): Result<Device> {
        return try {
            val snapshot = firestore.collection(COLLECTION_DEVICES)
                .document(deviceId)
                .get()
                .await()

            val device = snapshot.toObject(Device::class.java)
            if (device != null) {
                Result.success(device)
            } else {
                Result.failure(Exception("Device not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get device", e)
            Result.failure(e)
        }
    }

    // ============================================================
    // Usage Logging
    // ============================================================

    /**
     * Log app usage for analytics.
     */
    suspend fun logAppUsage(
        familyId: String,
        userId: String,
        deviceId: String,
        appIdentifier: String,
        appDisplayName: String,
        minutes: Double,
        wasWhitelisted: Boolean
    ): Result<Unit> {
        return try {
            val today = DATE_FORMAT.format(Date())

            val log = UsageLog(
                familyId = familyId,
                userId = userId,
                deviceId = deviceId,
                platform = Platform.ANDROID.value,
                date = today,
                appIdentifier = appIdentifier,
                appDisplayName = appDisplayName,
                minutes = minutes,
                wasWhitelisted = wasWhitelisted
            )

            firestore.collection(COLLECTION_USAGE_LOGS)
                .add(log)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            // Don't fail the app if logging fails
            Log.e(TAG, "Failed to log usage (non-critical)", e)
            Result.success(Unit)
        }
    }

    // ============================================================
    // Family
    // ============================================================

    /**
     * Get family document.
     */
    suspend fun getFamily(familyId: String): Result<Family> {
        return try {
            val snapshot = firestore.collection(COLLECTION_FAMILIES)
                .document(familyId)
                .get()
                .await()

            val family = snapshot.toObject(Family::class.java)
            if (family != null) {
                Result.success(family)
            } else {
                Result.failure(Exception("Family not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get family", e)
            Result.failure(e)
        }
    }
}
