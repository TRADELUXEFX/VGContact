package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

class DownloadsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var allGroups: List<org.json.JSONObject> = emptyList()
    private var unlockedGroupIds: Set<String> = emptySet()

    // Key balance, fetched ahead of time so the pop-up opens instantly on tap.
    // null = not known yet. The server still has the final say when spending.
    private var cachedKeyBalance: Int? = null

    // Lists whose save-contact screen the user has opened and come back from.
    // Android doesn't say whether Save was tapped, so coming back = "Saved".
    private var pendingSavedGroupIds = mutableSetOf<String>()

    // Select mode (tick several files, unlock + save them together).
    private var selectMode = false
    private val selectedIds = mutableSetOf<String>()
    private val rowsById = mutableMapOf<String, RowViews>()
    private var batchBusy = false

    private fun savedIds(): Set<String> =
        getSharedPreferences("vg_saved", MODE_PRIVATE)
            .getStringSet("ids_${sessionManager.getUserId()}", emptySet()) ?: emptySet()

    private fun markSaved(groupId: String) {
        val updated = savedIds() + groupId
        getSharedPreferences("vg_saved", MODE_PRIVATE).edit()
            .putStringSet("ids_${sessionManager.getUserId()}", updated).apply()
    }

    override fun onResume() {
        super.onResume()
        if (pendingSavedGroupIds.isNotEmpty()) {
            pendingSavedGroupIds.forEach { markSaved(it) }
            pendingSavedGroupIds.clear()
            if (allGroups.isNotEmpty()) loadGroups()
        }
        // Also covers coming back from Repost / Buy keys with a new balance.
        prefetchKeyBalance()
    }

    private fun prefetchKeyBalance() {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return
        thread {
            SupabaseClient.fetchKeyBalance(userId) { ok, balance ->
                if (ok) {
                    cachedKeyBalance = balance
                    runOnUiThread { refreshSelectionUi() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        // Group list container
        fetchGroupsFromSupabase()

        setupSelectMode()
        setupBottomNav()
        FloatingContactHelper.attach(this)
    }

    private fun fetchGroupsFromSupabase() {
        val userId = sessionManager.getUserId()

        showLoadingState(true)
        showEmptyState(false)

        thread {
            SupabaseClient.fetchGroups { groupsSuccess, groupsArr ->
                if (!groupsSuccess || groupsArr == null) {
                    runOnUiThread {
                        showLoadingState(false)
                        Toast.makeText(this, "Couldn't load contact lists. Check your connection.", Toast.LENGTH_SHORT).show()
                        showEmptyState(true, "Couldn't load contact lists")
                    }
                    return@fetchGroups
                }

                val groups = mutableListOf<org.json.JSONObject>()
                for (i in 0 until groupsArr.length()) {
                    groups.add(groupsArr.getJSONObject(i))
                }

                if (userId.isNullOrBlank()) {
                    allGroups = groups
                    unlockedGroupIds = emptySet()
                    runOnUiThread { loadGroups() }
                    return@fetchGroups
                }

                SupabaseClient.fetchUnlockedGroupIds(userId) { _, ids ->
                    allGroups = groups
                    unlockedGroupIds = ids
                    runOnUiThread { loadGroups() }
                }
            }
        }
    }

    // Toggles the white card + spinner shown while the fetch is in
    // flight, before either results or the empty state are ready to
    // display - covers the window right after opening this screen (or
    // tapping retry) where the container would otherwise look blank.
    private fun showLoadingState(show: Boolean) {
        val loadingStateContainer = findViewById<LinearLayout>(R.id.loading_state_container)
        loadingStateContainer.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    // Toggles the illustration/"tap to retry" state. Called whenever the
    // fetch fails outright, and also from loadGroups() when the fetch
    // succeeded but returned zero groups - both cases used to just leave
    // file_list_container empty with no visible explanation.
    private fun showEmptyState(show: Boolean, message: String = "No contact lists available right now") {
        val emptyStateContainer = findViewById<LinearLayout>(R.id.empty_state_container)
        val emptyStateText = findViewById<TextView>(R.id.empty_state_text)
        emptyStateText.text = message
        emptyStateContainer.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        emptyStateContainer.setOnClickListener { fetchGroupsFromSupabase() }
    }

    private fun loadGroups() {
        showLoadingState(false)

        val fileListContainer = findViewById<LinearLayout>(R.id.file_list_container)
        fileListContainer.removeAllViews()
        rowsById.clear()

        if (allGroups.isEmpty()) {
            showEmptyState(true, "No contact lists available right now")
            return
        }
        showEmptyState(false)

        allGroups.forEach { group ->
            val groupId = group.optString("id")
            val groupNumber = group.optInt("group_number", 0)
            val memberCount = group.optInt("member_count", 0)
            val isLocked = !unlockedGroupIds.contains(groupId)

            val groupView = layoutInflater.inflate(R.layout.item_file, fileListContainer, false)
            val row = RowViews(
                icon = groupView.findViewById(R.id.file_icon),
                dot = groupView.findViewById(R.id.file_status_dot),
                count = groupView.findViewById(R.id.file_count),
                btn = groupView.findViewById(R.id.download_btn),
                btnIcon = groupView.findViewById(R.id.download_btn_icon),
                btnText = groupView.findViewById(R.id.download_btn_text),
                check = groupView.findViewById(R.id.file_check)
            )
            rowsById[groupId] = row
            groupView.findViewById<TextView>(R.id.file_name).text = "Contact List #$groupNumber"
            val isSaved = !isLocked && savedIds().contains(groupId)
            row.count.text = when {
                isLocked -> "Locked, $memberCount contacts"
                isSaved -> "Saved, $memberCount contacts"
                else -> "Verified, $memberCount contacts"
            }
            row.icon.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_unlock)
            row.dot.setBackgroundResource(if (isLocked) R.drawable.status_dot_locked else R.drawable.status_dot_unlocked)
            applyActionButton(row, groupId)

            // Locked: UNLOCK spins while it checks keys, spends one, and opens the
            // save-contact screen by itself (no confirmation question).
            // Unlocked: SAVE spins briefly, then opens the save-contact screen.
            row.btn.setOnClickListener {
                if (selectMode) {
                    toggleSelected(groupId)
                    return@setOnClickListener
                }
                // One file at a time: ignore taps while another file is still
                // unlocking/loading, so keys and save screens never overlap.
                if (spinners.isNotEmpty() || batchBusy) return@setOnClickListener
                if (unlockedGroupIds.contains(groupId)) {
                    generateVcfAndImport(row, groupId)
                } else {
                    startUnlock(row, groupId)
                }
            }

            // In select mode, tapping anywhere on the row ticks it.
            groupView.setOnClickListener { if (selectMode) toggleSelected(groupId) }

            fileListContainer.addView(groupView)
        }
        refreshSelectionUi()
    }

    private class RowViews(
        val icon: ImageView,
        val dot: android.view.View,
        val count: TextView,
        val btn: FrameLayout,
        val btnIcon: ImageView,
        val btnText: TextView,
        val check: ImageView
    )

    private val spinners = mutableMapOf<android.view.View, ProgressBar>()

    // Locked -> green UNLOCK pill. Unlocked -> green SAVE pill (tapping it
    // opens the save-contact screen again if it didn't open by itself).
    private fun applyActionButton(row: RowViews, groupId: String) {
        val locked = !unlockedGroupIds.contains(groupId)
        row.btn.setBackgroundResource(R.drawable.file_row_unlock_pill_background)
        row.btnText.text = when {
            locked -> "UNLOCK"
            savedIds().contains(groupId) -> "SAVED"
            else -> "SAVE"
        }
        row.btnText.visibility = android.view.View.VISIBLE
        row.btnIcon.visibility = android.view.View.GONE
        row.btn.isEnabled = true
    }

    // Rotating spinner inside the button (keeps the button's size).
    private fun setRowLoading(row: RowViews, loading: Boolean, groupId: String) {
        if (loading) {
            if (spinners.containsKey(row.btn)) return
            row.btn.isEnabled = false
            if (row.btnIcon.visibility == android.view.View.VISIBLE) row.btnIcon.visibility = android.view.View.INVISIBLE
            if (row.btnText.visibility == android.view.View.VISIBLE) row.btnText.visibility = android.view.View.INVISIBLE
            val spinner = ProgressBar(this).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@DownloadsActivity, R.color.white)
                )
            }
            val params = FrameLayout.LayoutParams(18.dpToPx(), 18.dpToPx())
            params.gravity = android.view.Gravity.CENTER
            row.btn.addView(spinner, params)
            spinners[row.btn] = spinner
        } else {
            spinners.remove(row.btn)?.let { row.btn.removeView(it) }
            applyActionButton(row, groupId)
        }
    }

    // Pulls the group's 3 members (username + phone, only reachable because
    // this group is unlocked - see get_group_contacts RPC), builds a .vcf
    // on device, and hands it to Android's native "save contact" screen.
    private fun generateVcfAndImport(row: RowViews, groupId: String) {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't verify your account. Please restart the app.", Toast.LENGTH_SHORT).show()
            return
        }

        setRowLoading(row, true, groupId)

        thread {
            SupabaseClient.getGroupContacts(userId, groupId) { success, contacts ->
                runOnUiThread {
                    setRowLoading(row, false, groupId)

                    if (!success || contacts.isEmpty()) {
                        Toast.makeText(this, "Couldn't load this contact list. Try again.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    try {
                        val vcfFile = writeVcfFile(groupId, contacts)
                        val uri = FileProvider.getUriForFile(
                            this,
                            "$packageName.fileprovider",
                            vcfFile
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "text/x-vcard")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(intent)
                        pendingSavedGroupIds.add(groupId)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Couldn't open the save contact screen", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Builds "FN:{username} VGC" / "TEL:{phone}" cards for each of the
    // group's 3 members and writes them to one .vcf in the app's cache dir
    // (served back out via FileProvider - see generateVcfAndImport).
    private fun writeVcfFile(groupId: String, contacts: List<Pair<String, String>>): File {
        val vcfDir = File(cacheDir, "vcf").apply { mkdirs() }
        val vcfFile = File(vcfDir, "contact_list_$groupId.vcf")

        val builder = StringBuilder()
        contacts.forEach { (username, phone) ->
            builder.append("BEGIN:VCARD\r\n")
            builder.append("VERSION:3.0\r\n")
            builder.append("FN:$username VGC\r\n")
            builder.append("TEL:$phone\r\n")
            builder.append("END:VCARD\r\n")
        }

        vcfFile.writeText(builder.toString())
        return vcfFile
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    // Tap on UNLOCK: the button spins while we check the key balance.
    //  - Has keys  -> "Unlock this file?" pop-up (4 -> 3 keys left). Unlock
    //                 spends the key and opens the save-contact screen.
    //  - No keys   -> "You're out of keys" pop-up (repost free / buy a key).
    // If the balance can't be read we still try the unlock; the server
    // answers NO_KEYS when there are none.
    private fun startUnlock(row: RowViews, groupId: String) {
        if (batchBusy) return
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't verify your account. Please restart the app.", Toast.LENGTH_SHORT).show()
            return
        }

        // Fast path: balance already known, so the pop-up shows right away.
        val known = cachedKeyBalance
        if (known != null) {
            if (known <= 0) {
                showOutOfKeysDialog()
                prefetchKeyBalance() // in case keys were added since
            } else {
                showConfirmUnlockDialog(
                    known,
                    onUnlock = { unlockWithKey(row, groupId) },
                    onCancel = { }
                )
            }
            return
        }

        // Slow path (first tap before the prefetch finished): check, then show.
        setRowLoading(row, true, groupId)
        thread {
            SupabaseClient.fetchKeyBalance(userId) { ok, balance ->
                runOnUiThread {
                    if (ok) cachedKeyBalance = balance
                    when {
                        !ok -> unlockWithKey(row, groupId)
                        balance <= 0 -> {
                            setRowLoading(row, false, groupId)
                            showOutOfKeysDialog()
                        }
                        else -> showConfirmUnlockDialog(
                            balance,
                            onUnlock = { unlockWithKey(row, groupId) },
                            onCancel = { setRowLoading(row, false, groupId) }
                        )
                    }
                }
            }
        }
    }

    // Rounded pop-up: green header with the icon beside the title.
    private fun showVgPopup(
        iconRes: Int,
        title: String,
        message: String,
        balanceFrom: Int?,
        balanceTo: Int?,
        balanceOnly: Int? = null,
        positiveLabel: String,
        negativeLabel: String,
        onPositive: () -> Unit,
        onNegative: () -> Unit,
        onOutside: () -> Unit = {}
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_vg_popup, null)
        view.findViewById<ImageView>(R.id.popup_icon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.popup_title).text = title
        view.findViewById<TextView>(R.id.popup_message).text = message
        if (balanceFrom != null && balanceTo != null) {
            view.findViewById<android.view.View>(R.id.popup_balance_row).visibility = android.view.View.VISIBLE
            view.findViewById<TextView>(R.id.popup_balance_from).text = balanceFrom.toString()
            view.findViewById<TextView>(R.id.popup_balance_to).text = balanceTo.toString()
        }
        if (balanceOnly != null) {
            // Single balance ("0 keys left"), same big green style as the confirm pop-up.
            view.findViewById<android.view.View>(R.id.popup_balance_row).visibility = android.view.View.VISIBLE
            view.findViewById<android.view.View>(R.id.popup_balance_from).visibility = android.view.View.GONE
            view.findViewById<android.view.View>(R.id.popup_balance_arrow).visibility = android.view.View.GONE
            view.findViewById<TextView>(R.id.popup_balance_to).text = balanceOnly.toString()
        }
        val positive = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.popup_positive)
        val negative = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.popup_negative)
        positive.text = positiveLabel
        negative.text = negativeLabel

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(view).create()
        var handled = false
        positive.setOnClickListener { handled = true; dialog.dismiss(); onPositive() }
        negative.setOnClickListener { handled = true; dialog.dismiss(); onNegative() }
        // Tapping outside or pressing Back just closes it.
        dialog.setOnCancelListener { if (!handled) onOutside() }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showConfirmUnlockDialog(balance: Int, onUnlock: () -> Unit, onCancel: () -> Unit) {
        showVgPopup(
            iconRes = R.drawable.ic_unlock,
            title = "Unlock this file?",
            message = "This uses 1 key from your balance.",
            balanceFrom = balance,
            balanceTo = balance - 1,
            positiveLabel = "Unlock",
            negativeLabel = "Cancel",
            onPositive = onUnlock,
            onNegative = onCancel,
            onOutside = onCancel
        )
    }

    private fun showOutOfKeysDialog() {
        showVgPopup(
            iconRes = R.drawable.ic_unlock,
            title = "Unlock this file?",
            message = "You need 1 key to unlock. Repost today for a free key, or buy one for \u20A61,000.",
            balanceFrom = null,
            balanceTo = null,
            balanceOnly = 0,
            positiveLabel = "Repost free",
            negativeLabel = "Buy a key",
            onPositive = { startActivity(Intent(this, RepostActivity::class.java)) },
            onNegative = { startActivity(Intent(this, BuyKeysActivity::class.java)) }
        )
    }

    // Spends one key (via the spend_key_unlock_group RPC, atomic on the
    // server - see SupabaseClient.spendKeyToUnlockGroup). On success the row
    // flips to "Verified" and the import screen opens right away; the button
    // keeps spinning until then.
    private fun unlockWithKey(row: RowViews, groupId: String) {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            setRowLoading(row, false, groupId)
            Toast.makeText(this, "Couldn't verify your account. Please restart the app.", Toast.LENGTH_SHORT).show()
            return
        }

        setRowLoading(row, true, groupId)
        thread {
            SupabaseClient.spendKeyToUnlockGroup(userId, groupId) { success, message, _ ->
                runOnUiThread {
                    if (success) {
                        unlockedGroupIds = unlockedGroupIds + groupId
                        cachedKeyBalance = cachedKeyBalance?.let { maxOf(it - 1, 0) }

                        val memberCount = allGroups.firstOrNull { it.optString("id") == groupId }
                            ?.optInt("member_count", 0) ?: 0
                        row.count.text = "Verified, $memberCount contacts"
                        row.icon.setImageResource(R.drawable.ic_unlock)
                        row.dot.setBackgroundResource(R.drawable.status_dot_unlocked)

                        Toast.makeText(this, "Unlocked! 1 key used.", Toast.LENGTH_SHORT).show()
                        generateVcfAndImport(row, groupId)
                    } else {
                        setRowLoading(row, false, groupId)
                        when (message) {
                            "NO_KEYS" -> {
                                cachedKeyBalance = 0
                                showOutOfKeysDialog()
                            }
                            "ALREADY_UNLOCKED" -> {
                                Toast.makeText(this, "Already unlocked - refreshing.", Toast.LENGTH_SHORT).show()
                                fetchGroupsFromSupabase()
                            }
                            else -> {
                                Toast.makeText(this, "Couldn't unlock this list. Try again.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Select mode: tick several files, then unlock + save them together.
    // Only locked files cost a key; already-unlocked ones are free.
    // ---------------------------------------------------------------

    private fun setupSelectMode() {
        findViewById<TextView>(R.id.select_toggle).setOnClickListener {
            if (batchBusy || spinners.isNotEmpty()) return@setOnClickListener
            if (selectMode) exitSelectMode() else enterSelectMode()
        }
        findViewById<android.view.View>(R.id.selection_action).setOnClickListener { startBatch() }
        // Select all / Clear all (left pill, only visible in select mode).
        findViewById<TextView>(R.id.select_all_toggle).setOnClickListener {
            if (batchBusy || spinners.isNotEmpty()) return@setOnClickListener
            val ids = allGroups.map { it.optString("id") }.filter { it.isNotEmpty() }
            if (ids.isNotEmpty() && selectedIds.containsAll(ids)) selectedIds.clear() else selectedIds.addAll(ids)
            refreshSelectionUi()
        }
    }

    private fun enterSelectMode() {
        selectMode = true
        selectedIds.clear()
        refreshSelectionUi()
        prefetchKeyBalance()
    }

    private fun exitSelectMode() {
        selectMode = false
        selectedIds.clear()
        refreshSelectionUi()
    }

    private fun toggleSelected(groupId: String) {
        if (batchBusy) return
        if (!selectedIds.add(groupId)) selectedIds.remove(groupId)
        refreshSelectionUi()
    }

    private fun keysText(n: Int) = if (n == 1) "1 key" else "$n keys"

    private fun refreshSelectionUi() {
        findViewById<TextView>(R.id.select_toggle).text = if (selectMode) "Cancel" else "Select"
        findViewById<TextView>(R.id.select_all_toggle).apply {
            visibility = if (selectMode) android.view.View.VISIBLE else android.view.View.GONE
            val ids = allGroups.map { it.optString("id") }.filter { it.isNotEmpty() }
            text = if (ids.isNotEmpty() && selectedIds.containsAll(ids)) "Clear all" else "Select all"
        }
        rowsById.forEach { (id, row) ->
            row.check.visibility = if (selectMode) android.view.View.VISIBLE else android.view.View.GONE
            row.check.setImageResource(
                if (selectedIds.contains(id)) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off
            )
            row.btn.visibility = if (selectMode) android.view.View.GONE else android.view.View.VISIBLE
        }

        val bar = findViewById<android.view.View>(R.id.selection_bar)
        bar.visibility = if (selectMode) android.view.View.VISIBLE else android.view.View.GONE
        if (!selectMode) return

        val n = selectedIds.size
        val lockedCount = selectedIds.count { !unlockedGroupIds.contains(it) }
        findViewById<TextView>(R.id.selection_count).text =
            if (n == 0) "Pick files to save" else if (n == 1) "1 file selected" else "$n files selected"
        val balance = cachedKeyBalance
        findViewById<TextView>(R.id.selection_sub).text = when {
            n == 0 -> "Tap the files you want"
            lockedCount == 0 -> "Already unlocked, no keys used"
            balance != null -> "Uses ${keysText(lockedCount)} \u00B7 you have $balance"
            else -> "Uses ${keysText(lockedCount)}"
        }
    }

    private fun setBatchBusy(busy: Boolean) {
        batchBusy = busy
        findViewById<TextView>(R.id.selection_action_text).text =
            if (busy) "PLEASE WAIT..." else "UNLOCK AND SAVE"
        findViewById<android.view.View>(R.id.selection_action).isEnabled = !busy
    }

    private fun startBatch() {
        if (batchBusy || spinners.isNotEmpty()) return
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't verify your account. Please restart the app.", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "Pick at least one file", Toast.LENGTH_SHORT).show()
            return
        }

        // Keep the list's own order.
        val ordered = allGroups.map { it.optString("id") }.filter { selectedIds.contains(it) }
        val locked = ordered.filter { !unlockedGroupIds.contains(it) }

        // Nothing to pay for: just save.
        if (locked.isEmpty()) {
            runBatch(ordered, emptyList())
            return
        }

        // Always check the balance live here, so we never wrongly say
        // "not enough keys" off an old number.
        setBatchBusy(true)
        thread {
            SupabaseClient.fetchKeyBalance(userId) { ok, balance ->
                runOnUiThread {
                    setBatchBusy(false)
                    if (!ok) {
                        Toast.makeText(this, "Couldn't check your keys. Try again.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    cachedKeyBalance = balance
                    refreshSelectionUi()
                    if (balance < locked.size) {
                        showVgPopup(
                            iconRes = R.drawable.ic_key,
                            title = "Not enough keys",
                            message = "You picked ${locked.size} locked files. That needs ${keysText(locked.size)} and you have $balance.",
                            balanceFrom = null,
                            balanceTo = null,
                            balanceOnly = balance,
                            positiveLabel = "Repost free",
                            negativeLabel = "Buy a key",
                            onPositive = { startActivity(Intent(this, RepostActivity::class.java)) },
                            onNegative = { startActivity(Intent(this, BuyKeysActivity::class.java)) }
                        )
                    } else {
                        val free = ordered.size - locked.size
                        showVgPopup(
                            iconRes = R.drawable.ic_unlock,
                            title = if (locked.size == 1) "Unlock 1 file?" else "Unlock ${locked.size} files?",
                            message = "This uses ${keysText(locked.size)} from your balance." +
                                if (free > 0) " Files you already unlocked are free." else "",
                            balanceFrom = balance,
                            balanceTo = balance - locked.size,
                            positiveLabel = "Unlock",
                            negativeLabel = "Cancel",
                            onPositive = { runBatch(ordered, locked) },
                            onNegative = { }
                        )
                    }
                }
            }
        }
    }

    // Spends a key for each locked file (each spend is atomic on the server),
    // then gathers every selected unlocked file's contacts into ONE .vcf and
    // opens the save-contact screen once.
    private fun runBatch(ordered: List<String>, locked: List<String>) {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return
        setBatchBusy(true)

        thread {
            val succeeded = mutableListOf<String>()
            var outOfKeys = false
            var failed = false

            if (locked.isNotEmpty()) {
                // Preferred: one all-or-nothing request.
                var handled = false
                SupabaseClient.spendKeysToUnlockGroups(userId, locked) { success, message, _ ->
                    when {
                        success -> { succeeded.addAll(locked); handled = true }
                        message == "NO_KEYS" -> { outOfKeys = true; handled = true }
                        message == "GROUP_NOT_FOUND" || message == "GROUP_NOT_FULL" -> { failed = true; handled = true }
                    }
                }

                // Fallback (batch SQL not added yet, or a network hiccup):
                // one file at a time, each spend is still atomic.
                if (!handled) {
                    loop@ for (gid in locked) {
                        var ok = false
                        var msg = ""
                        SupabaseClient.spendKeyToUnlockGroup(userId, gid) { success, message, _ ->
                            ok = success
                            msg = message
                        }
                        when {
                            ok || msg == "ALREADY_UNLOCKED" -> succeeded.add(gid)
                            msg == "NO_KEYS" -> { outOfKeys = true; break@loop }
                            else -> failed = true
                        }
                    }
                }
            }

            val toSave = ordered.filter { unlockedGroupIds.contains(it) || succeeded.contains(it) }
            val allContacts = mutableListOf<Pair<String, String>>()
            val savedNow = mutableListOf<String>()
            for (gid in toSave) {
                SupabaseClient.getGroupContacts(userId, gid) { ok, contacts ->
                    if (ok && contacts.isNotEmpty()) {
                        allContacts.addAll(contacts)
                        savedNow.add(gid)
                    }
                }
            }
            val contacts = allContacts.distinctBy { it.second }

            runOnUiThread {
                unlockedGroupIds = unlockedGroupIds + succeeded
                cachedKeyBalance = cachedKeyBalance?.let { maxOf(it - succeeded.size, 0) }
                setBatchBusy(false)
                exitSelectMode()
                loadGroups()
                prefetchKeyBalance()

                if (contacts.isEmpty()) {
                    if (outOfKeys) showOutOfKeysDialog()
                    else Toast.makeText(this, "Couldn't load these contact lists. Try again.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                when {
                    outOfKeys -> Toast.makeText(this, "Not enough keys for the locked files. Saving the ones you already have.", Toast.LENGTH_LONG).show()
                    failed -> Toast.makeText(this, "Some files couldn't be unlocked. Try again.", Toast.LENGTH_LONG).show()
                    succeeded.isNotEmpty() -> Toast.makeText(this, "Unlocked! ${keysText(succeeded.size)} used.", Toast.LENGTH_SHORT).show()
                }

                try {
                    val vcfFile = writeVcfFile("batch", contacts)
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", vcfFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "text/x-vcard")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                    pendingSavedGroupIds.addAll(savedNow)
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't open the save contact screen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.DOWNLOADS)
    }

}
