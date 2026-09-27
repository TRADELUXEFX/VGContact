package com.vgcontact.app

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.provider.Settings
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
        val keysText = findViewById<TextView>(R.id.profile_keys)
        val downloadsText = findViewById<TextView>(R.id.profile_downloads)
        val repostsText = findViewById<TextView>(R.id.profile_reposts)
        val createdText = findViewById<TextView>(R.id.profile_created)
        val androidIdText = findViewById<TextView>(R.id.profile_android_id)

        emailText.text = sessionManager.getUsername()
        phoneText.text = sessionManager.getPhone()
        downloadsText.text = sessionManager.getTotalDownloads().toString()
        repostsText.text = sessionManager.getTotalReposts().toString()
        refreshKeyBalance(keysText)

        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        createdText.text = dateFormat.format(Date())

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        androidIdText.text = androidId

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
        ChatSupportHelper.attach(this)
    }

    override fun onResume() {
        super.onResume()
        refreshKeyBalance(findViewById(R.id.profile_keys))
    }

    private fun refreshKeyBalance(keysText: TextView) {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return

        Thread {
            SupabaseClient.fetchKeyBalance(userId) { success, balance ->
                runOnUiThread {
                    if (success) {
                        keysText.text = balance.toString()
                    }
                }
            }
        }.start()
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.PROFILE)
    }

}
