package com.vgcontact.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Single place that decides what a notification tap does.
 *
 * The server sets `notifications.action` (see
 * supabase/migrations/add_notification_actions_and_triggers.sql). It reaches
 * the app three ways and all three end up here:
 *   1. Push tapped while app is closed/background -> Firebase launches the
 *      launcher activity with the FCM data as intent extras.
 *   2. Push shown by us while app is in foreground -> VgFirebaseMessagingService
 *      puts the same keys on the PendingIntent.
 *   3. Row tapped in the in-app Notifications feed.
 */
object NotificationRouter {

    const val EXTRA_ACTION = "action"
    const val EXTRA_TARGET = "target"
    const val EXTRA_NOTIFICATION_ID = "notification_id"   // same key the send-push function uses

    const val ACTION_WHATSAPP_REPOST = "open_whatsapp_repost"
    const val ACTION_REPOST = "open_repost"
    const val ACTION_DOWNLOADS = "open_downloads"
    const val ACTION_HOME = "open_home"
    const val ACTION_FIX_SYNC = "fix_sync"   // "your sync stopped" nudge from the server

    /** Keys to copy onto an Intent so they survive a hop to another activity. */
    fun putExtras(intent: Intent, action: String?, target: String?, notificationId: String? = null): Intent {
        if (!action.isNullOrBlank()) intent.putExtra(EXTRA_ACTION, action)
        if (!target.isNullOrBlank()) intent.putExtra(EXTRA_TARGET, target)
        if (!notificationId.isNullOrBlank()) intent.putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        return intent
    }

    /** Copies notification extras from one intent to another (e.g. Register -> Home). */
    fun forward(from: Intent?, to: Intent): Intent {
        val extras: Bundle = from?.extras ?: return to
        return putExtras(
            to,
            extras.getString(EXTRA_ACTION),
            extras.getString(EXTRA_TARGET),
            extras.getString(EXTRA_NOTIFICATION_ID)
        )
    }

    /**
     * Runs the action carried by [intent], if any, then strips it so a
     * rotation / re-create can't fire it a second time.
     * Returns true if an action was handled.
     */
    fun handle(context: Context, intent: Intent?): Boolean {
        val action = intent?.getStringExtra(EXTRA_ACTION)
        val target = intent?.getStringExtra(EXTRA_TARGET)
        val notificationId = intent?.getStringExtra(EXTRA_NOTIFICATION_ID)
        intent?.removeExtra(EXTRA_ACTION)
        intent?.removeExtra(EXTRA_TARGET)
        intent?.removeExtra(EXTRA_NOTIFICATION_ID)

        // The user tapped a push: count it as opened (and therefore delivered).
        if (!notificationId.isNullOrBlank()) {
            val userId = SessionManager(context).getUserId()
            if (!userId.isNullOrBlank()) SupabaseClient.recordNotificationOpened(userId, notificationId)
        }
        return run(context, action, target)
    }

    fun run(context: Context, action: String?, target: String?): Boolean {
        // Pending users are locked out of Repost/WhatsApp-repost actions: show the verify sheet.
        if ((action == ACTION_WHATSAPP_REPOST || action == ACTION_REPOST) && PendingPrompt.isPending(context)) {
            if (context is android.app.Activity) PendingPrompt.showGate(context)
            return true
        }
        return when (action) {
            ACTION_WHATSAPP_REPOST -> {
                openAdminWhatsApp(context)
                // Deliberately does NOT log a repost: the user hasn't reposted yet.
                // They log it with the button on the Repost screen afterwards.
                true
            }
            ACTION_REPOST -> {
                context.startActivity(Intent(context, RepostActivity::class.java))
                true
            }
            ACTION_FIX_SYNC -> {
                if (context is HomeActivity) {
                    context.showSyncHelp()
                } else {
                    // From another screen (e.g. the Notifications feed): go to Home
                    // and let Home run the same action.
                    val home = Intent(context, HomeActivity::class.java)
                    putExtras(home, ACTION_FIX_SYNC, null)
                    if (context !is android.app.Activity) home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(home)
                }
                true
            }
            ACTION_DOWNLOADS -> {
                context.startActivity(Intent(context, HomeActivity::class.java))
                true
            }
            else -> false   // open_home / null / unknown: just stay on Home
        }
    }

    private fun openAdminWhatsApp(context: Context) {
        try {
            val message = Uri.encode("Hi VGContact, I want to repost today's status")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/${SupportContact.WHATSAPP}?text=$message")
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
