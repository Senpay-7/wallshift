package app.wallshift.work

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import app.wallshift.data.SettingsRepository
import app.wallshift.data.WallpaperChanger
import app.wallshift.shared.SharedState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What to do when an automatic change comes due. Pure, so it is unit-tested. */
enum class DueAction { NOTHING, DEFER, CHANGE }

object AutoPolicy {
    /**
     * @param regularTick true for a scheduled tick (alarm chain / worker), false for an
     *   "unlocked" signal or a re-check alarm that only matters if a change is pending.
     */
    fun decide(enabled: Boolean, locked: Boolean, regularTick: Boolean, pending: Boolean): DueAction = when {
        !enabled -> DueAction.NOTHING
        !regularTick && !pending -> DueAction.NOTHING
        locked -> DueAction.DEFER // the lock screen keeps the last photo
        else -> DueAction.CHANGE
    }
}

/**
 * Runs automatic changes, but never while the phone is locked or the screen is off: then the
 * change is marked pending and applied right after unlock (USER_PRESENT / live wallpaper becoming
 * visible), with a non-wakeup re-check alarm as a fallback.
 */
class AutoRunner(
    private val context: Context,
    private val settings: SettingsRepository,
    private val changer: WallpaperChanger,
) {
    private val mutex = Mutex()

    fun isLocked(): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        return km.isKeyguardLocked || !pm.isInteractive
    }

    /** A scheduled change is due (alarm tick for short intervals, worker for long ones). */
    suspend fun onDue(): Unit = handle(regularTick = true)

    /** Re-check alarm, unlock broadcast or Home screen became visible. */
    suspend fun onMaybeUnlocked(): Unit = handle(regularTick = false)

    private fun screenOff(): Boolean = !context.getSystemService(PowerManager::class.java).isInteractive

    /** Screen turned off: give the lock screen the latest photo before it is seen again. */
    suspend fun onScreenOff() = changer.flushLock()

    private suspend fun handle(regularTick: Boolean) {
        // Fallback for the lock-screen write if the screen-off signal was missed.
        if (screenOff()) changer.flushLock()
        val action = mutex.withLock {
            val s = settings.current()
            val short = s.intervalSeconds < AutoScheduler.WORK_MIN_SECONDS
            val a = AutoPolicy.decide(s.autoEnabled, isLocked(), regularTick, settings.isPending())
            when (a) {
                DueAction.NOTHING -> Unit
                DueAction.DEFER -> {
                    settings.setPending(true)
                    SharedState.mark(context, SharedState.PENDING, true) // engine checks it on unlock
                    Alarms.scheduleRecheck(context)
                }
                DueAction.CHANGE -> {
                    settings.setPending(false)
                    SharedState.mark(context, SharedState.PENDING, false)
                    // Short intervals: the chain continues from now (replaces any re-check alarm).
                    if (short) Alarms.scheduleNext(context, s.intervalSeconds)
                    else Alarms.cancel(context)
                }
            }
            a
        }
        if (action == DueAction.CHANGE) changer.changeNextIfIdle()
    }
}
