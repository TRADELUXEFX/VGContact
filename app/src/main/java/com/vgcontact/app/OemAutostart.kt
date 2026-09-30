package com.vgcontact.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * Advisory for phone brands that ship their own background-app killer on top
 * of stock Android (Tecno, Infinix, itel, Xiaomi, Oppo, Vivo, Huawei, ...).
 * On those phones the app can be exempt from normal battery optimisation and
 * still be stopped by the brand's own manager, so the daily sync silently
 * stops.
 *
 * Android has no API to read that setting back, so this only remembers that
 * the user was sent there once (SyncPrefs.hasSeenOemAutostartPrompt). The
 * banner shows once per phone and then stays quiet.
 *
 * The screens below are undocumented brand-specific activities. They can
 * change or disappear between OS versions, so each one is tried in turn and
 * the app's own Settings page is the fallback.
 */
object OemAutostart {

    private val KNOWN_OEMS = setOf(
        "xiaomi", "redmi", "poco",
        "huawei", "honor",
        "oppo", "realme", "oneplus",
        "vivo", "iqoo",
        "tecno", "infinix", "itel"
    )

    private fun isKnownOem(): Boolean = Build.MANUFACTURER.lowercase() in KNOWN_OEMS

    /** True only on a listed brand that has not been sent to its autostart screen yet. */
    fun needsPrompt(activity: Activity): Boolean =
        isKnownOem() && !SyncPrefs.hasSeenOemAutostartPrompt(activity)

    fun openSettings(activity: Activity) {
        // Mark as seen first so the banner stops even if the fallback fires.
        SyncPrefs.setSeenOemAutostartPrompt(activity, true)

        val m = Build.MANUFACTURER.lowercase()
        fun c(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

        val candidates: List<Intent> = when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> listOf(
                c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                c("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity")
            )
            m.contains("huawei") || m.contains("honor") -> listOf(
                c("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                c("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> listOf(
                c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                c("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                c("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            )
            m.contains("vivo") || m.contains("iqoo") -> listOf(
                c("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                c("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            )
            m.contains("tecno") || m.contains("infinix") || m.contains("itel") -> listOf(
                c("com.transsion.phonemanager", "com.itel.autobootmanager.activity.AutoBootMgrActivity"),
                c("com.transsion.phonemanager", "com.transsion.phonemanager.module.security.autoboot.AutoBootManagerActivity")
            )
            else -> emptyList()
        }

        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                Toast.makeText(
                    activity,
                    "Allow VGContact to auto-start / run in background, then come back",
                    Toast.LENGTH_LONG
                ).show()
                return
            } catch (e: Exception) {
                // This screen doesn't exist on this phone/OS version - try the next.
            }
        }

        try {
            activity.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${activity.packageName}"))
            )
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't open Settings", Toast.LENGTH_SHORT).show()
        }
    }
}
