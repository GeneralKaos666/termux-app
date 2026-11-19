package com.termux.app.domain.backup

import java.util.Locale

/**
 * Formats a byte count with binary units. The decimal separator is fixed to `.` so the same backup
 * size reads the same on every device, and the monospace column in the list does not move.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
