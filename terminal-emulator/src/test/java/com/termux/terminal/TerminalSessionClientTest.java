package com.termux.terminal;

import java.lang.reflect.Method;

import junit.framework.TestCase;

/**
 * The published {@link TerminalSessionClient} surface must stay source-compatible: a plugin that
 * implements the interface directly only implements the methods that existed when it was written.
 * Every callback added later is therefore a {@code default} method with a working no-op (or null)
 * body, so the plugin keeps compiling and behaves as before.
 */
public class TerminalSessionClientTest extends TestCase {

    public void testPasteCallbackIsDefault() throws Exception {
        assertTrue(method("onPasteTextFromClipboard", TerminalSession.class).isDefault());
    }

    public void testCursorStateCallbackIsDefault() throws Exception {
        assertTrue(method("onTerminalCursorStateChange", boolean.class).isDefault());
    }

    public void testShellPidCallbackIsDefault() throws Exception {
        assertTrue(method("setTerminalShellPid", TerminalSession.class, int.class).isDefault());
    }

    public void testCursorStyleQueryIsDefault() throws Exception {
        assertTrue(method("getTerminalCursorStyle").isDefault());
    }

    private static Method method(String name, Class<?>... parameterTypes) throws Exception {
        return TerminalSessionClient.class.getMethod(name, parameterTypes);
    }

}
