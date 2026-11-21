package app.wallshift.wallpaper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import app.wallshift.shared.SharedState
import app.wallshift.tap.EngineEventsReceiver
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max

/**
 * Home-screen live wallpaper, running in its own tiny process (`:wallpaper`, see the manifest).
 *
 * The Home screen must never wait for it:
 * - its process holds only bitmaps and a few small classes (no Compose, DataStore, coroutines);
 * - returning to the Home screen (unlock, leaving an app) costs nothing: the surface keeps the
 *   last frame, nothing is redrawn or recomputed;
 * - a double tap switches *instantly* to the next image, which was decoded in advance from the
 *   order the app publishes ([SharedState.UPCOMING]); the app is told afterwards;
 * - the colors reported to the system are stored, so a restarted wallpaper process reports the
 *   same colors at once and the system has no reason to rebuild its theme.
 */
class WallshiftWallpaperService : WallpaperService() {

    /** One background thread for decoding and small file I/O; never the main thread. */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wallshift-io").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())

    override fun onCreateEngine(): Engine = HomeEngine()

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun sendToApp(action: String, id: String? = null) = io.execute {
        runCatching {
            val intent = Intent(this, EngineEventsReceiver::class.java).setAction(action)
            if (id != null) intent.putExtra(EngineEventsReceiver.EXTRA_ID, id)
            sendBroadcast(intent)
        }
    }

    /** An image being decoded (or decoded) ahead of time. */
    private class Preload(val id: String) {
        var bitmap: Bitmap? = null
        var showWhenReady = false
        var task: Future<*>? = null
    }

    private inner class HomeEngine : Engine() {
        private val ctx: Context = this@WallshiftWallpaperService
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val src = Rect()
        private val dst = Rect()

        private val queue = EngineQueue()
        private var current: Bitmap? = null
        private var previous: Bitmap? = null
        private var fade = 1f
        private var animator: ValueAnimator? = null

        private var loadSeq = 0
        private var loadTask: Future<*>? = null
        private var loadingId: String? = null
        private var preload: Preload? = null

        private var reportedColors: WallpaperColors? = null
        private var reportedHints = -1

        @Volatile private var doubleTapOff = false
        /** Screens this engine draws (published process-wide through [EngineRegistry]). */
        private var servesHome = false
        private var servesLock = false
        private var homeTaps = 0

        private val vc = ViewConfiguration.get(ctx)
        private val taps = DoubleTapDetector(
            // Android measures first UP -> second DOWN; taps are paired UP -> UP here, so the
            // second press itself (~50-150 ms) is added, or relaxed double taps would be missed.
            timeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong() + PRESS_ALLOWANCE_MS,
            slopPx = vc.scaledDoubleTapSlop.toFloat(),
        )
        private var downT = 0L
        private var downX = 0f
        private var downY = 0f
        private var tapCandidate = false
        private var lastSeenAt = Long.MIN_VALUE

        // --- lifecycle ----------------------------------------------------------------------

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false) // no per-frame scroll callbacks
            doubleTapOff = SharedState.exists(ctx, SharedState.DOUBLE_TAP_OFF)
            updateTouchDelivery()
            // Older builds could switch raw touches off for good; the whole screen counts again.
            io.execute { SharedState.file(ctx, RAW_TOUCH_OFF).delete() }
            restoreColors()

            if (!isPreview) {
                val (home, lock) = screensFor(currentFlags())
                setServes(home, lock)
                io.execute { homeTaps = SharedState.read(ctx, SharedState.HOME_TAPS)?.toIntOrNull() ?: 0 }
                registerScreenEvents()
            }
            observer.startWatching()

