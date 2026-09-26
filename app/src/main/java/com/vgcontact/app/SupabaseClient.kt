package com.vgcontact.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object SupabaseClient {
    private lateinit var supabaseUrl: String
    private lateinit var anonKey: String
    private val client = OkHttpClient()

    fun initialize(context: Context) {
        supabaseUrl = BuildConfig.SUPABASE_URL
        anonKey = BuildConfig.SUPABASE_ANON_KEY
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

}
