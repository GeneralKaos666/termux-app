package com.termux.app.data.backup

import com.termux.app.domain.backup.BackupCommand
import com.termux.app.domain.backup.BackupProgress
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.StateFlow

/** Port the backup screen uses to run the CLI in a hidden session. */
interface BackupSessionRunner {

    /** Latest parsed progress; updated by [refresh] and by the runner's own poll loop. */
    val state: StateFlow<BackupProgress>

    /** Re-reads the progress file and publishes it. */
    fun refresh(): BackupProgress

    /** Re-reads the progress file for a screen that just opened and resumes polling a live run. */
    fun attach(): BackupProgress

    /** Starts [command] unless a live run holds the lock. */
    fun start(
        command: BackupCommand,
        startSession: (ExecutionCommand, BackupSessionClient) -> TerminalSession?,
    ): StartResult

    /** Stops the running command and releases the lock. */
    fun cancel()

    /** Blocks until the started session ends and returns its transcript, or null. */
    fun awaitResult(timeoutMs: Long = AWAIT_TIMEOUT_MS): String?

    /** Clears a lock left behind by a killed run. */
    fun sweepStaleLock()

    companion object {
        /** How long a result may take before it is treated as missing. */
        const val AWAIT_TIMEOUT_MS: Long = 3_600_000L
    }
}
