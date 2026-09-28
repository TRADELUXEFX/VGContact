package com.vgcontact.app

/**
 * Version text for the Profile screen, e.g. "VGKontact v1.0.954".
 * The number is the GitHub run number (see versionName in build.gradle),
 * so it matches the release tag on the Releases page.
 */
object BuildInfo {
    fun displayVersion(): String = "VGKontact v${BuildConfig.VERSION_NAME}"
}
