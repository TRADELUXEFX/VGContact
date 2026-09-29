package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * New launcher activity. Shows a brief splash with just the app icon
 * (no text, no spinner) while we check whether this device already has
 * a session (SessionManager.isLoggedIn()), then routes straight to
 * HomeActivity or to RegisterActivity - the same decision RegisterActivity
 * used to make on its own onCreate, just moved one screen earlier so
 * there's no flash of the registration form on an already-registered
 * device.
 *
 * MIN_SPLASH_MS keeps the icon visible for a minimum stretch even
 * when the session check is instant, so it reads as a deliberate splash
 * screen rather than a one-frame flicker.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val MIN_SPLASH_MS = 600L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BanPrefs.isBanned(this)) {
            BannedHandler.launch(this)
            finish()
            return
        }
        setContentView(R.layout.activity_splash)

        val sessionManager = SessionManager(this)
        val isLoggedIn = sessionManager.isLoggedIn()

        // Covers the case where the user is already logged in from a
        // previous launch: onNewToken() only fires on rotation, so this is
        // what keeps users.fcm_token in sync with whatever token is
        // currently valid, on every cold start.
        if (isLoggedIn) {
            val userId = sessionManager.getUserId()
            if (!userId.isNullOrBlank()) {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        SupabaseClient.saveFcmToken(userId, token) { _ -> }
                    }
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val destination = if (isLoggedIn) HomeActivity::class.java else RegisterActivity::class.java
            startActivity(NotificationRouter.forward(intent, Intent(this, destination)))
            finish()
        }, MIN_SPLASH_MS)
    }
}
