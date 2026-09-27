package com.vgcontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.ImageView
import android.widget.LinearLayout

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

        // Key balance row + "Get More Keys" button both open the Keys
        // screen (RepostActivity), same destination as the bottom-nav
        // Repost tab.
        val keyBalanceRow = findViewById<LinearLayout>(R.id.home_key_balance_row)
        val keyBalanceText = findViewById<TextView>(R.id.home_key_balance_text)
        val getMoreKeysBtn = findViewById<Button>(R.id.repost_btn)

        keyBalanceRow.setOnClickListener {
            startActivity(Intent(this, RepostActivity::class.java))
        }
        getMoreKeysBtn.setOnClickListener {
            startActivity(Intent(this, RepostActivity::class.java))
        }
        refreshKeyBalance(keyBalanceText)

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

        // Shared profile header (username, phone/referral row, bell)
        setupProfileHeader()
        setupBottomNav()
        ChatSupportHelper.attach(this)
    }


    private fun setupProfileHeader() {
        val usernameText = findViewById<TextView>(R.id.headerUsernameText)
        val phoneText = findViewById<TextView>(R.id.headerPhoneText)
        val copyBtn = findViewById<Button>(R.id.headerCopyBtn)
        val bellIcon = findViewById<ImageView>(R.id.headerBellIcon)
        val profileIcon = findViewById<ImageView>(R.id.headerProfileIcon)

        profileIcon.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        usernameText.text = sessionManager.getUsername() ?: "VGContact User"
        val phone = sessionManager.getPhone() ?: ""
        phoneText.text = "Referral code: $phone"
        // headerUsernameText/headerPhoneText/headerCopyBtn/headerBellIcon ids
        // now live directly in activity_home.xml's own header block instead
        // of a separate included layout_profile_header.xml.

        copyBtn.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("referral_code", phone)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        }

        bellIcon.setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
        }

        refreshUnreadBadge(findViewById(R.id.headerBellUnreadDot))
    }

    // Shows the small red dot on the bell if any notification (own or
    // broadcast) is currently unread. Re-checked each time Home loads,
    // since NotificationsActivity marks everything read on open.
    private fun refreshUnreadBadge(dot: android.view.View) {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return

        Thread {
            SupabaseClient.fetchNotifications(userId) { success, notifications ->
                runOnUiThread {
                    if (success) {
                        dot.visibility = if (notifications.any { !it.isRead }) android.view.View.VISIBLE else android.view.View.GONE
                    }
                }
            }
        }.start()
    }

    private fun refreshKeyBalance(keyBalanceText: TextView) {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return

        Thread {
            SupabaseClient.fetchKeyBalance(userId) { success, balance ->
                runOnUiThread {
                    if (success) {
                        keyBalanceText.text = balance.toString()
                    }
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // Covers coming back from the Keys screen (a repost just got
        // verified, or a key was just spent unlocking a file) so the
        // dashboard balance doesn't go stale.
        val keyBalanceText = findViewById<TextView>(R.id.home_key_balance_text)
        refreshKeyBalance(keyBalanceText)
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.HOME)
    }

}
