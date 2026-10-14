package za.co.madbot.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One view, four modes:
 *  PREVIEW  - shows the finished 512x512 sticker (animated if it has frames); drag text around.
 *  CROP     - shows the working bitmap with a draggable crop rectangle.
 *  ERASE / RESTORE - brush the working bitmap's alpha away / back from the untouched original.
 */
class EditorView(context: Context) : View(context) {
    enum class Mode { PREVIEW, CROP, ERASE, RESTORE }

    var mode = Mode.PREVIEW
        set(v) {
            field = v
            if (v == Mode.CROP) resetCrop()
            invalidate()
        }
    var image: Bitmap? = null
        set(v) { field = v; invalidate() }
    var orig: Bitmap? = null
    var brushFraction = 0.06f

    /** Called before the first brush dab of a stroke; must return a mutable bitmap that is now the working image. */
    var onBeginStroke: (() -> Bitmap)? = null
    var onTextTap: ((Int) -> Unit)? = null
    var onTextMove: ((Int, Float, Float) -> Unit)? = null
    var textBoxes: List<RectF> = emptyList()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ---- preview ----
    private var frameCount = 1
    private var delays: List<Int> = listOf(100)
    private var provider: ((Int) -> Bitmap)? = null
    private var frameIdx = 0
    private var lastTick = 0L
    private var dragging = false

    fun setPreview(count: Int, delays: List<Int>, provider: (Int) -> Bitmap) {
        frameCount = count
        this.delays = delays
        this.provider = provider
        frameIdx = 0
        lastTick = SystemClock.uptimeMillis()
        invalidate()
    }

