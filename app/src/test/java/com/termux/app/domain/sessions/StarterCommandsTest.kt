package com.termux.app.domain.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterCommandsTest {

    @Test
    fun `chips include package upgrade and listing after bootstrap`() {
        val chips = starterChips(isFirstSession = true, hasBootstrap = true)
        assertTrue(chips.any { it.command == "pkg upgrade" })
        assertTrue(chips.isNotEmpty() && chips.size <= 4)
    }

    @Test
    fun `no chips before bootstrap`() {
        assertTrue(starterChips(isFirstSession = true, hasBootstrap = false).isEmpty())
    }

    @Test
    fun `no chips after the first session exists`() {
        assertTrue(starterChips(isFirstSession = false, hasBootstrap = true).isEmpty())
    }

    @Test
    fun `commands are unique and non-blank`() {
        val chips = starterChips(isFirstSession = true, hasBootstrap = true)
        assertEquals(chips.size, chips.map { it.command }.distinct().size)
        assertTrue(chips.none { it.command.isBlank() })
    }
}
