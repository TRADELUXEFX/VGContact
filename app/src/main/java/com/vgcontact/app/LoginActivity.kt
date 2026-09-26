package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class LoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

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
        val loginBtn = findViewById<Button>(R.id.login_btn)

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        loginBtn.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            val referral = referralInput.text.toString().trim()

            if (username.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Enter your username and phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loginBtn.isEnabled = false
            loginBtn.text = "Setting up..."

            thread {
                SupabaseClient.registerOrFetchUser(androidId, username, phone, referral.ifEmpty { null }) { success, user ->
                    runOnUiThread {
                        loginBtn.isEnabled = true
                        loginBtn.text = getString(R.string.login_button)

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

}
