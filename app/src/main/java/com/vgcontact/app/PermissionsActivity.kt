package com.vgcontact.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Shown once, right after registration or login, before the dashboard.
 * Asks for the two things the app actually needs:
 *  1. Notifications (POST_NOTIFICATIONS on Android 13+)
 *  2. Battery optimization exemption (so background work isn't killed)
 *
 * Both are "requested", not strictly required to proceed - if the user
 * declines a system dialog we don't trap them here, we just move on to
 * HomeActivity. This mirrors the manifest's exact 4-permission MVP scope.
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var notifStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var continueBtn: Button

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        notifStatus = findViewById(R.id.notif_status)
        batteryStatus = findViewById(R.id.battery_status)
        continueBtn = findViewById(R.id.continue_btn)

        val notifBtn = findViewById<Button>(R.id.notif_btn)
        val batteryBtn = findViewById<Button>(R.id.battery_btn)

        notifBtn.setOnClickListener { requestNotificationPermission() }
        batteryBtn.setOnClickListener { requestBatteryOptimizationExemption() }

        continueBtn.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers the case where the user flips a setting in the system
        // dialog/Settings screen and comes back here.
        refreshStatus()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        refreshStatus()
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                // Some OEMs block this screen; fall back to the general list.
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun refreshStatus() {
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required pre-Android 13.
        }

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val batteryExempt = powerManager.isIgnoringBatteryOptimizations(packageName)

        notifStatus.text = if (notifGranted) "✓ Enabled" else "Not enabled"
        batteryStatus.text = if (batteryExempt) "✓ Enabled" else "Not enabled"
    }
}
