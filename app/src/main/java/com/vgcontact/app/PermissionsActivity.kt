package com.vgcontact.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Shown once, right after registration or login, before the dashboard.
 *
 * Step 1: contacts (READ + WRITE). The contact sync starts right after this step.
 * Step 2: notifications (POST_NOTIFICATIONS, Android 13+ only).
 * Step 3: battery (one-tap system pop-up "always run in the background"), so the
 * background contact sync is not stopped by battery saver. Skipped when already
 * allowed. When contacts are allowed, the same contact sync as the Home button
 * runs right here (the server decides which numbers to save) before handing off
 * to HomeActivity.
 * Denial never blocks the user - we only ever advance forward.
 */
class PermissionsActivity : BaseActivity() {

    private enum class Step { CONTACTS, NOTIFICATIONS, BATTERY, DONE }

    private lateinit var permissionStepContainer: LinearLayout
    private lateinit var loadingContainer: LinearLayout
    private lateinit var stepCounterText: TextView
    private lateinit var stepTitleText: TextView
    private lateinit var stepDescriptionText: TextView
    private lateinit var stepIcon: ImageView
    private lateinit var stepActionButton: Button

    private var currentStep: Step = Step.CONTACTS

    // The contact sync starts the moment contacts are allowed (step 1), while the user
    // is still on the next steps. At the end we only wait for it if it has not finished yet.
    private var syncStarted = false
    private var syncDone = false
    private var waitingForSync = false
    private var wentToDashboard = false

    private val NOTIFICATIONS_REQUEST_CODE = 201
    private val CONTACTS_REQUEST_CODE = 202

    // Whatever the user answers in the battery pop-up (Allow or Deny), we move on.
    private val batteryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            advanceTo(Step.DONE)
        }

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
        // Contacts already allowed: the sync starts right away.
        if (ContactSync.hasPermission(this)) startContactSyncNow()
        showStep(
            if (!ContactSync.hasPermission(this)) Step.CONTACTS else nextAfterContacts()
        )
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun batteryRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun nextAfterContacts(): Step =
        if (!notificationsGranted()) Step.NOTIFICATIONS else nextAfterNotifications()

    private fun nextAfterNotifications(): Step =
        if (batteryRestricted()) Step.BATTERY else Step.DONE

    private fun showStep(step: Step) {
        currentStep = step
        when (step) {
            Step.NOTIFICATIONS -> {
                stepCounterText.text = "STEP 2 OF 3"
                stepTitleText.text = "Stay Notified"
                stepDescriptionText.text =
                    "Get alerts for new files, reposts and viewers."
                stepIcon.setImageResource(R.drawable.illus_empty_contacts)
                stepActionButton.text = "Allow Notifications"
                stepActionButton.setOnClickListener { requestNotificationPermission() }
            }
            Step.CONTACTS -> {
                stepCounterText.text = "STEP 1 OF 3"
                stepTitleText.text = "Add Your Contacts"
                stepDescriptionText.text =
                    "Allow contacts so VGContact can save your group members to your phone and you can see each other's statuses."
                stepIcon.setImageResource(R.drawable.illus_empty_contacts)
                stepActionButton.text = "Allow Contacts"
                stepActionButton.setOnClickListener { requestContactsPermission() }
            }
            Step.BATTERY -> {
                stepCounterText.text = "STEP 3 OF 3"
                stepTitleText.text = "Keep Syncing"
                stepDescriptionText.text =
                    "Allow VGContact to run in the background so your contacts keep syncing even when the app is closed."
                stepIcon.setImageResource(R.drawable.illus_empty_contacts)
                stepActionButton.text = "Allow Background Sync"
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

    // ---------------- Step 2: Notifications ----------------

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
            CONTACTS_REQUEST_CODE -> {
                startContactSyncNow()
                advanceTo(nextAfterContacts())
            }
        }
    }

    // ---------------- Step 1: Contacts ----------------

    private fun requestContactsPermission() {
        if (ContactSync.hasPermission(this)) {
            startContactSyncNow()
            advanceTo(nextAfterContacts())
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
            CONTACTS_REQUEST_CODE
        )
    }

    // ---------------- Step 3: Battery ----------------

    private fun requestBatteryExemption() {
        if (!batteryRestricted()) {
            advanceTo(Step.DONE)
            return
        }
        try {
            batteryLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // The phone refused the pop-up: never block the user.
            advanceTo(Step.DONE)
        }
    }

    // ---------------- Loading screen + handoff ----------------

    private fun startContactSyncNow() {
        if (syncStarted) return
        val userId = SessionManager(this).getUserId()
        if (!ContactSync.hasPermission(this) || userId.isNullOrBlank()) return
        syncStarted = true
        val app = applicationContext
        Thread {
            val result = ContactSync.run(app, userId)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                syncDone = true
                if (result.added > 0) {
                    Toast.makeText(
                        app,
                        if (result.added == 1) "1 contact added" else "${result.added} contacts added",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (waitingForSync && !isFinishing) goToDashboard()
            }
        }.start()
    }

    private fun showLoadingScreen() {
        permissionStepContainer.visibility = View.GONE
        loadingContainer.visibility = View.VISIBLE

        val userId = SessionManager(this).getUserId()
        if (!ContactSync.hasPermission(this) || userId.isNullOrBlank()) {
            loadingContainer.postDelayed({ goToDashboard() }, 600)
            return
        }

        // Contacts allowed: normally the sync already started at step 2. Start it now if not
        // (e.g. someone logging back in with everything already allowed), then wait for it.
        startContactSyncNow()
        if (syncDone) {
            goToDashboard()
        } else {
            waitingForSync = true
        }
    }

    private fun goToDashboard() {
        if (wentToDashboard) return
        wentToDashboard = true
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
