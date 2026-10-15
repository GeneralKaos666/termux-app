package com.termux.app.ui.appearance

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * Tests for the reload signal the terminal uses after the appearance screen applies a change: the
 * terminal must reload exactly when the stored revision differs from the one it last showed, which is
 * the case for a change applied while the terminal was not visible.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppearanceStylingTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    @Test
    fun `a fresh install has nothing to reload`() {
        assertEquals(0L, AppearanceStyling.revision(context()))
        assertFalse("nothing has been applied yet", AppearanceStyling.hasChanged(context(), 0L))
    }

    @Test
    fun `applying an appearance raises the revision and requests a reload`() {
        val applied = AppearanceStyling.revision(context())

        AppearanceStyling.bumpRevision(context())

        assertTrue("a change the terminal has not shown must reload", AppearanceStyling.hasChanged(context(), applied))
        assertEquals(applied + 1, AppearanceStyling.revision(context()))
    }

    @Test
    fun `a revision that was already applied does not request another reload`() {
        val applied = AppearanceStyling.bumpRevision(context())

        assertFalse(AppearanceStyling.hasChanged(context(), applied))
    }
}
