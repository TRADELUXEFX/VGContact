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

    // Records today's repost attempt as 'pending'. It only becomes
    // 'verified' once the admin checks it against WhatsApp status viewers
    // and marks it in the daily_reposts table. One attempt
    // per calendar day is enforced both here (via the RPC's own check) and
    // by a unique index on (user_id, repost_date), so a double-tap can't
    // queue two pending rows.
    //
    // message is one of: "OK", "ALREADY_REPOSTED_TODAY", or a raw error.
    // kind = "verify" (pending sheet, first task) or "repost" (Repost screen, verified users).
    // Extra messages: ALREADY_VERIFIED, NOT_VERIFIED, REPOST_LIMIT_REACHED.
    fun submitDailyRepost(userId: String, kind: String = "repost", callback: (Boolean, String) -> Unit) {
        val arr = rpc("submit_daily_repost", JSONObject().apply {
            put("p_user_id", userId)
            put("p_kind", kind)
        })
        when {
            arr == null -> callback(false, if (!isConfigured()) "NOT_CONFIGURED" else "REQUEST_FAILED")
            arr.length() == 0 -> callback(false, "EMPTY_RESPONSE")
            else -> {
                val row = arr.getJSONObject(0)
                callback(row.optBoolean("success", false), row.optString("message", "OK"))
            }
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

    // Today's verification (first task) status: null, "pending", "verified" or "rejected".
    fun fetchTodayVerifyStatus(userId: String, callback: (Boolean, String?) -> Unit) {
        val arr = rpc("get_today_verify_status", JSONObject().apply { put("p_user_id", userId) })
        if (arr == null) {
            callback(false, null)
            return
        }
        callback(true, if (arr.length() > 0) arr.getJSONObject(0).optString("status") else null)
    }

    // ---- Home + Sync contacts (viewers model) ----

    // What the Home card shows. status is "pending" or "verified".
    // Free = the default group from signup. Extra = every other group.
    // Referral = a plain count (no cap).
    data class HomeData(
        val status: String,
        val freeCurrent: Int,
        val freeMax: Int,
        val extraCurrent: Int,
        val extraMax: Int,
        val referralCount: Int,
        val verifiedReposts: Int
    )

    fun fetchHome(userId: String, callback: (Boolean, HomeData?) -> Unit) {
        val arr = rpc("get_home", JSONObject().apply { put("p_user_id", userId) })
        if (arr == null || arr.length() == 0) {
            callback(false, null)
            return
        }
        val r = arr.getJSONObject(0)
        callback(
            true,
            HomeData(
                status = r.optString("status", "pending"),
                freeCurrent = r.optInt("free_current", 0),
                freeMax = r.optInt("free_max", 0),
                extraCurrent = r.optInt("extra_current", 0),
                extraMax = r.optInt("extra_max", 0),
                referralCount = r.optInt("referral_count", 0),
                verifiedReposts = r.optInt("verified_reposts", 0)
            )
        )
    }

    data class SyncContact(val phone: String, val name: String)

    // Every contact this user should have on the phone, already named by
    // the server. Blocking - call from a background thread. Null = failed.
    fun fetchSyncContacts(userId: String): List<SyncContact>? {
        val arr = rpc("get_sync_contacts", JSONObject().apply { put("p_user_id", userId) }) ?: return null
        val out = mutableListOf<SyncContact>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            out.add(SyncContact(r.optString("phone"), r.optString("display_name")))
        }
        return out
    }

    // One person in a referral list (see get_my_referrals). invitedCount =
    // how many people that person referred themselves.
    data class MyReferral(
        val userId: String,
        val username: String,
        val phone: String,
        val createdAt: String,
        val invitedCount: Int
    )

    // The people one user referred. targetUserId = null gives the caller's own
    // list; a downline member's id gives that person's list (the server only
    // answers for people inside the caller's own levels). Blocking - call from
    // a background thread. Null = failed.
    fun fetchMyReferrals(userId: String, targetUserId: String?): List<MyReferral>? {
        val params = JSONObject().apply {
            put("p_user_id", userId)
            put("p_target_user_id", if (targetUserId.isNullOrBlank()) JSONObject.NULL else targetUserId)
        }
        val arr = rpc("get_my_referrals", params) ?: return null
        val out = mutableListOf<MyReferral>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            out.add(
                MyReferral(
                    userId = r.optString("user_id"),
                    username = r.optString("username"),
                    phone = r.optString("phone"),
                    createdAt = r.optString("created_at"),
                    invitedCount = r.optInt("invited_count", 0)
                )
            )
        }
        return out
    }

    data class LeaderboardEntry(val username: String, val referralCount: Int, val isMe: Boolean, val phone: String = "")

    // Top 50 referrers by direct referrals, shown by phone number. Null = failed.
    fun fetchReferralLeaderboard(userId: String): List<LeaderboardEntry>? {
        val params = JSONObject().apply { put("p_user_id", userId) }
        val arr = rpc("get_referral_leaderboard_phone", params)
            ?: rpc("get_referral_leaderboard", params) ?: return null
        val out = mutableListOf<LeaderboardEntry>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            out.add(LeaderboardEntry(r.optString("username"), r.optInt("referral_count", 0), r.optBoolean("is_me", false), r.optString("phone")))
        }
        return out
    }

    // One row of the repost leaderboard. score = verified reposts. Username, plus phone when the server sends it (get_repost_leaderboard_phone).
    data class RepostBoardEntry(
        val rank: Int,
        val userId: String,
        val username: String,
        val score: Int,
        val isMe: Boolean,
        val phone: String = ""
    )

    // Repost leaderboard via get_leaderboard. metric = "reposts" (the only one the server accepts);
    // all-time, top 50 plus the caller's own row even when outside the top.
    // Blocking - call from a background thread. Null = failed.
    fun fetchRepostLeaderboard(userId: String, metric: String): List<RepostBoardEntry>? {
        val params = JSONObject().apply {
            put("p_user_id", userId)
            put("p_metric", metric)
            put("p_period", "all")
            put("p_limit", 50)
        }
        val arr = rpc("get_repost_leaderboard_phone", JSONObject().apply { put("p_user_id", userId) })
            ?: rpc("get_leaderboard", params) ?: return null
        val out = mutableListOf<RepostBoardEntry>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            out.add(
                RepostBoardEntry(
                    rank = r.optInt("rank", i + 1),
                    userId = r.optString("user_id"),
                    username = r.optString("username"),
                    score = r.optInt("score", 0),
                    isMe = r.optBoolean("is_me", false),
                    phone = r.optString("phone")
                )
            )
        }
        return out
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
        val arr = rpc("fetch_notifications", JSONObject().apply { put("p_user_id", userId) })
        if (arr == null) {
            callback(false, emptyList())
            return
        }
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

    // "I just synced": stamps users.last_synced_at so the server knows this
    // user is active (see add_inactivity.sql). Fire-and-forget; also restarts
    // the local 5-day inactivity reminder. Called from ContactSync.run only.
    fun recordSync(context: android.content.Context, userId: String) {
        InactivityWarningWorker.reschedule(context)
        kotlin.concurrent.thread {
            rpc("record_sync", JSONObject().apply { put("p_user_id", userId) })
        }
    }

    // ---- Delivery tracking (see add_notification_tracking.sql) ----------
    // All three are fire-and-forget: a failed receipt must never affect the app.

    private fun fireAndForget(name: String, params: JSONObject) {
        kotlin.concurrent.thread { rpc(name, params) }
    }

    // A push arrived while the app was in the foreground.
    fun recordNotificationDelivered(userId: String, notificationId: String) {
        fireAndForget("record_notification_delivered", JSONObject().apply {
            put("p_user_id", userId)
            put("p_notification_id", notificationId)
        })
    }

    // The user tapped a push (this also proves it was delivered).
    fun recordNotificationOpened(userId: String, notificationId: String) {
        fireAndForget("record_notification_opened", JSONObject().apply {
            put("p_user_id", userId)
            put("p_notification_id", notificationId)
        })
    }

    // Whether Android currently lets this app show notifications.
    fun reportNotificationsEnabled(userId: String, enabled: Boolean, callback: (Boolean) -> Unit) {
        kotlin.concurrent.thread {
            callback(rpc("report_notifications_enabled", JSONObject().apply {
                put("p_user_id", userId)
                put("p_enabled", enabled)
            }) != null)
        }
    }

    // Marks every notification currently visible to this user as read
    // (called when they open the Notifications screen), via the
    // mark_notifications_read RPC.
    fun markNotificationsRead(userId: String, callback: (Boolean) -> Unit) {
        callback(rpc("mark_notifications_read", JSONObject().apply { put("p_user_id", userId) }) != null)
    }

    // One value from the app_settings table (null if missing / offline).
    fun fetchSetting(key: String, callback: (String?) -> Unit) {
        val arr = rpc("get_app_setting", JSONObject().apply { put("p_key", key) })
        callback(if (arr != null && arr.length() > 0) arr.getJSONObject(0).optString("value").ifBlank { null } else null)
    }

    // The newest published build, from get_app_update (see add_app_update.sql).
    // Returns one row {update_available, force_update, latest_build, download_url, notes},
    // or null when offline / the SQL hasn't been run yet. Blocking: call from a background thread.
    fun fetchAppUpdate(currentBuild: Int, callback: (JSONObject?) -> Unit) {
        val arr = rpc("get_app_update", JSONObject().apply { put("p_current_build", currentBuild) })
        callback(if (arr != null && arr.length() > 0) arr.getJSONObject(0) else null)
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
