package com.termux.view;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;

/**
 * Unit tests for {@link TerminalView#getLinkUriAt}, the resolver behind the OSC 8 tap hit-test. It
 * mirrors the {@link LinkUris} contract: a cell either resolves to a dispatchable URI or to
 * {@code null}, never to an exception and never to an unfiltered URI.
 */
public class TerminalViewLinkHitTest extends TestCase {

    private static final String LINK_OPEN = "\033]8;;";
    private static final String LINK_CLOSE = "\033]8;;\007";

    /** Minimal session output stub; link resolution never reads session callbacks. */
    private static final class StubOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    private TerminalEmulator mTerminal;

    @Override
    protected void setUp() {
        mTerminal = new TerminalEmulator(new StubOutput(), 10, 3, 10, 20, 3, null);
    }

    private void enter(String string) {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        mTerminal.append(bytes, bytes.length);
    }

    public void testLinkCellResolvesToTheUri() {
        enter(LINK_OPEN + "http://example.com\007X" + LINK_CLOSE);
        assertEquals("http://example.com", TerminalView.getLinkUriAt(mTerminal, 0, 0));
    }

    public void testNonLinkCellIsNull() {
        enter(LINK_OPEN + "http://example.com\007X" + LINK_CLOSE);
        assertNull(TerminalView.getLinkUriAt(mTerminal, 0, 1));
    }

    public void testOutOfBoundsIsNull() {
        enter("X" + LINK_OPEN + "http://example.com\007Y" + LINK_CLOSE);
        assertNull(TerminalView.getLinkUriAt(mTerminal, -1, 0));
        assertNull(TerminalView.getLinkUriAt(mTerminal, mTerminal.mRows, 0));
        assertNull(TerminalView.getLinkUriAt(mTerminal, 0, -1));
        assertNull(TerminalView.getLinkUriAt(mTerminal, 0, mTerminal.mColumns));
    }

    public void testDisallowedSchemeIsNull() {
        enter(LINK_OPEN + "file:///etc/passwd\007X" + LINK_CLOSE);
        assertNull(TerminalView.getLinkUriAt(mTerminal, 0, 0));
    }

    public void testLinkEndsAtOsc8Reset() {
        enter(LINK_OPEN + "https://example.com\007X" + LINK_CLOSE + "Y");
        assertEquals("https://example.com", TerminalView.getLinkUriAt(mTerminal, 0, 0));
        assertNull(TerminalView.getLinkUriAt(mTerminal, 0, 1));
    }
}
