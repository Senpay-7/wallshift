package app.wallshift.domain

import kotlin.random.Random

/**
 * Persistent state of the shuffle bag.
 *
 * @param remaining ids still to be shown in the current round, in order.
 * @param shown ids already shown in the current round.
 * @param last the id shown most recently (survives round boundaries).
 */
data class BagState(
    val remaining: List<String> = emptyList(),
    val shown: Set<String> = emptySet(),
    val last: String? = null,
) {
    companion object {
        val Empty = BagState()
    }
}

/**
 * Random order without repeats.
 *
 * - Inside a round every image is shown exactly once.
 * - A new round is a fresh shuffle whose first item differs from the last item of the previous round.
 * - Images added mid-round are inserted into the remaining queue at random positions.
 * - Removed images are dropped from the queue.
 *
 * The bag is reconciled against the current library on every [next] call, so it is always
 * correct even if add/remove happened while nothing was running (e.g. in another process run).
 */
object ShuffleBag {

    /** Syncs [state] with [library]: drops removed ids, inserts unseen ids at random positions. */
    fun reconcile(state: BagState, library: Collection<String>, random: Random): BagState {
        val lib: Set<String> = library as? Set<String> ?: library.toHashSet()
        val shown = state.shown.filterTo(LinkedHashSet()) { it in lib }
        val seen = HashSet<String>(shown)
        // Keep order; drop removed ids, duplicates and anything already shown (defensive).
        val remaining = state.remaining.filterTo(ArrayList(lib.size)) { it in lib && seen.add(it) }
        for (id in library) {
            if (seen.add(id)) remaining.add(random.nextInt(remaining.size + 1), id)
        }
        return BagState(remaining, shown, state.last)
    }

    /**
     * Picks the next id. Returns null for an empty library.
     * The returned state must be persisted by the caller once the wallpaper is applied.
     */
    fun next(state: BagState, library: Collection<String>, random: Random): Pair<String, BagState>? {
        if (library.isEmpty()) return null
        var s = reconcile(state, library, random)
        if (s.remaining.isEmpty()) s = newRound(library, s.last, random)
        val id = s.remaining.first()
        return id to BagState(
            remaining = s.remaining.subList(1, s.remaining.size).toList(),
            shown = s.shown + id,
            last = id,
        )
    }

    /**
     * Records that [id] is being shown now, even if it isn't the head of the queue (the live
     * wallpaper switches instantly to the image it preloaded and reports it afterwards).
     * It leaves the remaining queue and counts as shown in this round.
     */
    fun take(state: BagState, library: Collection<String>, id: String, random: Random): BagState {
        val s = reconcile(state, library, random)
        val remaining = s.remaining.toMutableList().apply { remove(id) }
        return BagState(remaining, s.shown + id, id)
    }

    /**
     * Makes the upcoming order known in advance: reconciles with the library and, when the
     * round is used up, starts the next one right away (same seam rule as [next]). The head
     * of [BagState.remaining] is then exactly what [next] will return.
     */
    fun prepare(state: BagState, library: Collection<String>, random: Random): BagState {
        if (library.isEmpty()) return state
        val s = reconcile(state, library, random)
        return if (s.remaining.isEmpty()) newRound(library, s.last, random) else s
    }

    internal fun newRound(library: Collection<String>, last: String?, random: Random): BagState {
        val order = library.distinct().toMutableList()
        order.shuffle(random)
        if (order.size > 1 && order[0] == last) {
            val j = 1 + random.nextInt(order.size - 1)
            val tmp = order[j]
            order[j] = order[0]
            order[0] = tmp
        }
        return BagState(order, emptySet(), last)
    }

    // --- compact persistence (ids are [a-z0-9] only) ---

    fun encodeList(ids: Collection<String>): String = ids.joinToString(",")

    fun decodeList(raw: String?): List<String> =
        if (raw.isNullOrEmpty()) emptyList() else raw.split(',').filter { it.isNotEmpty() }
}
