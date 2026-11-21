package app.wallshift

import android.app.Application
import android.content.Context
import android.util.Log
import app.wallshift.tap.TapStats
import app.wallshift.shared.SharedState
import kotlinx.coroutines.flow.update
import androidx.work.Configuration
import app.wallshift.data.Importer
import app.wallshift.data.SettingsRepository
import app.wallshift.data.WallpaperChanger
import app.wallshift.data.WallpaperStore
import app.wallshift.work.AutoRunner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

/** Nothing is done in onCreate: every dependency is created lazily on first use. */
class WallApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    val graph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Graph(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).crossfade(120).build()
}

class Graph(private val context: Context) {
    /** Outlives screens, so imports and "Change now" finish even if the user leaves the app. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.w("Wallshift", "background task failed", e) },
    )
    val settings = SettingsRepository(context)
    val store = WallpaperStore(context, appScope)
    val importer = Importer(context, store, appScope)
    val changer = WallpaperChanger(context, store, settings, appScope)
    val runner = AutoRunner(context, settings, changer)

    private val _liveActive = MutableStateFlow<Boolean?>(null)
    /** Whether Wallshift is the Home screen live wallpaper. null until first checked. */
    val liveActive: StateFlow<Boolean?> = _liveActive.asStateFlow()

    init {
        // The wallpaper process reads the double-tap switch from a marker file.
        appScope.launch(Dispatchers.IO) {
            SharedState.mark(context, SharedState.DOUBLE_TAP_OFF, !settings.current().doubleTap)
        }
        // Library changed (import / delete): publish the new order the wallpaper preloads from.
        // Debounced, so importing 50 photos causes one update, not 50.
        appScope.launch {
            store.items.filterNotNull().collectLatest {
                delay(300)
                changer.refreshUpcoming()
            }
        }
    }

    private val _liveOnLock = MutableStateFlow<Boolean?>(null)
    /** Whether the live wallpaper is also the lock-screen wallpaper. null until checked. */
    val liveOnLock: StateFlow<Boolean?> = _liveOnLock.asStateFlow()

    /** Asks WallpaperManager / the engine's marker files off the main thread. */
    fun refreshLiveActive() {
        appScope.launch(Dispatchers.IO) {
            _liveActive.value = changer.isLiveActive()
            _liveOnLock.value = changer.lockShowsLive()
        }
    }

    /** After the system wallpaper picker: the engine needs a moment to start, so look again. */
    fun refreshLiveActiveSoon() {
        appScope.launch {
            for (wait in longArrayOf(0, 800, 2_500, 6_000)) {
                delay(wait)
                refreshLiveActive()
            }
        }
    }

    // --- double tap ------------------------------------------------------------------------

    private val _tapStats = MutableStateFlow(TapStats())
    val tapStats: StateFlow<TapStats> = _tapStats.asStateFlow()

    /** Home taps are counted by the wallpaper process into a file. */
    fun refreshHomeTaps() {
        appScope.launch(Dispatchers.IO) {
            val n = SharedState.read(context, SharedState.HOME_TAPS)?.toIntOrNull() ?: 0
            _tapStats.update { it.copy(homeTaps = n) }
        }
    }

    /**
     * Double tap on the Home screen. The live wallpaper has already switched to [id] (from its
     * preload); only record it. [id] is null if no order was known yet.
     */
    suspend fun onHomeDoubleTap(id: String?) {
        _tapStats.update { it.copy(changes = it.changes + 1) }
        if (id != null) changer.commitShown(id) else if (!runner.isLocked()) changer.changeNext()
    }
}
