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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.concurrent.thread

class DownloadsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var allGroups: List<org.json.JSONObject> = emptyList()
    private var unlockedGroupIds: Set<String> = emptySet()

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
                btnText = groupView.findViewById(R.id.download_btn_text)
            )
            groupView.findViewById<TextView>(R.id.file_name).text = "Contact List #$groupNumber"
            row.count.text = if (isLocked) "Locked, $memberCount contacts" else "Verified, $memberCount contacts"
            row.icon.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_unlock)
            row.dot.setBackgroundResource(if (isLocked) R.drawable.status_dot_locked else R.drawable.status_dot_unlocked)
            applyActionButton(row, groupId)

            // Locked: UNLOCK spins while it checks keys, spends one, and opens the
            // save-contact screen by itself (no confirmation question).
            // Unlocked: SAVE spins briefly, then opens the save-contact screen.
            row.btn.setOnClickListener {
                if (unlockedGroupIds.contains(groupId)) {
                    generateVcfAndImport(row, groupId)
                } else {
                    startUnlock(row, groupId)
                }
            }

            fileListContainer.addView(groupView)
        }
    }

    private class RowViews(
        val icon: ImageView,
        val dot: android.view.View,
        val count: TextView,
        val btn: FrameLayout,
        val btnIcon: ImageView,
        val btnText: TextView
    )

    private val spinners = mutableMapOf<android.view.View, ProgressBar>()

    // Locked -> green UNLOCK pill. Unlocked -> green SAVE pill (tapping it
    // opens the save-contact screen again if it didn't open by itself).
    private fun applyActionButton(row: RowViews, groupId: String) {
        val locked = !unlockedGroupIds.contains(groupId)
        row.btn.setBackgroundResource(R.drawable.file_row_unlock_pill_background)
        row.btnText.text = if (locked) "UNLOCK" else "SAVE"
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
    //  - Has a key -> spend it and open the import screen straight away.
    //  - No keys   -> stop spinning and show the out-of-keys pop-up.
    // If the balance can't be read we still try the unlock; the server
    // answers NO_KEYS when there are none.
    private fun startUnlock(row: RowViews, groupId: String) {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't verify your account. Please restart the app.", Toast.LENGTH_SHORT).show()
            return
        }

        setRowLoading(row, true, groupId)
        thread {
            SupabaseClient.fetchKeyBalance(userId) { ok, balance ->
                runOnUiThread {
                    if (ok && balance <= 0) {
                        setRowLoading(row, false, groupId)
                        showOutOfKeysDialog()
                    } else {
                        unlockWithKey(row, groupId)
                    }
                }
            }
        }
    }

    // Rounded pop-up: ways to get a key.
    private fun showOutOfKeysDialog() {
        MaterialAlertDialogBuilder(this, R.style.VGRoundedAlertDialog)
            .setTitle("You're out of keys")
            .setMessage("Repost today for a free key, or buy one for \u20A61,000. Keys are added within 24 hours.")
            .setPositiveButton("Repost free") { _, _ ->
                startActivity(Intent(this, RepostActivity::class.java))
            }
            .setNegativeButton("Buy a key") { _, _ ->
                startActivity(Intent(this, BuyKeysActivity::class.java))
            }
            .show()
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

        thread {
            SupabaseClient.spendKeyToUnlockGroup(userId, groupId) { success, message, _ ->
                runOnUiThread {
                    if (success) {
                        unlockedGroupIds = unlockedGroupIds + groupId

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
                            "NO_KEYS" -> showOutOfKeysDialog()
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

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.DOWNLOADS)
    }

}
