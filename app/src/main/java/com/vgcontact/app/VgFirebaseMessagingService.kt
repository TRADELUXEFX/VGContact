package com.vgcontact.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives pushes sent by the send-push Edge Function (see
 * supabase/functions/send-push) and shows them as a system notification.
 *
 * Also keeps the user's row in `users.fcm_token` up to date: once at first
 * successful token fetch/refresh, and again any time FCM rotates the token
 * (onNewToken - happens occasionally, e.g. after an app reinstall or FCM-side
 * rotation). Both paths only actually write to Supabase if we know who the
 * user is (SessionManager has a saved user_id) - a token generated before
 * login/register just gets saved locally to send once login completes, since
 * there's nobody to attach it to yet.
 */
class VgFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "vgcontact_notifications"
        private const val PREFS_NAME = "vgkontact_session"
        private const val KEY_PENDING_TOKEN = "pending_fcm_token"

        /**
         * Call this right after login/register succeeds (once SessionManager
         * has a user_id) so a token that arrived before login isn't lost.
         */
        fun flushPendingTokenIfAny(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pending = prefs.getString(KEY_PENDING_TOKEN, null) ?: return
            val userId = SessionManager(context).getUserId() ?: return
            SupabaseClient.saveFcmToken(userId, pending) { success ->
                if (success) {
                    prefs.edit().remove(KEY_PENDING_TOKEN).apply()
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val session = SessionManager(this)
        val userId = session.getUserId()
        if (userId.isNullOrBlank()) {
            // Not logged in yet - stash it, flushPendingTokenIfAny() picks
            // it up once login/register completes.
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PENDING_TOKEN, token).apply()
            return
        }
        SupabaseClient.saveFcmToken(userId, token) { _ -> }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title
            ?: message.data["title"]
            ?: "VGContact"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: ""

        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VGContact notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New files, reposts and unlocks"
            }
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationId = System.currentTimeMillis().toInt()
        manager.notify(notificationId, notification)
    }
}
