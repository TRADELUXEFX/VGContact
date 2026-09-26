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

    fun authSignUp(email: String, password: String, callback: (Boolean, String) -> Unit) {
        val url = "$supabaseUrl/auth/v1/signup"
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", anonKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                callback(response.isSuccessful, response.body?.string() ?: "")
            }
        } catch (e: Exception) {
            callback(false, e.message ?: "Error")
        }
    }

    fun authSignIn(email: String, password: String, callback: (Boolean, String) -> Unit) {
        val url = "$supabaseUrl/auth/v1/token?grant_type=password"
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", anonKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respBody = response.body?.string() ?: ""
                    val json = JSONObject(respBody)
                    val token = json.optString("access_token", "")
                    callback(true, token)
                } else {
                    callback(false, "Login failed")
                }
            }
        } catch (e: Exception) {
            callback(false, e.message ?: "Error")
        }
    }

    fun getUser(token: String, callback: (Boolean, JSONObject?) -> Unit) {
        val url = "$supabaseUrl/auth/v1/user"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("apikey", anonKey)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    callback(true, json)
                } else {
                    callback(false, null)
                }
            }
        } catch (e: Exception) {
            callback(false, null)
        }
    }

}
