package com.vgcontact.app

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }

        setupBottomNav()
        FloatingContactHelper.attach(this)
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
