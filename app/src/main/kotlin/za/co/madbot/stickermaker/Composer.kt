package za.co.madbot.stickermaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Text placed on the sticker. Positions and size are fractions of the 512px canvas. */
data class TextOverlay(
    val text: String,
    val color: Int = Color.WHITE,
    val size: Float = 0.12f,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
)

/** Builds the final 512x512 sticker frame from a working bitmap: fit, outline, text. */
object Composer {
    const val SIZE = 512
    const val OUTLINE_PX = 10f

    fun compose(src: Bitmap, outline: Boolean, texts: List<TextOverlay>): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val pad = if (outline) OUTLINE_PX + 4 else 4f
        val avail = SIZE - 2 * pad
        val s = min(avail / src.width, avail / src.height)
        val w = src.width * s
        val h = src.height * s
        val dst = RectF((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2)
        if (outline) drawOutline(c, src, dst)
        c.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        texts.forEach { drawText(c, it) }
        return out
    }

    private fun drawOutline(c: Canvas, src: Bitmap, dst: RectF) {
        val alpha = src.extractAlpha()
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        for (radius in floatArrayOf(OUTLINE_PX, OUTLINE_PX * 0.5f)) {
            for (k in 0 until 24) {
                val a = Math.PI * 2 * k / 24
                val r = RectF(dst)
                r.offset((cos(a) * radius).toFloat(), (sin(a) * radius).toFloat())
                c.drawBitmap(alpha, null, r, p)
            }
        }
        alpha.recycle()
    }

    private fun layout(t: TextOverlay, tp: TextPaint): StaticLayout {
        tp.textSize = t.size * SIZE
        tp.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        return StaticLayout.Builder.obtain(t.text, 0, t.text.length, tp, (SIZE * 0.9f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
    }

    /** Visible box of [t] in canvas fractions (0..1). */
    fun textBox(t: TextOverlay): RectF {
        val l = layout(t, TextPaint(Paint.ANTI_ALIAS_FLAG))
        var w = 0f
        for (i in 0 until l.lineCount) w = max(w, l.getLineWidth(i))
        val hw = w / SIZE / 2
        val hh = l.height.toFloat() / SIZE / 2
        return RectF(t.cx - hw, t.cy - hh, t.cx + hw, t.cy + hh)
    }

    private fun drawText(c: Canvas, t: TextOverlay) {
        if (t.text.isBlank()) return
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val l = layout(t, tp)
        c.save()
        c.translate(t.cx * SIZE - l.width / 2f, t.cy * SIZE - l.height / 2f)
        val lum = (Color.red(t.color) * 299 + Color.green(t.color) * 587 + Color.blue(t.color) * 114) / 1000
        tp.style = Paint.Style.STROKE
        tp.strokeJoin = Paint.Join.ROUND
        tp.strokeWidth = tp.textSize * 0.16f
        tp.color = if (lum > 140) Color.BLACK else Color.WHITE
        l.draw(c)
        tp.style = Paint.Style.FILL
        tp.color = t.color
        l.draw(c)
        c.restore()
    }
}

/** Encodes finished 512x512 frames, squeezing quality / frame count to fit WhatsApp's limits. */
object StickerEncoder {
    const val STATIC_MAX = 100 * 1024
    const val ANIMATED_MAX = 500 * 1024
    private const val MAX_DURATION_MS = 9500
    private const val MIN_DELAY_MS = 20
    private const val START_FRAMES = 60

    fun encodeStatic(b: Bitmap): ByteArray {
        var q = 95
        while (true) {
            val bos = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            b.compress(fmt, q, bos)
            if (bos.size() <= STATIC_MAX) return bos.toByteArray()
            if (q <= 20) error("Sticker is ${bos.size() / 1024} KB even at lowest quality (limit 100 KB)")
            q -= 10
        }
    }

    fun encodeAnimated(frames: List<Bitmap>, delays: List<Int>, progress: (String) -> Unit): ByteArray {
        // Clamp delays and cut to the maximum duration.
        var fr = ArrayList<Bitmap>()
        var dl = ArrayList<Int>()
        var total = 0
        for (i in frames.indices) {
            val d = max(delays[i], MIN_DELAY_MS)
            if (total + d > MAX_DURATION_MS && fr.isNotEmpty()) break
            fr.add(frames[i]); dl.add(d); total += d
        }
        // Start from a sane frame budget: 100+ frame encodes are slow and rarely fit in 500 KB anyway.
        while (fr.size > START_FRAMES) {
            val nf = ArrayList<Bitmap>()
            val nd = ArrayList<Int>()
            var i = 0
            while (i < fr.size) {
                nf.add(fr[i])
                nd.add(dl[i] + if (i + 1 < fr.size) dl[i + 1] else 0)
                i += 2
            }
            fr = nf; dl = nd
        }
        val qualities = intArrayOf(70, 45, 25)
        var lastSize = 0
        for (round in 0 until 3) {
            for (q in qualities) {
                progress("Encoding ${fr.size} frames (quality $q)...")
                val t0 = android.os.SystemClock.uptimeMillis()
                val bytes = encode(fr, dl, q)
                android.util.Log.i("StickerEncoder", "${fr.size} frames q=$q -> ${bytes.size / 1024} KB in ${android.os.SystemClock.uptimeMillis() - t0} ms")
                lastSize = bytes.size
                if (bytes.size <= ANIMATED_MAX) return bytes
            }
            if (fr.size < 8) break
            // Drop every other frame, merging delays, and try again.
            val nf = ArrayList<Bitmap>()
            val nd = ArrayList<Int>()
            var i = 0
            while (i < fr.size) {
                nf.add(fr[i])
                nd.add(dl[i] + if (i + 1 < fr.size) dl[i + 1] else 0)
                i += 2
            }
            fr = nf; dl = nd
        }
        error("Animation is ${lastSize / 1024} KB even after reducing quality and frames (limit 500 KB)")
    }

    private fun encode(frames: List<Bitmap>, delays: List<Int>, quality: Int): ByteArray {
        val px = Array(frames.size) { i ->
            IntArray(Composer.SIZE * Composer.SIZE).also {
                frames[i].getPixels(it, 0, Composer.SIZE, 0, 0, Composer.SIZE, Composer.SIZE)
            }
        }
        return WebpNative.encodeAnimated(px, Composer.SIZE, Composer.SIZE, delays.toIntArray(), quality, 3)
            ?: error("Animated WebP encoder failed")
    }
}
