package app.wallshift.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapDetectorTest {
    private fun detector() = DoubleTapDetector(timeoutMs = 300, slopPx = 100f)

    @Test
    fun twoQuickNearbyTapsAreADoubleTap() {
        val d = detector()
        assertFalse(d.onRawTap(1000, 500f, 800f))
        assertTrue(d.onRawTap(1200, 520f, 790f))
    }

    @Test
    fun slowTapsAreNot() {
        val d = detector()
        d.onRawTap(1000, 500f, 800f)
        assertFalse(d.onRawTap(1400, 500f, 800f))
        assertTrue(d.onRawTap(1600, 500f, 800f)) // ...but the second one starts a new pair
    }

    @Test
    fun farApartTapsAreNot() {
        val d = detector()
        d.onRawTap(1000, 100f, 100f)
        assertFalse(d.onRawTap(1100, 600f, 900f))
    }

    @Test
    fun continuousTappingKeepsChanging() {
        // 20 taps, 120 ms apart: every pair is a change -> 10 changes, forever.
        val d = detector()
        var fired = 0
        for (i in 0 until 20) if (d.onRawTap(1000L + i * 120, 300f, 600f)) fired++
        assertEquals(10, fired)
    }

    @Test
    fun keepsWorkingAfterLauncherCommandsAppear() {
        // Old bug: one launcher command switched off raw taps for good.
        val d = detector()
        assertFalse(d.onCommandTap(500, 10f, 10f)) // a lone single-tap command
        var fired = 0
        for (i in 0 until 10) if (d.onRawTap(2000L + i * 120, 10f, 10f)) fired++
        assertEquals(5, fired)
    }

    @Test
    fun physicalDoubleTapSeenByBothSourcesFiresOnce() {
        val d = detector()
        var fired = 0
        // tap 1 and tap 2, each delivered as raw touch and as launcher command
        if (d.onRawTap(1000, 10f, 10f)) fired++
        if (d.onCommandTap(1004, 10f, 10f)) fired++
        if (d.onRawTap(1150, 12f, 12f)) fired++
        if (d.onCommandTap(1155, 12f, 12f)) fired++
        assertEquals(1, fired)
        // the next pair fires again
        if (d.onRawTap(1300, 10f, 10f)) fired++
        if (d.onCommandTap(1305, 10f, 10f)) fired++
        if (d.onRawTap(1450, 10f, 10f)) fired++
        if (d.onCommandTap(1455, 10f, 10f)) fired++
        assertEquals(2, fired)
    }

    @Test
    fun commandOnlyLauncherWorks() {
        val d = detector()
        assertFalse(d.onCommandTap(1000, 50f, 50f))
        assertTrue(d.onCommandTap(1200, 50f, 50f))
        assertFalse(d.onCommandTap(1350, 50f, 50f))
        assertTrue(d.onCommandTap(1500, 50f, 50f))
    }

    @Test
    fun delayedSingleTapCommandsNeverPair() {
        // Launchers that report only confirmed single taps send them >= timeout apart.
        val d = detector()
        assertFalse(d.onCommandTap(1000, 0f, 0f))
        assertFalse(d.onCommandTap(1400, 0f, 0f))
    }

    @Test
    fun resetRawForgetsFirstTap() {
        val d = detector()
        d.onRawTap(1000, 0f, 0f)
        d.resetRaw()
        assertFalse(d.onRawTap(1100, 0f, 0f))
    }

    @Test
    fun relaxedDoubleTapIsCountedLikeAndroidDoes() {
        // Android: first UP -> second DOWN <= 300 ms. The engine pairs UP -> UP, so it allows
        // for the second press itself (300 + 150 ms).
        val d = DoubleTapDetector(timeoutMs = 300 + 150, slopPx = 100f)
        assertFalse(d.onRawTap(1000, 50f, 50f)) // first UP
        // second DOWN at 1290 (290 ms gap), held 110 ms -> UP at 1400
        assertTrue(d.onRawTap(1400, 52f, 48f))
        // two separate single taps half a second apart stay single taps
        assertFalse(d.onRawTap(3000, 50f, 50f))
        assertFalse(d.onRawTap(3600, 50f, 50f))
    }
}
