package com.vgcontact.app

/**
 * Convenience accessor for showing the app version on a Profile/Settings
 * screen, e.g.:
 *
 *   versionText.text = BuildInfo.displayVersion()
 *
 * Produces something like "1.0.1 (2)".
 */
object BuildInfo {
    fun displayVersion(): String =
        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
