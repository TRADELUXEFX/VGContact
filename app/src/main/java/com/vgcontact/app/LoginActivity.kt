package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class LoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        sessionManager = SessionManager(this)

        // If already logged in, go to home
        if (sessionManager.isLoggedIn()) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        val emailInput = findViewById<EditText>(R.id.email_input)
        val passwordInput = findViewById<EditText>(R.id.password_input)
        val loginBtn = findViewById<Button>(R.id.login_btn)
        val signupLink = findViewById<TextView>(R.id.signup_link)

        loginBtn.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loginBtn.isEnabled = false
            loginBtn.text = "Logging in..."

            thread {
                SupabaseClient.authSignIn(email, password) { success, token ->
                    runOnUiThread {
                        loginBtn.isEnabled = true
                        loginBtn.text = "Login"

                        if (success && token.isNotEmpty()) {
                            sessionManager.saveToken(token)
                            sessionManager.saveEmail(email)
                            Toast.makeText(this, "Login successful", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this, HomeActivity::class.java))
                            finish()
                        } else {
                            Toast.makeText(this, "Login failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        signupLink.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            signupLink.isEnabled = false
            signupLink.text = "Creating account..."

            thread {
                SupabaseClient.authSignUp(email, password) { success, response ->
                    runOnUiThread {
                        signupLink.isEnabled = true
                        signupLink.text = "Create Account"

                        if (success) {
                            Toast.makeText(this, "Account created. Please login.", Toast.LENGTH_SHORT).show()
                            emailInput.text.clear()
                            passwordInput.text.clear()
                        } else {
                            Toast.makeText(this, "Signup failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

}
