package com.vgcontact.app

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * Light or dark. Follows the phone's setting until the moon/sun button on Home is used;
 * after that the saved choice wins over the phone. Same behaviour as VG RESHARE.
 */
object AppTheme {
    private const val PREFS = "theme"
    private const val KEY_DARK = "dark"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the screen is dark right now: the saved choice, or else the phone's mode. */
    fun isDark(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.contains(KEY_DARK)) return p.getBoolean(KEY_DARK, false)
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    fun apply(ctx: Context) {
        val p = prefs(ctx)
        AppCompatDelegate.setDefaultNightMode(
            when {
                !p.contains(KEY_DARK) -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                p.getBoolean(KEY_DARK, false) -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }

    fun toggle(ctx: Context) {
        val dark = !isDark(ctx)
        prefs(ctx).edit().putBoolean(KEY_DARK, dark).apply()
        apply(ctx)
    }
}
