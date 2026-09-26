package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class DownloadsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var selectedCategory = "All"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Header
        val headerTitle = findViewById<TextView>(R.id.header_title)
        headerTitle.text = "Contact Files"

        // Category filters
        setupCategoryFilters()

        // File list container
        val fileListContainer = findViewById<LinearLayout>(R.id.file_list_container)
        loadFiles("All")

        // Chat button
        val chatBtn = findViewById<Button>(R.id.chat_btn)
        chatBtn.setOnClickListener {
            Toast.makeText(this, "Opening chat support", Toast.LENGTH_SHORT).show()
        }

        // Bottom Navigation
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

    private fun loadFiles(category: String) {
        val fileListContainer = findViewById<LinearLayout>(R.id.file_list_container)
        fileListContainer.removeAllViews()

        // Sample files (in real app, fetch from Supabase)
        val files = listOf(
            mapOf(
                "name" to "Business_Contacts_Sept26.vcf",
                "count" to "150 Contacts",
                "category" to "Business",
                "status" to "Locked"
            ),
            mapOf(
                "name" to "Social_Influencers_Sept26.vcf",
                "count" to "250 Contacts",
                "category" to "Social",
                "status" to "Unlocked"
            )
        )

        files.forEach { file ->
            if (category == "All" || file["category"] == category) {
                val fileView = layoutInflater.inflate(R.layout.item_file, fileListContainer, false)
                val fileName = fileView.findViewById<TextView>(R.id.file_name)
                val fileCount = fileView.findViewById<TextView>(R.id.file_count)
                val statusPill = fileView.findViewById<LinearLayout>(R.id.file_status_pill)
                val statusIconView = fileView.findViewById<ImageView>(R.id.file_status_icon)
                val fileStatus = fileView.findViewById<TextView>(R.id.file_status)
                val downloadBtn = fileView.findViewById<Button>(R.id.download_btn)

                fileName.text = file["name"]
                fileCount.text = file["count"]
                fileStatus.text = file["status"]

                val isLocked = file["status"] == "Locked"
                statusIconView.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_unlock)
                val statusColor = ContextCompat.getColor(this, if (isLocked) R.color.locked_text else R.color.success_text)
                statusIconView.setColorFilter(statusColor)
                fileStatus.setTextColor(statusColor)
                statusPill.setBackgroundResource(if (isLocked) R.drawable.pill_locked_background else R.drawable.pill_unlocked_background)

                downloadBtn.setOnClickListener {
                    Toast.makeText(this, "Downloading ${file["name"]}", Toast.LENGTH_SHORT).show()
                }

                fileListContainer.addView(fileView)
            }
        }
    }

    private fun setupBottomNav() {
        val navView = findViewById<BottomNavigationView>(R.id.bottom_nav)
        navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, HomeActivity::class.java))
                    false
                }
                R.id.nav_repost -> {
                    startActivity(Intent(this, RepostActivity::class.java))
                    false
                }
                R.id.nav_downloads -> true
                R.id.nav_community -> {
                    startActivity(Intent(this, CommunityActivity::class.java))
                    false
                }
                R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    false
                }
                else -> false
            }
        }
        navView.selectedItemId = R.id.nav_downloads
    }

}
