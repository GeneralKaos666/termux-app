package com.termux.app.ui.backup

import com.termux.app.data.backup.BackupProvisioner
import com.termux.app.data.backup.BackupSessionClient
import com.termux.app.data.backup.BackupSessionRunner
import com.termux.app.data.backup.ProvisionResult
import com.termux.app.data.backup.StartResult
import com.termux.app.data.backup.TransferResult
import com.termux.app.data.backup.TransferState
import com.termux.app.domain.backup.BackupCommand
import com.termux.app.domain.backup.BackupEntry
import com.termux.app.domain.backup.BackupJson
import com.termux.app.domain.backup.BackupProgress
import com.termux.app.domain.backup.BackupRunState
import com.termux.app.domain.backup.BackupStatus
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * State owner for the backup screen: provisions the script, loads `status` and the backup list,
 * runs commands, and turns their transcripts into [RunResult]s.
 *
 * @param async runs a suspending block off the main thread; tests pass a synchronous executor
 */
class BackupViewModel(
    private val provisioner: BackupProvisioner,
    private val runner: BackupSessionRunner,
    private val startSession: (ExecutionCommand, BackupSessionClient) -> TerminalSession?,
    private val async: (suspend () -> Unit) -> Unit,
) {

    private val mutableState = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutableState.asStateFlow()

    private var lastCommand: BackupCommand? = null
    private var lastTranscript: String? = null

    /** Provisions the script, then loads the status and the backup list. */
    fun open() = async {
        mutableState.update { it.copy(loading = true, busy = true, message = null) }
        val provision = provisioner.provision()
        mutableState.update { it.copy(provision = provision, loading = false) }
        if (provision is ProvisionResult.Failed) {
            mutableState.update { it.copy(busy = true, message = provision.message) }
            return@async
        }
        runner.sweepStaleLock()
        loadStatusNow()
        loadListNow()
        mutableState.update { it.copy(busy = false) }
    }

    /** Reloads the backup list. */
    fun refreshList() = async { loadListNow() }

    /** Runs [command] and reloads the list when it finishes. */
    fun start(command: BackupCommand) = runCommand(command)

    /** Opens the restore confirmation for [entry]. */
    fun onRestore(entry: BackupEntry) {
        mutableState.update { it.copy(restoreDialog = entry) }
    }

    /** Starts the restore the user confirmed. */
    fun onConfirmRestore(entry: BackupEntry, safety: Boolean, packages: Boolean) {
        mutableState.update { it.copy(restoreDialog = null) }
        runCommand(BackupCommand.Restore(entry.name, safety = safety, packages = packages))
    }

    /** Opens the delete confirmation for [entry]. */
    fun onDelete(entry: BackupEntry) {
        mutableState.update { it.copy(confirmDelete = entry) }
    }

    /** Deletes the backup the user confirmed. */
    fun onConfirmDelete(entry: BackupEntry) {
        mutableState.update { it.copy(confirmDelete = null) }
        runCommand(BackupCommand.Delete(entry.name))
    }

    /** Runs `verify` for [entry] and records the verdict under its name. */
    fun onVerify(entry: BackupEntry) = runCommand(BackupCommand.Verify(entry.name))

    /** Prunes every backup but the newest [keep]. */
    fun onPrune(keep: Int) = runCommand(BackupCommand.Prune(keep))

    /** Closes whichever confirmation dialog is open. */
    fun onDismissDialog() {
        mutableState.update { it.copy(restoreDialog = null, confirmDelete = null) }
    }

    /** Records the result of the run that just ended. */
    fun onRunFinished(exitCode: Int) {
        val command = lastCommand ?: return
        finish(command, Outcome(lastTranscript, exitCode))
    }

    /** Clears the restart prompt once the user acted on it. */
    fun onRestartHandled() {
        mutableState.update { it.copy(restartRequired = false) }
    }

    /** Stops the running command. */
    fun cancel() {
        runner.cancel()
        mutableState.update {
            it.copy(run = runner.refresh(), result = RunResult.Cancelled, busy = false)
        }
    }

    /** Rewrites the installed script from the APK assets. */
    fun reinstallScript() = async {
        val provision = provisioner.provision(force = true)
        mutableState.update {
            it.copy(
                provision = provision,
                message = (provision as? ProvisionResult.Failed)?.message,
            )
        }
    }

    /** Publishes a transfer snapshot and reloads the list when one succeeds. */
    fun onTransfer(transfer: TransferState) {
        mutableState.update { it.copy(transfer = transfer) }
        if (transfer.result is TransferResult.Success) refreshList()
    }

    /** Publishes a progress snapshot, called from the activity's collector. */
    fun onProgress(progress: BackupProgress) {
        mutableState.update { it.copy(run = progress) }
    }

    private fun runCommand(command: BackupCommand) = async {
        mutableState.update { it.copy(busy = true, message = null, result = null) }
        val outcome = execute(command)
        if (outcome.exitCode != null) {
            finish(command, outcome)
            loadStatusNow()
            loadListNow()
        }
        mutableState.update { it.copy(busy = false) }
    }

    private fun finish(command: BackupCommand, outcome: Outcome) {
        val transcript = outcome.transcript
        val exitCode = outcome.exitCode ?: return
        val result = when {
            exitCode == 3 -> RunResult.Partial(summaryFor(command, transcript))
            exitCode == 0 -> RunResult.Success(summaryFor(command, transcript))
            else -> RunResult.Failure(exitCode, lastErrorLine(transcript))
        }
        val verified = if (command is BackupCommand.Verify) {
            val valid = runCatching { BackupJson.verified(jsonOf(transcript)).valid }.getOrNull()
            if (valid != null) state.value.verified + (command.name to valid) else state.value.verified
        } else {
            state.value.verified
        }
        mutableState.update {
            it.copy(
                result = result,
                partialSuccess = result is RunResult.Partial,
                restartRequired = command is BackupCommand.Restore && exitCode in setOf(0, 3),
                verified = verified,
            )
        }
    }

    private fun summaryFor(command: BackupCommand, transcript: String?): String {
        val json = transcript?.let { runCatching { BackupJson.lastJsonLine(it) }.getOrNull() }
        return when (command) {
            is BackupCommand.Backup -> json?.let {
                runCatching {
                    val created = BackupJson.created(it)
                    "Created ${created.name} (${formatBytes(created.sizeBytes)}, ${created.packages} packages)"
                }.getOrNull()
            } ?: "Backup created"
            is BackupCommand.Restore -> json?.let {
                runCatching {
                    val restored = BackupJson.restored(it)
                    if (restored.safety != null) {
                        "Restored ${restored.name}; safety archive ${restored.safety}"
                    } else {
                        "Restored ${restored.name}"
                    }
                }.getOrNull()
            } ?: "Backup restored"
            is BackupCommand.Delete -> json?.let {
                runCatching { "Deleted ${BackupJson.deleted(it).name}" }.getOrNull()
            } ?: "Backup deleted"
            is BackupCommand.Prune -> json?.let {
                runCatching {
                    val pruned = BackupJson.pruned(it)
                    "Removed ${pruned.removed}, kept ${pruned.kept}"
                }.getOrNull()
            } ?: "Backups pruned"
            is BackupCommand.Verify -> json?.let {
                runCatching {
                    val checked = BackupJson.verified(it)
                    if (checked.valid) "Verified ${checked.name}" else "Verification failed: ${checked.name}"
                }.getOrNull()
            } ?: "Backup verified"
            BackupCommand.ListBackups, BackupCommand.Status -> "Done"
        }
    }

    /**
     * Runs [command] and returns its transcript and exit code, reporting refusals and start
     * failures on the screen instead of throwing.
     */
    private fun execute(command: BackupCommand): Outcome {
        lastCommand = command
        return when (val result = runner.start(command, startSession)) {
            StartResult.Started -> {
                val transcript = runner.awaitResult()
                lastTranscript = transcript
                Outcome(transcript, exitCodeOf(runner.refresh()))
            }
            is StartResult.Refused -> {
                mutableState.update { it.copy(message = result.reason) }
                Outcome(null, null)
            }
            is StartResult.Failed -> {
                mutableState.update { it.copy(message = result.message) }
                Outcome(null, null)
            }
        }
    }

    private fun exitCodeOf(progress: BackupProgress): Int? = when (val run = progress.state) {
        is BackupRunState.Finished -> run.exitCode
        else -> null
    }

    private fun loadStatusNow() {
        val json = executeTranscript(BackupCommand.Status) ?: return
        val status: BackupStatus =
            runCatching { BackupJson.status(BackupJson.lastJsonLine(json)) }.getOrNull() ?: return
        mutableState.update { it.copy(status = status) }
    }

    private fun loadListNow() {
        val json = executeTranscript(BackupCommand.ListBackups) ?: return
        val entries: List<BackupEntry> =
            runCatching { BackupJson.list(BackupJson.lastJsonLine(json)).entries }.getOrNull() ?: return
        mutableState.update { it.copy(entries = entries) }
    }

    /** Runs a read-only command and returns its transcript, ignoring results and failures. */
    private fun executeTranscript(command: BackupCommand): String? {
        return when (runner.start(command, startSession)) {
            StartResult.Started -> runner.awaitResult()
            else -> null
        }
    }

    private fun jsonOf(transcript: String?): String =
        transcript?.let { runCatching { BackupJson.lastJsonLine(it) }.getOrNull() } ?: ""

    /** Last human line of the transcript, for a failure message. */
    private fun lastErrorLine(transcript: String?): String {
        val line = transcript.orEmpty()
            .split('\n')
            .map { it.trim().trimEnd('\r') }
            .lastOrNull { it.isNotEmpty() && !it.startsWith("{") }
        return line ?: "The command failed"
    }

    private class Outcome(val transcript: String?, val exitCode: Int?)

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format("%.1f %s", value, units[unit])
    }
}
