package com.vgcontact.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * What a user posts to their WhatsApp status for verification (Repost screen and the
 * pending sheet). They choose:
 *  - text + link, or
 *  - image + link (the image is drawn here with the user's own number).
 * The link is always ReferralActivity.LINK_BASE + the user's phone number.
 * Video + link comes later.
 */
object ShareHelper {

    /** Shows a small "Share as" menu. */
    fun showMenu(activity: Activity, phone: String, onShared: () -> Unit = {}) {
        if (phone.isBlank()) {
            Toast.makeText(activity, "Number unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf("Text + link", "Image + link")
        AlertDialog.Builder(activity)
            .setTitle("What do you want to post?")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (shareText(activity, phone)) onShared()
                    1 -> if (shareImage(activity, phone)) onShared()
                }
            }
            .show().also { RoundedDialog.style(it) }
    }

    private fun link(phone: String) = ReferralActivity.LINK_BASE + phone

    private fun caption(phone: String) = buildString {
        append("Join me on VGContact! ")
        append("Download it here: ${link(phone)}")
        append("\nWhen you sign up, enter my referral number: $phone")
    }

    fun shareText(activity: Activity, phone: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, caption(phone))
            }
            activity.startActivity(Intent.createChooser(intent, "Post to"))
            true
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't open share menu", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun shareImage(activity: Activity, phone: String): Boolean {
        return try {
            val dir = File(activity.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "vgcontact_invite.png")
            val bmp = drawCard(phone)
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, caption(phone))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Post to"))
            true
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't share the image", Toast.LENGTH_SHORT).show()
            false
        }
    }

    // 1080 x 1350 invite card: green background, white box with the user's number.
    private fun drawCard(phone: String): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#1FAA59"))

        fun text(size: Float, bold: Boolean, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        fun centered(s: String, paint: TextPaint, top: Float, width: Int = 880): Float {
            val layout = StaticLayout.Builder
                .obtain(s, 0, s.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
            c.save()
            c.translate((w - width) / 2f, top)
            layout.draw(c)
            c.restore()
            return top + layout.height
        }

        var y = 150f
        y = centered("Get FREE status viewers", text(84f, true, Color.WHITE), y) + 40f
        y = centered("Join me on VGContact", text(48f, false, Color.parseColor("#E1F5EB")), y) + 90f

        val boxTop = y
        val boxH = 380f
        val box = RectF(90f, boxTop, w - 90f, boxTop + boxH)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawRoundRect(box, 48f, 48f, boxPaint)
        centered("My referral number", text(44f, false, Color.parseColor("#555555")), boxTop + 60f)
        centered(formatPhone(phone), text(92f, true, Color.parseColor("#158245")), boxTop + 170f)

        y = boxTop + boxH + 90f
        y = centered("Download the app and enter my number when you sign up", text(46f, false, Color.WHITE), y) + 60f
        centered(ReferralActivity.LINK_BASE.removePrefix("https://") + phone, text(40f, true, Color.WHITE), y, 940)

        return bmp
    }

    private fun formatPhone(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return if (d.length == 11) "${d.substring(0, 4)} ${d.substring(4, 7)} ${d.substring(7)}" else raw
    }
}
