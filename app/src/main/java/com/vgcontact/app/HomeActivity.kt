package com.vgcontact.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
    private var autoSyncing = false

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
            if (PendingPrompt.showGate(this)) return@setOnClickListener
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<ImageView>(R.id.headerBellIcon).setOnClickListener {
            if (PendingPrompt.showGate(this)) return@setOnClickListener
            startActivity(Intent(this, NotificationsActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.permissionBanner).setOnClickListener { fixPermissions() }
        // Pending users: the strip brings the verify sheet back.
        findViewById<LinearLayout>(R.id.updateBanner).setOnClickListener {
            AppUpdatePrompt.openPending(this)
        }
        findViewById<LinearLayout>(R.id.pendingBanner).setOnClickListener {
            PendingPrompt.showGate(this)
        }

        syncBtn = findViewById(R.id.syncContactsBtn)
        syncBtn.setOnClickListener {
            if (PendingPrompt.showGate(this)) return@setOnClickListener
            startSync()
        }

        findViewById<Button>(R.id.buyViewersBtn).setOnClickListener {
            if (PendingPrompt.showGate(this)) return@setOnClickListener
            SupportContact.openBuyViewers(this)
        }

        findViewById<Button>(R.id.join_community_btn).setOnClickListener {
            if (PendingPrompt.showGate(this)) return@setOnClickListener
            CommunityLink.open(this)
        }

        setupReferralLink()
        applyCachedStatus()

        BottomNavHelper.setup(this, BottomNavHelper.Tab.HOME)
        contactUsFab = FloatingContactHelper.attach(this)

    }

    // Shows the last known account status straight away, so a verified user never
    // sees PENDING flash while Home reloads. With nothing saved yet, the badge
    // stays hidden until the first load answers.
    private fun applyCachedStatus() {
        val badgeText = findViewById<TextView>(R.id.statusBadgeText)
        val status = PendingPrompt.cachedStatus(this)
        if (status == null) {
            (badgeText.parent as View).visibility = View.INVISIBLE
            return
        }
        badgeText.text = status.uppercase()
        val pending = status == "pending"
        findViewById<View>(R.id.pendingBanner).visibility = if (pending) View.VISIBLE else View.GONE
        findViewById<View>(R.id.home_contacts_card).alpha = if (pending) 0.45f else 1f
        syncBtn.alpha = if (pending) 0.6f else 1f
    }

    // Referral link row in the green header card: the link is LINK_BASE + the user's phone
    // number (same value the Referral tab shares). Copy copies the full link.
    private fun setupReferralLink() {
        val phone = sessionManager.getPhone().orEmpty()
        val link = if (phone.isNotBlank()) ReferralActivity.LINK_BASE + phone else ""
        val card = findViewById<View>(R.id.home_referral_card)
        if (link.isBlank()) {
            card.visibility = View.GONE
            return
        }
        // Full link, unshortened. It sits in a HorizontalScrollView, so the
        // user slides it sideways to read the whole thing.
        findViewById<TextView>(R.id.home_referral_link_text).text = link
        findViewById<View>(R.id.home_referral_copy_btn).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("referral_link", link))
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        }
    }

    // Guards against starting the sheet/tour twice (loadHome runs on every resume).
    private var onboardingActive = false

    // Called once the account status is known.
    // Pending users: only the one-time "Verify your account" sheet, never the coach mark tour.
    // Verified users: the tour runs from their second Home open (never on the very first one).
    private fun startOnboarding(pending: Boolean) {
        if (onboardingActive || isFinishing || isDestroyed) return

        val tips = getSharedPreferences("vg_tips", MODE_PRIVATE)
        val seenHomeBefore = tips.getBoolean("home_seen_before", false)
        if (!seenHomeBefore) tips.edit().putBoolean("home_seen_before", true).apply()

        if (pending) {
            if (PendingPrompt.wasShown(this)) return
            onboardingActive = true
            AppUpdatePrompt.dismissSoft()   // the verify sheet goes first, never two sheets at once
            PendingPrompt.show(this, onLater = { onboardingActive = false })
            return
        }

        if (CoachMarkOverlay.isTourDone(this) || !seenHomeBefore) return
        onboardingActive = true
        findViewById<View>(R.id.home_content_scroll).post { showHomeTour() }
    }

    private fun showHomeTour() {
        if (CoachMarkOverlay.isTourDone(this)) return

        val scroller = findViewById<NestedScrollView>(R.id.home_content_scroll)
        scroller.scrollTo(0, 0)

        val steps = mutableListOf(
            CoachMarkOverlay.Step(
                findViewById(R.id.home_contacts_card),
                "Your viewers",
                "Free viewers come with your group. Extra viewers come from verified reposts.",
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

    // ONE server call (get_app_bundle) fills the whole screen: status + viewers, bell dot,
    // ban check, app update and community link.
    private fun loadHome() {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, "No account id saved on this phone. Log out and log in again.", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val bundle = SupabaseClient.fetchBundle(applicationContext)
            val err = SupabaseClient.lastError
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bundle != null && bundle.banned) {
                    BannedHandler.handleBanned(this, bundle.banReason)
                    return@runOnUiThread
                }
                if (bundle == null || bundle.home == null) {
                    Toast.makeText(
                        this,
                        "Couldn't load your viewers: " + (err ?: "no account found for this login. Log out and log in again."),
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                // Save the sheet numbers + verify status first: the verify sheet reads them.
                PendingConfig.save(this, bundle.settings)
                PendingPrompt.saveVerifyStatus(this, bundle.todayVerifyStatus)
                showHome(bundle.home)
                startOnboarding(bundle.home.status == "pending")
                findViewById<View>(R.id.headerBellUnreadDot).visibility =
                    if (bundle.hasUnread) View.VISIBLE else View.GONE
                CommunityLink.save(this, bundle.communityLink)
                // After startOnboarding, so the soft update pop-up yields to the verify sheet.
                val updateBanner = findViewById<View>(R.id.updateBanner)
                AppUpdatePrompt.handleUpdate(this, bundle.update, { !onboardingActive }) { show ->
                    updateBanner.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
        }.start()
    }

    private var contactsWereOn: Boolean? = null

    private fun showHome(h: SupabaseClient.HomeData) {
        findViewById<View>(R.id.home_limits_loading).visibility = View.GONE
        findViewById<View>(R.id.home_limits_block).visibility = View.VISIBLE
        val statusBadge = findViewById<TextView>(R.id.statusBadgeText)
        statusBadge.text = h.status.uppercase()
        (statusBadge.parent as View).visibility = View.VISIBLE
        PendingPrompt.saveStatus(this, h.status)
        val pending = h.status == "pending"
        PendingPrompt.setPending(this, pending)
        findViewById<View>(R.id.pendingBanner).visibility = if (pending) View.VISIBLE else View.GONE
        // Locked look: the viewers card is dimmed until the account is verified.
        findViewById<View>(R.id.home_contacts_card).alpha = if (pending) 0.45f else 1f
        syncBtn.alpha = if (pending) 0.6f else 1f

        findViewById<TextView>(R.id.freeViewersCurrentText).text = h.freeCurrent.toString()
        findViewById<TextView>(R.id.freeViewersMaxText).text = "/${h.freeMax}"

        findViewById<TextView>(R.id.extraViewersCurrentText).text = h.extraCurrent.toString()
        findViewById<TextView>(R.id.extraViewersMaxText).text = "/${h.extraMax}"

        findViewById<TextView>(R.id.referralViewersCountText).text = h.referralCount.toString()

        autoSyncIfViewersChanged(h)
    }

    // Verified (or gained viewers) since the last sync: save the new contacts right
    // away instead of waiting for the button or the background timer.
    private fun autoSyncIfViewersChanged(h: SupabaseClient.HomeData) {
        if (h.status == "pending" || isSyncing || autoSyncing) return
        if (!ContactSync.hasPermission(this) || SyncPrefs.isPaused(this)) return
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return
        val snapshot = "${h.status}:${h.freeCurrent}:${h.extraCurrent}:${h.referralCount}"
        if (SyncPrefs.getViewerSnapshot(this) == snapshot) return
        autoSyncing = true
        Thread {
            val result = ContactSync.run(this, userId)
            runOnUiThread {
                autoSyncing = false
                if (result.error == null) {
                    SyncPrefs.setViewerSnapshot(this, snapshot)
                    if (result.added > 0) {
                        Toast.makeText(this, "${result.added} new contacts saved", Toast.LENGTH_LONG).show()
                    }
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

        // Sync now also resumes: no need to go to Profile after deleting contacts.
        if (SyncPrefs.isPaused(this)) SyncPrefs.setPaused(this, false)

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
                    result.error == ContactSync.ERR_BANNED -> "This account is banned"
                    result.error == ContactSync.ERR_PAUSED -> "Syncing is paused. Tap Resume Syncing in Profile."
                    result.error == ContactSync.ERR_NO_INTERNET -> "No internet connection"
                    result.error == ContactSync.ERR_FETCH -> "Couldn't reach the server. Try again."
                    result.failed > 0 -> "${result.added} added, ${result.failed} failed"
                    result.added == 0 && result.removed > 0 -> "${result.removed} inactive contact(s) removed"
                    result.added == 0 -> "Your contacts are up to date"
                    SyncPrefs.getTodayAdded(this) == 1 -> "1 contact added today"
                    else -> "${SyncPrefs.getTodayAdded(this)} contacts added today"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshLastSync()
                loadHome()
            }
        }.start()
    }

    // ---------------- last sync pill ----------------

    // "Synced 2 hours ago" (green), "Last sync 3 days ago" (amber, older than 2 days),
    // "Not synced yet" or "Syncing paused" (grey). Time is the last successful sync of
    // any kind (button, opening the app, or the background sync).
    private fun refreshLastSync() {
        val pill = findViewById<TextView>(R.id.lastSyncPill) ?: return
        val last = SyncPrefs.getLastSyncAt(this)
        val ageMs = System.currentTimeMillis() - last
        val mins = ageMs / 60000L
        val hours = mins / 60L
        val days = hours / 24L
        val ago = when {
            mins < 1L -> "just now"
            mins < 60L -> "$mins min ago"
            hours < 24L -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
            days == 1L -> "yesterday"
            else -> "$days days ago"
        }
        val text: String
        val bg: String
        val fg: String
        when {
            SyncPrefs.isPaused(this) -> { text = "Syncing paused"; bg = "#EEF1EF"; fg = "#5F6B64" }
            last == 0L -> { text = "Not synced yet"; bg = "#EEF1EF"; fg = "#5F6B64" }
            hours >= 48L -> { text = "Last sync $ago"; bg = "#FFF1D6"; fg = "#8A5A00" }
            else -> { text = "Synced $ago"; bg = "#E1F5EB"; fg = "#158245" }
        }
        pill.text = text
        pill.setTextColor(android.graphics.Color.parseColor(fg))
        pill.compoundDrawableTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(fg))
        pill.backgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(bg))
    }

    // ---------------- lifecycle ----------------

    override fun onResume() {
        super.onResume()
        if (BanPrefs.isBanned(this)) {
            BannedHandler.showFrom(this)
            return
        }
        loadHome()
        refreshLastSync()
        updatePermissionBanner()
        // Contacts were off and are now on (turned on in the phone's Settings): sync right away.
        val contactsOn = ContactSync.hasPermission(this)
        if (contactsWereOn == false && contactsOn && !SyncPrefs.isPaused(this)) startSync()
        contactsWereOn = contactsOn
        // The red update banner shows right away from what the phone remembers; the single
        // server call in loadHome() then confirms it and may open the update pop-up.
        findViewById<View>(R.id.updateBanner).visibility =
            if (AppUpdatePrompt.hasPendingUpdate(this)) View.VISIBLE else View.GONE
    }

    // ---------------- "your sync stopped" help ----------------

    /** Opened by the server's stalled-sync push (action fix_sync). */
    fun showSyncHelp() {
        if (isFinishing) return
        val close = VgDialog.Action("Close")
        val title = "Your contact sync stopped"
        when {
            SyncPrefs.isPaused(this) -> VgDialog.show(
                this, VgDialog.Tone.WARNING, title,
                "Syncing is paused, so new viewers are not being saved to your phone. Open Profile and tap Resume Syncing.",
                primary = VgDialog.Action("Open Profile") {
                    startActivity(Intent(this, ProfileActivity::class.java))
                },
                secondary = close
            )
            !ContactSync.hasPermission(this) -> VgDialog.show(
                this, VgDialog.Tone.WARNING, title,
                "Contacts permission is off, so nothing can be saved to your phone.",
                primary = VgDialog.Action("Turn on") {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            android.Manifest.permission.READ_CONTACTS,
                            android.Manifest.permission.WRITE_CONTACTS
                        ),
                        CONTACTS_REQUEST_CODE
                    )
                },
                secondary = close
            )
            else -> VgDialog.show(
                this, VgDialog.Tone.WARNING, title,
                "Your phone may be stopping VGContact in the background. Allow it to keep running. This warning goes away after the next automatic sync works.",
                primary = VgDialog.Action("Fix now") {
                    when {
                        OemAutostart.isKnownOem() -> OemAutostart.openSettings(this)
                        isBatteryRestricted() -> openBatterySettings()
                        else -> openAppSettings()
                    }
                },
                secondary = close,
                extra = VgDialog.Action("Sync now") { startSync() }
            )
        }
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

        // Contacts and battery only matter while syncing is not paused.
        val syncOn = !SyncPrefs.isPaused(this)
        val missing = mutableListOf<String>()
        if (syncOn && !ContactSync.hasPermission(this)) missing.add("Contacts")
        if (notificationsOff) missing.add("Notifications")
        if (syncOn && isBatteryRestricted()) missing.add("Battery")
        if (syncOn && OemAutostart.needsPrompt(this)) missing.add("Autostart")
        missingPermissions = missing

        if (missing.isEmpty()) {
            banner.visibility = View.GONE
        } else {
            // One line, most important problem first.
            bannerText.text = when (missing.first()) {
                "Contacts" -> "Contacts are off - new viewers can't be saved to your phone"
                "Notifications" -> "Notifications are off - you won't get repost alerts"
                "Autostart" -> "Your contact sync looks stalled - allow autostart so it keeps running"
                else -> "Battery saver may stop your daily contact sync"
            }
            banner.visibility = View.VISIBLE
        }
    }

    private fun isBatteryRestricted(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return false
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open Settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatterySettings() {
        // First choice: the one-tap system pop-up "Allow VGContact to always run in the
        // background?" (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the manifest).
        // If the phone refuses it, fall back to the battery settings list, then app settings.
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
            return
        } catch (e: Exception) {
            // fall through
        }
        try {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    private fun fixPermissions() {
        when (missingPermissions.firstOrNull()) {
            "Contacts" -> androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.WRITE_CONTACTS
                ),
                CONTACTS_REQUEST_CODE
            )
            "Notifications" -> {
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
            "Battery" -> openBatterySettings()
            "Autostart" -> OemAutostart.openSettings(this)
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
                else {
                    Toast.makeText(this, "Contacts permission is needed to sync", Toast.LENGTH_LONG).show()
                    // "Don't ask again" was chosen: only Settings can turn it on now.
                    if (!androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                            this, android.Manifest.permission.READ_CONTACTS)
                    ) openAppSettings()
                }
                updatePermissionBanner()
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
