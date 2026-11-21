package app.wallshift.shared

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.wallshift.WallApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SharedStateTest {
    private val app = ApplicationProvider.getApplicationContext<WallApp>()

    @Test
    fun writeIsAtomicAndReadable() {
        assertNull(SharedState.read(app, SharedState.HOME))
        SharedState.write(app, SharedState.HOME, "abc")
        assertEquals("abc", SharedState.read(app, SharedState.HOME))
        SharedState.write(app, SharedState.HOME, "def")
        assertEquals("def", SharedState.read(app, SharedState.HOME))
        // no temp files left behind for the watcher to trip over
        assertEquals(listOf(SharedState.HOME), SharedState.dir(app).list()!!.toList())
    }

    @Test
    fun markersToggle() {
        SharedState.mark(app, SharedState.PENDING, true)
        assertTrue(SharedState.exists(app, SharedState.PENDING))
        SharedState.mark(app, SharedState.PENDING, false)
        assertFalse(SharedState.exists(app, SharedState.PENDING))
    }

    @Test
    fun newestImageIsFoundWithoutTheAppGraph() {
        val walls = java.io.File(app.filesDir, "walls").apply { mkdirs() }
        java.io.File(walls, "old.jpg").apply { writeText("x"); setLastModified(1_000_000) }
        java.io.File(walls, "new.jpg").apply { writeText("x"); setLastModified(2_000_000) }
        assertEquals("new", SharedState.newestImageId(app))
    }
}
