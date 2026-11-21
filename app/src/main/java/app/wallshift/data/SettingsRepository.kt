package app.wallshift.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.wallshift.domain.BagState
import app.wallshift.domain.ShuffleBag
import app.wallshift.shared.SharedState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class Target(val label: String) { HOME("Home"), LOCK("Lock"), BOTH("Both") }

/** Preset chips, in seconds. */
val Intervals = listOf(900, 1800, 3600, 10_800, 21_600, 43_200, 86_400)

enum class TimeUnitChoice(val label: String, val seconds: Int) {
    SECONDS("Seconds", 1), MINUTES("Minutes", 60), HOURS("Hours", 3600)
}

/** Custom value range for every unit: 1..60. */
val CustomRange = 1..60

/** Any preset, or 1..60 seconds / minutes / hours. */
fun isValidInterval(seconds: Int): Boolean =
    seconds in Intervals || TimeUnitChoice.entries.any { u ->
        seconds % u.seconds == 0 && seconds / u.seconds in CustomRange
    }

/** Splits an interval into the largest unit that represents it exactly (for the picker). */
fun splitInterval(seconds: Int): Pair<Int, TimeUnitChoice> {
    for (u in TimeUnitChoice.entries.reversed()) {
        if (seconds % u.seconds == 0 && seconds / u.seconds in CustomRange) return seconds / u.seconds to u
    }
    return seconds.coerceIn(CustomRange) to TimeUnitChoice.SECONDS
}

fun intervalLabel(seconds: Int): String = when {
    seconds % 3600 == 0 -> "${seconds / 3600} h"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "$seconds s"
}

const val DefaultInterval = 3600

data class Settings(
    val target: Target = Target.BOTH,
    val autoEnabled: Boolean = false,
    val intervalSeconds: Int = DefaultInterval,
    val hintDismissed: Boolean = false,
    val doubleTap: Boolean = true,
)

/** One instance per process (owned by the Graph singleton), as DataStore requires. */
class SettingsRepository(private val context: Context) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("settings") },
    )

    private object K {
        val target = intPreferencesKey("target")
        val auto = booleanPreferencesKey("auto")
        val intervalLegacyMin = intPreferencesKey("interval") // v1 stored minutes
        val interval = intPreferencesKey("interval_s")
        val hint = booleanPreferencesKey("hint_dismissed")
        val remaining = stringPreferencesKey("bag_remaining")
        val shown = stringPreferencesKey("bag_shown")
        val last = stringPreferencesKey("bag_last")
        val home = stringPreferencesKey("home_current")
        val pending = booleanPreferencesKey("pending_change")
        val doubleTap = booleanPreferencesKey("double_tap")
        val lockPending = stringPreferencesKey("lock_pending")
        val lockBreaksLive = booleanPreferencesKey("lock_breaks_live")
    }

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            target = Target.entries.getOrElse(p[K.target] ?: Target.BOTH.ordinal) { Target.BOTH },
            autoEnabled = p[K.auto] ?: false,
            intervalSeconds = (p[K.interval] ?: p[K.intervalLegacyMin]?.times(60) ?: DefaultInterval)
                .takeIf(::isValidInterval) ?: DefaultInterval,
            hintDismissed = p[K.hint] ?: false,
            doubleTap = p[K.doubleTap] ?: true,
        )
    }.distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    suspend fun setTarget(t: Target) = store.edit { it[K.target] = t.ordinal }
    suspend fun setAuto(on: Boolean) = store.edit { it[K.auto] = on }
    suspend fun setInterval(seconds: Int) = store.edit { it[K.interval] = seconds }
    suspend fun dismissHint() = store.edit { it[K.hint] = true }
    suspend fun setDoubleTap(on: Boolean) {
        store.edit { it[K.doubleTap] = on }
        SharedState.mark(context, SharedState.DOUBLE_TAP_OFF, !on) // read by the wallpaper process
    }

    /** Image the lock screen should get at the next screen-off (live wallpaper mode). */
    suspend fun lockPending(): String? = store.data.first()[K.lockPending]
    suspend fun setLockPending(id: String?) = store.edit {
        if (id != null) it[K.lockPending] = id else it.remove(K.lockPending)
    }

    /** This ROM drops the Home live wallpaper when the lock wallpaper is set separately. */
    suspend fun lockBreaksLive(): Boolean = store.data.first()[K.lockBreaksLive] ?: false
    suspend fun setLockBreaksLive() = store.edit { it[K.lockBreaksLive] = true }

    /** Image currently shown by the live wallpaper on the Home screen. */
    suspend fun homeCurrent(): String? = store.data.first()[K.home]
    suspend fun setHomeCurrent(id: String) = store.edit { it[K.home] = id }

    /** A scheduled change came due while the phone was locked; apply it on unlock. */
    suspend fun isPending(): Boolean = store.data.first()[K.pending] ?: false
    suspend fun setPending(on: Boolean) = store.edit { it[K.pending] = on }

    suspend fun bag(): BagState = store.data.first().let { p ->
        BagState(
            remaining = ShuffleBag.decodeList(p[K.remaining]),
            shown = ShuffleBag.decodeList(p[K.shown]).toSet(),
            last = p[K.last],
        )
    }

    suspend fun saveBag(s: BagState) = store.edit {
        it[K.remaining] = ShuffleBag.encodeList(s.remaining)
        it[K.shown] = ShuffleBag.encodeList(s.shown)
        if (s.last != null) it[K.last] = s.last else it.remove(K.last)
    }
}
