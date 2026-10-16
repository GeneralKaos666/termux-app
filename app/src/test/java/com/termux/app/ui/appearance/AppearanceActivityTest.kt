package com.termux.app.ui.appearance

import android.os.Looper
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode

/**
 * Robolectric test that the appearance screen opens and renders. The staged-vs-applied behaviour is
 * covered by [AppearanceControllerTest]; here only the Compose host has to come up without throwing.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppearanceActivityTest {

    @Test
    fun `activity opens and renders its content`() {
        val controller = Robolectric.buildActivity(AppearanceActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        val activity = controller.get()

        assertFalse("activity should not be finishing after setup", activity.isFinishing)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertNotNull("activity has no content view", content)
        assertTrue("activity rendered no child view", content.childCount >= 1)
        assertTrue(
            "the appearance screen's Compose host was not installed",
            content.getChildAt(0) is ComposeView,
        )
    }
}
