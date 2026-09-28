package com.vgcontact.app

import android.app.Application

/** Application entry point. Registers app-wide UI that lives above every screen. */
class VGApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FloatingContactHelper.register(this)
    }
}
