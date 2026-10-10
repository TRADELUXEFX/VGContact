package com.vgcontact.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.NestedScrollView

/**
 * First-run dashboard tour: a full-screen dimming scrim with a rounded-rect
 * cutout ("spotlight") around one real button at a time, plus a fixed
 * tooltip card explaining it. The cutout is punched with PorterDuff.CLEAR
 * on a hardware layer, so everything outside the target stays dark and
 * only the target itself reads at full brightness.
 *
 * Ported from VGKontact's CoachMarkOverlay. Differences for VGContact:
 *  - Home is a scroll view with targets below the fold, so each step can
 *    pass a [Step.scrollParent] and the target is scrolled into view
 *    before it is spotlighted.
 *  - The "done" flag lives in its own prefs file, NOT SessionManager,
 *    because SessionManager.logout() clears everything and the tour
 *    would replay after every logout/login.
 *
 * Runs once per install. Call showIfNeeded(activity, steps) after the
 * screen has been laid out (e.g. from a view.post{} in onCreate).
 */
object CoachMarkOverlay {

    private const val PREFS = "vg_tips"
    private const val KEY_TOUR_DONE = "home_tour_done"
    private const val OVERLAY_TAG = "vg_coach_mark_scrim"

    fun isTourDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOUR_DONE, false)

    private fun setTourDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TOUR_DONE, true).apply()
    }

    data class Step(
        val target: View,
        val title: String,
        val message: String,
        val dockAtBottom: Boolean = false,
        /** If the target lives inside a scroll view, pass it so the target is scrolled into view first. */
        val scrollParent: NestedScrollView? = null
    )

    fun showIfNeeded(activity: Activity, steps: List<Step>, onFinished: (() -> Unit)? = null) {
        if (isTourDone(activity)) return
        if (steps.isEmpty()) return

        // android.R.id.content is the Activity's true top-level container.
        // Adding views here guarantees they draw above everything else on
        // screen, including the floating bottom nav bar in its own layout.
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // Never stack a second overlay on top of a running tour.
        if (root.findViewWithTag<View>(OVERLAY_TAG) != null) return

        val scrim = SpotlightScrimView(activity)
        scrim.tag = OVERLAY_TAG
        val tooltip = TooltipView(activity)
        // Hidden until the first showStep() binds content and docks it,
        // otherwise it flashes at its default position for a frame.
        tooltip.root.visibility = View.GONE

        var index = 0

        fun finish() {
            setTourDone(activity)
            root.removeView(scrim)
            root.removeView(tooltip.root)
            onFinished?.invoke()
        }

        fun showStep() {
            val step = steps[index]

            fun apply() {
                positionSpotlight(scrim, step.target, root)
                tooltip.bind(
                    title = step.title,
                    message = step.message,
                    counter = "${index + 1}/${steps.size}",
                    nextLabel = if (index == steps.size - 1) "Got it" else "Next"
                )
                // Dock on whichever side leaves the target uncovered. The
                // old fixed dockAtBottom flag hid buttons that ended up
                // low on screen (a short page can't scroll them higher).
                tooltip.dockAt(top = shouldDockAtTop(tooltip, step.target, root, step.dockAtBottom))
                tooltip.root.visibility = View.VISIBLE
            }

            val scroller = step.scrollParent
            if (scroller != null) {
                // Hide the spotlight while scrolling so the cutout never
                // sits over the wrong spot, then re-measure once the
                // scroll has settled.
                scrim.clearHole()
                val density = activity.resources.displayMetrics.density
                val targetTop = offsetInScroller(step.target, scroller)
                val desired = (targetTop - 160 * density).toInt().coerceAtLeast(0)
                scroller.smoothScrollTo(0, desired)
                scroller.postDelayed({ apply() }, 350)
            } else {
                apply()
            }
        }

        tooltip.onNext = {
            if (index < steps.size - 1) {
                index += 1
                showStep()
            } else {
                finish()
            }
        }
        tooltip.onSkip = { finish() }

        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(tooltip.root, tooltip.root.layoutParams)

        // The floating "Contact Us" bubble (FloatingContactHelper) sets its
        // own elevation (12dp). Elevation wins over add-order, so without
        // this the FAB would poke through the scrim and stay tappable.
        val density = activity.resources.displayMetrics.density
        scrim.elevation = 14 * density
        tooltip.root.elevation = 16 * density

        // Wait for a real completed layout pass on the first target
        // instead of guessing a frame count - the Home card is still
        // reflowing (key balance loads async), so an early measure can
        // return 0x0 or a stale size.
        val firstTarget = steps[0].target
        if (firstTarget.width > 0 && firstTarget.height > 0) {
            showStep()
        } else {
            val vto = firstTarget.viewTreeObserver
            val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (firstTarget.width > 0 && firstTarget.height > 0) {
                        if (vto.isAlive) vto.removeOnGlobalLayoutListener(this)
                        showStep()
                    }
                }
            }
            vto.addOnGlobalLayoutListener(listener)
        }
    }

    /**
     * True when the tooltip should sit at the top of the screen. Measures the
     * tooltip and checks which side the target does not overlap; when both
     * sides are free it keeps the step's preferred side.
     */
    private fun shouldDockAtTop(tooltip: TooltipView, target: View, root: View, preferBottom: Boolean): Boolean {
        val density = root.resources.displayMetrics.density
        val t = IntArray(2)
        target.getLocationInWindow(t)
        val r = IntArray(2)
        root.getLocationInWindow(r)
        val targetTop = t[1] - r[1] - 6 * density
        val targetBottom = t[1] - r[1] + target.height + 6 * density

        val spec = View.MeasureSpec.makeMeasureSpec(
            root.width - (32 * density).toInt(), View.MeasureSpec.EXACTLY
        )
        tooltip.root.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = tooltip.root.measuredHeight

        val gap = 8 * density
        val topEdgeOfBottomDock = root.height - 96 * density - h   // tooltip top when docked at bottom
        val bottomEdgeOfTopDock = 28 * density + h                  // tooltip bottom when docked at top
        val bottomFree = targetBottom + gap <= topEdgeOfBottomDock
        val topFree = targetTop - gap >= bottomEdgeOfTopDock

        return when {
            bottomFree && topFree -> !preferBottom
            bottomFree -> false
            topFree -> true
            else -> (targetTop + targetBottom) / 2 > root.height / 2   // neither fits: go opposite the target
        }
    }

    /** Target's top edge relative to the scroller's content, walking up the parent chain. */
    private fun offsetInScroller(target: View, scroller: NestedScrollView): Int {
        var y = 0
        var v: View = target
        while (v !== scroller) {
            y += v.top
            val parent = v.parent as? View ?: break
            v = parent
        }
        return y
    }

    /**
     * Moves the spotlight cutout to sit around target's current on-screen
     * position. getLocationInWindow() on both views, subtracted, read
     * fresh at call time so scroll offsets are already accounted for.
     */
    private fun positionSpotlight(scrim: SpotlightScrimView, target: View, root: View) {
        val t = IntArray(2)
        target.getLocationInWindow(t)
        val r = IntArray(2)
        root.getLocationInWindow(r)

        val pad = 6 * scrim.resources.displayMetrics.density
        val left = (t[0] - r[0]).toFloat() - pad
        val top = (t[1] - r[1]).toFloat() - pad
        scrim.setHole(
            RectF(left, top, left + target.width + pad * 2, top + target.height + pad * 2)
        )
    }

    /**
     * Full-screen dimming scrim that punches a rounded-rect hole around
     * the current target, with a thin green stroke tracing the cutout.
     */
    private class SpotlightScrimView(activity: Activity) : View(activity) {
        private val density = activity.resources.displayMetrics.density
        private val cornerRadius = 16 * density
        private val hole = RectF()

        private val dimPaint = Paint().apply {
            color = Color.parseColor("#CC000000") // ~80% black
            isAntiAlias = true
        }
        private val clearPaint = Paint().apply {
            isAntiAlias = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            color = ContextCompat.getColor(activity, R.color.vg_green)
            isAntiAlias = true
        }

        init {
            // CLEAR blending only works correctly on a hardware layer.
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        fun setHole(rect: RectF) {
            hole.set(rect)
            invalidate()
        }

        fun clearHole() {
            hole.setEmpty()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            if (hole.isEmpty) return
            canvas.drawRoundRect(hole, cornerRadius, cornerRadius, clearPaint)
            canvas.drawRoundRect(hole, cornerRadius, cornerRadius, strokePaint)
        }

        // Block every touch except inside the cutout, so the dimmed area
        // can't be tapped through - only the spotlighted target stays
        // interactive during the tour.
        override fun onTouchEvent(event: MotionEvent): Boolean {
            return !hole.contains(event.x, event.y)
        }
    }

    /** The tooltip card. Docks at a fixed spot - top or bottom - and never follows the target. */
    private class TooltipView(private val activity: Activity) {
        var onNext: (() -> Unit)? = null
        var onSkip: (() -> Unit)? = null

        val root: LinearLayout
        private val titleView: TextView
        private val messageView: TextView
        private val counterView: TextView
        private val nextButton: Button
        private val skipView: TextView

        init {
            val built = build()
            root = built.first
            counterView = built.second[0] as TextView
            titleView = built.second[1] as TextView
            messageView = built.second[2] as TextView
            skipView = built.second[3] as TextView
            nextButton = built.second[4] as Button

            nextButton.setOnClickListener { onNext?.invoke() }
            skipView.setOnClickListener { onSkip?.invoke() }
        }

        fun bind(title: String, message: String, counter: String, nextLabel: String) {
            titleView.text = title
            messageView.text = message
            counterView.text = counter
            nextButton.text = nextLabel
        }

        fun dockAt(top: Boolean) {
            val density = activity.resources.displayMetrics.density
            val sideMargin = (16 * density).toInt()
            val statusBarClearance = (28 * density).toInt()
            val navBarClearance = (96 * density).toInt() // floating nav pill + its margin

            val params = root.layoutParams as FrameLayout.LayoutParams
            params.leftMargin = sideMargin
            params.rightMargin = sideMargin
            if (top) {
                params.gravity = Gravity.TOP
                params.topMargin = statusBarClearance
                params.bottomMargin = 0
            } else {
                params.gravity = Gravity.BOTTOM
                params.topMargin = 0
                params.bottomMargin = navBarClearance
            }
            root.layoutParams = params
        }

        private fun build(): Pair<LinearLayout, List<View>> {
            val density = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(activity, R.drawable.coach_mark_tooltip_background)
                setPadding(dp(20), dp(14), dp(20), dp(14))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                elevation = dp(12).toFloat()
            }

            // Fonts: these are built in code, so the XML fontFamily
            // attributes used elsewhere in the app don't apply - set the
            // Poppins face the rest of the UI uses.
            val poppinsBold = ResourcesCompat.getFont(activity, R.font.poppins_bold)
            val poppins = ResourcesCompat.getFont(activity, R.font.poppins)
            val poppinsSemi = ResourcesCompat.getFont(activity, R.font.poppins_semibold)
            val poppinsExtra = ResourcesCompat.getFont(activity, R.font.poppins_extrabold)

            val title = TextView(activity).apply {
                textSize = 18f
                includeFontPadding = false
                typeface = poppinsSemi
                setTextColor(ContextCompat.getColor(activity, R.color.vg_dark))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            container.addView(title)

            val message = TextView(activity).apply {
                textSize = 14f
                typeface = poppins
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            container.addView(message)

            // Counter sits on the left of the actions row: "3/6   Skip   Next"
            val counter = TextView(activity).apply {
                textSize = 13f
                typeface = poppins
                setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val actionsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
            }
            actionsRow.addView(counter)

            val buttonGroup = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val skip = TextView(activity).apply {
                text = "Skip"
                textSize = 14f
                typeface = poppins
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                background = ContextCompat.getDrawable(activity, R.drawable.coach_mark_skip_background)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(10) }
            }
            buttonGroup.addView(skip)

            val next = Button(activity).apply {
                text = "Next"
                textSize = 14f
                typeface = poppinsExtra
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = ContextCompat.getDrawable(activity, R.drawable.coach_mark_button_background)
                stateListAnimator = null
                setPadding(dp(20), dp(10), dp(20), dp(10))
            }
            buttonGroup.addView(next)

            actionsRow.addView(buttonGroup)
            container.addView(actionsRow)
            return Pair(container, listOf(counter, title, message, skip, next))
        }
    }
}
