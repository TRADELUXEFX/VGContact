package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class LoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var progressBar: ProgressBar  // ← ADDED: ProgressBar declaration
    private lateinit var loginBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        sessionManager = SessionManager(this)

        // If already registered on this device, go straight to home
        if (sessionManager.isLoggedIn()) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        val usernameInput = findViewById<EditText>(R.id.username_input)
        val phoneInput = findViewById<EditText>(R.id.phone_input)
        val referralInput = findViewById<EditText>(R.id.referral_input)
        loginBtn = findViewById(R.id.login_btn)
        progressBar = findViewById(R.id.progressBar)  // ← ADDED: ProgressBar initialization

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        loginBtn.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            val referral = referralInput.text.toString().trim()

            if (username.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Enter your username and phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!SupabaseClient.isOnline(this)) {
                Toast.makeText(this, "No internet connection. Check your network and try again.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            setLoading(true)  // ← CHANGED: Use proper loading state function

            thread {
                SupabaseClient.registerOrFetchUser(androidId, username, phone, referral.ifEmpty { null }) { success, user ->
                    runOnUiThread {
                        setLoading(false)  // ← CHANGED: Properly clear loading state

                        if (success && user != null) {
                            sessionManager.saveUsername(user.optString("username", username))
                            sessionManager.savePhone(user.optString("phone", phone))
                            sessionManager.saveUserId(user.optString("id", ""))
                            startActivity(Intent(this, HomeActivity::class.java))
                            finish()
                        } else {
                            Toast.makeText(this, "Couldn't sign up — check your connection, or that username may be taken", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    // ← ADDED: Proper loading state management function
    private fun setLoading(loading: Boolean) {
        loginBtn.isEnabled = !loading
        loginBtn.text = if (loading) "" else getString(R.string.login_button)
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

}
