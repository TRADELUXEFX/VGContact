package com.vgcontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.ImageView

class HomeActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        sessionManager = SessionManager(this)

        // Check login
        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        // Stats
        val downloadsCount = findViewById<TextView>(R.id.downloads_count)
        val repostsCount = findViewById<TextView>(R.id.reposts_count)
        downloadsCount.text = sessionManager.getTotalDownloads().toString()
        repostsCount.text = sessionManager.getTotalReposts().toString()

        // Community button
        val joinBtn = findViewById<Button>(R.id.join_community_btn)
        joinBtn.setOnClickListener {
            // Open WhatsApp group link
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("https://chat.whatsapp.com/your-group-link")
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
            }
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
        BottomNavHelper.setup(this, BottomNavHelper.Tab.HOME)
    }

}
