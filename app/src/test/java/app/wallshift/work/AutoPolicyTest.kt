package app.wallshift.work

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoPolicyTest {
    @Test
    fun disabledDoesNothing() {
        for (locked in listOf(true, false)) for (tick in listOf(true, false)) for (p in listOf(true, false))
            assertEquals(DueAction.NOTHING, AutoPolicy.decide(false, locked, tick, p))
    }

    @Test
    fun dueWhileLockedIsDeferredSoLockScreenKeepsLastPhoto() {
        assertEquals(DueAction.DEFER, AutoPolicy.decide(true, locked = true, regularTick = true, pending = false))
        assertEquals(DueAction.DEFER, AutoPolicy.decide(true, locked = true, regularTick = false, pending = true))
    }

    @Test
    fun dueWhileUnlockedChanges() {
        assertEquals(DueAction.CHANGE, AutoPolicy.decide(true, locked = false, regularTick = true, pending = false))
    }

    @Test
    fun unlockAppliesPendingChangeOnlyOnce() {
        assertEquals(DueAction.CHANGE, AutoPolicy.decide(true, locked = false, regularTick = false, pending = true))
        // after it was applied (pending cleared) another unlock signal does nothing
        assertEquals(DueAction.NOTHING, AutoPolicy.decide(true, locked = false, regularTick = false, pending = false))
    }
}
