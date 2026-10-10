package com.vgcontact.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * The community (WhatsApp group) link. The live value is the
 * 'community_link' row in the app_settings table, so it can be changed
 * without a new APK. The last value fetched is cached on the phone, and
 * DEFAULT_LINK is used until the first fetch succeeds (or when offline).
 */
object CommunityLink {
    const val DEFAULT_LINK = "https://chat.whatsapp.com/LuTImF6uCmnDexMnnIDkGo"
    private const val PREFS = "vg_settings"
    private const val KEY = "community_link"

    /** Call when a screen opens; the new link is used for the next tap. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        thread {
            SupabaseClient.fetchSetting("community_link") { value ->
                if (value != null && value.startsWith("http")) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY, value).apply()
                }
            }
        }
    }

    /** Home's single bundle call brings the link; cache it (no extra call). */
    fun save(context: Context, value: String?) {
        if (value != null && value.startsWith("http")) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, value).apply()
        }
    }

    private const val JOINED_KEY = "joined_link"

    private fun currentLink(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: DEFAULT_LINK

    /**
     * True once the user tapped Join for the community that is live now. When the admin changes
     * `community_link` (a new community), this turns false again and the Home banner comes back.
     * Kept on the phone only (it survives logout, not a reinstall).
     */
    fun hasJoined(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(JOINED_KEY, null) == currentLink(context)

    fun open(activity: Activity) {
        val link = currentLink(activity)
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(JOINED_KEY, link).apply()
        } catch (e: Exception) {
            Toast.makeText(activity, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
