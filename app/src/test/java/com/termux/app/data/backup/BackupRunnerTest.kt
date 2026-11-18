package com.termux.app.data.backup

import com.termux.app.domain.backup.BackupCommand
import com.termux.app.domain.backup.BackupRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * Drives [BackupRunner] against a real temp directory and fake process probes, so the lock,
 * the progress file and the argv contract are exercised without an Android service.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class BackupRunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var scriptPath: File

    @Before
    fun setUp() {
        dir = temp.newFolder("config")
        scriptPath = File(dir, "termux-backup")
    }

    @Test
    fun `stale lock is cleared`() {
        val runner = BackupRunner(dir, scriptPath, pidAlive = { false })
        File(dir, "run.lock").writeText("""{"command":"backup","pid":4242,"started_at":"t"}""")

        runner.sweepStaleLock()

        assertFalse(File(dir, "run.lock").exists())
    }

    @Test
    fun `live lock refuses a second start`() {
        val runner = BackupRunner(dir, scriptPath, pidAlive = { true })
        File(dir, "run.lock").writeText("""{"command":"backup","pid":4242,"started_at":"t"}""")
        var dispatched = false

        val result = runner.start(BackupCommand.ListBackups) { _, _ ->
            dispatched = true
            null
        }

        assertEquals(StartResult.Refused("A backup is already running"), result)
        assertFalse(dispatched)
    }

    @Test
    fun `corrupt lock is treated as stale`() {
        val runner = BackupRunner(dir, scriptPath, pidAlive = { true })
        File(dir, "run.lock").writeText("garbage")

        runner.sweepStaleLock()

        assertFalse(File(dir, "run.lock").exists())
    }

    @Test
    fun `refresh reads the progress file`() {
        File(dir, "progress.jsonl").writeText(
            """{"event":"run_start","command":"backup","ts":"t"}
        {"event":"run_end","exit_code":0}""",
        )
        val runner = BackupRunner(dir, scriptPath, pidAlive = { false })

        assertEquals(BackupRunState.Finished("backup", 0, true), runner.refresh().state)
    }

    @Test
    fun `running progress with a dead pid is reported as interrupted`() {
        File(dir, "progress.jsonl").writeText(
            """{"event":"run_start","command":"backup","ts":"t"}
        {"event":"phase","name":"archiving_home"}""",
        )
        File(dir, "run.lock").writeText("""{"command":"backup","pid":4242,"started_at":"t"}""")
        val runner = BackupRunner(dir, scriptPath, pidAlive = { false })

        assertEquals(BackupRunState.Interrupted, runner.refresh().state)
    }

    @Test
    fun `running progress with a live pid stays running`() {
        File(dir, "progress.jsonl").writeText(
            """{"event":"run_start","command":"backup","ts":"t"}
        {"event":"phase","name":"archiving_home"}""",
        )
        File(dir, "run.lock").writeText("""{"command":"backup","pid":4242,"started_at":"t"}""")
        val runner = BackupRunner(dir, scriptPath, pidAlive = { true })

        assertTrue(runner.refresh().state is BackupRunState.Running)
    }

    @Test
    fun `a failed dispatch reports failure and leaves no lock`() {
        val runner = BackupRunner(dir, scriptPath, pidAlive = { false })

        val result = runner.start(BackupCommand.Backup(null, "gz", null, false, true, true)) { _, _ -> null }

        assertTrue(result is StartResult.Failed)
        assertFalse(File(dir, "run.lock").exists())
    }

    @Test
    fun `dispatch runs the installed script with the command argv`() {
        val runner = BackupRunner(dir, scriptPath, pidAlive = { false })
        var seenExecutable: String? = null
        var seenArguments: List<String>? = null

        runner.start(BackupCommand.Backup("pre-upgrade", "xz", 2, true, true, false)) { command, _ ->
            seenExecutable = command.executable
            seenArguments = command.arguments?.toList()
            null
        }

        val expected = BackupCommand.Backup("pre-upgrade", "xz", 2, true, true, false)
            .argv(scriptPath.path, File(dir, "progress.jsonl").path)
        assertEquals(scriptPath.path, seenExecutable)
        assertEquals(expected.drop(1), seenArguments)
    }
}
