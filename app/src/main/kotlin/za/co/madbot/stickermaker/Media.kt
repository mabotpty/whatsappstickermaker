package za.co.madbot.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeader
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class Frames(val bitmaps: List<Bitmap>, val delays: List<Int>) {
    val animated get() = bitmaps.size > 1
}

/** Loads jpg/png/webp/gif/heic/avif/bmp (and animated gif / animated webp) into [Frames]. */
object MediaDecoder {
    private const val MAX_STATIC = 1536
    private const val MAX_ANIMATED = 512
    private const val MAX_FRAMES = 120

    fun load(ctx: Context, uri: Uri): Frames {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot open the selected file")
        return when {
            isGif(bytes) -> decodeGif(bytes)
            isAnimatedWebp(bytes) -> decodeAnimatedWebp(bytes)
            else -> Frames(listOf(decodeStatic(bytes)), listOf(100))
        }
    }

    private fun isGif(b: ByteArray) = b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()

    private fun isAnimatedWebp(b: ByteArray): Boolean {
        if (b.size < 21) return false
        fun tag(o: Int, s: String) = s.indices.all { b[o + it] == s[it].code.toByte() }
        return tag(0, "RIFF") && tag(8, "WEBP") && tag(12, "VP8X") && (b[20].toInt() and 0x02) != 0
    }

    private fun decodeStatic(bytes: ByteArray): Bitmap {
        val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val m = max(w, h)
            if (m > MAX_STATIC) {
                val s = MAX_STATIC.toFloat() / m
                dec.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
            }
        }
    }

    private class Provider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config) = Bitmap.createBitmap(width, height, config)
        override fun release(bitmap: Bitmap) {}
        override fun obtainByteArray(size: Int) = ByteArray(size)
        override fun release(bytes: ByteArray) {}
        override fun obtainIntArray(size: Int) = IntArray(size)
        override fun release(array: IntArray) {}
    }

    private fun decodeGif(bytes: ByteArray): Frames {
        val header: GifHeader = GifHeaderParser().setData(bytes).parseHeader()
        if (header.numFrames <= 0 || header.status != GifDecoder.STATUS_OK) error("Could not read this GIF")
        val sample = Integer.highestOneBit((max(header.width, header.height) / MAX_ANIMATED).coerceAtLeast(1))
        val d = StandardGifDecoder(Provider(), header, ByteBuffer.wrap(bytes), sample)
        d.setDefaultBitmapConfig(Bitmap.Config.ARGB_8888)
        val frames = ArrayList<Bitmap>()
        val delays = ArrayList<Int>()
        for (i in 0 until d.frameCount) {
            d.advance()
            val b = d.nextFrame ?: continue
            frames.add(b.copy(Bitmap.Config.ARGB_8888, false))
            delays.add(d.getDelay(i))
        }
        return finishAnimated(frames, delays)
    }

    private fun decodeAnimatedWebp(bytes: ByteArray): Frames {
        val r = WebpNative.decodeAnimated(bytes, 0) ?: error("Could not read this animated WebP")
        val head = r[0] ?: error("Could not read this animated WebP")
        val w = head[0]
        val h = head[1]
        val frames = ArrayList<Bitmap>()
        val delays = ArrayList<Int>()
        for (i in 1 until r.size) {
            val px = r[i] ?: continue
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.setPixels(px, 0, w, 0, 0, w, h)
            frames.add(b)
            delays.add(head[1 + i])
        }
        return finishAnimated(frames, delays)
    }

    /** Normalises delays, caps the frame count and downsizes big frames. */
    private fun finishAnimated(frames: List<Bitmap>, delays: List<Int>): Frames {
        if (frames.isEmpty()) error("No frames found")
        if (frames.size == 1) return Frames(frames, listOf(100))
        // Browsers treat tiny delays as 100 ms; do the same.
        val fixed = delays.map { if (it <= 10) 100 else it }
        val step = ceil(frames.size / MAX_FRAMES.toDouble()).toInt().coerceAtLeast(1)
        val outF = ArrayList<Bitmap>()
        val outD = ArrayList<Int>()
        var i = 0
        while (i < frames.size) {
            var sum = 0
            for (k in i until minOf(i + step, frames.size)) sum += fixed[k]
            outF.add(shrink(frames[i], MAX_ANIMATED))
            outD.add(sum)
            i += step
        }
        return Frames(outF, outD)
    }

    private fun shrink(b: Bitmap, maxSide: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= maxSide) return b
        val s = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
    }
}
