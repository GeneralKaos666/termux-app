package com.termux.filepicker;

import android.database.Cursor;
import android.provider.DocumentsContract.Document;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The provider is mostly a thin shell over {@link File}, but its document-id checks and its search
 * walk are its own: a walk that trusts a plain string prefix, or that does not tolerate an
 * unlistable directory, is a defect the framework will surface as a crash or as a wrong answer.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TermuxDocumentsProviderTest {

    private final TermuxDocumentsProvider provider = new TermuxDocumentsProvider();
    private File root;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("documents-provider-test").toFile();
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursively(root);
    }

    @Test
    public void testChildDocumentsListsDirectChildren() throws Exception {
        file("plain.txt");
        dir("sub");

        List<String> names = names(provider.queryChildDocuments(root.getAbsolutePath(), null, (String) null));

        assertTrue(names.toString(), names.contains("plain.txt"));
        assertTrue(names.toString(), names.contains("sub"));
    }

    @Test
    public void testChildDocumentsOfAFileIsEmptyInsteadOfCrashing() throws Exception {
        File file = file("plain.txt");

        Cursor cursor = provider.queryChildDocuments(file.getAbsolutePath(), null, (String) null);

        assertEquals("a file has no children", 0, cursor.getCount());
    }

    @Test
    public void testChildDocumentsOfAnUnlistableDirectoryIsEmptyInsteadOfCrashing() throws Exception {
        File locked = dir("locked");
        file(locked, "hidden.txt");
        if (!lock(locked)) return;

        try {
            Cursor cursor = provider.queryChildDocuments(locked.getAbsolutePath(), null, (String) null);

            assertEquals(0, cursor.getCount());
        } finally {
            unlock(locked);
        }
    }

    @Test
    public void testIsChildDocumentAcceptsDescendants() {
        String parent = root.getAbsolutePath();
        String child = new File(root, "notes.txt").getAbsolutePath();
        String grandChild = new File(new File(root, "sub"), "notes.txt").getAbsolutePath();

        assertTrue(provider.isChildDocument(parent, child));
        assertTrue(provider.isChildDocument(parent, grandChild));
    }

    @Test
    public void testIsChildDocumentAcceptsTheParentItself() {
        assertTrue(provider.isChildDocument(root.getAbsolutePath(), root.getAbsolutePath()));
    }

    @Test
    public void testIsChildDocumentRejectsASiblingSharingThePrefix() {
        String parent = root.getAbsolutePath();
        String sibling = new File(parent + "-sibling", "notes.txt").getAbsolutePath();

        assertFalse("a path that only shares the parent's prefix is not inside it",
            provider.isChildDocument(parent, sibling));
    }

    @Test
    public void testSearchMatchesRegardlessOfQueryCase() throws Exception {
        file("Notes.TXT");
        file("other.txt");

        List<String> names = names(provider.querySearchDocuments(root.getAbsolutePath(), "NOTES", null));

        assertEquals("[Notes.TXT]", names.toString());
    }

    @Test
    public void testSearchWalksSubdirectories() throws Exception {
        File sub = dir("sub");
        file(sub, "nested.txt");

        List<String> names = names(provider.querySearchDocuments(root.getAbsolutePath(), "nested", null));

        assertEquals("[nested.txt]", names.toString());
    }

    @Test
    public void testSearchSkipsAnUnlistableDirectory() throws Exception {
        File locked = dir("locked");
        file(locked, "hidden.txt");
        if (!lock(locked)) return;

        try {
            Cursor cursor = provider.querySearchDocuments(root.getAbsolutePath(), "hidden", null);

            assertEquals(0, cursor.getCount());
        } finally {
            unlock(locked);
        }
    }

    @Test
    public void testDocumentTypeOfADirectory() throws Exception {
        assertEquals(Document.MIME_TYPE_DIR, provider.getDocumentType(dir("sub").getAbsolutePath()));
    }

    @Test
    public void testDocumentTypeOfAnUnknownExtensionIsOctetStream() throws Exception {
        assertEquals("application/octet-stream",
            provider.getDocumentType(file("archive.unknownextension").getAbsolutePath()));
    }

    @Test
    public void testCreateDocumentAvoidsOverwritingAnExistingFile() throws Exception {
        String first = provider.createDocument(root.getAbsolutePath(), "text/plain", "notes.txt");
        String second = provider.createDocument(root.getAbsolutePath(), "text/plain", "notes.txt");

        assertTrue(new File(first).isFile());
        assertTrue(new File(second).isFile());
        assertFalse("the second create must pick another name", first.equals(second));
        assertTrue(new File(second).getName(), new File(second).getName().startsWith("notes.txt ("));
    }

    @Test
    public void testCreateDocumentWithTheDirectoryMimeTypeMakesADirectory() throws Exception {
        String path = provider.createDocument(root.getAbsolutePath(), Document.MIME_TYPE_DIR, "sub");

        assertTrue(new File(path).isDirectory());
    }

    private File file(String name) throws IOException {
        return file(root, name);
    }

    private File file(File parent, String name) throws IOException {
        File file = new File(parent, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{'x'});
        }
        return file;
    }

    private File dir(String name) {
        File dir = new File(root, name);
        assertTrue("could not create " + dir, dir.mkdir());
        return dir;
    }

    private static List<String> names(Cursor cursor) {
        List<String> names = new ArrayList<>();
        try {
            int column = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME);
            while (cursor.moveToNext()) names.add(cursor.getString(column));
        } finally {
            cursor.close();
        }
        return names;
    }

    /** @return whether the directory really became unlistable (a privileged test run may ignore it). */
    private static boolean lock(File dir) {
        dir.setReadable(false, false);
        dir.setExecutable(false, false);
        return dir.listFiles() == null;
    }

    private static void unlock(File dir) {
        dir.setReadable(true, false);
        dir.setExecutable(true, false);
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("could not delete " + file);
    }

}
