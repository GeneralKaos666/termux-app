package com.termux.shared.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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

    @Test
    public void testIsDirectChildPath() throws IOException {
        File dir = Files.createTempDirectory("direct-child").toFile();
        assertTrue(FileUtils.isDirectChildPath(dir.getAbsolutePath(), new File(dir, "a.bin").getAbsolutePath()));
        assertFalse(FileUtils.isDirectChildPath(dir.getAbsolutePath(),
            new File(dir, "../escape.bin").getAbsolutePath()));
        assertFalse(FileUtils.isDirectChildPath(dir.getAbsolutePath(),
            new File(dir.getAbsolutePath() + "2", "a.bin").getAbsolutePath()));
        assertFalse(FileUtils.isDirectChildPath(dir.getAbsolutePath(), dir.getAbsolutePath()));
        assertFalse(FileUtils.isDirectChildPath(null, dir.getAbsolutePath()));
        assertFalse(FileUtils.isDirectChildPath(dir.getAbsolutePath(), null));
    }

    @Test
    public void testCollectFilesOlderThanSkipsDirectorySymlinks() throws Exception {
        File root = Files.createTempDirectory("old-files").toFile();
        File outside = Files.createTempDirectory("outside").toFile();
        File victim = new File(outside, "keep.txt");
        assertTrue(victim.createNewFile());
        assertTrue(victim.setLastModified(0));
        File mine = new File(root, "mine.txt");
        assertTrue(mine.createNewFile());
        assertTrue(mine.setLastModified(0));
        File sub = new File(root, "sub");
        assertTrue(sub.mkdir());
        File nested = new File(sub, "nested.txt");
        assertTrue(nested.createNewFile());
        assertTrue(nested.setLastModified(0));
        Files.createSymbolicLink(new File(root, "link").toPath(), outside.toPath());

        List<File> collected = new ArrayList<>();
        FileUtils.collectFilesOlderThan(root, System.currentTimeMillis(), file -> file.isDirectory(), collected);

        assertTrue(collected.contains(mine));
        assertTrue(collected.contains(nested));
        assertFalse("a file reached through a symlink must not be collected", collected.contains(victim));
        for (File file : collected)
            assertFalse(file.getAbsolutePath(), file.getAbsolutePath().startsWith(new File(root, "link").getAbsolutePath()));
    }
}
