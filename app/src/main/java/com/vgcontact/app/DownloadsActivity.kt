package com.vgcontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class DownloadsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var allFiles: List<org.json.JSONObject> = emptyList()
    private var unlockedFileIds: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        // File list container
        fetchFilesFromSupabase()

        // Simple title header (this screen doesn't show the full profile header)
        findViewById<TextView>(R.id.headerTitleText).text = "Downloads"
        setupBottomNav()
        ChatSupportHelper.attach(this)
    }

    private fun fetchFilesFromSupabase() {
        val userId = sessionManager.getUserId()

        thread {
            SupabaseClient.fetchFiles { filesSuccess, filesArr ->
                if (!filesSuccess || filesArr == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Couldn't load files. Check your connection.", Toast.LENGTH_SHORT).show()
                    }
                    return@fetchFiles
                }

                val files = mutableListOf<org.json.JSONObject>()
                for (i in 0 until filesArr.length()) {
                    files.add(filesArr.getJSONObject(i))
                }

                if (userId.isNullOrBlank()) {
                    allFiles = files
                    unlockedFileIds = emptySet()
                    runOnUiThread { loadFiles() }
                    return@fetchFiles
                }

                SupabaseClient.fetchUnlockedFileIds(userId) { _, ids ->
                    allFiles = files
                    unlockedFileIds = ids
                    runOnUiThread { loadFiles() }
                }
            }
        }
    }

    private fun loadFiles() {
        val fileListContainer = findViewById<LinearLayout>(R.id.file_list_container)
        fileListContainer.removeAllViews()

        allFiles.forEach { file ->
            val fileId = file.optString("id")
            val fileName = file.optString("file_name")
            val contactCount = file.optInt("contact_count", 0)
            val fileUrl = file.optString("file_url")
            val isLocked = !unlockedFileIds.contains(fileId)

            val fileView = layoutInflater.inflate(R.layout.item_file, fileListContainer, false)
            val fileNameView = fileView.findViewById<TextView>(R.id.file_name)
            val fileCountView = fileView.findViewById<TextView>(R.id.file_count)
            val statusPill = fileView.findViewById<LinearLayout>(R.id.file_status_pill)
            val statusIconView = fileView.findViewById<ImageView>(R.id.file_status_icon)
            val fileStatusView = fileView.findViewById<TextView>(R.id.file_status)
            val downloadBtn = fileView.findViewById<Button>(R.id.download_btn)

            fileNameView.text = fileName
            fileCountView.text = "$contactCount Contacts"
            fileStatusView.text = if (isLocked) "Locked" else "Unlocked"

            statusIconView.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_unlock)
            val statusColor = ContextCompat.getColor(this, if (isLocked) R.color.locked_text else R.color.success_text)
            statusIconView.setColorFilter(statusColor)
            fileStatusView.setTextColor(statusColor)
            statusPill.setBackgroundResource(if (isLocked) R.drawable.pill_locked_background else R.drawable.pill_unlocked_background)

            downloadBtn.text = if (isLocked) "Repost to Unlock" else "Download"
            downloadBtn.setOnClickListener {
                if (isLocked) {
                    val intent = Intent(this, RepostActivity::class.java)
                    intent.putExtra("file_id", fileId)
                    intent.putExtra("file_name", fileName)
                    startActivity(intent)
                } else if (fileUrl.isNotBlank()) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fileUrl)))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Couldn't open file link", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "No file link available", Toast.LENGTH_SHORT).show()
                }
            }

            fileListContainer.addView(fileView)
        }
    }


    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.DOWNLOADS)
    }

}
