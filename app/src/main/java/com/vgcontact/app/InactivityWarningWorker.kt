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
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Reminds the user to open the app before the server drops them from other
 * people's contact lists (see add_inactivity.sql).
 *
 * A one-off timer is restarted every time contacts sync successfully (button,
 * first run or background, via [reschedule]). Only syncing counts, not opening
 * the app. It fires after 5 days with no successful sync.
 */
class InactivityWarningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val session = SessionManager(ctx)
        if (!session.isLoggedIn() || BanPrefs.isBanned(ctx) || SyncPrefs.isPaused(ctx)) return Result.success()
        show(ctx)
        return Result.success()
    }

    private fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val open = Intent(context, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val message = "Your contacts haven't synced in 5 days. Open the app and sync now so you don't lose your viewers."
            val n = NotificationCompat.Builder(context, VgFirebaseMessagingService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Don't lose your viewers")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, n)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val WORK_NAME = "vgcontact_inactivity_warning"
        private const val NOTIFICATION_ID = 7103
        private const val DAYS = 5L

        /** The user was just seen: clear any warning and restart the 5-day timer. */
        fun reschedule(context: Context) {
            val app = context.applicationContext
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            val request = OneTimeWorkRequestBuilder<InactivityWarningWorker>()
                .setInitialDelay(DAYS, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
