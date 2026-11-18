package com.termux.app.data.backup

import com.termux.app.domain.backup.BackupCommand
import com.termux.app.domain.backup.BackupProgress
import com.termux.app.domain.backup.BackupProgressParser
import com.termux.app.domain.backup.BackupRunState
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.shell.TermuxShellManager
import com.termux.terminal.TerminalSession
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Outcome of asking the runner to start a command. */
sealed interface StartResult {
    /** The session started and the lock is held. */
    data object Started : StartResult

    /** Another run holds the lock. */
    data class Refused(val reason: String) : StartResult

    /** The session could not be started. */
    data class Failed(val message: String) : StartResult
}

/** Identity of the run that currently holds the lock. */
data class RunLock(
    val command: String,
    val pid: Int,
    val startedAt: String,
    /** Process start time, so a reused pid is not mistaken for the same run. */
    val startTime: String? = null,
)

/**
 * Starts `termux-backup` in a hidden terminal session, holds the single-run lock, and polls the
 * progress file into [state].
 *
 * @param configDir directory holding `progress.jsonl` and `run.lock`
 * @param scriptPath the provisioned script to execute
 * @param pidAlive process probe for stale-lock detection; tests pass a fake
 * @param scope scope used for the polling coroutine
 */
class BackupRunner(
    private val configDir: File,
    private val scriptPath: File,
    private val pidAlive: (Int) -> Boolean = ::defaultPidAlive,
    private val pidStart: (Int) -> String? = ::defaultPidStart,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BackupSessionRunner {

    private val progressFile: File = File(configDir, "progress.jsonl")
    private val lockFile: File = File(configDir, "run.lock")

    private val mutableState = MutableStateFlow(BackupProgressParser.parseFile(progressFile))
    override val state: StateFlow<BackupProgress> = mutableState.asStateFlow()

    private var session: TerminalSession? = null
    private var client: BackupSessionClient? = null
    private var pollJob: Job? = null

    /** Clears a lock whose pid is dead or whose contents cannot be parsed. */
    override fun sweepStaleLock() {
        val lock = readLock() ?: run {
            if (lockFile.exists()) lockFile.delete()
            return
        }
        if (!isLockLive(lock)) lockFile.delete()
    }

    /**
     * Re-reads the progress file for a screen that just opened. When the file shows a run in flight,
     * polling resumes so the screen follows it instead of freezing at the first snapshot.
     */
    override fun attach(): BackupProgress {
        val progress = refresh()
        if (progress.state is BackupRunState.Running) startPolling()
        return progress
    }

    /** Re-reads the progress file and publishes the state, mapping a dead run to [BackupRunState.Interrupted]. */
    override fun refresh(): BackupProgress {
        val progress = BackupProgressParser.parseFile(progressFile)
        val resolved = if (progress.state is BackupRunState.Running) {
            val lock = readLock()
            if (lock != null && !isLockLive(lock)) {
                BackupProgress(BackupRunState.Interrupted, progress.phasesSeen, progress.startedAt)
            } else {
                progress
            }
        } else {
            progress
        }
        mutableState.value = resolved
        return resolved
    }

    /**
     * Starts [command] unless another run holds a live lock.
     *
     * @param startSession creates the hidden session; returns the terminal session or null on failure
     */
    override fun start(
        command: BackupCommand,
        startSession: (ExecutionCommand, BackupSessionClient) -> TerminalSession?,
    ): StartResult {
        readLock()?.let { lock ->
            if (isLockLive(lock)) return StartResult.Refused("A backup is already running")
            lockFile.delete()
        }

        val argv = command.argv(scriptPath.path, progressFile.path)
        val executionCommand = ExecutionCommand(
            TermuxShellManager.getNextShellId(),
            scriptPath.path,
            argv.drop(1).toTypedArray(),
            null,
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            ExecutionCommand.Runner.TERMINAL_SESSION.getName(),
            false,
        ).apply { shellName = SUBCOMMAND_SHELL_NAME }

        val newClient = BackupSessionClient()
        val newSession = startSession(executionCommand, newClient)
            ?: return StartResult.Failed("Could not start the backup session")
        if (!awaitShellPid(newClient)) {
            newSession.finishIfRunning()
            return StartResult.Failed("The backup session did not start")
        }

        session = newSession
        client = newClient
        val pid = newClient.shellPid
        writeLock(RunLock(command.subcommand, pid, java.time.Instant.now().toString(), pidStart(pid)))
        startPolling()
        return StartResult.Started
    }

    /** Stops the running command and releases the lock. */
    override fun cancel() {
        pollJob?.cancel()
        pollJob = null
        lockFile.delete()
        session?.finishIfRunning()
        session = null
        client = null
    }

    /**
     * Blocks until the started session ends and returns its transcript, or null on timeout or when
     * no session was started.
     */
    override fun awaitResult(timeoutMs: Long): String? {
        val active = client ?: return null
        return runBlocking {
            withTimeoutOrNull(timeoutMs) { active.finished.await() }
            active.transcript
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val progress = refresh()
                when (progress.state) {
                    is BackupRunState.Finished -> {
                        lockFile.delete()
                        return@launch
                    }
                    BackupRunState.Interrupted -> return@launch
                    else -> delay(BackupDefaults.pollIntervalMs)
                }
            }
        }
    }

    /** True when the lock's pid is alive and, when recorded, is still the same process. */
    private fun isLockLive(lock: RunLock): Boolean {
        if (!pidAlive(lock.pid)) return false
        val recorded = lock.startTime ?: return true
        return pidStart(lock.pid) == recorded
    }

    private fun awaitShellPid(target: BackupSessionClient): Boolean {
        val deadline = System.currentTimeMillis() + SHELL_PID_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (target.shellPid > 0) return true
            Thread.sleep(SHELL_PID_POLL_MS)
        }
        return target.shellPid > 0
    }

    private fun readLock(): RunLock? {
        val text = runCatching { lockFile.readText() }.getOrNull() ?: return null
        return runCatching {
            val json = JSONObject(text)
            RunLock(
                json.optString("command"),
                json.optInt("pid"),
                json.optString("started_at"),
                json.optString("start_time").takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    private fun writeLock(lock: RunLock) {
        runCatching {
            lockFile.parentFile?.mkdirs()
            lockFile.writeText(
                JSONObject().apply {
                    put("command", lock.command)
                    put("pid", lock.pid)
                    put("started_at", lock.startedAt)
                    lock.startTime?.let { put("start_time", it) }
                }.toString(),
            )
        }
    }

    companion object {
        private const val SUBCOMMAND_SHELL_NAME = "backup"
        private const val SHELL_PID_TIMEOUT_MS = 2_000L
        private const val SHELL_PID_POLL_MS = 20L

        /** Start time of [pid] from `/proc`, used to detect a reused pid. */
        fun defaultPidStart(pid: Int): String? {
            if (pid <= 0) return null
            return runCatching {
                val stat = File("/proc/$pid/stat").readText()
                // Fields after the parenthesised command name start at field 3; starttime is field 22.
                stat.substringAfterLast(')').trim().split(' ')[19]
            }.getOrNull()
        }

        /** Default probe: true when [pid] names a live process. */
        fun defaultPidAlive(pid: Int): Boolean {
            if (pid <= 0) return false
            return try {
                android.system.Os.kill(pid, 0)
                true
            } catch (_: android.system.ErrnoException) {
                // EPERM means the process exists but belongs to someone else.
                false
            }
        }
    }
}
