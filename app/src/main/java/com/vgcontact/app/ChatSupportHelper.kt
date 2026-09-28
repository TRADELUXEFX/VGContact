package com.vgcontact.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Adds a single floating help button to every screen, instead of each
 * activity declaring and wiring its own copy in XML. Call attach(this)
 * once from onCreate, after setContentView, on any activity whose root view
 * is a FrameLayout (all main tab screens qualify).
 *
 * Tapping it opens a WhatsApp chat with support. Terms & Conditions and
 * Privacy Policy live on the Profile screen (Help & legal card).
 */
object ChatSupportHelper {

    fun attach(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
            .getChildAt(0) as? FrameLayout ?: return

        // Avoid adding a duplicate if attach() is somehow called twice.
        if (root.findViewById<ImageButton>(R.id.global_chat_btn) != null) return

        val sizePx = (56 * activity.resources.displayMetrics.density).toInt()
        val marginPx = (16 * activity.resources.displayMetrics.density).toInt()
        val paddingPx = (14 * activity.resources.displayMetrics.density).toInt()

        val chatBtn = ImageButton(activity).apply {
            id = R.id.global_chat_btn
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            setBackgroundResource(R.drawable.row_icon_circle_background)
            setImageResource(R.drawable.ic_chat)
            setColorFilter(ContextCompat.getColor(activity, R.color.vg_green_dark))
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            elevation = 4f * activity.resources.displayMetrics.density
            contentDescription = "Contact us"
            setOnClickListener { openWhatsApp(activity) }
        }

        root.addView(chatBtn)
    }

    fun openWhatsApp(activity: Activity) {
        try {
            val message = Uri.encode("Hi VGContact, I need help with...")
            val uri = Uri.parse("https://wa.me/${BuyKeysActivity.SUPPORT_WHATSAPP}?text=$message")
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(activity, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
