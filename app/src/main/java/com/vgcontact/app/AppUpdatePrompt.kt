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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * "New version ready" pop-up.
 *
 * You publish an update from the admin page's Updates tab (it fills four app_settings rows, see
 * supabase/migrations/add_app_update.sql). On every Home open the app asks get_app_update() with
 * its own build number (the number at the end of versionName, e.g. 954 in v1.0.954).
 *
 *  - A newer build exists: a dismissible sheet, shown at most once a day per build.
 *  - This build is below update_min_build: a sheet that can't be closed (no back, no tap outside,
 *    no Maybe later), shown on every Home open until the user updates.
 *
 * "Update now" opens the download link you set in the admin page. If nothing can open it, the
 * user lands in a WhatsApp chat with support instead of a dead end.
 * Debug / local builds have build number 0 and never see the pop-up.
 */
object AppUpdatePrompt {

    private const val PREFS = "vg_app_update"
    private const val KEY_LAST_BUILD = "last_shown_build"
    private const val KEY_LAST_DAY = "last_shown_day"
    private const val KEY_PENDING_BUILD = "pending_build"
    private const val KEY_PENDING_URL = "pending_url"

    private var visible = false
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

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun shouldShowSoft(context: Context, latest: Int): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !(p.getInt(KEY_LAST_BUILD, 0) == latest && p.getString(KEY_LAST_DAY, "") == today())
    }

    private fun markShown(context: Context, latest: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LAST_BUILD, latest).putString(KEY_LAST_DAY, today()).apply()
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

    private fun openUpdate(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // No browser: don't leave the user stuck, hand them to support on WhatsApp.
            SupportContact.openSupport(activity, activity.getString(R.string.update_msg_support))
        }
    }
}
