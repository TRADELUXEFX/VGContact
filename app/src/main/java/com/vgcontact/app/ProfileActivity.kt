package com.vgcontact.app

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class ProfileActivity : BaseActivity() {

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

        // Copy phone number
        findViewById<LinearLayout>(R.id.profilePhoneCopyIcon).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("phone", sessionManager.getPhone().orEmpty()))
            Toast.makeText(this, "Phone number copied", Toast.LENGTH_SHORT).show()
        }

        // Copy username
        findViewById<LinearLayout>(R.id.profileUsernameCopyIcon).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("username", sessionManager.getUsername()))
            Toast.makeText(this, "Username copied", Toast.LENGTH_SHORT).show()
        }

        // Sync frequency (1 / 6 / 12 / 24 hours)
        syncFrequencyBtn = findViewById(R.id.sync_frequency_btn)
        syncFrequencyBtn.setOnClickListener { showSyncFrequencyDialog() }
        refreshSyncFrequencyButton()

        // Delete My Contacts / Resume Syncing
        deleteContactsBtn = findViewById(R.id.delete_contacts_btn)
        deleteContactsBtn.setOnClickListener { onDeleteOrResumeClicked() }
        refreshDeleteButton()

        setupBottomNav()
        FloatingContactHelper.attach(this)
    }

    private lateinit var deleteContactsBtn: com.google.android.material.button.MaterialButton
    private lateinit var syncFrequencyBtn: com.google.android.material.button.MaterialButton

    private fun hoursLabel(h: Int) = if (h == 1) "hour" else "hours"

    private fun refreshSyncFrequencyButton() {
        val h = SyncPrefs.getIntervalHours(this)
        syncFrequencyBtn.text = if (h == 1) "Sync every hour" else "Sync every $h hours"
    }

    private fun showSyncFrequencyDialog() {
        val choices = SyncPrefs.INTERVAL_CHOICES
        val labels = choices.map { if (it == 1) "Every hour" else "Every $it hours" }.toTypedArray()
        val current = choices.indexOf(SyncPrefs.getIntervalHours(this)).coerceAtLeast(0)
        VgDialog.showChoices(
            this,
            "How often should VGContact save new viewers to your phone?",
            "",
            labels.toList(),
            current
        ) { which ->
            val hours = choices[which]
            if (hours != SyncPrefs.getIntervalHours(this)) {
                SyncPrefs.setIntervalHours(this, hours)
                DailySyncWorker.reschedule(this)
                SyncAdapterSetup.refreshInterval(this)
                refreshSyncFrequencyButton()
                Toast.makeText(this, "Contacts will sync every $hours ${hoursLabel(hours)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // One button, two states: normal = Delete My Contacts (red outline),
    // paused = Resume Syncing (green).
    override fun onResume() {
        super.onResume()
        if (::deleteContactsBtn.isInitialized) refreshDeleteButton()
    }

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
        VgDialog.show(
            this,
            VgDialog.Tone.DANGER,
            "Delete VGC contacts?",
            "This removes the contacts VGContact saved to your phone (the ones ending in VGC). " +
                "Your own contacts are safe. Tap Resume Syncing later to bring them back.",
            primary = VgDialog.Action("Delete") { deleteContacts() }
        )
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
                    result.error == ContactSync.ERR_FETCH -> "Couldn't reach the server. Try Get new viewers on Home."
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
        val phoneBox = findViewById<View>(R.id.profileReferrerPhoneBox)
        val phoneText = findViewById<TextView>(R.id.profile_referrer_phone)
        fun render() {
            createdText.text = formatRegistered(sessionManager.getCreatedAt())
            referredByText.text = sessionManager.getReferredBy() ?: "None"
            val rp = sessionManager.getReferrerPhone()
            if (sessionManager.getReferredBy() != null && rp != null) {
                phoneText.text = formatPhone(rp)
                phoneBox.visibility = View.VISIBLE
            } else {
                phoneBox.visibility = View.GONE
            }
        }
        render()

        // Who referred this user is saved as a username; fetch their phone number once.
        if (sessionManager.getReferredBy() != null && sessionManager.getReferrerPhone() == null) {
            val uid = sessionManager.getUserId().orEmpty()
            Thread {
                val phone = SupabaseClient.fetchMyReferrerPhone(uid)
                if (phone != null) {
                    sessionManager.saveReferrerPhone(phone)
                    runOnUiThread { if (!isFinishing && !isDestroyed) render() }
                } else if (SupabaseClient.fetchMyReferrerExists(uid) == false) {
                    // The server has no such referrer: drop the stale name saved on this phone.
                    sessionManager.clearReferredBy()
                    runOnUiThread { if (!isFinishing && !isDestroyed) render() }
                }
            }.start()
        }

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

    // 08108709620 -> 0810 870 9620 (other lengths are shown as they are).
    private fun formatPhone(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return if (d.length == 11) "${d.substring(0, 4)} ${d.substring(4, 7)} ${d.substring(7)}" else raw
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
