package com.vgcontact.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Shown once, right after registration or login, before the dashboard.
 *
 * Step-through flow, one system prompt at a time - never both fired together:
 *   1. Notifications (POST_NOTIFICATIONS, Android 13+ only)
 *   2. Battery optimization exemption (opens system Settings, not a popup)
 *
 * Neither step blocks the user on denial - we only ever advance forward.
 * Once both have been asked, a brief loading screen is shown before handing
 * off to HomeActivity.
 */
class PermissionsActivity : AppCompatActivity() {

    private enum class Step { NOTIFICATIONS, BATTERY, DONE }

    private lateinit var permissionStepContainer: LinearLayout
    private lateinit var loadingContainer: LinearLayout
    private lateinit var stepCounterText: TextView
    private lateinit var stepTitleText: TextView
    private lateinit var stepDescriptionText: TextView
    private lateinit var stepIcon: ImageView
    private lateinit var stepActionButton: Button

    private var currentStep: Step = Step.NOTIFICATIONS
    private var batterySettingsLaunched: Boolean = false

    private val NOTIFICATIONS_REQUEST_CODE = 201

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        permissionStepContainer = findViewById(R.id.permissionStepContainer)
        loadingContainer = findViewById(R.id.loadingContainer)
        stepCounterText = findViewById(R.id.stepCounterText)
        stepTitleText = findViewById(R.id.stepTitleText)
        stepDescriptionText = findViewById(R.id.stepDescriptionText)
        stepIcon = findViewById(R.id.stepIcon)
        stepActionButton = findViewById(R.id.stepActionButton)

        showStep(Step.NOTIFICATIONS)
    }

    private fun showStep(step: Step) {
        currentStep = step
        when (step) {
            Step.NOTIFICATIONS -> {
                stepCounterText.text = "STEP 1 OF 2"
                stepTitleText.text = "Stay Notified"
                stepDescriptionText.text =
                    "Get alerts for new files, reposts and unlocks."
                stepIcon.setImageResource(R.drawable.illus_empty_contacts)
                stepActionButton.text = "Allow Notifications"
                stepActionButton.setOnClickListener { requestNotificationPermission() }
            }
            Step.BATTERY -> {
                stepCounterText.text = "STEP 2 OF 2"
                stepTitleText.text = "Reliable Background Activity"
                stepDescriptionText.text =
                    "Allow the app to run in the background so you don't miss anything."
                stepIcon.setImageResource(R.drawable.illus_sync_success)
                stepActionButton.text = "Allow Background Activity"
                stepActionButton.setOnClickListener { requestBatteryExemption() }
            }
            Step.DONE -> {
                showLoadingScreen()
            }
        }
    }

    private fun advanceTo(next: Step) {
        runOnUiThread { showStep(next) }
    }

    // ---------------- Step 1: Notifications ----------------

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Not needed on this OS version - permission is implicitly granted.
            advanceTo(Step.BATTERY)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) {
            advanceTo(Step.BATTERY)
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATIONS_REQUEST_CODE
        )
    }

    // ---------------- Step 2: Battery optimization ----------------

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            advanceTo(Step.DONE)
            return
        }
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
            batterySettingsLaunched = true
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                batterySettingsLaunched = true
            } catch (e2: Exception) {
                Toast.makeText(this, "Please allow background activity manually in Settings", Toast.LENGTH_LONG).show()
                // Couldn't open Settings at all - nothing to wait for, so don't
                // block the user here; let them proceed.
                advanceTo(Step.DONE)
            }
        }
        // No callback for a Settings screen - we check the real state again in onResume,
        // but only once we know we actually left for Settings (see batterySettingsLaunched).
    }

    override fun onResume() {
        super.onResume()
        // Only relevant for the battery step, and only once the user has actually
        // been sent to the system Settings screen and come back - otherwise the
        // very first onResume() after showStep(BATTERY) would skip the step
        // before the user ever saw or tapped the button.
        if (currentStep == Step.BATTERY && batterySettingsLaunched) {
            batterySettingsLaunched = false
            advanceTo(Step.DONE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // We advance regardless of grant/deny - denial just means that feature
        // won't work yet, it never blocks the user from reaching the dashboard.
        if (requestCode == NOTIFICATIONS_REQUEST_CODE) {
            advanceTo(Step.BATTERY)
        }
    }

    // ---------------- Loading screen + handoff ----------------

    private fun showLoadingScreen() {
        permissionStepContainer.visibility = View.GONE
        loadingContainer.visibility = View.VISIBLE

        loadingContainer.postDelayed({ goToDashboard() }, 600)
    }

    private fun goToDashboard() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
