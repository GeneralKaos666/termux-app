package com.termux.app.ui.backup

import com.termux.app.data.backup.BackupProvisioner
import com.termux.app.data.backup.BackupSessionClient
import com.termux.app.data.backup.BackupSessionRunner
import com.termux.app.data.backup.ProvisionResult
import com.termux.app.data.backup.StartResult
import com.termux.app.domain.backup.BackupCommand
import com.termux.app.domain.backup.BackupEntry
import com.termux.app.domain.backup.BackupJson
import com.termux.app.domain.backup.BackupProgress
import com.termux.app.domain.backup.BackupStatus
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * State owner for the backup screen: provisions the script, loads `status` and the backup list,
 * runs commands, and exposes the result to [BackupScreen].
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
    fun start(command: BackupCommand) = async {
        mutableState.update { it.copy(busy = true, message = null) }
        execute(command)
        mutableState.update { it.copy(busy = false) }
        loadStatusNow()
        loadListNow()
    }

    /** Stops the running command. */
    fun cancel() {
        runner.cancel()
        mutableState.update { it.copy(run = runner.refresh()) }
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

    /** Publishes a progress snapshot, called from the activity's collector. */
    fun onProgress(progress: BackupProgress) {
        mutableState.update { it.copy(run = progress) }
    }

    private fun loadStatusNow() {
        val json = execute(BackupCommand.Status) ?: return
        val status: BackupStatus = runCatching { BackupJson.status(BackupJson.lastJsonLine(json)) }.getOrNull()
            ?: return
        mutableState.update { it.copy(status = status) }
    }

    private fun loadListNow() {
        val json = execute(BackupCommand.ListBackups) ?: return
        val entries: List<BackupEntry> = runCatching { BackupJson.list(BackupJson.lastJsonLine(json)).entries }
            .getOrNull() ?: return
        mutableState.update { it.copy(entries = entries) }
    }

    /**
     * Runs [command] and returns its transcript, reporting refusals and start failures on the
     * screen instead of throwing.
     */
    private fun execute(command: BackupCommand): String? {
        return when (val result = runner.start(command, startSession)) {
            StartResult.Started -> runner.awaitResult()
            is StartResult.Refused -> {
                mutableState.update { it.copy(message = result.reason) }
                null
            }
            is StartResult.Failed -> {
                mutableState.update { it.copy(message = result.message) }
                null
            }
        }
    }
}
