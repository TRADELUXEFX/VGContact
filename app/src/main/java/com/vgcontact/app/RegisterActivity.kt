package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * REGISTRATION ONLY. This is the launcher screen — reached the first
 * time someone opens the app on this device. It signs a brand-new user
 * up. It never logs an existing user in.
 *
 * If this device is already registered (SessionManager.isLoggedIn()),
 * skip straight to HomeActivity — same as before.
 *
 * If someone already has an account but reinstalled the app (so this
 * device has no local session), they use the "Already have an account?
 * Log in" link below the form, which goes to LoginActivity — a
 * separate, login-only screen. Registration and login are two
 * different jobs now, matching the VGKontact OnboardingActivity /
 * LoginActivity split.
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var progressBar: ProgressBar
    private lateinit var registerBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        sessionManager = SessionManager(this)

        // Already registered on this device -> go straight to home.
        if (sessionManager.isLoggedIn()) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        val usernameInput = findViewById<EditText>(R.id.username_input)
        val phoneInput = findViewById<EditText>(R.id.phone_input)
        val referralInput = findViewById<EditText>(R.id.referral_input)
        registerBtn = findViewById(R.id.register_btn)
        progressBar = findViewById(R.id.progressBar)

        // Entry point for someone who already has an account (e.g.
        // reinstalled the app) rather than signing up fresh.
        val loginLink = findViewById<TextView>(R.id.login_link)
        loginLink.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        registerBtn.setOnClickListener {
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

            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

            if (androidId.isNullOrBlank()) {
                Toast.makeText(this, "Couldn't verify this device. Please restart the app and try again.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            setLoading(true)

            thread {
                SupabaseClient.registerOrFetchUser(androidId, username, phone, referral.ifEmpty { null }) { success, user ->
                    runOnUiThread {
                        setLoading(false)

                        if (success && user != null) {
                            sessionManager.saveUsername(user.optString("username", username))
                            sessionManager.savePhone(user.optString("phone", phone))
                            sessionManager.saveUserId(user.optString("id", ""))
                            startActivity(Intent(this, HomeActivity::class.java))
                            finish()
                        } else {
                            // Most likely cause: this username or phone is
                            // already registered. Send them to Login instead
                            // of dead-ending on a generic error - same
                            // "Option A" pattern VGKontact uses.
                            Toast.makeText(
                                this,
                                "That username or phone may already be registered. Try logging in instead.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        registerBtn.isEnabled = !loading
        registerBtn.text = if (loading) "" else getString(R.string.login_button)
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

}
