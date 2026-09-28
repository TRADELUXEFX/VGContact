package com.vgcontact.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object SupabaseClient {
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY
    private val client = OkHttpClient()

    // True only if the app was actually built with real Supabase credentials.
    // If SUPABASE_URL / SUPABASE_ANON_KEY were blank at build time (missing
    // local.properties or missing CI secrets), every call below fails cleanly
    // through this check instead of crashing on a malformed URL.
    private fun isConfigured(): Boolean {
        return supabaseUrl.isNotBlank() && anonKey.isNotBlank() && supabaseUrl.startsWith("http")
    }

    // Cheap connectivity check before spending a request on a device
    // that's plainly offline (e.g. airplane mode).
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // No password, no Supabase auth session. Identity is the android_id;
    // registering just upserts a row in `users` keyed on it (anon key + RLS,
    // same pattern as the rest of the app's writes).
    fun registerOrFetchUser(
        androidId: String,
        username: String,
        phone: String,
        referredBy: String?,
        callback: (Boolean, JSONObject?) -> Unit
    ) {
        if (!isConfigured()) {
            callback(false, null)
            return
        }

        try {
            // 1. Check if this android_id is already registered.
            val lookupUrl = "$supabaseUrl/rest/v1/users?android_id=eq.$androidId&select=*"
            val lookupRequest = Request.Builder()
                .url(lookupUrl)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .get()
                .build()

            client.newCall(lookupRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        callback(true, arr.getJSONObject(0))
                        return
                    }
                }
            }

            // 2. Not found - create it.
            val insertUrl = "$supabaseUrl/rest/v1/users"
            val body = JSONObject().apply {
                put("android_id", androidId)
                put("username", username)
                put("phone", phone)
                if (!referredBy.isNullOrBlank()) put("referred_by", referredBy)
            }

            val insertRequest = Request.Builder()
                .url(insertUrl)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(insertRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    val newUser = if (arr.length() > 0) arr.getJSONObject(0) else null

                    // Drop the brand-new user into a contact group. Best-effort:
                    // if this fails, registration still succeeded and the user
                    // just won't be grouped yet - not worth failing signup over.
                    val newUserId = newUser?.optString("id")
                    if (!newUserId.isNullOrBlank()) {
                        addUserToGroup(newUserId) { _, _ -> }
                    }

                    callback(true, newUser)
                } else {
                    callback(false, null)
                }
            }
        } catch (e: Exception) {
            callback(false, null)
        }
    }

    // Places a just-registered user into the oldest open contact group
    // (or a fresh one) via the add_user_to_group RPC. Fire-and-forget from
    // registerOrFetchUser; called synchronously (same thread) since
    // registerOrFetchUser already runs off the UI thread.
    fun addUserToGroup(userId: String, callback: (Boolean, Int) -> Unit) {
        if (!isConfigured()) {
            callback(false, 0)
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/rpc/add_user_to_group"
            val body = JSONObject().apply {
                put("p_user_id", userId)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    if (arr.length() > 0) {
                        callback(true, arr.getJSONObject(0).optInt("member_position", 0))
                    } else {
                        callback(false, 0)
                    }
                } else {
                    callback(false, 0)
                }
            }
        } catch (e: Exception) {
            callback(false, 0)
        }
    }

    // Looks an account up by phone number and confirms this device's
    // android_id matches the one on file - that's what makes login
    // "seamless" (no OTP, no password): if the phone number was
    // registered from *this* physical device, we trust it and log
    // them straight in. If the numbers match but the android_id
    // doesn't (different phone), we refuse and report a mismatch so
    // the caller can show the right message instead of silently
    // logging in the wrong device.
    fun fetchUserByPhone(
        phone: String,
        androidId: String,
        callback: (found: Boolean, deviceMatches: Boolean, user: JSONObject?) -> Unit
    ) {
        if (!isConfigured()) {
            callback(false, false, null)
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/users?phone=eq.$phone&select=*"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    if (arr.length() > 0) {
                        val user = arr.getJSONObject(0)
                        val storedAndroidId = user.optString("android_id", "")
                        val matches = storedAndroidId.isNotBlank() && storedAndroidId == androidId
                        callback(true, matches, user)
                    } else {
                        callback(false, false, null)
                    }
                } else {
                    callback(false, false, null)
                }
            }
        } catch (e: Exception) {
            callback(false, false, null)
        }
    }

    // Published (full) contact groups, newest-filled first.
    fun fetchGroups(callback: (Boolean, org.json.JSONArray?) -> Unit) {
        if (!isConfigured()) {
            callback(false, null)
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/contact_groups?is_published=eq.true&select=*&order=filled_at.desc"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    callback(true, arr)
                } else {
                    callback(false, null)
                }
            }
        } catch (e: Exception) {
            callback(false, null)
        }
    }

    // Set of group_ids this user has already unlocked.
    fun fetchUnlockedGroupIds(userId: String, callback: (Boolean, Set<String>) -> Unit) {
        if (!isConfigured()) {
            callback(false, emptySet())
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/group_unlocks?user_id=eq.$userId&select=group_id"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    val ids = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        ids.add(arr.getJSONObject(i).optString("group_id"))
                    }
                    callback(true, ids)
                } else {
                    callback(false, emptySet())
                }
            }
        } catch (e: Exception) {
            callback(false, emptySet())
        }
    }

    // The 3 members (username + phone) of an unlocked group, in position
    // order, via the get_group_contacts RPC. Server re-checks the unlock
    // itself (SECURITY DEFINER), so this fails with NOT_UNLOCKED if called
    // for a group this user hasn't paid for.
    fun getGroupContacts(userId: String, groupId: String, callback: (Boolean, List<Pair<String, String>>) -> Unit) {
        if (!isConfigured()) {
            callback(false, emptyList())
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/rpc/get_group_contacts"
            val body = JSONObject().apply {
                put("p_user_id", userId)
                put("p_group_id", groupId)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    val contacts = mutableListOf<Pair<String, String>>()
                    for (i in 0 until arr.length()) {
                        val row = arr.getJSONObject(i)
                        contacts.add(row.optString("username") to row.optString("phone"))
                    }
                    callback(true, contacts)
                } else {
                    callback(false, emptyList())
                }
            }
        } catch (e: Exception) {
            callback(false, emptyList())
        }
    }

    // Current key balance. New users start with 3 (set server-side by the
    // users.key_balance default); this just reads whatever the DB has now,
    // since it may have changed since login (nightly verification, admin
    // top-up, etc).
    fun fetchKeyBalance(userId: String, callback: (Boolean, Int) -> Unit) {
        if (!isConfigured()) {
            callback(false, 0)
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/users?id=eq.$userId&select=key_balance"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    if (arr.length() > 0) {
                        callback(true, arr.getJSONObject(0).optInt("key_balance", 0))
                    } else {
                        callback(false, 0)
                    }
                } else {
                    callback(false, 0)
                }
            }
        } catch (e: Exception) {
            callback(false, 0)
        }
    }

    // Records today's repost attempt as 'pending'. This does NOT grant a
    // key - keys are only added once the repost is manually cross-checked
    // against WhatsApp status viewers that night and marked 'verified' in
    // the daily_reposts table (see vgcontact_keys_schema.sql). One attempt
    // per calendar day is enforced both here (via the RPC's own check) and
    // by a unique index on (user_id, repost_date), so a double-tap can't
    // queue two pending rows.
    //
    // message is one of: "OK", "ALREADY_REPOSTED_TODAY", or a raw error.
    fun submitDailyRepost(userId: String, callback: (Boolean, String) -> Unit) {
        if (!isConfigured()) {
            callback(false, "NOT_CONFIGURED")
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/rpc/submit_daily_repost"
            val body = JSONObject().apply {
                put("p_user_id", userId)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    if (arr.length() > 0) {
                        val row = arr.getJSONObject(0)
                        callback(row.optBoolean("success", false), row.optString("message", "OK"))
                    } else {
                        callback(false, "EMPTY_RESPONSE")
                    }
                } else {
                    callback(false, "REQUEST_FAILED")
                }
            }
        } catch (e: Exception) {
            callback(false, "EXCEPTION")
        }
    }

    // Spends one key to unlock a contact group, via the spend_key_unlock_group
    // RPC so the balance check and the deduction happen atomically on the
    // server - two rapid taps can't both succeed off a stale balance read.
    //
    // message is one of: "OK", "NO_KEYS", "ALREADY_UNLOCKED",
    // "GROUP_NOT_FULL", "GROUP_NOT_FOUND", "USER_NOT_FOUND", or a raw error.
    fun spendKeyToUnlockGroup(userId: String, groupId: String, callback: (Boolean, String, Int) -> Unit) {
        if (!isConfigured()) {
            callback(false, "NOT_CONFIGURED", 0)
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/rpc/spend_key_unlock_group"
            val body = JSONObject().apply {
                put("p_user_id", userId)
                put("p_group_id", groupId)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    if (arr.length() > 0) {
                        val row = arr.getJSONObject(0)
                        callback(
                            row.optBoolean("success", false),
                            row.optString("message", "OK"),
                            row.optInt("remaining_keys", 0)
                        )
                    } else {
                        callback(false, "EMPTY_RESPONSE", 0)
                    }
                } else {
                    callback(false, "REQUEST_FAILED", 0)
                }
            }
        } catch (e: Exception) {
            callback(false, "EXCEPTION", 0)
        }
    }

    // Whether today's daily repost has already been submitted (regardless
    // of verified/pending/rejected) - used to grey out the Repost Today
    // button after the first tap of the day instead of relying only on
    // the server rejecting a second attempt.
    fun fetchTodayRepostStatus(userId: String, callback: (Boolean, String?) -> Unit) {
        if (!isConfigured()) {
            callback(false, null)
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/daily_reposts?user_id=eq.$userId&repost_date=eq.${todayIsoDate()}&select=status"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    if (arr.length() > 0) {
                        callback(true, arr.getJSONObject(0).optString("status"))
                    } else {
                        callback(true, null)
                    }
                } else {
                    callback(false, null)
                }
            }
        } catch (e: Exception) {
            callback(false, null)
        }
    }

    private fun todayIsoDate(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    // A notification the user can see: their own targeted rows (a group
    // they're in became full) plus every broadcast row (any group filled).
    data class AppNotification(
        val id: String,
        val title: String,
        val body: String,
        val createdAt: String,
        val isRead: Boolean
    )

    // Up to the 50 most recent notifications visible to this user, via the
    // fetch_notifications RPC (SECURITY DEFINER - direct table reads are
    // blocked by RLS, see notifications_schema.sql).
    fun fetchNotifications(userId: String, callback: (Boolean, List<AppNotification>) -> Unit) {
        if (!isConfigured()) {
            callback(false, emptyList())
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/rpc/fetch_notifications"
            val body = JSONObject().apply {
                put("p_user_id", userId)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    val notifications = mutableListOf<AppNotification>()
                    for (i in 0 until arr.length()) {
                        val row = arr.getJSONObject(i)
                        notifications.add(
                            AppNotification(
                                id = row.optString("id"),
                                title = row.optString("title"),
                                body = row.optString("body"),
                                createdAt = row.optString("created_at"),
                                isRead = row.optBoolean("is_read", false)
                            )
                        )
                    }
                    callback(true, notifications)
                } else {
                    callback(false, emptyList())
                }
            }
        } catch (e: Exception) {
            callback(false, emptyList())
        }
    }

    // Upserts this device's current FCM token onto the user's row, so the
    // send-push Edge Function knows where to deliver notifications for
    // them. Called on login/register and whenever FCM hands us a refreshed
    // token (see VgFirebaseMessagingService.onNewToken). Best-effort: a
    // failure here just means push delivery is stale until the next
    // successful call - never worth interrupting the user over.
    fun saveFcmToken(userId: String, token: String, callback: (Boolean) -> Unit) {
        if (!isConfigured()) {
            callback(false)
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/users?id=eq.$userId"
            val body = JSONObject().apply {
                put("fcm_token", token)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .patch(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                callback(response.isSuccessful)
            }
        } catch (e: Exception) {
            callback(false)
        }
    }

    // Marks every notification currently visible to this user as read
    // (called when they open the Notifications screen), via the
    // mark_notifications_read RPC.
    fun markNotificationsRead(userId: String, callback: (Boolean) -> Unit) {
        if (!isConfigured()) {
            callback(false)
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/rpc/mark_notifications_read"
            val body = JSONObject().apply {
                put("p_user_id", userId)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                callback(response.isSuccessful)
            }
        } catch (e: Exception) {
            callback(false)
        }
    }

    // One row of the leaderboard. rank is shared on ties (1, 2, 2, 4).
    data class LeaderboardEntry(
        val rank: Int,
        val userId: String,
        val username: String,
        val score: Int,
        val isMe: Boolean
    )

    // Top players plus this user's own row (even if they are outside the
    // top), via the get_leaderboard RPC (SECURITY DEFINER - it returns only
    // username + numbers, never phone/android_id).
    //
    // metric: "streak" | "reposts" | "referrals"
    // period: "week" | "month" | "all"
    fun fetchLeaderboard(
        userId: String,
        metric: String,
        period: String,
        limit: Int,
        callback: (Boolean, List<LeaderboardEntry>) -> Unit
    ) {
        if (!isConfigured()) {
            callback(false, emptyList())
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/rpc/get_leaderboard"
            val body = JSONObject().apply {
                put("p_user_id", userId)
                put("p_metric", metric)
                put("p_period", period)
                put("p_limit", limit)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    val entries = mutableListOf<LeaderboardEntry>()
                    for (i in 0 until arr.length()) {
                        val row = arr.getJSONObject(i)
                        entries.add(
                            LeaderboardEntry(
                                rank = row.optInt("rank", 0),
                                userId = row.optString("user_id"),
                                username = row.optString("username"),
                                score = row.optInt("score", 0),
                                isMe = row.optBoolean("is_me", false)
                            )
                        )
                    }
                    callback(true, entries)
                } else {
                    callback(false, emptyList())
                }
            }
        } catch (e: Exception) {
            callback(false, emptyList())
        }
    }

}
