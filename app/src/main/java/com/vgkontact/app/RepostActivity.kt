package com.vgkontact.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

class RepostActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var isUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_repost)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Header
        val headerTitle = findViewById<TextView>(R.id.header_title)
        headerTitle.text = "Repost to Unlock"

        val statusText = findViewById<TextView>(R.id.repost_status)
        val repostBtn = findViewById<Button>(R.id.repost_btn)
        val unlockedCodeLayout = findViewById<androidx.constraintlayout.widget.ConstraintLayout?>(R.id.unlocked_code_layout)
        val unlockedCode = findViewById<TextView>(R.id.unlocked_code_text)
        val copyCodeBtn = findViewById<Button>(R.id.copy_code_btn)

        // Repost button
        repostBtn.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("https://wa.me/?text=Check%20out%20VGKontact")
                startActivity(intent)

                // Simulate verification after 5 seconds
                statusText.text = "⏳ Waiting for verification..."
                repostBtn.isEnabled = false

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!isUnlocked) {
                        isUnlocked = true
                        statusText.visibility = android.view.View.GONE
                        unlockedCodeLayout?.visibility = android.view.View.VISIBLE
                        
                        val code = "ABC123"
                        unlockedCode.text = code

                        copyCodeBtn.setOnClickListener {
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("unlock_code", code)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this, "Code copied!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, 5000)

            } catch (e: Exception) {
                Toast.makeText(this, "Error opening WhatsApp", Toast.LENGTH_SHORT).show()
            }
        }

        // Chat button
        val chatBtn = findViewById<Button>(R.id.chat_btn)
        chatBtn.setOnClickListener {
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
                R.id.nav_repost -> true
                R.id.nav_downloads -> {
                    startActivity(Intent(this, DownloadsActivity::class.java))
                    false
                }
                R.id.nav_community -> {
                    startActivity(Intent(this, CommunityActivity::class.java))
                    false
                }
                R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    false
                }
                else -> false
            }
        }
        navView.selectedItemId = R.id.nav_repost
    }

}
