package com.vgcontact.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class SessionManager(private val context: Context) {
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

    // The account's private secret (from register_account / login_account). Every server call
    // that acts for this user sends it along with the user id; without it the server refuses.
    fun saveSecret(secret: String) {
        prefs.edit().putString("secret", secret).apply()
    }

    fun getSecret(): String? {
        return prefs.getString("secret", null)?.ifBlank { null }
    }

    fun savePhone(phone: String) {
        prefs.edit().putString("phone", phone).apply()
    }

    fun getPhone(): String? {
        return prefs.getString("phone", null)
    }

    // Date registered + who referred this user, saved from the `users` row
    // at register/login so the Profile shows real values instead of
    // recalculating. A JSON null must become "nothing" - optString() would
    // turn it into the text "null".
    fun saveRegistrationFrom(user: JSONObject) {
        val createdAt = if (user.isNull("created_at")) "" else user.optString("created_at", "")
        val referredBy = if (user.isNull("referred_by")) "" else user.optString("referred_by", "").trim()
        prefs.edit()
            .putString("created_at", createdAt)
            .putString("referred_by", referredBy)
            .apply()
    }

    // null = never saved yet (e.g. logged in before this was added).
    fun getCreatedAt(): String? {
        return prefs.getString("created_at", null)?.ifBlank { null }
    }

    fun getReferredBy(): String? {
        return prefs.getString("referred_by", null)?.ifBlank { null }
    }

    // Forget the saved "referred by" (the server found no such referrer).
    fun clearReferredBy() {
        prefs.edit().remove("referred_by").remove("referrer_phone").apply()
    }

    // Phone number of the person who referred this user (null = not known yet).
    fun saveReferrerPhone(phone: String) {
        prefs.edit().putString("referrer_phone", phone).apply()
    }

    fun getReferrerPhone(): String? {
        return prefs.getString("referrer_phone", null)?.ifBlank { null }
    }

    fun logout() {
        prefs.edit().clear().apply()
        SyncPrefs.clear(context)
        InactivityWarningWorker.cancel(context)
    }

    // Logged in = a saved username AND a secret. An account saved by an older build has no
    // secret, so it counts as logged out and the user logs in again once to get one.
    fun isLoggedIn(): Boolean {
        return getUsername() != null && getSecret() != null
    }

}
