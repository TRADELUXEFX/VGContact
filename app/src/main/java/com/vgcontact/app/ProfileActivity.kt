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
        val appVersionText = findViewById<TextView>(R.id.profile_app_version)

        emailText.text = sessionManager.getUsername()
        phoneText.text = sessionManager.getPhone()

        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        createdText.text = dateFormat.format(Date())

        appVersionText.text = BuildInfo.displayVersion()

        // Copy username
        findViewById<LinearLayout>(R.id.profileUsernameCopyIcon).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("username", sessionManager.getUsername()))
            Toast.makeText(this, "Username copied", Toast.LENGTH_SHORT).show()
        }

        // Legal
        findViewById<LinearLayout>(R.id.profile_terms_row).setOnClickListener { LegalActivity.openTerms(this) }
        findViewById<LinearLayout>(R.id.profile_privacy_row).setOnClickListener { LegalActivity.openPrivacy(this) }

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

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.PROFILE)
    }

}
