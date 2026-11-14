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
}
