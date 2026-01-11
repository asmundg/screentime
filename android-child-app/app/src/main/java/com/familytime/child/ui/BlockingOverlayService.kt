package com.familytime.child.ui

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.familytime.child.R
import com.familytime.child.data.FirebaseRepository
import com.familytime.child.data.LocalCache
import kotlinx.coroutines.*

/**
 * Service that displays a full-screen blocking overlay when time limit is exceeded.
 *
 * The overlay:
 * - Cannot be dismissed by the child
 * - Shows "Time's Up!" message
 * - Shows remaining time (0 when exceeded)
 * - Provides "Request Extension" button
 * - Automatically hides when switching to whitelisted app
 *
 * Matches specification in README.md lines 442-467.
 */
class BlockingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var isOverlayShowing = false

    private lateinit var firebaseRepository: FirebaseRepository
    private lateinit var localCache: LocalCache

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var userId: String? = null
    private var deviceId: String? = null
    private var familyId: String? = null

    companion object {
        private const val TAG = "BlockingOverlay"
        const val ACTION_SHOW = "com.familytime.child.ACTION_SHOW_OVERLAY"
        const val ACTION_HIDE = "com.familytime.child.ACTION_HIDE_OVERLAY"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BlockingOverlayService created")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        firebaseRepository = FirebaseRepository()
        localCache = LocalCache(this)

        loadDeviceConfiguration()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Show the blocking overlay.
     */
    private fun showOverlay() {
        if (isOverlayShowing) {
            return // Already showing
        }

        try {
            // Create overlay view
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_blocking, null)

            // Configure window parameters
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Cannot be dismissed
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            // Add view to window manager
            windowManager.addView(overlayView, params)
            isOverlayShowing = true

            // Setup UI
            setupOverlayUI()

            Log.d(TAG, "Overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing overlay", e)
        }
    }

    /**
     * Hide the blocking overlay.
     */
    private fun hideOverlay() {
        if (!isOverlayShowing || overlayView == null) {
            return // Not showing
        }

        try {
            windowManager.removeView(overlayView)
            overlayView = null
            isOverlayShowing = false

            Log.d(TAG, "Overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding overlay", e)
        }
    }

    /**
     * Setup overlay UI elements.
     */
    private fun setupOverlayUI() {
        overlayView?.let { view ->
            serviceScope.launch {
                try {
                    val uid = userId ?: return@launch

                    // Get user state
                    val userState = localCache.getCachedUserState(uid)

                    if (userState != null) {
                        // Update UI on main thread
                        withContext(Dispatchers.Main) {
                            val titleText = view.findViewById<TextView>(R.id.tv_title)
                            val messageText = view.findViewById<TextView>(R.id.tv_message)
                            val requestButton = view.findViewById<Button>(R.id.btn_request_extension)

                            titleText?.text = "Time's Up!"

                            val remainingMinutes = (userState.dailyLimitMinutes - userState.todayUsedMinutes).coerceAtLeast(0.0)
                            messageText?.text = "You've used your daily screen time.\nRemaining: ${remainingMinutes.toInt()} minutes"

                            // Make request button touchable
                            requestButton?.let { btn ->
                                // Update layout params to make button touchable
                                val params = view.layoutParams as WindowManager.LayoutParams
                                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                windowManager.updateViewLayout(view, params)

                                btn.setOnClickListener {
                                    showExtensionRequestDialog()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up overlay UI", e)
                }
            }
        }
    }

    /**
     * Show extension request dialog.
     */
    private fun showExtensionRequestDialog() {
        try {
            val intent = Intent(this, ExtensionRequestActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing extension request dialog", e)
        }
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

                Log.d(TAG, "Loaded config: userId=$userId, deviceId=$deviceId")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading device configuration", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BlockingOverlayService destroyed")

        hideOverlay()
        serviceScope.cancel()
    }
}
