package com.vgcontact.app

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.concurrent.thread

/** What the server says right now (get_notice). [paused] = Maintenance mode is on. */
data class Notice(val message: String, val paused: Boolean)

/**
 * Maintenance on Home (admin: Settings > Maintenance mode).
 * Maintenance mode on  -> full-screen "We're doing maintenance" cover on Home (the notice text, if
 *                         any, is shown inside it). The floating chat button is hidden meanwhile.
 * Maintenance mode off -> nothing at all. There is no amber card or pop-up any more.
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
                    showOverlay(activity, notice?.message ?: "")
                    if (wasPaused && !paused) onEnded()
                }
            }
        }
    }

    private fun showOverlay(activity: Activity, message: String) {
        val overlay = activity.findViewById<View>(R.id.maintenanceOverlay) ?: return
        activity.findViewById<TextView>(R.id.maintenanceOverlayMsg)?.text = message.trim()
        activity.findViewById<View>(R.id.maintenanceMsgCard)?.visibility =
            if (message.isBlank()) View.GONE else View.VISIBLE
        overlay.visibility = if (paused) View.VISIBLE else View.GONE
        hideChatButton(activity)
        tintSystemBars(activity)
    }

    /**
     * The floating chat button lives in the activity's content root, next to Home's own layout, so
     * the cover's elevation cannot lift the cover above it. Hide it while the cover is up.
     */
    private fun hideChatButton(activity: Activity) {
        activity.findViewById<ViewGroup>(android.R.id.content)
            ?.findViewWithTag<View>(FloatingContactHelper.FAB_TAG)
            ?.visibility = if (paused) View.GONE else View.VISIBLE
    }

    private var originalNavColor: Int? = null

    /** Paint the phone's bottom system bar green while the cover is up, so it is truly full screen. */
    private fun tintSystemBars(activity: Activity) {
        val window = activity.window
        if (originalNavColor == null) originalNavColor = window.navigationBarColor
        window.navigationBarColor = if (paused)
            androidx.core.content.ContextCompat.getColor(activity, R.color.vg_green)
        else originalNavColor!!
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = false
    }
}
