package com.termux.app.data.backup

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The coordinator is the one place that decides whether a transfer may start. */
class BackupTransferCoordinatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var tree: SafDocumentTree

    @org.junit.Before
    fun setUp() {
        root = temp.newFolder("backups")
        tree = fakeTree("termux_backup_x")
    }

    @Test
    fun `one transfer at a time`() {
        val transfer = BlockingTransfer()
        val coordinator = BackupTransferCoordinator.forTest(transfer, busy = { false })

        coordinator.startImport(tree, root)
        assertTrue(transfer.started.await(5, TimeUnit.SECONDS))
        assertEquals("import", coordinator.state.value.operation)
        assertTrue(coordinator.state.value.running)

        coordinator.startExport(root, tree)

        assertEquals("import", coordinator.state.value.operation)
        assertTrue(coordinator.state.value.result is TransferResult.Failed)

        transfer.release.countDown()
        awaitIdle(coordinator)
        assertFalse(coordinator.state.value.running)
    }

    @Test
    fun `refuses while a script run holds the lock`() {
        val coordinator = BackupTransferCoordinator.forTest(FixedTransfer(TransferResult.Success("x")), busy = { true })

        coordinator.startImport(tree, root)

        assertNull(coordinator.state.value.operation)
        assertFalse(coordinator.state.value.running)
        assertTrue(coordinator.state.value.result is TransferResult.Failed)
    }

    @Test
    fun `stale import temp directories are swept`() {
        File(root, ".tmp-import-123").mkdirs()
        File(root, "termux_backup_keep").mkdirs()

        BackupTransferCoordinator.forTest(FixedTransfer(TransferResult.Success("x")), busy = { false })
            .sweepStaleImports(root)

        assertFalse(File(root, ".tmp-import-123").exists())
        assertTrue(File(root, "termux_backup_keep").exists())
    }

    @Test
    fun `a finished transfer reports its result`() {
        val coordinator = BackupTransferCoordinator.forTest(
            FixedTransfer(TransferResult.Success("termux_backup_x")),
            busy = { false },
        )

        coordinator.startExport(root, tree)
        awaitIdle(coordinator)

        assertEquals(TransferResult.Success("termux_backup_x"), coordinator.state.value.result)
        assertNull(coordinator.state.value.operation)
    }

    private fun awaitIdle(coordinator: BackupTransferCoordinator) {
        val deadline = System.currentTimeMillis() + 5_000
        while (coordinator.state.value.running && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertFalse("transfer did not finish", coordinator.state.value.running)
    }

    private fun fakeTree(name: String): SafDocumentTree = object : SafDocumentTree {
        override fun displayName(): String = name
        override fun children(parent: SafDocumentTree.Document?): List<SafDocumentTree.Document> = emptyList()
        override fun createDirectory(name: String) = SafDocumentTree.Document("dir-$name", name, null)
        override fun createFile(parent: SafDocumentTree.Document, name: String) =
            SafDocumentTree.Document("file-$name", name, 0)
        override fun openInput(document: SafDocumentTree.Document) = java.io.ByteArrayInputStream(ByteArray(0))
        override fun openOutput(document: SafDocumentTree.Document) = java.io.ByteArrayOutputStream()
        override fun delete(document: SafDocumentTree.Document) = true
    }

    private class FixedTransfer(private val result: TransferResult) : BackupTransfer {
        override fun export(sourceDir: File, tree: SafDocumentTree) = result
        override fun importInto(tree: SafDocumentTree, destinationRoot: File, takenNames: Set<String>) = result
    }

    private class BlockingTransfer : BackupTransfer {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun export(sourceDir: File, tree: SafDocumentTree): TransferResult = block()
        override fun importInto(tree: SafDocumentTree, destinationRoot: File, takenNames: Set<String>): TransferResult = block()

        private fun block(): TransferResult {
            started.countDown()
            release.await()
            return TransferResult.Success("done")
        }
    }
}
