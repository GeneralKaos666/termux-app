package com.termux.app.ui.keys

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests proving every second-page preset serialises into the `extra-keys-page2` property syntax
 * the shared `ExtraKeysView` engine parses, and that unknown preset ids fall back to the default
 * layout.
 *
 * The expected literals below pin the exact property text each preset produces; the default
 * layout's format is additionally pinned by [KeySetTest] against the shared constant.
 */
class ExtraKeysPage2PresetsTest {

    @Test
    fun `all presets have unique ids`() {
        val ids = ExtraKeysPage2Presets.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `default preset is the termux default second page layout`() {
        assertEquals(
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_PAGE2,
            ExtraKeysPage2Presets.jsonFor(ExtraKeysPage2Presets.PRESET_DEFAULT),
        )
        assertEquals(
            KeySetPresets.serializeExtraKeys(KeySet.DEFAULT_PAGE2),
            ExtraKeysPage2Presets.jsonFor(ExtraKeysPage2Presets.PRESET_DEFAULT),
        )
    }

    @Test
    fun `unknown preset ids and null fall back to the default layout`() {
        assertEquals(
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_PAGE2,
            ExtraKeysPage2Presets.jsonFor("does-not-exist"),
        )
        assertEquals(
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_PAGE2,
            ExtraKeysPage2Presets.jsonFor(null),
        )
    }

    @Test
    fun `function preset is the F-key layout`() {
        assertEquals(
            "[['F1','F2','F3','F4','F5','F6','F7','F8'], " +
                "['F9','F10','F11','F12','PGUP','PGDN','HOME','END'], " +
                "['ESC','TAB','CTRL','ALT','LEFT','DOWN','UP','RIGHT']]",
            ExtraKeysPage2Presets.jsonFor(ExtraKeysPage2Presets.PRESET_FUNCTION),
        )
    }

    @Test
    fun `every preset stays a populated rectangular grid`() {
        for (preset in ExtraKeysPage2Presets.ALL) {
            assertTrue("preset ${preset.id} has no rows", preset.rows.isNotEmpty())
            val columns = preset.rows.first().size
            assertTrue("preset ${preset.id} has an empty row", columns > 0)
            for (row in preset.rows) {
                assertEquals("preset ${preset.id} is not rectangular", columns, row.size)
            }
        }
    }
}
