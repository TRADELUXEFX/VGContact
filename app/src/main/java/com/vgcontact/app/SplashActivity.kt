package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * New launcher activity. Shows a brief white-background/green-spinner
 * splash while we check whether this device already has a session
 * (SessionManager.isLoggedIn()), then routes straight to HomeActivity
 * or to RegisterActivity - the same decision RegisterActivity used to
 * make on its own onCreate, just moved one screen earlier so there's
 * no flash of the registration form on an already-registered device.
 *
 * MIN_SPLASH_MS keeps the spinner visible for a minimum stretch even
 * when the session check is instant, so it reads as a deliberate splash
 * screen rather than a one-frame flicker.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val MIN_SPLASH_MS = 600L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val sessionManager = SessionManager(this)
        val isLoggedIn = sessionManager.isLoggedIn()

        Handler(Looper.getMainLooper()).postDelayed({
            val destination = if (isLoggedIn) HomeActivity::class.java else RegisterActivity::class.java
            startActivity(Intent(this, destination))
            finish()
        }, MIN_SPLASH_MS)
    }
}
