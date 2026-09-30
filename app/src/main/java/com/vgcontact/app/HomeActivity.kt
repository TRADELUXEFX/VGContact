package com.vgcontact.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton

/**
 * Home. Shows the viewers block (Free / Extra / Referral) and two
 * buttons: Sync contacts (put the user's group contacts on the phone)
 * and Buy status viewers (opens the buy screen).
 */
class HomeActivity : AppCompatActivity() {

    // A push was tapped while Home is already open.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationRouter.handle(this, intent)
    }

    private lateinit var sessionManager: SessionManager
    private var contactUsFab: View? = null
    private var missingPermissions: List<String> = emptyList()
    private var isSyncing = false

    private lateinit var syncBtn: MaterialButton

    companion object {
        private const val NOTIFICATIONS_REQUEST_CODE = 301
        private const val CONTACTS_REQUEST_CODE = 302
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BanPrefs.isBanned(this)) {
            BannedHandler.showFrom(this)
            return
        }
        setContentView(R.layout.activity_home)

        sessionManager = SessionManager(this)
        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        if (savedInstanceState == null) NotificationRouter.handle(this, intent)

        findViewById<TextView>(R.id.headerUsernameText).text =
            sessionManager.getUsername() ?: "VGContact User"
        findViewById<ImageView>(R.id.headerProfileIcon).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<ImageView>(R.id.headerBellIcon).setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.permissionBanner).setOnClickListener { fixPermissions() }

        syncBtn = findViewById(R.id.syncContactsBtn)
        syncBtn.setOnClickListener { startSync() }

        findViewById<Button>(R.id.buyViewersBtn).setOnClickListener {
            SupportContact.openBuyViewers(this)
        }

        findViewById<Button>(R.id.join_community_btn).setOnClickListener {
            CommunityLink.open(this)
        }
        CommunityLink.refresh(this)

        BottomNavHelper.setup(this, BottomNavHelper.Tab.HOME)
        contactUsFab = FloatingContactHelper.attach(this)

        findViewById<View>(R.id.home_content_scroll).post { showHomeTourIfNeeded() }
    }

    private fun showHomeTourIfNeeded() {
        if (CoachMarkOverlay.isTourDone(this)) return

        val scroller = findViewById<NestedScrollView>(R.id.home_content_scroll)
        scroller.scrollTo(0, 0)

        val steps = mutableListOf(
            CoachMarkOverlay.Step(
                findViewById(R.id.home_contacts_card),
                "Your viewers",
                "Free viewers come with your group. Extra viewers come from reposts and purchases.",
                dockAtBottom = true
            ),
            CoachMarkOverlay.Step(
                findViewById(R.id.syncContactsBtn),
                "Sync contacts",
                "Tap to add your group's contacts to your phone.",
                dockAtBottom = true,
                scrollParent = scroller
            ),
            CoachMarkOverlay.Step(
                findViewById(R.id.buyViewersBtn),
                "Buy status viewers",
                "Want more people seeing your status? Buy a pack here.",
                dockAtBottom = true,
                scrollParent = scroller
            ),
            CoachMarkOverlay.Step(
                findViewById(R.id.navRepostTab),
                "Repost",
                "Repost the admin's status here to get more viewers."
            ),
            CoachMarkOverlay.Step(
                findViewById(R.id.navReferralTab),
                "Referral",
                "Share your link. People who join with it become your referral viewers."
            )
        )
        contactUsFab?.let { fab ->
            steps.add(
                CoachMarkOverlay.Step(
                    fab,
                    "Need help?",
                    "Tap the chat button anytime to contact us if you run into issues."
                )
            )
        }
        CoachMarkOverlay.showIfNeeded(this, steps)
    }

    // ---------------- data ----------------

    private fun loadHome() {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return
        Thread {
            SupabaseClient.fetchHome(userId) { ok, home ->
                runOnUiThread {
                    if (ok && home != null) showHome(home)
                }
            }
        }.start()
    }

    private fun showHome(h: SupabaseClient.HomeData) {
        findViewById<TextView>(R.id.statusBadgeText).text = h.status.uppercase()

        findViewById<TextView>(R.id.freeViewersCurrentText).text = h.freeCurrent.toString()
        findViewById<TextView>(R.id.freeViewersMaxText).text = "/${h.freeMax}"
        val meter = findViewById<ProgressBar>(R.id.freeViewersMeter)
        meter.progress = if (h.freeMax > 0) (h.freeCurrent * 100 / h.freeMax).coerceIn(0, 100) else 0

        findViewById<TextView>(R.id.extraViewersCurrentText).text = h.extraCurrent.toString()
        findViewById<TextView>(R.id.extraViewersMaxText).text = "/${h.extraMax}"

        findViewById<TextView>(R.id.referralViewersCountText).text = h.referralCount.toString()
    }

    private fun refreshUnreadBadge() {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return
        val dot = findViewById<View>(R.id.headerBellUnreadDot)
        Thread {
            SupabaseClient.fetchNotifications(userId) { success, notifications ->
                runOnUiThread {
                    if (success) dot.visibility =
                        if (notifications.any { !it.isRead }) View.VISIBLE else View.GONE
                }
            }
        }.start()
    }

    // ---------------- sync ----------------

    private fun startSync() {
        if (isSyncing) return
        if (!ContactSync.hasPermission(this)) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.WRITE_CONTACTS
                ),
                CONTACTS_REQUEST_CODE
            )
            return
        }
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return

        isSyncing = true
        syncBtn.isEnabled = false
        val original = syncBtn.text
        syncBtn.text = "Syncing..."
        Thread {
            val result = ContactSync.run(this, userId)
            runOnUiThread {
                isSyncing = false
                syncBtn.isEnabled = true
                syncBtn.text = original
                val message = when {
                    result.error == ContactSync.ERR_NO_INTERNET -> "No internet connection"
                    result.error == ContactSync.ERR_FETCH -> "Couldn't reach the server. Try again."
                    result.failed > 0 -> "${result.added} added, ${result.failed} failed"
                    result.added == 0 -> "Your contacts are up to date"
                    result.added == 1 -> "1 new contact added"
                    else -> "${result.added} new contacts added"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                loadHome()
            }
        }.start()
    }

    // ---------------- lifecycle ----------------

    override fun onResume() {
        super.onResume()
        if (BanPrefs.isBanned(this)) {
            BannedHandler.showFrom(this)
            return
        }
        loadHome()
        refreshUnreadBadge()
        updatePermissionBanner()
        BannedHandler.checkWithServer(this)
    }

    // ---------------- notification permission banner ----------------

    private fun reportNotificationsEnabledIfChanged(enabled: Boolean) {
        val userId = sessionManager.getUserId() ?: return
        val prefs = getSharedPreferences("vgkontact_session", MODE_PRIVATE)
        val value = if (enabled) 1 else 0
        if (prefs.getInt("reported_notif_enabled", -1) == value) return
        SupabaseClient.reportNotificationsEnabled(userId, enabled) { ok ->
            if (ok) prefs.edit().putInt("reported_notif_enabled", value).apply()
        }
    }

    private fun updatePermissionBanner() {
        val banner = findViewById<LinearLayout>(R.id.permissionBanner)
        val bannerText = findViewById<TextView>(R.id.permissionBannerText)

        val notificationsOff =
            !androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        reportNotificationsEnabledIfChanged(!notificationsOff)

        val missing = mutableListOf<String>()
        if (notificationsOff) missing.add("Notifications")
        missingPermissions = missing

        if (missing.isEmpty()) {
            banner.visibility = View.GONE
        } else {
            bannerText.text = "Notifications are off - you won't get repost alerts"
            banner.visibility = View.VISIBLE
        }
    }

    private fun fixPermissions() {
        if ("Notifications" !in missingPermissions) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATIONS_REQUEST_CODE
            )
        } else {
            openNotificationSettings()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            NOTIFICATIONS_REQUEST_CODE -> {
                if (!granted && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                        this, android.Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    openNotificationSettings()
                }
                updatePermissionBanner()
            }
            CONTACTS_REQUEST_CODE -> {
                if (granted) startSync()
                else Toast.makeText(this, "Contacts permission is needed to sync", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openNotificationSettings() {
        try {
            val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open Settings", Toast.LENGTH_SHORT).show()
        }
    }
}
