package com.termux.app.ui.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar is a three-page pager (two key grids with independent layouts, plus the writing page) and
 * [ExtraKeysState.textInputVisible] is derived from [ExtraKeysState.page], so the Java-facing
 * contract in com.termux.app.TermuxActivity (get/setTextInputVisible, toolbar hide resetting to the
 * first keys page) keeps working unchanged.
 */
class ExtraKeysStateTest {

    @Test
    fun `defaults to the keys page with no text input`() {
        val state = ExtraKeysState()
        assertEquals(ExtraKeysState.PAGE_KEYS, state.page)
        assertFalse(state.textInputVisible)
    }

    @Test
    fun `enabling text input selects the writing page`() {
        val state = ExtraKeysState()
        state.textInputVisible = true
        assertEquals(ExtraKeysState.PAGE_WRITING, state.page)
        assertTrue(state.textInputVisible)
    }

    @Test
    fun `second grid keeps its own height alongside the first`() {
        val state = ExtraKeysState()
        state.gridHeightPx = 120
        state.grid2HeightPx = 80
        assertEquals(120, state.gridHeightPx)
        assertEquals(80, state.grid2HeightPx)
    }

    @Test
    fun `disabling text input returns to the keys page`() {
        val state = ExtraKeysState()
        state.textInputVisible = true
        state.textInputVisible = false
        assertEquals(ExtraKeysState.PAGE_KEYS, state.page)
        assertFalse(state.textInputVisible)
    }

    @Test
    fun `the second keys page reports no text input`() {
        val state = ExtraKeysState()
        state.page = ExtraKeysState.PAGE_KEYS_2
        assertFalse(state.textInputVisible)
    }

    @Test
    fun `out-of-range pages are clamped to the valid range`() {
        val state = ExtraKeysState()
        state.page = 99
        assertEquals(ExtraKeysState.PAGE_WRITING, state.page)
        state.page = -1
        assertEquals(ExtraKeysState.PAGE_KEYS, state.page)
    }
}
