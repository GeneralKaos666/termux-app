package com.termux.app.data.backup

import java.io.File

/**
 * Port the transfer coordinator drives. [SafBackupTransfer] is the real implementation; tests use
 * a fake that can hold the transfer open.
 */
interface BackupTransfer {
    fun export(sourceDir: File, tree: SafDocumentTree): TransferResult

    fun importInto(
        tree: SafDocumentTree,
        destinationRoot: File,
        takenNames: Set<String>,
    ): TransferResult
}
