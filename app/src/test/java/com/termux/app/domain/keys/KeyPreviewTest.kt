package com.termux.app.domain.keys

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyPreviewTest {

    private fun preset(vararg rows: List<String>): KeySetPresets.Preset =
        KeySetPresets.Preset(
            id = "test",
            rows = rows.map { row -> row.map { ExtraKey(it) } },
        )

    @Test
    fun `packs keys into requested columns`() {
        val rows = previewRows(preset(listOf("ESC", "/", "TAB")), maxColumns = 8)
        assertEquals(listOf(listOf("ESC", "/", "TAB")), rows.map { it.keys })
    }

    @Test
    fun `drops keys beyond max columns without throwing`() {
        val keys = (1..12).map { "K$it" }
        val rows = previewRows(preset(keys), maxColumns = 4)
        assertEquals(listOf(keys.take(4)), rows.map { it.keys })
    }

    @Test
    fun `preserves preset order`() {
        val rows = previewRows(
            preset(
                listOf("ESC", "/"),
                listOf("TAB", "CTRL", "ALT"),
            ),
        )
        assertEquals(listOf("ESC", "/"), rows[0].keys)
        assertEquals(listOf("TAB", "CTRL", "ALT"), rows[1].keys)
    }

    @Test
    fun `never shows a third row`() {
        val rows = previewRows(
            preset(
                listOf("a"),
                listOf("b"),
                listOf("c"),
            ),
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun `every preset fits the gallery`() {
        KeySetPresets.ALL.forEach { preset ->
            val rows = previewRows(preset)
            assertEquals(true, rows.size <= 2)
            assertEquals(true, rows.all { it.keys.size <= 8 })
            assertEquals(true, rows.all { it.keys.isNotEmpty() })
        }
    }
}
