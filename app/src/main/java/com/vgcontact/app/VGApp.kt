package com.vgcontact.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/** Application entry point. Registers app-wide UI that lives above every screen. */
class VGApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FloatingContactHelper.register(this)
        createNotificationChannel()
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
            description = "New files, reposts and unlocks"
        }
        manager.createNotificationChannel(channel)
    }
}
