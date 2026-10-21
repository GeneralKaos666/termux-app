package com.termux.app.ui.keys

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The bar animates its height per page: each key grid page uses its own configured grid height, the
 * writing page its own natural height, and the pager collapses to nothing (leaving only the chevron)
 * when the keys are hidden.
 */
class ExtraKeysPageHeightTest {

    @Test
    fun `collapsing hides the pager regardless of the current page`() {
        for (page in ExtraKeysState.PAGE_KEYS..ExtraKeysState.PAGE_WRITING) {
            assertEquals(
                0.dp,
                extraKeysPageHeight(page, keysVisible = false, gridHeight = 120.dp, grid2Height = 80.dp),
            )
        }
    }

    @Test
    fun `the first keys page uses the first grid height`() {
        assertEquals(
            120.dp,
            extraKeysPageHeight(ExtraKeysState.PAGE_KEYS, keysVisible = true, gridHeight = 120.dp, grid2Height = 80.dp),
        )
    }

    @Test
    fun `the second keys page uses the second grid height`() {
        assertEquals(
            80.dp,
            extraKeysPageHeight(ExtraKeysState.PAGE_KEYS_2, keysVisible = true, gridHeight = 120.dp, grid2Height = 80.dp),
        )
    }

    @Test
    fun `without a second grid height the second keys page follows the first`() {
        assertEquals(
            120.dp,
            extraKeysPageHeight(ExtraKeysState.PAGE_KEYS_2, keysVisible = true, gridHeight = 120.dp),
        )
    }

    @Test
    fun `the writing page uses its own natural height`() {
        assertEquals(
            WritingPageHeight,
            extraKeysPageHeight(ExtraKeysState.PAGE_WRITING, keysVisible = true, gridHeight = 120.dp, grid2Height = 80.dp),
        )
    }

    @Test
    fun `the writing page stays reachable without a key grid`() {
        assertNotEquals(
            0.dp,
            extraKeysPageHeight(ExtraKeysState.PAGE_WRITING, keysVisible = true, gridHeight = 0.dp),
        )
    }
}
