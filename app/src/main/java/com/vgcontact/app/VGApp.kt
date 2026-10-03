package com.vgcontact.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/** Application entry point. Registers app-wide UI that lives above every screen. */
class VGApp : Application() {
    companion object {
        @Volatile var instance: VGApp? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        FloatingContactHelper.register(this)
        // When the last screen of the app is closed (not just turned by a rotation), the
        // next open counts as a new entry and the update pop-up shows again.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var live = 0
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) { live++ }
            override fun onActivityDestroyed(activity: android.app.Activity) {
                live--
                if (live <= 0) {
                    live = 0
                    if (!activity.isChangingConfigurations) AppUpdatePrompt.newLaunch()
                }
            }
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
        })
        createNotificationChannel()
        DailySyncWorker.schedule(this)
    }

    // Firebase draws pushes itself when the app is closed/backgrounded, and
    // it can only use the channel named in the manifest if that channel
    // already exists. Creating it here (safe to repeat) guarantees it does.
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            VgFirebaseMessagingService.CHANNEL_ID,
            "VGContact notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "New files, reposts and viewers"
        }
        manager.createNotificationChannel(channel)
    }
}
