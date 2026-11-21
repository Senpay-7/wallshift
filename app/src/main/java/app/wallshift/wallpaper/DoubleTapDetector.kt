package app.wallshift.wallpaper

import kotlin.math.hypot

/**
 * Double-tap detection for the Home screen from two independent sources:
 * - "command" taps: launchers send WallpaperManager.COMMAND_TAP for taps on empty space
 *   (some send it immediately, some only for confirmed single taps, some never);
 * - "raw" taps: touch events the system forwards to the wallpaper window.
 *
 * Each source pairs its own taps; whichever completes a double tap first fires. The other
 * source's copy of the *same* physical double tap (arriving within [dedupeMs]) is dropped, but
 * the same source may fire again immediately, so tapping continuously keeps changing wallpapers:
 * taps 1+2 -> change, 3+4 -> change, 5+6 -> change ...
 */
class DoubleTapDetector(
    private val timeoutMs: Long,
    private val slopPx: Float,
    private val dedupeMs: Long = 250,
) {
    private enum class Source { RAW, COMMAND }

    private inner class Pairer {
        var lastT = Long.MIN_VALUE
        var lastX = 0f
        var lastY = 0f

        fun tap(t: Long, x: Float, y: Float): Boolean {
            val isDouble = lastT != Long.MIN_VALUE && t - lastT in 0..timeoutMs &&
                hypot(x - lastX, y - lastY) <= slopPx
            if (isDouble) {
                lastT = Long.MIN_VALUE // the next tap starts a new pair
                return true
            }
            lastT = t
            lastX = x
            lastY = y
            return false
        }
    }

    private val raw = Pairer()
    private val command = Pairer()
    private var firedAt = Long.MIN_VALUE
    private var firedBy: Source? = null

    /** @return true when this tap completes a double tap. */
    fun onRawTap(timeMs: Long, x: Float, y: Float): Boolean = handle(Source.RAW, raw, timeMs, x, y)

    /** @return true when this tap completes a double tap. */
    fun onCommandTap(timeMs: Long, x: Float, y: Float): Boolean = handle(Source.COMMAND, command, timeMs, x, y)

    /** Forget a half-finished pair from raw touches (multi-touch, cancel). */
    fun resetRaw() {
        raw.lastT = Long.MIN_VALUE
    }

    private fun handle(src: Source, p: Pairer, t: Long, x: Float, y: Float): Boolean {
        if (!p.tap(t, x, y)) return false
        val duplicate = firedBy != null && firedBy != src && firedAt != Long.MIN_VALUE && t - firedAt in 0..dedupeMs
        if (duplicate) return false
        firedAt = t
        firedBy = src
        return true
    }
}
