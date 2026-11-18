package com.termux.app.domain.backup

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/** Names that arrive from a SAF picker must still be listable by the CLI. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SafBackupNameTest {

    @Test
    fun `sanitize keeps safe names and replaces the rest`() {
        assertEquals("termux_backup_20260101_000000", SafBackupName.sanitize("termux_backup_20260101_000000"))
        assertEquals("my-backup", SafBackupName.sanitize("my backup"))
        assertEquals("backup-2", SafBackupName.sanitize("../backup (2)"))
        assertEquals("backup", SafBackupName.sanitize("///"))
        assertEquals("backup", SafBackupName.sanitize(" .. "))
    }

    @Test
    fun `destination derives a listable name`() {
        val now = Instant.parse("2026-11-17T10:00:00Z")
        assertEquals(
            "termux_backup_20260101_101530",
            SafBackupName.destination("Backups", "2026-01-01T10:15:30Z", now),
        )
        assertEquals("termux_backup_20261117_100000", SafBackupName.destination("Backups", null, now))
        assertEquals(
            "safety_pre_restore_20260101_000000",
            SafBackupName.destination("safety_pre_restore_20260101_000000", null, now),
        )
        assertEquals(
            "termux_backup_20260101_101530",
            SafBackupName.destination("termux_backup_20260101_101530", null, now),
        )
    }

    @Test
    fun `destination falls back to now for an unparseable timestamp`() {
        val now = Instant.parse("2026-11-17T10:00:00Z")
        assertEquals(
            "termux_backup_20261117_100000",
            SafBackupName.destination("Backups", "not a timestamp", now),
        )
    }

    @Test
    fun `collision appends a numeric suffix`() {
        assertEquals("termux_backup_x-2", SafBackupName.collision("termux_backup_x", setOf("termux_backup_x")))
        assertEquals(
            "termux_backup_x-3",
            SafBackupName.collision("termux_backup_x", setOf("termux_backup_x", "termux_backup_x-2")),
        )
        assertEquals("termux_backup_x", SafBackupName.collision("termux_backup_x", emptySet()))
    }
}
