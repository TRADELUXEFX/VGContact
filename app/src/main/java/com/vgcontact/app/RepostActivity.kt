package com.vgcontact.app

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Key-earning screen. Keys are the only currency used to unlock contact
 * groups now - see DownloadsActivity, which spends a key via
 * SupabaseClient.spendKeyToUnlockGroup().
 *
 * New users start with 3 free keys (users.key_balance default, set at
 * signup). More keys are earned by:
 *   1. Reposting once per day - tapping the button here sends the user
 *      to WhatsApp and logs a 'pending' row in daily_reposts. This does
 *      NOT grant a key immediately: reposts are checked manually against
 *      WhatsApp status viewers that night, and only once marked
 *      'verified' server-side does key_balance go up by 1. So after
 *      tapping, this screen shows "Verifying..." rather than an instant
 *      unlock - the balance only changes the next time the app fetches
 *      it (e.g. next open, or pull-to-refresh here).
 *   2. Buying keys directly (no in-app payment yet - the button below
 *      just opens a WhatsApp chat to arrange it manually).
 */
class RepostActivity : AppCompatActivity() {

    companion object {
        private const val PREF_TODAYS_TASK_DISMISSED = "todays_task_dismissed"
    }

    private lateinit var sessionManager: SessionManager
    private lateinit var prefs: SharedPreferences

    private lateinit var contentScroll: android.widget.ScrollView
    private lateinit var loadingState: LinearLayout

    private lateinit var keyBalanceText: TextView
    private lateinit var repostStatusText: TextView
    private lateinit var repostTodayBtn: com.google.android.material.button.MaterialButton
    private lateinit var buyKeysBtn: Button

    private lateinit var statusCard: LinearLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView

    private lateinit var streakTab: TextView
    private lateinit var leaderboardTab: TextView

    private lateinit var todaysTaskCard: LinearLayout
    private lateinit var todaysTaskClose: ImageView
    private lateinit var todaysTaskRestore: TextView

