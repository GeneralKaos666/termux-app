package com.termux.app.api.file

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedIntentGateTest {

    @Test
    fun `the first resume handles the intent and later ones do not`() {
        val gate = ReceivedIntentGate()
        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertFalse(gate.begin())
    }

    @Test
    fun `a fresh gate is open`() {
        assertTrue(ReceivedIntentGate().begin())
    }
}
