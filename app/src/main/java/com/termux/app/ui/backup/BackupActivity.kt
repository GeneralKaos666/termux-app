package com.termux.app.ui.backup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import com.termux.app.TermuxService
import com.termux.app.data.backup.ApkAssetSource
import com.termux.app.data.backup.BackupDefaults
import com.termux.app.data.backup.BackupRunner
import com.termux.app.data.backup.BackupScriptProvisioner
import com.termux.app.ui.theme.TermuxShellTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    private fun actions() = BackupActions(
        onCreate = { viewModel.start(it) },
        onVerify = {},
        onDelete = {},
        onPrune = {},
        onRestore = {},
        onExport = {},
        onImport = {},
        onCancel = { viewModel.cancel() },
        onRetry = { viewModel.refreshList() },
        onReinstallScript = { viewModel.reinstallScript() },
        onGrantStorage = {},
        onRestart = {},
    )
}
