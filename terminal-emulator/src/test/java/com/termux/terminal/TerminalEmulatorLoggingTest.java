package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Escape-sequence diagnostics were silently swallowed in release builds by the removed
 * {@code LOG_ESCAPE_SEQUENCES} flag. These tests pin that unknown/unimplemented sequences now reach
 * the client's warning channel.
 */
public class TerminalEmulatorLoggingTest extends TestCase {

    private static final class RecordingClient implements TerminalSessionClient {
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override public void onTextChanged(@NonNull TerminalSession changedSession) { }
        @Override public void onTitleChanged(@NonNull TerminalSession changedSession) { }
        @Override public void onSessionFinished(@NonNull TerminalSession finishedSession) { }
        @Override public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) { }
        @Override public void onPasteTextFromClipboard(@Nullable TerminalSession session) { }
        @Override public void onBell(@NonNull TerminalSession session) { }
        @Override public void onColorsChanged(@NonNull TerminalSession session) { }
        @Override public void onTerminalCursorStateChange(boolean state) { }
        @Override public void setTerminalShellPid(@NonNull TerminalSession session, int pid) { }
        @Override public Integer getTerminalCursorStyle() { return null; }
        @Override public void logError(String tag, String message) { errors.add(message); }
        @Override public void logWarn(String tag, String message) { warnings.add(message); }
        @Override public void logInfo(String tag, String message) { }
        @Override public void logDebug(String tag, String message) { }
        @Override public void logVerbose(String tag, String message) { }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { }
        @Override public void logStackTrace(String tag, Exception e) { }
    }

    private TerminalEmulator newEmulator(RecordingClient client) {
        return new TerminalEmulator(new TerminalTestCase.MockTerminalOutput(), 80, 24, 13, 15, 48, client);
    }

    public void testUnknownDecSetModeLogsWarning() {
        RecordingClient client = new RecordingClient();
        TerminalEmulator emulator = newEmulator(client);
        feed(emulator, "\033[?9999h");
        assertFalse("Unknown DECSET mode should log a warning", client.warnings.isEmpty());
        assertTrue("Warning should name the mode", client.warnings.get(0).contains("9999"));
    }

    public void testInvalidSgrColorIndexLogsWarning() {
        RecordingClient client = new RecordingClient();
        TerminalEmulator emulator = newEmulator(client);
        feed(emulator, "\033[38;5;999m");
        assertFalse("Invalid SGR color index should log a warning", client.warnings.isEmpty());
    }

    private static void feed(TerminalEmulator emulator, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }
}
