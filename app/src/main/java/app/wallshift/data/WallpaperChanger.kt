package app.wallshift.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import app.wallshift.domain.BagState
import app.wallshift.domain.ShuffleBag
import app.wallshift.shared.SharedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Picks and applies wallpapers from the shuffle bag ("Change now", in-app double tap, the worker
 * and the alarm chain), and records the switches the live wallpaper makes on its own.
 *
 * Home screen:
 * - Wallshift live wallpaper active -> only the id is published ([SharedState.HOME]); the engine
 *   (own process) has usually preloaded exactly that image ([SharedState.UPCOMING]) and switches
 *   instantly. setBitmap(FLAG_SYSTEM) is never called then: it would replace the live wallpaper.
 * - otherwise -> WallpaperManager.setBitmap(FLAG_SYSTEM).
 *
 * Lock screen:
 * - our live wallpaper also runs there -> nothing to write at all (it shows the latest image, and
 *   changes wait for unlock), so unlocking never has to switch between two wallpapers;
 * - a separate static lock wallpaper -> the latest image is written once at screen-off.
 */
class WallpaperChanger(
    private val context: Context,
    private val store: WallpaperStore,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** True while our live wallpaper engine is drawing the Home screen (ground truth). */
    private val homeEngineAlive: () -> Boolean = { SharedState.engineAlive(context, SharedState.ENGINE) },
    private val lockEngineAlive: () -> Boolean = { SharedState.engineAlive(context, SharedState.ENGINE_LOCK) },
) {
    private val wm by lazy { WallpaperManager.getInstance(context) }
    private val mutex = Mutex()
    private val random = Random.Default

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _home = MutableStateFlow<String?>(null)
    private val _current = MutableStateFlow<String?>(null)
    @Volatile private var loaded = false

    /** Image id on the Home screen (as far as this process knows). */
    val homeImage: StateFlow<String?>
        get() {
            ensureLoaded()
            return _home.asStateFlow()
        }

    /** The most recently shown image on any screen; drives the in-app preview. */
    val current: StateFlow<String?>
        get() {
            ensureLoaded()
            return _current.asStateFlow()
        }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        scope.launch {
            val home = withContext(Dispatchers.IO) { SharedState.read(context, SharedState.HOME) }
                ?: settings.homeCurrent()
                ?: settings.bag().last
                ?: store.snapshot().lastOrNull()?.id
            // Atomic: never overwrite an id a change published meanwhile.
            if (home != null) _home.compareAndSet(null, home)
            val last = settings.bag().last ?: home
            if (last != null) _current.compareAndSet(null, last)
        }
    }

    /**
     * Our live wallpaper draws the Home screen. Either signal is enough: our running engine
     * (ground truth: some ROMs report no live wallpaper info to apps) or the system's answer.
     * Getting this wrong is destructive — a setBitmap(FLAG_SYSTEM) would *replace* the live
     * wallpaper — so the static path is only taken when both say no.
     */
    fun isLiveActive(): Boolean = homeEngineAlive() || systemSaysLive(WallpaperManager.FLAG_SYSTEM)

    /** Our live wallpaper draws the lock screen (then nothing is ever written there). */
    fun lockShowsLive(): Boolean =
        lockEngineAlive() ||
            (Build.VERSION.SDK_INT >= 34 && systemSaysLive(WallpaperManager.FLAG_LOCK)) ||
            (isLiveActive() && runCatching { wm.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0 }.getOrDefault(false))

    private fun systemSaysLive(which: Int): Boolean = runCatching {
        val info = if (which == WallpaperManager.FLAG_SYSTEM) wm.wallpaperInfo else wm.getWallpaperInfo(which)
        info?.packageName == context.packageName
    }.getOrDefault(false)

    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        // In-app double taps: processed one after another, never dropped. In live mode a change
        // takes a few ms; if setBitmap is slow (no live wallpaper), taps made meanwhile collapse
        // into one more change instead of piling up.
        scope.launch { for (r in requests) runCatching { changeNext() } }
    }

    /** Asks for the next wallpaper. Returns immediately. */
    fun requestNext() {
        requests.trySend(Unit)
    }

    /** @return true if a wallpaper was set. Hops to IO itself. */
    suspend fun changeNext(): Boolean = mutex.withLock { run() }

    /** Like [changeNext] but returns immediately if a change is already in progress. */
    suspend fun changeNextIfIdle(): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            run()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun run(): Boolean {
        _busy.value = true
        return try {
            withContext(Dispatchers.IO) { applyNext() }
        } finally {
            _busy.value = false
        }
    }

    private suspend fun applyNext(): Boolean {
        val target = settings.current().target
        val wantHome = target != Target.LOCK
        val wantLock = target != Target.HOME
        val live = isLiveActive()
        val liveLock = lockShowsLive()
        // Static wallpapers are only written where our live wallpaper is NOT running.
        val staticFlags = (if (wantHome && !live) WallpaperManager.FLAG_SYSTEM else 0) or
            (if (wantLock && !live && !liveLock) WallpaperManager.FLAG_LOCK else 0)

        var bag = settings.bag()
        while (true) {
            val library = store.snapshot().map { it.id }
            val (id, nextBag) = ShuffleBag.next(bag, library, random) ?: return false
            val file = store.imageFile(id)

            // A change must always be visible: skip the image already on the Home screen
            // (it counts as shown in this round).
            if (wantHome && library.size > 1 && id == shownOnHome()) {
                bag = nextBag
                continue
            }

            // Live mode: no decode here; the engine has the image preloaded (or decodes it).
            val needBitmap = staticFlags != 0
            val bitmap: Bitmap? = when {
                !file.isFile -> null
                needBitmap -> BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
                else -> null
            }
            if (!file.isFile || (needBitmap && bitmap == null)) {
                // File vanished or is unreadable: silently drop it and try the next one.
                store.remove(setOf(id))
                bag = nextBag
                continue
            }

            try {
                if (staticFlags != 0) wm.setBitmap(bitmap, null, true, staticFlags)
                // The live wallpaper (Home and/or lock) shows whatever is published here.
                if (wantHome || liveLock) publishHome(id)
                // Separate static lock screen next to a live Home: written at the next screen-off.
                if (live && wantLock && !liveLock) setLockPending(id)
            } finally {
                bitmap?.recycle()
            }
            saveAndAnnounce(ShuffleBag.prepare(nextBag, library, random), id)
            return true
        }
    }

    /**
     * The live wallpaper already switched to [id] by itself (double tap on the Home screen,
     * using the image it had preloaded). Record it so the shuffle order stays exact.
     */
    suspend fun commitShown(id: String): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val library = store.snapshot().map { it.id }
            if (id !in library) return@withContext
            val bag = ShuffleBag.prepare(ShuffleBag.take(settings.bag(), library, id, random), library, random)
            publishHome(id) // acknowledges the switch to the engine
            if (settings.current().target != Target.HOME && !lockShowsLive()) setLockPending(id)
            saveAndAnnounce(bag, id)
        }
    }

    /** Library changed (import / delete): refresh the order the engine preloads from. */
    suspend fun refreshUpcoming(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val library = store.snapshot().map { it.id }
            val bag = ShuffleBag.prepare(settings.bag(), library, random)
            settings.saveBag(bag)
            writeUpcoming(bag)
        }
    }

    private suspend fun saveAndAnnounce(bag: BagState, shownId: String) {
        settings.saveBag(bag)
        _current.value = shownId
        writeUpcoming(bag)
    }

    @Volatile private var lastUpcoming: String? = null

    /** The next few ids, in order: the live wallpaper preloads the first one. */
    private fun writeUpcoming(bag: BagState) {
        val text = bag.remaining.take(UPCOMING_COUNT).joinToString(",")
        if (text == lastUpcoming) return
        lastUpcoming = text
        SharedState.write(context, SharedState.UPCOMING, text)
    }

    /**
     * Writes the pending lock-screen image (separate static lock wallpaper only). Called when
     * the screen is off, so the lock screen never visibly changes and shows the latest photo.
     */
    suspend fun flushLock(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val id = settings.lockPending() ?: return@withContext
            val liveBefore = isLiveActive()
            val infoBefore = runCatching { wm.wallpaperInfo != null }.getOrDefault(false)
            if (liveBefore && (lockShowsLive() || settings.lockBreaksLive())) {
                // Lock screen shows our live wallpaper (already the latest image), or this ROM
                // can't take a separate lock wallpaper without dropping the live one.
                setLockPending(null)
                return@withContext
            }
            val file = store.imageFile(id)
            val bmp = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
            if (bmp != null) {
                try {
                    wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
                } finally {
                    bmp.recycle()
                }
                // Some ROMs drop the Home live wallpaper when a lock one is set: never again then.
                if (infoBefore && runCatching { wm.wallpaperInfo == null }.getOrDefault(false)) {
                    settings.setLockBreaksLive()
                }
            }
            setLockPending(null)
        }
    }

    private fun shownOnHome(): String? = _home.value ?: SharedState.read(context, SharedState.HOME)

    /** IO thread. Atomic file write; the wallpaper process watches it. */
    private fun publishHome(id: String) {
        _home.value = id
        SharedState.write(context, SharedState.HOME, id)
    }

    private suspend fun setLockPending(id: String?) {
        settings.setLockPending(id)
        SharedState.mark(context, SharedState.LOCK_PENDING, id != null)
    }

    private companion object {
        const val UPCOMING_COUNT = 8
    }
}
