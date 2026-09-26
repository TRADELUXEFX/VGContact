package com.vgcontact.app

import android.app.Activity
import android.content.Intent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Wires the floating pill bottom nav (bottom_nav_bar.xml) the same way
 * on every screen, replacing the old per-activity setupBottomNav() that
 * used to configure a BottomNavigationView + bottom_nav_menu.xml.
 */
object BottomNavHelper {

    enum class Tab { HOME, REPOST, DOWNLOADS, COMMUNITY, PROFILE }

    fun setup(activity: Activity, selected: Tab) {
        val tabs = listOf(
            Tab.HOME to Triple(R.id.navHomeTab, R.id.navHomeIcon, R.id.navHomeLabel),
            Tab.REPOST to Triple(R.id.navRepostTab, R.id.navRepostIcon, R.id.navRepostLabel),
            Tab.DOWNLOADS to Triple(R.id.navDownloadsTab, R.id.navDownloadsIcon, R.id.navDownloadsLabel),
            Tab.COMMUNITY to Triple(R.id.navCommunityTab, R.id.navCommunityIcon, R.id.navCommunityLabel),
            Tab.PROFILE to Triple(R.id.navProfileTab, R.id.navProfileIcon, R.id.navProfileLabel)
        )

        val activeColor = ContextCompat.getColor(activity, R.color.vg_green)
        val inactiveColor = ContextCompat.getColor(activity, R.color.text_muted)

        for ((tab, ids) in tabs) {
            val (rowId, iconId, labelId) = ids
            val row = activity.findViewById<LinearLayout>(rowId) ?: continue
            val icon = activity.findViewById<ImageView>(iconId)
            val label = activity.findViewById<TextView>(labelId)

            val isSelected = tab == selected
            icon?.setColorFilter(if (isSelected) activeColor else inactiveColor)
            label?.setTextColor(if (isSelected) activeColor else inactiveColor)

            row.setOnClickListener {
                if (tab != selected) {
                    navigateTo(activity, tab)
                }
            }
        }
    }

    private fun navigateTo(activity: Activity, tab: Tab) {
        val target = when (tab) {
            Tab.HOME -> HomeActivity::class.java
            Tab.REPOST -> RepostActivity::class.java
            Tab.DOWNLOADS -> DownloadsActivity::class.java
            Tab.COMMUNITY -> CommunityActivity::class.java
            Tab.PROFILE -> ProfileActivity::class.java
        }
        activity.startActivity(Intent(activity, target))
        activity.finish()
    }
}
