package com.vgcontact.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
 * Asks for notifications (POST_NOTIFICATIONS, Android 13+ only). Denial never
 * blocks the user - we only ever advance forward. Once asked, a brief loading
 * screen is shown before handing off to HomeActivity.
 */
class PermissionsActivity : AppCompatActivity() {

    private enum class Step { NOTIFICATIONS, DONE }

    private lateinit var permissionStepContainer: LinearLayout
    private lateinit var loadingContainer: LinearLayout
    private lateinit var stepCounterText: TextView
    private lateinit var stepTitleText: TextView
    private lateinit var stepDescriptionText: TextView
    private lateinit var stepIcon: ImageView
    private lateinit var stepActionButton: Button

    private var currentStep: Step = Step.NOTIFICATIONS

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
                stepCounterText.text = "STEP 1 OF 1"
                stepTitleText.text = "Stay Notified"
                stepDescriptionText.text =
                    "Get alerts for new files, reposts and unlocks."
                stepIcon.setImageResource(R.drawable.illus_empty_contacts)
                stepActionButton.text = "Allow Notifications"
                stepActionButton.setOnClickListener { requestNotificationPermission() }
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
            advanceTo(Step.DONE)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) {
            advanceTo(Step.DONE)
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATIONS_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // We advance regardless of grant/deny - denial just means that feature
        // won't work yet, it never blocks the user from reaching the dashboard.
        if (requestCode == NOTIFICATIONS_REQUEST_CODE) {
            advanceTo(Step.DONE)
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
