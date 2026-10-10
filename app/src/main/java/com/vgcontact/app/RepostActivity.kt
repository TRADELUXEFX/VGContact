package com.vgcontact.app

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.graphics.Typeface
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import android.widget.ImageView
import android.view.Gravity
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
 *
 * Two tabs in the header: My Repost (the button) and Leaderboard
 * (hero rank card, top-3 podium, ranked list with a pager; styled after
 * VGKontact's repost leaderboard). The board comes from get_leaderboard:
 * usernames and counts only, never phone numbers.
 */
class RepostActivity : BaseActivity() {

    companion object {
        private const val PREF_TODAYS_TASK_DISMISSED = "todays_task_dismissed"
        private const val BOARD_PAGE_SIZE = 10
        // Matches the server setting free_repost_cap. Change both together.
        private const val FREE_REPOST_CAP = 3
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

    // Leaderboard tab
    private lateinit var tabMine: TextView
    private lateinit var tabBoard: TextView
    private lateinit var panelMine: View
    private lateinit var panelBoard: View
    private lateinit var boardList: LinearLayout
    private lateinit var boardPagerScroll: View
    private lateinit var boardPager: LinearLayout
    private lateinit var boardMessage: TextView
    private lateinit var boardSpinner: View

    private var board: List<SupabaseClient.RepostBoardEntry> = emptyList()
    private var boardPage = 0
    private var boardRequest = 0
    private var onBoardTab = false
    // Phone number / Username switch in the leaderboard header (default Username).
    private var showNumber = false

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

        bindBoardViews()
        setupBoardSwitch()
        tabMine.setOnClickListener { showMineTab() }
        tabBoard.setOnClickListener { showBoardTab() }

        repostTodayBtn.setOnClickListener { onRepostTodayClicked() }
        buyViewersBtn.setOnClickListener { openBuyViewers() }

        refreshTodayStatus()
        refreshFreeReposts()

        setupBottomNav()
        FloatingContactHelper.attach(this)
    }

    // ---------------- free reposts progress ----------------

    private fun refreshFreeReposts() {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return
        thread {
            SupabaseClient.fetchHome(userId) { success, home ->
                if (!success || home == null) return@fetchHome
                runOnUiThread { bindFreeReposts(home.verifiedReposts) }
            }
        }
    }

    private fun bindFreeReposts(verified: Int) {
        val done = verified.coerceIn(0, FREE_REPOST_CAP)
        val left = FREE_REPOST_CAP - done
        val density = resources.displayMetrics.density

        val segments = findViewById<LinearLayout>(R.id.free_repost_segments)
        segments.removeAllViews()
        for (i in 0 until FREE_REPOST_CAP) {
            val color = when {
                i < done -> R.color.vg_green
                i == done -> R.color.card_border   // the next one to fill
                else -> R.color.gray_light
            }
            val seg = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 99 * density
                    setColor(ContextCompat.getColor(this@RepostActivity, color))
                }
            }
            segments.addView(seg, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = (6 * density).toInt()
            })
        }

        val pill = findViewById<TextView>(R.id.free_repost_pill)
        pill.text = if (left == 0) "Complete" else "$left to go"

        val count = findViewById<TextView>(R.id.free_repost_count)
        count.text = android.text.SpannableString("$done of $FREE_REPOST_CAP done").apply {
            val start = done.toString().length
            setSpan(
                android.text.style.RelativeSizeSpan(0.55f), start, length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                android.text.style.ForegroundColorSpan(ContextCompat.getColor(this@RepostActivity, R.color.text_muted)),
                start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        findViewById<TextView>(R.id.free_repost_msg).text = when {
            left == 0 -> "All $FREE_REPOST_CAP free reposts done. Keep reposting to climb the board."
            left == 1 -> "One more verified repost brings you more viewers."
            else -> "$left more verified reposts bring you more viewers."
        }
        findViewById<View>(R.id.free_repost_card).visibility = View.VISIBLE
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
        refreshFreeReposts()
        if (onBoardTab) loadBoard()
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

        // The Repost screen only sends the user to the admin's WhatsApp (the image post is
        // the separate pending-sheet task). Today's repost is logged once per day, as before.
        val name = sessionManager.getUsername()
        SupportContact.openSupport(
            this,
            "Hi VGContact admin, I reposted today." + if (name.isNullOrBlank()) "" else " My username is $name."
        )
        logRepost(userId)
    }

    private fun logRepost(userId: String) {
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
                        val msg = SupabaseClient.messageForCode(message) ?: "Couldn't log your repost. Try again."
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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

    // Not posted yet: solid RED button, white text, white send icon next to the text.
    private fun showRepostReady() {
        repostTodayBtn.text = "REPOST TODAY"
        repostTodayBtn.setIconResource(R.drawable.ic_pending_send)
        repostTodayBtn.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        repostTodayBtn.iconPadding = (8 * resources.displayMetrics.density).toInt()
        repostTodayBtn.iconSize = (20 * resources.displayMetrics.density).toInt()
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

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private fun setupBoardSwitch() {
        val tabNumber = findViewById<TextView>(R.id.repost_board_tab_number)
        val tabUsername = findViewById<TextView>(R.id.repost_board_tab_username)
        fun render() {
            tabNumber.background =
                if (showNumber) ContextCompat.getDrawable(this, R.drawable.referral_switch_selected) else null
            tabUsername.background =
                if (!showNumber) ContextCompat.getDrawable(this, R.drawable.referral_switch_selected) else null
            tabNumber.setTextColor(
                ContextCompat.getColor(this, if (showNumber) R.color.white else R.color.text_secondary)
            )
            tabUsername.setTextColor(
                ContextCompat.getColor(this, if (!showNumber) R.color.white else R.color.text_secondary)
            )
            if (board.isNotEmpty()) renderBoard()
        }
        tabNumber.setOnClickListener { showNumber = true; render() }
        tabUsername.setOnClickListener { showNumber = false; render() }
        render()
    }

    // 09110321143 -> 0911 032 1143 (display only).
    private fun formatPhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length == 11) {
            "${digits.substring(0, 4)} ${digits.substring(4, 7)} ${digits.substring(7)}"
        } else raw
    }

    private fun bindBoardViews() {
        tabMine = findViewById(R.id.repost_tab_mine)
        tabBoard = findViewById(R.id.repost_tab_board)
        panelMine = findViewById(R.id.repost_panel_mine)
        panelBoard = findViewById(R.id.repost_panel_board)
        boardList = findViewById(R.id.board_list)
        boardPagerScroll = findViewById(R.id.board_pager_scroll)
        boardPager = findViewById(R.id.board_pager)
        boardMessage = findViewById(R.id.board_message)
        boardSpinner = findViewById(R.id.repost_board_spinner)
    }

    private fun showMineTab() {
        onBoardTab = false
        panelMine.visibility = View.VISIBLE
        panelBoard.visibility = View.GONE
        styleTab(tabMine, true)
        styleTab(tabBoard, false)
    }

    private fun showBoardTab() {
        onBoardTab = true
        panelMine.visibility = View.GONE
        panelBoard.visibility = View.VISIBLE
        styleTab(tabBoard, true)
        styleTab(tabMine, false)
        // The board has its own loading text, so don't make it wait for
        // today's repost status before the body shows.
        if (!initialContentRevealed) {
            initialContentRevealed = true
            loadingState.visibility = View.GONE
            contentScroll.visibility = View.VISIBLE
        }
        loadBoard()
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.background = if (selected)
            ContextCompat.getDrawable(this, R.drawable.referral_tab_selected_background) else null
        tab.setTextColor(ContextCompat.getColor(this, if (selected) R.color.vg_green else R.color.white))
    }

    // ------------------------------------------------------------------
    // Leaderboard
    // ------------------------------------------------------------------

    // Text under the leaderboard, with the trophy illustration above it when the board is simply empty.
    private fun setBoardMessage(text: String, illustration: Boolean) {
        boardMessage.text = text
        boardMessage.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()
        boardMessage.setCompoundDrawablesWithIntrinsicBounds(0, if (illustration) R.drawable.ill_leaderboard_empty else 0, 0, 0)
    }

    private fun loadBoard() {
        val userId = sessionManager.getUserId().orEmpty()
        val requestId = ++boardRequest
        if (board.isEmpty()) {
            boardList.removeAllViews()
            boardPagerScroll.visibility = View.GONE
            boardMessage.visibility = View.GONE; boardSpinner.visibility = View.VISIBLE
        }
        if (userId.isBlank()) {
            boardSpinner.visibility = View.GONE
            boardMessage.visibility = View.VISIBLE
            setBoardMessage("No reposts yet. Be the first on the board.", true)
            return
        }
        thread {
            val reposts = SupabaseClient.fetchRepostLeaderboard(userId, "reposts")
            runOnUiThread {
                if (isFinishing || requestId != boardRequest) return@runOnUiThread
                if (reposts == null) {
                    if (board.isEmpty()) {
                        boardSpinner.visibility = View.GONE
                        boardMessage.visibility = View.VISIBLE
                        setBoardMessage(if (!SupabaseClient.isOnline(this))
                            "No internet connection. Check your connection and try again."
                        else "Couldn't load the leaderboard. Please try again.", false)
                    }
                    return@runOnUiThread
                }
                board = reposts
                boardPage = 0
                renderBoard()
            }
        }
    }

    private fun renderBoard() {
        boardList.removeAllViews()
        if (board.isEmpty()) {
            boardPagerScroll.visibility = View.GONE
            boardSpinner.visibility = View.GONE
            boardMessage.visibility = View.VISIBLE
            setBoardMessage("No reposts yet. Be the first on the board.", true)
            return
        }
        boardSpinner.visibility = View.GONE
        boardMessage.visibility = View.GONE

        // 10 rows per page, ranked by the server (ties share a rank).
        val pages = maxOf(1, (board.size + BOARD_PAGE_SIZE - 1) / BOARD_PAGE_SIZE)
        if (boardPage >= pages) boardPage = pages - 1
        val start = boardPage * BOARD_PAGE_SIZE
        val rows = board.subList(start, minOf(start + BOARD_PAGE_SIZE, board.size))
        rows.forEachIndexed { i, entry ->
            boardList.addView(buildBoardRow(entry))
            if (i != rows.lastIndex) {
                boardList.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(ContextCompat.getColor(this@RepostActivity, R.color.stats_card_border))
                })
            }
        }
        buildBoardPager(pages)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // One row, same scheme as VGKontact's repost board: numbered circle
    // (gold / silver / bronze for 1-3, soft green after), name,
    // repost count on the right. The user's row is tinted.
    private fun buildBoardRow(entry: SupabaseClient.RepostBoardEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (entry.isMe) setBackgroundResource(R.drawable.repost_row_me_background)
        }

        val top3 = entry.rank in 1..3
        val badgeBg = when (entry.rank) {
            1 -> R.drawable.repost_rank_1
            2 -> R.drawable.repost_rank_2
            3 -> R.drawable.repost_rank_3
            else -> R.drawable.repost_rank_other
        }
        row.addView(TextView(this).apply {
            text = entry.rank.toString()
            textSize = 14f
            typeface = Fonts.semibold(this@RepostActivity)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setBackgroundResource(badgeBg)
            setTextColor(ContextCompat.getColor(this@RepostActivity,
                if (top3) R.color.white else R.color.vg_green_dark))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        })

        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14); marginEnd = dp(8) }
        }
        nameCol.addView(TextView(this).apply {
            text = if (entry.isMe) "You" else if (showNumber) {
                if (entry.phone.isNotBlank()) formatPhone(entry.phone) else entry.username
            } else {
                if (entry.username.isNotBlank()) entry.username else formatPhone(entry.phone)
            }
            textSize = 14f
            typeface = if (entry.isMe || top3) Fonts.semibold(this@RepostActivity) else Fonts.medium(this@RepostActivity)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            TextFit.shrinkToFit(this, 14)
            setTextColor(ContextCompat.getColor(this@RepostActivity, R.color.vg_dark))
        })
        row.addView(nameCol)

        row.addView(TextView(this).apply {
            text = entry.score.toString()
            textSize = 14f
            typeface = Fonts.semibold(this@RepostActivity)
            includeFontPadding = false
            setTextColor(ContextCompat.getColor(this@RepostActivity, R.color.vg_green))
        })
        return row
    }

    // Numbered page circles, hidden when everything fits on one page.
    private fun buildBoardPager(pages: Int) {
        if (pages <= 1) {
            boardPagerScroll.visibility = View.GONE
            return
        }
        boardPagerScroll.visibility = View.VISIBLE
        boardPager.removeAllViews()
        // Max 5 numbered circles at a time; arrows on the sides move to the
        // previous / next page (the window of 5 slides with the current page).
        val maxBtns = 5
        val winStart = if (pages <= maxBtns) 0 else (boardPage - 2).coerceIn(0, pages - maxBtns)
        val winEnd = minOf(winStart + maxBtns, pages)
        fun addBtn(label: String, enabled: Boolean, selected: Boolean, target: Int) {
            boardPager.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                alpha = if (enabled) 1f else 0.35f
                setBackgroundResource(
                    if (selected) R.drawable.page_button_selected_background
                    else R.drawable.page_button_default_background
                )
                setTextColor(ContextCompat.getColor(this@RepostActivity, if (selected) R.color.white else R.color.vg_dark))
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    marginStart = if (boardPager.childCount == 0) 0 else dp(8)
                }
                setOnClickListener { if (enabled && !selected) { boardPage = target; renderBoard() } }
            })
        }
        if (pages > maxBtns) addBtn("\u2039", boardPage > 0, false, boardPage - 1)
        for (i in winStart until winEnd) addBtn((i + 1).toString(), true, i == boardPage, i)
        if (pages > maxBtns) addBtn("\u203A", boardPage < pages - 1, false, boardPage + 1)
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.REPOST)
    }

}
