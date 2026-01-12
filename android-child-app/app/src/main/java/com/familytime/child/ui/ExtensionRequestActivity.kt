package com.familytime.child.ui

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familytime.child.R
import com.familytime.child.data.FirebaseRepository
import com.familytime.child.data.LocalCache
import kotlinx.coroutines.*

/**
 * Activity for requesting extension time.
 */
class ExtensionRequestActivity : AppCompatActivity() {

    private lateinit var firebaseRepository: FirebaseRepository
    private lateinit var localCache: LocalCache

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var userId: String? = null
    private var deviceId: String? = null
    private var familyId: String? = null
    private var deviceName: String? = null

    companion object {
        private const val TAG = "ExtensionRequest"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extension_request)

        firebaseRepository = FirebaseRepository()
        localCache = LocalCache(this)

        loadDeviceConfiguration()
        setupUI()
    }

    /**
     * Setup UI elements.
     */
    private fun setupUI() {
        val minutesInput = findViewById<EditText>(R.id.et_minutes)
        val reasonInput = findViewById<EditText>(R.id.et_reason)
        val submitButton = findViewById<Button>(R.id.btn_submit_request)
        val cancelButton = findViewById<Button>(R.id.btn_cancel)

        submitButton.setOnClickListener {
            val minutesText = minutesInput.text.toString()
            val reason = reasonInput.text.toString()

            if (minutesText.isBlank()) {
                Toast.makeText(this, "Please enter minutes", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val minutes = minutesText.toIntOrNull()
            if (minutes == null || minutes <= 0) {
                Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            submitExtensionRequest(minutes, reason)
        }

        cancelButton.setOnClickListener {
            finish()
        }
    }

    /**
     * Submit extension request to Firestore.
     */
    private fun submitExtensionRequest(minutes: Int, reason: String) {
        activityScope.launch {
            try {
                val uid = userId
                val did = deviceId
                val fid = familyId
                val dname = deviceName

                if (uid == null || did == null || fid == null || dname == null) {
                    Toast.makeText(
                        this@ExtensionRequestActivity,
                        "Device not configured",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val result = firebaseRepository.createExtensionRequest(
                    familyId = fid,
                    userId = uid,
                    deviceId = did,
                    deviceName = dname,
                    requestedMinutes = minutes,
                    reason = reason.ifBlank { null }
                )

                result.onSuccess {
                    Toast.makeText(
                        this@ExtensionRequestActivity,
                        "Request sent to parent",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }.onFailure { error ->
                    Log.e(TAG, "Failed to create extension request", error)
                    Toast.makeText(
                        this@ExtensionRequestActivity,
                        "Failed to send request",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error submitting extension request", e)
                Toast.makeText(this@ExtensionRequestActivity, "Error occurred", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Load device configuration.
     */
    private fun loadDeviceConfiguration() {
        activityScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("screentime_config", MODE_PRIVATE)
                userId = prefs.getString("user_id", null)
                deviceId = prefs.getString("device_id", null)
                familyId = prefs.getString("family_id", null)
                deviceName = prefs.getString("device_name", null)

                Log.d(TAG, "Loaded config: userId=$userId, deviceId=$deviceId")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading device configuration", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
