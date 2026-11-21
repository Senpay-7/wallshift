package app.wallshift.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineQueueTest {

    @Test
    fun nextIsTheHeadOfTheAppsOrder() {
        val q = EngineQueue().apply { current = "a"; setUpcoming(listOf("b", "c", "d")) }
        assertEquals("b", q.nextCandidate())
    }

    @Test
    fun rapidTapsWalkTheOrderWithoutWaitingForTheApp() {
        val q = EngineQueue().apply { current = "a"; setUpcoming(listOf("b", "c", "d")) }
        val shown = mutableListOf<String>()
        repeat(3) {
            val id = q.nextCandidate()!!
            q.onLocalSwitch(id)
            shown += id
        }
        assertEquals(listOf("b", "c", "d"), shown)
        assertNull("app hasn't published more yet", q.nextCandidate())
    }

    @Test
    fun confirmationsAreEchoesNotNewChanges() {
        val q = EngineQueue().apply { current = "a"; setUpcoming(listOf("b", "c", "d")) }
        q.onLocalSwitch("b")
        q.onLocalSwitch("c")
        assertFalse("echo of our switch to b", q.onHome("b"))
        // app republished its order after committing b
        q.setUpcoming(listOf("c", "d", "e"))
        assertEquals("c still in flight, so d is next", "d", q.nextCandidate())
        assertFalse(q.onHome("c"))
        q.setUpcoming(listOf("d", "e"))
        assertEquals("d", q.nextCandidate())
        assertEquals("c", q.current)
    }

    @Test
    fun appMadeChangeIsShownAndVoidsUnconfirmedSwitches() {
        val q = EngineQueue().apply { current = "a"; setUpcoming(listOf("b", "c")) }
        q.onLocalSwitch("b")
        assertTrue("auto-change from the app must be drawn", q.onHome("x"))
        q.current = "x"
        // later confirmation of b arrives: it is now a real change again (app's final word)
        assertTrue(q.onHome("b"))
    }

    @Test
    fun publishingTheImageAlreadyOnScreenDrawsNothing() {
        val q = EngineQueue().apply { current = "a" }
        assertFalse(q.onHome("a"))
    }

    @Test
    fun neverOffersTheCurrentImage() {
        val q = EngineQueue().apply { current = "b"; setUpcoming(listOf("b", "c")) }
        assertEquals("c", q.nextCandidate())
    }
}
