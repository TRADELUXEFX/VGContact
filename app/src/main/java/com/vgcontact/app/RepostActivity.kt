package com.vgcontact.app

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Repost screen. Tapping the repost button sends the user to WhatsApp and
 * logs a 'pending' row in daily_reposts. Reposts are checked manually
 * against the admin's WhatsApp status viewers; once the admin marks the
 * repost 'verified' the user gets the "Repost verified" push and the
 * streak counts it. This screen never touches keys.
 */
class RepostActivity : AppCompatActivity() {

    companion object {
        private const val PREF_TODAYS_TASK_DISMISSED = "todays_task_dismissed"
    }

    private lateinit var sessionManager: SessionManager
    private lateinit var prefs: SharedPreferences

    private lateinit var contentScroll: android.widget.ScrollView
    private lateinit var loadingState: LinearLayout

    private lateinit var repostStatusText: TextView
    private lateinit var repostTodayBtn: com.google.android.material.button.MaterialButton
    private lateinit var buyViewersBtn: Button

    private lateinit var statusCard: LinearLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView

    private lateinit var todaysTaskCard: LinearLayout
    private lateinit var todaysTaskClose: ImageView
    private lateinit var todaysTaskRestore: TextView

    // Both fetches must finish before the body is revealed on first
    // load (the green header stays visible the whole time, same as Get
    // Viewers). Later refreshes (e.g. onResume) update the visible
    // content in place instead of hiding it again.
    private var todayStatusLoaded = false
    private var initialContentRevealed = false

    // True once a repost is logged for today (any status). The button stays
    // tappable; taps then only re-open WhatsApp and re-show today's status.
    private var repostedToday = false

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

        repostStatusText = findViewById(R.id.repost_status)
        repostTodayBtn = findViewById(R.id.repost_btn)
        buyViewersBtn = findViewById(R.id.buy_viewers_btn)

        statusCard = findViewById(R.id.repost_status_card)
        statusIcon = findViewById(R.id.repost_status_icon)
        statusTitle = findViewById(R.id.repost_status_title)

        todaysTaskCard = findViewById(R.id.todays_task_card)
        todaysTaskClose = findViewById(R.id.todays_task_close)
        todaysTaskRestore = findViewById(R.id.todays_task_restore)
        todaysTaskClose.setOnClickListener { dismissTodaysTask() }
        todaysTaskRestore.setOnClickListener { restoreTodaysTask() }
        applyTodaysTaskVisibility()

        repostTodayBtn.setOnClickListener { onRepostTodayClicked() }
        buyViewersBtn.setOnClickListener { openBuyViewers() }

        refreshTodayStatus()

        setupBottomNav()
        FloatingContactHelper.attach(this)
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

    override fun onResume() {
        super.onResume()
        // Covers the case where verification landed while the user was
        // away from this screen (e.g. reopening the app the next day).
        refreshTodayStatus()
    }

    private fun revealContentIfReady() {
        if (initialContentRevealed) return
        if (todayStatusLoaded) {
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
                            repostedToday = false
                            showRepostReady()
                            statusCard.visibility = android.view.View.GONE
                        }
                        "pending" -> {
                            repostedToday = true
                            showRepostSent()
                            showStatusCard(
                                title = "Verification pending",
                                message = "We check your repost within 24 hours.",
                                icon = R.drawable.ic_pending,
                                tint = R.color.warning_amber
                            )
                        }
                        "verified" -> {
                            repostedToday = true
                            showRepostSent(verified = true)
                            showStatusCard(
                                title = "Repost verified",
                                message = "Today's repost was verified.",
                                icon = R.drawable.ic_check,
                                tint = R.color.vg_green
                            )
                        }
                        "rejected" -> {
                            repostedToday = true
                            showRepostReady()
                            repostTodayBtn.text = "NOT VERIFIED"
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

        // Send the user to the admin's WhatsApp chat (same number used by
        // Contact Us) so they can view and repost the admin's status.
        try {
            val message = Uri.encode("Hi VGContact, I want to repost today's status")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://wa.me/${SupportContact.WHATSAPP}?text=$message")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }

        if (repostedToday) {
            // Already logged today: no second submit, just re-show status.
            refreshTodayStatus()
            return
        }

        repostedToday = true
        showRepostSent()   // instant visible change: red -> yellow "YOU POSTED TODAY"
        showStatusCard(
            title = "Verification pending",
            message = "We'll check your repost within 24 hours.",
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
                            message = "We check your repost within 24 hours.",
                            icon = R.drawable.ic_pending,
                            tint = R.color.warning_amber
                        )
                    } else if (message == "ALREADY_REPOSTED_TODAY") {
                        refreshTodayStatus()
                    } else {
                        repostedToday = false
                        showRepostReady()
                        statusCard.visibility = android.view.View.GONE
                        Toast.makeText(this, "Couldn't log your repost. Try again.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Paints the repost button one solid colour with white text/icon.
    private fun paintRepostButton(colorRes: Int) {
        val color = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        val white = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
        repostTodayBtn.backgroundTintList = color
        repostTodayBtn.strokeColor = color
        repostTodayBtn.setTextColor(white)
        repostTodayBtn.iconTint = white
    }

    // Not posted yet: solid RED button, white text, no icon.
    private fun showRepostReady() {
        repostTodayBtn.text = "REPOST TODAY"
        repostTodayBtn.icon = null
        paintRepostButton(R.color.vg_red)
    }

    // Posted: YELLOW (amber) while pending, GREEN once verified. White text
    // and a white check either way; the button stays tappable.
    private fun showRepostSent(verified: Boolean = false) {
        repostTodayBtn.text = if (verified) "POSTED & VERIFIED" else "YOU POSTED TODAY"
        repostTodayBtn.setIconResource(R.drawable.ic_check)
        repostTodayBtn.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        repostTodayBtn.iconPadding = (8 * resources.displayMetrics.density).toInt()
        paintRepostButton(if (verified) R.color.vg_green else R.color.warning_amber)
    }

    // Opens a WhatsApp chat with support to buy status viewers.
    private fun openBuyViewers() {
        SupportContact.openBuyViewers(this)
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.REPOST)
    }

}
