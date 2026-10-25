package com.termux.shared.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** JVM tests for the pure helpers in {@link ShellUtils}. */
public class ShellUtilsTest {

    @Test
    public void testSetupShellCommandArguments() {
        assertArrayEquals(new String[] { "sh" }, ShellUtils.setupShellCommandArguments("sh", null));
        assertArrayEquals(new String[] { "sh", "-c" }, ShellUtils.setupShellCommandArguments("sh", new String[] { "-c" }));
        assertArrayEquals(
            new String[] { "sh", "-c", "echo hi" },
            ShellUtils.setupShellCommandArguments("sh", new String[] { "-c", "echo hi" }));
    }

    @Test
    public void testGetExecutableBasename() {
        assertEquals("sh", ShellUtils.getExecutableBasename("/system/bin/sh"));
        assertEquals("sh", ShellUtils.getExecutableBasename("sh"));
        assertNull(ShellUtils.getExecutableBasename(null));
    }
}
