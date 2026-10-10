package com.vgcontact.app

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class NotificationsActivity : BaseActivity() {

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
            // Unread rows get a different colour (light green with a green border), read rows
            // stay a plain white card. Padding is kept because setting a background can reset it.
            val l = row.paddingLeft; val t = row.paddingTop; val r = row.paddingRight; val b = row.paddingBottom
            row.setBackgroundResource(
                if (notification.isRead) R.drawable.card_background else R.drawable.notification_unread_background
            )
            row.setPadding(l, t, r, b)
            val rowAction = NotificationRouter.resolveAction(notification.title, notification.action)
            if (!rowAction.isNullOrBlank() && rowAction != NotificationRouter.ACTION_HOME) {
                row.setOnClickListener {
                    NotificationRouter.run(this, rowAction, notification.target)
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
            // Normalise to "yyyy-MM-ddTHH:mm:ss+00:00": drop fractional
            // seconds, turn Z into +00:00, assume UTC if no offset.
            var ts = isoTimestamp.replace(Regex("\\.\\d+"), "").replace("Z", "+00:00")
            if (!Regex("[+-]\\d{2}:\\d{2}$").containsMatchIn(ts)) ts += "+00:00"
            val parsed = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(ts)
                ?: return isoTimestamp
            val minutes = (System.currentTimeMillis() - parsed.time) / 60000
            when {
                minutes < 1 -> "Just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 1440 -> "${minutes / 60}h ago"
                else -> "${minutes / 1440}d ago"
            }
        } catch (e: Throwable) {
            isoTimestamp
        }
    }
}
