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
 * Log in" link — never the launcher screen itself. For someone who
 * already registered (on this phone number/username) but this device
 * has no local session (e.g. reinstalled the app).
 *
 * Looks the account up by phone number. If found, restores the local
 * session and goes to HomeActivity. If not found, tells the user to
 * register instead - it never creates a new account.
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
            val phone = phoneInput.text.toString().trim()

            if (phone.isEmpty()) {
                Toast.makeText(this, "Enter the phone number you registered with", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!SupabaseClient.isOnline(this)) {
                Toast.makeText(this, "No internet connection. Check your network and try again.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            setLoading(true)

            thread {
                SupabaseClient.fetchUserByPhone(phone) { found, user ->
                    runOnUiThread {
                        setLoading(false)

                        if (found && user != null) {
                            sessionManager.saveUsername(user.optString("username", ""))
                            sessionManager.savePhone(user.optString("phone", phone))
                            sessionManager.saveUserId(user.optString("id", ""))
                            startActivity(Intent(this, HomeActivity::class.java))
                            finish()
                        } else {
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

    private fun setLoading(loading: Boolean) {
        loginBtn.isEnabled = !loading
        loginBtn.text = if (loading) "" else "Log In"
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

}
