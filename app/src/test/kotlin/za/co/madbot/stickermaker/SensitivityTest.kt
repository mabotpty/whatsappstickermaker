package za.co.madbot.stickermaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same expectations as the desktop self-test, so both apps cut out backgrounds the same way (docs/SPEC.md). */
class SensitivityTest {
    @Test
    fun zeroLeavesTheModelOutputAlone() {
        val l = Sensitivity.lut(0)
        for (a in 0..255) assertEquals(a, l[a])
    }

    @Test
    fun keepMoreRaisesFaintAlphaAndDropsNoise() {
        val l = Sensitivity.lut(100)
        assertEquals(255, l[60])   // thin text at ~25% alpha becomes solid
        assertEquals(0, l[3])      // tiny noise is dropped
        assertEquals(255, l[255])
        val half = Sensitivity.lut(25) // +25: gain 2
        assertEquals(100, half[50])
    }

    @Test
    fun keepLessRemovesFaintAlpha() {
        val l = Sensitivity.lut(-100)
        assertEquals(0, l[30])
        assertTrue(l[60] < 60)
        assertEquals(255, l[255])
    }

    @Test
    fun curveNeverDecreasesWithMaskAlpha() {
        for (v in listOf(-100, -50, 0, 50, 100)) {
            val l = Sensitivity.lut(v)
            for (a in 1..255) assertTrue("v=$v a=$a", l[a] >= l[a - 1])
        }
    }

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun colourMaskFindsTextOnAFlatBackground() {
        val w = 64
        val px = IntArray(w * w) { white }
        for (y in 20 until 30) for (x in 20 until 30) px[y * w + x] = black
        val cm = Sensitivity.colourMask(px, w, w)!!
        assertEquals(255, cm[25 * w + 25].toInt() and 0xFF)
        assertEquals(0, cm[5 * w + 5].toInt() and 0xFF)
    }

    @Test
    fun colourMaskIsOffForBusyBorders() {
        val w = 64
        val px = IntArray(w * w) { i -> if ((i % w) / 4 % 2 == 0) 0xFFCC3333.toInt() else 0xFF33CC33.toInt() }
        assertEquals(null, Sensitivity.colourMask(px, w, w))
    }

    @Test
    fun keepMoreBringsBackTextTheModelRemoved() {
        // model alpha ~0 on the text pixel (a hard case no gain can fix), colour mask 255
        val px = intArrayOf(black, white)
        val mask = byteArrayOf(5, 0)
        val cm = byteArrayOf(255.toByte(), 0)
        val keep = Sensitivity.applyPx(px, mask, cm, Sensitivity.lut(60), Sensitivity.colourWeight(60))
        assertEquals(255, keep[0] ushr 24)   // text is back
        assertEquals(0, keep[1] ushr 24)     // background still gone
        val model = Sensitivity.applyPx(px, mask, cm, Sensitivity.lut(0), Sensitivity.colourWeight(0))
        assertEquals(5, model[0] ushr 24)    // at 0 the model output is untouched
        assertEquals(0x000000, keep[0] and 0xFFFFFF) // colours come from the original
    }
}
