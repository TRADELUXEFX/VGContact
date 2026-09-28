package com.vgcontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Buy-keys page, opened from the "Get more" button on Home. Only for
 * buying keys - earning them by reposting stays on RepostActivity.
 * No in-app payment yet, so buying opens the same WhatsApp chat the
 * Repost screen's buy button uses.
 */
class BuyKeysActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_buy_keys)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        findViewById<android.view.View>(R.id.buy_back_btn).setOnClickListener { finish() }

        findViewById<android.view.View>(R.id.buy_keys_whatsapp_btn).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("https://wa.me/?text=Hi%2C%20I%27d%20like%20to%20buy%20more%20VGContact%20keys")
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
            }
        }

        BottomNavHelper.setup(this, BottomNavHelper.Tab.HOME)
        ChatSupportHelper.attach(this)
    }

    override fun onResume() {
        super.onResume()
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return
        val balanceText = findViewById<TextView>(R.id.buy_key_balance_text)
        Thread {
            SupabaseClient.fetchKeyBalance(userId) { success, balance ->
                runOnUiThread {
                    if (success) balanceText.text = balance.toString()
                }
            }
        }.start()
    }
}
