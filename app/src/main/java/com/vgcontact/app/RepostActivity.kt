package com.vgcontact.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlin.concurrent.thread

class RepostActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var isUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_repost)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        val fileId = intent.getStringExtra("file_id")
        val fileName = intent.getStringExtra("file_name") ?: "this file"
        val userId = sessionManager.getUserId()

        // Header
        val headerTitle = findViewById<TextView>(R.id.header_title)
        headerTitle.text = if (fileId.isNullOrBlank()) "Repost to Unlock" else "Unlock $fileName"

        val statusText = findViewById<TextView>(R.id.repost_status)
        val repostBtn = findViewById<Button>(R.id.repost_btn)
        val unlockedCodeLayout = findViewById<androidx.constraintlayout.widget.ConstraintLayout?>(R.id.unlocked_code_layout)
        val unlockedCode = findViewById<TextView>(R.id.unlocked_code_text)
        val copyCodeBtn = findViewById<Button>(R.id.copy_code_btn)

        // Repost button
        repostBtn.setOnClickListener {
            if (fileId.isNullOrBlank() || userId.isNullOrBlank()) {
                Toast.makeText(this, "Pick a file from Downloads first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("https://wa.me/?text=Check%20out%20VGContact")
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Error opening WhatsApp", Toast.LENGTH_SHORT).show()
            }

            statusText.text = "⏳ Verifying your repost..."
            repostBtn.isEnabled = false

            thread {
                SupabaseClient.createRepost(userId, fileId) { repostSuccess, code ->
                    if (!repostSuccess || code == null) {
                        runOnUiThread {
                            statusText.text = "Repost to Unlock"
                            repostBtn.isEnabled = true
                            Toast.makeText(this, "Couldn't verify repost. Try again.", Toast.LENGTH_SHORT).show()
                        }
                        return@createRepost
                    }

                    SupabaseClient.unlockFile(userId, fileId) { unlockSuccess ->
                        runOnUiThread {
                            repostBtn.isEnabled = true
                            if (unlockSuccess) {
                                isUnlocked = true
                                statusText.visibility = android.view.View.GONE
                                unlockedCodeLayout?.visibility = android.view.View.VISIBLE
                                unlockedCode.text = code

                                sessionManager.saveTotalReposts(sessionManager.getTotalReposts() + 1)

                                copyCodeBtn.setOnClickListener {
                                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("unlock_code", code)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(this, "Code copied!", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                statusText.text = "Repost to Unlock"
                                Toast.makeText(this, "Repost saved, but unlock failed. Try again.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }

        // Chat button
        val chatBtn = findViewById<ImageButton>(R.id.chat_btn)
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
