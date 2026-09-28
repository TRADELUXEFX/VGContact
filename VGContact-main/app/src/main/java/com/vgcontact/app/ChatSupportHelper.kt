package com.vgcontact.app

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.content.ContextCompat

/**
 * Adds a single floating "chat support" button to every screen, instead of
 * each activity declaring and wiring its own copy in XML. Call attach(this)
 * once from onCreate, after setContentView, on any activity whose root view
 * is a FrameLayout (all main tab screens qualify).
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
            contentDescription = "Chat support"
            setOnClickListener {
                activity.startActivity(Intent(activity, ChatSupportActivity::class.java))
            }
        }

        root.addView(chatBtn)
    }
}
