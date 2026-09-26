package com.vgcontact.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object SupabaseClient {
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY
    private val client = OkHttpClient()

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
        // 1. Check if this android_id is already registered.
        val lookupUrl = "$supabaseUrl/rest/v1/users?android_id=eq.$androidId&select=*"
        val lookupRequest = Request.Builder()
            .url(lookupUrl)
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $anonKey")
            .get()
            .build()

        try {
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
        } catch (e: Exception) {
            callback(false, null)
            return
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

        try {
            client.newCall(insertRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    callback(true, if (arr.length() > 0) arr.getJSONObject(0) else null)
                } else {
                    callback(false, null)
                }
            }
        } catch (e: Exception) {
            callback(false, null)
        }
    }

    // Published files, newest first.
    fun fetchFiles(callback: (Boolean, org.json.JSONArray?) -> Unit) {
        val url = "$supabaseUrl/rest/v1/files?is_published=eq.true&select=*&order=created_at.desc"
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $anonKey")
            .get()
            .build()

        try {
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

    // Set of file_ids this user has already unlocked.
    fun fetchUnlockedFileIds(userId: String, callback: (Boolean, Set<String>) -> Unit) {
        val url = "$supabaseUrl/rest/v1/unlocks?user_id=eq.$userId&select=file_id"
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $anonKey")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = org.json.JSONArray(response.body?.string() ?: "[]")
                    val ids = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        ids.add(arr.getJSONObject(i).optString("file_id"))
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

    // Creates a repost row with a generated unlock_code, returns the code on success.
    fun createRepost(userId: String, fileId: String, callback: (Boolean, String?) -> Unit) {
        val code = (1..6)
            .map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }
            .joinToString("")

        val url = "$supabaseUrl/rest/v1/reposts"
        val body = JSONObject().apply {
            put("user_id", userId)
            put("file_id", fileId)
            put("status", "completed")
            put("unlock_code", code)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $anonKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=representation")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    callback(true, code)
                } else {
                    callback(false, null)
                }
            }
        } catch (e: Exception) {
            callback(false, null)
        }
    }

    // Records that a user has unlocked a file (after a valid repost/code).
    fun unlockFile(userId: String, fileId: String, callback: (Boolean) -> Unit) {
        val url = "$supabaseUrl/rest/v1/unlocks"
        val body = JSONObject().apply {
            put("user_id", userId)
            put("file_id", fileId)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $anonKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                callback(response.isSuccessful)
            }
        } catch (e: Exception) {
            callback(false)
        }
    }

}
