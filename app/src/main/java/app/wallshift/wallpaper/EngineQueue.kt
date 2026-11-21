package app.wallshift.wallpaper

/**
 * The live wallpaper's view of the shuffle order, so a double tap can switch *instantly* to an
 * image decoded in advance, without waiting for the app process.
 *
 * - [setUpcoming]: the order published by the app (head = what the app would pick next).
 * - [onLocalSwitch]: the engine switched by itself; the id is "in flight" until the app confirms.
 * - [onHome]: the app published a Home id. Either the confirmation of our own switch (nothing
 *   to draw) or a change made by the app itself (auto-change, "Change now") that must be shown.
 */
class EngineQueue {
    private var upcoming: List<String> = emptyList()
    private val inFlight = ArrayDeque<String>()

    /** Image on screen. */
    var current: String? = null

    fun setUpcoming(ids: List<String>) {
        upcoming = ids
    }

    /** What the next double tap shows (and what to preload): first upcoming id not yet used. */
    fun nextCandidate(): String? = upcoming.firstOrNull { it != current && it !in inFlight }

    fun onLocalSwitch(id: String) {
        inFlight.addLast(id)
        if (inFlight.size > MAX_IN_FLIGHT) inFlight.removeFirst()
        current = id
    }

    /** @return true if [id] must be displayed (it is not an echo of our own switch). */
    fun onHome(id: String): Boolean {
        if (id in inFlight) {
            // The app confirmed our switch to [id] (and everything before it).
            while (inFlight.isNotEmpty()) {
                if (inFlight.removeFirst() == id) break
            }
            return false
        }
        // A change made by the app: it wins, our unconfirmed switches are void.
        inFlight.clear()
        return id != current
    }

    private companion object {
        const val MAX_IN_FLIGHT = 16
    }
}
