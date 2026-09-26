package com.vgcontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

class CommunityActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_community)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        // Header
        val headerTitle = findViewById<TextView>(R.id.header_title)
        headerTitle.text = "Our Community"

        // Join WhatsApp button
        val joinBtn = findViewById<Button>(R.id.join_whatsapp_btn)
        joinBtn.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("https://chat.whatsapp.com/your-group-link")
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
            }
        }

        // Chat support button
        val chatBtn = findViewById<Button>(R.id.chat_support_btn)
        chatBtn.setOnClickListener {
            Toast.makeText(this, "Opening chat support", Toast.LENGTH_SHORT).show()
        }

        // Floating chat button
        val floatingChatBtn = findViewById<ImageButton>(R.id.floating_chat_btn)
        floatingChatBtn.setOnClickListener {
            Toast.makeText(this, "Opening chat support", Toast.LENGTH_SHORT).show()
        }

        // Bottom Navigation
        setupBottomNav()
    }

    private fun setupBottomNav() {
        val navView = findViewById<BottomNavigationView>(R.id.bottom_nav)
        navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, HomeActivity::class.java))
                    false
                }
                R.id.nav_repost -> {
                    startActivity(Intent(this, RepostActivity::class.java))
                    false
                }
                R.id.nav_downloads -> {
                    startActivity(Intent(this, DownloadsActivity::class.java))
                    false
                }
                R.id.nav_community -> true
                R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    false
                }
                else -> false
            }
        }
        navView.selectedItemId = R.id.nav_community
    }

}
