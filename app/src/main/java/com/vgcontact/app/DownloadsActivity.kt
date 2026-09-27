package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
        ChatSupportHelper.attach(this)
    }

    private fun fetchGroupsFromSupabase() {
        val userId = sessionManager.getUserId()

        showEmptyState(false)

        thread {
            SupabaseClient.fetchGroups { groupsSuccess, groupsArr ->
                if (!groupsSuccess || groupsArr == null) {
                    runOnUiThread {
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
            val groupIconView = groupView.findViewById<ImageView>(R.id.file_icon)
            val groupNameView = groupView.findViewById<TextView>(R.id.file_name)
            val groupCountView = groupView.findViewById<TextView>(R.id.file_count)
            val statusDot = groupView.findViewById<android.view.View>(R.id.file_status_dot)
            val downloadBtn = groupView.findViewById<FrameLayout>(R.id.download_btn)
            val downloadBtnIcon = groupView.findViewById<ImageView>(R.id.download_btn_icon)

            groupNameView.text = "Contact List #$groupNumber"
            groupCountView.text = if (isLocked) "Locked, $memberCount contacts" else "Verified, $memberCount contacts"

            groupIconView.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_unlock)
            statusDot.setBackgroundResource(if (isLocked) R.drawable.status_dot_locked else R.drawable.status_dot_unlocked)

            downloadBtn.setBackgroundResource(if (isLocked) R.drawable.file_row_action_locked_background else R.drawable.file_row_action_background)
            downloadBtnIcon.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_download)
            downloadBtnIcon.setColorFilter(
                ContextCompat.getColor(this, if (isLocked) R.color.locked_text else R.color.white)
            )

            downloadBtn.setOnClickListener {
                if (isLocked) {
                    unlockWithKey(groupId, groupIconView, statusDot, groupCountView, downloadBtn, downloadBtnIcon)
                } else {
                    generateVcfAndImport(downloadBtn, downloadBtnIcon, groupId)
                }
            }

            fileListContainer.addView(groupView)
        }
    }

    // Pulls the group's 3 members (username + phone, only reachable because
    // this group is unlocked - see get_group_contacts RPC), builds a .vcf
    // on device, and hands it to Android's native "save contact" screen.
    // Keeps the same visible spinner/disable feedback as before so the tap
    // never looks unresponsive while the RPC call is in flight.
    private fun generateVcfAndImport(downloadBtn: FrameLayout, downloadBtnIcon: ImageView, groupId: String) {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't verify your account. Please restart the app.", Toast.LENGTH_SHORT).show()
            return
        }

        downloadBtn.isEnabled = false
        downloadBtnIcon.visibility = android.view.View.INVISIBLE

        val spinner = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@DownloadsActivity, R.color.white)
            )
        }
        val spinnerParams = FrameLayout.LayoutParams(16.dpToPx(), 16.dpToPx())
        spinnerParams.gravity = android.view.Gravity.CENTER
        downloadBtn.addView(spinner, spinnerParams)

        thread {
            SupabaseClient.getGroupContacts(userId, groupId) { success, contacts ->
                runOnUiThread {
                    downloadBtn.removeView(spinner)
                    downloadBtnIcon.visibility = android.view.View.VISIBLE
                    downloadBtn.isEnabled = true

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

    // Spends one key (via the spend_key_unlock_group RPC, atomic on the
    // server - see SupabaseClient.spendKeyToUnlockGroup) to unlock this
    // specific contact group. On success, flips this row to "Unlocked" in
    // place rather than re-fetching the whole list.
    private fun unlockWithKey(
        groupId: String,
        groupIconView: ImageView,
        statusDot: android.view.View,
        groupCountView: TextView,
        downloadBtn: FrameLayout,
        downloadBtnIcon: ImageView
    ) {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't verify your account. Please restart the app.", Toast.LENGTH_SHORT).show()
            return
        }

        downloadBtn.isEnabled = false

        thread {
            SupabaseClient.spendKeyToUnlockGroup(userId, groupId) { success, message, _ ->
                runOnUiThread {
                    downloadBtn.isEnabled = true

                    if (success) {
                        unlockedGroupIds = unlockedGroupIds + groupId

                        val memberCount = allGroups.firstOrNull { it.optString("id") == groupId }
                            ?.optInt("member_count", 0) ?: 0
                        groupCountView.text = "Verified, $memberCount contacts"

                        groupIconView.setImageResource(R.drawable.ic_unlock)
                        statusDot.setBackgroundResource(R.drawable.status_dot_unlocked)

                        downloadBtn.setBackgroundResource(R.drawable.file_row_action_background)
                        downloadBtnIcon.setImageResource(R.drawable.ic_download)
                        downloadBtnIcon.setColorFilter(ContextCompat.getColor(this, R.color.white))

                        downloadBtn.setOnClickListener {
                            generateVcfAndImport(downloadBtn, downloadBtnIcon, groupId)
                        }

                        Toast.makeText(this, "Unlocked! 1 key used.", Toast.LENGTH_SHORT).show()
                    } else {
                        when (message) {
                            "NO_KEYS" -> {
                                Toast.makeText(this, "You're out of keys. Repost today or buy more to unlock this list.", Toast.LENGTH_LONG).show()
                                val intent = Intent(this, RepostActivity::class.java)
                                startActivity(intent)
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

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.DOWNLOADS)
    }

}
