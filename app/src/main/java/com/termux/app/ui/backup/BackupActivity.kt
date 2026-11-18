package com.termux.app.ui.backup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import com.termux.app.TermuxInstaller
import com.termux.app.TermuxService
import com.termux.app.data.backup.ApkAssetSource
import com.termux.app.data.backup.BackupDefaults
import com.termux.app.data.backup.BackupRunner
import com.termux.app.data.backup.AndroidSafDocumentTree
import com.termux.app.data.backup.BackupScriptProvisioner
import com.termux.app.data.backup.BackupTransferCoordinator
import com.termux.app.domain.backup.BackupEntry
import com.termux.app.ui.theme.TermuxShellTheme
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Composition root for the backup screen: binds [TermuxService] so the CLI can run in a hidden
 * session, owns the view model, and hands [BackupScreen] its actions.
 */
class BackupActivity : ComponentActivity(), ServiceConnection {

    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var service: TermuxService? = null
    private lateinit var runner: BackupRunner
    private lateinit var viewModel: BackupViewModel
    private var bound = false
    private var pendingExport: BackupEntry? = null
    private var sweptImports = false

    private val coordinator: BackupTransferCoordinator by lazy { BackupTransferCoordinator.get(this) }

    private val exportPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val entry = pendingExport
            pendingExport = null
            val root = viewModel.state.value.status?.root ?: return@registerForActivityResult
            if (uri == null || entry == null) return@registerForActivityResult
            coordinator.startExport(File(root, entry.name), AndroidSafDocumentTree(this, uri))
        }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                TermuxInstaller.setupStorageSymlinks(this)
            }
            viewModel.open()
        }

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val root = viewModel.state.value.status?.root ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            coordinator.startImport(AndroidSafDocumentTree(this, uri), File(root))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runner = BackupRunner(BackupDefaults.configDir, BackupDefaults.scriptPath)
        viewModel = BackupViewModel(
            provisioner = BackupScriptProvisioner(
                ApkAssetSource(this),
                BackupDefaults.scriptInstallDir,
                BackupDefaults.scriptBinLink,
            ),
            runner = runner,
            startSession = { command, client -> service?.createBackupSession(command, client)?.terminalSession },
            async = { block -> workScope.launch { block() } },
        )

        setContent {
            TermuxShellTheme {
                val state by viewModel.state.collectAsState()
                BackupScreen(state = state, actions = actions())
            }
        }

        lifecycleScope.launch { runner.state.collect { viewModel.onProgress(it) } }
        lifecycleScope.launch {
            coordinator.state.collect { transfer ->
                viewModel.onTransfer(transfer)
                val root = viewModel.state.value.status?.root
                if (!sweptImports && root != null) {
                    sweptImports = true
                    coordinator.sweepStaleImports(File(root))
                }
            }
        }

        val intent = Intent(this, TermuxService::class.java)
        startService(intent)
        bound = bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        service = (binder as? TermuxService.LocalBinder)?.service
        viewModel.open()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(this)
            bound = false
        }
        workScope.cancel()
        super.onDestroy()
    }

    /** Asks for legacy storage access, then re-reads status so the header reflects the grant. */
    private fun requestStorageAccess() {
        storagePermission.launch(
            arrayOf(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ),
        )
    }

    private fun actions() = BackupActions(
        onCreate = { viewModel.start(it) },
        onVerify = { viewModel.onVerify(it) },
        onDelete = { viewModel.onDelete(it) },
        onPrune = { viewModel.onPrune(it) },
        onRestore = { viewModel.onRestore(it) },
        onExport = { entry ->
            pendingExport = entry
            exportPicker.launch(null)
        },
        onImport = { importPicker.launch(null) },
        onCancel = { viewModel.cancel() },
        onRetry = { viewModel.open() },
        onReinstallScript = { viewModel.reinstallScript() },
        onGrantStorage = { requestStorageAccess() },
        onRestart = {
            viewModel.onRestartHandled()
            sendBroadcast(
                Intent(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE).apply {
                    setPackage(packageName)
                    putExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, false)
                },
            )
            finish()
        },
        onConfirmRestore = { entry, safety, packages -> viewModel.onConfirmRestore(entry, safety, packages) },
        onConfirmDelete = { viewModel.onConfirmDelete(it) },
        onDismissDialog = { viewModel.onDismissDialog() },
        onCancelTransfer = { coordinator.cancel() },
    )
}
