package com.termux.app.data.backup

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the in-flight (or last) SAF export or import. */
data class TransferState(
    val running: Boolean = false,
    val operation: String? = null,
    val progress: CopyProgress? = null,
    val result: TransferResult? = null,
)

/**
 * Runs one SAF transfer at a time, in an app-scoped coroutine that survives the activity, and
 * refuses to start while a script run holds the backup lock.
 */
class BackupTransferCoordinator private constructor(
    private val busy: () -> Boolean,
    private val scope: CoroutineScope,
    transferFactory: (onProgress: (CopyProgress) -> Unit, isCancelled: () -> Boolean) -> BackupTransfer,
) {

    private val mutableState = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = mutableState.asStateFlow()

    private val cancelled = AtomicBoolean(false)

    private val transfer: BackupTransfer = transferFactory(
        { copyProgress -> mutableState.update { it.copy(progress = copyProgress) } },
        { cancelled.get() },
    )

    /** Copies [sourceDir] into [tree]; ignored while another transfer or a script run is active. */
    fun startExport(sourceDir: File, tree: SafDocumentTree) =
        start("export") { transfer.export(sourceDir, tree) }

    /** Copies the directory in [tree] into [root]; ignored while another transfer or a run is active. */
    fun startImport(tree: SafDocumentTree, root: File) =
        start("import") { transfer.importInto(tree, root, root.list()?.toSet().orEmpty()) }

    /** Asks the transfer to stop; the partial output is deleted by the transfer itself. */
    fun cancel() {
        cancelled.set(true)
    }

    /** Deletes import staging directories left behind by a killed process. */
    fun sweepStaleImports(root: File) {
        root.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith(TEMP_PREFIX)) file.deleteRecursively()
        }
    }

    private fun start(operation: String, job: () -> TransferResult) {
        if (mutableState.value.running) {
            mutableState.update {
                it.copy(result = TransferResult.Failed("A transfer is already running"))
            }
            return
        }
        if (busy()) {
            mutableState.update { it.copy(result = TransferResult.Failed("A backup is already running")) }
            return
        }
        cancelled.set(false)
        mutableState.value = TransferState(running = true, operation = operation)
        scope.launch {
            val result = job()
            mutableState.update { it.copy(running = false, operation = null, result = result, progress = null) }
        }
    }

    companion object {
        private const val TEMP_PREFIX = ".tmp-import-"

        @Volatile
        private var instance: BackupTransferCoordinator? = null

        /** App-scoped coordinator; the first call creates it. */
        fun get(context: Context): BackupTransferCoordinator {
            return instance ?: synchronized(this) {
                instance ?: BackupTransferCoordinator(
                    busy = { BackupDefaults.lockFile.exists() },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                ) { onProgress, isCancelled -> SafBackupTransfer(onProgress, isCancelled) }
                    .also { instance = it }
            }
        }

        /** Test seam: a fixed transfer and a fixed busy check, on an unconfined scope. */
        fun forTest(
            transfer: BackupTransfer,
            busy: () -> Boolean,
        ): BackupTransferCoordinator = BackupTransferCoordinator(
            busy = busy,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ) { _, _ -> transfer }
    }
}
