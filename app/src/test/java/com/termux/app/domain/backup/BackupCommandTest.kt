package com.termux.app.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCommandTest {

    @Test
    fun `backup argv carries flags and never shell-quotes`() {
        val argv = BackupCommand.Backup("pre-upgrade", "xz", 3, true, true, false)
            .argv("/p/lib/termux-backup/termux-backup", "/h/progress.jsonl")
        assertEquals(
            listOf(
                "/p/lib/termux-backup/termux-backup", "backup",
                "--label", "pre-upgrade", "--compression", "xz", "--keep", "3",
                "--exclude-secrets", "--no-config",
                "--json", "--progress-file", "/h/progress.jsonl",
            ),
            argv,
        )
    }

    @Test
    fun `restore argv always confirms and maps toggles`() {
        val argv = BackupCommand.Restore("termux_backup_20260101_000000", safety = false, packages = false)
            .argv("/p/t", "/h/p.jsonl")
        assertTrue("--yes" in argv)
        assertTrue("--no-safety" in argv)
        assertTrue("--no-packages" in argv)
    }

    @Test
    fun `delete and verify differ on yes`() {
        assertTrue(BackupCommand.Delete("x").argv("/p/t", "/h/p").contains("--yes"))
        assertFalse(BackupCommand.Verify("x").argv("/p/t", "/h/p").contains("--yes"))
    }

    @Test
    fun `label validation rejects separators`() {
        assertNull(BackupCommand.labelError("pre-upgrade"))
        assertNotNull(BackupCommand.labelError("bad/label"))
        assertNotNull(BackupCommand.labelError("bad label"))
        assertNotNull(BackupCommand.labelError(""))
    }

    @Test
    fun `backup argv omits options the user did not choose`() {
        val argv = BackupCommand.Backup(null, "gz", null, false, false, false)
            .argv("/p/t", "/h/p")
        assertEquals(
            listOf(
                "/p/t", "backup", "--compression", "gz",
                "--no-home", "--no-config",
                "--json", "--progress-file", "/h/p",
            ),
            argv,
        )
    }

    @Test
    fun `simple commands name their target and end with the progress tail`() {
        assertEquals(
            listOf("/p/t", "verify", "--name", "x", "--json", "--progress-file", "/h/p"),
            BackupCommand.Verify("x").argv("/p/t", "/h/p"),
        )
        assertEquals(
            listOf("/p/t", "delete", "--name", "x", "--yes", "--json", "--progress-file", "/h/p"),
            BackupCommand.Delete("x").argv("/p/t", "/h/p"),
        )
        assertEquals(
            listOf("/p/t", "prune", "--keep", "5", "--json", "--progress-file", "/h/p"),
            BackupCommand.Prune(5).argv("/p/t", "/h/p"),
        )
        assertEquals(
            listOf("/p/t", "list", "--json", "--progress-file", "/h/p"),
            BackupCommand.ListBackups.argv("/p/t", "/h/p"),
        )
        assertEquals(
            listOf("/p/t", "status", "--json", "--progress-file", "/h/p"),
            BackupCommand.Status.argv("/p/t", "/h/p"),
        )
    }
}
