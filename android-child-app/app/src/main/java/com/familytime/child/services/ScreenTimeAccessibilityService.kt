package com.familytime.child.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.familytime.child.data.FirebaseRepository
import com.familytime.child.data.LocalCache
import com.familytime.child.data.WhitelistManager
import com.familytime.child.ui.BlockingOverlayService
import com.familytime.child.utils.AppUtils
import com.familytime.child.utils.TimeUtils
import kotlinx.coroutines.*

/**
 * Accessibility Service for detecting foreground app changes.
 *
 * This is the core monitoring component that:
 * 1. Detects when user switches to a different app
 * 2. Checks if the app is whitelisted
 * 3. Increments time budget if not whitelisted
 * 4. Shows/hides blocking overlay based on time limit
 *
 * Matches the specification in README.md lines 316-350.
 */
class ScreenTimeAccessibilityService : AccessibilityService() {

    private lateinit var firebaseRepository: FirebaseRepository
    private lateinit var localCache: LocalCache
    private lateinit var whitelistManager: WhitelistManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentPackageName: String? = null
    private var lastAppSwitchTime: Long = 0
    private var userId: String? = null
    private var deviceId: String? = null
    private var familyId: String? = null

    // Tracking state
    @Volatile
    private var isTimeLimitExceeded = false

    companion object {
        private const val TAG = "AccessibilityService"
        private const val MIN_TRACKING_SECONDS = 10 // Minimum time to track before incrementing
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AccessibilityService created")

        firebaseRepository = FirebaseRepository()
        localCache = LocalCache(this)
        whitelistManager = WhitelistManager(this, firebaseRepository, localCache)

        // Load device configuration
        loadDeviceConfiguration()

        // Start monitoring service if not already running
        startMonitoringService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // We only care about window state changes (app switches)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return

        // Ignore system packages
        if (AppUtils.isSystemPackage(packageName)) {
            return
        }

        // If same app, ignore
        if (packageName == currentPackageName) {
            return
        }

        // App switch detected
        onAppSwitch(packageName)
    }

    /**
     * Handle app switch event.
     */
    private fun onAppSwitch(newPackageName: String) {
        serviceScope.launch {
            try {
                val now = System.currentTimeMillis()

                // If we were tracking a previous app, record its time
                if (currentPackageName != null && lastAppSwitchTime > 0) {
                    val secondsElapsed = TimeUtils.getSecondsElapsed(lastAppSwitchTime)

                    if (secondsElapsed >= MIN_TRACKING_SECONDS) {
                        recordAppTime(currentPackageName!!, secondsElapsed)
                    }
                }

                // Update current app
                currentPackageName = newPackageName
                lastAppSwitchTime = now

                // Update device status in Firestore
                updateDeviceStatus(newPackageName)

                // Check if we should show/hide blocking overlay
                checkAndUpdateBlockingState(newPackageName)

                Log.d(TAG, "App switch: $newPackageName")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling app switch", e)
            }
        }
    }

    /**
     * Record time spent in an app.
     */
    private suspend fun recordAppTime(packageName: String, secondsElapsed: Int) {
        try {
            val uid = userId ?: return
            val fid = familyId ?: return
            val did = deviceId ?: return

            // Check if whitelisted
            val isWhitelisted = whitelistManager.isWhitelisted(packageName)

            if (!isWhitelisted) {
                // Increment time budget
                val result = firebaseRepository.incrementUsedTime(uid, secondsElapsed)

                result.onSuccess { newTotal ->
                    // Update local cache
                    localCache.updateCachedUsedTime(uid, newTotal)

                    // Check if limit exceeded
                    val userState = localCache.getCachedUserState(uid)
                    if (userState != null) {
                        isTimeLimitExceeded = newTotal >= userState.dailyLimitMinutes
                    }

                    Log.d(TAG, "Time recorded: +${secondsElapsed}s for $packageName, total=$newTotal")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to increment time, queuing for later", error)
                    // Queue for later sync
                    localCache.queuePendingTimeUpdate(uid, secondsElapsed)

                    // Update local cache optimistically
                    val userState = localCache.getCachedUserState(uid)
                    if (userState != null) {
                        val newTotal = userState.todayUsedMinutes + (secondsElapsed / 60.0)
                        localCache.updateCachedUsedTime(uid, newTotal)
                        isTimeLimitExceeded = newTotal >= userState.dailyLimitMinutes
                    }
                }
            }

            // Log usage for analytics
            val appName = AppUtils.getAppName(this, packageName)
            firebaseRepository.logAppUsage(
                familyId = fid,
                userId = uid,
                deviceId = did,
                appIdentifier = packageName,
                appDisplayName = appName,
                minutes = secondsElapsed / 60.0,
                wasWhitelisted = isWhitelisted
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error recording app time", e)
        }
    }

    /**
     * Update device status in Firestore.
     */
    private suspend fun updateDeviceStatus(packageName: String) {
        try {
            val did = deviceId ?: return
            val appName = AppUtils.getAppName(this, packageName)

            firebaseRepository.updateDeviceStatus(
                deviceId = did,
                currentApp = appName,
                currentAppPackage = packageName
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error updating device status", e)
        }
    }

    /**
     * Check if we should show or hide the blocking overlay.
     */
    private fun checkAndUpdateBlockingState(packageName: String) {
        val isWhitelisted = whitelistManager.isWhitelisted(packageName)

        if (isTimeLimitExceeded && !isWhitelisted) {
            // Show blocking overlay
            showBlockingOverlay()
        } else {
            // Hide blocking overlay
            hideBlockingOverlay()
        }
    }

    /**
     * Show the blocking overlay.
     */
    private fun showBlockingOverlay() {
        try {
            val intent = Intent(this, BlockingOverlayService::class.java)
            intent.action = BlockingOverlayService.ACTION_SHOW
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing blocking overlay", e)
        }
    }

    /**
     * Hide the blocking overlay.
     */
    private fun hideBlockingOverlay() {
        try {
            val intent = Intent(this, BlockingOverlayService::class.java)
            intent.action = BlockingOverlayService.ACTION_HIDE
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding blocking overlay", e)
        }
    }

    /**
     * Load device configuration from SharedPreferences.
     */
    private fun loadDeviceConfiguration() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("screentime_config", MODE_PRIVATE)
                userId = prefs.getString("user_id", null)
                deviceId = prefs.getString("device_id", null)
                familyId = prefs.getString("family_id", null)

                if (userId != null && familyId != null) {
                    // Load whitelist from cache
                    whitelistManager.loadFromCache(familyId!!)

                    // Load user state from cache
                    val userState = localCache.getCachedUserState(userId!!)
                    if (userState != null) {
                        isTimeLimitExceeded = userState.todayUsedMinutes >= userState.dailyLimitMinutes
                    }

                    Log.d(TAG, "Loaded device config: userId=$userId, deviceId=$deviceId, familyId=$familyId")
                } else {
                    Log.w(TAG, "Device not configured yet")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading device configuration", e)
            }
        }
    }

    /**
     * Start the monitoring service if not already running.
     */
    private fun startMonitoringService() {
        try {
            val intent = Intent(this, MonitoringService::class.java)
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting monitoring service", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AccessibilityService destroyed")
        serviceScope.cancel()
    }
}
