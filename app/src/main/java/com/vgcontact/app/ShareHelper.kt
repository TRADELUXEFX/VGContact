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

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
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
            // Open WhatsApp straight away when exactly one WhatsApp (normal or Business) is
            // installed; otherwise (both, or none) show the normal "Post to" menu.
            val whatsapps = WHATSAPP_PACKAGES.filter { isInstalled(activity, it) }
            if (whatsapps.size == 1) {
                intent.setPackage(whatsapps[0])
                try {
                    activity.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    intent.setPackage(null)
                }
            }
            activity.startActivity(Intent.createChooser(intent, "Post to"))
            true
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't share the image", Toast.LENGTH_SHORT).show()
            false
        }
    }

    // 1080 x 1350 invite card. Same layout language as VG Reshare's status image: green header with
    // rounded bottom corners, logo tile + title on the left (x = 70 / 226), a pill right-aligned at
    // x = 1011, and everything else on one 50..1030 grid (number card edges, three equal step
    // columns). Text is centred vertically with baseline = middle + size * 0.35, as in Reshare.
    // Drawn on the phone, no server.
    private fun drawCard(ctx: Context, phone: String): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val green = Color.parseColor("#1FAA59")
        val greenDark = Color.parseColor("#158245")
        val tint = Color.parseColor("#E1F5EB")
        val soft = Color.parseColor("#D6F4E2")
        val gray = Color.parseColor("#7A8580")
        val dark = Color.parseColor("#111B21")
        val line = Color.parseColor("#DCEEE1")

        fun font(res: Int, fallback: Typeface): Typeface = try {
            ResourcesCompat.getFont(ctx, res)
        } catch (e: Exception) {
            null
        } ?: fallback
        val regular = font(R.font.poppins, Typeface.DEFAULT)
        val semibold = font(R.font.poppins_semibold, Typeface.DEFAULT_BOLD)
        val bold = font(R.font.poppins_bold, Typeface.DEFAULT_BOLD)
        val extrabold = font(R.font.poppins_extrabold, bold)

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
        // Baseline that centres text of [size] on [mid] (the Reshare rule).
        fun mid(m: Float, size: Float) = m + size * 0.35f

        // Page background.
        c.drawColor(Color.parseColor("#F5FAF6"))

        // Header: green, bottom corners rounded (drawn from above the top edge so only the bottom curves).
        val header = RectF(0f, -120f, w.toFloat(), 500f)
        c.drawRoundRect(header, 100f, 100f, fill(green))
        c.save()
        c.clipPath(Path().apply { addRoundRect(header, 100f, 100f, Path.Direction.CW) })
        c.drawCircle(w - 60f, -40f, 200f, fill(Color.argb(26, 255, 255, 255)))
        c.drawCircle(40f, 470f, 110f, fill(Color.argb(20, 255, 255, 255)))
        c.restore()

        // Logo tile + title block on the left, pill right-aligned at x = 1011.
        val tile = RectF(70f, 100f, 190f, 220f)
        c.drawRoundRect(tile, 30f, 30f, fill(Color.WHITE))
        try {
            val d = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)
            if (d != null) {
                c.save()
                c.clipPath(Path().apply { addRoundRect(tile, 30f, 30f, Path.Direction.CW) })
                val half = 90 // adaptive icon: show its visible area across the 120px tile
                val tcx = tile.centerX().toInt()
                val tcy = tile.centerY().toInt()
                d.setBounds(tcx - half, tcy - half, tcx + half, tcy + half)
                d.draw(c)
                c.restore()
            }
        } catch (e: Exception) {
            drawEye(c, tile.centerX(), tile.centerY(), 40f, greenDark)
        }
        c.drawText("VGContact", 226f, 172f, paint(66f, Color.WHITE, extrabold))
        c.drawText("Free status viewers, every day", 226f, 216f, paint(32f, soft, regular))

        val pillPaint = paint(27f, greenDark, semibold).apply { textAlign = Paint.Align.CENTER }
        val pillW = pillPaint.measureText("FREE") + 44f
        val pill = RectF(1011f - pillW, 135f, 1011f, 184f)
        c.drawRoundRect(pill, 24.5f, 24.5f, fill(Color.WHITE))
        c.drawText("FREE", pill.centerX(), mid(pill.centerY(), 27f), pillPaint)

        // Headline.
        val head = paint(80f, Color.WHITE, bold)
        centered("Get FREE 500+", cx, 365f, head)
        centered("status viewers", cx, 455f, head)

        // Number card on the 50..1030 grid, with a soft shadow and a thin border.
        val card = RectF(50f, 560f, 1030f, 830f)
        val cardShadow = fill(Color.WHITE).apply { setShadowLayer(32f, 0f, 14f, Color.argb(55, 0, 0, 0)) }
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
        c.drawCircle(left + 26f, 630f, 26f, fill(tint))
        drawPhone(c, left + 26f, 630f, 26f, greenDark)
        c.drawText(labelText, left + 68f, mid(630f, 38f), label)
        val numPaint = paint(108f, greenDark, bold)
        val numText = formatPhone(phone)
        while (numPaint.measureText(numText) > 840f && numPaint.textSize > 50f) numPaint.textSize -= 4f
        centered(numText, cx, 780f, numPaint)

        // Three numbered steps: equal thirds of the 50..1030 grid, so they sit symmetric with the card.
        val col = 980f / 3f
        val xs = floatArrayOf(50f + col * 0.5f, 50f + col * 1.5f, 50f + col * 2.5f)
        val titles = arrayOf("Download", "Enter referral", "Get free")
        val subs = arrayOf("the app", "number at sign up", "500+ status viewers")
        val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 4f
            color = line
        }
        c.drawLine(xs[0] + 52f, 935f, xs[1] - 52f, 935f, rail)
        c.drawLine(xs[1] + 52f, 935f, xs[2] - 52f, 935f, rail)
        for (i in 0..2) {
            c.drawCircle(xs[i], 935f, 38f, fill(green))
            centered((i + 1).toString(), xs[i], mid(935f, 40f), paint(40f, Color.WHITE, bold))
            centered(titles[i], xs[i], 1027f, paint(32f, dark, bold))
            centered(subs[i], xs[i], 1069f, paint(28f, gray, regular))
        }

        // Instruction + link button (icon and link centred as one group).
        centered("Tap the link below to download the app", cx, 1160f, paint(30f, gray, regular))
        val linkText = ReferralActivity.LINK_BASE.removePrefix("https://") + phone
        val linkPaint = paint(36f, Color.WHITE, bold)
        while (linkPaint.measureText(linkText) > 800f && linkPaint.textSize > 22f) linkPaint.textSize -= 1f
        val iconW = 48f
        val gap = 18f
        val groupW = iconW + gap + linkPaint.measureText(linkText)
        val btnW = groupW + 110f
        val btn = RectF(cx - btnW / 2f, 1195f, cx + btnW / 2f, 1295f)
        c.drawRoundRect(btn, 50f, 50f, fill(green))
        val gx = cx - groupW / 2f
        drawLink(c, gx + iconW / 2f - 4f, 1245f, 20f, Color.WHITE)
        c.drawText(linkText, gx + iconW + gap, mid(1245f, linkPaint.textSize), linkPaint)

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
