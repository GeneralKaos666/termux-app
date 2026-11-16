package com.termux.app.data.snippets

import com.termux.app.data.ui.UiPreferenceKeys
import com.termux.app.data.ui.UiPreferences
import com.termux.app.domain.snippets.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetStoreTest {

    /** In-memory [UiPreferences] so the store is exercised without Android. */
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

    private val prefs = FakePrefs()
    private val store = SnippetStore(prefs)

    @Test
    fun `empty store loads nothing`() {
        assertEquals(emptyList<Snippet>(), store.load())
    }

    @Test
    fun `saved snippets load back`() {
        store.save(listOf(Snippet("", "build", "npm run build")))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("build", loaded.single().label)
        assertEquals("npm run build", loaded.single().command)
    }

    @Test
    fun `store caps at 20 and drops blank labels`() {
        val input = (1..25).map { Snippet("", "s$it", "cmd $it") } + Snippet("", "  ", "orphan")
        val saved = store.save(input)
        assertEquals(20, saved.size)
        assertTrue(saved.none { it.label.isBlank() })
    }

    @Test
    fun `store survives a corrupt raw value`() {
        prefs.setString(UiPreferenceKeys.SNIPPETS, "corrupt-without-tab\u0000")
        assertEquals(emptyList<Snippet>(), store.load())
        // Saving after a corrupt read rewrites the normalized value rather than keeping the junk.
        store.save(emptyList())
        assertEquals("", prefs.getString(UiPreferenceKeys.SNIPPETS, null))
    }

    @Test
    fun `ids are assigned on save and kept for unchanged rows`() {
        val saved = store.save(listOf(Snippet("keep-me", "build", "npm run build"), Snippet("", "ls", "ls")))
        assertEquals("keep-me", saved[0].id)
        assertEquals("snippet-1", saved[1].id)
        // Re-saving the returned list must not renumber the rows the user already has.
        assertEquals(listOf("keep-me", "snippet-1"), store.save(saved).map { it.id })
    }

    @Test
    fun `blank commands are dropped`() {
        assertEquals(emptyList<Snippet>(), store.save(listOf(Snippet("", "build", "   "))))
    }
}
