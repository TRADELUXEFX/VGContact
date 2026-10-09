package com.vgcontact.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

/** What the server says right now (get_notice). [paused] = Maintenance mode is on. */
data class Notice(val message: String, val paused: Boolean)

/**
 * Maintenance banner on Home (admin: Settings > Maintenance).
 *  - Notice written, or Maintenance mode on  -> amber card under the header.
 *  - Maintenance mode on                     -> the Sync button shows "Sync paused".
 * The app keeps working. Nothing is blocked and nothing is deleted.
 * Server side: get_notice() in supabase/migrations/add_notice.sql.
 */
object MaintenanceNotice {

    /** True while Maintenance mode is on. HomeActivity.startSync reads it. */
    @Volatile var paused = false
        private set

    /**
     * [onEnded] runs once when Maintenance mode was on the last time this phone looked and is
     * off now, so HomeActivity can start the sync right away instead of waiting for tomorrow.
     */
    fun refresh(activity: Activity, onEnded: () -> Unit = {}) {
        thread {
            SupabaseClient.fetchNotice { notice, failed ->
                if (failed) return@fetchNotice // offline: leave everything as it was
                val prefs = activity.getSharedPreferences("maintenance", android.content.Context.MODE_PRIVATE)
                val wasPaused = prefs.getBoolean("was_paused", false)
                paused = notice?.paused == true
                prefs.edit().putBoolean("was_paused", paused).apply()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    apply(activity, notice)
                    if (wasPaused && !paused) onEnded()
                }
            }
        }
    }

    private fun apply(activity: Activity, notice: Notice?) {
        val banner = activity.findViewById<View>(R.id.maintenanceBanner) ?: return
        if (notice == null) {
            banner.visibility = View.GONE
        } else {
            activity.findViewById<TextView>(R.id.maintenanceTitle).text =
                if (notice.paused) "We're doing maintenance" else "Notice"
            activity.findViewById<TextView>(R.id.maintenanceSub).text = notice.message
            banner.visibility = View.VISIBLE
        }
        val btn = activity.findViewById<MaterialButton>(R.id.syncContactsBtn) ?: return
        if (paused) {
            btn.text = "Sync paused"
            btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F0F2F5"))
            btn.setTextColor(Color.parseColor("#A8B0AC"))
            btn.iconTint = ColorStateList.valueOf(Color.parseColor("#A8B0AC"))
        } else if (btn.text.toString() == "Sync paused") {
            btn.setText(R.string.btn_sync_contacts)
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.vg_green))
            btn.setTextColor(Color.WHITE)
            btn.iconTint = ColorStateList.valueOf(Color.WHITE)
        }
    }
}
