package com.vgcontact.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Gate for pending (unverified) users.
 *
 *  - First open: the sheet shows once per install ([show]).
 *  - After "Not now": Home shows the slim strip and dims the viewers card.
 *  - While pending, the only thing that works is the sheet's button, which opens
 *    the admin's WhatsApp with a ready-made message (compose screen, not sent yet).
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

    /** Opens the admin's WhatsApp with the verify message typed in, ready to send. */
    fun openVerifyChat(context: Context) {
        val username = SessionManager(context).getUsername()
        val message = "Hi VGContact, please verify my account." +
            if (username.isNullOrBlank()) "" else " My username is $username."
        SupportContact.openSupport(context, message)
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

        var verifyTapped = false
        view.findViewById<View>(R.id.pendingSheetVerifyBtn).setOnClickListener {
            verifyTapped = true
            dialog.dismiss()
            openVerifyChat(activity)
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
