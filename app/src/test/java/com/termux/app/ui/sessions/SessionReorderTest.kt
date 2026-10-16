package com.termux.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure reorder maths behind drag-to-reorder in the sessions bar. */
class SessionReorderTest {

    // --- dropIndex -------------------------------------------------------

    @Test
    fun `empty bar keeps the drag at index zero`() {
        assertEquals(0, dropIndex(emptyList(), draggedCenterPx = 123f))
    }

    @Test
    fun `centre inside a tab selects that tab`() {
        val tabs = tabs(0 to 100, 100 to 250, 250 to 300)

        assertEquals(0, dropIndex(tabs, 50f))
        assertEquals(1, dropIndex(tabs, 175f))
        assertEquals(2, dropIndex(tabs, 275f))
    }

    @Test
    fun `centre left of the first tab clamps to the first index`() {
        val tabs = tabs(100 to 200, 200 to 300)

        assertEquals(0, dropIndex(tabs, -50f))
        assertEquals(0, dropIndex(tabs, 0f))
    }

    @Test
    fun `centre right of the last tab clamps to the last index`() {
        val tabs = tabs(0 to 100, 100 to 200)

        assertEquals(1, dropIndex(tabs, 200f))
        assertEquals(1, dropIndex(tabs, 9999f))
    }

    @Test
    fun `the boundary between two tabs belongs to the later tab`() {
        val tabs = tabs(0 to 100, 100 to 200)

        assertEquals(1, dropIndex(tabs, 100f))
    }

    @Test
    fun `non uniform tab widths map by their own bounds`() {
        val tabs = tabs(0 to 10, 10 to 200, 200 to 210)

        assertEquals(1, dropIndex(tabs, 11f))
        assertEquals(1, dropIndex(tabs, 199f))
        assertEquals(2, dropIndex(tabs, 205f))
    }

    // --- moveItemInPlace -------------------------------------------------

    @Test
    fun `moving forward shifts the crossed elements left`() {
        val list = mutableListOf("a", "b", "c", "d")

        assertTrue(moveItemInPlace(list, 0, 2))
        assertEquals(listOf("b", "c", "a", "d"), list)
    }

    @Test
    fun `moving backward shifts the crossed elements right`() {
        val list = mutableListOf("a", "b", "c", "d")

        assertTrue(moveItemInPlace(list, 3, 1))
        assertEquals(listOf("a", "d", "b", "c"), list)
    }

    @Test
    fun `moving to the same index changes nothing`() {
        val list = mutableListOf("a", "b", "c")

        assertFalse(moveItemInPlace(list, 1, 1))
        assertEquals(listOf("a", "b", "c"), list)
    }

    @Test
    fun `an out of range source changes nothing`() {
        val list = mutableListOf("a", "b", "c")

        assertFalse(moveItemInPlace(list, -1, 1))
        assertFalse(moveItemInPlace(list, 3, 1))
        assertEquals(listOf("a", "b", "c"), list)
    }

    @Test
    fun `an out of range target clamps to the ends`() {
        val forward = mutableListOf("a", "b", "c")
        assertTrue(moveItemInPlace(forward, 0, 99))
        assertEquals(listOf("b", "c", "a"), forward)

        val backward = mutableListOf("a", "b", "c")
        assertTrue(moveItemInPlace(backward, 2, -5))
        assertEquals(listOf("c", "a", "b"), backward)
    }

    @Test
    fun `an empty list is left untouched`() {
        val list = mutableListOf<String>()

        assertFalse(moveItemInPlace(list, 0, 1))
        assertTrue(list.isEmpty())
    }

    private fun tabs(vararg bounds: Pair<Int, Int>): List<TabBounds> =
        bounds.mapIndexed { index, (start, end) -> TabBounds(index, start, end) }
}
