package com.familytime.child.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.familytime.child.MainActivity
import com.familytime.child.R
import com.familytime.child.data.FirebaseRepository
import com.familytime.child.data.LocalCache
import com.familytime.child.data.WhitelistManager
import kotlinx.coroutines.*

/**
 * Foreground Service for persistent monitoring.
 *
 * Keeps the app running continuously and performs periodic tasks:
 * - Refresh whitelist from Firestore
 * - Check for approved extension requests
 * - Sync pending time updates when online
 * - Daily reset check
 * - Health checks
 *
 * Matches specification in README.md lines 479-492.
 */
class MonitoringService : Service() {

    private lateinit var firebaseRepository: FirebaseRepository
    private lateinit var localCache: LocalCache
    private lateinit var whitelistManager: WhitelistManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var userId: String? = null
    private var deviceId: String? = null
    private var familyId: String? = null

    companion object {
        private const val TAG = "MonitoringService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screentime_monitoring"
        private const val CHANNEL_NAME = "Screen Time Monitoring"

        private const val WHITELIST_REFRESH_INTERVAL = 60_000L // 60 seconds
        private const val EXTENSION_CHECK_INTERVAL = 30_000L // 30 seconds
        private const val SYNC_INTERVAL = 120_000L // 2 minutes
        private const val DAILY_RESET_CHECK_INTERVAL = 300_000L // 5 minutes
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MonitoringService created")

        firebaseRepository = FirebaseRepository()
        localCache = LocalCache(this)
        whitelistManager = WhitelistManager(this, firebaseRepository, localCache)

        loadDeviceConfiguration()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Start periodic tasks
        startPeriodicTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "MonitoringService started")
        return START_STICKY // Restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    /**
     * Create notification channel for Android O+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps screen time monitoring active"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Create foreground service notification.
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Time Active")
            .setContentText("Monitoring app usage")
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // TODO: Replace with app icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Load device configuration.
     */
    private fun loadDeviceConfiguration() {
        serviceScope.launch {
            try {
                val prefs = getSharedPreferences("screentime_config", MODE_PRIVATE)
                userId = prefs.getString("user_id", null)
                deviceId = prefs.getString("device_id", null)
                familyId = prefs.getString("family_id", null)

                Log.d(TAG, "Loaded config: userId=$userId, deviceId=$deviceId, familyId=$familyId")

                if (familyId != null) {
                    // Initial whitelist load
                    whitelistManager.loadFromCache(familyId!!)
                    whitelistManager.refreshWhitelist(familyId!!)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading device configuration", e)
            }
        }
    }

    /**
     * Start all periodic background tasks.
     */
    private fun startPeriodicTasks() {
        // Refresh whitelist periodically
        serviceScope.launch {
            while (isActive) {
                delay(WHITELIST_REFRESH_INTERVAL)
                refreshWhitelist()
            }
        }

        // Check for approved extension requests
        serviceScope.launch {
            while (isActive) {
                delay(EXTENSION_CHECK_INTERVAL)
                checkExtensionRequests()
            }
        }

        // Sync pending time updates
        serviceScope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL)
                syncPendingTimeUpdates()
            }
        }

        // Check for daily reset
        serviceScope.launch {
            while (isActive) {
                delay(DAILY_RESET_CHECK_INTERVAL)
                checkDailyReset()
            }
        }
    }

    /**
     * Refresh whitelist from Firestore.
     */
    private suspend fun refreshWhitelist() {
        try {
            val fid = familyId ?: return
            whitelistManager.refreshWhitelist(fid)
            Log.d(TAG, "Whitelist refreshed")
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing whitelist", e)
        }
    }

    /**
     * Check for approved extension requests.
     */
    private suspend fun checkExtensionRequests() {
        try {
            val uid = userId ?: return
            val did = deviceId ?: return

            val result = firebaseRepository.getAndClearApprovedExtensions(did)

            result.onSuccess { approvedMinutes ->
                if (approvedMinutes > 0) {
                    // Apply extension to user's time budget
                    firebaseRepository.applyExtensionMinutes(uid, approvedMinutes)

                    // Update local cache
                    val userState = localCache.getCachedUserState(uid)
                    if (userState != null) {
                        val newUsed = (userState.todayUsedMinutes - approvedMinutes).coerceAtLeast(0.0)
                        localCache.updateCachedUsedTime(uid, newUsed)
                    }

                    Log.d(TAG, "Applied extension: $approvedMinutes minutes")

                    // Show notification
                    showExtensionApprovedNotification(approvedMinutes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking extension requests", e)
        }
    }

    /**
     * Sync pending time updates when online.
     */
    private suspend fun syncPendingTimeUpdates() {
        try {
            val uid = userId ?: return

            val pendingUpdates = localCache.getPendingTimeUpdates(uid)

            if (pendingUpdates.isNotEmpty()) {
                // Sum all pending seconds
                val totalSeconds = pendingUpdates.sumOf { it.secondsToAdd }

                // Try to sync
                val result = firebaseRepository.incrementUsedTime(uid, totalSeconds)

                result.onSuccess {
                    // Clear pending updates
                    localCache.clearPendingTimeUpdates(pendingUpdates)
                    Log.d(TAG, "Synced ${pendingUpdates.size} pending time updates ($totalSeconds sec)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing pending time updates", e)
        }
    }

    /**
     * Check if daily reset is needed.
     */
    private suspend fun checkDailyReset() {
        try {
            val uid = userId ?: return

            val result = firebaseRepository.checkAndResetDaily(uid)

            result.onSuccess { wasReset ->
                if (wasReset) {
                    // Update local cache
                    val today = com.familytime.child.utils.TimeUtils.getTodayDate()
                    localCache.resetCachedDailyTime(uid, today)
                    Log.d(TAG, "Daily reset performed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking daily reset", e)
        }
    }

    /**
     * Show notification when extension is approved.
     */
    private fun showExtensionApprovedNotification(minutes: Int) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Extension Approved!")
                .setContentText("You've been granted $minutes more minutes")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(2, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing extension notification", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MonitoringService destroyed")
        serviceScope.cancel()
    }
}
