package com.termux.app.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class BackupProgressTest {

    @Test
    fun `empty file is idle`() {
        assertEquals(BackupRunState.Idle, BackupProgressParser.parse("").state)
    }

    @Test
    fun `running reports the last phase`() {
        val text = """
            {"ts":"t","event":"run_start","command":"backup","pid":1}
            {"ts":"t","event":"phase","name":"collect_packages"}
            {"ts":"t","event":"phase","name":"archiving_home"}
        """.trimIndent()
        val progress = BackupProgressParser.parse(text)
        assertEquals(BackupRunState.Running("backup", BackupPhase.ARCHIVING_HOME, "t"), progress.state)
        assertEquals(listOf(BackupPhase.COLLECT_PACKAGES, BackupPhase.ARCHIVING_HOME), progress.phasesSeen)
    }

    @Test
    fun `partial trailing line is ignored`() {
        val text = """{"event":"run_start","command":"backup","ts":"t"}{"event":"pha"""
        val progress = BackupProgressParser.parse(text)
        assertTrue(progress.state is BackupRunState.Running)
    }

    @Test
    fun `run end wins and maps exit codes`() {
        val ok = BackupProgressParser.parse(
            """{"event":"run_start","command":"restore","ts":"t"}
            {"event":"run_end","exit_code":0}""",
        )
        assertEquals(BackupRunState.Finished("restore", 0, true), ok.state)
        val failed = BackupProgressParser.parse(
            """{"event":"run_start","command":"backup","ts":"t"}
            {"event":"run_end","exit_code":2}""",
        )
        assertEquals(BackupRunState.Finished("backup", 2, false), failed.state)
    }

    @Test
    fun `run end without exit code is a failure`() {
        val progress = BackupProgressParser.parse(
            """{"event":"run_start","command":"backup","ts":"t"}
            {"event":"run_end"}""",
        )
        assertEquals(BackupRunState.Finished("backup", 2, false), progress.state)
    }

    @Test
    fun `run without end keeps the running state`() {
        val progress = BackupProgressParser.parse(
            """{"event":"run_start","command":"backup","ts":"t"}
            {"event":"phase","name":"archiving_home"}""",
        )
        assertEquals(BackupRunState.Running("backup", BackupPhase.ARCHIVING_HOME, "t"), progress.state)
    }

    @Test
    fun `unknown phase and corrupt line are skipped`() {
        val text = "not json\n" + """{"event":"run_start","command":"backup","ts":"t"}
            {"event":"phase","name":"future_phase"}
            {"event":"phase","name":"checksums"}"""
        assertEquals(
            BackupPhase.CHECKSUMS,
            (BackupProgressParser.parse(text).state as BackupRunState.Running).phase,
        )
    }
}
