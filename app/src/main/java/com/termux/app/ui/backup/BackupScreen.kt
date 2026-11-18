package com.termux.app.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.domain.backup.BackupCommand
import com.termux.app.domain.backup.BackupEntry
import com.termux.app.domain.backup.BackupKind
import com.termux.app.domain.backup.BackupRunState
import com.termux.app.data.backup.TransferResult
import com.termux.app.data.backup.TransferState
import com.termux.app.domain.backup.BackupStatus

/** Actions the screen offers; the activity wires them to the view model and the transfer pickers. */
data class BackupActions(
    val onCreate: (BackupCommand.Backup) -> Unit,
    val onVerify: (BackupEntry) -> Unit,
    val onDelete: (BackupEntry) -> Unit,
    val onPrune: (Int) -> Unit,
    val onRestore: (BackupEntry) -> Unit,
    val onExport: (BackupEntry) -> Unit,
    val onConfirmRestore: (BackupEntry, Boolean, Boolean) -> Unit,
    val onConfirmDelete: (BackupEntry) -> Unit,
    val onDismissDialog: () -> Unit,
    val onCancelTransfer: () -> Unit,
    val onImport: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onReinstallScript: () -> Unit,
    val onGrantStorage: () -> Unit,
    val onRestart: () -> Unit,
)

/**
 * The backup screen: status header, the create form, and the list of backups with their actions.
 * Every control is disabled while a run is in flight or while provisioning has failed.
 */
@Composable
fun BackupScreen(
    state: BackupUiState,
    actions: BackupActions,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.busy && !state.loading && state.transfer?.running != true

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.title_activity_backup),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        state.message?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        state.result?.let { result ->
            item { ResultCard(result) }
        }

        state.transfer?.let { transfer ->
            item { TransferCard(transfer, onCancel = actions.onCancelTransfer) }
        }

        if (state.loading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            return@LazyColumn
        }

        item { StatusCard(state.status, onGrantStorage = actions.onGrantStorage) }

        state.run?.state?.let { run ->
            if (run is BackupRunState.Running) {
                item { RunningCard(state, onCancel = actions.onCancel) }
            }
        }

        item {
            CreateCard(state = state, enabled = enabled, onCreate = actions.onCreate)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.backup_list_header),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row {
                    TextButton(
                        onClick = { actions.onPrune(state.status?.keep ?: 5) },
                        enabled = enabled && state.entries.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.backup_prune_action))
                    }
                    TextButton(onClick = actions.onImport, enabled = enabled) {
                        Text(stringResource(R.string.backup_import_action))
                    }
                    TextButton(onClick = actions.onRetry, enabled = enabled) {
                        Text(stringResource(R.string.backup_refresh_action))
                    }
                }
            }
        }

        if (state.entries.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.backup_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.entries, key = { it.name }) { entry ->
            EntryCard(
                entry = entry,
                enabled = enabled,
                actions = actions,
            )
        }

        item {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                TextButton(onClick = actions.onReinstallScript) {
                    Text(stringResource(R.string.backup_reinstall_action))
                }
            }
        }
    }

    state.restoreDialog?.let { entry ->
        RestoreDialog(
            entry = entry,
            onConfirm = { safety, packages -> actions.onConfirmRestore(entry, safety, packages) },
            onDismiss = actions.onDismissDialog,
        )
    }

    state.confirmDelete?.let { entry ->
        ConfirmDeleteDialog(
            entry = entry,
            onConfirm = { actions.onConfirmDelete(entry) },
            onDismiss = actions.onDismissDialog,
        )
    }

    if (state.restartRequired) {
        RestartDialog(
            onRestart = actions.onRestart,
            onDismiss = actions.onDismissDialog,
        )
    }
}

@Composable
private fun ResultCard(result: RunResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = when (result) {
                is RunResult.Success -> result.summary
                is RunResult.Partial -> result.summary
                is RunResult.Failure -> stringResource(
                    R.string.backup_result_failure,
                    result.exitCode,
                    result.lastError,
                )
                RunResult.Cancelled -> stringResource(R.string.backup_cancel_action)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun RestoreDialog(
    entry: BackupEntry,
    onConfirm: (safety: Boolean, packages: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var safety by rememberSaveable { mutableStateOf(true) }
    var packages by rememberSaveable { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_dialog_title, entry.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(buildString {
                    append(entry.createdAt)
                    append(" · ")
                    append(formatBytes(entry.sizeBytes))
                    append(" · ")
                    append(entry.packages)
                    append(" packages")
                })
                Text(stringResource(R.string.backup_restore_targets))
                SwitchRow(
                    text = stringResource(R.string.backup_restore_safety),
                    checked = safety,
                    enabled = true,
                    onCheckedChange = { safety = it },
                )
                if (!safety) {
                    Text(
                        text = stringResource(R.string.backup_restore_safety_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SwitchRow(
                    text = stringResource(R.string.backup_restore_packages),
                    checked = packages,
                    enabled = true,
                    onCheckedChange = { packages = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(safety, packages) }) {
                Text(stringResource(R.string.backup_restore_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel_action)) }
        },
    )
}

@Composable
private fun ConfirmDeleteDialog(entry: BackupEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_delete_dialog_title, entry.name)) },
        text = { Text(stringResource(R.string.backup_delete_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.backup_delete_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel_action)) }
        },
    )
}

@Composable
private fun RestartDialog(onRestart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restart_title)) },
        text = { Text(stringResource(R.string.backup_restart_message)) },
        confirmButton = {
            TextButton(onClick = onRestart) { Text(stringResource(R.string.backup_restart_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel_action)) }
        },
    )
}

