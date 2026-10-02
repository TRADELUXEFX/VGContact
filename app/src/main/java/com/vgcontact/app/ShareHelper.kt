package com.vgcontact.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.io.FileOutputStream

/**
 * What a user posts to their WhatsApp status for verification (Repost screen and the
 * pending sheet): always image + caption with link (the image is drawn here with the
 * user's own number). There is no text-only option and no menu.
 * The link is always ReferralActivity.LINK_BASE + the user's phone number.
 * Video + link comes later.
 */
object ShareHelper {

    /**
     * Posts the image + caption + link straight away (no "Share as" menu: the app only
     * uses image + caption with link). [onShared] runs once the share sheet opens.
     */
    fun showMenu(activity: Activity, phone: String, onShared: () -> Unit = {}) {
        if (phone.isBlank()) {
            Toast.makeText(activity, "Number unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        if (shareImage(activity, phone)) onShared()
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
            val bmp = drawCard(activity, phone)
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

    // 1080 x 1350 invite card ("clean white"): green header with logo and headline, white
    // number card, three numbered steps, and the link as a green button. Drawn on the
    // phone, no server.
    private fun drawCard(ctx: Context, phone: String): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val green = Color.parseColor("#1FAA59")
        val greenDark = Color.parseColor("#158245")
        val tint = Color.parseColor("#E1F5EB")
        val gray = Color.parseColor("#7A8580")
        val dark = Color.parseColor("#111B21")
        val line = Color.parseColor("#DCEEE1")

        val bold: Typeface = try {
            ResourcesCompat.getFont(ctx, R.font.poppins_bold)
        } catch (e: Exception) {
            null
        } ?: Typeface.DEFAULT_BOLD
        val regular: Typeface = try {
            ResourcesCompat.getFont(ctx, R.font.poppins)
        } catch (e: Exception) {
            null
        } ?: Typeface.DEFAULT

        fun paint(size: Float, color: Int, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = face
        }
        fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        fun centered(t: String, x: Float, y: Float, p: TextPaint) {
            p.textAlign = Paint.Align.CENTER
            c.drawText(t, x, y, p)
            p.textAlign = Paint.Align.LEFT
        }

        // Page background + green header.
        c.drawColor(Color.parseColor("#F5FAF6"))
        c.drawRect(0f, 0f, w.toFloat(), 470f, fill(green))
        val soft = fill(Color.argb(28, 255, 255, 255))
        c.drawCircle(w - 80f, 60f, 230f, soft)
        c.drawCircle(60f, 340f, 120f, fill(Color.argb(22, 255, 255, 255)))

        // Logo in a white ring.
        c.drawCircle(cx, 120f, 62f, fill(Color.WHITE))
        try {
            val d = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)
            if (d != null) {
                c.save()
                c.clipPath(Path().apply { addCircle(cx, 120f, 56f, Path.Direction.CW) })
                val half = (56f * 1.5f).toInt()
                d.setBounds(cx.toInt() - half, 120 - half, cx.toInt() + half, 120 + half)
                d.draw(c)
                c.restore()
            }
        } catch (e: Exception) {
            drawEye(c, cx, 120f, 40f, greenDark)
        }

        // Headline.
        val head = paint(84f, Color.WHITE, bold)
        centered("Get FREE 500+", cx, 300f, head)
        centered("status viewers", cx, 395f, head)

        // Number card with a soft shadow and a thin border.
        val card = RectF(80f, 520f, w - 80f, 800f)
        val cardShadow = fill(Color.WHITE).apply { setShadowLayer(32f, 0f, 14f, Color.argb(60, 0, 0, 0)) }
        c.drawRoundRect(card, 48f, 48f, cardShadow)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = line
        }
        c.drawRoundRect(card, 48f, 48f, border)
        val label = paint(38f, gray, regular)
        val labelText = "My referral number"
        val rowW = 52f + 16f + label.measureText(labelText)
        val left = cx - rowW / 2f
        c.drawCircle(left + 26f, 590f, 26f, fill(tint))
        drawPhone(c, left + 26f, 590f, 26f, greenDark)
        c.drawText(labelText, left + 68f, 603f, label)
        val numPaint = paint(108f, greenDark, bold)
        val numText = formatPhone(phone)
        while (numPaint.measureText(numText) > 780f && numPaint.textSize > 50f) numPaint.textSize -= 4f
        centered(numText, cx, 740f, numPaint)

        // Three numbered steps.
        val xs = floatArrayOf(190f, 540f, 890f)
        val titles = arrayOf("Download", "Enter referral", "Get free")
        val subs = arrayOf("the app", "number at sign up", "500+ status viewers")
        val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 4f
            color = line
        }
        c.drawLine(xs[0] + 45f, 880f, xs[1] - 45f, 880f, rail)
        c.drawLine(xs[1] + 45f, 880f, xs[2] - 45f, 880f, rail)
        for (i in 0..2) {
            c.drawCircle(xs[i], 880f, 38f, fill(green))
            centered((i + 1).toString(), xs[i], 893f, paint(40f, Color.WHITE, bold))
            centered(titles[i], xs[i], 975f, paint(32f, dark, bold))
            centered(subs[i], xs[i], 1017f, paint(28f, gray, regular))
        }

        // Instruction + link button.
        centered("Tap the link below to download the app", cx, 1105f, paint(30f, gray, regular))
        val linkText = ReferralActivity.LINK_BASE.removePrefix("https://") + phone
        val linkPaint = paint(36f, Color.WHITE, bold)
        while (linkPaint.measureText(linkText) > 760f && linkPaint.textSize > 22f) linkPaint.textSize -= 1f
        val btnW = linkPaint.measureText(linkText) + 130f
        val btn = RectF(cx - btnW / 2f, 1140f, cx + btnW / 2f, 1240f)
        c.drawRoundRect(btn, 50f, 50f, fill(green))
        drawLink(c, btn.left + 56f, 1190f, 20f, Color.WHITE)
        c.drawText(linkText, btn.left + 96f, 1190f + 13f, linkPaint)

        return bmp
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Path().apply {
            moveTo(cx - r * 0.9f, cy)
            quadTo(cx, cy - r * 1.05f, cx + r * 0.9f, cy)
            quadTo(cx, cy + r * 1.05f, cx - r * 0.9f, cy)
            close()
        }
        c.drawPath(p, stroke(color, r * 0.16f))
        c.drawCircle(cx, cy, r * 0.26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun drawPhone(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val rect = RectF(cx - r * 0.34f, cy - r * 0.56f, cx + r * 0.34f, cy + r * 0.56f)
        c.drawRoundRect(rect, r * 0.14f, r * 0.14f, stroke(color, r * 0.12f))
        c.drawLine(cx - r * 0.1f, cy + r * 0.38f, cx + r * 0.1f, cy + r * 0.38f, stroke(color, r * 0.12f))
    }

    private fun drawLink(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        c.save()
        c.rotate(-45f, cx, cy)
        val p = stroke(color, r * 0.2f)
        c.drawRoundRect(RectF(cx - r * 1.2f, cy - r * 0.4f, cx - r * 0.05f, cy + r * 0.4f), r * 0.4f, r * 0.4f, p)
        c.drawRoundRect(RectF(cx + r * 0.05f, cy - r * 0.4f, cx + r * 1.2f, cy + r * 0.4f), r * 0.4f, r * 0.4f, p)
        c.drawLine(cx - r * 0.4f, cy, cx + r * 0.4f, cy, p)
        c.restore()
    }

    private fun formatPhone(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return if (d.length == 11) "${d.substring(0, 4)} ${d.substring(4, 7)} ${d.substring(7)}" else raw
    }
}
