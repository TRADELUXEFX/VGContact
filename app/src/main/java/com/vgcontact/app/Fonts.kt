package com.vgcontact.app

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * Poppins weights for text that is built in code (XML screens use
 * android:fontFamily). One weight per role, same as the type scale in
 * res/values/styles.xml:
 *   light 300, regular 400, medium 500, semibold 600, bold 700, extrabold 800.
 */
object Fonts {
    /**
     * One pass over a whole screen, the same approach VG RESHARE uses.
     * Each TextView gets its weight from android:tag (medium, semibold, bold, extrabold).
     * A TextView without a tag keeps the font its style already set (textAppearance).
     * Every TextView also gets includeFontPadding=false, so Poppins text sits centred in
     * buttons, pills and chips.
     */
    fun apply(root: View) {
        if (root is TextView) {
            (root.tag as? String)?.let { weight ->
                val res = when (weight) {
                    "medium" -> R.font.poppins_medium
                    "semibold" -> R.font.poppins_semibold
                    "bold" -> R.font.poppins_bold
                    "extrabold" -> R.font.poppins_extrabold
                    else -> null
                }
                if (res != null) root.typeface = load(root.context, res)
            }
            root.includeFontPadding = false
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) apply(root.getChildAt(i))
        }
    }

    private fun load(ctx: Context, res: Int): Typeface? =
        try { ResourcesCompat.getFont(ctx, res) } catch (e: Exception) { null }

    fun light(ctx: Context) = load(ctx, R.font.poppins_light)
    fun regular(ctx: Context) = load(ctx, R.font.poppins)
    fun medium(ctx: Context) = load(ctx, R.font.poppins_medium)
    fun semibold(ctx: Context) = load(ctx, R.font.poppins_semibold)
    fun bold(ctx: Context) = load(ctx, R.font.poppins_bold)
    fun extrabold(ctx: Context) = load(ctx, R.font.poppins_extrabold)
}
