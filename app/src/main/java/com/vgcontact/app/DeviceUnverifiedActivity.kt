package com.vgcontact.app

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Full screen shown when the phone returns no Android ID. One account per
 * phone can't be checked without it, so sign-up / login stops here and the
 * user can try again (back to the form) or contact support.
 */
class DeviceUnverifiedActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_unverified)
        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        findViewById<Button>(R.id.tryAgainButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.contactCareButton).setOnClickListener {
            SupportContact.openSupport(
                this,
                "Hi VGContact, I'm trying to sign up but my phone can't get a verification code."
            )
        }
    }
}
