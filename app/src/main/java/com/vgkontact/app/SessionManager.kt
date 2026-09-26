package com.vgkontact.app

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vgkontact_session", Context.MODE_PRIVATE)

    fun saveToken(token: String) {
        prefs.edit().putString("auth_token", token).apply()
    }

    fun getToken(): String? {
        return prefs.getString("auth_token", null)
    }

    fun saveEmail(email: String) {
        prefs.edit().putString("user_email", email).apply()
    }

    fun getEmail(): String? {
        return prefs.getString("user_email", null)
    }

    fun saveTotalDownloads(count: Int) {
        prefs.edit().putInt("total_downloads", count).apply()
    }

    fun getTotalDownloads(): Int {
        return prefs.getInt("total_downloads", 0)
    }

    fun saveTotalReposts(count: Int) {
        prefs.edit().putInt("total_reposts", count).apply()
    }

    fun getTotalReposts(): Int {
        return prefs.getInt("total_reposts", 0)
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    fun isLoggedIn(): Boolean {
        return getToken() != null
    }

}
