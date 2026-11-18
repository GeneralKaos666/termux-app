package com.termux.app.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * Exercises the SAF export/import copy against an in-memory document tree, so hashing, collision
 * naming, cancellation and cleanup are all verified without a provider.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SafBackupTransferTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = temp.newFolder("backups")
    }

    @Test
    fun `export copies the summed set and verifies each hash`() {
        val tree = FakeTree(sizes = sizes)
        val dir = backupDirWith(sums = mapOf("home.tar.gz" to sha("abc")))

        val result = SafBackupTransfer({}, { false }).export(dir, tree)

        assertEquals(TransferResult.Success(dir.name), result)
        assertEquals(
            setOf("SHA256SUMS", "manifest.json", "packages.txt", "home.tar.gz"),
            tree.namesIn(dir.name),
        )
    }

    @Test
    fun `export hash mismatch deletes the created directory`() {
        val tree = FakeTree(sizes = sizes)
        val dir = backupDirWith(sums = mapOf("home.tar.gz" to "0".repeat(64)))

        val result = SafBackupTransfer({}, { false }).export(dir, tree)

        assertTrue(result is TransferResult.Failed)
        assertTrue(tree.children(null).none { it.name == dir.name })
    }

    @Test
    fun `existing destination refuses without touching it`() {
        val tree = FakeTree(existing = setOf("termux_backup_x"), sizes = sizes)

        val result = SafBackupTransfer({}, { false }).export(backupDirNamed("termux_backup_x"), tree)

        assertTrue(result is TransferResult.Failed)
        assertTrue(tree.namesIn("termux_backup_x").isEmpty())
    }

    @Test
    fun `import verifies and is not listed on mismatch`() {
        val tree = sourceTree(payload = "tampered", sumsFor = "abc")

        val result = SafBackupTransfer({}, { false }).importInto(tree, root, emptySet())

        assertTrue(result is TransferResult.Failed)
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("termux_backup_") })
    }

    @Test
    fun `import succeeds and picks a collision suffix`() {
        File(root, "termux_backup_x").mkdirs()

        val result = SafBackupTransfer({}, { false })
            .importInto(sourceTree(payload = "abc", sumsFor = "abc"), root, setOf("termux_backup_x"))

        assertEquals(TransferResult.Success("termux_backup_x-2"), result)
        assertEquals("abc", File(root, "termux_backup_x-2/home.tar.gz").readText())
    }

    @Test
    fun `unknown provider size keeps the bar indeterminate`() {
        val seen = mutableListOf<CopyProgress>()

        SafBackupTransfer({ seen += it }, { false })
            .export(backupDirWith(sums = mapOf("home.tar.gz" to sha("abc")), nullSize = true), FakeTree(sizes = sizes))

        assertTrue(seen.any { it.indeterminate })
    }

    @Test
    fun `cancellation deletes partial output`() {
        val tree = FakeTree(sizes = sizes)

        val result = SafBackupTransfer({}, { true })
            .export(backupDirWith(sums = mapOf("home.tar.gz" to sha("abc"))), tree)

        assertEquals(TransferResult.Cancelled, result)
        assertTrue(tree.children(null).isEmpty())
    }

    @Test
    fun `import rejects a manifest with a future format version`() {
        val tree = FakeTree(sizes = sizes, name = "termux_backup_future")
        tree.writeRootFile("manifest.json", """{"format_version":2}""".toByteArray())
        tree.writeRootFile("SHA256SUMS", "${sha("abc")}  home.tar.gz".toByteArray())

        val result = SafBackupTransfer({}, { false }).importInto(tree, root, emptySet())

        assertTrue(result is TransferResult.Failed)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `import rejects a directory without checksums`() {
        val tree = FakeTree(sizes = sizes, name = "termux_backup_empty")

        val result = SafBackupTransfer({}, { false }).importInto(tree, root, emptySet())

        assertTrue(result is TransferResult.Failed)
    }

    @Test
    fun `export refuses a missing summed file`() {
        val dir = temp.newFolder("termux_backup_missing")
        File(dir, "manifest.json").writeText("""{"format_version":1}""")
        writeSums(dir, mapOf("home.tar.gz" to sha("abc")))
        val tree = FakeTree(sizes = sizes)

        val result = SafBackupTransfer({}, { false }).export(dir, tree)

        assertTrue(result is TransferResult.Failed)
        assertTrue(tree.children(null).isEmpty())
    }

    @Test
    fun `import refuses a missing summed file`() {
        val tree = FakeTree(sizes = sizes, name = "termux_backup_x")
        tree.writeRootFile("SHA256SUMS", "${sha("abc")}  home.tar.gz".toByteArray())

        val result = SafBackupTransfer({}, { false }).importInto(tree, root, emptySet())

        assertTrue(result is TransferResult.Failed)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `import refuses a provider name that escapes the directory`() {
        val tree = FakeTree(sizes = sizes, name = "termux_backup_x")
        tree.writeRootFile("home.tar.gz", "abc".toByteArray())
        tree.writeRootFile("SHA256SUMS", "${sha("abc")}  home.tar.gz".toByteArray())
        tree.writeRootFile("../evil", "boom".toByteArray())

        val result = SafBackupTransfer({}, { false }).importInto(tree, root, emptySet())

        assertTrue(result is TransferResult.Failed)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertFalse(File(root.parentFile, "evil").exists())
    }

    @Test
    fun `export reports the name the provider assigned`() {
        val tree = FakeTree(sizes = sizes)
        tree.renameOnCreate = "renamed_backup"
        val dir = backupDirWith(sums = mapOf("home.tar.gz" to sha("abc")))

        val result = SafBackupTransfer({}, { false }).export(dir, tree)

        assertEquals(TransferResult.Success("renamed_backup"), result)
    }

    @Test
    fun `import refuses when the destination has too little space`() {
        val transfer = SafBackupTransfer({}, { false }, freeSpace = { 1024L })

        val result = transfer.importInto(sourceTree(payload = "abc", sumsFor = "abc"), root, emptySet())

        assertTrue(result is TransferResult.Failed)
        assertTrue((result as TransferResult.Failed).message.contains("free space"))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `export survives a provider that throws`() {
        val tree = FakeTree(sizes = sizes)
        tree.failOnWrite = true
        val dir = backupDirWith(sums = mapOf("home.tar.gz" to sha("abc")))

        val result = SafBackupTransfer({}, { false }).export(dir, tree)

        assertTrue(result is TransferResult.Failed)
        assertTrue(tree.children(null).isEmpty())
    }

    private fun backupDirWith(sums: Map<String, String>, nullSize: Boolean = false): File {
        val dir = temp.newFolder("termux_backup_20260101_000000")
        File(dir, "manifest.json").writeText("""{"format_version":1,"created_at":"2026-01-01T10:15:30Z"}""")
        File(dir, "packages.txt").writeText("bash\n")
        File(dir, "home.tar.gz").writeText("abc")
        writeSums(dir, sums)
        if (nullSize) sizes.put("home.tar.gz", null)
        return dir
    }

    private fun backupDirNamed(name: String): File {
        val dir = File(root.parentFile, name)
        dir.mkdirs()
        File(dir, "home.tar.gz").writeText("abc")
        writeSums(dir, mapOf("home.tar.gz" to sha("abc")))
        return dir
    }

    private fun writeSums(dir: File, sums: Map<String, String>) {
        File(dir, "SHA256SUMS").writeText(sums.entries.joinToString("\n") { "${it.value}  ${it.key}" } + "\n")
    }

    private fun sourceTree(payload: String, sumsFor: String): FakeTree {
        val tree = FakeTree(sizes = sizes, name = "termux_backup_x")
        tree.writeRootFile("manifest.json", """{"format_version":1,"created_at":"2026-01-01T10:15:30Z"}""".toByteArray())
        tree.writeRootFile("home.tar.gz", payload.toByteArray())
        tree.writeRootFile("SHA256SUMS", "${sha(sumsFor)}  home.tar.gz".toByteArray())
        return tree
    }

    private fun sha(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private val sizes = mutableMapOf<String, Long?>()

    /** In-memory tree: one level of directories, each holding named files. */
    private class FakeTree(
        private val existing: Set<String> = emptySet(),
        private val sizes: Map<String, Long?> = emptyMap(),
        private val name: String = "Backups",
    ) : SafDocumentTree {

        var renameOnCreate: String? = null
        var failOnWrite: Boolean = false

        private class Node(val id: String, val name: String, val size: Long?, val parent: String?) {
            val bytes = ByteArrayOutputStream()
            var isDir = false
        }

        private val nodes = linkedMapOf<String, Node>()
        private var counter = 0

        init {
            existing.forEach { name ->
                nodes["dir-$name"] = Node("dir-$name", name, null, null).also { it.isDir = true }
            }
        }

        override fun displayName(): String = name

        override fun children(parent: SafDocumentTree.Document?): List<SafDocumentTree.Document> =
            nodes.values
                .filter { it.parent == parent?.id }
                .map { SafDocumentTree.Document(it.id, it.name, it.size) }

        override fun createDirectory(name: String): SafDocumentTree.Document {
            val assigned = renameOnCreate ?: name
            val node = Node("dir-$assigned", assigned, null, null)
            node.isDir = true
            nodes[node.id] = node
            return SafDocumentTree.Document(node.id, node.name, node.size)
        }

        override fun createFile(parent: SafDocumentTree.Document, name: String): SafDocumentTree.Document {
            val node = Node("file-${counter++}-$name", name, sizes[name], parent.id)
            nodes[node.id] = node
            return SafDocumentTree.Document(node.id, node.name, node.size)
        }

        override fun openInput(document: SafDocumentTree.Document): InputStream =
            ByteArrayInputStream(nodes.getValue(document.id).bytes.toByteArray())

        override fun openOutput(document: SafDocumentTree.Document): OutputStream {
            if (failOnWrite) throw IllegalStateException("provider refused the write")
            return nodes.getValue(document.id).bytes
        }

        override fun delete(document: SafDocumentTree.Document): Boolean {
            nodes.remove(document.id)
            return true
        }

        fun writeRootFile(name: String, bytes: ByteArray) {
            val node = Node("file-${counter++}-$name", name, bytes.size.toLong(), null)
            node.bytes.write(bytes)
            nodes[node.id] = node
        }

        fun writeFile(dir: SafDocumentTree.Document, name: String, bytes: ByteArray) {
            val node = Node("file-${counter++}-$name", name, bytes.size.toLong(), dir.id)
            node.bytes.write(bytes)
            nodes[node.id] = node
        }

        fun namesIn(dirName: String): Set<String> {
            val dir = nodes.values.firstOrNull { it.name == dirName && it.isDir } ?: return emptySet()
            return nodes.values.filter { it.parent == dir.id }.map { it.name }.toSet()
        }
    }
}
