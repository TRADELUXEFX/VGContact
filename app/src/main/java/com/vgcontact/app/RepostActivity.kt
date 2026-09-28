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

        // Leaderboard: ranks by verified reposts inside the chosen period.
        private const val LB_METRIC = "reposts"
        private const val LB_LIMIT = 20
        private const val PERIOD_WEEK = "week"
        private const val PERIOD_MONTH = "month"
        private const val PERIOD_ALL = "all"
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

    private lateinit var leaderboardRoot: FrameLayout
    private lateinit var lbPodiumCard: LinearLayout
    private lateinit var lbList: LinearLayout
    private lateinit var lbEmpty: TextView
    private lateinit var lbLoading: ProgressBar
    private lateinit var lbYouCard: LinearLayout
    private lateinit var lbYouRank: TextView
    private lateinit var lbYouScore: TextView
    private lateinit var lbChipWeek: TextView
    private lateinit var lbChipMonth: TextView
    private lateinit var lbChipAll: TextView

    private var showingLeaderboard = false
    private var lbPeriod = PERIOD_WEEK
    private var lbRequestId = 0   // ignores stale responses when the user switches fast

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
        leaderboardTab.setOnClickListener { selectLeaderboardTab() }
        setupLeaderboardViews()

        todaysTaskCard = findViewById(R.id.todays_task_card)
        todaysTaskClose = findViewById(R.id.todays_task_close)
        todaysTaskRestore = findViewById(R.id.todays_task_restore)
        todaysTaskClose.setOnClickListener { dismissTodaysTask() }
        todaysTaskRestore.setOnClickListener { restoreTodaysTask() }
        applyTodaysTaskVisibility()

        repostTodayBtn.setOnClickListener { onRepostTodayClicked() }
        buyKeysBtn.setOnClickListener { openBuyKeysPage() }

        refreshKeyBalance()
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

    private fun selectStreakTab() {
        showingLeaderboard = false
        lbRequestId++   // drop any leaderboard response still in flight
        streakTab.background = ContextCompat.getDrawable(this, R.drawable.tab_selected_background)
        streakTab.setTextColor(ContextCompat.getColor(this, R.color.vg_green_dark))
        leaderboardTab.background = null
        leaderboardTab.setTextColor(ContextCompat.getColor(this, R.color.white))

        leaderboardRoot.visibility = View.GONE
        if (initialContentRevealed) {
            contentScroll.visibility = View.VISIBLE
            loadingState.visibility = View.GONE
        } else {
            contentScroll.visibility = View.GONE
            loadingState.visibility = View.VISIBLE
        }
    }

    private fun selectLeaderboardTab() {
        showingLeaderboard = true
        leaderboardTab.background = ContextCompat.getDrawable(this, R.drawable.tab_selected_background)
        leaderboardTab.setTextColor(ContextCompat.getColor(this, R.color.vg_green_dark))
        streakTab.background = null
        streakTab.setTextColor(ContextCompat.getColor(this, R.color.white))

        contentScroll.visibility = View.GONE
        loadingState.visibility = View.GONE
        leaderboardRoot.visibility = View.VISIBLE
        loadLeaderboard()
    }

    // ---------------------------------------------------------------
    // Leaderboard (Option 1: podium for the top 3, list for the rest,
    // "You" card pinned above the bottom nav).
    // ---------------------------------------------------------------

    private fun setupLeaderboardViews() {
        leaderboardRoot = findViewById(R.id.repost_leaderboard_root)
        lbPodiumCard = findViewById(R.id.lb_podium_card)
        lbList = findViewById(R.id.lb_list)
        lbEmpty = findViewById(R.id.lb_empty)
        lbLoading = findViewById(R.id.lb_loading)
        lbYouCard = findViewById(R.id.lb_you_card)
        lbYouRank = findViewById(R.id.lb_you_rank)
        lbYouScore = findViewById(R.id.lb_you_score)
        lbChipWeek = findViewById(R.id.lb_chip_week)
        lbChipMonth = findViewById(R.id.lb_chip_month)
        lbChipAll = findViewById(R.id.lb_chip_all)

        lbChipWeek.setOnClickListener { selectPeriod(PERIOD_WEEK) }
        lbChipMonth.setOnClickListener { selectPeriod(PERIOD_MONTH) }
        lbChipAll.setOnClickListener { selectPeriod(PERIOD_ALL) }
        lbEmpty.setOnClickListener { loadLeaderboard() }   // tap to retry after an error
        styleChips()
    }

    private fun selectPeriod(period: String) {
        if (period == lbPeriod) return
        lbPeriod = period
        styleChips()
        loadLeaderboard()
    }

    private fun styleChips() {
        val chips = listOf(
            lbChipWeek to PERIOD_WEEK,
            lbChipMonth to PERIOD_MONTH,
            lbChipAll to PERIOD_ALL
        )
        for ((chip, period) in chips) {
            val selected = period == lbPeriod
            chip.background = ContextCompat.getDrawable(
                this,
                if (selected) R.drawable.filter_chip_selected_background
                else R.drawable.filter_chip_default_background
            )
            chip.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.white else R.color.vg_green_dark)
            )
        }
    }

    private fun loadLeaderboard() {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            showLeaderboardMessage("Couldn't verify your account. Please restart the app.")
            return
        }

        val requestId = ++lbRequestId
        lbLoading.visibility = View.VISIBLE
        lbEmpty.visibility = View.GONE

        thread {
            SupabaseClient.fetchLeaderboard(userId, LB_METRIC, lbPeriod, LB_LIMIT) { success, entries ->
                runOnUiThread {
                    // Ignore if the user left the tab or switched period meanwhile.
                    if (requestId != lbRequestId || !showingLeaderboard) return@runOnUiThread
                    lbLoading.visibility = View.GONE
                    if (success) {
                        renderLeaderboard(entries)
                    } else {
                        showLeaderboardMessage("Couldn't load the leaderboard. Tap to try again.")
                    }
                }
            }
        }
    }

    private fun showLeaderboardMessage(message: String) {
        lbPodiumCard.visibility = View.GONE
        lbList.removeAllViews()
        lbYouCard.visibility = View.GONE
        lbEmpty.text = message
        lbEmpty.visibility = View.VISIBLE
        lbLoading.visibility = View.GONE
    }

    private fun scoreLabel(score: Int): String = if (score == 1) "1 repost" else "$score reposts"

    private fun initials(name: String): String = name.trim().take(2).uppercase()

    private fun renderLeaderboard(entries: List<SupabaseClient.LeaderboardEntry>) {
        // The server always appends the caller's own row. When they are
        // outside the top N it is only shown in the "You" card, not the list.
        val me = entries.firstOrNull { it.isMe }
        val visible = entries.filterNot { it.isMe && it.rank > LB_LIMIT }

        lbList.removeAllViews()

        if (visible.isEmpty()) {
            lbPodiumCard.visibility = View.GONE
            lbEmpty.text = "No verified reposts yet for this period. Repost today to take the top spot."
            lbEmpty.visibility = View.VISIBLE
        } else {
            lbEmpty.visibility = View.GONE
            renderPodium(visible.take(3))
            lbPodiumCard.visibility = View.VISIBLE

            val inflater = LayoutInflater.from(this)
            for (entry in visible.drop(3)) {
                val row = inflater.inflate(R.layout.item_leaderboard_row, lbList, false)
                row.findViewById<TextView>(R.id.lb_row_rank).text = entry.rank.toString()
                row.findViewById<TextView>(R.id.lb_row_avatar).text = initials(entry.username)
                row.findViewById<TextView>(R.id.lb_row_name).text = entry.username
                row.findViewById<TextView>(R.id.lb_row_score).text = scoreLabel(entry.score)
                if (entry.isMe) {
                    row.background = ContextCompat.getDrawable(this, R.drawable.lb_row_me_background)
                }
                lbList.addView(row)
            }
        }

        // "You" card
        lbYouCard.visibility = View.VISIBLE
        if (me != null) {
            lbYouRank.text = "#${me.rank}"
            lbYouScore.text = scoreLabel(me.score)
        } else {
            lbYouRank.text = "--"
            lbYouScore.text = "Not ranked yet"
        }
    }

    // Podium slots on screen are [2nd | 1st | 3rd]; entries arrive in rank
    // order, so index 0 -> centre, 1 -> left, 2 -> right. Missing slots
    // (fewer than 3 players) stay invisible but keep their space.
    private fun renderPodium(top: List<SupabaseClient.LeaderboardEntry>) {
        fun fill(slot: Int, entry: SupabaseClient.LeaderboardEntry?) {
            val column = findViewById<LinearLayout>(
                when (slot) { 1 -> R.id.lb_podium_1; 2 -> R.id.lb_podium_2; else -> R.id.lb_podium_3 }
            )
            if (entry == null) {
                column.visibility = View.INVISIBLE
                return
            }
            column.visibility = View.VISIBLE
            val avatar = findViewById<TextView>(
                when (slot) { 1 -> R.id.lb_p1_avatar; 2 -> R.id.lb_p2_avatar; else -> R.id.lb_p3_avatar }
            )
            val name = findViewById<TextView>(
                when (slot) { 1 -> R.id.lb_p1_name; 2 -> R.id.lb_p2_name; else -> R.id.lb_p3_name }
            )
            val score = findViewById<TextView>(
                when (slot) { 1 -> R.id.lb_p1_score; 2 -> R.id.lb_p2_score; else -> R.id.lb_p3_score }
            )
            val block = findViewById<TextView>(
                when (slot) { 1 -> R.id.lb_p1_block; 2 -> R.id.lb_p2_block; else -> R.id.lb_p3_block }
            )
            avatar.text = initials(entry.username)
            name.text = if (entry.isMe) "${entry.username} (you)" else entry.username
            score.text = scoreLabel(entry.score)
            block.text = entry.rank.toString()
        }

        fill(1, top.getOrNull(0))
        fill(2, top.getOrNull(1))
        fill(3, top.getOrNull(2))
    }

    override fun onResume() {
        super.onResume()
        // Covers the case where verification landed while the user was
        // away from this screen (e.g. reopening the app the next day).
        refreshKeyBalance()
        refreshTodayStatus()
        if (showingLeaderboard) loadLeaderboard()
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
            if (!showingLeaderboard) contentScroll.visibility = android.view.View.VISIBLE
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
                                message = "We check your repost and add your key within 24 hours.",
                                icon = R.drawable.ic_pending,
                                tint = R.color.warning_amber
                            )
                        }
                        "verified" -> {
                            repostedToday = true
                            showRepostSent(verified = true)
                            showStatusCard(
                                title = "Repost verified",
                                message = "Today's repost was verified and your key has been added.",
                                icon = R.drawable.ic_check,
                                tint = R.color.vg_green
                            )
                        }
                        "rejected" -> {
                            repostedToday = true
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

        // Send the user to the admin's WhatsApp chat (same number used by
        // Buy Keys / Contact Us) so they can view and repost the admin's status.
        try {
            val message = Uri.encode("Hi VGContact, I want to repost today's status")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://wa.me/${BuyKeysActivity.SUPPORT_WHATSAPP}?text=$message")
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
        showRepostSent()   // instant visible change: green -> white "YOU POSTED TODAY"
        showStatusCard(
            title = "Verification pending",
            message = "We'll add your key within 24 hours.",
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
                            message = "We check your repost and add your key within 24 hours.",
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

    // Not-yet-posted look: solid green button, no icon.
    private fun showRepostReady() {
        repostTodayBtn.isActivated = false
        repostTodayBtn.text = "REPOST TODAY"
        repostTodayBtn.icon = null
    }

    // Posted look: the button flips from solid green to WHITE with a green
    // outline, green text and a green check, and says the user posted.
    // (Colors come from the "activated" state in repost_btn_bg.xml and
    // repost_btn_text.xml; the button stays tappable.)
    private fun showRepostSent(verified: Boolean = false) {
        repostTodayBtn.isActivated = true
        repostTodayBtn.text = if (verified) "POSTED & VERIFIED" else "YOU POSTED TODAY"
        repostTodayBtn.setIconResource(R.drawable.ic_check)
        repostTodayBtn.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        repostTodayBtn.iconPadding = (8 * resources.displayMetrics.density).toInt()
        repostTodayBtn.iconTint = repostTodayBtn.textColors
    }

    // Opens the Buy Keys page (packs + WhatsApp checkout live there).
    private fun openBuyKeysPage() {
        startActivity(Intent(this, BuyKeysActivity::class.java))
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.REPOST)
    }

}
