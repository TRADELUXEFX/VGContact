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

    companion object {
        // Each user's link is this base + their phone number.
        const val LINK_BASE = "https://vgcontact.netlify.app?ref="
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_referral)
        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green_dark)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        val code = sessionManager.getPhone().orEmpty()
        val link = if (code.isNotBlank()) LINK_BASE + code else ""

        findViewById<View>(R.id.referral_back_btn).setOnClickListener { finish() }

        findViewById<TextView>(R.id.referral_code_text).text =
            if (code.isNotBlank()) code else "Unavailable"
        findViewById<TextView>(R.id.referral_link_text).text =
            if (link.isNotBlank()) link else "Unavailable"

        findViewById<View>(R.id.referral_copy_code_btn).setOnClickListener {
            copy("referral_code", code)
        }
        findViewById<View>(R.id.referral_copy_link_btn).setOnClickListener {
            copy("referral_link", link)
        }
        findViewById<View>(R.id.referral_share_btn).setOnClickListener { share(code, link) }
    }

    override fun onResume() {
        super.onResume()
        loadMilestones()
    }

    // Asks the server to pay any milestone keys now due (every 10 referrals
    // = 1 key; never pays the same key twice) and returns the live count.
    private fun loadMilestones() {
        val userId = sessionManager.getUserId().orEmpty()
        if (userId.isBlank()) return
        Thread {
            SupabaseClient.claimReferralKeys(userId) { ok, referrals, keysAdded ->
                runOnUiThread {
                    if (ok && !isFinishing) {
                        showMilestones(referrals)
                        if (keysAdded > 0) {
                            val word = if (keysAdded == 1) "key" else "keys"
                            Toast.makeText(this, "You earned $keysAdded $word!", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }.start()
    }

    private fun showMilestones(count: Int) {
        findViewById<TextView>(R.id.referral_count_num).text = count.toString()
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
