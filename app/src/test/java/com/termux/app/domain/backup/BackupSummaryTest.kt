package com.termux.app.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class BackupSummaryTest {

    @Test
    fun `list parses backup and safety kinds`() {
        val raw = """{"root":"/r","backups":[
            {"name":"termux_backup_20260101_000000","created_at":"2026-01-01 00:00:00","size":10,"packages":2,"kind":"backup"},
            {"name":"safety_pre_restore_20260102_000000","created_at":"2026-01-02 00:00:00","size":5,"packages":0,"kind":"safety"}]}"""
        val list = BackupJson.list(raw)
        assertEquals("/r", list.root)
        assertEquals(listOf(BackupKind.BACKUP, BackupKind.SAFETY), list.entries.map { it.kind })
        assertEquals("2026-01-01 00:00:00", list.entries.first().createdAt)
        assertEquals(10L, list.entries.first().sizeBytes)
        assertEquals(2, list.entries.first().packages)
    }

    @Test
    fun `list falls back to the name prefix when kind is absent`() {
        val raw = """{"root":"/r","backups":[
            {"name":"termux_backup_20260101_000000"},
            {"name":"safety_pre_restore_20260102_000000"}]}"""
        val list = BackupJson.list(raw)
        assertEquals(listOf(BackupKind.BACKUP, BackupKind.SAFETY), list.entries.map { it.kind })
    }

    @Test
    fun `list tolerates an empty list and extra keys`() {
        assertEquals(emptyList<BackupEntry>(), BackupJson.list("""{"root":"/r","backups":[]}""").entries)
        val list = BackupJson.list(
            """{"root":"/r","tool_version":"1.1.0","backups":[
                {"name":"termux_backup_20260101_000000","kind":"backup","label":"x"}]}""",
        )
        assertEquals(1, list.entries.size)
    }

    @Test
    fun `list decodes escaped names`() {
        val raw = """{"root":"/r","backups":[{"name":"termux_backup_a\"b","kind":"backup"}]}"""
        assertEquals("""termux_backup_a"b""", BackupJson.list(raw).entries.single().name)
    }

    @Test
    fun `status parses booleans and numbers`() {
        val s = BackupJson.status(
            """{"tool_version":"1.1.0","root":"/r","writable":true,"storage_access":false,"backups":2,"compression":"gz","keep":5,"free_space":4096}""",
        )
        assertEquals("/r", s.root)
        assertTrue(s.writable)
        assertFalse(s.storageAccess)
        assertEquals(2, s.backups)
        assertEquals("gz", s.compression)
        assertEquals(5, s.keep)
        assertEquals(4096L, s.freeSpaceBytes)
    }

    @Test
    fun `created parses size and packages`() {
        val created = BackupJson.created(
            """{"name":"termux_backup_20260101_000000","root":"/r","size":10,"packages":2}""",
        )
        assertEquals(CreatedBackup("termux_backup_20260101_000000", "/r", 10L, 2), created)
    }

    @Test
    fun `restore parses a missing safety archive as null`() {
        val r = BackupJson.restored(
            """{"name":"n","root":"/r","safety":"","packages_installed":1,"packages_failed":0}""",
        )
        assertNull(r.safety)
    }

    @Test
    fun `restore keeps a made safety archive`() {
        val r = BackupJson.restored(
            """{"name":"n","root":"/r","safety":"safety_pre_restore_20260101_000000","packages_installed":1,"packages_failed":3}""",
        )
        assertEquals("safety_pre_restore_20260101_000000", r.safety)
        assertEquals(1, r.packagesInstalled)
        assertEquals(3, r.packagesFailed)
    }

    @Test
    fun `verify parses validity`() {
        assertEquals(
            VerifiedBackup("n", false),
            BackupJson.verified("""{"name":"n","root":"/r","valid":false}"""),
        )
    }

    @Test
    fun `delete parses the deleted name and root`() {
        assertEquals(
            DeletedBackup("n", "/r"),
            BackupJson.deleted("""{"deleted":"n","root":"/r"}"""),
        )
    }

    @Test
    fun `prune parses counts and limit`() {
        assertEquals(
            PrunedBackups(removed = 2, kept = 3, keep = 5),
            BackupJson.pruned("""{"root":"/r","removed":2,"kept":3,"keep":5}"""),
        )
    }

    @Test
    fun `missing fields use safe defaults`() {
        val list = BackupJson.list("""{"backups":[{}]}""")
        assertEquals("", list.root)
        assertEquals(BackupEntry("", BackupKind.BACKUP, "", 0L, 0), list.entries.single())
        assertEquals(BackupStatus("", false, false, 0, "", 0, 0L), BackupJson.status("{}"))
    }

    @Test
    fun `last json line skips prompts and human output`() {
        val transcript = "Restored n\r\n{\"deleted\":\"n\",\"root\":\"/r\"}\r\n"
        assertEquals("""{"deleted":"n","root":"/r"}""", BackupJson.lastJsonLine(transcript))
    }

    @Test
    fun `last json line ignores a trailing prompt`() {
        val transcript = "{\"deleted\":\"n\",\"root\":\"/r\"}\r\n$ "
        assertEquals("""{"deleted":"n","root":"/r"}""", BackupJson.lastJsonLine(transcript))
    }

    @Test
    fun `last json line without an object raises`() {
        try {
            BackupJson.lastJsonLine("Restored n\r\n$ ")
        } catch (expected: BackupJsonException) {
            return
        }
        throw AssertionError("expected BackupJsonException")
    }

    @Test(expected = BackupJsonException::class)
    fun `malformed json raises`() {
        BackupJson.list("not json")
    }

    @Test(expected = BackupJsonException::class)
    fun `an empty document raises`() {
        BackupJson.status("")
    }
}
