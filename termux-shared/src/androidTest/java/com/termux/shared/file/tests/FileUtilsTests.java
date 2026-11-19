package com.termux.shared.file.tests;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.shared.errors.Errno;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.file.FileUtilsErrno;
import com.termux.shared.logger.Logger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

/**
 * Instrumented tests for {@link FileUtils}. Every test gets its own empty root directory, so the
 * order they run in does not matter and a failure leaves the others meaningful.
 */
@RunWith(AndroidJUnit4.class)
public class FileUtilsTests {

    private static final String LOG_TAG = "FileUtilsTests";

    private String testRootDirectoryPath;

    /**
     * - dir1
     *  - sub_dir1
     *  - sub_reg1
     *  - sub_sym1 (absolute symlink to dir2)
     *  - sub_sym2 (copy of sub_sym1 for symlink to dir2)
     * - dir2
     *  - sub_reg1
     *  - sub_reg2 (copy of dir2/sub_reg1)
     * - dir3 (copy of dir2)
     * - dir4 (moved from dir3)
     */
    private String dir1Path;
    private String dir1SubDir1Path;
    private String dir1SubReg1Path;
    private String dir1SubSym1Path;
    private String dir1SubSym2Path;
    private String dir2Path;
    private String dir2SubReg1Path;
    private String dir2SubReg2Path;
    private String dir3Path;
    private String dir4Path;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        testRootDirectoryPath = context.getFilesDir().getAbsolutePath() + "/FileUtilsTests";
        Logger.logInfo(LOG_TAG, "testRootDirectoryPath: \"" + testRootDirectoryPath + "\"");

        assertEqual("FileUtilsTests directory path is not a canonical path", testRootDirectoryPath,
            FileUtils.getCanonicalPath(testRootDirectoryPath, null));
        assertEqual("Failed to create the test root directory file", null,
            FileUtils.clearDirectory("testRootDirectoryPath", testRootDirectoryPath));
        if (!FileUtils.directoryFileExists(testRootDirectoryPath, false))
            throwException("The test root directory file does not exist after creation");

