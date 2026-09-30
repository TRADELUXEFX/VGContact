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
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PAUSED, paused).apply()
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

    fun recordSyncSuccess(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
    }

    /**
     * True when a sync worked before but the last successful one is older than
     * [stallLimitMs]. If no sync has ever been recorded on this phone, it is
     * false, so a new install or a fresh update never shows the banner.
     */
    fun isSyncStalled(context: Context): Boolean {
        val last = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC, 0L)
        if (last == 0L) return false
        return System.currentTimeMillis() - last > stallLimitMs(context)
    }

    // Twice the chosen sync interval, but never less than 30 hours. Android may run
    // background jobs late to save battery, so a single late run must not count.
    private fun stallLimitMs(context: Context): Long {
        val hours = maxOf(30, 2 * getIntervalHours(context))
        return hours * 60L * 60L * 1000L
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
            .remove(KEY_LAST_SYNC).apply()
    }
}
