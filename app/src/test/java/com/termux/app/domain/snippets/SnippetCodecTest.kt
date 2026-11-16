package com.termux.app.domain.snippets

import org.junit.Assert.assertEquals
import org.junit.Test

class SnippetCodecTest {

    @Test
    fun `blank lines are skipped`() {
        val snippets = parseSnippets("\n\nbuild\tnpm run build\n\n")
        assertEquals(listOf("build"), snippets.map { it.label })
    }

    @Test
    fun `tabs inside command are preserved`() {
        val snippets = parseSnippets("build\tnpm run build -- --watch")
        assertEquals("npm run build -- --watch", snippets.single().command)
    }

    @Test
    fun `label and command are trimmed`() {
        val snippets = parseSnippets("  build  \t  make all  ")
        assertEquals("build", snippets.single().label)
        assertEquals("make all", snippets.single().command)
    }

    @Test
    fun `a row without a command is dropped`() {
        assertEquals(emptyList<Snippet>(), parseSnippets("no tab here\n"))
    }

    @Test
    fun `a row with a blank label is dropped`() {
        assertEquals(emptyList<Snippet>(), parseSnippets("\tnpm run build\n"))
    }

    @Test
    fun `null input is empty`() {
        assertEquals(emptyList<Snippet>(), parseSnippets(null))
    }

    @Test
    fun `ids are assigned in order`() {
        val snippets = parseSnippets("a\ta\nb\tb")
        assertEquals(listOf("snippet-1", "snippet-2"), snippets.map { it.id })
    }

    @Test
    fun `serialize round trips through parse`() {
        val snippets = parseSnippets("build\tnpm run build\nls\tls -la")
        assertEquals(snippets.map { it.label to it.command }, parseSnippets(serializeSnippets(snippets)).map { it.label to it.command })
    }
}
