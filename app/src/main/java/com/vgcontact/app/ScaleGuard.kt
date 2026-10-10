package com.vgcontact.app

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics

/**
 * The ONE place that decides how much the phone's "Font size" and "Display size" settings
 * can stretch this app. Every screen goes through it (see BaseActivity), so a screen can
 * never be rendered at a size the layouts were not built for, on any phone.
 *
 * The layouts are built and checked for this range (see tools/check_layouts.py).
 * To allow more or less, change the two numbers below; nothing else needs to change.
 *
 *  - MAX_FONT_SCALE: text is never scaled above this. 1.0 = normal, 1.3 = "Largest" on most
 *    phones. Phones set to 1.5 or 2.0 get 1.3.
 *  - MAX_DISPLAY_DENSITY_RATIO: "Display size" can make everything look bigger, which leaves
 *    less room (a 411dp wide screen can drop to about 300dp). 1.0 means the app never
 *    grows beyond the phone's default display size. Smaller display sizes are respected.
 */
object ScaleGuard {
    const val MAX_FONT_SCALE = 1.3f
    const val MAX_DISPLAY_DENSITY_RATIO = 1.0f

    fun wrap(base: Context): Context {
        val cfg = Configuration(base.resources.configuration)
        var changed = false

        if (cfg.fontScale > MAX_FONT_SCALE) {
            cfg.fontScale = MAX_FONT_SCALE
            changed = true
        }

        val maxDensity = (DisplayMetrics.DENSITY_DEVICE_STABLE * MAX_DISPLAY_DENSITY_RATIO).toInt()
        val current = cfg.densityDpi
        if (current > maxDensity && maxDensity > 0) {
            // Keep the dp-based sizes in step with the new density.
            val ratio = current.toFloat() / maxDensity
            cfg.densityDpi = maxDensity
            if (cfg.screenWidthDp > 0) cfg.screenWidthDp = (cfg.screenWidthDp * ratio).toInt()
            if (cfg.screenHeightDp > 0) cfg.screenHeightDp = (cfg.screenHeightDp * ratio).toInt()
            if (cfg.smallestScreenWidthDp > 0) {
                cfg.smallestScreenWidthDp = (cfg.smallestScreenWidthDp * ratio).toInt()
            }
            changed = true
        }

        return if (changed) base.createConfigurationContext(cfg) else base
    }
}
