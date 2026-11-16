package com.termux.app.domain.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionContextTest {

    @Test
    fun `home is tilde`() {
        assertEquals("~", cwdLabel("/home/u", "/home/u"))
        assertEquals("~", cwdLabel("/home/u/", "/home/u"))
    }

    @Test
    fun `descendant keeps the relative tail`() {
        assertEquals("~/repos/app", cwdLabel("/home/u/repos/app", "/home/u"))
    }

    @Test
    fun `outside home is basename elided on a word boundary`() {
        assertEquals(
            "…/a-very-long-directory",
            cwdLabel("/sdcard/a-very-long-directory-name-that-overflows", "/home/u"),
        )
    }

    @Test
    fun `short outside path is shown as is`() {
        assertEquals("…/sdcard", cwdLabel("/sdcard", "/home/u"))
    }

    @Test
    fun `null and blank are absent`() {
        assertNull(cwdLabel(null, "/home/u"))
        assertNull(cwdLabel("  ", "/home/u"))
    }

    @Test
    fun `session item defaults carry no context and no unseen output`() {
        val item = SessionItem(id = 1, title = "x", running = true, exitCode = 0, active = true)
        assertNull(item.subtitle)
        assertEquals(false, item.unseenOutput)
    }

    @Test
    fun `subtitle joins with a middle dot and skips nulls`() {
        assertEquals("~/repos · npm", tabSubtitle("/home/u/repos", "/home/u", "npm"))
        assertEquals("~/repos", tabSubtitle("/home/u/repos", "/home/u", null))
        assertEquals("npm", tabSubtitle(null, "/home/u", "npm"))
        assertNull(tabSubtitle(null, "/home/u", null))
    }
}
