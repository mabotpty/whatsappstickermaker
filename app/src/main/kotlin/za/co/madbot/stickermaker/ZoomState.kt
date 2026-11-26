package za.co.madbot.stickermaker

import kotlin.math.max

/**
 * Zoom and pan of the image in the eraser / restore brush. The unzoomed image is centred in the view; [zoom] scales it
 * about the view centre and [panX]/[panY] move it. Kept free of Android classes so it can be unit-tested.
 */
class ZoomState(private val maxZoom: Float = 8f) {
    var zoom = 1f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set

    fun reset() {
        zoom = 1f; panX = 0f; panY = 0f
    }

    /**
     * Applies a pinch: scales by [factor] keeping the image point that is under ([fx], [fy]) under it, and moves the image
     * by the focus movement ([shiftX], [shiftY]) so two fingers also pan.
     * [curLeft]/[curTop]/[curW]/[curH] describe the image rectangle on screen before the change, [baseW]/[baseH] the unzoomed
     * one, and [viewW]/[viewH] the view size.
     */
    fun scaleBy(
        factor: Float, fx: Float, fy: Float, shiftX: Float, shiftY: Float,
        curLeft: Float, curTop: Float, curW: Float, curH: Float,
        baseW: Float, baseH: Float, viewW: Float, viewH: Float, margin: Float,
    ) {
        val u = (fx - curLeft) / curW
        val v = (fy - curTop) / curH
        zoom = (zoom * factor).coerceIn(1f, maxZoom)
        val w = baseW * zoom
        val h = baseH * zoom
        panX = fx - (u - 0.5f) * w - viewW / 2f + shiftX
        panY = fy - (v - 0.5f) * h - viewH / 2f + shiftY
        clamp(w, h, viewW, viewH, margin)
    }

    /** Keeps the image from being dragged out of reach; at 1x it always sits centred. */
    fun clamp(w: Float, h: Float, viewW: Float, viewH: Float, margin: Float) {
        val mx = max(0f, (w - viewW) / 2f + margin)
        val my = max(0f, (h - viewH) / 2f + margin)
        panX = panX.coerceIn(-mx, mx)
        panY = panY.coerceIn(-my, my)
        if (zoom <= 1.001f) { panX = 0f; panY = 0f }
    }
}