        dir1Path = testRootDirectoryPath + "/dir1";
        dir1SubDir1Path = dir1Path + "/sub_dir1";
        dir1SubReg1Path = dir1Path + "/sub_reg1";
        dir1SubSym1Path = dir1Path + "/sub_sym1";
        dir1SubSym2Path = dir1Path + "/sub_sym2";
        dir2Path = testRootDirectoryPath + "/dir2";
        dir2SubReg1Path = dir2Path + "/sub_reg1";
        dir2SubReg2Path = dir2Path + "/sub_reg2";
        dir3Path = testRootDirectoryPath + "/dir3";
        dir4Path = testRootDirectoryPath + "/dir4";
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteDirectoryFile("testRootDirectoryPath", testRootDirectoryPath, true);
    }

    @Test
    public void testCreateDirectoriesAndFiles() throws Exception {
        createDir1WithSymlink();
        createDir2();

        if (!FileUtils.directoryFileExists(dir1SubDir1Path, false))
            throwException("dir1/sub_dir1 does not exist after creation");
        if (!FileUtils.regularFileExists(dir1SubReg1Path, false))
            throwException("dir1/sub_reg1 does not exist after creation");
        if (!FileUtils.symlinkFileExists(dir1SubSym1Path))
            throwException("dir1/sub_sym1 does not exist after creation");
    }

    @Test
    public void testCopySymlinkPointsAtTheTarget() throws Exception {
        createDir1WithSymlink();
        createDir2();

        assertEqual("Failed to copy dir1/sub_sym1 to dir1/sub_sym2", null,
            FileUtils.copySymlinkFile("dir1/sub_sym2", dir1SubSym1Path, dir1SubSym2Path, false));
        if (!FileUtils.symlinkFileExists(dir1SubSym2Path))
            throwException("dir1/sub_sym2 does not exist after copying it from dir1/sub_sym1");
        if (!new File(dir1SubSym2Path).getCanonicalPath().equals(dir2Path))
            throwException("dir1/sub_sym2 does not point to dir2");
    }

    @Test
    public void testRegularFileReadWrite() throws Exception {
        createDir2();

        assertEqual("Failed to write with append mode false", null,
            FileUtils.writeTextToFile("dir2/sub_reg1", dir2SubReg1Path, Charset.defaultCharset(), "line1", false));
        if (!FileUtils.regularFileExists(dir2SubReg1Path, false))
            throwException("dir2/sub_reg1 does not exist after writing to it with append mode false");

        assertEqual("Failed to write with append mode true", null,
            FileUtils.writeTextToFile("dir2/sub_reg1", dir2SubReg1Path, Charset.defaultCharset(), "\nline2", true));

        StringBuilder dataStringBuilder = new StringBuilder();
        assertEqual("Failed to read dir2/sub_reg1", null,
            FileUtils.readTextFromFile("dir2/sub_reg1", dir2SubReg1Path, Charset.defaultCharset(), dataStringBuilder, false));
        assertEqual("The data read is not as expected", "line1\nline2", dataStringBuilder.toString());

        assertEqual("Failed to copy dir2/sub_reg1 to dir2/sub_reg2", null,
            FileUtils.copyRegularFile("dir2/sub_reg2", dir2SubReg1Path, dir2SubReg2Path, false));
        if (!FileUtils.regularFileExists(dir2SubReg2Path, false))
            throwException("dir2/sub_reg2 does not exist after copying it from dir2/sub_reg1");
    }

    @Test
    public void testCopyAndMoveDirectory() throws Exception {
        createDir2();

        assertEqual("Failed to copy dir2 to dir3", null,
            FileUtils.copyDirectoryFile("dir3", dir2Path, dir3Path, false));
        if (!FileUtils.directoryFileExists(dir3Path, false))
            throwException("dir3 does not exist after copying it from dir2");

        // A second copy must overwrite whatever the first one left behind instead of failing.
        assertEqual("Failed to copy dir2 to dir3 again", null,
            FileUtils.copyDirectoryFile("dir3", dir2Path, dir3Path, false));
        if (!FileUtils.directoryFileExists(dir3Path, false))
            throwException("dir3 does not exist after copying it from dir2 again");

        assertEqual("Failed to move dir3 to dir4", null,
            FileUtils.moveDirectoryFile("dir4", dir3Path, dir4Path, false));
        if (!FileUtils.directoryFileExists(dir4Path, false))
            throwException("dir4 does not exist after moving it from dir3");
    }

    @Test
    public void testDeletingLinksAndDirectoriesKeepsTheirTargets() throws Exception {
        createDir1WithSymlink();
        createDir2();

        assertEqual("Failed to copy dir1/sub_sym1 to dir1/sub_sym2", null,
            FileUtils.copySymlinkFile("dir1/sub_sym2", dir1SubSym1Path, dir1SubSym2Path, false));

        assertEqual("Failed to delete dir1/sub_sym2", null,
            FileUtils.deleteSymlinkFile("dir1/sub_sym2", dir1SubSym2Path, false));
        if (FileUtils.fileExists(dir1SubSym2Path, false))
            throwException("dir1/sub_sym2 still exists after deletion");
        // Deleting a symlink must not delete what it points at.
        if (!FileUtils.directoryFileExists(dir2Path, false))
            throwException("dir2 was deleted by the deletion of dir1/sub_sym2");

        assertEqual("Failed to delete dir1", null,
            FileUtils.deleteDirectoryFile("dir1", dir1Path, false));
        if (FileUtils.fileExists(dir1Path, false))
            throwException("dir1 still exists after deletion");
        // Deleting dir1 must not delete the target of the symlink it contained.
        if (!FileUtils.directoryFileExists(dir2Path, false))
            throwException("dir2 was deleted by the deletion of dir1");
    }

    @Test
    public void testValidateDirectoryFileEmptyOrOnlyContainsSpecificFiles() throws Exception {
        String dir1SubDir2Path = dir1Path + "/sub_dir2";
        String dir1SubDir3Path = dir1Path + "/sub_dir3";
        String dir1SubDir3SubReg1Path = dir1SubDir3Path + "/sub_reg1";

        assertEqual("Failed to create dir1", null, FileUtils.createDirectoryFile("dir1", dir1Path));
        List<String> ignoredSubFilePaths = Arrays.asList(dir1SubDir2Path, dir1SubDir3SubReg1Path);

        assertEqual("An empty dir1 must validate", null,
            FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles("dir1", dir1Path, ignoredSubFilePaths, false));

        // The parent directory of an ignored but missing file exists: still a non-empty directory.
        assertEqual("Failed to create dir1/sub_dir3", null,
            FileUtils.createDirectoryFile("dir1/sub_dir3", dir1SubDir3Path));
        assertErrnoEqual("dir1 must be non-empty when the parent of a missing ignored file exists",
            FileUtilsErrno.ERRNO_NON_EMPTY_DIRECTORY_FILE,
            FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles("dir1", dir1Path, ignoredSubFilePaths, false));

        assertEqual("Failed to write dir1/sub_dir3/sub_reg1", null,
            FileUtils.writeTextToFile("dir1/sub_dir3/sub_reg1", dir1SubDir3SubReg1Path, Charset.defaultCharset(), "line1", false));
        assertEqual("An existing ignored regular file must validate", null,
            FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles("dir1", dir1Path, ignoredSubFilePaths, false));

        assertEqual("Failed to create dir1/sub_dir2", null,
            FileUtils.createDirectoryFile("dir1/sub_dir2", dir1SubDir2Path));
        assertEqual("An existing ignored directory must validate", null,
            FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles("dir1", dir1Path, ignoredSubFilePaths, false));

        assertEqual("Failed to create dir1/sub_dir1", null,
            FileUtils.createDirectoryFile("dir1/sub_dir1", dir1SubDir1Path));
        assertErrnoEqual("dir1 must be non-empty when a non-ignored directory exists",
            FileUtilsErrno.ERRNO_NON_EMPTY_DIRECTORY_FILE,
            FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles("dir1", dir1Path, ignoredSubFilePaths, false));
    }

    @Test
    public void testValidateDirectoryFileAcceptsAPathEqualToItsParent() throws Exception {
        // A directory path is "in" its own parent; without that, a missing path equal to the parent
        // would not be created and the call would report it as not found.
        String missingDirPath = testRootDirectoryPath + "/missing_dir";
        assertEqual("A path equal to its parent directory must be created", null,
            FileUtils.validateDirectoryFileExistenceAndPermissions("missing_dir", missingDirPath, missingDirPath,
                true, null, false, false, false, false));
        if (!FileUtils.directoryFileExists(missingDirPath, false))
            throwException("missing_dir does not exist after validation created it");
    }

    @Test
    public void testReadSerializableObjectFromFile() throws Exception {
        String filePath = testRootDirectoryPath + "/serializable.txt";
        assertEqual("Failed to write the serializable object", null,
            FileUtils.writeSerializableObjectToFile("serializable", filePath, "hello"));

        FileUtils.ReadSerializableObjectResult result =
            FileUtils.readSerializableObjectFromFile("serializable", filePath, String.class, false);
        assertEqual("Failed to read the serializable object", null, result.error);
        assertEqual("The serializable object is not as expected", "hello", (String) result.serializableObject);
    }

    @Test
    public void testGetFileType() throws Exception {
        FileUtils.getFileType("/dev/ptmx", false);
        FileUtils.getFileType("/dev/null", false);
    }

    private void createDir1WithSymlink() throws Exception {
        assertEqual("Failed to create dir1", null, FileUtils.createDirectoryFile("dir1", dir1Path));
        assertEqual("Failed to create dir1/sub_dir1", null, FileUtils.createDirectoryFile("dir1/sub_dir1", dir1SubDir1Path));
        assertEqual("Failed to create dir1/sub_reg1", null, FileUtils.createRegularFile("dir1/sub_reg1", dir1SubReg1Path));
        assertEqual("Failed to create dir1/sub_sym1", null,
            FileUtils.createSymlinkFile("dir1/sub_sym1", dir2Path, dir1SubSym1Path));
    }

    private void createDir2() throws Exception {
        assertEqual("Failed to create dir2", null, FileUtils.createDirectoryFile("dir2", dir2Path));
    }

    public static void assertEqual(@NonNull final String message, final String expected, final Error actual) throws Exception {
        String actualString = actual != null ? actual.getMessage() : null;
        if (!equalsRegardingNull(expected, actualString))
            throwException(message + "\nexpected: \"" + expected + "\"\nactual: \"" + actualString + "\"\nFull Error:\n" + (actual != null ? actual.toString() : ""));
    }

    public static void assertEqual(@NonNull final String message, final String expected, final String actual) throws Exception {
        if (!equalsRegardingNull(expected, actual))
            throwException(message + "\nexpected: \"" + expected + "\"\nactual: \"" + actual + "\"");
    }

    public static void assertErrnoEqual(@NonNull final String message, final Errno expected, final Error actual) throws Exception {
        if ((expected == null && actual != null) || (expected != null && !expected.equalsErrorTypeAndCode(actual)))
            throwException(message + "\nexpected: \"" + expected + "\"\nactual: \"" + actual + "\"\nFull Error:\n" + (actual != null ? actual.toString() : ""));
    }

    private static boolean equalsRegardingNull(final String expected, final String actual) {
        if (expected == null) {
            return actual == null;
        }

        return expected.equals(actual);
    }

    public static void throwException(@NonNull final String message) throws Exception {
        throw new Exception(message);
    }

}
