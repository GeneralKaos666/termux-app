package com.termux.app.ui.backup

import com.termux.app.data.backup.BackupProvisioner
import com.termux.app.data.backup.BackupSessionClient
import com.termux.app.data.backup.BackupSessionRunner
import com.termux.app.data.backup.ProvisionResult
import com.termux.app.data.backup.StartResult
import com.termux.app.data.backup.TransferState
import com.termux.app.domain.backup.BackupCommand
import com.termux.app.domain.backup.BackupProgress
import com.termux.app.domain.backup.BackupRunState
import com.termux.app.domain.backup.BackupStatus
import com.termux.app.domain.backup.BackupPhase
import com.termux.app.domain.backup.BackupEntry
import com.termux.app.domain.backup.BackupKind
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * Drives [BackupViewModel] with fake ports and a synchronous executor, so the state transitions are
 * deterministic without a coroutine test dispatcher dependency.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class BackupViewModelTest {

    private val statusJson =
        """{"tool_version":"1.1.0","root":"/storage/termux-backups","writable":true,""" +
            """"storage_access":true,"backups":2,"compression":"gz","keep":5,"free_space":4096}"""

    private val listJson =
        """{"root":"/storage/termux-backups","backups":[""" +
            """{"name":"termux_backup_20260101_000000","created_at":"2026-01-01T00:00:00Z","size":10,"packages":2,"kind":"backup"},""" +
            """{"name":"safety_pre_restore_20260102_000000","created_at":"2026-01-02T00:00:00Z","size":5,"packages":0,"kind":"safety"}]}"""

    private val createdJson =
        """{"name":"termux_backup_20260103_000000","root":"/storage/termux-backups","size":12,"packages":3}"""

    private val entry = BackupEntry(ENTRY_NAME, BackupKind.BACKUP, "2026-01-01T00:00:00Z", 10, 2)

    @Test
    fun `open provisions then loads status and list`() {
        val provisioner = FakeProvisioner(ProvisionResult.Installed("1.1.0"))
        val runner = FakeRunner(mapOf("status" to statusJson, "list" to listJson))
        val viewModel = viewModel(provisioner, runner)

        viewModel.open()

        assertEquals("1.1.0", (viewModel.state.value.provision as ProvisionResult.Installed).version)
        assertFalse(viewModel.state.value.loading)
        assertEquals("/storage/termux-backups", viewModel.state.value.status?.root)
        assertEquals(2, viewModel.state.value.entries.size)
        assertEquals(BackupKind.SAFETY, viewModel.state.value.entries[1].kind)
    }

    @Test
    fun `provision failure disables actions and keeps the screen`() {
        val provisioner = FakeProvisioner(ProvisionResult.Failed("/p/lib: read-only"))
        val runner = FakeRunner()
        val viewModel = viewModel(provisioner, runner)

        viewModel.open()

        assertTrue(viewModel.state.value.busy)
        assertEquals("/p/lib: read-only", viewModel.state.value.message)
        assertEquals(0, runner.startCount)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun `a run refreshes the list when it finishes`() {
        val runner = FakeRunner(mapOf("status" to statusJson, "list" to listJson, "backup" to createdJson))
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)
        viewModel.open()
        val listsBefore = runner.listCount

        viewModel.start(BackupCommand.Backup(null, "gz", null, false, true, true))

        assertEquals(listsBefore + 1, runner.listCount)
        assertEquals(2, viewModel.state.value.entries.size)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `reinstall script forces provisioning`() {
        val provisioner = FakeProvisioner(ProvisionResult.UpToDate)
        val viewModel = viewModel(provisioner, FakeRunner())

        viewModel.open()
        viewModel.reinstallScript()

        assertEquals(listOf(false, true), provisioner.forceCalls)
    }

    @Test
    fun `refused run surfaces the reason`() {
        val runner = FakeRunner(mapOf("status" to statusJson, "list" to listJson), refuse = "A backup is already running")
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)
        viewModel.open()

        viewModel.start(BackupCommand.ListBackups)

        assertEquals("A backup is already running", viewModel.state.value.message)
    }

    @Test
    fun `status without storage access keeps the list and warns`() {
        val noAccess = statusJson.replace("\"storage_access\":true", "\"storage_access\":false")
        val runner = FakeRunner(mapOf("status" to noAccess, "list" to listJson))
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)

        viewModel.open()

        assertNotNull(viewModel.state.value.status)
        assertFalse(viewModel.state.value.status!!.storageAccess)
        assertEquals(2, viewModel.state.value.entries.size)
    }

    @Test
    fun `restore confirmation maps toggles`() {
        val runner = FakeRunner(mapOf("status" to statusJson, "list" to listJson))
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)
        viewModel.open()
        runner.lastCommand = null

        viewModel.onConfirmRestore(entry, safety = false, packages = true)

        assertEquals(BackupCommand.Restore(ENTRY_NAME, safety = false, packages = true), runner.issued())
        assertNull(viewModel.state.value.restoreDialog)
    }

    @Test
    fun `verify success is shown inline`() {
        val runner = FakeRunner(
            mapOf(
                "status" to statusJson,
                "list" to listJson,
                "verify" to """{"name":"$ENTRY_NAME","root":"/r","valid":true}""",
            ),
        )
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)
        viewModel.open()

        viewModel.onVerify(entry)

        assertEquals(true, viewModel.state.value.verified[ENTRY_NAME])
    }

    @Test
    fun `delete requires confirmation before the command`() {
        val runner = FakeRunner(
            mapOf(
                "status" to statusJson,
                "list" to listJson,
                "delete" to """{"deleted":"$ENTRY_NAME","root":"/r"}""",
            ),
        )
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)
        viewModel.open()
        runner.lastCommand = null

        viewModel.onDelete(entry)
        assertNull(runner.lastCommand)
        assertNotNull(viewModel.state.value.confirmDelete)

        viewModel.onConfirmDelete(entry)
        assertEquals(BackupCommand.Delete(ENTRY_NAME), runner.issued())
        assertNull(viewModel.state.value.confirmDelete)
    }

    @Test
    fun `prune sends the chosen keep count`() {
        val runner = FakeRunner(mapOf("status" to statusJson, "list" to listJson))
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)
        viewModel.open()
        runner.lastCommand = null

        viewModel.onPrune(0)

        assertEquals(BackupCommand.Prune(0), runner.issued())
    }

    @Test
    fun `failure carries the last stderr line`() {
        val runner = FakeRunner(
            mapOf("status" to statusJson, "list" to listJson),
            exit = 2,
            fixedTranscript = "termux-backup: backup failed; no backup was kept\n",
        )
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)
        viewModel.open()

        viewModel.start(BackupCommand.Backup(null, "gz", null, false, true, true))

        val result = viewModel.state.value.result
        assertTrue(result is RunResult.Failure)
        assertEquals("termux-backup: backup failed; no backup was kept", (result as RunResult.Failure).lastError)
    }

    @Test
    fun `failure ignores the session exit trailer`() {
        val runner = FakeRunner(
            mapOf("status" to statusJson, "list" to listJson),
            exit = 2,
            fixedTranscript = "termux-backup: checksum mismatch: demo\n" +
                "[Process completed (code 2) - press Enter]\n",
        )
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)
        viewModel.open()

        viewModel.start(BackupCommand.Verify("demo"))

        val result = viewModel.state.value.result
        assertTrue(result is RunResult.Failure)
        assertEquals("termux-backup: checksum mismatch: demo", (result as RunResult.Failure).lastError)
    }

    @Test
    fun `restore exit 3 is reported as partial success`() {
        val runner = FakeRunner(
            mapOf(
                "status" to statusJson,
                "list" to listJson,
                "restore" to
                    """{"name":"$ENTRY_NAME","root":"/r","safety":"safety_pre_restore_20260102_000000",""" +
                    """"packages_installed":1,"packages_failed":2}""",
            ),
            exit = 3,
        )
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)
        viewModel.open()

        viewModel.onConfirmRestore(entry, safety = true, packages = true)

        assertTrue(viewModel.state.value.partialSuccess)
        assertTrue(viewModel.state.value.result is RunResult.Partial)
        assertTrue(viewModel.state.value.restartRequired)
    }

    @Test
    fun `open attaches to a live run instead of reloading status and list`() {
        val running = BackupProgress(
            BackupRunState.Running("backup", BackupPhase.ARCHIVING_HOME, "2026-01-01T00:00:00Z"),
            listOf(BackupPhase.ARCHIVING_HOME),
            "2026-01-01T00:00:00Z",
        )
        val runner = FakeRunner(mapOf("status" to statusJson, "list" to listJson), attachState = running)
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)

        viewModel.open()

        assertEquals(0, runner.startCount)
        assertTrue(viewModel.state.value.busy)
        assertEquals(running.state, viewModel.state.value.run?.state)
    }

    @Test
    fun `open reports a run whose process is gone as interrupted`() {
        val interrupted = BackupProgress(BackupRunState.Interrupted, emptyList(), "2026-01-01T00:00:00Z")
        val runner = FakeRunner(mapOf("status" to statusJson, "list" to listJson), attachState = interrupted)
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)

        viewModel.open()

        assertEquals(BackupRunState.Interrupted, viewModel.state.value.run?.state)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `start is refused while a transfer runs`() {
        val runner = FakeRunner(mapOf("status" to statusJson, "list" to listJson))
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)
        viewModel.open()
        val startsBefore = runner.startCount
        viewModel.onTransfer(TransferState(running = true, operation = "import"))

        viewModel.start(BackupCommand.Backup(null, "gz", null, false, true, true))

        assertEquals("MARKER transfer running", viewModel.state.value.message)
        assertEquals(startsBefore, runner.startCount)
    }

    @Test
    fun `a finished command reports the injected summary`() {
        val runner = FakeRunner(mapOf("status" to statusJson, "list" to listJson, "backup" to createdJson))
        val viewModel = viewModel(FakeProvisioner(ProvisionResult.UpToDate), runner)
        viewModel.open()

        viewModel.start(BackupCommand.Backup(null, "gz", null, false, true, true))

        val result = viewModel.state.value.result
        assertTrue(result is RunResult.Success)
        assertEquals("MARKER created", (result as RunResult.Success).summary)
    }

    private fun viewModel(provisioner: BackupProvisioner, runner: BackupSessionRunner) =
        BackupViewModel(
            provisioner = provisioner,
            runner = runner,
            startSession = { _, _ -> null },
            async = { block -> runBlocking { block() } },
            strings = FakeBackupStrings(),
        )

    /** Proves the view model has no copy of its own: every message is a marker it must not know. */
    private class FakeBackupStrings : BackupStrings {
        override fun transferRunning() = "MARKER transfer running"
        override fun created(name: String?, size: String?, packages: Int?) = "MARKER created"
        override fun restored(name: String?, safety: String?) = "MARKER restored"
        override fun deleted(name: String?) = "MARKER deleted"
        override fun pruned(removed: Int?, kept: Int?) = "MARKER pruned"
        override fun verified(name: String?, valid: Boolean?) = "MARKER verified"
        override fun commandFailed() = "MARKER command failed"
        override fun done() = "MARKER done"
    }

    private class FakeProvisioner(private val result: ProvisionResult) : BackupProvisioner {
        val forceCalls = mutableListOf<Boolean>()

        override fun provision(force: Boolean): ProvisionResult {
            forceCalls += force
            return result
        }
    }

    private inner class FakeRunner(
        private val transcripts: Map<String, String> = emptyMap(),
        private val refuse: String? = null,
        private val exit: Int = 0,
        private val fixedTranscript: String? = null,
        private val attachState: BackupProgress = BackupProgress(BackupRunState.Idle, emptyList(), null),
    ) : BackupSessionRunner {

        private val mutableState = MutableStateFlow(
            BackupProgress(BackupRunState.Idle, emptyList(), null),
        )
        override val state: StateFlow<BackupProgress> = mutableState
        private var pending: String? = null

        var startCount = 0
        var listCount = 0
        var statusCount = 0
        var lastCommand: BackupCommand? = null
        val commands = mutableListOf<BackupCommand>()

        override fun start(
            command: BackupCommand,
            startSession: (ExecutionCommand, BackupSessionClient) -> TerminalSession?,
        ): StartResult {
            startCount++
            lastCommand = command
            commands += command
            when (command.subcommand) {
                "list" -> listCount++
                "status" -> statusCount++
            }
            refuse?.let { return StartResult.Refused(it) }
            pending = fixedTranscript ?: transcripts[command.subcommand]
            mutableState.value = BackupProgress(BackupRunState.Running(command.subcommand, null, null), emptyList(), null)
            return StartResult.Started
        }

        override fun refresh(): BackupProgress {
            val progress = BackupProgress(
                BackupRunState.Finished(lastCommand?.subcommand ?: "", exit, exit == 0),
                emptyList(),
                null,
            )
            mutableState.value = progress
            return progress
        }

        override fun attach(): BackupProgress {
            mutableState.value = attachState
            return attachState
        }

        override fun cancel() = Unit

        override fun awaitResult(timeoutMs: Long): String? = pending

        override fun sweepStaleLock() = Unit

        /** Last command issued by the user, ignoring the status/list reloads. */
        fun issued(): BackupCommand? = commands.lastOrNull {
            it !is BackupCommand.Status && it !is BackupCommand.ListBackups
        }
    }

    private companion object {
        const val ENTRY_NAME = "termux_backup_x"
    }
}
