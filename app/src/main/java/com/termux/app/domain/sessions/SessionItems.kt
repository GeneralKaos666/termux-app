package com.termux.app.domain.sessions

/**
 * Builds the sessions-bar items from [snapshots], pairing each with its stable [ids].
 *
 * Exactly the item at [activeIndex] is marked active; an out-of-range index marks none active.
 */
fun sessionItems(
    snapshots: List<SessionSnapshot>,
    ids: List<Int>,
    activeIndex: Int,
): List<SessionItem> {
    require(ids.size == snapshots.size) { "ids and snapshots must have the same size" }
    return snapshots.mapIndexed { index, snapshot ->
        sessionItem(
            id = ids[index],
            name = snapshot.name,
            terminalTitle = snapshot.terminalTitle,
            running = snapshot.running,
            exitCode = snapshot.exitStatus,
            active = index == activeIndex,
        )
    }
}
