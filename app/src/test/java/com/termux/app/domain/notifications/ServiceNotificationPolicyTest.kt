package com.termux.app.domain.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceNotificationPolicyTest {

    @Test
    fun `stops when nothing is alive`() {
        assertTrue(shouldStopService(false, 0, 0, false))
    }

    @Test
    fun `does not stop while a session runs`() {
        assertFalse(shouldStopService(false, 1, 0, false))
    }

    @Test
    fun `does not stop while a task runs`() {
        assertFalse(shouldStopService(false, 0, 1, false))
    }

    @Test
    fun `does not stop while the wake lock is held`() {
        assertFalse(shouldStopService(true, 0, 0, false))
    }

    @Test
    fun `does not stop while the float session runs`() {
        assertFalse(shouldStopService(false, 0, 0, true))
    }
}
