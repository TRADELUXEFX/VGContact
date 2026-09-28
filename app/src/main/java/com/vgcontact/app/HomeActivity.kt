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

        // READY FOR YOU: 3 placeholder rows right away, real groups
        // replace them once fetched (see refreshReadyGroups in onResume).
        findViewById<TextView>(R.id.see_all_groups).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        renderReadyGroups(emptyList(), emptySet())

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
        refreshReadyGroups()
    }

    // Always shows exactly 3 rows. Real groups (from Supabase) take the
    // first slots; any slot without a real group stays a placeholder until
    // one exists. Placeholder "Group 1" looks unlocked, the rest locked.
    private fun renderReadyGroups(groups: List<org.json.JSONObject>, unlockedIds: Set<String>) {
        val container = findViewById<LinearLayout>(R.id.ready_groups_container)
        container.removeAllViews()

        for (i in 0 until 3) {
            val group = groups.getOrNull(i)
            val isPlaceholder = group == null
            val groupId = group?.optString("id").orEmpty()
            val isLocked = if (group == null) i != 0 else !unlockedIds.contains(groupId)
            val memberCount = group?.optInt("member_count", 0) ?: 250

            val row = layoutInflater.inflate(R.layout.item_file, container, false)
            val icon = row.findViewById<ImageView>(R.id.file_icon)
            val name = row.findViewById<TextView>(R.id.file_name)
            val count = row.findViewById<TextView>(R.id.file_count)
            val dot = row.findViewById<android.view.View>(R.id.file_status_dot)
            val btn = row.findViewById<android.widget.FrameLayout>(R.id.download_btn)
            val btnIcon = row.findViewById<ImageView>(R.id.download_btn_icon)

            name.text = if (group == null) "Group ${i + 1}" else "Contact List #${group.optInt("group_number", i + 1)}"
            count.text = if (isLocked) "Locked, $memberCount contacts" else "Verified, $memberCount contacts"

            icon.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_unlock)
            dot.setBackgroundResource(if (isLocked) R.drawable.status_dot_locked else R.drawable.status_dot_unlocked)
            btn.setBackgroundResource(if (isLocked) R.drawable.file_row_action_locked_background else R.drawable.file_row_action_background)
            btnIcon.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_download)
            btnIcon.setColorFilter(
                androidx.core.content.ContextCompat.getColor(this, if (isLocked) R.color.locked_text else R.color.white)
            )

            // Real groups: unlock/download lives in DownloadsActivity.
            val onTap = android.view.View.OnClickListener {
                if (isPlaceholder) {
                    Toast.makeText(this, "Contact groups are coming soon", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(Intent(this, DownloadsActivity::class.java))
                }
            }
            row.setOnClickListener(onTap)
            btn.setOnClickListener(onTap)

            container.addView(row)
        }
    }

    private fun refreshReadyGroups() {
        val userId = sessionManager.getUserId()
        Thread {
            SupabaseClient.fetchGroups { ok, arr ->
                if (!ok || arr == null || arr.length() == 0) return@fetchGroups
                val groups = (0 until arr.length()).map { arr.getJSONObject(it) }
                if (userId.isNullOrBlank()) {
                    runOnUiThread { renderReadyGroups(groups, emptySet()) }
                    return@fetchGroups
                }
                SupabaseClient.fetchUnlockedGroupIds(userId) { _, ids ->
                    runOnUiThread { renderReadyGroups(groups, ids) }
                }
            }
        }.start()
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.HOME)
    }

}
