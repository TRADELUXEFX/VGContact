package com.vgcontact.app

import android.app.Activity
import kotlin.concurrent.thread

/**
 * Notice from the admin page (Settings > "Notice to users"). When the admin writes a message,
 * every user sees it once as a pop-up when they open the app, and again only if the text changes
 * or the app is fully closed and reopened. An empty message shows nothing.
 * Server side: get_notice() in supabase/migrations/add_notice.sql.
 */
object MaintenanceNotice {

    @Volatile private var shownMessage: String? = null

    fun check(activity: Activity) {
        thread {
            SupabaseClient.fetchNotice { message ->
                if (message == null || message == shownMessage) return@fetchNotice
                shownMessage = message
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    VgDialog.show(
                        activity,
                        VgDialog.Tone.INFO,
                        "Notice",
                        message,
                        VgDialog.Action("OK"),
                        secondary = null
                    )
                }
            }
        }
    }
}
