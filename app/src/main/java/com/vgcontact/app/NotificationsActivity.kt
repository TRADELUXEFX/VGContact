package com.vgcontact.app

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class NotificationsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)

        sessionManager = SessionManager(this)

        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            showState(loading = false, error = true)
            return
        }

        findViewById<View>(R.id.notifications_retry_btn).setOnClickListener {
            loadNotifications(userId)
        }

        loadNotifications(userId)
    }

    private fun loadNotifications(userId: String) {
        showState(loading = true)

        Thread {
            SupabaseClient.fetchNotifications(userId) { success, notifications ->
                runOnUiThread {
                    if (!success) {
                        // A failed load must never look like an empty inbox.
                        showState(error = true)
                        return@runOnUiThread
                    }
                    render(notifications)
                }

                // Only mark as read once the list actually loaded, so a
                // failed fetch doesn't silently clear the unread dot for
                // notifications the user never got to see.
                if (success) {
                    SupabaseClient.markNotificationsRead(userId) { _ -> }
                }
            }
        }.start()
    }

    // Exactly one of these is visible at a time.
    private fun showState(
        loading: Boolean = false,
        error: Boolean = false,
        empty: Boolean = false,
        list: Boolean = false
    ) {
        findViewById<View>(R.id.notifications_loading_state).visibility = if (loading) View.VISIBLE else View.GONE
        findViewById<View>(R.id.notifications_error_state).visibility = if (error) View.VISIBLE else View.GONE
        findViewById<View>(R.id.notifications_empty_state).visibility = if (empty) View.VISIBLE else View.GONE
        findViewById<View>(R.id.notifications_scroll).visibility = if (list) View.VISIBLE else View.GONE
    }

    private fun render(notifications: List<SupabaseClient.AppNotification>) {
        val container = findViewById<LinearLayout>(R.id.notification_list_container)

        if (notifications.isEmpty()) {
            showState(empty = true)
            return
        }

        showState(list = true)
        container.removeAllViews()

        notifications.forEach { notification ->
            val row = layoutInflater.inflate(R.layout.item_notification, container, false)
            row.findViewById<TextView>(R.id.notification_title).text = notification.title
            row.findViewById<TextView>(R.id.notification_body).text = notification.body
            row.findViewById<TextView>(R.id.notification_time).text = formatRelativeTime(notification.createdAt)
            row.findViewById<View>(R.id.notification_unread_dot).visibility =
                if (notification.isRead) View.GONE else View.VISIBLE
            if (!notification.action.isNullOrBlank() && notification.action != NotificationRouter.ACTION_HOME) {
                row.setOnClickListener {
                    NotificationRouter.run(this, notification.action, notification.target)
                }
            }
            container.addView(row)
        }
    }

    // created_at comes back from Supabase as an ISO-8601 timestamp
    // (e.g. "2026-09-27T10:15:00.123456+00:00"). Falls back to the raw
    // string if parsing fails rather than crashing the screen over it.
    private fun formatRelativeTime(isoTimestamp: String): String {
        return try {
            val instant = java.time.Instant.parse(
                if (isoTimestamp.contains("+") || isoTimestamp.endsWith("Z")) isoTimestamp
                else "${isoTimestamp}Z"
            )
            val minutes = java.time.Duration.between(instant, java.time.Instant.now()).toMinutes()
            when {
                minutes < 1 -> "Just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 1440 -> "${minutes / 60}h ago"
                else -> "${minutes / 1440}d ago"
            }
        } catch (e: Exception) {
            isoTimestamp
        }
    }
}
