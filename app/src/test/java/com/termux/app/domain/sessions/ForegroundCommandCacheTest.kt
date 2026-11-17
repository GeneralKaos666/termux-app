package com.termux.app.domain.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundCommandCacheTest {

    @Test
    fun `a value is reused within the ttl`() {
        var reads = 0
        var now = 0L
        val cache = ForegroundCommandCache(ttlMillis = 1000L, clock = { now }) {
            reads++
            "vim"
        }

        assertEquals("vim", cache.command(10))
        now = 900L
        assertEquals("vim", cache.command(10))
        assertEquals(1, reads)
    }

    @Test
    fun `a value is re-read after the ttl and on a pid change`() {
        var reads = 0
        var now = 0L
        val cache = ForegroundCommandCache(ttlMillis = 1000L, clock = { now }) { pid ->
            reads++
            if (pid == 10) "vim" else "top"
        }

        cache.command(10)
        now = 1001L
        cache.command(10)
        assertEquals(2, reads)

        cache.command(11)
        assertEquals(3, reads)
        assertEquals("top", cache.command(11))
    }

    @Test
    fun `a missing command is cached as well`() {
        var reads = 0
        val cache = ForegroundCommandCache(ttlMillis = 1000L, clock = { 0L }) {
            reads++
            null
        }
        assertNull(cache.command(10))
        assertNull(cache.command(10))
        assertEquals(1, reads)
    }
}
