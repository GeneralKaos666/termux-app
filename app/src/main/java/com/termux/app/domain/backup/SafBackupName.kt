package com.termux.app.domain.backup

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Backup directory names that come from a SAF picker. The CLI only lists directories whose names
 * start with `termux_backup_` or `safety_pre_restore_`, so an imported or exported directory is
 * renamed into one of those shapes.
 */
object SafBackupName {

    private const val BACKUP_PREFIX = "termux_backup_"
    private const val SAFETY_PREFIX = "safety_pre_restore_"
    private const val FALLBACK = "backup"
    private const val MAX_LENGTH = 120

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

    private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    private val REPEATED_DASH = Regex("-{2,}")
    private val EDGE = Regex("^[.-]+|[.-]+$")

    /** Reduces [displayName] to `[A-Za-z0-9._-]`, collapsing separators and trimming edges. */
    fun sanitize(displayName: String): String {
        val replaced = displayName.replace(UNSAFE, "-").replace(REPEATED_DASH, "-")
        val trimmed = replaced.replace(EDGE, "")
        val bounded = trimmed.take(MAX_LENGTH).replace(EDGE, "")
        return bounded.ifEmpty { FALLBACK }
    }

    /**
     * Returns a directory name the CLI will list: the sanitised name when it already carries a
     * backup prefix, otherwise a `termux_backup_` name stamped from [manifestCreatedAt] or [now].
     */
    fun destination(displayName: String, manifestCreatedAt: String?, now: Instant): String {
        val clean = sanitize(displayName)
        if (clean.startsWith(BACKUP_PREFIX) || clean.startsWith(SAFETY_PREFIX)) return clean
        val stamp = manifestCreatedAt?.let { parse(it) } ?: now
        return BACKUP_PREFIX + STAMP.format(stamp)
    }

    /** Returns [base], or the first free `base-N` starting at 2. */
    fun collision(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var suffix = 2
        while ("$base-$suffix" in taken) suffix++
        return "$base-$suffix"
    }

    private fun parse(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}
