package com.vgcontact.app

import android.content.Context

/**
 * Local sync switch. "Paused" is set by Profile > Delete My Contacts and
 * blocks every sync (button, first-run and background) until the user taps
 * Resume Syncing. Stored on the phone only; the phone's own address book
 * (the VGC tag on contact names) is what tells the app which contacts are
 * its own, so nothing else needs saving here.
 */
object SyncPrefs {
    private const val FILE = "vgc_sync"
    private const val KEY_PAUSED = "sync_paused"

    fun isPaused(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        val e = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PAUSED, paused)
        // Resuming starts a fresh watch: time spent paused must not look like a stall.
        if (!paused) e.putLong(KEY_WATCH_SINCE, System.currentTimeMillis())
        e.apply()
    }

    // How often the background sync runs (hours). Only 1, 6, 12 or 24.
    private const val KEY_INTERVAL = "sync_interval_hours"
    val INTERVAL_CHOICES = intArrayOf(1, 6, 12, 24)

    fun getIntervalHours(context: Context): Int {
        val h = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_INTERVAL, 24)
        return if (h in INTERVAL_CHOICES) h else 24
    }

    fun setIntervalHours(context: Context, hours: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_INTERVAL, hours).apply()
    }

    // When the last successful sync finished (ms since 1970). 0 = none yet.
    // Set by ContactSync.run as soon as the server answers.
    private const val KEY_LAST_SYNC = "last_sync_at"
    // When we started watching for a stalled sync. Used when there is no
    // successful sync on record yet (fresh install, just updated, just resumed),
    // so nobody is warned the moment they open the app.
    private const val KEY_WATCH_SINCE = "sync_watch_since"

    fun recordSyncSuccess(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
    }

    /**
     * True when the background sync looks stuck: no successful sync for longer
     * than [stallLimitMs]. Never true before that much time has passed since we
     * first started watching, so a new install or a fresh update is not flagged.
     */
    fun isSyncStalled(context: Context): Boolean {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var since = p.getLong(KEY_WATCH_SINCE, 0L)
        if (since == 0L) {
            since = now
            p.edit().putLong(KEY_WATCH_SINCE, now).apply()
        }
        val base = maxOf(p.getLong(KEY_LAST_SYNC, 0L), since)
        return now - base > stallLimitMs(context)
    }

    // Twice the chosen sync interval, but never less than 30 hours. Android may run
    // background jobs late to save battery, so a single late run must not count.
    private fun stallLimitMs(context: Context): Long {
        val hours = maxOf(30, 2 * getIntervalHours(context))
        return hours * 60L * 60L * 1000L
    }

    // When we last sent the user to their phone brand's autostart screen (ms). 0 = never.
    // Deliberately NOT cleared by clear(): it describes the phone, not the account.
    private const val KEY_OEM_PROMPTED_AT = "oem_autostart_prompted_at"

    fun getOemAutostartPromptedAt(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_OEM_PROMPTED_AT, 0L)

    fun markOemAutostartPrompted(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putLong(KEY_OEM_PROMPTED_AT, System.currentTimeMillis()).apply()
    }

    // Running total of contacts added today (resets by itself on a new day).
    private const val KEY_DAY = "added_day"
    private const val KEY_DAY_COUNT = "added_day_count"

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    fun getTodayAdded(context: Context): Int {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return if (p.getString(KEY_DAY, null) == today()) p.getInt(KEY_DAY_COUNT, 0) else 0
    }

    fun recordAdded(context: Context, count: Int) {
        if (count <= 0) return
        val total = getTodayAdded(context) + count
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_DAY, today()).putInt(KEY_DAY_COUNT, total).apply()
    }

    fun resetToday(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove(KEY_DAY).remove(KEY_DAY_COUNT).apply()
    }

    fun clear(context: Context) {
        // Keep the chosen sync frequency: the WorkManager schedule outlives a logout.
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove(KEY_PAUSED).remove(KEY_DAY).remove(KEY_DAY_COUNT)
            .remove(KEY_LAST_SYNC).remove(KEY_WATCH_SINCE).apply()
    }
}
