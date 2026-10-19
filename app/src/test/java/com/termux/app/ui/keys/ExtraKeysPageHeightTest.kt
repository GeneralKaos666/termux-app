package com.termux.app.ui.keys

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The bar animates its height per page: the key grid page uses the configured grid height, the
 * quick-actions and writing pages their own natural heights, and the pager collapses to nothing
 * (leaving only the strip) when the keys are hidden.
 */
class ExtraKeysPageHeightTest {

    @Test
    fun `collapsing hides the pager regardless of the current page`() {
        for (page in ExtraKeysState.PAGE_KEYS..ExtraKeysState.PAGE_WRITING) {
            assertEquals(0.dp, extraKeysPageHeight(page, keysVisible = false, gridHeight = 120.dp))
        }
    }

    @Test
    fun `the keys page uses the configured grid height`() {
        assertEquals(
            120.dp,
            extraKeysPageHeight(ExtraKeysState.PAGE_KEYS, keysVisible = true, gridHeight = 120.dp),
        )
    }

    @Test
    fun `the quick actions and writing pages use their own natural heights`() {
        assertEquals(
            QuickActionsPageHeight,
            extraKeysPageHeight(ExtraKeysState.PAGE_ACTIONS, keysVisible = true, gridHeight = 120.dp),
        )
        assertEquals(
            WritingPageHeight,
            extraKeysPageHeight(ExtraKeysState.PAGE_WRITING, keysVisible = true, gridHeight = 120.dp),
        )
    }

    @Test
    fun `the quick actions and writing pages stay reachable without a key grid`() {
        assertNotEquals(
            0.dp,
            extraKeysPageHeight(ExtraKeysState.PAGE_ACTIONS, keysVisible = true, gridHeight = 0.dp),
        )
        assertNotEquals(
            0.dp,
            extraKeysPageHeight(ExtraKeysState.PAGE_WRITING, keysVisible = true, gridHeight = 0.dp),
        )
    }
}
