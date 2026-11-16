package com.termux.app.domain.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionItemsTest {

    @Test
    fun `ids survive a reorder`() {
        val a = SessionSnapshot("A", null, true, 0)
        val b = SessionSnapshot("B", null, true, 0)
        val first = sessionItems(listOf(a, b), listOf(7, 8), 0)
        assertEquals(listOf(7, 8), first.map { it.id })
        val second = sessionItems(listOf(b, a), listOf(8, 7), 1)
        assertEquals(listOf("B", "A"), second.map { it.title })
        assertEquals(listOf(8, 7), second.map { it.id })
    }

    @Test
    fun `exactly one item is active`() {
        val snapshots = listOf(
            SessionSnapshot("a", null, true, 0),
            SessionSnapshot("b", null, true, 0),
        )
        val items = sessionItems(snapshots, listOf(1, 2), 1)
        assertFalse(items[0].active)
        assertTrue(items[1].active)
    }

    @Test
    fun `no item is active when the index is out of range`() {
        val snapshots = listOf(SessionSnapshot("a", null, true, 0))
        val items = sessionItems(snapshots, listOf(1), -1)
        assertTrue(items.none { it.active })
    }

    @Test
    fun `the fallback title keeps the stable id`() {
        val items = sessionItems(
            listOf(SessionSnapshot(null, null, false, 0)),
            listOf(7),
            0,
        )
        assertEquals("Session 7", items.single().title)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mismatched id and snapshot counts are rejected`() {
        sessionItems(listOf(SessionSnapshot("a", null, true, 0)), emptyList(), 0)
    }

    @Test
    fun `contexts and unseen are projected per stable id`() {
        val snapshots = listOf(
            SessionSnapshot("a", null, true, 0),
            SessionSnapshot("b", null, true, 0),
        )
        val items = sessionItems(
            snapshots,
            listOf(7, 9),
            activeIndex = 0,
            contexts = mapOf(7 to "~/repos"),
            unseen = setOf(9),
        )
        assertEquals("~/repos", items[0].subtitle)
        assertFalse(items[0].unseenOutput)
        assertEquals(null, items[1].subtitle)
        assertTrue(items[1].unseenOutput)
    }
}
