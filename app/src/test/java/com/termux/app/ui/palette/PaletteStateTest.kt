package com.termux.app.ui.palette

import com.termux.app.domain.palette.PaletteAction
import com.termux.app.domain.palette.PaletteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteStateTest {

    private fun entry(label: String) =
        PaletteEntry(id = label, label = label, hint = null, action = PaletteAction.OPEN_SETTINGS)

    private fun stateWith(vararg labels: String): PaletteState = PaletteState().apply {
        updateEntries(labels.map { entry(it) })
        open()
    }

    @Test
    fun `selection moves and clamps`() {
        val state = stateWith("Appearance", "Paste", "Settings")
        state.moveSelection(1)
        assertEquals("Paste", state.selected()?.label)
        state.moveSelection(10)
        assertEquals("Settings", state.selected()?.label)
        state.moveSelection(-10)
        assertEquals("Appearance", state.selected()?.label)
    }

    @Test
    fun `closing resets query and index`() {
        val state = stateWith("Appearance", "Paste")
        state.updateQuery("pa")
        state.moveSelection(1)
        state.close()
        assertFalse(state.visible)
        assertEquals("", state.query)
        assertEquals(0, state.selectedIndex)
    }

    @Test
    fun `filtering keeps selection in range`() {
        val state = stateWith("Appearance", "Paste", "Settings")
        state.moveSelection(2)
        state.updateQuery("set")
        assertEquals(1, state.filtered.size)
        assertEquals(0, state.selectedIndex)
        assertEquals("Settings", state.selected()?.label)
    }

    @Test
    fun `no match leaves nothing selected`() {
        val state = stateWith("Appearance")
        state.updateQuery("zzz")
        assertNull(state.selected())
        state.moveSelection(1)
        assertNull(state.selected())
    }

    @Test
    fun `opening shows every entry`() {
        val state = stateWith("Appearance", "Paste")
        assertTrue(state.visible)
        assertEquals(2, state.filtered.size)
    }
}
