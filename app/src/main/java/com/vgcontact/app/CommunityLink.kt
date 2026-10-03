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

    fun open(activity: Activity) {
        val link = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: DEFAULT_LINK
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: Exception) {
            Toast.makeText(activity, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
