package com.vgcontact.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.concurrent.thread

/**
 * "New version ready" pop-up.
 *
 * You publish an update from the admin page's Updates tab (it fills four app_settings rows, see
 * supabase/migrations/add_app_update.sql). On every Home open the app asks get_app_update() with
 * its own build number (the number at the end of versionName, e.g. 954 in v1.0.954).
 *
 *  - A newer build exists: a dismissible sheet, shown every time the user opens the app
 *    (once per open; "Maybe later" hides it until the app is closed and opened again).
 *  - This build is below update_min_build: a sheet that can't be closed (no back, no tap outside,
 *    no Maybe later), shown on every Home open until the user updates.
 *
 * "Update now" opens the download link you set in the admin page. If nothing can open it, the
 * user lands in a WhatsApp chat with support instead of a dead end.
 * Debug / local builds have build number 0 and never see the pop-up.
 */
object AppUpdatePrompt {

    private const val PREFS = "vg_app_update"
    private const val KEY_PENDING_BUILD = "pending_build"
    private const val KEY_PENDING_URL = "pending_url"

    private var visible = false

    // True once the soft pop-up has been shown since the user last entered the app. It is
    // reset by VGApp when every screen of the app has been closed, so the pop-up comes back
    // each time the app is opened again, and "Maybe later" only hides it until then.
    @Volatile private var shownThisLaunch = false

    /** Called by VGApp when the app was fully closed: the pop-up may show again on the next open. */
    fun newLaunch() {
        shownThisLaunch = false
    }
    private var softDialog: BottomSheetDialog? = null

    /** Closes a dismissible update sheet if one is open (the pending sheet takes priority). */
    fun dismissSoft() {
        softDialog?.dismiss()
    }