            queue.setUpcoming(SharedState.readList(ctx, SharedState.UPCOMING))
            val home = SharedState.read(ctx, SharedState.HOME) ?: SharedState.newestImageId(ctx)
            if (home != null) startLoad(home, animate = false)
        }

        override fun onDestroy() {
            observer.stopWatching()
            animator?.cancel()
            loadTask?.cancel(true)
            preload?.let { it.task?.cancel(true); it.bitmap?.recycle() }
            preload = null
            if (screenReceiverOn) runCatching { unregisterReceiver(screenReceiver) }
            if (!isPreview) setServes(home = false, lock = false)
            previous?.recycle()
            current?.recycle()
            previous = null
            current = null
            super.onDestroy()
        }

        /** Android 14+ reports where this engine is used; 0 = unknown (older Android). */
        private fun currentFlags(): Int =
            if (Build.VERSION.SDK_INT >= 34) runCatching { wallpaperFlags }.getOrDefault(0) else 0

        /** (home, lock). Unknown flags mean the classic case: the Home screen wallpaper. */
        private fun screensFor(which: Int): Pair<Boolean, Boolean> =
            if (which == 0) true to false
            else (which and WallpaperManager.FLAG_SYSTEM != 0) to (which and WallpaperManager.FLAG_LOCK != 0)

        private fun setServes(home: Boolean, lock: Boolean) {
            val dh = (if (home) 1 else 0) - (if (servesHome) 1 else 0)
            val dl = (if (lock) 1 else 0) - (if (servesLock) 1 else 0)
            servesHome = home
            servesLock = lock
            // Tiny file writes; done right here so create/destroy of two engines never reorder.
            if (dh != 0 || dl != 0) EngineRegistry.update(ctx, dh, dl)
        }

        /** Android 14+: the user moved the wallpaper between Home / lock screen. */
        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            if (!isPreview) {
                val (home, lock) = screensFor(which)
                setServes(home, lock)
            }
        }

        /**
         * The Home screen became visible (unlock, leaving an app, recents...). Deliberately does
         * no drawing: the surface still holds the last frame. Only a cheap marker check, off the
         * main thread, in case a change is waiting for unlock.
         */
        override fun onVisibilityChanged(visible: Boolean) {
            if (visible && servesHome) io.execute {
                if (SharedState.exists(ctx, SharedState.PENDING) && !isLocked()) sendToApp(EngineEventsReceiver.ACTION_UNLOCKED)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        /**
         * The system waits for this before it shows the wallpaper. If the full image isn't
         * decoded yet (process just started), draw a quick low-resolution decode right away
         * (a few ms) instead of black or a wait; the sharp image replaces it moments later.
         */
        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            if (current == null) {
                val id = loadingId
                val quick = id?.let { decodeFile(it, sampleSize = 4) }
                if (quick != null) {
                    current = quick
                    queue.current = id
                }
            }
            draw()
        }

        // --- screen events (only marker checks; the app is woken only if needed) -----------

        private var screenReceiverOn = false
        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_USER_PRESENT -> io.execute {
                        if (SharedState.exists(ctx, SharedState.PENDING)) sendToApp(EngineEventsReceiver.ACTION_UNLOCKED)
                    }
                    Intent.ACTION_SCREEN_OFF -> io.execute {
                        if (SharedState.exists(ctx, SharedState.LOCK_PENDING)) sendToApp(EngineEventsReceiver.ACTION_SCREEN_OFF)
                    }
                }
            }
        }

        private fun registerScreenEvents() {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenReceiver, filter)
            }
            screenReceiverOn = true
        }

        private fun isLocked(): Boolean =
            runCatching { ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)

        // --- shared state from the app (inotify) --------------------------------------------

        @Suppress("DEPRECATION")
        private val observer = object : FileObserver(
            SharedState.dir(ctx).path,
            MOVED_TO or CLOSE_WRITE or DELETE or CREATE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                when (path) {
                    SharedState.HOME -> {
                        val id = SharedState.read(ctx, SharedState.HOME)
                        if (id != null) main.post { onHomePublished(id) }
                    }
                    SharedState.UPCOMING -> {
                        val ids = SharedState.readList(ctx, SharedState.UPCOMING)
                        main.post {
                            queue.setUpcoming(ids)
                            schedulePreload()
                        }
                    }
                    SharedState.DOUBLE_TAP_OFF -> main.post {
                        doubleTapOff = SharedState.exists(ctx, SharedState.DOUBLE_TAP_OFF)
                        updateTouchDelivery()
                    }
                }
            }
        }

        /** The app published a Home image: our own switch confirmed, or its own change to show. */
        private fun onHomePublished(id: String) {
            if (!queue.onHome(id)) return
            showId(id, animate = true)
        }

        /**
         * The whole Home screen is the double-tap area: every touch the system forwards to the
         * wallpaper counts (on any page, over empty cells and over widgets that don't use taps),
         * plus the taps the launcher itself reports. Delivery is off only when double tap is.
         */
        private fun updateTouchDelivery() {
            setTouchEventsEnabled(!isPreview && !doubleTapOff)
        }

        // --- double tap ---------------------------------------------------------------------

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && !doubleTapOff && !isPreview) {
                val now = SystemClock.uptimeMillis()
                seen(now)
                if (taps.onCommandTap(now, x.toFloat(), y.toFloat())) onDoubleTap()
            }
            return null
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downT = event.eventTime; downX = event.x; downY = event.y; tapCandidate = true
                }
                MotionEvent.ACTION_MOVE -> if (tapCandidate &&
                    (abs(event.x - downX) > vc.scaledTouchSlop || abs(event.y - downY) > vc.scaledTouchSlop)
                ) tapCandidate = false
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    tapCandidate = false
                    taps.resetRaw()
                }
                MotionEvent.ACTION_UP -> {
                    if (tapCandidate && !doubleTapOff &&
                        event.eventTime - downT <= ViewConfiguration.getLongPressTimeout()
                    ) {
                        seen(event.eventTime)
                        if (taps.onRawTap(event.eventTime, event.x, event.y)) onDoubleTap()
                    }
                    tapCandidate = false
                }
            }
        }

        /** Diagnostics for the app screen: count each physical tap once. */
        private fun seen(now: Long) {
            if (lastSeenAt == Long.MIN_VALUE || now - lastSeenAt > 150) {
                val n = ++homeTaps
                io.execute { SharedState.write(ctx, SharedState.HOME_TAPS, n.toString()) }
            }
            lastSeenAt = now
        }

        /**
         * Switch right now to the preloaded next image (no round trip to the app), then tell the
         * app which one it was. Never on the lock screen. No haptics on purpose.
         */
        private fun onDoubleTap() {
            if (isPreview || doubleTapOff || isLocked()) return
            val id = queue.nextCandidate()
            if (id != null) switchLocally(id) else io.execute {
                // The app hasn't published an order yet (first run): pick any other image.
                val all = File(ctx.filesDir, "walls").list()?.filter { it.endsWith(".jpg") }
                    ?.map { it.removeSuffix(".jpg") }.orEmpty()
                val pick = all.filter { it != queue.current }.randomOrNull()
                if (pick != null) main.post { switchLocally(pick) }
            }
        }

        private fun switchLocally(id: String) {
            queue.onLocalSwitch(id)
            showId(id, animate = true)
            sendToApp(EngineEventsReceiver.ACTION_DOUBLE_TAP, id)
        }

        // --- images -------------------------------------------------------------------------

        /** Shows [id]: instantly from the preload if possible, otherwise decodes it first. */
        private fun showId(id: String, animate: Boolean) {
            val p = preload
            if (p != null && p.id == id) {
                val b = p.bitmap
                if (b != null) {
                    preload = null
                    show(id, b, animate)
                } else {
                    p.showWhenReady = true // decode in progress: show the moment it's done
                    ++loadSeq // a load of something else must not land after this
                    loadTask?.cancel(true)
                    loadingId = null
                }
                return
            }
            startLoad(id, animate)
        }

        private fun startLoad(id: String, animate: Boolean) {
            val seq = ++loadSeq
            loadTask?.cancel(true)
            loadingId = id
            loadTask = io.submit {
                val bmp = decodeFile(id, sampleSize = 1)
                main.post {
                    if (seq != loadSeq) {
                        bmp?.recycle()
                    } else {
                        loadingId = null
                        if (bmp != null) show(id, bmp, animate)
                    }
                }
            }
        }

        /** Decodes the next image in the background so a double tap can switch at once. */
        private fun schedulePreload() {
            val target = queue.nextCandidate()
            val p = preload
            if (p != null && (p.id == target || p.showWhenReady)) return
            p?.task?.cancel(true)
            p?.bitmap?.recycle()
            preload = null
            if (target == null) return
            val next = Preload(target)
            preload = next
            next.task = io.submit {
                val bmp = decodeFile(target, sampleSize = 1)
                main.post {
                    if (preload !== next || bmp == null) {
                        bmp?.recycle()
                        if (preload === next) preload = null
                        return@post
                    }
                    if (next.showWhenReady) {
                        preload = null
                        show(target, bmp, animate = true)
                    } else {
                        next.bitmap = bmp
                    }
                }
            }
        }

        private fun decodeFile(id: String, sampleSize: Int): Bitmap? {
            val file = SharedState.imageFile(ctx, id)
            if (!file.isFile) return null
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.HARDWARE // lives in GPU memory, draws for free
                inSampleSize = sampleSize
            }
            return runCatching { BitmapFactory.decodeFile(file.path, opts) }.getOrNull()
        }

        /** Main thread. Swaps in [bmp] with a short cross-fade when visible. */
        private fun show(id: String, bmp: Bitmap, animate: Boolean) {
            // Whatever is shown now wins: a decode still running for an older request is dropped.
            ++loadSeq
            loadingId = null
            animator?.cancel() // its end listener recycles the old 'previous'
            val old = current
            current = bmp
            queue.current = id
            io.execute { computeColors(id) }
            schedulePreload()

            if (old == null || old === bmp || !animate || !isVisible) {
                if (old !== bmp) old?.recycle()
                previous = null
                fade = 1f
                draw() // even when hidden: returning to the Home screen then costs nothing
                return
            }
            previous = old
            fade = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FADE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { fade = it.animatedValue as Float; draw() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        fade = 1f
                        previous?.recycle()
                        previous = null
                        draw()
                    }
                })
                start()
            }
        }

        // --- colors -------------------------------------------------------------------------

        /** Report the stored colors immediately, so a restarted process changes nothing. */
        private fun restoreColors() {
            if (Build.VERSION.SDK_INT < 27) return
            val seed = SharedState.read(ctx, COLOR_SEED)?.toIntOrNull() ?: return
            val hints = SharedState.read(ctx, COLOR_HINTS)?.toIntOrNull() ?: 0
            reportedColors = buildColors(seed, hints)
            reportedHints = hints
        }

        private fun buildColors(seed: Int, hints: Int): WallpaperColors =
            if (Build.VERSION.SDK_INT >= 31) WallpaperColors(Color.valueOf(seed), null, null, hints)
            else WallpaperColors(Color.valueOf(seed), null, null)

        /**
         * IO thread. The primary (seed) color stays the same across images, so Android/HyperOS
         * doesn't rebuild the system theme — and restart apps and the launcher — on each change.
         * Only the light/dark hint follows the image, so Home screen labels stay readable.
         */
        private fun computeColors(id: String) {
            if (Build.VERSION.SDK_INT < 27) return
            val thumb = runCatching { BitmapFactory.decodeFile(SharedState.thumbFile(ctx, id).path) }.getOrNull() ?: return
            val full = try {
                WallpaperColors.fromBitmap(thumb)
            } catch (e: Exception) {
                return
            } finally {
                thumb.recycle()
            }
            val seed = SharedState.read(ctx, COLOR_SEED)?.toIntOrNull() ?: full.primaryColor.toArgb().also {
                SharedState.write(ctx, COLOR_SEED, it.toString())
            }
            val hints = if (Build.VERSION.SDK_INT >= 31) full.colorHints else 0
            main.post {
                val first = reportedColors == null
                if (!first && hints == reportedHints) return@post
                if (!first && Build.VERSION.SDK_INT < 31) return@post // no hints there: keep stable
                reportedColors = buildColors(seed, hints)
                reportedHints = hints
                io.execute { SharedState.write(ctx, COLOR_HINTS, hints.toString()) }
                notifyColorsChanged()
            }
        }

        override fun onComputeColors(): WallpaperColors? = reportedColors

        // --- drawing ------------------------------------------------------------------------

        private fun draw() {
            val holder = surfaceHolder
            val canvas: Canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val prev = previous
                val cur = current
                if (prev != null && fade < 1f) drawCrop(canvas, prev, 255)
                if (cur != null) drawCrop(canvas, cur, (fade * 255).toInt())
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun drawCrop(canvas: Canvas, bmp: Bitmap, alpha: Int) {
            val cw = canvas.width
            val ch = canvas.height
            val scale = max(cw.toFloat() / bmp.width, ch.toFloat() / bmp.height)
            val sw = (cw / scale).toInt()
            val sh = (ch / scale).toInt()
            val sx = (bmp.width - sw) / 2
            val sy = (bmp.height - sh) / 2
            src.set(sx, sy, sx + sw, sy + sh)
            dst.set(0, 0, cw, ch)
            paint.alpha = alpha
            canvas.drawBitmap(bmp, src, dst, paint)
        }
    }

    private companion object {
        /** Written by older builds (raw touches switched off); deleted on start. */
        const val RAW_TOUCH_OFF = "raw_touch_off"
        const val PRESS_ALLOWANCE_MS = 150L
        /** Engine-only: the stable seed color and the current light/dark hints. */
        const val COLOR_SEED = "color_seed"
        const val COLOR_HINTS = "color_hints"
        const val FADE_MS = 180L
    }
}
