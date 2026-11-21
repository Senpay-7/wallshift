package app.wallshift.wallpaper

import android.content.Context
import android.os.Process
import app.wallshift.shared.SharedState

/**
 * Which screens this (wallpaper) process currently draws, published for the app process.
 *
 * Counted per screen across all engines of the process: the system often creates the new engine
 * *before* destroying the old one (applying from the preview, switching to "Home and lock
 * screen", configuration changes). A per-engine "delete my marker on destroy" would then erase
 * the marker the new engine just wrote, and the app would think the live wallpaper is off.
 */
object EngineRegistry {
    private var home = 0
    private var lock = 0

    @Synchronized
    fun update(context: Context, homeDelta: Int, lockDelta: Int) {
        home = (home + homeDelta).coerceAtLeast(0)
        lock = (lock + lockDelta).coerceAtLeast(0)
        publish(context)
    }

    @Synchronized
    fun counts(): Pair<Int, Int> = home to lock

    private fun publish(context: Context) {
        val pid = Process.myPid().toString()
        if (home > 0) SharedState.write(context, SharedState.ENGINE, pid)
        else SharedState.file(context, SharedState.ENGINE).delete()
        if (lock > 0) SharedState.write(context, SharedState.ENGINE_LOCK, pid)
        else SharedState.file(context, SharedState.ENGINE_LOCK).delete()
    }

    /** Test hook. */
    @Synchronized
    internal fun reset() {
        home = 0
        lock = 0
    }
}
