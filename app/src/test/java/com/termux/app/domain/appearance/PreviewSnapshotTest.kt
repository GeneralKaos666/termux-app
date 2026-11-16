package com.termux.app.domain.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSnapshotTest {

    @Test
    fun `strips control sequences and trailing spaces`() {
        val lines = buildPreviewLines("\u001b[31merror\u001b[0m: failed   \n\u001b]0;title\u0007ok")
        assertEquals(listOf("error: failed", "ok"), lines)
    }

    @Test
    fun `keeps last n lines`() {
        val transcript = (1..20).joinToString("\n") { "line $it" }
        val lines = buildPreviewLines(transcript, maxLines = 3)
        assertEquals(listOf("line 18", "line 19", "line 20"), lines)
    }

    @Test
    fun `null transcript returns empty list`() {
        assertEquals(emptyList<String>(), buildPreviewLines(null))
    }

    @Test
    fun `blank transcript returns empty list`() {
        assertEquals(emptyList<String>(), buildPreviewLines("   \n\n  "))
    }

    @Test
    fun `long lines are elided at max columns`() {
        val lines = buildPreviewLines("x".repeat(70), maxColumns = 10)
        assertEquals(listOf("xxxxxxxxx…"), lines)
        assertEquals(10, lines.single().length)
    }

    @Test
    fun `carriage returns become line breaks`() {
        assertEquals(listOf("a", "b"), buildPreviewLines("a\r\nb"))
    }

    @Test
    fun `an empty transcript line is preserved inside the window`() {
        assertEquals(listOf("a", "", "b"), buildPreviewLines("a\n\nb"))
    }
}
