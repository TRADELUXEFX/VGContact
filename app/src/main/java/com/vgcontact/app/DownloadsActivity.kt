package com.vgcontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class DownloadsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var selectedCategory = "All"
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

        // Category filters
        setupCategoryFilters()

        // File list container
        fetchFilesFromSupabase()

        // Chat button
        val chatBtn = findViewById<ImageButton>(R.id.chat_btn)
        chatBtn.setOnClickListener {
            Toast.makeText(this, "Opening chat support", Toast.LENGTH_SHORT).show()
        }

        // Bottom Navigation

        // Simple title header (this screen doesn't show the full profile header)
        findViewById<TextView>(R.id.headerTitleText).text = "Downloads"
        setupBottomNav()
    }

    private fun setupCategoryFilters() {
        val filterAll = findViewById<Button>(R.id.filter_all)
        val filterBusiness = findViewById<Button>(R.id.filter_business)
        val filterSocial = findViewById<Button>(R.id.filter_social)
        val filterOther = findViewById<Button>(R.id.filter_other)

        filterAll.setOnClickListener { selectFilter("All", filterAll, filterBusiness, filterSocial, filterOther) }
        filterBusiness.setOnClickListener { selectFilter("Business", filterAll, filterBusiness, filterSocial, filterOther) }
        filterSocial.setOnClickListener { selectFilter("Social", filterAll, filterBusiness, filterSocial, filterOther) }
        filterOther.setOnClickListener { selectFilter("Other", filterAll, filterBusiness, filterSocial, filterOther) }
    }

    private fun selectFilter(category: String, vararg buttons: Button) {
        selectedCategory = category
        val selectedIndex = when (category) {
            "All" -> 0
            "Business" -> 1
            "Social" -> 2
            else -> 3
        }
        buttons.forEachIndexed { index, button ->
            if (index == selectedIndex) {
                button.setBackgroundResource(R.drawable.filter_chip_selected_background)
                button.setTextColor(ContextCompat.getColor(this, R.color.white))
            } else {
                button.setBackgroundResource(R.drawable.filter_chip_default_background)
                button.setTextColor(ContextCompat.getColor(this, R.color.vg_green_dark))
            }
        }
        loadFiles(category)
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
                    runOnUiThread { loadFiles(selectedCategory) }
                    return@fetchFiles
                }

                SupabaseClient.fetchUnlockedFileIds(userId) { _, ids ->
                    allFiles = files
                    unlockedFileIds = ids
                    runOnUiThread { loadFiles(selectedCategory) }
                }
            }
        }
    }

    private fun loadFiles(category: String) {
        val fileListContainer = findViewById<LinearLayout>(R.id.file_list_container)
        fileListContainer.removeAllViews()

        allFiles.forEach { file ->
            val fileCategory = file.optString("file_category", "Other")
            if (category == "All" || fileCategory == category) {
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
    }


    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.DOWNLOADS)
    }

}
