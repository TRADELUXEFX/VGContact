package com.vgcontact.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * The one WhatsApp support number used across the app, plus the
 * "Buy Status Viewers" entry point. There is no in-app payment: buying
 * just opens a WhatsApp chat with support, prefilled with the username.
 */
object SupportContact {

    // 09110321143 in international format (Nigeria +234, no leading 0).
    const val WHATSAPP = "2349110321143"

    /** The support number as people write it locally, e.g. 0911 032 1143 (from [WHATSAPP]). */
    fun displayNumber(): String {
        val local = if (WHATSAPP.startsWith("234")) "0" + WHATSAPP.removePrefix("234") else WHATSAPP
        return if (local.length == 11) "${local.substring(0, 4)} ${local.substring(4, 7)} ${local.substring(7)}" else local
    }

    fun openBuyViewers(context: Context) {
        val username = SessionManager(context).getUsername()
        val message = "Hi VGContact, I'd like to buy status viewers." +
            if (username.isNullOrBlank()) "" else " My username is $username."
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$WHATSAPP?text=${Uri.encode(message)}"))
            )
        } catch (e: Exception) {
            Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens a WhatsApp chat with support with a ready-made message. */
    fun openSupport(context: Context, message: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$WHATSAPP?text=${Uri.encode(message)}"))
            )
        } catch (e: Exception) {
            Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
