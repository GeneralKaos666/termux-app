package com.termux.app.domain.backup

import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * The phases `termux-backup` reports through `progress_phase`. [wire] is the frozen string the
 * script writes, so renaming a Kotlin constant never changes the file format.
 */
enum class BackupPhase(val wire: String) {
    VERIFYING("verifying"),
    SCANNING("scanning"),
    SAFETY("safety"),
    COLLECT_PACKAGES("collect_packages"),
    ARCHIVING_HOME("archiving_home"),
    ARCHIVING_ETC("archiving_etc"),
    CHECKSUMS("checksums"),
    PRUNE("prune"),
    EXTRACTING_ETC("extracting_etc"),
    EXTRACTING_HOME("extracting_home"),
    PACKAGES("packages"),
    ;

    companion object {

        /** The phase named [value] in the progress file, or `null` for an unknown name. */
        fun fromWire(value: String): BackupPhase? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * What the progress file says about the current run.
 *
 * [Interrupted] is never produced by [BackupProgressParser]: only the runner can decide that a
 * silent run is dead, by combining a [Running] state with a dead lock pid.
 */
sealed interface BackupRunState {

    /** No run has started, or the progress file is empty or unreadable. */
    data object Idle : BackupRunState

    /** A run started; [phase] is the last phase seen, or `null` before the first one. */
    data class Running(
        val command: String,
        val phase: BackupPhase?,
        val startedAt: String?,
    ) : BackupRunState

    /** The run ended; [ok] is true only for exit code 0. */
    data class Finished(
        val command: String,
        val exitCode: Int,
        val ok: Boolean,
    ) : BackupRunState

    /** The run has no `run_end` but its process is gone. Produced by the runner, not the parser. */
    data object Interrupted : BackupRunState
}

/**
 * A parsed progress file: the current [state], the phases in the order they were first seen, and
 * the start timestamp of the run.
 */
data class BackupProgress(
    val state: BackupRunState,
    val phasesSeen: List<BackupPhase>,
    val startedAt: String?,
) {

    companion object {

        /** The value of a file that cannot be read, or that holds no run. */
        val EMPTY: BackupProgress = BackupProgress(BackupRunState.Idle, emptyList(), null)
    }
}

/**
 * Reads the JSONL events appended by `termux-backup --progress-file`.
 *
 * The file is written while this parser reads it, so any line that does not parse is skipped: a
 * half-written trailing line, a corrupt line and an unknown event or phase all leave the state as
 * the last event that did parse. No exception escapes.
 */
object BackupProgressParser {

    /** Exit code assumed for a `run_end` event that carries none. */
    private const val DEFAULT_EXIT_CODE = 2

    /** Parse the whole progress file as text. */
    fun parse(text: String): BackupProgress {
        var state: BackupRunState = BackupRunState.Idle
        var startedAt: String? = null
        var command = ""
        val phasesSeen = mutableListOf<BackupPhase>()

        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            for (event in eventsOf(line)) {
                when (event.optString("event")) {
                    "run_start" -> {
                        command = event.optString("command")
                        startedAt = event.optString("ts").ifEmpty { null }
                        state = BackupRunState.Running(command, null, startedAt)
                    }

                    "phase" -> {
                        val phase = BackupPhase.fromWire(event.optString("name")) ?: continue
                        if (phase !in phasesSeen) phasesSeen.add(phase)
                        state = when (val current = state) {
                            is BackupRunState.Running -> current.copy(phase = phase)
                            is BackupRunState.Finished -> current
                            else -> BackupRunState.Running("", phase, startedAt)
                        }
                    }

                    "run_end" -> {
                        val exitCode = event.optInt("exit_code", DEFAULT_EXIT_CODE)
                        state = BackupRunState.Finished(command, exitCode, exitCode == 0)
                    }
                }
            }
        }

        return BackupProgress(state, phasesSeen, startedAt)
    }

    /** Parse [file]; a missing or unreadable file is [BackupProgress.EMPTY]. */
    fun parseFile(file: File): BackupProgress {
        val text = runCatching { file.readText() }.getOrNull() ?: return BackupProgress.EMPTY
        return parse(text)
    }

    /**
     * The JSON objects on one line. A line usually holds exactly one, but a partial append can
     * leave a complete object glued to the start of the next one, so read every object the line
     * holds and stop at the first thing that does not parse.
     */
    private fun eventsOf(line: String): List<JSONObject> {
        val tokener = JSONTokener(line)
        val events = mutableListOf<JSONObject>()
        while (true) {
            val value = try {
                tokener.nextValue()
            } catch (_: Exception) {
                return events
            }
            if (value is JSONObject) events.add(value)
        }
    }
}
