package za.co.madbot.stickermaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomStateTest {
    // A 1000x2000 view with a 900x900 image centred in it (like the editor).
    private val viewW = 1000f
    private val viewH = 2000f
    private val baseW = 900f
    private val baseH = 900f
    private val margin = 100f

    private class Rect(val l: Float, val t: Float, val w: Float, val h: Float)

    private fun rectOf(z: ZoomState): Rect {
        val w = baseW * z.zoom
        val h = baseH * z.zoom
        return Rect(viewW / 2 + z.panX - w / 2, viewH / 2 + z.panY - h / 2, w, h)
    }

    private fun pinch(z: ZoomState, factor: Float, fx: Float, fy: Float, sx: Float = 0f, sy: Float = 0f) {
        val r = rectOf(z)
        z.scaleBy(factor, fx, fy, sx, sy, r.l, r.t, r.w, r.h, baseW, baseH, viewW, viewH, margin)
    }

    /** Image coordinates (0..1) under a screen point. */
    private fun under(z: ZoomState, x: Float, y: Float): Pair<Float, Float> {
        val r = rectOf(z)
        return (x - r.l) / r.w to (y - r.t) / r.h
    }

    @Test fun startsCentredAtOneX() {
        val z = ZoomState()
        assertEquals(1f, z.zoom, 0f)
        assertEquals(0f, z.panX, 0f)
        assertEquals(0f, z.panY, 0f)
    }

    @Test fun pointUnderTheFingersStaysUnderThem() {
        val z = ZoomState()
        // Pinch near the top-left of the image, far enough in that the image is bigger than the view
        // (a smaller image is kept centred instead, so the point can't be held there).
        val fx = 300f
        val fy = 800f
        val before = under(z, fx, fy)
        pinch(z, 4f, fx, fy)
        val after = under(z, fx, fy)
        assertEquals(4f, z.zoom, 0.0001f)
        assertEquals(before.first, after.first, 0.001f)
        assertEquals(before.second, after.second, 0.001f)
    }

    @Test fun repeatedPinchesKeepTheFocusPoint() {
        val z = ZoomState()
        val fx = 700f
        val fy = 1300f
        pinch(z, 4f, fx, fy)
        val before = under(z, fx, fy)
        repeat(3) { pinch(z, 1.15f, fx, fy) }
        val after = under(z, fx, fy)
        assertTrue(z.zoom > 4f)
        assertEquals(before.first, after.first, 0.01f)
        assertEquals(before.second, after.second, 0.01f)
    }

    @Test fun zoomIsLimited() {
        val z = ZoomState()
        repeat(30) { pinch(z, 2f, 500f, 1000f) }
        assertEquals(8f, z.zoom, 0f)
        repeat(60) { pinch(z, 0.5f, 500f, 1000f) }
        assertEquals(1f, z.zoom, 0f)
        assertEquals(0f, z.panX, 0f) // back at 1x: centred again
        assertEquals(0f, z.panY, 0f)
    }

    @Test fun twoFingerDragPans() {
        val z = ZoomState()
        pinch(z, 4f, 500f, 1000f)
        val x0 = z.panX
        val y0 = z.panY
        pinch(z, 1f, 500f, 1000f, sx = 120f, sy = -80f)
        assertEquals(x0 + 120f, z.panX, 0.001f)
        assertEquals(y0 - 80f, z.panY, 0.001f)
    }

    @Test fun panCannotLoseTheImage() {
        val z = ZoomState()
        pinch(z, 4f, 500f, 1000f)
        pinch(z, 1f, 500f, 1000f, sx = 100000f, sy = -100000f)
        val r = rectOf(z)
        // Some part of the image is still on screen.
        assertTrue(r.l < viewW && r.l + r.w > 0f)
        assertTrue(r.t < viewH && r.t + r.h > 0f)
    }

    @Test fun cannotPanAtOneX() {
        val z = ZoomState()
        pinch(z, 1f, 500f, 1000f, sx = 300f, sy = 300f)
        assertEquals(0f, z.panX, 0f)
        assertEquals(0f, z.panY, 0f)
    }
}
