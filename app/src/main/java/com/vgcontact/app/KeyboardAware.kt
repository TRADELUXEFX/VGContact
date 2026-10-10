package com.vgcontact.app

import android.app.Activity
import android.graphics.Rect
import android.view.View

/**
 * Screens with a floating bottom bar or a big header lose most of their space when the
 * keyboard opens. This hides the given views while the keyboard is up and puts them back
 * exactly as they were (visible or gone) when it closes.
 * Pair it with android:windowSoftInputMode="adjustResize" on the activity.
 */
object KeyboardAware {
    fun hideWhileKeyboardOpen(activity: Activity, vararg views: View?) {
        val targets = views.filterNotNull()
        if (targets.isEmpty()) return
        val root = activity.findViewById<View>(android.R.id.content)
        val saved = HashMap<View, Int>()
        var open = false
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val screen = root.rootView.height
            val nowOpen = screen > 0 && (screen - visible.bottom) > screen * 0.15f
            if (nowOpen == open) return@addOnGlobalLayoutListener
            open = nowOpen
            if (nowOpen) {
                targets.forEach { saved[it] = it.visibility; it.visibility = View.GONE }
            } else {
                targets.forEach { it.visibility = saved[it] ?: View.VISIBLE }
                saved.clear()
            }
        }
    }
}
