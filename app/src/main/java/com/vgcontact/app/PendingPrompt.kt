package com.vgcontact.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.concurrent.thread

/**
 * Gate for pending (unverified) users.
 *
 *  - First open: the sheet shows once per install ([show]).
 *  - After "Not now": Home shows the slim strip and dims the viewers card.
 *  - While pending, the only thing that works is the sheet's button. It does what the
 *    Repost screen's button does: opens the admin's WhatsApp (so the user can repost the
 *    status) and logs a 'pending' repost for the admin to verify.
 *    Every other tap calls [showGate] and brings the same sheet back.
 *
 * The pending flag is saved by Home each time it loads the account, so the
 * bottom nav and notification router can check it without a network call.
 */
object PendingPrompt {
    private const val PREFS = "vg_pending_prompt"
    private const val KEY_SHOWN = "sheet_shown"
    private const val KEY_PENDING = "is_pending"

    fun wasShown(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, false)

    private fun markShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOWN, true).apply()
    }

    /** True while the account is pending verification (last known state). */
    fun isPending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

    fun setPending(context: Context, pending: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PENDING, pending).apply()
    }

    /**
     * Same as the Repost screen's button: opens the admin's WhatsApp, then logs today's
     * repost as 'pending' (once per day; a second tap only re-opens WhatsApp).
     */
    private fun repostNow(context: Context, alreadyLogged: Boolean) {
        SupportContact.openSupport(context, "Hi VGContact, I want to repost today's status")
        if (alreadyLogged) return
        val userId = SessionManager(context).getUserId()
        if (userId.isNullOrBlank()) {
            Toast.makeText(context, "Couldn't verify your account. Please restart the app.", Toast.LENGTH_SHORT).show()
            return
        }
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        thread {
            SupabaseClient.submitDailyRepost(userId) { success, message ->
                main.post {
                    if (success) {
                        Toast.makeText(app, "Repost logged. Verification pending.", Toast.LENGTH_SHORT).show()
                    } else if (message != "ALREADY_REPOSTED_TODAY") {
                        Toast.makeText(app, "Couldn't log your repost. Try again.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Shows the sheet again when a locked button is tapped.
     * Returns true if the user is pending (the tap was blocked), false if it may go through.
     */
    fun showGate(activity: Activity): Boolean {
        if (!isPending(activity)) return false
        show(activity, onLater = {})
        return true
    }

    private var visible = false

    /**
     * [onLater] runs for every way of closing without the button: Not now, back,
     * swipe down, tap outside.
     */
    fun show(activity: Activity, onLater: () -> Unit) {
        if (visible || activity.isFinishing || activity.isDestroyed) return
        visible = true
        markShown(activity)
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_pending_verify, null)
        dialog.setContentView(view)

        val chip = view.findViewById<TextView>(R.id.pendingSheetChip)
        val verifyBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.pendingSheetVerifyBtn)

        // If today's repost is already logged, say so and don't log a second one.
        var alreadyLogged = false
        val userId = SessionManager(activity).getUserId()
        if (!userId.isNullOrBlank()) {
            thread {
                SupabaseClient.fetchTodayRepostStatus(userId) { ok, status ->
                    if (ok && status == "pending") {
                        activity.runOnUiThread {
                            alreadyLogged = true
                            chip.visibility = View.VISIBLE
                            verifyBtn.text = "Open WhatsApp again"
                        }
                    }
                }
            }
        }

        var verifyTapped = false
        verifyBtn.setOnClickListener {
            verifyTapped = true
            dialog.dismiss()
            repostNow(activity, alreadyLogged)
        }
        view.findViewById<View>(R.id.pendingSheetLaterBtn).setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener {
            // Let the layout's own rounded white background show instead of the default sheet surface.
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setOnDismissListener {
            visible = false
            if (!verifyTapped) onLater()
        }
        dialog.show()
    }
}
