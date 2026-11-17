package com.termux.app.domain.appearance

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedReadTest {

    @Test
    fun `small input is read whole`() {
        assertEquals("<plist/>", readBounded(ByteArrayInputStream("<plist/>".toByteArray()), 64))
    }

    @Test
    fun `oversized input is refused`() {
        val big = ByteArray(100) { 'a'.code.toByte() }
        assertNull(readBounded(ByteArrayInputStream(big), 64))
    }

    @Test
    fun `no stream is refused`() {
        assertNull(readBounded(null, 64))
    }
}
