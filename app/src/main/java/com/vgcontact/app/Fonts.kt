package com.vgcontact.app

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Poppins weights for text that is built in code (XML screens use
 * android:fontFamily). One weight per role, same as the type scale in
 * res/values/styles.xml:
 *   light 300, regular 400, medium 500, semibold 600, bold 700, extrabold 800.
 */
object Fonts {
    private fun load(ctx: Context, res: Int): Typeface? =
        try { ResourcesCompat.getFont(ctx, res) } catch (e: Exception) { null }

    fun light(ctx: Context) = load(ctx, R.font.poppins_light)
    fun regular(ctx: Context) = load(ctx, R.font.poppins)
    fun medium(ctx: Context) = load(ctx, R.font.poppins_medium)
    fun semibold(ctx: Context) = load(ctx, R.font.poppins_semibold)
    fun bold(ctx: Context) = load(ctx, R.font.poppins_bold)
    fun extrabold(ctx: Context) = load(ctx, R.font.poppins_extrabold)
}
