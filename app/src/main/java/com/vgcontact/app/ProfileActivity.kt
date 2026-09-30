package com.vgcontact.app

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class ProfileActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        // Profile Info
        val emailText = findViewById<TextView>(R.id.profile_email)
        val phoneText = findViewById<TextView>(R.id.profile_phone)
        val createdText = findViewById<TextView>(R.id.profile_created)
        val referredByText = findViewById<TextView>(R.id.profile_referred_by)
        val appVersionText = findViewById<TextView>(R.id.profile_app_version)

        emailText.text = sessionManager.getUsername()
        phoneText.text = sessionManager.getPhone()
        showRegistrationInfo(createdText, referredByText)

        appVersionText.text = BuildInfo.displayVersion()

        // Copy username
        findViewById<LinearLayout>(R.id.profileUsernameCopyIcon).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("username", sessionManager.getUsername()))
            Toast.makeText(this, "Username copied", Toast.LENGTH_SHORT).show()
        }

        // Logout button
        val logoutBtn = findViewById<Button>(R.id.logout_btn)
        logoutBtn.setOnClickListener {
            sessionManager.logout()
            startActivity(Intent(this, RegisterActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) })
            finish()
        }

        // Delete My Contacts / Resume Syncing
        deleteContactsBtn = findViewById(R.id.delete_contacts_btn)
        deleteContactsBtn.setOnClickListener { onDeleteOrResumeClicked() }
        refreshDeleteButton()

        setupBottomNav()
        FloatingContactHelper.attach(this)
    }

    private lateinit var deleteContactsBtn: com.google.android.material.button.MaterialButton

    // One button, two states: normal = Delete My Contacts (red outline),
    // paused = Resume Syncing (green).
    private fun refreshDeleteButton() {
        if (SyncPrefs.isPaused(this)) {
            deleteContactsBtn.text = "Resume Syncing"
            val green = androidx.core.content.ContextCompat.getColor(this, R.color.vg_green)
            deleteContactsBtn.setTextColor(green)
            deleteContactsBtn.iconTint = android.content.res.ColorStateList.valueOf(green)
            deleteContactsBtn.strokeColor = android.content.res.ColorStateList.valueOf(green)
        } else {
            deleteContactsBtn.text = "Delete My Contacts"
            val red = androidx.core.content.ContextCompat.getColor(this, R.color.vg_red)
            deleteContactsBtn.setTextColor(red)
            deleteContactsBtn.iconTint = android.content.res.ColorStateList.valueOf(red)
            deleteContactsBtn.strokeColor = android.content.res.ColorStateList.valueOf(red)
        }
    }

    private fun onDeleteOrResumeClicked() {
        if (SyncPrefs.isPaused(this)) resumeSync() else confirmDeleteContacts()
    }

    private fun confirmDeleteContacts() {
        if (!ContactSync.hasPermission(this)) {
            Toast.makeText(this, "Allow Contacts permission first", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete My Contacts?")
            .setMessage(
                "This removes every contact VGContact saved to your phone (the ones ending in VGC) " +
                    "and pauses syncing. Your own contacts are not touched. " +
                    "You can tap Resume Syncing at any time to bring them back."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteContacts() }
            .show()
    }

    private fun deleteContacts() {
        // Pause first so no background sync can re-add them while deleting.
        SyncPrefs.setPaused(this, true)
        deleteContactsBtn.isEnabled = false
        Thread {
            val removed = ContactSync.deleteAll(this)
            runOnUiThread {
                deleteContactsBtn.isEnabled = true
                refreshDeleteButton()
                Toast.makeText(
                    this,
                    when (removed) {
                        0 -> "No VGContact contacts found. Syncing is paused."
                        1 -> "1 contact removed. Syncing is paused."
                        else -> "$removed contacts removed. Syncing is paused."
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun resumeSync() {
        SyncPrefs.setPaused(this, false)
        refreshDeleteButton()
        val userId = sessionManager.getUserId()
        if (!ContactSync.hasPermission(this) || userId.isNullOrBlank()) {
            Toast.makeText(this, "Syncing resumed", Toast.LENGTH_SHORT).show()
            return
        }
        deleteContactsBtn.isEnabled = false
        Toast.makeText(this, "Syncing resumed. Adding your contacts...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = ContactSync.run(this, userId)
            runOnUiThread {
                deleteContactsBtn.isEnabled = true
                val msg = when {
                    result.error == ContactSync.ERR_NO_INTERNET -> "No internet. Contacts will sync later."
                    result.error == ContactSync.ERR_FETCH -> "Couldn't reach the server. Try Sync Contacts on Home."
                    result.added == 0 -> "Your contacts are up to date"
                    result.added == 1 -> "1 contact added"
                    else -> "${result.added} contacts added"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // Date registered + referred by come from what was saved at register or
    // login. Accounts that logged in before that existed have nothing saved:
    // fetch once, save, and refresh the two rows.
    private fun showRegistrationInfo(createdText: TextView, referredByText: TextView) {
        fun render() {
            createdText.text = formatRegistered(sessionManager.getCreatedAt())
            referredByText.text = sessionManager.getReferredBy() ?: "None"
        }
        render()

        val userId = sessionManager.getUserId().orEmpty()
        if (sessionManager.getCreatedAt() == null && userId.isNotBlank()) {
            Thread {
                SupabaseClient.fetchUserProfile(userId) { ok, user ->
                    if (ok && user != null) {
                        sessionManager.saveRegistrationFrom(user)
                        runOnUiThread { render() }
                    }
                }
            }.start()
        }
    }

    // Server time is UTC ("2026-09-16T10:23:45.123+00:00"); show it as a
    // plain date in the phone's own time zone, e.g. 2026-09-16.
    private fun formatRegistered(iso: String?): String {
        if (iso.isNullOrBlank()) return "-"
        return try {
            val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = utc.parse(iso.take(19)) ?: return iso.substringBefore('T')
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        } catch (e: Exception) {
            iso.substringBefore('T')
        }
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.PROFILE)
    }

}
