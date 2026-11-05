package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(@NonNull TerminalSession changedSession);

    void onTitleChanged(@NonNull TerminalSession changedSession);

    void onSessionFinished(@NonNull TerminalSession finishedSession);

    void onCopyTextToClipboard(@NonNull TerminalSession session, String text);

    /**
     * Called when the user asks to paste. The default does nothing, so an implementor that predates
     * this callback keeps compiling.
     */
    default void onPasteTextFromClipboard(@Nullable TerminalSession session) {
    }

    void onBell(@NonNull TerminalSession session);

    void onColorsChanged(@NonNull TerminalSession session);

    /**
     * Called when the cursor blink mode changes. The default does nothing, so an implementor that
     * predates this callback keeps compiling.
     */
    default void onTerminalCursorStateChange(boolean state) {
    }

    /**
     * Called when the shell pid of a session is known. The default does nothing, so an implementor
     * that predates this callback keeps compiling.
     */
    default void setTerminalShellPid(@NonNull TerminalSession session, int pid) {
    }

    /**
     * @return the cursor style the user configured, or {@code null} to use the emulator default.
     * The default returns {@code null}, so an implementor that predates this callback keeps
     * compiling.
     */
    default Integer getTerminalCursorStyle() {
        return null;
    }

    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
