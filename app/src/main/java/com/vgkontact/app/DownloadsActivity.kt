package com.vgkontact.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        buttons.forEach {
            it.alpha = 0.5f
        }
        buttons[0].alpha = 1f
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
                "status" to "🔒 Locked"
            ),
            mapOf(
                "name" to "Social_Influencers_Sept26.vcf",
                "count" to "250 Contacts",
                "category" to "Social",
                "status" to "🔓 Unlocked"
            )
        )

        files.forEach { file ->
            if (category == "All" || file["category"] == category) {
                val fileView = layoutInflater.inflate(R.layout.item_file, fileListContainer, false)
                val fileName = fileView.findViewById<TextView>(R.id.file_name)
                val fileCount = fileView.findViewById<TextView>(R.id.file_count)
                val fileStatus = fileView.findViewById<TextView>(R.id.file_status)
                val downloadBtn = fileView.findViewById<Button>(R.id.download_btn)

                fileName.text = file["name"]
                fileCount.text = file["count"]
                fileStatus.text = file["status"]

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
