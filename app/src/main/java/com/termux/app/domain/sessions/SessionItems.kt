package com.termux.app.domain.sessions

/**
 * Builds the sessions-bar items from [snapshots], pairing each with its stable [ids].
 *
 * Exactly the item at [activeIndex] is marked active; an out-of-range index marks none active.
 * [contexts] maps a stable id to its already-shortened subtitle, and [unseen] holds the ids that
 * produced output while they were not active.
 */
@JvmOverloads
fun sessionItems(
    snapshots: List<SessionSnapshot>,
    ids: List<Int>,
    activeIndex: Int,
    contexts: Map<Int, String?> = emptyMap(),
    unseen: Set<Int> = emptySet(),
): List<SessionItem> {
    require(ids.size == snapshots.size) { "ids and snapshots must have the same size" }
    return snapshots.mapIndexed { index, snapshot ->
        val id = ids[index]
        sessionItem(
            id = id,
            name = snapshot.name,
            terminalTitle = snapshot.terminalTitle,
            running = snapshot.running,
            exitCode = snapshot.exitStatus,
            active = index == activeIndex,
        ).copy(
            subtitle = contexts[id],
            unseenOutput = id in unseen,
        )
    }
}
