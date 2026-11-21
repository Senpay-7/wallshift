package app.wallshift.ui

import android.app.Application
import android.net.Uri
import android.os.FileObserver
import app.wallshift.shared.SharedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wallshift.Graph
import app.wallshift.WallApp
import app.wallshift.data.Settings
import app.wallshift.data.Target
import app.wallshift.data.isValidInterval
import app.wallshift.tap.TapAreaWidget
import app.wallshift.tap.TapStats
import app.wallshift.work.AutoScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Where the Wallshift live wallpaper is running. */
enum class LiveState { CHECKING, OFF, HOME_ONLY, LOCK_ONLY, BOTH }

/** A grid cell. [thumb] == null means the import is still running (placeholder). */
data class GridEntry(val id: String, val thumb: File?)

/**
 * Holds all screen state in StateFlows that are started eagerly once and live as long as the
 * Activity's ViewModel: leaving and returning to the app re-reads nothing and recomputes nothing.
 */
class MainViewModel(private val app: Application, private val g: Graph) : ViewModel() {

    /**
     * While this screen exists, follow what the wallpaper process reports (engine running,
     * Home taps) through inotify on its state files; stopped in onCleared, so nothing of this
     * runs once the app's UI is gone.
     */
    @Suppress("DEPRECATION")
    private val stateObserver = object : FileObserver(
        SharedState.dir(app).path,
        MOVED_TO or CLOSE_WRITE or DELETE or CREATE,
    ) {
        override fun onEvent(event: Int, path: String?) {
            when (path) {
                SharedState.ENGINE, SharedState.ENGINE_LOCK -> g.refreshLiveActive()
                SharedState.HOME_TAPS -> g.refreshHomeTaps()
            }
        }
    }

    private val _leftoverTapAreas = MutableStateFlow(0)
    /** Old invisible tap-area widgets still on the Home screen (shown as a cleanup hint). */
    val leftoverTapAreas: StateFlow<Int> = _leftoverTapAreas.asStateFlow()

    init {
        g.store.preload()
        g.refreshLiveActive()
        g.refreshHomeTaps()
        stateObserver.startWatching()
        g.appScope.launch(Dispatchers.IO) {
            TapAreaWidget.refreshLeftovers(app) // make them visible so they can be removed
            _leftoverTapAreas.value = TapAreaWidget.leftoverCount(app)
        }
    }

    /** Only while leftovers exist: re-count when the user comes back from the Home screen. */
    fun recountLeftovers() {
        if (_leftoverTapAreas.value == 0) return
        g.appScope.launch(Dispatchers.IO) { _leftoverTapAreas.value = TapAreaWidget.leftoverCount(app) }
    }

    override fun onCleared() {
        stateObserver.stopWatching()
    }

    val liveActive: StateFlow<Boolean?> = g.liveActive
    val liveOnLock: StateFlow<Boolean?> = g.liveOnLock

    /** The image most recently put on screen; shown at the top and marked "Now" in the grid. */
    val currentId: StateFlow<String?> = g.changer.current
    val currentImage: StateFlow<File?> = g.changer.current
        .map { id -> id?.let { g.store.imageFile(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val tapStats: StateFlow<TapStats> = g.tapStats

    /** Called when the system wallpaper picker returns. */
    fun refreshLiveActive() = g.refreshLiveActiveSoon()

    /** Where the live wallpaper runs: drives the status line under "Double tap". */
    val liveState: StateFlow<LiveState> =
        combine(g.liveActive, g.liveOnLock) { home, lock ->
            when {
                home == null || lock == null -> LiveState.CHECKING
                home && lock -> LiveState.BOTH
                home -> LiveState.HOME_ONLY
                lock -> LiveState.LOCK_ONLY
                else -> LiveState.OFF
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LiveState.CHECKING)

    val settings: StateFlow<Settings?> =
        g.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Newest first; null until the index has been read. */
    val entries: StateFlow<List<GridEntry>?> =
        combine(g.store.items, g.store.pending) { items, pending ->
            if (items == null) return@combine null
            val out = ArrayList<Pair<Long, GridEntry>>(items.size + pending.size)
            items.forEach { out += it.added to GridEntry(it.id, g.store.thumbFile(it.id)) }
            // An id is briefly in both lists while an import commits: never emit a duplicate key.
            val ready = if (pending.isEmpty()) emptySet() else items.mapTo(HashSet()) { it.id }
            pending.forEach { if (it.id !in ready) out += it.added to GridEntry(it.id, null) }
            out.sortByDescending { it.first }
            out.map { it.second }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val changing: StateFlow<Boolean> = g.changer.busy

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    fun import(uris: List<Uri>) = g.importer.import(uris)

    fun setTarget(t: Target) {
        g.appScope.launch { g.settings.setTarget(t) }
    }

    fun setAuto(on: Boolean) {
        g.appScope.launch {
            g.settings.setAuto(on)
            AutoScheduler.apply(app, on, g.settings.current().intervalSeconds)
        }
    }

    fun setInterval(seconds: Int) {
        if (!isValidInterval(seconds)) return
        g.appScope.launch {
            g.settings.setInterval(seconds)
            if (g.settings.current().autoEnabled) AutoScheduler.apply(app, true, seconds)
        }
    }

    fun dismissHint() {
        g.appScope.launch { g.settings.dismissHint() }
    }

    fun changeNow() {
        if (changing.value) return
        g.appScope.launch { runCatching { g.changer.changeNext() } }
    }

    fun setDoubleTap(on: Boolean) {
        g.appScope.launch { g.settings.setDoubleTap(on) }
    }

    fun toggleSelection(id: String) = _selection.update { if (id in it) it - id else it + id }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun deleteSelected() {
        val ids = _selection.value
        _selection.value = emptySet()
        g.appScope.launch { g.store.remove(ids) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as WallApp
                MainViewModel(app, app.graph)
            }
        }
    }
}
