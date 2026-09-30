package com.vgcontact.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background contact sync, every 24 hours by default (Profile > Sync every... can change it to 1, 6 or 12). Runs the same
 * ContactSync as the Sync Contacts button (the server decides which numbers
 * to save; contacts that left the list are removed, nothing is renamed).
 *
 * Skips quietly when the user is logged out, banned, or paused syncing
 * (Delete My Contacts). If contacts permission has been switched off it
 * shows one reminder notification. If the phone is offline or the server
 * fails, it queues a one-time retry that fires as soon as the network is
 * back (no waiting a full day). If new contacts were added it says so in a
 * notification, e.g. "3 contacts synced today at 10:30". If data was off, the
 * sync runs (and the notification appears) as soon as data comes back on. Android may run it a little late to save battery.
 */
class DailySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val session = SessionManager(ctx)
        val userId = session.getUserId()
        if (!session.isLoggedIn() || userId.isNullOrBlank()) return Result.success()
        if (BanPrefs.isBanned(ctx)) return Result.success()
        if (SyncPrefs.isPaused(ctx)) return Result.success()

        if (!ContactSync.hasPermission(ctx)) {
            notify(ctx, 7101, "Contacts permission is off",
                "Turn it on so your new viewers can be saved to your phone.")
            return Result.success()
        }

        val result = ContactSync.run(ctx, userId)
        if (result.error == ContactSync.ERR_NO_INTERNET || result.error == ContactSync.ERR_FETCH) {
            scheduleRetryOnReconnect(ctx)
            return Result.success()
        }
        if (result.added > 0) {
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            val total = SyncPrefs.getTodayAdded(ctx)
            notify(ctx, 7102, "Contacts synced",
                if (total == 1) "1 contact synced today at $time"
                else "$total contacts synced today at $time")
        }
        return Result.success()
    }

    // One notification, same channel as pushes; tapping opens the app.
    // Silent when the notification permission is off.
    private fun notify(context: Context, id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val open = Intent(context, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, VgFirebaseMessagingService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        } catch (_: Exception) {
        }
    }

    // One-time run held by Android until the phone has a connection. Same
    // worker, so it gets the same checks. REPLACE means repeated failures
    // refresh one pending job instead of stacking several.
    private fun scheduleRetryOnReconnect(context: Context) {
        val request = OneTimeWorkRequestBuilder<DailySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, request
        )
    }

    companion object {
        private const val WORK_NAME = "vgcontact_daily_contact_sync"
        private const val RETRY_WORK_NAME = "vgcontact_contact_sync_retry"

        /** Safe to call on every app start: an existing schedule is kept. */
        fun schedule(context: Context) = enqueue(context, ExistingPeriodicWorkPolicy.KEEP)

        /** Called when the user picks a new frequency: replaces the schedule. */
        fun reschedule(context: Context) = enqueue(context, ExistingPeriodicWorkPolicy.UPDATE)

        private fun enqueue(context: Context, policy: ExistingPeriodicWorkPolicy) {
            val request = PeriodicWorkRequestBuilder<DailySyncWorker>(
                SyncPrefs.getIntervalHours(context).toLong(), TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        }
    }
}
