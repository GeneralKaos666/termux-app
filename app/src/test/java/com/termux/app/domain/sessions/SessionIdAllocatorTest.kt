package com.termux.app.domain.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionIdAllocatorTest {

    @Test
    fun `an id is stable for the same key`() {
        val allocator = SessionIdAllocator()
        val a = Any()
        assertEquals(allocator.idFor(a), allocator.idFor(a))
    }

    @Test
    fun `ids are never reused after a key goes away`() {
        val allocator = SessionIdAllocator()
        val a = Any()
        val b = Any()
        val c = Any()
        assertEquals(1, allocator.idFor(a))
        assertEquals(2, allocator.idFor(b))
        assertEquals(1, allocator.idFor(a))
        assertEquals(3, allocator.idFor(c))
    }

    @Test
    fun `equal but distinct keys get distinct ids`() {
        val allocator = SessionIdAllocator()
        val a = String(charArrayOf('x'))
        val b = String(charArrayOf('x'))
        assertEquals(a, b)
        assertNotEquals(allocator.idFor(a), allocator.idFor(b))
    }
}
