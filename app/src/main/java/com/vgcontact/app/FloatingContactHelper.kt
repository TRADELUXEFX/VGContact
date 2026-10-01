package com.vgcontact.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * App-wide floating "Contact Us" button, styled like WhatsApp's chat
 * bubble that hovers over every screen. Call [attach] once from an
 * activity's onCreate() (after setContentView) to add it.
 *
 * The button is added directly to the activity's content FrameLayout
 * (android.R.id.content) rather than to each screen's own XML, so no
 * layout file needs to be touched and it's guaranteed to float above
 * everything else already on screen, including scrolling content and
 * the bottom nav pill.
 *
 * Uses the same WhatsApp support number as everything else
 * (SupportContact.WHATSAPP), so every contact-us entry point in
 * the app goes to the same place.
 */
object FloatingContactHelper {

    private const val FAB_TAG = "floating_contact_fab"

    /**
     * Screens that must NOT show the floating button (auth / onboarding /
     * permissions / legal,
     * Notifications, and Banned).
     */
    private val EXCLUDED: Set<Class<out Activity>> = setOf(
        RegisterActivity::class.java,
        LoginActivity::class.java,
        PermissionsActivity::class.java,
        LegalActivity::class.java,
        NotificationsActivity::class.java,
        BannedActivity::class.java
    )

    /**
     * Call once from Application.onCreate(). After this, every screen gets
     * the floating button automatically as it is created, so individual
     * activities no longer need their own attach() call (existing calls are
     * harmless: attach() de-duplicates by tag).
     */
    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                // Content view is guaranteed set by onStart, so android.R.id.content exists.
                if (activity.javaClass in EXCLUDED) return
                // Nav-bar screens get lifted above the pill; others sit low.
                val hasBottomNav = activity.findViewById<View>(R.id.bottomNavBar) != null
                attach(activity, if (hasBottomNav) 110 else 0)
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * @param bottomMarginDp extra bottom margin (in dp) to lift the button
     * above screens that have their own floating bottom nav bar, so it
     * doesn't overlap it. Pass 0 for screens without a bottom nav bar.
     * @return the FAB view, or the existing FAB if attach() was already
     * called for this screen.
     */
    fun attach(
        activity: Activity,
        bottomMarginDp: Int = 110
    ): View? {
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null

        // Avoid adding a second bubble if attach() is somehow called twice
        // (e.g. re-created activity) for the same screen.
        contentRoot.findViewWithTag<View>(FAB_TAG)?.let { return it }

        val density = activity.resources.displayMetrics.density
        val sizePx = (60 * density).toInt()
        val marginPx = (18 * density).toInt()
        val bottomMarginPx = (bottomMarginDp * density).toInt()
        val iconPaddingPx = (16 * density).toInt()

        val fab = ImageView(activity).apply {
            tag = FAB_TAG
            id = View.generateViewId()
            setImageResource(R.drawable.ic_chat)
            setColorFilter(ContextCompat.getColor(activity, R.color.white))
            background = ContextCompat.getDrawable(activity, R.drawable.floating_contact_fab_background)
            setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
            elevation = 12 * density
            contentDescription = activity.getString(R.string.menu_contact_us)
            isClickable = true
            isFocusable = true
        }

        val params = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = marginPx
            bottomMargin = marginPx + bottomMarginPx
        }

        fab.setOnClickListener { openWhatsAppContactUs(activity) }

        contentRoot.addView(fab, params)

        return fab
    }

    private fun openWhatsAppContactUs(activity: Activity) {
        val message = Uri.encode("Hi VGContact, I need help with...")
        val uri = Uri.parse("https://wa.me/${SupportContact.WHATSAPP}?text=$message")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(activity, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
