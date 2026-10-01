package com.vgcontact.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * First-open "Repost to get seen" sheet for pending users. Shown once per
 * install; after that the slim amber strip on Home is the only reminder.
 */
object PendingPrompt {
    private const val PREFS = "vg_pending_prompt"
    private const val KEY_SHOWN = "sheet_shown"

    fun wasShown(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, false)

    private fun markShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOWN, true).apply()
    }

    /**
     * [onRepost] runs when the user taps "Repost now" (caller opens the Repost screen).
     * [onLater] runs for every other way of closing it: Later, back, swipe down, tap outside.
     */
    fun show(activity: Activity, onRepost: () -> Unit, onLater: () -> Unit) {
        markShown(activity)
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_pending_verify, null)
        dialog.setContentView(view)

        var repostTapped = false
        view.findViewById<View>(R.id.pendingSheetRepostBtn).setOnClickListener {
            repostTapped = true
            dialog.dismiss()
            onRepost()
        }
        view.findViewById<View>(R.id.pendingSheetLaterBtn).setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener {
            // Let the layout's own rounded white background show instead of the default sheet surface.
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setOnDismissListener { if (!repostTapped) onLater() }
        dialog.show()
    }
}
