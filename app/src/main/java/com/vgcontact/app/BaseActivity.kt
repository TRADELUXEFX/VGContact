package com.vgcontact.app

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/**
 * Every screen in the app extends this instead of AppCompatActivity.
 * It applies ScaleGuard, so the font/display size policy covers every screen automatically,
 * including screens added later (tools/check_layouts.py fails the build if one forgets).
 */
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScaleGuard.wrap(newBase))
    }

    // AppCompat re-applies the system configuration on top of ours. Keep our scale, but let
    // it keep its own dark/light mode (uiMode).
    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        if (overrideConfiguration != null) {
            val uiMode = overrideConfiguration.uiMode
            overrideConfiguration.setTo(baseContext.resources.configuration)
            overrideConfiguration.uiMode = uiMode
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }
}
