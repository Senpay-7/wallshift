package app.wallshift.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.wallshift.WallApp
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Two engines, picked by interval:
 * - >= 15 min: WorkManager PeriodicWorkRequest (Android's minimum period). Survives reboot by itself.
 * - <  15 min: a chain of exact, non-wakeup alarms. Non-wakeup means nothing runs while the
 *   screen is off (no battery drain); an overdue alarm fires as soon as the device wakes up,
 *   Re-armed after reboot/update.
 * Either way, a change that comes due while the phone is locked is deferred until unlock
 * (see [AutoRunner]), so the lock screen keeps showing the last photo.
 */
object AutoScheduler {
    private const val WORK_NAME = "auto-change"
    const val WORK_MIN_SECONDS = 15 * 60

    fun apply(context: Context, enabled: Boolean, intervalSeconds: Int) {
        val wm = WorkManager.getInstance(context)
        when {
            !enabled -> {
                wm.cancelUniqueWork(WORK_NAME)
                Alarms.cancel(context)
            }
            intervalSeconds >= WORK_MIN_SECONDS -> {
                Alarms.cancel(context)
                val minutes = intervalSeconds / 60L
                val request = PeriodicWorkRequestBuilder<AutoChangeWorker>(minutes, TimeUnit.MINUTES)
                    .setInitialDelay(minutes, TimeUnit.MINUTES)
                    .build()
                wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
            else -> {
                wm.cancelUniqueWork(WORK_NAME)
                Alarms.scheduleNext(context, intervalSeconds)
            }
        }
    }
}

class AutoChangeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as WallApp).graph
        if (graph.settings.current().intervalSeconds < AutoScheduler.WORK_MIN_SECONDS) return Result.success()
        return try {
            graph.runner.onDue() // defers while locked; empty library -> no-op
            Result.success()
        } catch (e: Exception) {
            Result.failure() // periodic work simply runs again next interval
        }
    }
}

internal object Alarms {
    /** Re-check period while a deferred change waits for unlock (non-wakeup: only while screen is on). */
    const val RECHECK_SECONDS = 5
    private const val EXTRA_RECHECK = "recheck"

    private fun pendingIntent(context: Context, recheck: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, AlarmTickReceiver::class.java).putExtra(EXTRA_RECHECK, recheck),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun isRecheck(intent: Intent) = intent.getBooleanExtra(EXTRA_RECHECK, false)

    /** Schedules the single alarm of this app (replacing any previous one). */
    fun scheduleNext(context: Context, seconds: Int, recheck: Boolean = false) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + seconds * 1000L
        val pi = pendingIntent(context, recheck)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExact(AlarmManager.ELAPSED_REALTIME, at, pi)
            else am.set(AlarmManager.ELAPSED_REALTIME, at, pi) // permission revoked: inexact fallback
        } catch (e: SecurityException) {
            am.set(AlarmManager.ELAPSED_REALTIME, at, pi)
        }
    }

    fun scheduleRecheck(context: Context) = scheduleNext(context, RECHECK_SECONDS, recheck = true)

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }
}

/** Alarm: either a regular tick of the short-interval chain or a re-check of a deferred change. */
class AlarmTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recheck = Alarms.isRecheck(intent)
        runAsync(context) { g -> if (recheck) g.runner.onMaybeUnlocked() else g.runner.onDue() }
    }
}

/** Re-arms the alarm chain after reboot or app update (alarms don't survive those). */
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // After an update: old invisible tap areas become visible so they can be removed.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) app.wallshift.tap.TapAreaWidget.refreshLeftovers(context)
        runAsync(context) { g ->
            val s = g.settings.current()
            if (!s.autoEnabled) return@runAsync
            when {
                g.settings.isPending() -> Alarms.scheduleRecheck(context.applicationContext)
                s.intervalSeconds < AutoScheduler.WORK_MIN_SECONDS ->
                    Alarms.scheduleNext(context.applicationContext, s.intervalSeconds)
            }
        }
    }
}

private fun BroadcastReceiver.runAsync(context: Context, block: suspend (app.wallshift.Graph) -> Unit) {
    val graph = (context.applicationContext as WallApp).graph
    val result = goAsync()
    graph.appScope.launch {
        try {
            block(graph)
        } finally {
            result.finish()
        }
    }
}
