package com.vgcontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CommunityActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_community)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        // Join WhatsApp button
        val joinBtn = findViewById<Button>(R.id.join_whatsapp_btn)
        joinBtn.setOnClickListener {
            CommunityLink.open(this)
        }
        CommunityLink.refresh(this)

        // Chat support button
        val chatBtn = findViewById<Button>(R.id.chat_support_btn)
        chatBtn.setOnClickListener {
            startActivity(Intent(this, ChatSupportActivity::class.java))
        }

        setupBottomNav()
        FloatingContactHelper.attach(this)
    }


    private fun setupBottomNav() {
        BottomNavHelper.setup(this, BottomNavHelper.Tab.HOME)
    }

}