    // ---- shared drawing state ----
    private val dst = RectF()
    private val checker = Paint().apply {
        val t = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        t.setPixel(0, 0, Color.rgb(205, 205, 205)); t.setPixel(1, 1, Color.rgb(205, 205, 205))
        t.setPixel(1, 0, Color.rgb(240, 240, 240)); t.setPixel(0, 1, Color.rgb(240, 240, 240))
        val shader = BitmapShader(Bitmap.createScaledBitmap(t, (dp(16f) * 2).toInt().coerceAtLeast(4), (dp(16f) * 2).toInt().coerceAtLeast(4), false), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        this.shader = shader
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 80, 160, 255); style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val brushRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 64, 129); style = Paint.Style.STROKE; strokeWidth = dp(2f) }

    // ---- crop ----
    private val crop = RectF()
    private var cropEdges = 0 // bit flags: 1=L 2=T 4=R 8=B, 16=move

    private fun resetCrop() {
        val b = image ?: return
        crop.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
    }

    fun getCropRect(): Rect = Rect(crop.left.toInt(), crop.top.toInt(), crop.right.toInt(), crop.bottom.toInt())

    // ---- brush ----
    private var lastX = 0f
    private var lastY = 0f
    private var touchX = -1f
    private var touchY = -1f
    private var strokeBitmap: Bitmap? = null

    private fun layoutImage(b: Bitmap) {
        val pad = dp(14f)
        val s = min((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        val w = b.width * s
        val h = b.height * s
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun layoutSquare() {
        val pad = dp(14f)
        val side = min(width, height) - 2 * pad
        dst.set((width - side) / 2, (height - side) / 2, (width + side) / 2, (height + side) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(48, 48, 52))
        if (mode == Mode.PREVIEW) drawPreview(canvas) else drawImage(canvas)
    }

    private fun drawPreview(canvas: Canvas) {
        layoutSquare()
        canvas.drawRect(dst, checker)
        val p = provider ?: return
        if (frameCount > 1 && !dragging) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTick >= delays[frameIdx]) {
                frameIdx = (frameIdx + 1) % frameCount
                lastTick = now
            }
        }
        canvas.drawBitmap(p(frameIdx.coerceIn(0, frameCount - 1)), null, dst, bmpPaint)
        textBoxes.forEach {
            canvas.drawRect(
                dst.left + it.left * dst.width(), dst.top + it.top * dst.height(),
                dst.left + it.right * dst.width(), dst.top + it.bottom * dst.height(), textBoxPaint,
            )
        }
        if (frameCount > 1 && !dragging) postInvalidateOnAnimation()
    }

    private fun drawImage(canvas: Canvas) {
        val b = image ?: return
        layoutImage(b)
        canvas.drawRect(dst, checker)
        canvas.drawBitmap(b, null, dst, bmpPaint)
        if (mode == Mode.CROP) {
            val s = dst.width() / b.width
            val r = RectF(dst.left + crop.left * s, dst.top + crop.top * s, dst.left + crop.right * s, dst.top + crop.bottom * s)
            canvas.drawRect(dst.left, dst.top, dst.right, r.top, dim)
            canvas.drawRect(dst.left, r.bottom, dst.right, dst.bottom, dim)
            canvas.drawRect(dst.left, r.top, r.left, r.bottom, dim)
            canvas.drawRect(r.right, r.top, dst.right, r.bottom, dim)
            canvas.drawRect(r, line)
            val hr = dp(7f)
            for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom,
                r.centerX() to r.top, r.centerX() to r.bottom, r.left to r.centerY(), r.right to r.centerY())) {
                canvas.drawCircle(x, y, hr, handle)
            }
        } else if (touchX >= 0) {
            val radius = brushFraction * max(b.width, b.height) * (dst.width() / b.width) / 2
            canvas.drawCircle(touchX, touchY, radius, brushRing)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (mode) {
            Mode.PREVIEW -> previewTouch(e)
            Mode.CROP -> cropTouch(e)
            else -> brushTouch(e)
        }
        return true
    }

    // ---- preview touch: drag text ----
    private var dragIdx = -1
    private var downX = 0f
    private var downY = 0f
    private var startCx = 0f
    private var startCy = 0f
    private var moved = false

    private fun previewTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nx = (e.x - dst.left) / dst.width()
                val ny = (e.y - dst.top) / dst.height()
                dragIdx = textBoxes.indexOfLast {
                    RectF(it).apply { inset(-0.03f, -0.03f) }.contains(nx, ny)
                }
                if (dragIdx >= 0) {
                    downX = e.x; downY = e.y
                    startCx = textBoxes[dragIdx].centerX(); startCy = textBoxes[dragIdx].centerY()
                    moved = false
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragIdx >= 0) {
                if (abs(e.x - downX) > dp(6f) || abs(e.y - downY) > dp(6f)) moved = true
                if (moved) {
                    val cx = (startCx + (e.x - downX) / dst.width()).coerceIn(0f, 1f)
                    val cy = (startCy + (e.y - downY) / dst.height()).coerceIn(0f, 1f)
                    onTextMove?.invoke(dragIdx, cx, cy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragIdx >= 0 && !moved && e.actionMasked == MotionEvent.ACTION_UP) onTextTap?.invoke(dragIdx)
                dragIdx = -1
                dragging = false
                invalidate()
            }
        }
    }

    // ---- crop touch ----
    private fun cropTouch(e: MotionEvent) {
        val b = image ?: return
        val s = dst.width() / b.width
        val r = RectF(dst.left + crop.left * s, dst.top + crop.top * s, dst.left + crop.right * s, dst.top + crop.bottom * s)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val slop = dp(26f)
                var f = 0
                if (e.y in (r.top - slop)..(r.bottom + slop)) {
                    if (abs(e.x - r.left) < slop) f = f or 1
                    if (abs(e.x - r.right) < slop) f = f or 4
                }
                if (e.x in (r.left - slop)..(r.right + slop)) {
                    if (abs(e.y - r.top) < slop) f = f or 2
                    if (abs(e.y - r.bottom) < slop) f = f or 8
                }
                if (f == 0 && r.contains(e.x, e.y)) f = 16
                cropEdges = f
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lastX) / s
                val dy = (e.y - lastY) / s
                lastX = e.x; lastY = e.y
                val minSize = 24f
                val w = b.width.toFloat()
                val h = b.height.toFloat()
                if (cropEdges == 16) {
                    val nx = (crop.left + dx).coerceIn(0f, w - crop.width())
                    val ny = (crop.top + dy).coerceIn(0f, h - crop.height())
                    crop.offsetTo(nx, ny)
                } else {
                    if (cropEdges and 1 != 0) crop.left = (crop.left + dx).coerceIn(0f, crop.right - minSize)
                    if (cropEdges and 4 != 0) crop.right = (crop.right + dx).coerceIn(crop.left + minSize, w)
                    if (cropEdges and 2 != 0) crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minSize)
                    if (cropEdges and 8 != 0) crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minSize, h)
                }
                invalidate()
            }
        }
    }

    // ---- brush touch ----
    private fun brushTouch(e: MotionEvent) {
        val img = image ?: return
        val ref = orig ?: return
        val s = dst.width() / img.width
        fun ix(x: Float) = (x - dst.left) / s
        fun iy(y: Float) = (y - dst.top) / s
        val strokeW = brushFraction * max(img.width, img.height)
        touchX = e.x; touchY = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokeBitmap = onBeginStroke?.invoke() ?: return
                lastX = e.x; lastY = e.y
                paintSegment(strokeBitmap!!, ref, ix(e.x), iy(e.y), ix(e.x), iy(e.y), strokeW)
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> strokeBitmap?.let {
                paintSegment(it, ref, ix(lastX), iy(lastY), ix(e.x), iy(e.y), strokeW)
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchX = -1f; touchY = -1f
                strokeBitmap = null
            }
        }
        invalidate()
    }

    private fun paintSegment(target: Bitmap, ref: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float, w: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = w
        }
        if (mode == Mode.ERASE) {
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else {
            p.shader = BitmapShader(ref, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        Canvas(target).drawLine(x0, y0, x1, y1, p)
    }
}
