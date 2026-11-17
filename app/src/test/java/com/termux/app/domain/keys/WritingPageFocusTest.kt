package com.termux.app.domain.keys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingPageFocusTest {

    @Test
    fun `leaving the writing page hands focus back`() {
        assertTrue(shouldHandBackFocus(previousWriting = true, writing = false))
    }

    @Test
    fun `staying or arriving does not`() {
        assertFalse(shouldHandBackFocus(previousWriting = true, writing = true))
        assertFalse(shouldHandBackFocus(previousWriting = false, writing = true))
        assertFalse(shouldHandBackFocus(previousWriting = false, writing = false))
    }
}
