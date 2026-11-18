package com.termux.app.ui.backup

import com.termux.app.data.backup.ProvisionResult
import com.termux.app.domain.backup.BackupEntry
import com.termux.app.domain.backup.BackupProgress
import com.termux.app.domain.backup.BackupStatus

/**
 * What the backup screen renders: whether it is still loading, what provisioning and `status`
 * reported, the backups found, and the progress of a run.
 */
data class BackupUiState(
    val loading: Boolean = true,
    val provision: ProvisionResult? = null,
    val status: BackupStatus? = null,
    val entries: List<BackupEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val run: BackupProgress? = null,
)
