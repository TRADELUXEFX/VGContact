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

        // Keys card: "Get more" opens the buy-keys page only. Earning keys
        // by reposting is the Repost tab / today banner below.
        val keyBalanceText = findViewById<TextView>(R.id.home_key_balance_text)
        findViewById<Button>(R.id.get_more_keys_btn).setOnClickListener {
            startActivity(Intent(this, BuyKeysActivity::class.java))
        }
        refreshKeyBalance(keyBalanceText)

        // TODAY banner -> Repost screen
        findViewById<LinearLayout>(R.id.today_repost_banner).setOnClickListener {
            startActivity(Intent(this, RepostActivity::class.java))
        }

        // Single entry point into the unlock flow. Per-file lock state,
        // contact counts, and the spend-a-key confirmation all live in
        // DownloadsActivity - Home just opens straight into it.
        findViewById<Button>(R.id.unlock_contact_list_btn).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

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

        // First-time intro card: shown once, then never again.
        val tips = getSharedPreferences("vg_tips", MODE_PRIVATE)
        val introCard = findViewById<android.view.View>(R.id.home_intro_card)
        if (!tips.getBoolean("home_intro_seen", false)) {
            introCard.visibility = android.view.View.VISIBLE
        }
        findViewById<Button>(R.id.home_intro_got_it).setOnClickListener {
            tips.edit().putBoolean("home_intro_seen", true).apply()
            introCard.visibility = android.view.View.GONE
        }

        // Shared profile header (username, phone/referral row, bell)
        setupProfileHeader()
        setupBottomNav()
        FloatingContactHelper.attach(this)
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

    // The spinner sits inside the white stats card. The card's contents
    // stay hidden until the balance arrives (first load only); later
    // refreshes (onResume) update the visible number in place.
    private fun showCardContent() {
        findViewById<android.view.View>(R.id.home_card_loading).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.home_card_content).visibility = android.view.View.VISIBLE
    }

    private fun refreshKeyBalance(keyBalanceText: TextView) {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            showCardContent()
            return
        }

        Thread {
            SupabaseClient.fetchKeyBalance(userId) { success, balance ->
                runOnUiThread {
                    if (success) {
                        keyBalanceText.text = balance.toString()
                    }
                    showCardContent()
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
