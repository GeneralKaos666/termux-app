package com.termux.app.domain.palette

import com.termux.app.domain.sessions.SessionItem
import com.termux.app.domain.snippets.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteFilterTest {

    private fun entry(label: String, hint: String? = null) =
        PaletteEntry(id = label, label = label, hint = hint, action = PaletteAction.OPEN_SETTINGS)

    @Test
    fun `empty query returns input order`() {
        val entries = listOf(entry("Open settings"), entry("New session"), entry("Appearance"))
        assertEquals(entries, filterPalette(entries, ""))
    }

    @Test
    fun `prefix match ranks before subsequence`() {
        val entries = listOf(entry("Open settings"), entry("Set theme"))
        assertEquals("Set theme", filterPalette(entries, "set").first().label)
    }

    @Test
    fun `substring beats looser subsequence`() {
        val entries = listOf(entry("Reset everything"), entry("Set theme"))
        val out = filterPalette(entries, "set")
        assertEquals("Set theme", out.first().label)
        assertTrue(out.any { it.label == "Reset everything" })
    }

    @Test
    fun `non match is dropped`() {
        assertTrue(filterPalette(listOf(entry("Open settings")), "zzz").isEmpty())
    }

    @Test
    fun `query is case insensitive`() {
        assertEquals(1, filterPalette(listOf(entry("Open Settings")), "SETTINGS").size)
    }

    @Test
    fun `matches on hint as well as label`() {
        val entries = listOf(entry("Command", hint = "npm run build"))
        assertEquals(1, filterPalette(entries, "npm").size)
    }

    @Test
    fun `built entries cover app actions, sessions and snippets`() {
        val sessions = listOf(SessionItem(7, "shell", true, 0, true))
        val snippets = listOf(Snippet("snippet-1", "Build", "npm run build"))
        val entries = paletteEntries(
            sessions,
            snippets,
            PaletteLabels(
                newSession = "New session",
                newFloatSession = "New floating session",
                openAppearance = "Appearance",
                openSettings = "Settings",
                toggleKeys = "Toggle extra keys",
                paste = "Paste",
            ),
        )
        assertTrue(entries.any { it.action == PaletteAction.NEW_SESSION })
        assertTrue(entries.any { it.action == PaletteAction.JUMP_SESSION && it.payload == "7" })
        assertTrue(entries.any { it.action == PaletteAction.RUN_SNIPPET && it.payload == "npm run build" })
    }
}
