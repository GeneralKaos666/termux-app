package com.termux.app.ui.snippets

import com.termux.app.data.snippets.SnippetStore
import com.termux.app.data.ui.UiPreferences
import com.termux.app.domain.snippets.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetsStateTest {

    private class FakePrefs : UiPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getBoolean(key: String, def: Boolean): Boolean = values[key] as? Boolean ?: def
        override fun setBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun getString(key: String, def: String?): String? = values[key] as? String ?: def
        override fun setString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val store = SnippetStore(FakePrefs())

    @Test
    fun `add rejects blank and trims`() {
        val state = SnippetsState(store)
        state.add("  ", "npm run build")
        state.add("build", "   ")
        assertTrue(state.items.isEmpty())

        state.add("  build  ", "  npm run build  ")
        assertEquals(1, state.items.size)
        assertEquals("build", state.items.single().label)
        assertEquals("npm run build", state.items.single().command)
    }

    @Test
    fun `move reorders and persists order`() {
        val state = SnippetsState(store)
        state.add("a", "a")
        state.add("b", "b")
        state.add("c", "c")
        state.move(0, 2)
        state.save()
        assertEquals(listOf("b", "c", "a"), store.load().map { it.label })
    }

    @Test
    fun `remove drops exactly one`() {
        val state = SnippetsState(store)
        state.add("a", "a")
        state.add("b", "b")
        val id = state.save().first { it.label == "a" }.id

        state.remove(id)

        assertEquals(listOf("b"), state.items.map { it.label })
    }

    @Test
    fun `update edits the row with that id`() {
        val state = SnippetsState(store)
        state.add("build", "npm run build")
        val id = state.save().single().id

        state.update(id, "compile", "make all")

        val row = state.items.single()
        assertEquals("compile", row.label)
        assertEquals("make all", row.command)
        assertEquals(id, row.id)
    }

    @Test
    fun `save returns the normalized rows and they reload`() {
        val state = SnippetsState(store)
        state.add("build", "npm run build")
        val saved = state.save()

        assertEquals(saved.map { it.label to it.command }, store.load().map { it.label to it.command })
    }

    @Test
    fun `two blank rows are independent`() {
        val state = SnippetsState(store)
        state.addBlank()
        state.addBlank()
        assertEquals(2, state.items.size)
        assertTrue(state.items[0].id != state.items[1].id)

        state.update(state.items[0].id, "build", "npm run build")

        assertEquals("build", state.items[0].label)
        assertEquals("", state.items[1].label)
    }

    @Test
    fun `removing one blank row keeps the other`() {
        val state = SnippetsState(store)
        state.addBlank()
        state.addBlank()

        state.remove(state.items[0].id)

        assertEquals(1, state.items.size)
        assertEquals("", state.items.single().label)
    }

    @Test
    fun `save renumbers draft rows and drops the draft ids`() {
        val state = SnippetsState(store)
        state.addBlank()
        state.add("build", "npm run build")

        val saved = state.save()

        assertEquals(listOf("snippet-1"), saved.map { it.id })
        assertEquals(saved, state.items)
    }

    @Test
    fun `two added rows are independent before saving`() {
        val state = SnippetsState(store)
        state.add("a", "a")
        state.add("b", "b")

        state.update(state.items[0].id, "first", "one")

        assertEquals("first", state.items[0].label)
        assertEquals("b", state.items[1].label)
    }

    @Test
    fun `moving an out of range index is a no op`() {
        val state = SnippetsState(store)
        state.add("a", "a")
        state.move(0, 5)
        state.move(-1, 0)
        assertEquals(listOf("a" to "a"), state.items.map { it.label to it.command })
    }
}
