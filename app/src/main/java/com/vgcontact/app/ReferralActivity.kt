package com.vgcontact.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Refer and earn: the Referral tab of the floating bottom nav (also opened
 * from the referral button on Home). Laid out like VGKontact's Referrals page.
 *
 *  - My referrals tab: the user's code card (Number / Username, copy, share),
 *    then the people they referred. Tap a person who has invited others to see
 *    their referrals (level 2), and tap again for level 3. The server only
 *    answers for people inside the user's own 3 levels.
 *  - Leaderboard tab: top referrers by direct referrals (shown by phone number).
 *
 * The referral code is the user's phone number (same value new users type
 * into the "Referral Username" box at sign-up; users.referred_by stores it).
 */
class ReferralActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    companion object {
        // Each user's link is this base + their phone number.
        const val LINK_BASE = "https://vgcontact.netlify.app?ref="
        private const val PER_PAGE = 10
        // My referrals list is taller per row, so it pages at 3.
        private const val LIST_PER_PAGE = 3
        private const val MAX_DEPTH = 2 // stack size 2 = level 3, the last level
    }

    private class StackEntry(val userId: String, val label: String)

    private val stack = mutableListOf<StackEntry>()
    private var all: List<SupabaseClient.MyReferral> = emptyList()
    private var filtered: List<SupabaseClient.MyReferral> = emptyList()
    private var page = 0
    private var listRequest = 0

    private var board: List<SupabaseClient.LeaderboardEntry> = emptyList()
    private var boardFiltered: List<SupabaseClient.LeaderboardEntry> = emptyList()
    private var boardPage = 0
    private var boardLoaded = false

    private var onBoardTab = false
    private var listLoadedOnce = false

    private lateinit var tabMine: TextView
    private lateinit var tabBoard: TextView
    private lateinit var searchMine: EditText
    private lateinit var searchBoard: EditText
    private lateinit var panelMine: View
    private lateinit var panelBoard: View
    private lateinit var breadcrumb: View
    private lateinit var breadcrumbText: TextView
    private lateinit var recentRow: View
    private lateinit var totalPill: View
    private lateinit var totalText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var listMessage: TextView
    private lateinit var listSpinner: View
    private lateinit var pagerScroll: View
    private lateinit var pagerBox: LinearLayout
    private lateinit var boardBox: LinearLayout
    private lateinit var boardMessage: TextView
    private lateinit var boardSpinner: View
    private lateinit var boardPagerScroll: View
    private lateinit var boardPagerBox: LinearLayout

    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_referral)
        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)
        BottomNavHelper.setup(this, BottomNavHelper.Tab.REFERRAL)

        // Registered after the nav's Back handler so it runs first, but only
        // while drilled in; otherwise Back still goes to Home as before.
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = popLevel()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        bindViews()
        setupCodeCard()
        setupBoardSwitch()

        tabMine.setOnClickListener { showMineTab() }
        tabBoard.setOnClickListener { showBoardTab() }
        breadcrumb.setOnClickListener { popLevel() }
        searchMine.addTextChangedListener { applyListSearch() }
        searchBoard.addTextChangedListener { applyBoardSearch() }

        showMineTab()
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the screen refreshes the top-level list only.
        if (listLoadedOnce && stack.isEmpty() && !onBoardTab) loadList()
    }

    private fun bindViews() {
        tabMine = findViewById(R.id.referral_tab_mine)
        tabBoard = findViewById(R.id.referral_tab_board)
        searchMine = findViewById(R.id.referral_search_mine)
        searchBoard = findViewById(R.id.referral_search_board)
        panelMine = findViewById(R.id.referral_panel_mine)
        panelBoard = findViewById(R.id.referral_panel_board)
        breadcrumb = findViewById(R.id.referral_breadcrumb)
        breadcrumbText = findViewById(R.id.referral_breadcrumb_text)
        recentRow = findViewById(R.id.referral_recent_row)
        totalPill = findViewById(R.id.referral_total_pill)
        totalText = findViewById(R.id.referral_total_text)
        listBox = findViewById(R.id.referral_list)
        listMessage = findViewById(R.id.referral_list_message)
        listSpinner = findViewById(R.id.referral_list_spinner)
        pagerScroll = findViewById(R.id.referral_pager_scroll)
        pagerBox = findViewById(R.id.referral_pager)
        boardBox = findViewById(R.id.referral_board_list)
        boardMessage = findViewById(R.id.referral_board_message)
        boardSpinner = findViewById(R.id.referral_board_spinner)
        boardPagerScroll = findViewById(R.id.referral_board_pager_scroll)
        boardPagerBox = findViewById(R.id.referral_board_pager)
    }

    // ------------------------------------------------------------ tabs

    private fun showMineTab() {
        onBoardTab = false
        panelMine.visibility = View.VISIBLE
        panelBoard.visibility = View.GONE
        searchMine.visibility = View.VISIBLE
        searchBoard.visibility = View.GONE
        styleTab(tabMine, true)
        styleTab(tabBoard, false)
        // Reselecting the tab always goes back to the top-level list.
        stack.clear()
        updateBreadcrumb()
        loadList()
    }

    private fun showBoardTab() {
        onBoardTab = true
        panelMine.visibility = View.GONE
        panelBoard.visibility = View.VISIBLE
        searchMine.visibility = View.GONE
        searchBoard.visibility = View.VISIBLE
        styleTab(tabBoard, true)
        styleTab(tabMine, false)
        backCallback.isEnabled = false
        if (!boardLoaded) loadBoard()
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.background = if (selected)
            ContextCompat.getDrawable(this, R.drawable.referral_tab_selected_background) else null
        tab.setTextColor(ContextCompat.getColor(this, if (selected) R.color.vg_green else R.color.white))
    }

    // ------------------------------------------------------- my referrals

    private fun loadList() {
        val userId = sessionManager.getUserId().orEmpty()
        val requestId = ++listRequest
        listBox.removeAllViews()
        pagerScroll.visibility = View.GONE
        listMessage.visibility = View.GONE; listSpinner.visibility = View.VISIBLE
        if (stack.isEmpty()) totalPill.visibility = View.INVISIBLE
        if (userId.isBlank()) {
            listSpinner.visibility = View.GONE
            listMessage.visibility = View.VISIBLE
            listMessage.text = "No referrals yet."
            return
        }
        val target = stack.lastOrNull()?.userId
        Thread {
            val result = SupabaseClient.fetchMyReferrals(userId, target)
            runOnUiThread {
                if (isFinishing || requestId != listRequest) return@runOnUiThread
                if (result == null) {
                    listSpinner.visibility = View.GONE
                    listMessage.visibility = View.VISIBLE
                    listMessage.text = if (!SupabaseClient.isOnline(this))
                        "No internet connection. Check your connection and try again."
                    else "Couldn't load referrals. Please try again."
                    return@runOnUiThread
                }
                listLoadedOnce = true
                all = result
                searchMine.setText("")
                page = 0
                filtered = all
                if (stack.isEmpty()) {
                    totalText.text = all.size.toString()
                    totalPill.visibility = View.VISIBLE
                }
                renderList()
            }
        }.start()
    }

    private fun applyListSearch() {
        val q = searchMine.text.toString().trim().lowercase(Locale.getDefault())
        val qDigits = q.filter { it.isDigit() }
        filtered = if (q.isEmpty()) all else all.filter {
            it.username.lowercase(Locale.getDefault()).contains(q) ||
                (qDigits.isNotEmpty() && it.phone.filter { c -> c.isDigit() }.contains(qDigits))
        }
        page = 0
        renderList()
    }

    private fun renderList() {
        listBox.removeAllViews()
        if (filtered.isEmpty()) {
            listSpinner.visibility = View.GONE
            listMessage.visibility = View.VISIBLE
            listMessage.text = if (all.isEmpty()) "No referrals yet." else "No matches."
            pagerScroll.visibility = View.GONE
            return
        }
        listSpinner.visibility = View.GONE
        listMessage.visibility = View.GONE

        val start = page * LIST_PER_PAGE
        val end = minOf(start + LIST_PER_PAGE, filtered.size)
        val pageItems = filtered.subList(start, end)
        for ((i, item) in pageItems.withIndex()) {
            listBox.addView(buildReferralRow(item, i != pageItems.lastIndex))
        }
        buildPager(pagerBox, pagerScroll, filtered.size, page, LIST_PER_PAGE) { p -> page = p; renderList() }
    }

    private fun buildReferralRow(item: SupabaseClient.MyReferral, divider: Boolean): View {
        val atLastLevel = stack.size >= MAX_DEPTH
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }

        val pendingColor = ContextCompat.getColor(this, R.color.pending_pill_text)
        val avatar = ImageView(this).apply {
            if (item.isPending) {
                // Not verified yet: dashed amber ring with a clock, row slightly faded.
                setImageResource(R.drawable.ic_clock_small)
                setColorFilter(pendingColor)
                background = ContextCompat.getDrawable(this@ReferralActivity, R.drawable.pending_avatar_background)
            } else {
                setImageResource(R.drawable.ic_profile_person)
                setColorFilter(ContextCompat.getColor(this@ReferralActivity, R.color.vg_green))
                background = ContextCompat.getDrawable(this@ReferralActivity, R.drawable.referral_avatar_background)
            }
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        col.addView(TextView(this).apply {
            text = if (showNumber) {
                if (item.phone.isNotBlank()) formatPhone(item.phone) else item.username
            } else {
                if (item.username.isNotBlank()) item.username else formatPhone(item.phone)
            }
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@ReferralActivity, R.color.vg_dark))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(TextView(this).apply {
            text = relativeTime(item.createdAt)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@ReferralActivity, R.color.text_muted))
        })
        line.addView(avatar)
        line.addView(col)

        if (!atLastLevel) {
            // Trailing pill: invite count, and an arrow when the person has
            // invited others (so the row can be opened). The message icon sits
            // outside the pill as its own round button.
            val count = item.invitedCount
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@ReferralActivity, R.drawable.freq_summary_pill_background)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            if (item.isPending) {
                // Not verified yet: the pill just says "Pending" (amber) instead of the invite count.
                pill.background = ContextCompat.getDrawable(this, R.drawable.pending_pill_background)
            }
            pill.addView(TextView(this).apply {
                text = if (item.isPending) "Pending" else if (count > 0) "$count invited" else "no invites yet"
                textSize = 12f
                setTextColor(
                    if (item.isPending) pendingColor
                    else ContextCompat.getColor(
                        this@ReferralActivity,
                        if (count > 0) R.color.vg_green else R.color.text_muted
                    )
                )
                if (item.isPending || count > 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            if (count > 0 && !item.isPending) {
                pill.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    setColorFilter(ContextCompat.getColor(this@ReferralActivity, R.color.vg_green))
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) }
                })
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener { drillInto(item) }
            }
            line.addView(pill)
            line.addView(nudgeIcon(item).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            })
        } else {
            // Last level: nothing opens further, only the message button.
            line.addView(nudgeIcon(item))
        }

        if (item.isPending) {
            // Tapping a pending person explains what "Pending" means (works at every level).
            row.alpha = 0.85f
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { showPendingInfo(item) }
        }

        row.addView(line)
        if (divider) {
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(ContextCompat.getColor(this@ReferralActivity, R.color.stats_card_border))
            })
        }
        return row
    }

    // Explains the amber "Pending" mark. Two buttons only: Got it (closes) and Message (WhatsApp).
    private fun showPendingInfo(item: SupabaseClient.MyReferral) {
        val name = if (showNumber) {
            if (item.phone.isNotBlank()) formatPhone(item.phone) else item.username
        } else {
            if (item.username.isNotBlank()) item.username else formatPhone(item.phone)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.VGRoundedAlertDialog)
            .setTitle("Pending verification")
            .setMessage(
                "$name joined with your code but hasn't finished verification yet.\n\n" +
                "Until an admin approves their first task, they aren't placed in a group. " +
                "This changes to normal by itself once they're verified.\n\n" +
                "Tap Message to remind them to complete it."
            )
            .setPositiveButton("Got it", null)
            .setNeutralButton("Message") { _, _ -> openWhatsApp(item.phone) }
            .show().also { RoundedDialog.style(it) }
    }

    // Round message button, shown outside the invite pill (same style as the
    // copy button on the code card).
    private fun nudgeIcon(item: SupabaseClient.MyReferral): ImageView =
        ImageView(this).apply {
            setImageResource(R.drawable.ic_chat)
            setColorFilter(ContextCompat.getColor(this@ReferralActivity, R.color.vg_green_dark))
            background = ContextCompat.getDrawable(this@ReferralActivity, R.drawable.icon_button_circle)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Message ${item.username} on WhatsApp"
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            isClickable = true
            isFocusable = true
            setOnClickListener { openWhatsApp(item.phone) }
        }

    private fun drillInto(item: SupabaseClient.MyReferral) {
        if (stack.size >= MAX_DEPTH) return
        stack.add(StackEntry(item.userId, item.username))
        updateBreadcrumb()
        loadList()
    }

    private fun popLevel() {
        if (stack.isEmpty()) return
        stack.removeAt(stack.lastIndex)
        updateBreadcrumb()
        loadList()
    }

    private fun updateBreadcrumb() {
        backCallback.isEnabled = stack.isNotEmpty() && !onBoardTab
        recentRow.visibility = if (stack.isEmpty()) View.VISIBLE else View.GONE
        if (stack.isEmpty()) {
            breadcrumb.visibility = View.GONE
        } else {
            breadcrumb.visibility = View.VISIBLE
            breadcrumbText.text = "${stack.last().label}'s referrals"
        }
    }

    private fun openWhatsApp(phone: String) {
        val digits = phone.filter { it.isDigit() }
        if (digits.isEmpty()) {
            Toast.makeText(this, "No number available", Toast.LENGTH_SHORT).show()
            return
        }
        // Nigerian local format 0803... -> 234803...
        val intl = if (digits.length == 11 && digits.startsWith("0")) "234" + digits.substring(1) else digits
        val message = Uri.encode("Hi, you registered under me on VGContact")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$intl?text=$message")))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    // --------------------------------------------------------- leaderboard

    private fun loadBoard() {
        val userId = sessionManager.getUserId().orEmpty()
        boardBox.removeAllViews()
        boardPagerScroll.visibility = View.GONE
        boardMessage.visibility = View.GONE; boardSpinner.visibility = View.VISIBLE
        if (userId.isBlank()) {
            boardSpinner.visibility = View.GONE
            boardMessage.visibility = View.VISIBLE
            boardMessage.text = "No referrals yet."
            return
        }
        Thread {
            val result = SupabaseClient.fetchReferralLeaderboard(userId)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (result == null) {
                    boardSpinner.visibility = View.GONE
                    boardMessage.visibility = View.VISIBLE
                    boardMessage.text = if (!SupabaseClient.isOnline(this))
                        "No internet connection. Check your connection and try again."
                    else "Couldn't load the leaderboard. Please try again."
                    return@runOnUiThread
                }
                boardLoaded = true
                board = result
                boardFiltered = result
                boardPage = 0
                searchBoard.setText("")
                renderBoard()
            }
        }.start()
    }

    private fun applyBoardSearch() {
        val q = searchBoard.text.toString().trim().lowercase(Locale.getDefault())
        val qDigits = q.filter { it.isDigit() }
        boardFiltered = if (q.isEmpty()) board else board.filter {
            (qDigits.isNotEmpty() && it.phone.filter { c -> c.isDigit() }.contains(qDigits)) ||
                it.username.lowercase(Locale.getDefault()).contains(q)
        }
        boardPage = 0
        renderBoard()
    }

    private fun renderBoard() {
        boardBox.removeAllViews()
        if (boardFiltered.isEmpty()) {
            boardSpinner.visibility = View.GONE
            boardMessage.visibility = View.VISIBLE
            boardMessage.text = if (board.isEmpty()) "No referrals yet." else "No matches."
            boardPagerScroll.visibility = View.GONE
            return
        }
        boardSpinner.visibility = View.GONE
        boardMessage.visibility = View.GONE

        val start = boardPage * PER_PAGE
        val end = minOf(start + PER_PAGE, boardFiltered.size)
        val items = boardFiltered.subList(start, end)
        for ((i, e) in items.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, dp(16))
            }
            line.addView(TextView(this).apply {
                val who = if (showNumber) {
                    if (e.phone.isNotBlank()) formatPhone(e.phone) else e.username
                } else {
                    if (e.username.isNotBlank()) e.username else formatPhone(e.phone)
                }
                text = if (e.isMe) "$who (you)" else who
                textSize = 14f
                setTextColor(
                    ContextCompat.getColor(
                        this@ReferralActivity, if (e.isMe) R.color.vg_green_dark else R.color.vg_dark
                    )
                )
                if (e.isMe) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            line.addView(TextView(this).apply {
                text = e.referralCount.toString()
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@ReferralActivity, R.color.vg_green))
            })
            row.addView(line)
            if (i != items.lastIndex) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(ContextCompat.getColor(this@ReferralActivity, R.color.stats_card_border))
                })
            }
            boardBox.addView(row)
        }
        buildPager(boardPagerBox, boardPagerScroll, boardFiltered.size, boardPage) { p -> boardPage = p; renderBoard() }
    }

    // ---------------------------------------------------------------- pager

    // Numbered page circles (1, 2, 3...), hidden when everything fits on one page.
    private fun buildPager(
        box: LinearLayout, scroll: View, total: Int, current: Int, perPage: Int = PER_PAGE,
        onPage: (Int) -> Unit
    ) {
        val pages = (total + perPage - 1) / perPage
        if (pages <= 1) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE
        box.removeAllViews()
        // Max 5 numbered circles at a time; arrows on the sides move to the
        // previous / next page (the window of 5 slides with the current page).
        val maxBtns = 5
        val winStart = if (pages <= maxBtns) 0 else (current - 2).coerceIn(0, pages - maxBtns)
        val winEnd = minOf(winStart + maxBtns, pages)
        fun addBtn(label: String, enabled: Boolean, selected: Boolean, target: Int) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                alpha = if (enabled) 1f else 0.35f
                setBackgroundResource(
                    if (selected) R.drawable.page_button_selected_background
                    else R.drawable.page_button_default_background
                )
                setTextColor(ContextCompat.getColor(this@ReferralActivity, if (selected) R.color.white else R.color.vg_dark))
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    marginStart = if (box.childCount == 0) 0 else dp(8)
                }
                setOnClickListener { if (enabled && !selected) onPage(target) }
            })
        }
        if (pages > maxBtns) addBtn("\u2039", current > 0, false, current - 1)
        for (i in winStart until winEnd) addBtn((i + 1).toString(), true, i == current, i)
        if (pages > maxBtns) addBtn("\u203A", current < pages - 1, false, current + 1)
    }

    // --------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // "2026-09-28T10:15:30.12+00:00" -> "2 days ago". Times from the server are UTC.
    private fun relativeTime(iso: String): String {
        if (iso.length < 19) return ""
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val then = fmt.parse(iso.substring(0, 19)) ?: return ""
            val mins = (System.currentTimeMillis() - then.time) / 60000
            val days = mins / (60 * 24)
            when {
                mins < 1 -> "just now"
                mins < 60 -> "$mins min ago"
                mins < 60 * 24 -> "today"
                days == 1L -> "yesterday"
                days < 7 -> "$days days ago"
                days < 14 -> "last week"
                days < 60 -> "${days / 7} weeks ago"
                else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(then.time))
            }
        } catch (e: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------ code card

    // One Number / Username switch shared by the code card and the leaderboard header:
    // flipping either one flips both, and the leaderboard rows follow it.
    private var showNumber = true
    private var codeCardRender: (() -> Unit)? = null

    private fun setSwitch(number: Boolean) {
        showNumber = number
        codeCardRender?.invoke()
        styleBoardSwitch()
        if (boardLoaded) renderBoard()
        if (listLoadedOnce) renderList()
    }

    private fun styleBoardSwitch() {
        val tabNumber = findViewById<TextView>(R.id.referral_board_tab_number)
        val tabUsername = findViewById<TextView>(R.id.referral_board_tab_username)
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
    }

    private fun setupBoardSwitch() {
        findViewById<View>(R.id.referral_board_tab_number).setOnClickListener { setSwitch(true) }
        findViewById<View>(R.id.referral_board_tab_username).setOnClickListener { setSwitch(false) }
        styleBoardSwitch()
    }

    private fun setupCodeCard() {
        val phone = sessionManager.getPhone().orEmpty()
        val username = sessionManager.getUsername().orEmpty()
        val link = if (phone.isNotBlank()) LINK_BASE + phone else ""

        val codeText = findViewById<TextView>(R.id.referral_code_text)
        val tabNumber = findViewById<TextView>(R.id.referral_tab_number)
        val tabUsername = findViewById<TextView>(R.id.referral_tab_username)

        // The code shown (and copied / shared) follows the Number / Username switch.
        fun currentValue(): String = if (showNumber) phone else username

        fun render() {
            val value = currentValue()
            codeText.text = when {
                value.isBlank() -> "Unavailable"
                showNumber -> formatPhone(value)
                else -> value
            }
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
        }

        tabNumber.setOnClickListener { setSwitch(true) }
        tabUsername.setOnClickListener { setSwitch(false) }
        codeCardRender = { render() }
        render()

        findViewById<View>(R.id.referral_copy_code_btn).setOnClickListener {
            copy("referral_code", currentValue())
        }
        findViewById<View>(R.id.referral_share_btn).setOnClickListener {
            share(currentValue(), link)
        }
    }

    // 09110321143 -> 0911 032 1143 (display only; copy/share use the raw value).
    private fun formatPhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length == 11) {
            "${digits.substring(0, 4)} ${digits.substring(4, 7)} ${digits.substring(7)}"
        } else raw
    }

    private fun copy(label: String, value: String) {
        if (value.isBlank()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
    }

    private fun share(code: String, link: String) {
        val message = buildString {
            append("Join me on VGContact! ")
            append("Download it here: $link")
            if (code.isNotBlank()) {
                append("\nWhen you sign up, enter my referral code: $code")
            }
        }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            }
            startActivity(Intent.createChooser(intent, "Refer & Earn"))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open share menu", Toast.LENGTH_SHORT).show()
        }
    }
}
