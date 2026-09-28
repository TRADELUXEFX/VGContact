package com.vgcontact.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * App-wide floating "Contact us" button, modeled on WhatsApp's hovering
 * new-chat button. Registered once from VGApp and attached automatically
 * to every screen, so no activity has to add or wire it. It always sits
 * at the same spot (bottom-end, just above the floating nav pill), so it
 * appears to stay put while screens change underneath it.
 *
 * Skipped on: splash, the permissions walkthrough (full-screen action
 * button) and the support screen itself. Hidden while the keyboard is
 * open so it never covers a text field.
 */
object FloatingContactHelper : Application.ActivityLifecycleCallbacks {

    private const val SIZE_DP = 58
    private const val ICON_PADDING_DP = 16
    private const val MARGIN_END_DP = 16
    // Clears the floating nav pill (top edge ~76dp) and the pinned
    // Buy Keys button (~90dp), so one fixed position works on every screen.
    private const val MARGIN_BOTTOM_DP = 96
    private const val ELEVATION_DP = 6

    private val EXCLUDED: Set<Class<*>> = setOf(
        SplashActivity::class.java,
        PermissionsActivity::class.java,
        ChatSupportActivity::class.java
    )

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity.javaClass in EXCLUDED) return
        if (!activity.javaClass.name.startsWith(activity.packageName)) return
        val content = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        if (content.findViewById<View>(R.id.global_chat_btn) != null) return

        val d = activity.resources.displayMetrics.density
        val padding = (ICON_PADDING_DP * d).toInt()

        val btn = ImageButton(activity).apply {
            id = R.id.global_chat_btn
            layoutParams = FrameLayout.LayoutParams((SIZE_DP * d).toInt(), (SIZE_DP * d).toInt()).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = (MARGIN_END_DP * d).toInt()
                bottomMargin = (MARGIN_BOTTOM_DP * d).toInt()
            }
            setBackgroundResource(R.drawable.floating_contact_fab_background)
            setImageResource(R.drawable.ic_chat)
            setColorFilter(ContextCompat.getColor(activity, R.color.vg_dark))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(padding, padding, padding, padding)
            elevation = ELEVATION_DP * d
            contentDescription = "Contact us"
            setOnClickListener { openWhatsApp(activity) }
        }
        content.addView(btn)

        // Hide while the keyboard is up so it never sits over an input.
        content.viewTreeObserver.addOnGlobalLayoutListener {
            val imeVisible = ViewCompat.getRootWindowInsets(btn)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            btn.visibility = if (imeVisible) View.GONE else View.VISIBLE
        }
    }

    fun openWhatsApp(activity: Activity) {
        try {
            val username = SessionManager(activity).getUsername()
            val message = "Hi VGContact, I need help with..." +
                if (username.isNullOrBlank()) "" else " My username is $username."
            val uri = Uri.parse(
                "https://wa.me/${BuyKeysActivity.SUPPORT_WHATSAPP}?text=${Uri.encode(message)}"
            )
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(activity, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
