package com.vgcontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Buy-keys paywall, opened from the "Get more" button on Home. Only for
 * buying keys - earning them by reposting stays on RepostActivity.
 * No in-app payment yet, so the buy button opens a WhatsApp chat with
 * support (SUPPORT_WHATSAPP) with the chosen pack prefilled.
 */
class BuyKeysActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    private lateinit var buyBtn: com.google.android.material.button.MaterialButton
    private val packViews = mutableMapOf<Int, View>()
    private var selectedKeys = 5

    companion object {
        // 09110321143 in international format (Nigeria +234, no leading 0).
        const val SUPPORT_WHATSAPP = "2349110321143"
        // Pack size -> price in naira (10 keys is discounted).
        private val PACK_PRICES = mapOf(1 to 1000, 5 to 5000, 10 to 8000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_buy_keys)
        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green_dark)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        findViewById<View>(R.id.buy_back_btn).setOnClickListener { finish() }

        buyBtn = findViewById(R.id.buy_keys_whatsapp_btn)
        packViews[1] = findViewById(R.id.buy_pack_1)
        packViews[5] = findViewById(R.id.buy_pack_5)
        packViews[10] = findViewById(R.id.buy_pack_10)
        for ((keys, view) in packViews) {
            view.setOnClickListener { selectPack(keys) }
        }
        selectPack(selectedKeys)

        buyBtn.setOnClickListener { openWhatsApp() }
    }

    private fun selectPack(keys: Int) {
        selectedKeys = keys
        for ((k, view) in packViews) {
            view.setBackgroundResource(
                if (k == keys) R.drawable.buy_pack_selected else R.drawable.buy_pack_unselected
            )
        }
        buyBtn.text = "Get $keys ${if (keys == 1) "key" else "keys"}"
    }

    private fun openWhatsApp() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val username = sessionManager.getUsername()
            val total = String.format(java.util.Locale.US, "%,d", (PACK_PRICES[selectedKeys] ?: (selectedKeys * 1000)))
            val message = "Hi, I'd like to buy $selectedKeys VGContact " +
                (if (selectedKeys == 1) "key" else "keys") + " (₦$total)." +
                if (username.isNullOrBlank()) "" else " My username is $username."
            intent.data = Uri.parse("https://wa.me/$SUPPORT_WHATSAPP?text=${Uri.encode(message)}")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val userId = sessionManager.getUserId()
        if (userId.isNullOrBlank()) return
        val balanceText = findViewById<TextView>(R.id.buy_key_balance_text)
        Thread {
            SupabaseClient.fetchKeyBalance(userId) { success, balance ->
                runOnUiThread {
                    if (success) {
                        balanceText.text = "$balance ${if (balance == 1) "key" else "keys"} left"
                    }
                }
            }
        }.start()
    }
}
