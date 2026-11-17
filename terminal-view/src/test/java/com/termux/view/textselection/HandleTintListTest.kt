package com.termux.view.textselection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A zero tint must clear a previous tint, not keep it. */
class HandleTintListTest {

    @Test
    fun `zero means clear`() {
        assertNull(HandleTintList.tintOrNull(0))
    }

    @Test
    fun `an opaque colour is kept`() {
        assertEquals(0xFF00FF00.toInt(), HandleTintList.tintOrNull(0xFF00FF00.toInt()))
    }
}
