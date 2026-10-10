package com.vgcontact.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Full screen shown instead of a toast when the one-account-per-phone rule
 * stops a sign-up or login. Two reasons (EXTRA_REASON):
 *
 * REASON_DEVICE: this phone already has an account (sign-up returned it).
 * Shows that account and a Log in button that signs in to it. The account
 * comes from the server as JSON (EXTRA_USER_JSON), so no second call is needed.
 *
 * REASON_NUMBER: the number typed at login belongs to a DIFFERENT phone.
 * There is no account for this phone to log into, so Log in is hidden and
 * a verification code is shown (tap to copy, also put in the support message)
 * so the admin can move the account here.
 */
class DeviceBlockedActivity : BaseActivity() {

    companion object {
        const val EXTRA_REASON = "reason"
        const val EXTRA_USER_JSON = "user_json"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_ANDROID_ID = "android_id"
        const val REASON_DEVICE = "DEVICE"
        const val REASON_NUMBER = "NUMBER"
    }

    private var reason = REASON_DEVICE
    private var user: JSONObject? = null
    private var number: String = ""
    private var androidId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_blocked)
        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_DEVICE
        user = intent.getStringExtra(EXTRA_USER_JSON)?.let { runCatching { JSONObject(it) }.getOrNull() }
        number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        androidId = intent.getStringExtra(EXTRA_ANDROID_ID).orEmpty()

        val loginButton = findViewById<Button>(R.id.loginButton)

        if (reason == REASON_NUMBER) {
            findViewById<TextView>(R.id.headerLineOne).text = "This number is already"
            findViewById<TextView>(R.id.headerLineTwo).text = "Registered"
            findViewById<View>(R.id.accountCard).visibility = View.GONE
            findViewById<TextView>(R.id.registeredNumberLabel).text = "NUMBER YOU ENTERED"
            findViewById<TextView>(R.id.registeredNumberText).text = number.ifBlank { "—" }
            findViewById<TextView>(R.id.bodyText).text =
                "This number is registered on a different phone. Contact customer care to move it to this one."
            loginButton.visibility = View.GONE

            if (androidId.isNotBlank()) {
                findViewById<View>(R.id.newDeviceIdCard).visibility = View.VISIBLE
                findViewById<TextView>(R.id.newDeviceIdText).text = androidId
                findViewById<View>(R.id.newDeviceIdCard).setOnClickListener { copyId() }
            }
        } else {
            findViewById<TextView>(R.id.accountNameText).text =
                user?.optString("username").orEmpty().ifBlank { "—" }
            findViewById<TextView>(R.id.registeredNumberText).text =
                user?.optString("phone").orEmpty().ifBlank { "—" }
            loginButton.setOnClickListener { logIn() }
        }

        findViewById<Button>(R.id.contactCareButton).setOnClickListener { contactCare() }
    }

    // Signs in to the account the server returned for this phone.
    private fun logIn() {
        val u = user
        if (u == null) {
            Toast.makeText(this, "Couldn't find your account. Please contact customer care.", Toast.LENGTH_LONG).show()
            return
        }
        if (u.optString("secret", "").isBlank()) {
            Toast.makeText(this, "Couldn't log in. Please contact customer care.", Toast.LENGTH_LONG).show()
            return
        }
        val session = SessionManager(this)
        session.saveSecret(u.optString("secret", ""))
        session.saveUsername(u.optString("username", ""))
        session.savePhone(u.optString("phone", ""))
        session.saveUserId(u.optString("id", "").ifBlank { u.optString("user_id", "").ifBlank { u.optString("uid", "") } })
        session.saveRegistrationFrom(u)
        VgFirebaseMessagingService.flushPendingTokenIfAny(this)
        startActivity(Intent(this, PermissionsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    private fun copyId() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Verification code", androidId))
        Toast.makeText(this, "Verification code copied", Toast.LENGTH_SHORT).show()
    }

    private fun contactCare() {
        val text = if (reason == REASON_NUMBER) {
            "Hi VGContact, I tried to log in with $number but it says that number is registered on another phone." +
                (if (androidId.isNotBlank()) " My verification code is: $androidId." else "") +
                " I need help moving it."
        } else {
            "Hi VGContact, I need help with my phone registration."
        }
        SupportContact.openSupport(this, text)
    }
}
