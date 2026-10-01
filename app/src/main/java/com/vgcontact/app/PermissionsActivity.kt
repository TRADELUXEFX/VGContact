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
 * Step 1: notifications (POST_NOTIFICATIONS, Android 13+ only).
 * Step 2: contacts (READ + WRITE). When contacts are allowed, the same
 * contact sync as the Home button runs right here (the server decides which
 * numbers to save) before handing off to HomeActivity.
 * Denial never blocks the user - we only ever advance forward.
 */
class PermissionsActivity : AppCompatActivity() {

    private enum class Step { NOTIFICATIONS, CONTACTS, DONE }

    private lateinit var permissionStepContainer: LinearLayout
    private lateinit var loadingContainer: LinearLayout
    private lateinit var stepCounterText: TextView
    private lateinit var stepTitleText: TextView
    private lateinit var stepDescriptionText: TextView
    private lateinit var stepIcon: ImageView
    private lateinit var stepActionButton: Button

    private var currentStep: Step = Step.NOTIFICATIONS

    private val NOTIFICATIONS_REQUEST_CODE = 201
    private val CONTACTS_REQUEST_CODE = 202

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

        // Skip whatever is already allowed (e.g. someone logging back in).
        showStep(
            when {
                !notificationsGranted() -> Step.NOTIFICATIONS
                !ContactSync.hasPermission(this) -> Step.CONTACTS
                else -> Step.DONE
            }
        )
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun showStep(step: Step) {
        currentStep = step
        when (step) {
            Step.NOTIFICATIONS -> {
                stepCounterText.text = "STEP 1 OF 2"
                stepTitleText.text = "Stay Notified"
                stepDescriptionText.text =
                    "Get alerts for new files, reposts and viewers."
                stepIcon.setImageResource(R.drawable.illus_empty_contacts)
                stepActionButton.text = "Allow Notifications"
                stepActionButton.setOnClickListener { requestNotificationPermission() }
            }
            Step.CONTACTS -> {
                stepCounterText.text = "STEP 2 OF 2"
                stepTitleText.text = "Add Your Contacts"
                stepDescriptionText.text =
                    "Allow contacts so VGContact can save your group members to your phone and you can see each other's statuses."
                stepIcon.setImageResource(R.drawable.illus_empty_contacts)
                stepActionButton.text = "Allow Contacts"
                stepActionButton.setOnClickListener { requestContactsPermission() }
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
            advanceTo(nextAfterNotifications())
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) {
            advanceTo(nextAfterNotifications())
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
        when (requestCode) {
            NOTIFICATIONS_REQUEST_CODE -> advanceTo(nextAfterNotifications())
            CONTACTS_REQUEST_CODE -> advanceTo(Step.DONE)
        }
    }

    private fun nextAfterNotifications(): Step =
        if (ContactSync.hasPermission(this)) Step.DONE else Step.CONTACTS

    // ---------------- Step 2: Contacts ----------------

    private fun requestContactsPermission() {
        if (ContactSync.hasPermission(this)) {
            advanceTo(Step.DONE)
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
            CONTACTS_REQUEST_CODE
        )
    }

    // ---------------- Loading screen + handoff ----------------

    private fun showLoadingScreen() {
        permissionStepContainer.visibility = View.GONE
        loadingContainer.visibility = View.VISIBLE

        val userId = SessionManager(this).getUserId()
        if (!ContactSync.hasPermission(this) || userId.isNullOrBlank()) {
            loadingContainer.postDelayed({ goToDashboard() }, 600)
            return
        }

        // Contacts allowed: import them now, same logic as the Sync button.
        Thread {
            val result = ContactSync.run(this, userId)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (result.added > 0) {
                    Toast.makeText(
                        this,
                        if (result.added == 1) "1 contact added" else "${result.added} contacts added",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                goToDashboard()
            }
        }.start()
    }

    private fun goToDashboard() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
