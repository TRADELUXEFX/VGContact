package com.vgcontact.app

import android.content.Context
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
 * Remembers "this device is banned" so the banned screen shows instantly and
 * offline. Separate file from the session, so signing out does not erase it.
 */
object BanPrefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("vg_ban", Context.MODE_PRIVATE)
    fun isBanned(c: Context) = p(c).getBoolean("banned", false)
    fun phone(c: Context): String? = p(c).getString("phone", null)?.ifBlank { null }
    fun reason(c: Context): String? = p(c).getString("reason", null)?.ifBlank { null }
    fun set(c: Context, phone: String?, reason: String?) {
        p(c).edit().putBoolean("banned", true)
            .putString("phone", phone ?: phone(c) ?: "")
            .putString("reason", reason ?: reason(c) ?: "").apply()
    }
    fun clear(c: Context) = p(c).edit().clear().apply()
}

/** Single place that reacts to "this account is banned" (server error or status check). */
object BannedHandler {
    @Volatile private var launching = false

    /** Server refused a call with ACCOUNT_BANNED (login, sign-up, keys...). */
    fun trigger(phone: String?) {
        val app = VGApp.instance ?: return
        BanPrefs.set(app, phone ?: SessionManager(app).getPhone(), null)
        launch(app)
    }

    fun launch(ctx: Context) {
        if (launching) return
        launching = true
        val app = ctx.applicationContext
        app.startActivity(
            Intent(app, BannedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    fun reset() { launching = false }

    fun androidId(c: Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

    /** Asks the server if this signed-in user is banned; if so, opens the banned screen. */
    fun verifyInBackground(ctx: Context) {
        val app = ctx.applicationContext
        val sm = SessionManager(app)
        val userId = sm.getUserId() ?: return
        Thread {
            SupabaseClient.getBanStatus(userId, sm.getPhone(), androidId(app)) { banned, reason ->
                if (banned == true) {
                    BanPrefs.set(app, sm.getPhone(), reason)
                    launch(app)
                }
            }
        }.start()
    }
}

/**
 * Full-screen "Banned" page: account number, reason, what it means, and a
 * WhatsApp button to appeal. No way past it except support lifting the ban.
 */
class BannedActivity : AppCompatActivity() {

    private var accountNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_banned)
        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_red)

        accountNumber = BanPrefs.phone(this) ?: SessionManager(this).getPhone()
        accountNumber?.takeIf { it.isNotBlank() }?.let {
            findViewById<TextView>(R.id.bannedNumberText).text = it
            findViewById<View>(R.id.bannedAccountSection).visibility = View.VISIBLE
        }
        showReason(BanPrefs.reason(this))
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

    override fun onResume() {
        super.onResume()
        // Refresh the reason, and if support lifted the ban let the user back in.
        val app = applicationContext
        val sm = SessionManager(app)
        Thread {
            SupabaseClient.getBanStatus(sm.getUserId(), accountNumber, BannedHandler.androidId(app)) { banned, reason ->
                if (banned == false) {
                    BanPrefs.clear(app)
                    runOnUiThread {
                        startActivity(
                            Intent(this, SplashActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        finish()
                    }
                } else if (banned == true && reason != null) {
                    BanPrefs.set(app, accountNumber, reason)
                    runOnUiThread { showReason(reason) }
                }
            }
        }.start()
    }

    // Nowhere to go back to: leave the app.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finishAffinity() }

    override fun onDestroy() {
        super.onDestroy()
        BannedHandler.reset()
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
