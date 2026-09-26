package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.ImageView

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

        // Settings
        val notificationsSwitch = findViewById<Switch>(R.id.notifications_switch)
        val darkModeSwitch = findViewById<Switch>(R.id.dark_mode_switch)

        notificationsSwitch.isChecked = true
        darkModeSwitch.isChecked = false

        notificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(this, if (isChecked) "Notifications enabled" else "Notifications disabled", Toast.LENGTH_SHORT).show()
        }

        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(this, if (isChecked) "Dark mode enabled" else "Dark mode disabled", Toast.LENGTH_SHORT).show()
        }

        // Clear cache button
        val clearCacheBtn = findViewById<Button>(R.id.clear_cache_btn)
        clearCacheBtn.setOnClickListener {
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
        }

        // Logout button
        val logoutBtn = findViewById<Button>(R.id.logout_btn)
        logoutBtn.setOnClickListener {
            sessionManager.logout()
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }

        // Chat button
        val chatBtn = findViewById<ImageButton>(R.id.chat_btn)
        chatBtn.setOnClickListener {
            Toast.makeText(this, "Opening chat support", Toast.LENGTH_SHORT).show()
        }

        // Bottom Navigation

        // Shared profile header (username, phone/referral row, bell, trash)
        setupProfileHeader()
        setupBottomNav()
    }


    private fun setupProfileHeader() {
        val usernameText = findViewById<TextView>(R.id.headerUsernameText)
        val phoneText = findViewById<TextView>(R.id.headerPhoneText)
        val copyBtn = findViewById<Button>(R.id.headerCopyBtn)
        val bellIcon = findViewById<ImageView>(R.id.headerBellIcon)
        val trashIcon = findViewById<ImageView>(R.id.headerTrashIcon)

        usernameText.text = sessionManager.getUsername() ?: "VGContact User"
        val phone = sessionManager.getPhone() ?: ""
        phoneText.text = "Referral code: $phone"

        copyBtn.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("referral_code", phone)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        }

        bellIcon.setOnClickListener {
            Toast.makeText(this, "No new notifications", Toast.LENGTH_SHORT).show()
        }

        trashIcon.setOnClickListener {
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.PROFILE)
    }

}
