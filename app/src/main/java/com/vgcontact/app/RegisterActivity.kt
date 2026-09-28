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
 * REGISTRATION ONLY, and the app's launcher activity. Reached the first
 * time someone opens the app on this device (or every time, on a
 * device that isn't registered yet). It signs a brand-new user up. It
 * never logs an existing user in.
 *
 * If this device is already registered (SessionManager.isLoggedIn()),
 * skip straight to HomeActivity.
 *
 * If someone already has an account but reinstalled the app (so this
 * device has no local session), they use the "Already have an account?
 * Log in" link below the form, which goes to LoginActivity — a
 * separate, login-only screen. Registration and login are two
 * different jobs now, matching the VGKontact OnboardingActivity /
 * LoginActivity split.
 *
 * No separate splash activity/screen sits in front of this one. The
 * "splash" is the launch window Android shows while the process starts,
 * styled by Theme.VGContact.Splash (dark). onCreate() immediately
 * switches to Theme.VGContact so the splash color stays out of the
 * register screen.
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var progressBar: ProgressBar
    private lateinit var registerBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        // Leave the dark splash theme (set in the manifest for the launch
        // window only) and go back to the normal app theme BEFORE any
        // layout is inflated, so the register screen is unaffected by
        // the splash color.
        setTheme(R.style.Theme_VGContact)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        sessionManager = SessionManager(this)

        // Already registered on this device -> go straight to home.
        if (sessionManager.isLoggedIn()) {
            // Keeps users.fcm_token in sync with whatever token is
            // currently valid, on every cold start - onNewToken() only
            // fires on rotation, so this is what catches a token that
            // changed since the last time the app was opened. Formerly
            // lived in the now-removed SplashActivity.
            val userId = sessionManager.getUserId()
            if (!userId.isNullOrBlank()) {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        SupabaseClient.saveFcmToken(userId, token) { _ -> }
                    }
            }

            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        val usernameInput = findViewById<EditText>(R.id.username_input)
        val phoneInput = findViewById<EditText>(R.id.phone_input)
        val referralInput = findViewById<EditText>(R.id.referral_input)
        registerBtn = findViewById(R.id.register_btn)
        progressBar = findViewById(R.id.progressBar)

        setupConsentText()

        // Entry point for someone who already has an account (e.g.
        // reinstalled the app) rather than signing up fresh.
        val loginLink = findViewById<TextView>(R.id.login_link)
        loginLink.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        registerBtn.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val phone = PhoneUtils.clean(phoneInput.text.toString())
            val referral = referralInput.text.toString().trim()

            if (username.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Enter your username and phone number", Toast.LENGTH_SHORT).show()
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
                            sessionManager.saveRegistrationFrom(user)
                            VgFirebaseMessagingService.flushPendingTokenIfAny(this)
                            startActivity(Intent(this, PermissionsActivity::class.java))
                            finish()
                        } else {
                            val err = SupabaseClient.lastError.orEmpty()
                            val msg = when {
                                err.contains("23505") || err.contains("duplicate", true) ->
                                    "That username or phone is already registered. Try logging in instead."
                                err.startsWith("Network error") ->
                                    "Couldn't reach the server. Check your internet and try again."
                                err.isNotBlank() ->
                                    "Couldn't sign up ($err)"
                                else ->
                                    "Couldn't sign up. Please try again."
                            }
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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


    /** "By continuing you agree to our Terms & Conditions and Privacy Policy" with tappable links. */
    private fun setupConsentText() {
        val consent = findViewById<TextView>(R.id.register_consent)
        val full = "By continuing you agree to our Terms & Conditions and Privacy Policy."
        val span = android.text.SpannableString(full)
        val linkColor = androidx.core.content.ContextCompat.getColor(this, R.color.vg_green_dark)

        fun link(label: String, onClick: () -> Unit) {
            val start = full.indexOf(label)
            if (start < 0) return
            val end = start + label.length
            span.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) = onClick()
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = linkColor
                    ds.isUnderlineText = false
                    ds.isFakeBoldText = true
                }
            }, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        link("Terms & Conditions") { LegalActivity.openTerms(this) }
        link("Privacy Policy") { LegalActivity.openPrivacy(this) }

        consent.text = span
        consent.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }
}
