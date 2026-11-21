package app.wallshift.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleBagTest {

    private fun ids(n: Int, prefix: String = "i") = List(n) { "$prefix$it" }

    /** Runs [count] picks, returning the picked ids and the final state. */
    private fun pick(
        state: BagState,
        library: List<String>,
        count: Int,
        random: Random,
    ): Pair<List<String>, BagState> {
        var s = state
        val out = ArrayList<String>()
        repeat(count) {
            val (id, next) = ShuffleBag.next(s, library, random)!!
            out += id
            s = next
        }
        return out to s
    }

    @Test
    fun emptyLibraryYieldsNothing() {
        assertNull(ShuffleBag.next(BagState.Empty, emptyList(), Random(1)))
    }

    @Test
    fun singleItemAlwaysReturnsIt() {
        val (picked, _) = pick(BagState.Empty, listOf("only"), 5, Random(1))
        assertEquals(List(5) { "only" }, picked)
    }

    @Test
    fun noRepeatsWithinARound() {
        for (seed in 0 until 200) {
            val lib = ids(1 + seed % 40)
            val (picked, _) = pick(BagState.Empty, lib, lib.size, Random(seed))
            assertEquals("seed $seed: every item exactly once", lib.toSet(), picked.toSet())
            assertEquals(lib.size, picked.size)
        }
    }

    @Test
    fun everyRoundIsAPermutationAndBoundaryNeverRepeats() {
        for (seed in 0 until 300) {
            val n = 2 + seed % 9 // small sizes make a boundary repeat likely if unhandled
            val lib = ids(n)
            val rounds = 40
            val (picked, _) = pick(BagState.Empty, lib, n * rounds, Random(seed))
            picked.chunked(n).forEachIndexed { r, round ->
                assertEquals("seed $seed round $r", lib.toSet(), round.toSet())
            }
            picked.zipWithNext().forEachIndexed { i, (a, b) ->
                assertNotEquals("seed $seed: repeat at position $i", a, b)
            }
        }
    }

    @Test
    fun boundaryHoldsForTwoItems() {
        // With two images the order must strictly alternate across rounds.
        val (picked, _) = pick(BagState.Empty, listOf("a", "b"), 100, Random(7))
        picked.zipWithNext().forEach { (x, y) -> assertNotEquals(x, y) }
    }

    @Test
    fun addedMidRoundAreShownInSameRoundWithoutRepeats() {
        for (seed in 0 until 200) {
            val random = Random(seed)
            val lib = ids(10)
            val (first, mid) = pick(BagState.Empty, lib, 4, random)

            val added = ids(5, "new")
            val lib2 = lib + added
            // Rest of the round: 6 old remaining + 5 new.
            val (rest, end) = pick(mid, lib2, 11, random)

            val round = first + rest
            assertEquals("seed $seed", lib2.toSet(), round.toSet())
            assertEquals(lib2.size, round.size)
            assertTrue(rest.containsAll(added))
            assertTrue(end.remaining.isEmpty())

            // Next pick starts a new round and doesn't repeat the last one.
            val (nextId, _) = ShuffleBag.next(end, lib2, random)!!
            assertNotEquals(round.last(), nextId)
        }
    }

    @Test
    fun addedItemsGoToRandomPositionsNotJustTheEnd() {
        val positions = HashSet<Int>()
        for (seed in 0 until 300) {
            val random = Random(seed)
            val lib = ids(10)
            val (_, mid) = pick(BagState.Empty, lib, 2, random) // 8 remain
            val s = ShuffleBag.reconcile(mid, lib + "new", random)
            assertEquals(9, s.remaining.size)
            positions += s.remaining.indexOf("new")
            // Relative order of already-queued items is preserved.
            assertEquals(mid.remaining, s.remaining - "new")
        }
        assertEquals("new item should land in every slot 0..8", (0..8).toSet(), positions)
    }

    @Test
    fun removedMidRoundAreNeverShownAndRoundCompletes() {
        for (seed in 0 until 200) {
            val random = Random(seed)
            val lib = ids(10)
            val (first, mid) = pick(BagState.Empty, lib, 3, random)

            val removedRemaining = mid.remaining.take(2)
            val removedShown = first.first()
            val lib2 = lib - removedRemaining.toSet() - removedShown

            // 10 - 3 shown - 2 removed = 5 left in this round.
            val (rest, end) = pick(mid, lib2, 5, random)
            assertTrue(rest.none { it in removedRemaining || it == removedShown })
            assertEquals(rest.size, rest.toSet().size)
            assertTrue(rest.none { it in first })
            assertEquals(lib2.toSet(), (first.filter { it in lib2 } + rest).toSet())
            assertTrue(end.remaining.isEmpty())
            assertFalse(removedShown in end.shown)

            // Following rounds only use the remaining library.
            val (later, _) = pick(end, lib2, lib2.size * 3, random)
            assertTrue(later.all { it in lib2 })
            assertNotEquals(rest.last(), later.first())
        }
    }

    @Test
    fun removingLastShownItemStillWorks() {
        val random = Random(3)
        val lib = listOf("a", "b", "c")
        val (picked, end) = pick(BagState.Empty, lib, 3, random)
        val lib2 = lib - picked.last()
        val (next, _) = pick(end, lib2, 4, random)
        assertTrue(next.all { it in lib2 })
        next.zipWithNext().forEach { (x, y) -> assertNotEquals(x, y) }
    }

    @Test
    fun addAndRemoveTogetherMidRound() {
        for (seed in 0 until 100) {
            val random = Random(seed)
            val lib = ids(8)
            val (first, mid) = pick(BagState.Empty, lib, 3, random)
            val lib2 = (lib - mid.remaining.first()) + listOf("x", "y")
            val (rest, _) = pick(mid, lib2, 6, random) // 8-3-1 old + 2 new
            val round = first + rest
            assertEquals(round.size, round.toSet().size)
            assertEquals(lib2.toSet(), round.filter { it in lib2 }.toSet())
        }
    }

    @Test
    fun corruptStateIsRepaired() {
        // Duplicates and ids both shown and remaining must not cause repeats.
        val state = BagState(remaining = listOf("a", "a", "b", "c"), shown = setOf("c"), last = "c")
        val (picked, _) = pick(state, listOf("a", "b", "c", "d"), 3, Random(5))
        assertEquals(setOf("a", "b", "d"), picked.toSet())
    }

    @Test
    fun encodingRoundTrips() {
        val list = listOf("abc123", "zz9", "q")
        assertEquals(list, ShuffleBag.decodeList(ShuffleBag.encodeList(list)))
        assertEquals(emptyList<String>(), ShuffleBag.decodeList(""))
        assertEquals(emptyList<String>(), ShuffleBag.decodeList(null))
    }

    // --- take / prepare (instant switching by the live wallpaper) ---

    @Test
    fun preparedHeadIsExactlyWhatNextReturns() {
        for (seed in 0 until 200) {
            val random = Random(seed)
            val lib = ids(2 + seed % 9)
            var s = BagState.Empty
            repeat(lib.size * 3) {
                s = ShuffleBag.prepare(s, lib, random)
                val head = s.remaining.first()
                val (id, next) = ShuffleBag.next(s, lib, random)!!
                assertEquals("seed $seed", head, id)
                s = next
            }
        }
    }

    @Test
    fun takingPreparedHeadsKeepsAllRules() {
        for (seed in 0 until 300) {
            val random = Random(seed)
            val n = 2 + seed % 9
            val lib = ids(n)
            var s = BagState.Empty
            val picked = ArrayList<String>()
            repeat(n * 20) {
                s = ShuffleBag.prepare(s, lib, random)
                val id = s.remaining.first()
                s = ShuffleBag.take(s, lib, id, random)
                picked += id
            }
            picked.chunked(n).forEach { assertEquals("seed $seed", lib.toSet(), it.toSet()) }
            picked.zipWithNext().forEach { (a, b) -> assertNotEquals("seed $seed seam", a, b) }
        }
    }

    @Test
    fun takeOfANonHeadIdRemovesItFromTheRound() {
        val random = Random(4)
        val lib = ids(6)
        var s = ShuffleBag.prepare(BagState.Empty, lib, random)
        val notHead = s.remaining[3]
        s = ShuffleBag.take(s, lib, notHead, random)
        assertEquals(notHead, s.last)
        assertFalse(notHead in s.remaining)
        assertTrue(notHead in s.shown)
        // the rest of the round never repeats it
        val (rest, _) = pick(s, lib, 5, random)
        assertFalse(notHead in rest)
        assertEquals(lib.toSet() - notHead, rest.toSet())
    }

    @Test
    fun mixingNextAndTakeNeverRepeatsWithinARound() {
        for (seed in 0 until 200) {
            val random = Random(seed)
            val lib = ids(7)
            var s = BagState.Empty
            val round = ArrayList<String>()
            repeat(7) { i ->
                s = ShuffleBag.prepare(s, lib, random)
                if (i % 2 == 0) {
                    val (id, n) = ShuffleBag.next(s, lib, random)!!
                    round += id; s = n
                } else {
                    val id = s.remaining.last() // instant switch to some other upcoming image
                    s = ShuffleBag.take(s, lib, id, random); round += id
                }
            }
            assertEquals("seed $seed", lib.toSet(), round.toSet())
        }
    }

    @Test
    fun prepareStartsTheNextRoundWithoutRepeatingTheLastImage() {
        for (seed in 0 until 200) {
            val random = Random(seed)
            val lib = ids(2 + seed % 5)
            val (picked, end) = pick(BagState.Empty, lib, lib.size, random)
            assertTrue(end.remaining.isEmpty())
            val prepared = ShuffleBag.prepare(end, lib, random)
            assertEquals(lib.toSet(), prepared.remaining.toSet())
            assertNotEquals(picked.last(), prepared.remaining.first())
        }
    }

    @Test
    fun prepareOnEmptyLibraryIsANoOp() {
        assertEquals(BagState.Empty, ShuffleBag.prepare(BagState.Empty, emptyList(), Random(1)))
    }
}
