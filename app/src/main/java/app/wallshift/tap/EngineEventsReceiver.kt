package app.wallshift.tap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import app.wallshift.WallApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App-process side of the live wallpaper (which runs in its own `:wallpaper` process).
 * The engine only sends these when there is real work, so the app is not woken on every
 * return to the Home screen.
 */
class EngineEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as WallApp).graph
        val result = goAsync()
        when (intent.action) {
            // The wallpaper already switched (instantly, from its preload); just record it.
            ACTION_DOUBLE_TAP -> graph.appScope.launch {
                try {
                    val id = intent.getStringExtra(EXTRA_ID)
                    graph.onHomeDoubleTap(id)
                } finally {
                    result.finish()
                }
            }
            ACTION_UNLOCKED -> graph.appScope.launch {
                try {
                    // Let the unlock animation finish first, so the cross-fade never competes with it.
                    delay(UNLOCK_SETTLE_MS)
                    graph.runner.onMaybeUnlocked()
                } finally {
                    result.finish()
                }
            }
            ACTION_SCREEN_OFF -> {
                // The lock-screen write (~1 s) must finish even though the screen just went off.
                val wl = context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wallshift:lock")
                    .apply { acquire(10_000) }
                graph.appScope.launch {
                    try { graph.runner.onScreenOff() } finally {
                        if (wl.isHeld) wl.release()
                        result.finish()
                    }
                }
            }
            else -> result.finish()
        }
    }

    companion object {
        const val ACTION_DOUBLE_TAP = "app.wallshift.action.HOME_DOUBLE_TAP"
        const val ACTION_UNLOCKED = "app.wallshift.action.UNLOCKED"
        const val ACTION_SCREEN_OFF = "app.wallshift.action.SCREEN_OFF"
        const val EXTRA_ID = "id"
        private const val UNLOCK_SETTLE_MS = 600L
    }
}