@Composable
private fun TransferCard(transfer: TransferState, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val progress = transfer.progress
            Text(
                text = transfer.operation?.uppercase().orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                transfer.running && progress != null && progress.indeterminate -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(progress.fileName, style = MaterialTheme.typography.bodySmall)
                }
                transfer.running && progress != null && progress.bytesTotal > 0 -> {
                    LinearProgressIndicator(
                        progress = { (progress.bytesDone.toFloat() / progress.bytesTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(progress.fileName, style = MaterialTheme.typography.bodySmall)
                }
                transfer.running -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when (val result = transfer.result) {
                is TransferResult.Success -> Text(
                    text = result.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is TransferResult.Failed -> Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TransferResult.Cancelled -> Text(
                    text = stringResource(R.string.backup_cancel_action),
                    style = MaterialTheme.typography.bodyMedium,
                )
                null -> Unit
            }
            if (transfer.running) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.backup_cancel_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: BackupStatus?, onGrantStorage: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (status == null) {
                Text(stringResource(R.string.backup_unknown_value), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            LabeledValue(R.string.backup_directory_label, status.root)
            LabeledValue(R.string.backup_count_label, status.backups.toString())
            LabeledValue(R.string.backup_free_space_label, formatBytes(status.freeSpaceBytes))
            LabeledValue(R.string.backup_writable_label, yesNo(status.writable))
            LabeledValue(R.string.backup_storage_label, yesNo(status.storageAccess))
            if (status.root.startsWith("/sdcard") || status.root.startsWith("/storage/")) {
                Text(
                    text = stringResource(R.string.backup_sdcard_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!status.storageAccess) {
                Text(
                    text = stringResource(R.string.backup_storage_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onGrantStorage) {
                    Text(stringResource(R.string.backup_grant_storage_action))
                }
            }
        }
    }
}

@Composable
private fun RunningCard(state: BackupUiState, onCancel: () -> Unit) {
    val phase = (state.run?.state as? BackupRunState.Running)?.phase?.wire?.replace('_', ' ') ?: ""
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.backup_phase_running, phase),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.backup_cancel_action))
                }
            }
        }
    }
}

@Composable
private fun CreateCard(
    state: BackupUiState,
    enabled: Boolean,
    onCreate: (BackupCommand.Backup) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var compressionOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var keepOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var includeHome by rememberSaveable { mutableStateOf(true) }
    var includeConfig by rememberSaveable { mutableStateOf(true) }
    var excludeSecrets by rememberSaveable { mutableStateOf(false) }
    var labelError by rememberSaveable { mutableStateOf<String?>(null) }

    val compression = compressionOverride ?: state.status?.compression ?: "gz"
    val keepText = keepOverride ?: (state.status?.keep ?: 5).toString()
    val invalidLabel = stringResource(R.string.backup_invalid_label)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.backup_create_header),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = label,
                onValueChange = {
                    label = it
                    labelError = null
                },
                isError = labelError != null,
                supportingText = labelError?.let { { Text(it) } },
                label = { Text(stringResource(R.string.backup_label_hint)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("gz", "bz2", "xz").forEach { option ->
                    FilterChip(
                        selected = compression == option,
                        onClick = { compressionOverride = option },
                        enabled = enabled,
                        label = { Text(option) },
                    )
                }
            }
            OutlinedTextField(
                value = keepText,
                onValueChange = { keepOverride = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.backup_keep_label)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                text = stringResource(R.string.backup_include_home),
                checked = includeHome,
                enabled = enabled,
                onCheckedChange = { includeHome = it },
            )
            SwitchRow(
                text = stringResource(R.string.backup_include_config),
                checked = includeConfig,
                enabled = enabled,
                onCheckedChange = { includeConfig = it },
            )
            SwitchRow(
                text = stringResource(R.string.backup_exclude_secrets),
                checked = excludeSecrets,
                enabled = enabled,
                onCheckedChange = { excludeSecrets = it },
            )
            Button(
                onClick = {
                    val trimmed = label.trim()
                    val error = if (trimmed.isEmpty()) null else BackupCommand.labelError(trimmed)
                    if (error != null) {
                        labelError = invalidLabel
                        return@Button
                    }
                    onCreate(
                        BackupCommand.Backup(
                            label = trimmed.ifBlank { null },
                            compression = compression,
                            keep = keepText.toIntOrNull(),
                            excludeSecrets = excludeSecrets,
                            includeHome = includeHome,
                            includeConfig = includeConfig,
                        ),
                    )
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_create_action))
            }
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun EntryCard(entry: BackupEntry, enabled: Boolean, actions: BackupActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (entry.kind == BackupKind.SAFETY) R.string.backup_kind_safety else R.string.backup_kind_backup,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${entry.createdAt} · ${formatBytes(entry.sizeBytes)} · " +
                    stringResource(R.string.backup_packages_value, entry.packages),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { actions.onRestore(entry) }, enabled = enabled) {
                    Text(stringResource(R.string.backup_restore_action))
                }
                TextButton(onClick = { actions.onVerify(entry) }, enabled = enabled) {
                    Text(stringResource(R.string.backup_verify_action))
                }
                TextButton(onClick = { actions.onExport(entry) }, enabled = enabled) {
                    Text(stringResource(R.string.backup_export_action))
                }
                TextButton(onClick = { actions.onDelete(entry) }, enabled = enabled) {
                    Text(stringResource(R.string.backup_delete_action))
                }
            }
        }
    }
}

@Composable
private fun LabeledValue(labelRes: Int, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun yesNo(value: Boolean): String =
    stringResource(if (value) R.string.backup_yes else R.string.backup_no)

/** Compact byte size for cards; exact numbers stay in the CLI output. */
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
