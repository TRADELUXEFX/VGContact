package com.vgcontact.app

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vgkontact_session", Context.MODE_PRIVATE)

    fun saveUsername(username: String) {
        prefs.edit().putString("username", username).apply()
    }

    fun getUsername(): String? {
        return prefs.getString("username", null)
    }

    fun saveUserId(userId: String) {
        prefs.edit().putString("user_id", userId).apply()
    }

    fun getUserId(): String? {
        return prefs.getString("user_id", null)
    }

    fun savePhone(phone: String) {
        prefs.edit().putString("phone", phone).apply()
    }

    fun getPhone(): String? {
        return prefs.getString("phone", null)
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
        return getUsername() != null
    }

}
