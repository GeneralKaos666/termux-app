package com.termux.app.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkActionTest {

    @Test
    fun `http gets open copy share`() {
        assertEquals(
            listOf(LinkAction.OPEN, LinkAction.COPY, LinkAction.SHARE),
            availableLinkActions("http://example.com/a"),
        )
    }

    @Test
    fun `https gets open copy share`() {
        assertEquals(
            listOf(LinkAction.OPEN, LinkAction.COPY, LinkAction.SHARE),
            availableLinkActions("HTTPS://example.com"),
        )
    }

    @Test
    fun `mailto gets open and copy only`() {
        assertEquals(
            listOf(LinkAction.OPEN, LinkAction.COPY),
            availableLinkActions("mailto:user@example.com"),
        )
    }

    @Test
    fun `blank url gets no actions`() {
        assertEquals(emptyList<LinkAction>(), availableLinkActions(""))
        assertEquals(emptyList<LinkAction>(), availableLinkActions("   "))
    }

    @Test
    fun `unknown scheme still opens and copies`() {
        assertEquals(
            listOf(LinkAction.OPEN, LinkAction.COPY),
            availableLinkActions("ssh://host"),
        )
    }
}
