package com.vgcontact.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The server is the only source of truth: it refuses banned users, and
 * get_ban_status tells the app who is banned. Nothing is saved on the phone.
 */
object BannedHandler {
    @Volatile private var showing = false

    private fun intentFor(ctx: android.content.Context, phone: String?, reason: String?) =
        Intent(ctx, BannedActivity::class.java)
            .putExtra(BannedActivity.EXTRA_PHONE, phone)
            .putExtra(BannedActivity.EXTRA_REASON, reason)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    /** A server call was refused with ACCOUNT_BANNED (login, sign-up, keys...). Any thread. */
    fun trigger(phone: String?) {
        val app = VGApp.instance ?: return
        if (showing) return
        showing = true
        try {
            app.startActivity(intentFor(app, phone ?: SessionManager(app).getPhone(), null))
        } catch (e: Exception) {
            showing = false
        }
    }

    /** Home asks the server; if banned, replace Home with the banned screen. */
    fun checkFromHome(a: Activity) {
        val app = a.applicationContext
        val sm = SessionManager(app)
        val userId = sm.getUserId() ?: return
        Thread {
            try {
                SupabaseClient.getBanStatus(userId, sm.getPhone(), androidId(app)) { banned, reason ->
                    if (banned == true) {
                        a.runOnUiThread {
                            if (!a.isFinishing && !showing) {
                                showing = true
                                a.startActivity(intentFor(a, sm.getPhone(), reason))
                                @Suppress("DEPRECATION")
                                a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                                a.finish()
                            }
                        }
                    }
                }
            } catch (t: Throwable) { /* a failed check must never crash the app */ }
        }.start()
    }

    fun closed() { showing = false }

    fun androidId(c: android.content.Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
}

/** Full-screen "Banned" page: account number, reason, what it means, WhatsApp appeal. */
class BannedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHONE = "phone"
        const val EXTRA_REASON = "reason"
    }

    private var accountNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_banned)
        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_red)

        accountNumber = intent.getStringExtra(EXTRA_PHONE)?.ifBlank { null } ?: SessionManager(this).getPhone()
        accountNumber?.takeIf { it.isNotBlank() }?.let {
            findViewById<TextView>(R.id.bannedNumberText).text = it
            findViewById<View>(R.id.bannedAccountSection).visibility = View.VISIBLE
        }
        showReason(intent.getStringExtra(EXTRA_REASON))
        findViewById<Button>(R.id.contactCareButton).setOnClickListener { openWhatsApp() }
    }

    private fun showReason(code: String?) {
        findViewById<TextView>(R.id.bannedReasonText).text = when (code) {
            "multiple_accounts" -> "Using more than one account"
            "fake_reposts" -> "Submitting fake repost proof"
            "scam" -> "Scamming or misusing the app"
            else -> "Breaking the app's rules"
        }
    }

    // Every time this screen comes back into view: refresh the reason, and if
    // the ban was lifted, go back into the app.
    override fun onResume() {
        super.onResume()
        val app = applicationContext
        val sm = SessionManager(app)
        Thread {
            try {
                SupabaseClient.getBanStatus(sm.getUserId(), accountNumber, BannedHandler.androidId(app)) { banned, reason ->
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (banned == false) {
                            val dest = if (sm.isLoggedIn()) HomeActivity::class.java else RegisterActivity::class.java
                            startActivity(Intent(this, dest).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                            @Suppress("DEPRECATION")
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                            finish()
                        } else if (banned == true && reason != null) {
                            showReason(reason)
                        }
                    }
                }
            } catch (t: Throwable) { /* keep the screen as it is */ }
        }.start()
    }

    // Nowhere to go back to: leave the app.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finishAffinity() }

    override fun onDestroy() {
        super.onDestroy()
        BannedHandler.closed()
    }

    private fun openWhatsApp() {
        val number = accountNumber?.takeIf { it.isNotBlank() }
        val text = if (number != null)
            "Hi VG Kontact, my account ($number) has been banned. I'd like to appeal this."
        else "Hi VG Kontact, my account has been banned. I'd like to appeal this."
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/${BuyKeysActivity.SUPPORT_WHATSAPP}?text=${Uri.encode(text)}")))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
