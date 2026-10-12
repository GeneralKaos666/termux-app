package com.termux.app.ui.keys

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests proving every extra-keys preset serialises into the `extra-keys` property syntax
 * the shared `ExtraKeysView` engine parses, and that unknown preset ids fall back to the default
 * layout.
 *
 * The expected literals below pin the exact property text each preset produces; the default
 * layout's format is additionally pinned by [KeySetTest] against the shared constant.
 */
class KeySetPresetsTest {

    @Test
    fun `all presets have unique ids`() {
        val ids = KeySetPresets.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `default preset is the termux default layout`() {
        assertEquals(
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            KeySetPresets.jsonFor(KeySetPresets.PRESET_DEFAULT),
        )
    }

    @Test
    fun `compact preset is a single row with the essentials`() {
        assertEquals(
            "[['ESC','/','-','TAB','CTRL','ALT','UP','DOWN']]",
            KeySetPresets.jsonFor(KeySetPresets.PRESET_COMPACT),
        )
    }

    @Test
    fun `vim preset keeps escape and movement handy`() {
        assertEquals(
            "[['ESC','/',{key: ':', popup: '!'},'PGUP','UP','PGDN','END']," +
                " ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT',{key: '$', popup: '^'}]]",
            KeySetPresets.jsonFor(KeySetPresets.PRESET_VIM),
        )
    }

    @Test
    fun `emacs preset keeps control and alt handy`() {
        assertEquals(
            "[['ESC','CTRL','ALT','TAB','UP','PGUP','HOME']," +
                " ['LEFT','DOWN','RIGHT','END','PGDN','ENTER',{key: 'ESC', popup: 'CTRL'}]]",
            KeySetPresets.jsonFor(KeySetPresets.PRESET_EMACS),
        )
    }

    @Test
    fun `every preset has non-empty rows and non-blank keys`() {
        for (preset in KeySetPresets.ALL) {
            assertTrue("preset ${preset.id} must have rows", preset.rows.isNotEmpty())
            for (row in preset.rows) {
                assertTrue("preset ${preset.id} rows must not be empty", row.isNotEmpty())
                assertTrue(
                    "preset ${preset.id} keys must not be blank",
                    row.all { it.key.isNotBlank() },
                )
            }
        }
    }

    @Test
    fun `the serializer reproduces the shared default layout text`() {
        assertEquals(
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            KeySetPresets.serializeExtraKeys(KeySet.DEFAULT),
        )
    }

    @Test
    fun `unknown or null preset id falls back to the default layout`() {
        assertEquals(
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            KeySetPresets.jsonFor("not-a-preset"),
        )
        assertEquals(
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            KeySetPresets.jsonFor(null),
        )
    }
}
