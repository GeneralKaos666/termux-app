package com.termux.app.domain.sessions

import java.util.IdentityHashMap

/**
 * Assigns stable ids to session objects by identity.
 *
 * Ids start at 1 and are never reused, even after a key is no longer requested.
 */
class SessionIdAllocator {
    private val ids = IdentityHashMap<Any, Int>()
    private var nextId = 0

    fun idFor(key: Any): Int = ids.getOrPut(key) { ++nextId }
}
