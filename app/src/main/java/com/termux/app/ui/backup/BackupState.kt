package com.termux.app.ui.backup

import com.termux.app.data.backup.ProvisionResult
import com.termux.app.domain.backup.BackupEntry
import com.termux.app.domain.backup.BackupProgress
import com.termux.app.domain.backup.BackupStatus

/**
 * What the backup screen renders: whether it is still loading, what provisioning and `status`
 * reported, the backups found, the progress of a run, and the pending dialogs.
 */
data class BackupUiState(
    val loading: Boolean = true,
    val provision: ProvisionResult? = null,
    val status: BackupStatus? = null,
    val entries: List<BackupEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val run: BackupProgress? = null,
    /** Entry whose restore is being confirmed, or null. */
    val restoreDialog: BackupEntry? = null,
    /** Entry whose delete is being confirmed, or null. */
    val confirmDelete: BackupEntry? = null,
    /** Result of the last finished command. */
    val result: RunResult? = null,
    /** True when files were restored but packages failed (exit code 3). */
    val partialSuccess: Boolean = false,
    /** True after a restore, so the screen offers a restart. */
    val restartRequired: Boolean = false,
    /** Per-backup verification outcome, keyed by backup name. */
    val verified: Map<String, Boolean> = emptyMap(),
)

/** Outcome of one command, as the screen reports it. */
sealed interface RunResult {

    /** Finished successfully; [summary] is one human line. */
    data class Success(val summary: String) : RunResult

    /** Files were restored but the package step failed. */
    data class Partial(val summary: String) : RunResult

    /** The command failed; [lastError] is the last human line of the transcript. */
    data class Failure(val exitCode: Int, val lastError: String) : RunResult

    /** The user cancelled the run. */
    data object Cancelled : RunResult
}
