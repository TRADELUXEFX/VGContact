package com.vgcontact.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * The old unlock/download screen is gone. Contacts now reach the phone
 * through Sync contacts on Home (see ContactSync). This stub only exists
 * so an old push notification that still points here lands on Home
 * instead of crashing. Safe to delete once NotificationRouter no longer
 * references it.
 */
class DownloadsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
