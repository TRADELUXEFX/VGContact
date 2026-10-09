package com.vgcontact.app

import android.app.Activity
import android.view.View
import android.widget.TextView
import kotlin.concurrent.thread

/** What the server says right now (get_notice). [paused] = Maintenance mode is on. */
data class Notice(val message: String, val paused: Boolean)

/**
 * Maintenance banner on Home (admin: Settings > Maintenance).
 *  - Notice written (Maintenance mode off)   -> amber card under the header.
 *  - Maintenance mode on                     -> full-screen "We're doing maintenance" cover on Home.
 * Nothing is deleted.
 * Server side: get_notice() in supabase/migrations/add_notice.sql.
 */
object MaintenanceNotice {

    /** True while Maintenance mode is on. HomeActivity.startSync reads it. */
    @Volatile var paused = false
        private set

    /**
     * Shows the last known state at once (before the server answers), so the full-screen
     * maintenance cover is already up on open and nothing can be tapped in the first seconds.
     */
    fun restore(activity: Activity) {
        val prefs = activity.getSharedPreferences("maintenance", android.content.Context.MODE_PRIVATE)
        paused = prefs.getBoolean("was_paused", false)
        showOverlay(activity, prefs.getString("last_message", "") ?: "")
    }

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
                prefs.edit().putBoolean("was_paused", paused)
                    .putString("last_message", notice?.message ?: "").apply()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    apply(activity, notice)
                    if (wasPaused && !paused) onEnded()
                }
            }
        }
    }

    private fun apply(activity: Activity, notice: Notice?) {
        showOverlay(activity, notice?.message ?: "")
        // Plain notice (Maintenance mode off): the amber card. While paused the full-screen cover is up.
        val banner = activity.findViewById<View>(R.id.maintenanceBanner) ?: return
        if (notice == null || notice.paused) {
            banner.visibility = View.GONE
        } else {
            activity.findViewById<TextView>(R.id.maintenanceTitle).text = "Notice"
            activity.findViewById<TextView>(R.id.maintenanceSub).text = notice.message
            banner.visibility = View.VISIBLE
        }
    }

    private fun showOverlay(activity: Activity, message: String) {
        val overlay = activity.findViewById<View>(R.id.maintenanceOverlay) ?: return
        activity.findViewById<TextView>(R.id.maintenanceOverlayMsg)?.text = message.trim()
        activity.findViewById<View>(R.id.maintenanceMsgCard)?.visibility =
            if (message.isBlank()) View.GONE else View.VISIBLE
        overlay.visibility = if (paused) View.VISIBLE else View.GONE
    }
}