    // Both fetches must finish before the body is revealed on first
    // load (the green header stays visible the whole time, same as Get
    // Viewers). Later refreshes (e.g. onResume) update the visible
    // content in place instead of hiding it again.
    private var keyBalanceLoaded = false
    private var todayStatusLoaded = false
    private var initialContentRevealed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_repost)

        sessionManager = SessionManager(this)
        prefs = getSharedPreferences("vgcontact_repost_ui", MODE_PRIVATE)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        contentScroll = findViewById(R.id.repost_content_scroll)
        loadingState = findViewById(R.id.repost_loading_state)

        keyBalanceText = findViewById(R.id.key_balance_text)
        repostStatusText = findViewById(R.id.repost_status)
        repostTodayBtn = findViewById(R.id.repost_btn)
        buyKeysBtn = findViewById(R.id.buy_keys_btn)

        statusCard = findViewById(R.id.repost_status_card)
        statusIcon = findViewById(R.id.repost_status_icon)
        statusTitle = findViewById(R.id.repost_status_title)

        streakTab = findViewById(R.id.repost_tab_streak)
        leaderboardTab = findViewById(R.id.repost_tab_leaderboard)
        streakTab.setOnClickListener { selectStreakTab() }
        leaderboardTab.setOnClickListener {
            Toast.makeText(this, "Leaderboard coming soon", Toast.LENGTH_SHORT).show()
        }

        todaysTaskCard = findViewById(R.id.todays_task_card)
        todaysTaskClose = findViewById(R.id.todays_task_close)
        todaysTaskRestore = findViewById(R.id.todays_task_restore)
        todaysTaskClose.setOnClickListener { dismissTodaysTask() }
        todaysTaskRestore.setOnClickListener { restoreTodaysTask() }
        applyTodaysTaskVisibility()

        repostTodayBtn.setOnClickListener { onRepostTodayClicked() }
        buyKeysBtn.setOnClickListener { openBuyKeysChat() }

        refreshKeyBalance()
        refreshTodayStatus()

        setupBottomNav()
        ChatSupportHelper.attach(this)
    }

    private fun dismissTodaysTask() {
        prefs.edit().putBoolean(PREF_TODAYS_TASK_DISMISSED, true).apply()
        applyTodaysTaskVisibility()
    }

    private fun restoreTodaysTask() {
        prefs.edit().putBoolean(PREF_TODAYS_TASK_DISMISSED, false).apply()
        applyTodaysTaskVisibility()
    }

    private fun applyTodaysTaskVisibility() {
        val dismissed = prefs.getBoolean(PREF_TODAYS_TASK_DISMISSED, false)
        todaysTaskCard.visibility = if (dismissed) android.view.View.GONE else android.view.View.VISIBLE
        todaysTaskRestore.visibility = if (dismissed) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun selectStreakTab() {
        streakTab.background = ContextCompat.getDrawable(this, R.drawable.tab_selected_background)
        streakTab.setTextColor(ContextCompat.getColor(this, R.color.vg_green_dark))
        leaderboardTab.background = null
        leaderboardTab.setTextColor(ContextCompat.getColor(this, R.color.white))
    }

    override fun onResume() {
        super.onResume()
        // Covers the case where verification landed while the user was
        // away from this screen (e.g. reopening the app the next day).
        refreshKeyBalance()
        refreshTodayStatus()
    }

    private fun refreshKeyBalance() {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            keyBalanceLoaded = true
            revealContentIfReady()
            return
        }

        thread {
            SupabaseClient.fetchKeyBalance(userId) { success, balance ->
                runOnUiThread {
                    if (success) {
                        keyBalanceText.text = balance.toString()
                    }
                    keyBalanceLoaded = true
                    revealContentIfReady()
                }
            }
        }
    }

    private fun revealContentIfReady() {
        if (initialContentRevealed) return
        if (keyBalanceLoaded && todayStatusLoaded) {
            initialContentRevealed = true
            loadingState.visibility = android.view.View.GONE
            contentScroll.visibility = android.view.View.VISIBLE
        }
    }

    private fun refreshTodayStatus() {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            todayStatusLoaded = true
            revealContentIfReady()
            return
        }

        thread {
            SupabaseClient.fetchTodayRepostStatus(userId) { success, status ->
                runOnUiThread {
                    if (!success) {
                        todayStatusLoaded = true
                        revealContentIfReady()
                        return@runOnUiThread
                    }
                    when (status) {
                        null -> {
                            repostTodayBtn.isEnabled = true
                            repostTodayBtn.text = "REPOST TODAY"
                            repostTodayBtn.icon = null
                            statusCard.visibility = android.view.View.GONE
                        }
                        "pending" -> {
                            repostTodayBtn.isEnabled = false
                            showRepostSent()
                            showStatusCard(
                                title = "Verification pending",
                                message = "We check WhatsApp status views each night. Your key will appear here once confirmed.",
                                icon = R.drawable.ic_pending,
                                tint = R.color.warning_amber
                            )
                        }
                        "verified" -> {
                            repostTodayBtn.isEnabled = false
                            showRepostSent()
                            showStatusCard(
                                title = "Repost verified",
                                message = "Today's repost was verified and your key has been added.",
                                icon = R.drawable.ic_check,
                                tint = R.color.vg_green
                            )
                        }
                        "rejected" -> {
                            repostTodayBtn.isEnabled = false
                            showRepostSent()
                            showStatusCard(
                                title = "Couldn't verify",
                                message = "We couldn't verify today's repost. Try again tomorrow.",
                                icon = R.drawable.ic_close_small,
                                tint = R.color.vg_red
                            )
                        }
                    }
                    todayStatusLoaded = true
                    revealContentIfReady()
                }
            }
        }
    }

    private fun showStatusCard(title: String, message: String, icon: Int, tint: Int) {
        statusCard.visibility = android.view.View.VISIBLE
        statusTitle.text = title
        repostStatusText.text = message
        statusIcon.setImageResource(icon)
        statusIcon.setColorFilter(ContextCompat.getColor(this, tint))
    }

    private fun onRepostTodayClicked() {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't verify your account. Please restart the app.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://wa.me/?text=Check%20out%20VGContact")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening WhatsApp", Toast.LENGTH_SHORT).show()
        }

        repostTodayBtn.isEnabled = false
        showStatusCard(
            title = "Verification pending",
            message = "Verifying today's repost — this can take a few hours.",
            icon = R.drawable.ic_pending,
            tint = R.color.warning_amber
        )

        thread {
            SupabaseClient.submitDailyRepost(userId) { success, message ->
                runOnUiThread {
                    if (success) {
                        showRepostSent()
                        showStatusCard(
                            title = "Verification pending",
                            message = "We check WhatsApp status views each night. Your key will appear here once confirmed.",
                            icon = R.drawable.ic_pending,
                            tint = R.color.warning_amber
                        )
                    } else if (message == "ALREADY_REPOSTED_TODAY") {
                        refreshTodayStatus()
                    } else {
                        repostTodayBtn.isEnabled = true
                        statusCard.visibility = android.view.View.GONE
                        Toast.makeText(this, "Couldn't log your repost. Try again.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Sent state: repost icon + "REPOST" (button is disabled by the callers).
    private fun showRepostSent() {
        repostTodayBtn.text = "REPOST"
        repostTodayBtn.setIconResource(R.drawable.ic_repost)
        repostTodayBtn.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        repostTodayBtn.iconTint = repostTodayBtn.textColors
    }

    private fun openBuyKeysChat() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://wa.me/${BuyKeysActivity.SUPPORT_WHATSAPP}?text=Hi%2C%20I%27d%20like%20to%20buy%20more%20VGContact%20keys")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.REPOST)
    }

}
