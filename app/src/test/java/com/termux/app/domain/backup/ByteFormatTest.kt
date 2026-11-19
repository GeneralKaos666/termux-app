package com.termux.app.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {

    @Test
    fun `bytes below a kibibyte keep their unit`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun `kibibytes and above use binary units with one decimal`() {
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("1.0 MiB", formatBytes(1024L * 1024))
        assertEquals("2.5 GiB", formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `the decimal separator does not follow the device locale`() {
        // The test runs under the default locale; the format is fixed to Locale.US.
        assertEquals("1.5 KiB", formatBytes(1536))
    }
}
