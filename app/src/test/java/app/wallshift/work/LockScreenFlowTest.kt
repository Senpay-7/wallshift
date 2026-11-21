package app.wallshift.work

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.wallshift.WallApp
import app.wallshift.data.Target
import app.wallshift.data.WallItem
import app.wallshift.shared.SharedState
import app.wallshift.wallpaper.WallshiftWallpaperService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** End-to-end through the real Graph: settings, store, changer and runner. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LockScreenFlowTest {

    private val app = ApplicationProvider.getApplicationContext<WallApp>()
    private val g get() = app.graph
    private val wm get() = WallpaperManager.getInstance(app)

    private fun setLocked(locked: Boolean) {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    }

    @Before
    fun setUp(): Unit = runBlocking {
        // Three tiny prepared images in the library. Load the index first, like the importer
        // does, so the orphan sweep doesn't remove files written before their commit.
        g.store.snapshot()
        g.store.wallsDir.mkdirs()
        g.store.thumbsDir.mkdirs()
        for (i in 0 until 3) {
            val id = "img$i"
            val bmp = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)
            g.store.imageFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            g.store.thumbFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            g.store.commit(WallItem(id, i.toLong()))
        }
        g.settings.setTarget(Target.BOTH)
        g.settings.setInterval(30)
        g.settings.setAuto(true)
    }

    private fun lockBitmapSet() = shadowOf(wm).getBitmap(WallpaperManager.FLAG_LOCK) != null

    @Test
    fun changeIsDeferredWhileLockedAndAppliedOnUnlock(): Unit = runBlocking {
        setLocked(true)
        g.runner.onDue()
        assertTrue("change must wait for unlock", g.settings.isPending())
        assertTrue("wallpaper process checks this marker on unlock", SharedState.exists(app, SharedState.PENDING))
        assertFalse("lock screen must keep the last photo", lockBitmapSet())
        assertNull(g.settings.bag().last)

        // Still locked: re-check does nothing.
        g.runner.onMaybeUnlocked()
        assertTrue(g.settings.isPending())
        assertNull(g.settings.bag().last)

        setLocked(false)
        g.runner.onMaybeUnlocked()
        assertFalse(g.settings.isPending())
        assertFalse(SharedState.exists(app, SharedState.PENDING))
        assertNotNull("applied right after unlock", g.settings.bag().last)
        assertTrue(lockBitmapSet())

        // A second unlock signal must not change again.
        val shown = g.settings.bag().last
        g.runner.onMaybeUnlocked()
        assertEquals(shown, g.settings.bag().last)
    }

    @Test
    fun unlockedTickChangesImmediately(): Unit = runBlocking {
        setLocked(false)
        g.runner.onDue()
        assertNotNull(g.settings.bag().last)
        assertFalse(g.settings.isPending())
    }

    @Test
    fun screenOffCountsAsLocked(): Unit = runBlocking {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        g.runner.onDue()
        assertTrue(g.settings.isPending())
        assertNull(g.settings.bag().last)
    }

    private fun activateLive() {
        // Hidden system API; Robolectric's shadow makes our service the active live wallpaper.
        shadowOf(app).grantPermissions("android.permission.SET_WALLPAPER_COMPONENT")
        WallpaperManager::class.java.getMethod("setWallpaperComponent", ComponentName::class.java)
            .invoke(wm, ComponentName(app, WallshiftWallpaperService::class.java))
    }

    @Test
    fun withLiveWallpaperHomeChangesInstantlyAndLockIsWrittenAtScreenOff(): Unit = runBlocking {
        setLocked(false)
        activateLive()
        assertTrue(g.changer.isLiveActive())

        assertTrue(g.changer.changeNextIfIdle())
        val id = g.settings.bag().last
        assertEquals("live engine gets the new image", id, g.changer.homeImage.value)
        assertEquals(id, SharedState.read(app, SharedState.HOME))
        assertNull("home must not be replaced by a static bitmap", shadowOf(wm).getBitmap(WallpaperManager.FLAG_SYSTEM))
        assertFalse("no slow lock write while the user is tapping", lockBitmapSet())
        assertEquals(id, g.settings.lockPending())
        assertTrue("wallpaper process wakes the app at screen-off only if set", SharedState.exists(app, SharedState.LOCK_PENDING))

        g.runner.onScreenOff()
        assertTrue("lock gets the latest photo at screen-off", lockBitmapSet())
        assertNull(g.settings.lockPending())
        assertFalse(SharedState.exists(app, SharedState.LOCK_PENDING))
    }

    @Test
    fun everyDoubleTapRequestIsHonoured(): Unit = runBlocking {
        setLocked(false)
        activateLive()
        // The engine's initial image (newest one) is loaded first.
        withTimeout(5_000) { while (g.changer.homeImage.value == null) delay(5) }
        val initial = g.changer.homeImage.value
        val seen = mutableListOf<String?>()
        repeat(6) {
            val before = g.changer.homeImage.value
            g.changer.requestNext()
            // every request must produce a visible change
            withTimeout(5_000) { while (g.changer.homeImage.value == before) delay(5) }
            seen += g.changer.homeImage.value
        }
        assertEquals(6, seen.size)
        (listOf(initial) + seen).zipWithNext().forEach { (a, b) -> assertTrue("each tap shows a new image", a != b) }
        assertEquals(setOf("img0", "img1", "img2"), (listOf(initial) + seen).toSet())
    }

    @Test
    fun doubleTapSettingDefaultsToOnAndPersists(): Unit = runBlocking {
        assertTrue(g.settings.current().doubleTap)
        g.settings.setDoubleTap(false)
        assertFalse(g.settings.current().doubleTap)
    }
}
