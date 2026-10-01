package com.vgcontact.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import androidx.appcompat.app.AlertDialog

/**
 * Rounds the corners of an AlertDialog by replacing its window background.
 * Done in code because the theme's shape setting does not reliably change
 * the corner size. Call right after show().
 */
object RoundedDialog {
    private const val RADIUS_DP = 28
    private const val INSET_DP = 24

    fun style(dialog: AlertDialog) {
        val d = dialog.context.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = RADIUS_DP * d
        }
        val inset = (INSET_DP * d).toInt()
        dialog.window?.setBackgroundDrawable(InsetDrawable(bg, inset, inset, inset, inset))
    }
}
