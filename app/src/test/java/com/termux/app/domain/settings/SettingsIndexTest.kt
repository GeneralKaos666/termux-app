package com.termux.app.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the settings search index. */
class SettingsIndexTest {

    private fun entry(
        key: String,
        title: String,
        summary: String = "",
        fragment: String? = null,
    ) = SettingsEntry(key = key, title = title, summary = summary, fragment = fragment)

    private val entries = listOf(
        entry("display", "Display", "Terminal colours and text", "DisplayFragment"),
        entry("keyboard", "Keyboard", "Extra keys layout", "KeyboardFragment"),
        entry("debugging", "Debugging", "Log level and crash reports", "DebuggingFragment"),
    )

    @Test
    fun `filters on title and summary case insensitively`() {
        assertEquals(listOf("display"), filterSettings(entries, "COLOURS").map { it.key })
        assertEquals(listOf("keyboard"), filterSettings(entries, "Keyboard").map { it.key })
        assertEquals(listOf("debugging"), filterSettings(entries, "crash").map { it.key })
    }

    @Test
    fun `blank query returns full index`() {
        assertEquals(entries, filterSettings(entries, ""))
        assertEquals(entries, filterSettings(entries, "   "))
    }

    @Test
    fun `no match returns an empty list`() {
        assertEquals(emptyList<SettingsEntry>(), filterSettings(entries, "wallpaper"))
    }

    @Test
    fun `query is trimmed before matching`() {
        assertEquals(listOf("display"), filterSettings(entries, "  display  ").map { it.key })
    }

    @Test
    fun `result keeps the input order`() {
        assertEquals(
            listOf("display", "keyboard", "debugging"),
            filterSettings(entries, "e").map { it.key },
        )
    }
}
