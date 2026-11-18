package com.termux.app.data.backup

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred

/**
 * Client for the hidden backup session. It records the shell pid so the runner can guard against
 * concurrent runs, and keeps the screen transcript for the result summary once the session ends.
 */
class BackupSessionClient : TermuxTerminalSessionClientBase() {

    /** Shell pid reported by [TerminalSession]; 0 until the shell starts. */
    @Volatile
    var shellPid: Int = 0
        private set

    /** Full terminal text of the finished session, or null before it ends. */
    @Volatile
    var transcript: String? = null
        private set

    /** Completed when the session ends, so a caller can wait for the result. */
    val finished = CompletableDeferred<Unit>()

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        shellPid = pid
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        transcript = runCatching { finishedSession.emulator.screen.transcriptText }.getOrNull()
        finished.complete(Unit)
    }
}
