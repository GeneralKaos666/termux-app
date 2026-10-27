package com.termux.shared.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** JVM tests for the pure path and file-name helpers extracted into {@link FilePaths}. */
public class FilePathsTest {

    @Test
    public void testNormalizePath() {
        assertNull(FilePaths.normalizePath(null));
        assertEquals("", FilePaths.normalizePath(""));
        assertEquals("data", FilePaths.normalizePath("data"));
        assertEquals("/data", FilePaths.normalizePath("/data"));
        assertEquals("/data", FilePaths.normalizePath("/data/"));
        assertEquals("/data", FilePaths.normalizePath("//data//"));
        assertEquals("/data/file", FilePaths.normalizePath("/data/./file"));
        assertEquals("/data/file", FilePaths.normalizePath("/data/file/"));
    }

    @Test
    public void testSanitizeFileName() {
        assertNull(FilePaths.sanitizeFileName(null, false, false));
        assertEquals("file", FilePaths.sanitizeFileName("file", false, false));
        assertEquals("fi_le", FilePaths.sanitizeFileName("fi/le", false, false));
        assertEquals("fi_le", FilePaths.sanitizeFileName("fi\\le", false, false));
        assertEquals("fi_le", FilePaths.sanitizeFileName("fi:le", false, false));
        assertEquals("fi_le", FilePaths.sanitizeFileName("fi*le", false, false));
        assertEquals("fi_le", FilePaths.sanitizeFileName("fi?le", false, false));
        assertEquals("fi_le", FilePaths.sanitizeFileName("fi\"le", false, false));
        assertEquals("fi_le", FilePaths.sanitizeFileName("fi<le", false, false));
        assertEquals("fi_le", FilePaths.sanitizeFileName("fi>le", false, false));
        assertEquals("fi_le", FilePaths.sanitizeFileName("fi|le", false, false));
        assertEquals("file", FilePaths.sanitizeFileName("FILE", false, true));
        assertEquals("fi_le", FilePaths.sanitizeFileName("fi le", true, false));
        assertEquals("fi le", FilePaths.sanitizeFileName("fi le", false, false));
    }
}
