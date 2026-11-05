package com.termux.terminal;

import junit.framework.TestCase;

/**
 * The parts of {@link TerminalSession} that do not need a real pty: the UTF-8 encoding of
 * {@link TerminalSession#writeCodePoint(boolean, int)} and the state getters before the process has
 * started. The process, threads and main-thread handler stay device-only.
 */
public class TerminalSessionTest extends TestCase {

    /** A session whose pty is considered live, so {@link TerminalSession#write} reaches its queue. */
    private static TerminalSession startedSession() {
        TerminalSession session = new TerminalSession("/bin/sh", "/", new String[0], new String[0], 2000, null);
        session.mShellPid = 1234;
        return session;
    }

    /** @return the bytes the last {@link TerminalSession#writeCodePoint} call queued for the process. */
    private static byte[] takeWritten(TerminalSession session) {
        byte[] sink = new byte[8];
        int read = session.mTerminalToProcessIOQueue.read(sink, false);
        byte[] written = new byte[Math.max(read, 0)];
        System.arraycopy(sink, 0, written, 0, written.length);
        return written;
    }

    private static void assertWritten(int[] expected, TerminalSession session) {
        byte[] actual = takeWritten(session);
        assertEquals("byte count for " + describe(expected), expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("byte " + i + " of " + describe(expected), (byte) expected[i], actual[i]);
        }
    }

    private static String describe(int[] bytes) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) builder.append(' ');
            builder.append(String.format("%02x", bytes[i]));
        }
        return builder.append(']').toString();
    }

    public void testSingleByteCodePoints() {
        TerminalSession session = startedSession();

        session.writeCodePoint(false, 'A');
        assertWritten(new int[]{0x41}, session);

        session.writeCodePoint(false, 0x7F);
        assertWritten(new int[]{0x7F}, session);
    }

    public void testTwoByteCodePoints() {
        TerminalSession session = startedSession();

        session.writeCodePoint(false, 0x80);
        assertWritten(new int[]{0xC2, 0x80}, session);

        session.writeCodePoint(false, 0x7FF);
        assertWritten(new int[]{0xDF, 0xBF}, session);
    }

    public void testThreeByteCodePoints() {
        TerminalSession session = startedSession();

        session.writeCodePoint(false, 0x800);
        assertWritten(new int[]{0xE0, 0xA0, 0x80}, session);

        session.writeCodePoint(false, 0x20AC);
        assertWritten(new int[]{0xE2, 0x82, 0xAC}, session);

        session.writeCodePoint(false, 0xFFFF);
        assertWritten(new int[]{0xEF, 0xBF, 0xBF}, session);
    }

    public void testFourByteCodePoints() {
        TerminalSession session = startedSession();

        session.writeCodePoint(false, 0x10000);
        assertWritten(new int[]{0xF0, 0x90, 0x80, 0x80}, session);

        session.writeCodePoint(false, 0x1F600);
        assertWritten(new int[]{0xF0, 0x9F, 0x98, 0x80}, session);

        session.writeCodePoint(false, 0x10FFFF);
        assertWritten(new int[]{0xF4, 0x8F, 0xBF, 0xBF}, session);
    }

    public void testEscapeIsPrependedToTheEncodedCodePoint() {
        TerminalSession session = startedSession();

        session.writeCodePoint(true, 'A');
        assertWritten(new int[]{0x1B, 0x41}, session);
    }

    public void testSurrogateRangeIsRejected() {
        TerminalSession session = startedSession();

        assertRejected(session, 0xD800);
        assertRejected(session, 0xDFFF);
    }

    public void testOutOfRangeCodePointsAreRejected() {
        TerminalSession session = startedSession();

        assertRejected(session, 0x110000);
        assertRejected(session, Integer.MAX_VALUE);
    }

    public void testNegativeCodePointIsRejected() {
        TerminalSession session = startedSession();

        assertRejected(session, -1);
        assertRejected(session, Integer.MIN_VALUE);
    }

    public void testNothingIsWrittenToAStoppedSession() {
        TerminalSession session = new TerminalSession("/bin/sh", "/", new String[0], new String[0], 2000, null);

        session.writeCodePoint(false, 'A');

        assertEquals("a session without a live pty must drop writes", 0, takeWritten(session).length);
    }

    public void testGettersBeforeTheProcessStarts() {
        TerminalSession session = new TerminalSession("/bin/sh", "/", new String[0], new String[0], 2000, null);

        assertNull("no emulator before the size is known", session.getEmulator());
        assertNull("no title before the emulator exists", session.getTitle());
        assertNull("no working directory without a pid", session.getCwd());
        assertEquals(0, session.getPid());
        assertEquals(0, session.getExitStatus());
        assertNotNull("every session gets a handle", session.mHandle);
    }

    public void testHandlesAreUnique() {
        TerminalSession first = new TerminalSession("/bin/sh", "/", new String[0], new String[0], 2000, null);
        TerminalSession second = new TerminalSession("/bin/sh", "/", new String[0], new String[0], 2000, null);

        assertFalse(first.mHandle.equals(second.mHandle));
    }

    private static void assertRejected(TerminalSession session, int codePoint) {
        try {
            session.writeCodePoint(false, codePoint);
            fail("code point " + codePoint + " should be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        assertEquals("a rejected code point must not reach the process", 0, takeWritten(session).length);
    }

}
