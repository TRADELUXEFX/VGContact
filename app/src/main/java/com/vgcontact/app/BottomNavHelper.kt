package com.vgcontact.app

import android.app.Activity
import android.content.Intent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Wires the floating pill bottom nav (bottom_nav_bar.xml) the same way
 * on every screen. Four tabs: Home, Repost, Referral, Profile.
 */
object BottomNavHelper {

    enum class Tab { HOME, REPOST, REFERRAL, PROFILE }

    fun setup(activity: Activity, selected: Tab) {
        // Tab screens have no Back arrow. The phone's Back goes to Home
        // (Home itself exits the app as usual).
        if (selected != Tab.HOME && activity is androidx.activity.ComponentActivity) {
            activity.onBackPressedDispatcher.addCallback(
                activity,
                object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        activity.startActivity(Intent(activity, HomeActivity::class.java))
                        activity.finish()
                    }
                }
            )
        }
        val tabs = listOf(
            Tab.HOME to Triple(R.id.navHomeTab, R.id.navHomeIcon, R.id.navHomeLabel),
            Tab.REPOST to Triple(R.id.navRepostTab, R.id.navRepostIcon, R.id.navRepostLabel),
            Tab.REFERRAL to Triple(R.id.navReferralTab, R.id.navReferralIcon, R.id.navReferralLabel),
            Tab.PROFILE to Triple(R.id.navProfileTab, R.id.navProfileIcon, R.id.navProfileLabel)
        )

        val activeColor = ContextCompat.getColor(activity, R.color.vg_green)
        val inactiveColor = ContextCompat.getColor(activity, R.color.text_muted)
        val activeTabBackground = ContextCompat.getDrawable(activity, R.drawable.nav_active_tab_background)

        for ((tab, ids) in tabs) {
            val (rowId, iconId, labelId) = ids
            val row = activity.findViewById<LinearLayout>(rowId) ?: continue
            val icon = activity.findViewById<ImageView>(iconId)
            val label = activity.findViewById<TextView>(labelId)

            val isSelected = tab == selected
            icon?.setColorFilter(if (isSelected) activeColor else inactiveColor)
            label?.setTextColor(if (isSelected) activeColor else inactiveColor)

            if (isSelected) {
                row.background = activeTabBackground
            } else {
                val outValue = android.util.TypedValue()
                activity.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, outValue, true
                )
                row.setBackgroundResource(outValue.resourceId)
            }

            row.setOnClickListener {
                if (tab == selected) return@setOnClickListener
                // Pending users can only go Home; everything else brings the verify sheet back.
                if (tab != Tab.HOME && PendingPrompt.showGate(activity)) return@setOnClickListener
                navigateTo(activity, tab)
            }
        }
    }

    private fun navigateTo(activity: Activity, tab: Tab) {
        val target = when (tab) {
            Tab.HOME -> HomeActivity::class.java
            Tab.REPOST -> RepostActivity::class.java
            Tab.REFERRAL -> ReferralActivity::class.java
            Tab.PROFILE -> ProfileActivity::class.java
        }
        activity.startActivity(Intent(activity, target))
        activity.finish()
    }
}
