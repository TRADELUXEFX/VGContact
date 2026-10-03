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

/**
 * LOGIN ONLY. Reached from RegisterActivity's "Already have an account?
 * Log in" link - never the launcher screen itself. For someone who
 * already registered but this device has no local session (e.g.
 * reinstalled the app).
 *
 * Seamless login: they enter only the phone number they registered
 * with. We look the account up on Supabase and compare this device's
 * android_id against the one stored on that account.
 *  - Phone found AND android_id matches this device -> log them in,
 *    no password/OTP needed.
 *  - Phone found but android_id does NOT match (different phone) ->
 *    refuse and explain why, instead of registering.
 *  - Phone not found at all -> tell them to register instead.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var progressBar: ProgressBar
    private lateinit var loginBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        sessionManager = SessionManager(this)

        if (sessionManager.isLoggedIn()) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        val phoneInput = findViewById<EditText>(R.id.login_phone_input)
        loginBtn = findViewById(R.id.login_btn)
        progressBar = findViewById(R.id.progressBar)

        loginBtn.setOnClickListener {
            val phone = PhoneUtils.clean(phoneInput.text.toString())

            if (phone.isEmpty()) {
                Toast.makeText(this, "Enter the phone number you registered with", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!PhoneUtils.isValid(phone)) {
                Toast.makeText(this, PhoneUtils.ERROR_MESSAGE, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!SupabaseClient.isOnline(this)) {
                Toast.makeText(this, "No internet connection. Check your network and try again.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            if (androidId.isNullOrBlank()) {
                startActivity(Intent(this, DeviceUnverifiedActivity::class.java))
                return@setOnClickListener
            }

            setLoading(true)

            thread {
                SupabaseClient.fetchUserByPhone(phone, androidId) { found, deviceMatches, user ->
                    runOnUiThread {
                        setLoading(false)

                        when {
                            found && deviceMatches && user != null && user.optString("secret", "").isBlank() -> {
                                Toast.makeText(this, "Couldn't log in. Please try again.", Toast.LENGTH_LONG).show()
                            }
                            found && deviceMatches && user != null -> {
                                sessionManager.saveSecret(user.optString("secret", ""))
                                sessionManager.saveUsername(user.optString("username", ""))
                                sessionManager.savePhone(user.optString("phone", phone))
                                sessionManager.saveUserId(user.optString("id", "").ifBlank { user.optString("user_id", "").ifBlank { user.optString("uid", "") } })
                                sessionManager.saveRegistrationFrom(user)
                                VgFirebaseMessagingService.flushPendingTokenIfAny(this)
                                // Clear the whole back stack so Back from Home can never return to Register/Login.
                                startActivity(Intent(this, PermissionsActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) })
                                finish()
                            }
                            found && !deviceMatches -> {
                                startActivity(
                                    Intent(this, DeviceBlockedActivity::class.java)
                                        .putExtra(DeviceBlockedActivity.EXTRA_REASON, DeviceBlockedActivity.REASON_NUMBER)
                                        .putExtra(DeviceBlockedActivity.EXTRA_NUMBER, phone)
                                        .putExtra(DeviceBlockedActivity.EXTRA_ANDROID_ID, androidId)
                                )
                            }
                            SupabaseClient.lastError != null -> {
                                // Server/network problem - NOT "no account".
                                Toast.makeText(
                                    this,
                                    "Couldn't reach the server (${SupabaseClient.lastError}). Try again.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            else -> {
                                Toast.makeText(
                                    this,
                                    "No account found with this number. You may need to register instead.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        loginBtn.isEnabled = !loading
        loginBtn.text = if (loading) "" else "Log In"
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

}
