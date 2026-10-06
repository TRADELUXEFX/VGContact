package com.vgcontact.app

import android.app.Activity
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
 * Remembers "this account is banned" on the phone so the banned screen shows
 * instantly (also offline). Kept separate from the session, so signing out
 * does not erase it. Only a definite "not banned" answer from the server
 * clears it.
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

object BannedHandler {
    @Volatile private var showing = false

    private fun intentFor(ctx: Context) = Intent(ctx, BannedActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    /** Replace the screen the user is on with the banned screen (fade, no back-stack). */
    fun showFrom(a: Activity) {
        if (showing || a is BannedActivity) return
        showing = true
        try {
            a.startActivity(intentFor(a))
            @Suppress("DEPRECATION")
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            a.finish()
        } catch (e: Exception) {
            showing = false
        }
    }

    /** A server call was refused with ACCOUNT_BANNED (login, sign-up, keys...). Any thread. */
    fun trigger(phone: String?) {
        val app = VGApp.instance ?: return
        BanPrefs.set(app, phone ?: SessionManager(app).getPhone(), null)
        if (showing) return
        showing = true
        try { app.startActivity(intentFor(app)) } catch (e: Exception) { showing = false }
    }

    /** Ask the server (in the background) whether this signed-in account is banned. */
    fun checkWithServer(a: Activity) {
        val app = a.applicationContext
        val sm = SessionManager(app)
        val userId = sm.getUserId() ?: return
        Thread {
            try {
                SupabaseClient.getBanStatus(userId, sm.getPhone(), androidId(app)) { banned, reason ->
                    if (banned == true) {
                        BanPrefs.set(app, sm.getPhone(), reason)
                        a.runOnUiThread { if (!a.isFinishing) showFrom(a) }
                    }
                }
            } catch (t: Throwable) { /* a failed check must never crash the app */ }
        }.start()
    }

    /** Called from HomeActivity when a ban is detected during initialization. */
    fun handleBanned(a: Activity, reason: String?) {
        val app = a.applicationContext
        if (reason != null) BanPrefs.set(app, SessionManager(app).getPhone(), reason)
        showFrom(a)
    }

    fun closed() { showing = false }

    fun androidId(c: Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
}

/** Full-screen "Banned" page: account number, reason, what it means, WhatsApp appeal. */
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
        removeSavedContacts()
        findViewById<Button>(R.id.contactCareButton).setOnClickListener { openWhatsApp() }
    }

    // A banned account keeps no VGContact numbers on the phone: remove every
    // contact the app saved (names ending in VGC<N>). Contacts without that
    // tag are never touched. Syncing itself is blocked while banned, and
    // starts again by itself if the ban is lifted.
    private fun removeSavedContacts() {
        // The "VGContact" sync account is not needed while banned; Home brings it back after a ban is lifted.
        SyncAdapterSetup.disable(applicationContext)
        if (!ContactSync.hasPermission(this)) return
        val app = applicationContext
        Thread { try { ContactSync.deleteAll(app) } catch (_: Throwable) { } }.start()
    }

    private fun showReason(code: String?) {
        findViewById<TextView>(R.id.bannedReasonText).text = when (code) {
            "multiple_accounts" -> "Using more than one account"
            "fake_reposts" -> "Submitting fake repost proof"
            "scam" -> "Scamming or misusing the app"
            else -> "Breaking the app's rules"
        }
    }

    // Every time this screen comes into view: refresh the reason, and if the
    // ban was lifted, clear the saved flag and go back into the app.
    override fun onResume() {
        super.onResume()
        val app = applicationContext
        val sm = SessionManager(app)
        Thread {
            try {
                SupabaseClient.getBanStatus(sm.getUserId(), accountNumber, BannedHandler.androidId(app)) { banned, reason ->
                    if (banned == false) {
                        BanPrefs.clear(app)
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            val dest = if (sm.isLoggedIn()) HomeActivity::class.java else RegisterActivity::class.java
                            startActivity(Intent(this, dest).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                            @Suppress("DEPRECATION")
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                            finish()
                        }
                    } else if (banned == true && reason != null) {
                        BanPrefs.set(app, accountNumber, reason)
                        runOnUiThread { if (!isFinishing) showReason(reason) }
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
                Uri.parse("https://wa.me/${SupportContact.WHATSAPP}?text=${Uri.encode(text)}")))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
