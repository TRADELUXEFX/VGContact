package com.vgcontact.app

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class NotificationsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)

        sessionManager = SessionManager(this)

        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            return
        }

        Thread {
            SupabaseClient.fetchNotifications(userId) { success, notifications ->
                runOnUiThread {
                    if (!success) {
                        Toast.makeText(this, "Couldn't load notifications. Check your connection.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    render(notifications)
                }
            }

            // Mark everything the user can currently see as read. Fire
            // right after the fetch above, on the same background thread -
            // the already-fetched list still shows this visit's unread
            // dots, and the bell badge just reflects the new (all-read)
            // state next time Home loads.
            SupabaseClient.markNotificationsRead(userId) { _ -> }
        }.start()
    }

    private fun render(notifications: List<SupabaseClient.AppNotification>) {
        val scroll = findViewById<ScrollView>(R.id.notifications_scroll)
        val emptyState = findViewById<LinearLayout>(R.id.notifications_empty_state)
        val container = findViewById<LinearLayout>(R.id.notification_list_container)

        if (notifications.isEmpty()) {
            scroll.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            return
        }

        scroll.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        container.removeAllViews()

        notifications.forEach { notification ->
            val row = layoutInflater.inflate(R.layout.item_notification, container, false)
            row.findViewById<TextView>(R.id.notification_title).text = notification.title
            row.findViewById<TextView>(R.id.notification_body).text = notification.body
            row.findViewById<TextView>(R.id.notification_time).text = formatRelativeTime(notification.createdAt)
            row.findViewById<View>(R.id.notification_unread_dot).visibility =
                if (notification.isRead) View.GONE else View.VISIBLE
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
