package com.vgcontact.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * The app's one pop-up style: small icon, bold title, short message and pill
 * buttons (secondary on the left, main action on the right).
 * Replaces the plain MaterialAlertDialog used before.
 */
object VgDialog {

    enum class Tone { DANGER, WARNING, INFO }

    class Action(val label: String, val onClick: (() -> Unit)? = null)

    private const val RADIUS_DP = 24
    private const val INSET_DP = 20

    fun show(
        activity: Activity,
        tone: Tone,
        title: String,
        message: String,
        primary: Action,
        secondary: Action? = Action("Cancel"),
        extra: Action? = null
    ): AlertDialog {
        val root = column(activity)
        root.addView(titleView(activity, title, topMarginDp = 0))
        root.addView(bodyView(activity, message, topMarginDp = 10))

        var dialog: AlertDialog? = null
        val dismissThen = { a: Action? -> dialog?.dismiss(); a?.onClick?.invoke(); Unit }

        val primaryColor = if (tone == Tone.DANGER) R.color.vg_red else R.color.vg_green
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = activity.dp(24) }
        }
        // Same as VG RESHARE's confirm card: full-width buttons, main action on top.
        fun add(btn: View) {
            buttons.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(52))
                .apply { if (buttons.childCount > 0) topMargin = activity.dp(10) })
        }
        add(pill(activity, primary.label, filled = true, color = primaryColor) { dismissThen(primary) })
        if (extra != null) add(pill(activity, extra.label, filled = false, color = null) { dismissThen(extra) })
        if (secondary != null) add(pill(activity, secondary.label, filled = false, color = null) { dismissThen(secondary) })
        root.addView(buttons)

        val built = build(activity, root)
        dialog = built
        return built
    }

    /** Tiles instead of a radio list, e.g. "How often should contacts sync?". */
    fun showChoices(
        activity: Activity,
        title: String,
        message: String,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ): AlertDialog {
        val root = column(activity)
        root.addView(titleView(activity, title, topMarginDp = 0))
        if (message.isNotBlank()) root.addView(bodyView(activity, message, topMarginDp = 8))

        var dialog: AlertDialog? = null
        val green = ContextCompat.getColor(activity, R.color.vg_green)
        val greenDark = ContextCompat.getColor(activity, R.color.vg_green_dark)
        val tint = ContextCompat.getColor(activity, R.color.vg_green_tint)
        val line = ContextCompat.getColor(activity, R.color.card_border)
        val surface = ContextCompat.getColor(activity, R.color.surface)

        labels.forEachIndexed { i, label ->
            val on = i == selected
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(14), 0, activity.dp(14), 0)
                background = GradientDrawable().apply {
                    cornerRadius = activity.dp(16).toFloat()
                    setColor(if (on) tint else surface)
                    setStroke(activity.dp(2) / 1, if (on) green else line)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog?.dismiss(); onPick(i) }
            }
            val radio = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (on) { setColor(surface); setStroke(activity.dp(6), green) }
                    else { setColor(surface); setStroke(activity.dp(2), line) }
                }
            }
            tile.addView(radio, LinearLayout.LayoutParams(activity.dp(20), activity.dp(20)))
            tile.addView(TextView(activity).apply {
                text = label
                textSize = 14f
                typeface = ResourcesCompat.getFont(activity, R.font.poppins_medium)
                setTextColor(if (on) greenDark else ContextCompat.getColor(activity, R.color.vg_dark))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = activity.dp(12) })
            if (on) tile.addView(TextView(activity).apply {
                text = "Current"
                textSize = 11f
                typeface = ResourcesCompat.getFont(activity, R.font.poppins_medium)
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            })
            root.addView(tile, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(50)
            ).apply { topMargin = activity.dp(if (i == 0) 16 else 8) })
        }

        root.addView(
            pill(activity, "Cancel", filled = false, color = null) { dialog?.dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(50))
                .apply { topMargin = activity.dp(16) }
        )
        val built = build(activity, root)
        dialog = built
        return built
    }

    // ---------------------------------------------------------------- pieces

    private fun build(activity: Activity, content: View): AlertDialog {
        // Scrollable, so a long message or a large font size never pushes the buttons off screen.
        val scroll = android.widget.ScrollView(activity).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        val dialog = AlertDialog.Builder(activity).setView(scroll).create()
        dialog.show()
        val d = activity.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            setColor(ContextCompat.getColor(activity, R.color.surface))
            cornerRadius = RADIUS_DP * d
        }
        val inset = (INSET_DP * d).toInt()
        dialog.window?.setBackgroundDrawable(InsetDrawable(bg, inset, inset, inset, inset))
        return dialog
    }

    private fun column(activity: Activity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(24), activity.dp(24), activity.dp(24), activity.dp(24))
    }

    private fun titleView(activity: Activity, text: String, topMarginDp: Int) = TextView(activity).apply {
        this.text = text
        textSize = 18f
        typeface = ResourcesCompat.getFont(activity, R.font.poppins_extrabold)
        setTextColor(ContextCompat.getColor(activity, R.color.vg_dark))
        setLineSpacing(0f, 1.05f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(topMarginDp) }
    }

    private fun bodyView(activity: Activity, text: String, topMarginDp: Int) = TextView(activity).apply {
        // Each sentence starts on its own line: after a full stop (or ? or !)
        // followed by a space, drop down instead of running on.
        this.text = text.replace(Regex("(?<=[.!?]) +(?=\\S)"), "\n")
        textSize = 14f
        typeface = ResourcesCompat.getFont(activity, R.font.poppins)
        setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        setLineSpacing(0f, 1.15f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(topMarginDp) }
    }

    private fun pill(
        activity: Activity,
        label: String,
        filled: Boolean,
        color: Int?,
        onClick: () -> Unit
    ): View {
        val fill = if (filled) ContextCompat.getColor(activity, color!!) else ContextCompat.getColor(activity, R.color.surface)
        val shape = GradientDrawable().apply {
            cornerRadius = activity.dp(100).toFloat()
            setColor(fill)
            if (!filled) setStroke(activity.dp(2), ContextCompat.getColor(activity, R.color.vg_green))
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22000000")), shape, null)
        return TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = ResourcesCompat.getFont(activity, R.font.poppins_extrabold)
            setTextColor(if (filled) Color.WHITE else ContextCompat.getColor(activity, R.color.vg_green))
            background = ripple
            isClickable = true
            isFocusable = true
            maxLines = 1
            setOnClickListener { onClick() }
        }
    }

    private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
