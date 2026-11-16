package com.termux.app.data.wakelock

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLockControllerTest {

    private class TestWakeLockController(
        onChange: () -> Unit,
    ) : WakeLockController(ContextWrapper(null), onChange, requestBatteryOptimizationExemption = {}) {
        override fun acquirePlatformLocks() {}
        override fun releasePlatformLocks() {}
    }

    @Test
    fun `acquire holds the lock and notifies once`() {
        var changes = 0
        val c = TestWakeLockController { changes++ }

        c.acquire()

        assertTrue(c.isHeld)
        assertEquals(1, changes)
    }

    @Test
    fun `acquiring twice notifies once`() {
        var changes = 0
        val c = TestWakeLockController { changes++ }

        c.acquire()
        c.acquire()

        assertTrue(c.isHeld)
        assertEquals(1, changes)
    }

    @Test
    fun `release drops the lock and notifies`() {
        var changes = 0
        val c = TestWakeLockController { changes++ }

        c.acquire()
        c.release(updateNotification = true)

        assertFalse(c.isHeld)
        assertEquals(2, changes)
    }

    @Test
    fun `releasing twice does not throw or notify again`() {
        var changes = 0
        val c = TestWakeLockController { changes++ }

        c.acquire()
        c.release(updateNotification = true)
        c.release(updateNotification = true)

        assertFalse(c.isHeld)
        assertEquals(2, changes)
    }

    @Test
    fun `release without a notification update does not notify`() {
        var changes = 0
        val c = TestWakeLockController { changes++ }

        c.acquire()
        c.release(updateNotification = false)

        assertFalse(c.isHeld)
        assertEquals(1, changes)
    }
}
