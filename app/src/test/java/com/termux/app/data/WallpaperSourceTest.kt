package com.termux.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WallpaperSourceTest {

    @Test
    fun `running out of memory yields no bitmap`() {
        assertNull(containImageFailure<Unit>("background") { throw OutOfMemoryError("test") })
    }

    @Test
    fun `a decode failure yields no bitmap`() {
        assertNull(containImageFailure<Unit>("background") { throw IllegalStateException("test") })
    }

    @Test
    fun `a successful decode is returned`() {
        assertEquals("bitmap", containImageFailure("background") { "bitmap" })
    }
}
