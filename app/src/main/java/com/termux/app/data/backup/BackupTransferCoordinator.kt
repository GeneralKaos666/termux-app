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
import org.json.JSONObject

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
 *
 * A provider that throws is reported as a failure and never leaves the coordinator stuck in
 * "running"; `transfer.json` records the transfer so the next open can report an interrupted one.
 */
class BackupTransferCoordinator private constructor(
    private val busy: () -> Boolean,
    private val scope: CoroutineScope,
    private val stateFile: File?,
    transferFactory: (onProgress: (CopyProgress) -> Unit, isCancelled: () -> Boolean) -> BackupTransfer,
) {

    private val mutableState = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = mutableState.asStateFlow()

    private val cancelled = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)

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

    /** Reports and clears a transfer recorded by a process that died mid-copy. */
    fun recoverInterrupted(): TransferResult.Failed? {
        val file = stateFile ?: return null
        if (!file.exists()) return null
        file.delete()
        val failure = TransferResult.Failed(
            "The last transfer was interrupted; remove any partial directory it created",
        )
        mutableState.update { it.copy(result = failure) }
        return failure
    }

    private fun start(operation: String, job: () -> TransferResult) {
        if (!starting.compareAndSet(false, true)) return refuse("A transfer is already running")
        try {
            if (mutableState.value.running) return refuse("A transfer is already running")
            if (busy()) return refuse("A backup is already running")

            cancelled.set(false)
            writeState(operation)
            mutableState.value = TransferState(running = true, operation = operation)
            scope.launch {
                val result = try {
                    job()
                } catch (e: Exception) {
                    TransferResult.Failed(e.message ?: "the transfer failed")
                }
                clearState()
                mutableState.update {
                    it.copy(running = false, operation = null, result = result, progress = null)
                }
            }
        } finally {
            starting.set(false)
        }
    }

    private fun refuse(message: String) {
        mutableState.update { it.copy(result = TransferResult.Failed(message)) }
    }

    private fun writeState(operation: String) {
        val file = stateFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                JSONObject().apply {
                    put("operation", operation)
                    put("started_at", java.time.Instant.now().toString())
                }.toString(),
            )
        }
    }

    private fun clearState() {
        runCatching { stateFile?.delete() }
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
                    stateFile = BackupDefaults.transferStateFile,
                ) { onProgress, isCancelled -> SafBackupTransfer(onProgress, isCancelled) }
                    .also {
                        instance = it
                        it.recoverInterrupted()
                    }
            }
        }

        /** Test seam: a fixed transfer, a fixed busy check and an optional state file. */
        fun forTest(
            transfer: BackupTransfer,
            busy: () -> Boolean,
            stateFile: File? = null,
        ): BackupTransferCoordinator = BackupTransferCoordinator(
            busy = busy,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            stateFile = stateFile,
        ) { _, _ -> transfer }
    }
}
