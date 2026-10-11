package com.termux.app.ui.keys

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests proving the shell's [KeySet.DEFAULT] still describes Termux's default extra keys.
 *
 * The grid is rendered by the shared `ExtraKeysView`, so [KeySet.DEFAULT] is a reference copy of the
 * default layout: if either side changes, these tests fail and force the two to be reconciled.
 */
class KeySetTest {

    private val expectedDefaultKeys = listOf(
        "ESC", "/", "-", "HOME", "UP", "END", "PGUP",
        "TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN",
    )

    @Test
    fun `default is the documented double row`() {
        assertEquals(2, KeySet.DEFAULT.size)
        assertEquals(7, KeySet.DEFAULT[0].size)
        assertEquals(7, KeySet.DEFAULT[1].size)
    }

    @Test
    fun `default keys match today's defaults exactly`() {
        assertEquals(expectedDefaultKeys, KeySet.DEFAULT.flatten().map { it.key })
    }

    @Test
    fun `only the minus key has a pipe popup`() {
        val popups = KeySet.DEFAULT.flatten()
            .filter { it.popup != null }
            .associate { it.key to it.popup }

        assertEquals(mapOf("-" to "|"), popups)
    }

    @Test
    fun `shared termux default is unchanged`() {
        // TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS is a compile-time constant, so this
        // compares the source text without loading the shared class.
        assertEquals(
            "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]",
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
        )
    }
}
