package com.termux.app.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.junit.Assert.assertThrows

/** Checksums and the manifest decide whether an imported directory is a real backup. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SafBackupManifestTest {

    @Test
    fun `sums parse and reject traversal`() {
        val line = "a".repeat(64) + "  home.tar.gz"
        assertEquals(listOf(SumEntry("home.tar.gz", "a".repeat(64))), SafBackupManifest.parseSums("$line\n"))
        assertThrows(InvalidBackupException::class.java) {
            SafBackupManifest.parseSums("${"a".repeat(64)}  ../evil.tar.gz")
        }
        assertThrows(InvalidBackupException::class.java) {
            SafBackupManifest.parseSums("${"a".repeat(64)}  a/b.tar.gz")
        }
        assertThrows(InvalidBackupException::class.java) {
            SafBackupManifest.parseSums(
                "${"a".repeat(64)}  x.tar.gz\n${"a".repeat(64)}  x.tar.gz",
            )
        }
    }

    @Test
    fun `sums reject a malformed line and an empty file`() {
        assertThrows(InvalidBackupException::class.java) { SafBackupManifest.parseSums("not a checksum  x.tar.gz") }
        assertThrows(InvalidBackupException::class.java) { SafBackupManifest.parseSums("") }
    }

    @Test
    fun `sums accept the file names the script writes`() {
        val text = listOf("home.tar.gz", "etc.tar.gz", "packages.txt").joinToString("\n") {
            "${"0".repeat(64)}  $it"
        }
        assertEquals(3, SafBackupManifest.parseSums(text).size)
    }

    @Test
    fun `manifest version guard reads only known keys`() {
        assertEquals(
            1,
            SafBackupManifest.formatVersion("""{"format_version":1,"created_at":"2026-01-01T10:15:30Z"}"""),
        )
        assertNull(SafBackupManifest.formatVersion(null))
        assertEquals(
            "2026-01-01T10:15:30Z",
            SafBackupManifest.createdAt("""{"created_at":"2026-01-01T10:15:30Z"}"""),
        )
        assertNull(SafBackupManifest.createdAt(null))
        assertNull(SafBackupManifest.createdAt("garbage"))
    }
}
