package com.termux.shared.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** JVM tests for the pure path helpers in {@link FileUtils}. */
public class FileUtilsTest {

    @Test
    public void testGetFileBasename() {
        assertNull(FileUtils.getFileBasename(null));
        assertNull(FileUtils.getFileBasename(""));
        assertEquals("file.txt", FileUtils.getFileBasename("file.txt"));
        assertEquals("file.txt", FileUtils.getFileBasename("/data/data/com.termux/file.txt"));
        assertEquals("file.txt", FileUtils.getFileBasename("dir/sub/file.txt"));
        assertEquals("dir", FileUtils.getFileBasename("/data/dir"));
        assertEquals("", FileUtils.getFileBasename("/data/data/com.termux/"));
    }

    @Test
    public void testGetFileDirname() {
        assertNull(FileUtils.getFileDirname(null));
        assertNull(FileUtils.getFileDirname(""));
        assertNull(FileUtils.getFileDirname("file.txt"));
        assertEquals("/data/data/com.termux", FileUtils.getFileDirname("/data/data/com.termux/file.txt"));
        assertEquals("/data/data/com.termux", FileUtils.getFileDirname("/data/data/com.termux/"));
    }

    @Test
    public void testGetFileBasenameWithoutExtension() {
        assertNull(FileUtils.getFileBasenameWithoutExtension(null));
        assertNull(FileUtils.getFileBasenameWithoutExtension(""));
        assertEquals("file", FileUtils.getFileBasenameWithoutExtension("file.txt"));
        assertEquals("file", FileUtils.getFileBasenameWithoutExtension("/path/file.txt"));
        assertEquals("file", FileUtils.getFileBasenameWithoutExtension("file"));
        assertEquals("file.tar", FileUtils.getFileBasenameWithoutExtension("file.tar.gz"));
    }
}
