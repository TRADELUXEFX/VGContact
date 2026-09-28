package com.vgcontact.app

import android.app.Activity
import android.view.View
import android.widget.TextView

/**
 * Wires up the shared Back + title header (layout_back_header.xml).
 * One place to change so every screen with a Back button and a title
 * stays perfectly aligned.
 */
object BackHeader {

    /** Sets the title and makes Back call [onBack] (default: finish the screen). */
    fun bind(activity: Activity, title: String = "", onBack: () -> Unit = { activity.finish() }) {
        activity.findViewById<TextView?>(R.id.back_header_title)?.text = title
        activity.findViewById<View>(R.id.back_header_btn).setOnClickListener { onBack() }
    }
}
