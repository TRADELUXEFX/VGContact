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

    // Calls a Postgres function (RPC) with the anon key and returns the
    // parsed JSON array, or null on any failure. All table access for
    // private data now goes through these functions; the database no longer
    // lets the anon key read or write those tables directly.
    // Last failure reason from rpc(), so screens can show something more
    // useful than a guess. Cleared at the start of every call.
    @Volatile
    var lastError: String? = null
        private set

    private fun rpc(name: String, params: JSONObject): org.json.JSONArray? {
        lastError = null
        if (!isConfigured()) {
            lastError = "App is missing its server settings (build has no Supabase URL/key)."
            return null
        }
        return try {
            val request = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/$name")
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .post(params.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    org.json.JSONArray(if (body.isBlank()) "[]" else body)
                } else {
                    if (body.contains("ACCOUNT_BANNED")) {
                        BannedHandler.trigger(if (params.isNull("p_phone")) null else params.optString("p_phone").ifBlank { null })
                    }
                    lastError = "HTTP ${response.code}: " + body.take(160)
                    null
                }
            }
        } catch (e: Exception) {
            lastError = "Network error: " + (e.message ?: e.javaClass.simpleName)
            null
        }
    }

    // No password, no Supabase auth session. Identity is the android_id.
    // register_or_fetch_user (server side) either returns the existing
    // account for this device or creates one and drops it into a contact
    // group. A duplicate username/phone makes the call fail, which the
    // caller shows as "may already be registered".
    fun registerOrFetchUser(
        androidId: String,
        username: String,
        phone: String,
        referredBy: String?,
        callback: (Boolean, JSONObject?) -> Unit
    ) {
        val params = JSONObject().apply {
            put("p_android_id", androidId)
            put("p_username", username)
            put("p_phone", phone)
            put("p_referred_by", if (referredBy.isNullOrBlank()) JSONObject.NULL else referredBy)
        }
        val arr = rpc("register_or_fetch_user", params)
        if (arr != null && arr.length() > 0) callback(true, arr.getJSONObject(0)) else callback(false, null)
    }

    // Looks an account up by phone number and confirms this device's
    // android_id matches the one on file (no OTP, no password). The server
    // does the comparison and only returns account data when the device
    // matches, so a different device learns nothing about the account.
    fun fetchUserByPhone(
        phone: String,
        androidId: String,
        callback: (found: Boolean, deviceMatches: Boolean, user: JSONObject?) -> Unit
    ) {
        val params = JSONObject().apply {
            put("p_phone", phone)
            put("p_android_id", androidId)
        }
        val arr = rpc("login_by_phone", params)
        if (arr == null || arr.length() == 0) {
            callback(false, false, null)
            return
        }
        val row = arr.getJSONObject(0)
        val exists = row.optBoolean("account_exists", false)
        val matches = row.optBoolean("device_matches", false)
        callback(exists, matches, if (exists && matches) row else null)
    }

    // Is this account / phone / device banned? Never errors on a ban itself:
    // callback(null, null) means "couldn't tell" (offline / server error).
    fun getBanStatus(userId: String?, phone: String?, androidId: String?, callback: (Boolean?, String?) -> Unit) {
        val params = JSONObject().apply {
            put("p_user_id", userId ?: JSONObject.NULL)
            put("p_phone", phone ?: JSONObject.NULL)
            put("p_android_id", androidId ?: JSONObject.NULL)
        }
        val arr = rpc("get_ban_status", params)
        if (arr == null || arr.length() == 0) { callback(null, null); return }
        val row = arr.getJSONObject(0)
        callback(row.optBoolean("banned", false), if (row.isNull("reason")) null else row.optString("reason").ifBlank { null })
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
        val arr = rpc("get_my_unlocked_group_ids", JSONObject().apply { put("p_user_id", userId) })
        if (arr == null) {
            callback(false, emptySet())
            return
        }
        val ids = mutableSetOf<String>()
        for (i in 0 until arr.length()) ids.add(arr.getJSONObject(i).optString("group_id"))
        callback(true, ids)
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

    // Current key balance. New users start with 3 (set server-side); this
    // reads whatever the DB has now, since it may have changed since login.
    fun fetchKeyBalance(userId: String, callback: (Boolean, Int) -> Unit) {
        val arr = rpc("get_my_balance", JSONObject().apply { put("p_user_id", userId) })
        if (arr != null && arr.length() > 0) {
            callback(true, arr.getJSONObject(0).optInt("key_balance", 0))
        } else {
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

    // Unlocks several groups at once, all or nothing, via the
    // spend_keys_unlock_groups RPC (1 key per NEW group, already-unlocked
    // groups are free). message is one of: "OK", "NO_KEYS", "GROUP_NOT_FOUND",
    // "GROUP_NOT_FULL", "USER_NOT_FOUND", or "REQUEST_FAILED" / "EXCEPTION"
    // (e.g. the SQL hasn't been added to Supabase yet).
    fun spendKeysToUnlockGroups(userId: String, groupIds: List<String>, callback: (Boolean, String, Int) -> Unit) {
        if (!isConfigured()) {
            callback(false, "NOT_CONFIGURED", 0)
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/rpc/spend_keys_unlock_groups"
            val body = JSONObject().apply {
                put("p_user_id", userId)
                put("p_group_ids", org.json.JSONArray(groupIds))
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
    // button. The server decides what "today" is (UTC).
    fun fetchTodayRepostStatus(userId: String, callback: (Boolean, String?) -> Unit) {
        val arr = rpc("get_today_repost_status", JSONObject().apply { put("p_user_id", userId) })
        if (arr == null) {
            callback(false, null)
            return
        }
        callback(true, if (arr.length() > 0) arr.getJSONObject(0).optString("status") else null)
    }

    // A notification the user can see: their own targeted rows (a group
    // they're in became full) plus every broadcast row (any group filled).
    data class AppNotification(
        val id: String,
        val title: String,
        val body: String,
        val createdAt: String,
        val isRead: Boolean,
        val action: String? = null,
        val target: String? = null
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
                                isRead = row.optBoolean("is_read", false),
                                action = row.optString("action", "").ifBlank { null },
                                target = row.optString("target", "").ifBlank { null }
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

    // Saves this device's current FCM token onto the user's row so the
    // send-push Edge Function knows where to deliver notifications. Called
    // on login/register and whenever FCM refreshes the token. Best-effort.
    fun saveFcmToken(userId: String, token: String, callback: (Boolean) -> Unit) {
        val params = JSONObject().apply {
            put("p_user_id", userId)
            put("p_token", token)
        }
        // Network calls must not run on the main thread, and the callers
        // (cold start, post-login flush) are on it - so hop off here.
        kotlin.concurrent.thread {
            callback(rpc("save_fcm_token", params) != null)
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

    // Referral milestones: every 10 referrals = 1 key. The claim_referral_keys
    // RPC counts this user's qualifying referrals, adds any keys not yet
    // paid (safe to call repeatedly - it never pays the same key twice) and
    // returns (referrals, keys_earned, keys_added). Run off the UI thread.
    fun claimReferralKeys(userId: String, callback: (Boolean, Int, Int) -> Unit) {
        if (!isConfigured() || userId.isBlank()) {
            callback(false, 0, 0)
            return
        }

        try {
            val url = "$supabaseUrl/rest/v1/rpc/claim_referral_keys"
            val body = JSONObject().apply { put("p_user_id", userId) }
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
                        callback(true, row.optInt("referrals", 0), row.optInt("keys_added", 0))
                    } else {
                        callback(false, 0, 0)
                    }
                } else {
                    callback(false, 0, 0)
                }
            }
        } catch (e: Exception) {
            callback(false, 0, 0)
        }
    }

    // Date registered + referred by for accounts that logged in before
    // those were being saved (see SessionManager.saveRegistrationFrom).
    fun fetchUserProfile(userId: String, callback: (Boolean, JSONObject?) -> Unit) {
        if (userId.isBlank()) {
            callback(false, null)
            return
        }
        val arr = rpc("get_my_profile", JSONObject().apply { put("p_user_id", userId) })
        if (arr != null && arr.length() > 0) callback(true, arr.getJSONObject(0)) else callback(false, null)
    }
}
