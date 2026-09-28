package com.vgcontact.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Refer & Earn page, opened from the "Refer & Earn" button on Home.
 * The referral code is the user's phone number (same value shown in the
 * Home header and the same value new users type into the "Referral
 * Username" box at sign-up; users.referred_by stores it).
 */
class ReferralActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    // The body stays hidden (spinner under the green header) until both
    // fetches finish. Later refreshes (onResume) update in place.
    private var keysLoaded = false
    private var milestonesLoaded = false
    private var contentRevealed = false

    companion object {
        // Each user's link is this base + their phone number.
        const val LINK_BASE = "https://vgcontact.netlify.app?ref="
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_referral)
        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        val phone = sessionManager.getPhone().orEmpty()
        val username = sessionManager.getUsername().orEmpty()
        val link = if (phone.isNotBlank()) LINK_BASE + phone else ""

        BackHeader.bind(this, "Refer and earn")
        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        val codeText = findViewById<TextView>(R.id.referral_code_text)
        val tabNumber = findViewById<TextView>(R.id.referral_tab_number)
        val tabUsername = findViewById<TextView>(R.id.referral_tab_username)

        // The code shown (and copied / shared) follows the Number / Username switch.
        var showingNumber = true
        fun currentValue(): String = if (showingNumber) phone else username

        fun render() {
            val value = currentValue()
            codeText.text = when {
                value.isBlank() -> "Unavailable"
                showingNumber -> formatPhone(value)
                else -> value
            }
            tabNumber.background =
                if (showingNumber) ContextCompat.getDrawable(this, R.drawable.referral_switch_selected) else null
            tabUsername.background =
                if (!showingNumber) ContextCompat.getDrawable(this, R.drawable.referral_switch_selected) else null
            tabNumber.setTextColor(
                ContextCompat.getColor(this, if (showingNumber) R.color.white else R.color.text_secondary)
            )
            tabUsername.setTextColor(
                ContextCompat.getColor(this, if (!showingNumber) R.color.white else R.color.text_secondary)
            )
        }

        tabNumber.setOnClickListener { showingNumber = true; render() }
        tabUsername.setOnClickListener { showingNumber = false; render() }
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

    override fun onResume() {
        super.onResume()
        loadKeyBalance()
        loadMilestones()
    }

    // Header "Total keys" card: the user's current key balance.
    private fun loadKeyBalance() {
        val userId = sessionManager.getUserId().orEmpty()
        if (userId.isBlank()) {
            keysLoaded = true
            revealIfReady()
            return
        }
        Thread {
            SupabaseClient.fetchKeyBalance(userId) { ok, balance ->
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (ok) {
                        findViewById<TextView>(R.id.referral_keys_text).text = balance.toString()
                    }
                    keysLoaded = true
                    revealIfReady()
                }
            }
        }.start()
    }

    // Asks the server to pay any milestone keys now due (every 10 referrals
    // = 1 key; never pays the same key twice) and returns the live count.
    private fun loadMilestones() {
        val userId = sessionManager.getUserId().orEmpty()
        if (userId.isBlank()) {
            milestonesLoaded = true
            revealIfReady()
            return
        }
        Thread {
            SupabaseClient.claimReferralKeys(userId) { ok, referrals, keysAdded ->
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (ok) {
                        showMilestones(referrals)
                        if (keysAdded > 0) {
                            loadKeyBalance()
                            val word = if (keysAdded == 1) "key" else "keys"
                            Toast.makeText(this, "You earned $keysAdded $word!", Toast.LENGTH_LONG).show()
                        }
                    }
                    // Reveal only after the milestones are filled in (or failed).
                    milestonesLoaded = true
                    revealIfReady()
                }
            }
        }.start()
    }

    private fun revealIfReady() {
        if (contentRevealed || !keysLoaded || !milestonesLoaded) return
        contentRevealed = true
        findViewById<View>(R.id.referral_loading_state).visibility = View.GONE
        findViewById<View>(R.id.referral_content_scroll).visibility = View.VISIBLE
    }

    private fun showMilestones(count: Int) {
        findViewById<TextView>(R.id.referral_to_go).text = "${10 - (count % 10)} to go"
        findViewById<ProgressBar>(R.id.referral_progress).progress = count % 10
        styleChip(R.id.referral_chip_10, count >= 10)
        styleChip(R.id.referral_chip_20, count >= 20)
        styleChip(R.id.referral_chip_30, count >= 30)
        styleChip(R.id.referral_chip_50, count >= 50)
    }

    private fun styleChip(chipId: Int, reached: Boolean) {
        val chip = findViewById<LinearLayout>(chipId)
        chip.background = ContextCompat.getDrawable(
            this,
            if (reached) R.drawable.freq_tile_selected_background else R.drawable.stats_card_background
        )
        (chip.getChildAt(0) as TextView).setTextColor(
            ContextCompat.getColor(this, if (reached) R.color.vg_green_dark else R.color.text_secondary)
        )
        (chip.getChildAt(1) as TextView).setTextColor(
            ContextCompat.getColor(this, if (reached) R.color.vg_green_dark else R.color.text_muted)
        )
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
