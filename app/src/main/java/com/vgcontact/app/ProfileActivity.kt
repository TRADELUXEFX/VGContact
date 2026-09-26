package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
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
        val downloadsText = findViewById<TextView>(R.id.profile_downloads)
        val repostsText = findViewById<TextView>(R.id.profile_reposts)
        val createdText = findViewById<TextView>(R.id.profile_created)
        val androidIdText = findViewById<TextView>(R.id.profile_android_id)

        emailText.text = "Username: ${sessionManager.getUsername()} · ${sessionManager.getPhone()}"
        downloadsText.text = "Total Downloaded: ${sessionManager.getTotalDownloads()}"
        repostsText.text = "Total Reposts: ${sessionManager.getTotalReposts()}"

        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        createdText.text = "Account Created: ${dateFormat.format(Date())}"

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        androidIdText.text = "Android ID: $androidId"

        // Logout button
        val logoutBtn = findViewById<Button>(R.id.logout_btn)
        logoutBtn.setOnClickListener {
            sessionManager.logout()
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }

        // Simple title header (this screen doesn't show the full profile header)
        findViewById<TextView>(R.id.headerTitleText).text = "Profile"
        setupBottomNav()
        ChatSupportHelper.attach(this)
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.PROFILE)
    }

}
