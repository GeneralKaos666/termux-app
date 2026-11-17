package com.termux.app.domain.sessions

/**
 * Keeps the last foreground-command read per process for a short while. The tab bar refreshes on
 * every session change, and each read walks `/proc`; the label does not need to be fresher than a
 * second. The clock is injected by the caller so the domain layer stays free of Android APIs.
 */
internal class ForegroundCommandCache(
    private val ttlMillis: Long = 1000L,
    private val clock: () -> Long,
    private val read: (Int) -> String? = { readForegroundCommand(it) },
) {
    private var pid = -1
    private var value: String? = null
    private var readAt = Long.MIN_VALUE

    fun command(pid: Int): String? {
        val now = clock()
        if (pid == this.pid && now - readAt < ttlMillis) return value
        val fresh = read(pid)
        this.pid = pid
        value = fresh
        readAt = now
        return fresh
    }
}
