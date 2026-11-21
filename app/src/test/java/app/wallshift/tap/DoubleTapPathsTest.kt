package app.wallshift.tap

import android.app.ActivityManager
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.os.PowerManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.wallshift.R
import app.wallshift.WallApp
import app.wallshift.data.Target
import app.wallshift.data.WallItem
import app.wallshift.shared.SharedState
import app.wallshift.wallpaper.EngineQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app-process side of every double-tap path, through the real Graph. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DoubleTapPathsTest {

    private val app = ApplicationProvider.getApplicationContext<WallApp>()
    private val g get() = app.graph
    private val wm get() = WallpaperManager.getInstance(app)
    private val lib = listOf("img0", "img1", "img2")

    @Before
    fun setUp(): Unit = runBlocking {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        g.store.snapshot()
        g.store.wallsDir.mkdirs()
        g.store.thumbsDir.mkdirs()
        for ((i, id) in lib.withIndex()) {
            val bmp = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)
            g.store.imageFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            g.store.thumbFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            g.store.commit(WallItem(id, i.toLong()))
        }
        g.settings.setTarget(Target.BOTH)
    }

    /**
     * The wallpaper process announces the screens it draws with its pid; the app checks that
     * pid against its own running processes.
     */
    private fun engineRunning(onLockToo: Boolean = false, onHome: Boolean = true) {
        val pid = 4242
        val am = app.getSystemService(ActivityManager::class.java)
        shadowOf(am).setProcesses(
            listOf(
                ActivityManager.RunningAppProcessInfo(app.packageName, android.os.Process.myPid(), arrayOf(app.packageName)),
                ActivityManager.RunningAppProcessInfo("${app.packageName}:wallpaper", pid, arrayOf(app.packageName)),
            ),
        )
        if (onHome) SharedState.write(app, SharedState.ENGINE, pid.toString())
        else SharedState.file(app, SharedState.ENGINE).delete()
        if (onLockToo) SharedState.write(app, SharedState.ENGINE_LOCK, pid.toString())
        else SharedState.file(app, SharedState.ENGINE_LOCK).delete()
    }

    private suspend fun awaitUpcoming(): List<String> = withTimeout(5_000) {
        var list = SharedState.readList(app, SharedState.UPCOMING)
        while (list.isEmpty()) {
            delay(10)
            list = SharedState.readList(app, SharedState.UPCOMING)
        }
        list
    }

    private suspend fun awaitHome(id: String) = withTimeout(5_000) {
        while (SharedState.read(app, SharedState.HOME) != id || g.settings.bag().last != id) delay(10)
    }

    private fun sendEngineDoubleTap(id: String) {
        app.sendBroadcast(
            Intent(app, EngineEventsReceiver::class.java)
                .setAction(EngineEventsReceiver.ACTION_DOUBLE_TAP)
                .putExtra(EngineEventsReceiver.EXTRA_ID, id),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun engineRunningMeansHomeIsNeverReplacedByStaticBitmap(): Unit = runBlocking {
        // Some ROMs don't report the live wallpaper to apps; our running engine is the truth.
        assertNull(wm.wallpaperInfo)
        engineRunning()
        g.changer.changeNext()
        assertNull(
            "setBitmap(FLAG_SYSTEM) would kill the live wallpaper and with it the double tap",
            shadowOf(wm).getBitmap(WallpaperManager.FLAG_SYSTEM),
        )
        assertEquals("engine process reads the id from this file", g.settings.bag().last, SharedState.read(app, SharedState.HOME))
    }

    @Test
    fun theNextChangeIsExactlyTheImageTheWallpaperPreloaded(): Unit = runBlocking {
        engineRunning()
        repeat(5) {
            val head = awaitUpcoming().first()
            g.changer.changeNext()
            assertEquals("auto-change / Change now shows the preloaded image", head, SharedState.read(app, SharedState.HOME))
        }
    }

    @Test
    fun engineSwitchIsRecordedAsShown(): Unit = runBlocking {
        engineRunning()
        val upcoming = awaitUpcoming()
        val id = upcoming.last() // not necessarily the head (engine fallback / race)
        sendEngineDoubleTap(id)
        awaitHome(id)
        assertFalse("it was shown, so it leaves this round", id in g.settings.bag().remaining)
        assertFalse(id in awaitUpcoming().take(1))
        assertNull("no static Home bitmap in live mode", shadowOf(wm).getBitmap(WallpaperManager.FLAG_SYSTEM))
    }

    @Test
    fun continuousHomeDoubleTapsFollowTheShuffleRules(): Unit = runBlocking {
        engineRunning()
        // Simulate the engine: show the head of the published order at once, then report it.
        val q = EngineQueue()
        q.current = SharedState.read(app, SharedState.HOME)
        val shown = ArrayList<String>()
        repeat(lib.size * 4) {
            q.setUpcoming(awaitUpcoming())
            val id = q.nextCandidate()!!
            q.onLocalSwitch(id)
            shown += id
            sendEngineDoubleTap(id)
            awaitHome(id)
            assertFalse("app's confirmation is just an echo", q.onHome(id))
            // wait for the refreshed order
            withTimeout(5_000) { while (SharedState.readList(app, SharedState.UPCOMING).firstOrNull() == id) delay(10) }
        }
        shown.zipWithNext().forEach { (a, b) -> assertNotEquals("no repeat in a row", a, b) }
        // The bag started empty, so rounds are aligned: every block of 3 is a permutation.
        shown.chunked(lib.size).forEach { assertEquals(lib.toSet(), it.toSet()) }
        assertEquals(lib.size * 4, g.tapStats.value.changes)
    }

    @Test
    fun lockScreenServedByTheLiveWallpaperIsNeverWritten(): Unit = runBlocking {
        engineRunning(onLockToo = true)
        g.changer.changeNext()
        assertFalse("nothing to write for the lock screen", SharedState.exists(app, SharedState.LOCK_PENDING))
        g.runner.onScreenOff()
        assertNull("no static lock wallpaper replacing the live one", shadowOf(wm).getBitmap(WallpaperManager.FLAG_LOCK))
    }

    @Test
    fun separateLockScreenGetsTheLatestPhotoAtScreenOff(): Unit = runBlocking {
        engineRunning(onLockToo = false)
        g.changer.changeNext()
        assertTrue(SharedState.exists(app, SharedState.LOCK_PENDING))
        g.runner.onScreenOff()
        assertNotNull(shadowOf(wm).getBitmap(WallpaperManager.FLAG_LOCK))
        assertFalse(SharedState.exists(app, SharedState.LOCK_PENDING))
    }

    @Test
    fun doubleTapSwitchIsMirroredForTheWallpaperProcess(): Unit = runBlocking {
        g.settings.setDoubleTap(false)
        assertTrue("wallpaper process sees the switch", SharedState.exists(app, SharedState.DOUBLE_TAP_OFF))
        g.settings.setDoubleTap(true)
        assertFalse(SharedState.exists(app, SharedState.DOUBLE_TAP_OFF))
    }

    @Test
    fun liveWallpaperOnTheLockScreenOnlyIsNeverOverwrittenThere(): Unit = runBlocking {
        engineRunning(onLockToo = true, onHome = false)
        assertFalse(g.changer.isLiveActive())
        assertTrue(g.changer.lockShowsLive())
        g.changer.changeNext()
        assertNotNull("Home has a static wallpaper: set it", shadowOf(wm).getBitmap(WallpaperManager.FLAG_SYSTEM))
        assertNull("lock screen runs the live wallpaper: never replace it", shadowOf(wm).getBitmap(WallpaperManager.FLAG_LOCK))
        assertEquals("the lock-screen engine shows the new image", g.settings.bag().last, SharedState.read(app, SharedState.HOME))
    }

    @Test
    fun deadEngineProcessIsNotLive() {
        engineRunning()
        assertTrue(SharedState.engineAlive(app))
        SharedState.write(app, SharedState.ENGINE, "999999") // stale marker of a killed process
        assertFalse(SharedState.engineAlive(app))
        SharedState.write(app, SharedState.ENGINE, "1") // what old builds wrote into the lock marker
        assertFalse(SharedState.engineAlive(app))
    }

    @Test
    fun oldInvisibleTapAreasBecomeVisibleSoTheyCanBeRemoved() {
        val ids = shadowOf(AppWidgetManager.getInstance(app))
            .createWidgets(TapAreaWidget::class.java, R.layout.widget_tap_area, 2)
        assertEquals(2, TapAreaWidget.leftoverCount(app))
        TapAreaWidget.refreshLeftovers(app)
        val view = shadowOf(AppWidgetManager.getInstance(app)).getViewFor(ids[0])
        val texts = mutableListOf<String>()
        fun collect(v: android.view.View) {
            if (v is TextView) texts += v.text.toString()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(view)
        assertTrue(texts.joinToString(), texts.any { "no longer needed" in it && "Remove" in it })
    }
}
