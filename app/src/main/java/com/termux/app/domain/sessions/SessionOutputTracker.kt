package com.termux.app.domain.sessions

/**
 * Remembers which sessions produced output while they were not the active tab.
 *
 * The tracker holds no Android or session references; the activity owns the instance and feeds it
 * from the session-output callback, so the sessions bar can stay a pure projection.
 */
class SessionOutputTracker {

    private val unseen = LinkedHashSet<Int>()

    /** Records output for [id]; only an inactive session becomes unseen. */
    fun recordOutput(id: Int, active: Boolean) {
        if (!active) unseen.add(id)
    }

    /** Clears the mark once the user has looked at [id]. */
    fun clear(id: Int) {
        unseen.remove(id)
    }

    /** Forgets a session that no longer exists. */
    fun remove(id: Int) {
        unseen.remove(id)
    }

    fun hasUnseen(id: Int): Boolean = unseen.contains(id)

    fun unseen(): Set<Int> = unseen.toSet()
}
