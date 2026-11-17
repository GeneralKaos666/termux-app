package com.termux.app

import android.content.Intent
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TermuxActivityFiltersTest {

    @Test
    fun `internal filter carries only the in-process actions`() {
        val filter = internalActivityFilter()
        assertTrue(filter.hasAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH))
        assertTrue(filter.hasAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE))
        assertTrue(filter.hasAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS))
        assertFalse(filter.hasAction(Intent.ACTION_WALLPAPER_CHANGED))
    }

    @Test
    fun `system filter carries only the wallpaper change`() {
        val filter = systemActivityFilter()
        assertTrue(filter.hasAction(Intent.ACTION_WALLPAPER_CHANGED))
        assertFalse(filter.hasAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE))
    }
}
