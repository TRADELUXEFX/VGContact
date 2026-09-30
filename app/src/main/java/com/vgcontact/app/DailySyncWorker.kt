package com.vgcontact.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background contact sync, about once every 24 hours. Runs the same
 * ContactSync as the Sync Contacts button (the server decides which numbers
 * to save; nothing on the phone is ever deleted or renamed). Skips quietly
 * when the user is logged out, contacts permission is off, or the phone is
 * offline. Android may run it a little late to save battery.
 */
class DailySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val session = SessionManager(applicationContext)
        val userId = session.getUserId()
        if (!session.isLoggedIn() || userId.isNullOrBlank()) return Result.success()
        if (!ContactSync.hasPermission(applicationContext)) return Result.success()

        val result = ContactSync.run(applicationContext, userId)
        return if (result.error == ContactSync.ERR_NO_INTERNET || result.error == ContactSync.ERR_FETCH) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "vgcontact_daily_contact_sync"

        /** Safe to call on every app start: an existing schedule is kept. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailySyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
