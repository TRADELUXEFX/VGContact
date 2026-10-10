package com.vgcontact.app

import android.util.TypedValue
import android.widget.TextView
import androidx.core.widget.TextViewCompat

/**
 * Phone numbers and usernames must never be cut to "0803..." on phones that use a big
 * font size. Call this on a one-line TextView whose width is limited (weight 1 / 0dp):
 * the text keeps its normal size when it fits and shrinks, down to [minSp], when it does not.
 * [maxSp] must be the size the text normally has, so it never grows.
 */
object TextFit {
    fun shrinkToFit(tv: TextView, maxSp: Int, minSp: Int = 10) {
        tv.maxLines = 1
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            tv, minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP
        )
    }
}