    /**
     * True while a newer build is known and this app hasn't been updated yet. Remembered on the
     * phone, so the red banner shows instantly on Home (even offline) and survives "Maybe later".
     */
    fun hasPendingUpdate(context: Context): Boolean {
        val build = currentBuild()
        if (build <= 0) return false
        val pending = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PENDING_BUILD, 0)
        return pending > build
    }

    /** Opens the download link for the remembered update (used by the red banner). */
    fun openPending(activity: Activity) {
        val url = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING_URL, "") ?: ""
        openUpdate(activity, url)
    }

    /**
     * Opens the update download page from anywhere (a tapped "Update available" notification,
     * with the app open or closed). Uses the link remembered on the phone; if none is saved yet,
     * asks the server first. Does nothing but a short message when the app is already up to date.
     */
    fun openLatest(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_URL, "") ?: ""
        if (hasPendingUpdate(context) && saved.isNotBlank()) {
            launchUpdate(context, saved)
            return
        }
        val app = context.applicationContext
        thread {
            SupabaseClient.fetchAppUpdate(currentBuild()) { row ->
                val available = row != null &&
                    (row.optBoolean("update_available", false) || row.optBoolean("force_update", false))
                val url = row?.optString("download_url", "").orEmpty()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    when {
                        available && url.isNotBlank() -> {
                            rememberPending(app, row!!.optInt("latest_build", 0), url)
                            launchUpdate(context, url)
                        }
                        row == null -> android.widget.Toast.makeText(
                            app, "Couldn't reach the server. Try again.", android.widget.Toast.LENGTH_LONG
                        ).show()
                        else -> android.widget.Toast.makeText(
                            app, "You already have the latest version", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun launchUpdate(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            if (context is Activity) {
                SupportContact.openSupport(context, context.getString(R.string.update_msg_support))
            } else {
                android.widget.Toast.makeText(context, "Couldn't open the update page", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun rememberPending(context: Context, latest: Int, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PENDING_BUILD, latest).putString(KEY_PENDING_URL, url).apply()
    }

    private fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING_BUILD).remove(KEY_PENDING_URL).apply()
    }

    /** The build number of the running app (GitHub run number), 0 for local builds. */
    fun currentBuild(): Int = BuildConfig.VERSION_NAME.substringAfterLast('.').toIntOrNull() ?: 0

    /**
     * Asks the server and shows the sheet if needed. [allowSoft] is asked at the moment the sheet
     * would appear (after the network answer), and returns false while the pending verify sheet
     * is on screen, so the two never stack; the soft pop-up then simply waits for the next Home
     * open. A forced update is always shown.
     */
    fun check(activity: Activity, allowSoft: () -> Boolean, onBanner: (Boolean) -> Unit = {}) {
        val build = currentBuild()
        if (build <= 0 || visible) return
        thread {
            SupabaseClient.fetchAppUpdate(build) { row ->
                if (row == null) return@fetchAppUpdate
                val force = row.optBoolean("force_update", false)
                val available = row.optBoolean("update_available", false)
                val latest = row.optInt("latest_build", 0)
                val url = row.optString("download_url", "")
                val notes = row.optString("notes", "")
                if (!force && !available) {
                    // Up to date (or the update was unpublished): drop the red banner.
                    clearPending(activity)
                    activity.runOnUiThread { if (!activity.isFinishing) onBanner(false) }
                    return@fetchAppUpdate
                }
                rememberPending(activity, latest, url)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    onBanner(true)
                    if (force) {
                        show(activity, latest, url, notes, force = true)
                    } else if (allowSoft() && shouldShowSoft(activity, latest)) {
                        show(activity, latest, url, notes, force = false)
                    }
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun shouldShowSoft(context: Context, latest: Int): Boolean = !shownThisLaunch

    @Suppress("UNUSED_PARAMETER")
    private fun markShown(context: Context, latest: Int) {
        shownThisLaunch = true
    }

    private fun show(activity: Activity, latest: Int, url: String, notes: String, force: Boolean) {
        if (visible) return
        visible = true
        if (!force) markShown(activity, latest)

        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_app_update, null)
        dialog.setContentView(view)
        // Same trick as PendingPrompt: the layout paints its own rounded top, so the sheet
        // container must stay transparent.
        val clearSheetBackground = {
            (view.parent as? View)?.apply {
                background = null
                setBackgroundColor(Color.TRANSPARENT)
            }
            Unit
        }
        clearSheetBackground()
        dialog.setOnShowListener {
            clearSheetBackground()
            view.post { clearSheetBackground() }
        }
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        if (!force) softDialog = dialog
        dialog.setOnDismissListener {
            visible = false
            if (softDialog === dialog) softDialog = null
        }

        val versionText = "1.0.$latest"
        view.findViewById<TextView>(R.id.updateTitle)
            .setText(if (force) R.string.update_title_force else R.string.update_title)
        view.findViewById<TextView>(R.id.updateSubtitle).text = activity.getString(
            if (force) R.string.update_subtitle_force else R.string.update_subtitle, versionText
        )
        view.findViewById<TextView>(R.id.updateYourVersion).text =
            activity.getString(R.string.update_your_version, BuildConfig.VERSION_NAME)

        // No notes written in the admin page: hide the "What's new" card.
        val cleanNotes = notes.trim()
        view.findViewById<View>(R.id.updateNotesCard).visibility =
            if (cleanNotes.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.updateNotesBody).text = cleanNotes

        view.findViewById<View>(R.id.updateBtn).setOnClickListener {
            openUpdate(activity, url)
            // A soft pop-up closes once the user taps through; a forced one stays up.
            if (!force) dialog.dismiss()
        }

        if (force) {
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.behavior.isHideable = false
            dialog.behavior.isDraggable = false
            view.findViewById<View>(R.id.updateCloseBtn).visibility = View.GONE
            view.findViewById<View>(R.id.updateLaterBtn).visibility = View.GONE
        } else {
            view.findViewById<View>(R.id.updateCloseBtn).setOnClickListener { dialog.dismiss() }
            view.findViewById<View>(R.id.updateLaterBtn).setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    /** Handle app update from HomeActivity's bundle (called during initialization). */
    fun handleUpdate(activity: Activity, updateRow: org.json.JSONObject?, allowSoft: () -> Boolean, onBanner: (Boolean) -> Unit) {
        if (updateRow == null) {
            onBanner(false)
            return
        }
        val force = updateRow.optBoolean("force_update", false)
        val available = updateRow.optBoolean("update_available", false)
        val latest = updateRow.optInt("latest_build", 0)
        val url = updateRow.optString("download_url", "")
        val notes = updateRow.optString("notes", "")

        if (!force && !available) {
            clearPending(activity)
            onBanner(false)
            return
        }

        rememberPending(activity, latest, url)
        onBanner(true)
        if (force) {
            show(activity, latest, url, notes, force = true)
        } else if (allowSoft() && shouldShowSoft(activity, latest)) {
            show(activity, latest, url, notes, force = false)
        }
    }

    private fun openUpdate(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // No browser: don't leave the user stuck, hand them to support on WhatsApp.
            SupportContact.openSupport(activity, activity.getString(R.string.update_msg_support))
        }
    }
}
