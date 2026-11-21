package app.wallshift.wallpaper

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.wallshift.WallApp
import app.wallshift.shared.SharedState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EngineRegistryTest {
    private val app = ApplicationProvider.getApplicationContext<WallApp>()
    private fun home() = SharedState.exists(app, SharedState.ENGINE)
    private fun lock() = SharedState.exists(app, SharedState.ENGINE_LOCK)

    @Before
    fun reset() = EngineRegistry.reset()

    @Test
    fun newEngineCreatedBeforeTheOldOneIsDestroyedKeepsTheWallpaperOn() {
        EngineRegistry.update(app, homeDelta = 1, lockDelta = 0) // engine A: Home
        EngineRegistry.update(app, homeDelta = 1, lockDelta = 1) // engine B: Home + lock (re-applied)
        EngineRegistry.update(app, homeDelta = -1, lockDelta = 0) // A destroyed afterwards
        assertTrue("B still draws the Home screen", home())
        assertTrue(lock())
        EngineRegistry.update(app, homeDelta = -1, lockDelta = -1) // B destroyed: wallpaper removed
        assertFalse(home())
        assertFalse(lock())
    }

    @Test
    fun lockScreenOnlyEngineIsReportedAsSuch() {
        EngineRegistry.update(app, homeDelta = 0, lockDelta = 1)
        assertFalse(home())
        assertTrue(lock())
    }

    @Test
    fun movingTheWallpaperBetweenScreensUpdatesTheMarkers() {
        EngineRegistry.update(app, homeDelta = 1, lockDelta = 0)
        // Android 14+: user applies it to Home and lock -> flags change on the same engine
        EngineRegistry.update(app, homeDelta = 0, lockDelta = 1)
        assertTrue(home())
        assertTrue(lock())
        // ...and later to the lock screen only
        EngineRegistry.update(app, homeDelta = -1, lockDelta = 0)
        assertFalse(home())
        assertTrue(lock())
    }
}
