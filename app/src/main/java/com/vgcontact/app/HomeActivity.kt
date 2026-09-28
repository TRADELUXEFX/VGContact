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
import androidx.core.widget.NestedScrollView

class HomeActivity : AppCompatActivity() {

    // Home is already open and a push was tapped (FLAG_ACTIVITY_CLEAR_TOP on a
    // non-singleTop activity normally recreates it, but this covers the case
    // where the system delivers it to the existing instance).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationRouter.handle(this, intent)
    }

    private lateinit var sessionManager: SessionManager
    private var contactUsFab: android.view.View? = null

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

        // Opened by tapping a push? Do what the notification says (e.g. jump
        // straight to the admin's WhatsApp). Home is still underneath, so
        // Back returns here.
        if (savedInstanceState == null) NotificationRouter.handle(this, intent)

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

        // Refer & Earn: opens the referral page (code, link, how it works).
        findViewById<Button>(R.id.refer_earn_btn).setOnClickListener {
            startActivity(Intent(this, ReferralActivity::class.java))
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
            // The intro card was pushing the page down; once it's gone the
            // layout shifts, so start the tour after that reflow.
            introCard.post { showHomeTourIfNeeded() }
        }

        // Shared profile header (username, phone/referral row, bell)
        setupProfileHeader()
        setupBottomNav()
        contactUsFab = FloatingContactHelper.attach(this)

        // First-run tour (register or login -> permissions -> here). Runs
        // once per install. If the intro card is still showing, the tour
        // waits for its "Got it" instead (see above) so they don't stack.
        if (introCard.visibility != android.view.View.VISIBLE) {
            findViewById<android.view.View>(R.id.home_content_scroll).post { showHomeTourIfNeeded() }
        }
    }

    private fun showHomeTourIfNeeded() {
        if (CoachMarkOverlay.isTourDone(this)) return

        val scroller = findViewById<NestedScrollView>(R.id.home_content_scroll)
        // Start from the top so step 1 is measured in a known position.
        scroller.scrollTo(0, 0)

        val steps = mutableListOf(
            CoachMarkOverlay.Step(
                findViewById(R.id.today_repost_banner),
                "Earn a free key daily",
                "Repost today's status to earn a key for free. Tap here to start.",
                dockAtBottom = true
            ),
            CoachMarkOverlay.Step(
                findViewById(R.id.get_more_keys_btn),
                "Your keys",
                "This is your key balance. Need more? Tap Buy Keys.",
                dockAtBottom = true,
                scrollParent = scroller
            ),
            CoachMarkOverlay.Step(
                findViewById(R.id.unlock_contact_list_btn),
                "Unlock contacts",
                "1 key unlocks 1 contact file. Tap here to pick a file and unlock it.",
                dockAtBottom = true,
                scrollParent = scroller
            ),
            CoachMarkOverlay.Step(
                findViewById(R.id.refer_earn_btn),
                "Refer & Earn",
                "Share your code. Earn when people join with it.",
                scrollParent = scroller
            ),
            CoachMarkOverlay.Step(
                findViewById(R.id.navDownloadsTab),
                "Get Viewers",
                "Your unlocked contact files live here. Download and save them."
            ),
            CoachMarkOverlay.Step(
                findViewById(R.id.navRepostTab),
                "Repost",
                "Come back here anytime to repost and earn keys."
            )
        )
        contactUsFab?.let { fab ->
            steps.add(
                CoachMarkOverlay.Step(
                    fab,
                    "Need help?",
                    "Tap the chat button anytime to contact us if you run into issues."
                )
            )
        }

        CoachMarkOverlay.showIfNeeded(this, steps)
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
