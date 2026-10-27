package com.termux.shared.file;

import androidx.annotation.Nullable;

import com.termux.shared.data.DataUtils;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Pure path and file-name helpers extracted from {@link FileUtils}. These operate on path strings
 * only (with the exception of {@link #getCanonicalPath} and {@link #isPathInDirPaths}, which resolve
 * through {@link File#getCanonicalPath()}); the filesystem-mutating operations stay on
 * {@link FileUtils}.
 */
final class FilePaths {

    private FilePaths() {
    }

    /**
     * Get the canonical path for {@code path}.
     *
     * If an exception is raised to get the canonical path, then absolute path is returned.
     *
     * @param path The {@code path} to convert.
     * @param prefixForNonAbsolutePath Optional prefix path to prefix before non-absolute paths. This
     *                                 can be set to {@code null} if non-absolute paths should
     *                                 be prefixed with "/". The call to {@link File#getCanonicalPath()}
     *                                 will automatically do this anyways.
     * @return Returns the {@code canonical path}.
     */
    public static String getCanonicalPath(String path, final String prefixForNonAbsolutePath) {        if (path == null) path = "";

        String absolutePath;

        // If path is already an absolute path
        if (path.startsWith("/")) {
            absolutePath = path;
        } else {
            if (prefixForNonAbsolutePath != null)
                absolutePath = prefixForNonAbsolutePath + "/" + path;
            else
                absolutePath = "/" + path;
        }

        try {
            return new File(absolutePath).getCanonicalPath();
        } catch(Exception e) {
        }

        return absolutePath;
    }

    /**
     * Removes one or more forward slashes "//" with single slash "/"
     * Removes "./"
     * Removes trailing forward slash "/"
     *
     * @param path The {@code path} to convert.
     * @return Returns the {@code normalized path}.
     */
    @Nullable
    public static String normalizePath(String path) {
        if (path == null) return null;

        path = path.replaceAll("/+", "/");
        path = path.replaceAll("\\./", "");

        if (path.endsWith("/")) {
            path = path.replaceAll("/+$", "");
        }

        return path;
    }

    /**
     * Convert special characters `\/:*?"<>|` to underscore.
     *
     * @param fileName The name to sanitize.
     * @param sanitizeWhitespaces If set to {@code true}, then white space characters ` \t\n` will be
     *                            converted.
     * @param toLower If set to {@code true}, then file name will be converted to lower case.
     * @return Returns the {@code sanitized name}.
     */
    public static String sanitizeFileName(String fileName, boolean sanitizeWhitespaces, boolean toLower) {
        if (fileName == null) return null;

        if (sanitizeWhitespaces)
            fileName = fileName.replaceAll("[\\\\/:*?\"<>| \t\n]", "_");
        else
            fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");

        if (toLower)
            return fileName.toLowerCase();
        else
            return fileName;
    }

    /**
     * Determines whether path is in {@code dirPath}. The {@code dirPath} is not canonicalized and
     * only normalized.
     *
     * @param path The {@code path} to check.
     * @param dirPath The {@code directory path} to check in.
     * @param ensureUnder If set to {@code true}, then it will be ensured that {@code path} is
     *                    under the directory and does not equal it.
     * @return Returns {@code true} if path in {@code dirPath}, otherwise returns {@code false}.
     */
    public static boolean isPathInDirPath(String path, final String dirPath, final boolean ensureUnder) {
        return isPathInDirPaths(path, Collections.singletonList(dirPath), ensureUnder);
    }

    /**
     * Determines whether path is in one of the {@code dirPaths}. The {@code dirPaths} are not
     * canonicalized and only normalized.
     *
     * @param path The {@code path} to check.
     * @param dirPaths The {@code directory paths} to check in.
     * @param ensureUnder If set to {@code true}, then it will be ensured that {@code path} is
     *                    under the directories and does not equal it.
     * @return Returns {@code true} if path in {@code dirPaths}, otherwise returns {@code false}.
     */
    public static boolean isPathInDirPaths(String path, final List<String> dirPaths, final boolean ensureUnder) {
        if (path == null || path.isEmpty() || dirPaths == null || dirPaths.size() < 1) return false;

        try {
            path = new File(path).getCanonicalPath();
        } catch(Exception e) {
            return false;
        }

        boolean isPathInDirPaths;

        for (String dirPath : dirPaths) {
            String normalizedDirPath = normalizePath(dirPath);

            if (ensureUnder)
                isPathInDirPaths = !path.equals(normalizedDirPath) && path.startsWith(normalizedDirPath + "/");
            else
                isPathInDirPaths = path.startsWith(normalizedDirPath + "/");

            if (isPathInDirPaths) return true;
        }

        return false;
    }

    /**
     * Get file dirname for file at {@code filePath}.
     *
     * @param filePath The {@code path} for file.
     * @return Returns the file dirname if not {@code null}.
     */
    public static String getFileDirname(String filePath) {
        if (DataUtils.isNullOrEmpty(filePath)) return null;
        int lastSlash = filePath.lastIndexOf('/');
        return (lastSlash == -1) ? null : filePath.substring(0, lastSlash);
    }

    /**
     * Get file basename for file at {@code filePath}.
     *
     * @param filePath The {@code path} for file.
     * @return Returns the file basename if not {@code null}.
     */
    public static String getFileBasename(String filePath) {
        if (DataUtils.isNullOrEmpty(filePath)) return null;
        int lastSlash = filePath.lastIndexOf('/');
        return (lastSlash == -1) ? filePath : filePath.substring(lastSlash + 1);
    }

    /**
     * Get file basename for file at {@code filePath} without extension.
     *
     * @param filePath The {@code path} for file.
     * @return Returns the file basename without extension if not {@code null}.
     */
    public static String getFileBasenameWithoutExtension(String filePath) {
        String fileBasename = getFileBasename(filePath);
        if (DataUtils.isNullOrEmpty(fileBasename)) return null;
        int lastDot = fileBasename.lastIndexOf('.');
        return (lastDot == -1) ? fileBasename : fileBasename.substring(0, lastDot);
    }
}
