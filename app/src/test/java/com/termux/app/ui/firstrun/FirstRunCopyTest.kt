package com.termux.app.ui.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunCopyTest {

    @Test
    fun `each stage has distinct lines`() {
        val perStage = BootstrapStage.values().map { logLines(it) }
        assertTrue(perStage.all { it.isNotEmpty() })
        // No two stages print the same log, so the panel visibly advances.
        assertEquals(perStage.size, perStage.map { it.joinToString("\n") }.distinct().size)
    }

    @Test
    fun `finishing includes ready line`() {
        assertTrue(logLines(BootstrapStage.FINISHING).last().contains("ready", ignoreCase = true))
    }

    @Test
    fun `null stage shows only prompt lines`() {
        assertEquals(0, logLines(null).size)
    }

    @Test
    fun `tip commands are unique and non-blank`() {
        val tips = tipCommands()
        assertTrue(tips.isNotEmpty())
        assertEquals(tips.size, tips.distinct().size)
        assertTrue(tips.none { it.isBlank() })
    }
}
