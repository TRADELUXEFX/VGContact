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
 * Key-earning screen. Keys (not per-file reposts) are the only currency
 * used to unlock files now - see DownloadsActivity, which spends a key
 * via SupabaseClient.spendKeyToUnlock().
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

    private lateinit var sessionManager: SessionManager
    private lateinit var prefs: SharedPreferences

    private lateinit var keyBalanceText: TextView
    private lateinit var repostStatusText: TextView
    private lateinit var repostTodayBtn: Button
    private lateinit var buyKeysBtn: Button

    private lateinit var guideCard: LinearLayout
    private lateinit var guideCloseBtn: ImageView
    private lateinit var guideRestoreText: TextView

    private lateinit var statusCard: LinearLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView

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

        keyBalanceText = findViewById(R.id.key_balance_text)
        repostStatusText = findViewById(R.id.repost_status)
        repostTodayBtn = findViewById(R.id.repost_btn)
        buyKeysBtn = findViewById(R.id.buy_keys_btn)

        guideCard = findViewById(R.id.repost_guide_card)
        guideCloseBtn = findViewById(R.id.repost_guide_close)
        guideRestoreText = findViewById(R.id.repost_guide_restore)

        statusCard = findViewById(R.id.repost_status_card)
        statusIcon = findViewById(R.id.repost_status_icon)
        statusTitle = findViewById(R.id.repost_status_title)

        guideCloseBtn.setOnClickListener { setGuideHidden(true) }
        guideRestoreText.setOnClickListener { setGuideHidden(false) }
        renderGuide()

        repostTodayBtn.setOnClickListener { onRepostTodayClicked() }
        buyKeysBtn.setOnClickListener { openBuyKeysChat() }

        refreshKeyBalance()
        refreshTodayStatus()

        setupBottomNav()
        ChatSupportHelper.attach(this)
    }

    /** X on the tip dismisses it; tapping the dashed chip brings it back. */
    private fun setGuideHidden(hidden: Boolean) {
        prefs.edit().putBoolean("guide_hidden", hidden).apply()
        renderGuide()
    }

    private fun renderGuide() {
        val hidden = prefs.getBoolean("guide_hidden", false)
        guideCard.visibility = if (hidden) android.view.View.GONE else android.view.View.VISIBLE
        guideRestoreText.visibility = if (hidden) android.view.View.VISIBLE else android.view.View.GONE
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
        if (userId.isNullOrBlank()) return

        thread {
            SupabaseClient.fetchKeyBalance(userId) { success, balance ->
                runOnUiThread {
                    if (success) {
                        keyBalanceText.text = balance.toString()
                    }
                }
            }
        }
    }

    private fun refreshTodayStatus() {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return

        thread {
            SupabaseClient.fetchTodayRepostStatus(userId) { success, status ->
                runOnUiThread {
                    if (!success) return@runOnUiThread
                    when (status) {
                        null -> {
                            repostTodayBtn.isEnabled = true
                            repostTodayBtn.text = "Repost Today"
                            statusCard.visibility = android.view.View.GONE
                        }
                        "pending" -> {
                            repostTodayBtn.isEnabled = false
                            repostTodayBtn.text = "Repost Sent"
                            showStatusCard(
                                title = "Verification pending",
                                message = "We check WhatsApp status views each night. Your key will appear here once confirmed.",
                                icon = R.drawable.ic_pending,
                                tint = R.color.warning_amber
                            )
                        }
                        "verified" -> {
                            repostTodayBtn.isEnabled = false
                            repostTodayBtn.text = "Repost Sent"
                            showStatusCard(
                                title = "Repost verified",
                                message = "Today's repost was verified and your key has been added.",
                                icon = R.drawable.ic_check,
                                tint = R.color.vg_green
                            )
                        }
                        "rejected" -> {
                            repostTodayBtn.isEnabled = false
                            repostTodayBtn.text = "Repost Sent"
                            showStatusCard(
                                title = "Couldn't verify",
                                message = "We couldn't verify today's repost. Try again tomorrow.",
                                icon = R.drawable.ic_close_small,
                                tint = R.color.vg_red
                            )
                        }
                    }
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
                        repostTodayBtn.text = "Repost Sent"
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

    private fun openBuyKeysChat() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://wa.me/?text=Hi%2C%20I%27d%20like%20to%20buy%20more%20VGContact%20keys")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.REPOST)
    }

}
