package com.vgcontact.app

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Gate for pending (unverified) users. Shown as a bottom sheet (BottomSheetDialog).
 *
 *  - First open: the popup shows once per install ([show]).
 *  - After "Not now": Home shows the slim strip and dims the viewers card.
 *  - While pending, the only thing that works is the sheet's button. It does what the
 *    Repost screen's button does: opens the admin's WhatsApp (so the user can repost the
 *    status) and logs a 'pending' repost for the admin to verify.
 *    Every other tap calls [showGate] and brings the same sheet back.
 *
 * The pending flag is saved by Home each time it loads the account, so the
 * bottom nav and notification router can check it without a network call.
 */
object PendingPrompt {
    private const val PREFS = "vg_pending_prompt"
    private const val KEY_SHOWN = "sheet_shown"
    private const val KEY_PENDING = "is_pending"

    fun wasShown(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, false)

    private fun markShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOWN, true).apply()
    }

    /** True while the account is pending verification (last known state). */
    fun isPending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

    fun setPending(context: Context, pending: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PENDING, pending).apply()
    }

    /**
     * Same as the Repost screen's button: the user picks what to post (text + link or
     * image + link), the share sheet opens, then today's repost is logged as 'pending'
     * (once per day; a second tap only shows the picker again).
     */
    private fun repostNow(context: Context, alreadyLogged: Boolean) {
        val phone = SessionManager(context).getPhone().orEmpty()
        if (context !is Activity) return
        ShareHelper.showMenu(context, phone) { logRepost(context, alreadyLogged) }
    }

    private fun logRepost(context: Context, alreadyLogged: Boolean) {
        if (alreadyLogged) return
        val userId = SessionManager(context).getUserId()
        if (userId.isNullOrBlank()) {
            Toast.makeText(context, context.getString(R.string.pending_toast_no_account), Toast.LENGTH_SHORT).show()
            return
        }
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        thread {
            SupabaseClient.submitDailyRepost(userId) { success, message ->
                main.post {
                    if (success) {
                        Toast.makeText(app, app.getString(R.string.pending_toast_logged), Toast.LENGTH_SHORT).show()
                    } else if (message != "ALREADY_REPOSTED_TODAY") {
                        Toast.makeText(app, app.getString(R.string.pending_toast_log_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Shows the sheet again when a locked button is tapped.
     * Returns true if the user is pending (the tap was blocked), false if it may go through.
     */
    fun showGate(activity: Activity): Boolean {
        if (!isPending(activity)) return false
        show(activity, onLater = {})
        return true
    }

    /** False when the phone has animations turned off (Developer options / accessibility). */
    private fun animationsEnabled(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    /** Small "press in" feedback. Returns false so the normal click still fires. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pressEffect(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    private var visible = false

    /**
     * [onLater] runs for every way of closing without the button: Not now, back,
     * tap outside.
     */
    fun show(activity: Activity, onLater: () -> Unit) {
        if (visible || activity.isFinishing || activity.isDestroyed) return
        visible = true
        markShown(activity)
        // Bottom sheet: slides up from the bottom, dimmed background, easy to reach with a thumb.
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_pending_verify, null)
        dialog.setContentView(view)
        // The layout paints its own rounded top, so the sheet container must be transparent.
        // BottomSheetBehavior puts its own white shape on the container when the sheet is first
        // laid out (after this line), which showed as white wedges in the header's rounded
        // corners. So clear it again once the sheet is shown and after the first layout.
        val clearSheetBackground = {
            (view.parent as? View)?.apply {
                background = null
                setBackgroundColor(Color.TRANSPARENT)
            }
            Unit
        }
        clearSheetBackground()
        dialog.setOnShowListener {
            clearSheetBackground()
            view.post { clearSheetBackground() }
        }
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        val verifyBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.pendingSheetVerifyBtn)

        // Numbers come from app_settings (cached, with fallbacks); the support number comes
        // from SupportContact. Nothing is typed into the layout.
        PendingConfig.refresh(activity)
        val viewers = PendingConfig.viewers(activity)
        val minViews = PendingConfig.minViews(activity)
        val payAmount = PendingConfig.payAmount(activity)
        val hours = PendingConfig.verifyHours(activity)
        view.findViewById<TextView>(R.id.pendingTitle).text = activity.getString(R.string.pending_title, viewers)
        view.findViewById<TextView>(R.id.pendingStep2Title).text = activity.getString(R.string.pending_step2_title, minViews)
        view.findViewById<TextView>(R.id.pendingStep3Desc).text =
            activity.getString(R.string.pending_step3_desc, SupportContact.displayNumber(), hours)
        view.findViewById<TextView>(R.id.pendingCantReach).text = activity.getString(R.string.pending_cant_reach, minViews)
        view.findViewById<TextView>(R.id.pendingSheetProofBtn).text = activity.getString(R.string.pending_btn_proof, minViews)
        view.findViewById<TextView>(R.id.pendingSheetPayBtn).text = activity.getString(R.string.pending_btn_pay_short, payAmount)

        // If today's repost is already logged, say so and don't log a second one.
        var alreadyLogged = false
        val userId = SessionManager(activity).getUserId()
        if (!userId.isNullOrBlank()) {
            thread {
                SupabaseClient.fetchTodayRepostStatus(userId) { ok, status ->
                    if (ok && status == "pending") {
                        activity.runOnUiThread {
                            alreadyLogged = true
                            verifyBtn.text = activity.getString(R.string.pending_btn_post_again)
                        }
                    }
                }
            }
        }

        var verifyTapped = false
        verifyBtn.setOnClickListener {
            verifyTapped = true
            dialog.dismiss()
            repostNow(activity, alreadyLogged)
        }
        // Step 3: after 30+ views the user sends a screenshot to support on WhatsApp.
        view.findViewById<View>(R.id.pendingSheetProofBtn).setOnClickListener {
            dialog.dismiss()
            val username = SessionManager(activity).getUsername()
            SupportContact.openSupport(
                activity,
                activity.getString(R.string.pending_msg_proof, minViews) +
                    if (username.isNullOrBlank()) "" else activity.getString(R.string.pending_msg_username, username)
            )
        }
        // Skip the post: pay for verification on the Pay to verify screen (bank details + narration).
        view.findViewById<View>(R.id.pendingSheetPayBtn).setOnClickListener {
            dialog.dismiss()
            PayVerifyActivity.open(activity)
        }
        view.findViewById<View>(R.id.pendingSheetLaterBtn).setOnClickListener { dialog.dismiss() }

        // Motion: cards rise in one after another, the step icons pop, the Post button pulses.
        val animate = animationsEnabled(activity)
        val postWrap = view.findViewById<View>(R.id.pendingPostWrap)
        val risers = listOf(R.id.pendingStepsCard, R.id.pendingPostWrap, R.id.pendingSheetProofBtn, R.id.pendingFooterCard)
            .map { view.findViewById<View>(it) }
        val icons = listOf(R.id.pendingStep1Icon, R.id.pendingStep2Icon, R.id.pendingStep3Icon)
            .map { view.findViewById<View>(it) }
        var pulse: ObjectAnimator? = null
        if (animate) {
            val rise = 24f * activity.resources.displayMetrics.density
            risers.forEach { it.alpha = 0f; it.translationY = rise }
            icons.forEach { it.scaleX = 0f; it.scaleY = 0f }
        }
        pressEffect(verifyBtn)
        pressEffect(view.findViewById(R.id.pendingSheetProofBtn))
        pressEffect(view.findViewById(R.id.pendingSheetPayBtn))

        dialog.setOnDismissListener {
            pulse?.cancel()
            visible = false
            if (!verifyTapped) onLater()
        }
        dialog.show()
        if (animate) {
            risers.forEachIndexed { i, v ->
                v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(150L + i * 80L).setDuration(360)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
            icons.forEachIndexed { i, v ->
                v.animate().scaleX(1f).scaleY(1f)
                    .setStartDelay(380L + i * 120L).setDuration(380)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
            }
            postWrap.postDelayed({
                if (dialog.isShowing) {
                    pulse = ObjectAnimator.ofPropertyValuesHolder(
                        postWrap,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.03f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.03f)
                    ).apply {
                        duration = 900
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                }
            }, 900)
        }
    }
}
