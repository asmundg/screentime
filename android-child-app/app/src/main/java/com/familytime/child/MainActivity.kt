package com.familytime.child

import android.app.admin.DevicePolicy Manager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familytime.child.data.FirebaseRepository
import com.familytime.child.data.LocalCache
import com.familytime.child.services.MonitoringService
import com.familytime.child.services.ScreenTimeDeviceAdmin
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*

/**
 * Main activity for device setup and permission management.
 *
 * Guides the user through:
 * 1. Granting Display Over Other Apps permission
 * 2. Enabling Accessibility Service
 * 3. Granting Usage Stats permission
 * 4. Activating Device Admin (optional)
 * 5. Registering device with family code
 *
 * Matches setup flow in README.md lines 515-526.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var firebaseRepository: FirebaseRepository
    private lateinit var localCache: LocalCache
    private lateinit var firebaseAuth: FirebaseAuth

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_OVERLAY_PERMISSION = 1
        private const val REQUEST_USAGE_STATS_PERMISSION = 2
        private const val REQUEST_DEVICE_ADMIN = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        firebaseRepository = FirebaseRepository()
        localCache = LocalCache(this)
        firebaseAuth = FirebaseAuth.getInstance()

        setupUI()
        checkPermissions()
    }

    /**
     * Setup UI elements.
     */
    private fun setupUI() {
        val statusText = findViewById<TextView>(R.id.tv_status)
        val overlayButton = findViewById<Button>(R.id.btn_grant_overlay)
        val accessibilityButton = findViewById<Button>(R.id.btn_enable_accessibility)
        val usageStatsButton = findViewById<Button>(R.id.btn_grant_usage_stats)
        val deviceAdminButton = findViewById<Button>(R.id.btn_activate_device_admin)
        val registrationCodeInput = findViewById<EditText>(R.id.et_registration_code)
        val registerButton = findViewById<Button>(R.id.btn_register_device)

        overlayButton.setOnClickListener {
            requestOverlayPermission()
        }

        accessibilityButton.setOnClickListener {
            openAccessibilitySettings()
        }

        usageStatsButton.setOnClickListener {
            requestUsageStatsPermission()
        }

        deviceAdminButton.setOnClickListener {
            requestDeviceAdmin()
        }

        registerButton.setOnClickListener {
            val code = registrationCodeInput.text.toString()
            if (code.isBlank()) {
                Toast.makeText(this, "Please enter a registration code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            registerDevice(code)
        }

        updatePermissionStatus()
    }

    /**
     * Check and display permission status.
     */
    private fun checkPermissions() {
        updatePermissionStatus()
    }

    /**
     * Update permission status UI.
     */
    private fun updatePermissionStatus() {
        val hasOverlay = checkOverlayPermission()
        val hasAccessibility = checkAccessibilityPermission()
        val hasUsageStats = checkUsageStatsPermission()
        val hasDeviceAdmin = checkDeviceAdminActivated()

        val statusText = findViewById<TextView>(R.id.tv_status)
        statusText.text = buildString {
            appendLine("Permission Status:")
            appendLine("✓ Display Over Apps: ${if (hasOverlay) "Granted" else "Not granted"}")
            appendLine("✓ Accessibility Service: ${if (hasAccessibility) "Enabled" else "Not enabled"}")
            appendLine("✓ Usage Stats: ${if (hasUsageStats) "Granted" else "Not granted"}")
            appendLine("✓ Device Admin: ${if (hasDeviceAdmin) "Activated" else "Not activated"}")
        }

        // If all permissions granted, start monitoring
        if (hasOverlay && hasAccessibility) {
            startMonitoringService()
        }
    }

    // ============================================================
    // Permission Checks
    // ============================================================

    /**
     * Check if overlay permission is granted.
     */
    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * Check if accessibility service is enabled.
     */
    private fun checkAccessibilityPermission(): Boolean {
        val accessibilityEnabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )

        if (accessibilityEnabled == 0) {
            return false
        }

        val services = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val packageName = packageName
        val className = "com.familytime.child.services.ScreenTimeAccessibilityService"

        return services.contains("$packageName/$className")
    }

    /**
     * Check if usage stats permission is granted.
     */
    private fun checkUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                "android:get_usage_stats",
                android.os.Process.myUid(),
                packageName
            )
        } else {
            appOps.checkOpNoThrow(
                "android:get_usage_stats",
                android.os.Process.myUid(),
                packageName
            )
        }

        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /**
     * Check if device admin is activated.
     */
    private fun checkDeviceAdminActivated(): Boolean {
        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, ScreenTimeDeviceAdmin::class.java)

        return devicePolicyManager.isAdminActive(componentName)
    }

    // ============================================================
    // Permission Requests
    // ============================================================

    /**
     * Request overlay permission.
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            }
        }
    }

    /**
     * Open accessibility settings.
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    /**
     * Request usage stats permission.
     */
    private fun requestUsageStatsPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivityForResult(intent, REQUEST_USAGE_STATS_PERMISSION)
    }

    /**
     * Request device admin activation.
     */
    private fun requestDeviceAdmin() {
        val componentName = ComponentName(this, ScreenTimeDeviceAdmin::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Activate device admin to prevent uninstallation of parental controls"
        )
        startActivityForResult(intent, REQUEST_DEVICE_ADMIN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION,
            REQUEST_USAGE_STATS_PERMISSION,
            REQUEST_DEVICE_ADMIN -> {
                updatePermissionStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    // ============================================================
    // Device Registration
    // ============================================================

    /**
     * Register device with family using registration code.
     */
    private fun registerDevice(code: String) {
        activityScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Registering device...", Toast.LENGTH_SHORT).show()

                // TODO: Implement device registration via Cloud Function
                // This should call a Cloud Function that:
                // 1. Validates the registration code
                // 2. Creates a Device document in Firestore
                // 3. Returns a custom auth token
                // 4. Returns familyId, userId, deviceId

                // For now, we'll show a placeholder message
                Toast.makeText(
                    this@MainActivity,
                    "Registration not yet implemented. Please configure manually in SharedPreferences.",
                    Toast.LENGTH_LONG
                ).show()

                // Sample manual configuration (for development):
                // val prefs = getSharedPreferences("screentime_config", MODE_PRIVATE)
                // prefs.edit()
                //     .putString("family_id", "FAMILY_ID")
                //     .putString("user_id", "USER_ID")
                //     .putString("device_id", "DEVICE_ID")
                //     .putString("device_name", "My Android Phone")
                //     .apply()

            } catch (e: Exception) {
                Log.e(TAG, "Error registering device", e)
                Toast.makeText(this@MainActivity, "Registration failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // Service Management
    // ============================================================

    /**
     * Start monitoring service.
     */
    private fun startMonitoringService() {
        try {
            val intent = Intent(this, MonitoringService::class.java)
            startForegroundService(intent)
            Log.d(TAG, "Started monitoring service")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting monitoring service", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
