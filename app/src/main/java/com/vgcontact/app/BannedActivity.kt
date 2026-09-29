package com.vgcontact.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Shown when the server answers ACCOUNT_BANNED (see BannedHandler below).
 * The user has already been signed out; they can only contact support or close the app.
 */
class BannedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(32), px(32), px(32), px(32))
            setBackgroundColor(ContextCompat.getColor(this@BannedActivity, android.R.color.white))
        }
        val title = TextView(this).apply {
            text = "Account suspended"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@BannedActivity, R.color.vg_dark))
            gravity = Gravity.CENTER
        }
        val message = TextView(this).apply {
            text = "Your account has been suspended. If you think this is a mistake, contact support on WhatsApp."
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@BannedActivity, R.color.vg_dark))
            gravity = Gravity.CENTER
            setPadding(0, px(12), 0, px(28))
        }
        val support = Button(this).apply {
            text = "Contact support on WhatsApp"
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@BannedActivity, android.R.color.white))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@BannedActivity, R.color.vg_green))
            setOnClickListener { openSupport() }
        }
        val close = Button(this).apply {
            text = "Close"
            isAllCaps = false
            setOnClickListener { finishAffinity() }
        }
        root.addView(title)
        root.addView(message)
        root.addView(support, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun openSupport() {
        try {
            val uri = Uri.parse("https://wa.me/${BuyKeysActivity.SUPPORT_WHATSAPP}?text=" +
                Uri.encode("Hello, my VGContact account was suspended."))
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishAffinity()
    }

    override fun onDestroy() {
        super.onDestroy()
        BannedHandler.reset()
    }
}

/** Single place that reacts to "this account is banned" from any server call. */
object BannedHandler {
    @Volatile private var launching = false

    fun trigger() {
        val app = VGApp.instance ?: return
        if (launching) return
        launching = true
        SessionManager(app).logout()
        app.startActivity(
            Intent(app, BannedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    fun reset() { launching = false }
}
