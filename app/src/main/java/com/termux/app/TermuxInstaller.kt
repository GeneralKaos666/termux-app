package com.termux.app

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.os.Build
import android.os.Environment
import android.system.Os
import android.util.Pair
import android.view.WindowManager
import com.termux.R
import com.termux.app.ui.firstrun.BootstrapStage
import com.termux.shared.android.PackageUtils
import com.termux.shared.errors.Error
import com.termux.shared.file.FileUtils
import com.termux.shared.interact.MessageDialogUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR
import com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
import com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR
import com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.crash.TermuxCrashUtils
import com.termux.shared.termux.file.TermuxFileUtils
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.zip.ZipInputStream

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 *
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 *
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 *
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 *
 * (4) The zip file is loaded from a shared library.
 *
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 *
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 *
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
class TermuxInstaller private constructor() {

    /** Receives the install stages; called from the install thread, so marshal to the main thread. */
    fun interface BootstrapProgressListener {
        fun onStage(stage: BootstrapStage)

        /**
         * Called when the install ends without a usable prefix, so a caller showing progress can
         * leave that state. A failure is reported on the main thread; the default is a no-op for
         * callers that do not show progress.
         */
        fun onFailure() {}
    }

    companion object {
        private const val LOG_TAG = "TermuxInstaller"

        /** Whether a bootstrap install is currently running in the background. */
        private var mIsBootstrapInstalling = false

        /** The most recent install completion callback; the running install invokes it when finished. */
        private var sWhenDone: Runnable? = null

        /** A listener that ignores every stage, used by callers that do not show progress. */
        private val NO_STAGE_LISTENER = BootstrapProgressListener { }

        /** Whether the bootstrap prefix directory is fully installed (exists and is not empty). */
        @JvmStatic
        fun isBootstrapInstalled(): Boolean {
            return FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true) &&
                !TermuxFileUtils.isTermuxPrefixDirectoryEmpty()
        }

        /** Clears the install guard and returns the most recent completion callback, if any. */
        private fun takeWhenDoneAndMarkFinished(): Runnable? {
            synchronized(TermuxInstaller::class.java) {
                mIsBootstrapInstalling = false
                val done = sWhenDone
                sWhenDone = null
                return done
            }
        }

        /** Performs bootstrap setup if necessary. */
        @JvmStatic
        fun setupBootstrapIfNeeded(activity: Activity, whenDone: Runnable) {
            setupBootstrapIfNeeded(activity, NO_STAGE_LISTENER, whenDone)
        }

        /** Performs bootstrap setup if necessary, reporting [listener] each stage it enters. */
        @JvmStatic
        fun setupBootstrapIfNeeded(
            activity: Activity,
            listener: BootstrapProgressListener,
            whenDone: Runnable,
        ) {
            val bootstrapErrorMessage: String
            val filesDirectoryAccessibleError: Error?

            // This will also call Context.getFilesDir(), which should ensure that termux files directory
            // is created if it does not already exist
            filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true)
            val isFilesDirectoryAccessible = filesDirectoryAccessibleError == null

            // Termux can only be run as the primary user (device owner) since only that
            // account has the expected file system paths. Verify that:
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
                bootstrapErrorMessage = activity.getString(
                    R.string.bootstrap_error_not_primary_user_message,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false),
                )
                Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: $isFilesDirectoryAccessible")
                Logger.logError(LOG_TAG, bootstrapErrorMessage)
                sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage)
                MessageDialogUtils.exitAppWithErrorMessage(
                    activity,
                    activity.getString(R.string.bootstrap_error_title),
                    bootstrapErrorMessage,
                )
                listener.onFailure()
                return
            }

            if (!isFilesDirectoryAccessible) {
                var message = Error.getMinimalErrorString(filesDirectoryAccessibleError)
                //noinspection SdCardPath
                if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                    TermuxConstants.TERMUX_FILES_DIR_PATH != activity.getFilesDir().getAbsolutePath().replace(Regex("^/data/user/0/"), "/data/data/")
                ) {
                    message += "\n\n" + activity.getString(
                        R.string.bootstrap_error_installed_on_portable_sd,
                        MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false),
                    )
                }

                Logger.logError(LOG_TAG, message)
                sendBootstrapCrashReportNotification(activity, message)
                MessageDialogUtils.showMessage(
                    activity,
                    activity.getString(R.string.bootstrap_error_title),
                    message, null,
                )
                listener.onFailure()
                return
            }

            // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
            if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
                if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                    Logger.logInfo(
                        LOG_TAG,
                        "The termux prefix directory \"$TERMUX_PREFIX_DIR_PATH\" exists but is empty or only contains specific unimportant files.",
                    )
                } else {
                    whenDone.run()
                    return
                }
            } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
                Logger.logInfo(
                    LOG_TAG,
                    "The termux prefix directory \"$TERMUX_PREFIX_DIR_PATH\" does not exist but another file exists at its destination.",
                )
            }

            synchronized(TermuxInstaller::class.java) {
                if (mIsBootstrapInstalling) {
                    // An install is already running (e.g. the activity was recreated mid-install and
                    // the first-run overlay started the install again). The running install invokes
                    // the latest completion callback when it finishes.
                    sWhenDone = whenDone
                    return
                }
                mIsBootstrapInstalling = true
                sWhenDone = whenDone
            }

            val progress = ProgressDialog.show(
                activity, null, activity.getString(R.string.bootstrap_installer_body), true, false,
            )
            object : Thread() {
                override fun run() {
                    var installSucceeded = false
                    try {
                        Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.")

                        var error: Error?

                        // Delete prefix staging directory or any file at its destination
                        error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true)
                        if (error != null) {
                            showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                            return
                        }

                        // Delete prefix directory or any file at its destination
                        error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true)
                        if (error != null) {
                            showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                            return
                        }

                        // Create prefix staging directory if it does not already exist and set required permissions
                        error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true)
                        if (error != null) {
                            showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                            return
                        }

                        // Create prefix directory if it does not already exist and set required permissions
                        error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true)
                        if (error != null) {
                            showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                            return
                        }

                        Logger.logInfo(
                            LOG_TAG,
                            "Extracting bootstrap zip to prefix staging directory \"$TERMUX_STAGING_PREFIX_DIR_PATH\".",
                        )

                        val buffer = ByteArray(8096)
                        val symlinks = ArrayList<Pair<String, String>>(50)

                        listener.onStage(BootstrapStage.DOWNLOADING)
                        val zipBytes = loadZipBytes()
                        listener.onStage(BootstrapStage.EXTRACTING)
                        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipInput ->
                            while (true) {
                                val zipEntry = zipInput.getNextEntry() ?: break
                                if (zipEntry.getName() == "SYMLINKS.txt") {
                                    val symlinksReader = BufferedReader(InputStreamReader(zipInput))
                                    while (true) {
                                        val line = symlinksReader.readLine() ?: break
                                        val parts = line.split("←").dropLastWhile { it.isEmpty() }
                                        if (parts.size != 2)
                                            throw RuntimeException("Malformed symlink line: $line")
                                        val oldPath = parts[0]
                                        val newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1]
                                        symlinks.add(Pair.create(oldPath, newPath))

                                        val symlinkDirectoryError = ensureDirectoryExists(File(newPath).getParentFile())
                                        if (symlinkDirectoryError != null) {
                                            showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(symlinkDirectoryError))
                                            return
                                        }
                                    }
                                } else {
                                    val zipEntryName = zipEntry.getName()
                                    val targetFile = File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName)
                                    val isDirectory = zipEntry.isDirectory

                                    val entryDirectoryError = ensureDirectoryExists(if (isDirectory) targetFile else targetFile.getParentFile())
                                    if (entryDirectoryError != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(entryDirectoryError))
                                        return
                                    }

                                    if (!isDirectory) {
                                        FileOutputStream(targetFile).use { outStream ->
                                            while (true) {
                                                val readBytes = zipInput.read(buffer)
                                                if (readBytes == -1) break
                                                outStream.write(buffer, 0, readBytes)
                                            }
                                        }
                                        if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                            zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")
                                        ) {
                                            //noinspection OctalInteger
                                            Os.chmod(targetFile.getAbsolutePath(), 448) // 0700
                                        }
                                    }
                                }
                            }
                        }

                        if (symlinks.isEmpty())
                            throw RuntimeException("No SYMLINKS.txt encountered")
                        for (symlink in symlinks) {
                            Os.symlink(symlink.first, symlink.second)
                        }

                        listener.onStage(BootstrapStage.FINISHING)
                        Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.")

                        if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                            throw RuntimeException("Moving termux prefix staging to prefix directory failed")
                        }

                        Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.")

                        // Recreate env file since termux prefix was wiped earlier
                        TermuxShellEnvironment.writeEnvironmentToFile(activity)

                        installSucceeded = true
                    } catch (e: Exception) {
                        showBootstrapErrorDialog(
                            activity, whenDone,
                            Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        )
                    } finally {
                        val success = installSucceeded
                        activity.runOnUiThread {
                            val done = takeWhenDoneAndMarkFinished()
                            try {
                                progress.dismiss()
                            } catch (e: RuntimeException) {
                                // Activity already dismissed - ignore.
                            }
                            if (success) {
                                done?.run()
                            } else {
                                listener.onFailure()
                            }
                        }
                    }
                }
            }.start()
        }

        @JvmStatic
        fun showBootstrapErrorDialog(activity: Activity, whenDone: Runnable, message: String) {
            Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message)

            // Send a notification with the exception so that the user knows why bootstrap setup failed
            sendBootstrapCrashReportNotification(activity, message)

            activity.runOnUiThread {
                try {
                    AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                        .setNegativeButton(R.string.bootstrap_error_abort) { dialog, which ->
                            dialog.dismiss()
                            activity.finish()
                        }
                        .setPositiveButton(R.string.bootstrap_error_try_again) { dialog, which ->
                            dialog.dismiss()
                            FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true)
                            setupBootstrapIfNeeded(activity, whenDone)
                        }.show()
                } catch (e1: WindowManager.BadTokenException) {
                    // Activity already dismissed - ignore.
                }
            }
        }

        private fun sendBootstrapCrashReportNotification(activity: Activity, message: String) {
            val title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error"

            // Add info of all install Termux plugin apps as well since their target sdk or installation
            // on external/portable sd card can affect Termux app files directory access or exec.
            TermuxCrashUtils.sendCrashReportNotification(
                activity, LOG_TAG,
                title, null, "## " + title + "\n\n" + message + "\n\n" +
                    TermuxUtils.getTermuxDebugMarkdownString(activity),
                true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true,
            )
        }

        @JvmStatic
        fun setupStorageSymlinks(context: Context) {
            val storageLogTag = "termux-storage"
            val title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error"

            Logger.logInfo(storageLogTag, "Setting up storage symlinks.")

            object : Thread() {
                override fun run() {
                    try {
                        var error: Error?
                        val storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR

                        error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath())
                        if (error != null) {
                            Logger.logErrorAndShowToast(context, storageLogTag, error.getMessage())
                            Logger.logErrorExtended(storageLogTag, "Setup Storage Error\n" + error.toString())
                            TermuxCrashUtils.sendCrashReportNotification(
                                context, storageLogTag, title, null,
                                "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                                true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true,
                            )
                            return
                        }

                        Logger.logInfo(
                            storageLogTag,
                            "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".",
                        )

                        // Get primary storage root "/storage/emulated/0" symlink
                        val sharedDir = Environment.getExternalStorageDirectory()
                        Os.symlink(sharedDir.getAbsolutePath(), File(storageDir, "shared").getAbsolutePath())

                        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        Os.symlink(documentsDir.getAbsolutePath(), File(storageDir, "documents").getAbsolutePath())

                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        Os.symlink(downloadsDir.getAbsolutePath(), File(storageDir, "downloads").getAbsolutePath())

                        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                        Os.symlink(dcimDir.getAbsolutePath(), File(storageDir, "dcim").getAbsolutePath())

                        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        Os.symlink(picturesDir.getAbsolutePath(), File(storageDir, "pictures").getAbsolutePath())

                        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                        Os.symlink(musicDir.getAbsolutePath(), File(storageDir, "music").getAbsolutePath())

                        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        Os.symlink(moviesDir.getAbsolutePath(), File(storageDir, "movies").getAbsolutePath())

                        val podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS)
                        Os.symlink(podcastsDir.getAbsolutePath(), File(storageDir, "podcasts").getAbsolutePath())

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS)
                            Os.symlink(audiobooksDir.getAbsolutePath(), File(storageDir, "audiobooks").getAbsolutePath())
                        }

                        // Dir 0 should ideally be for primary storage
                        // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                        // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                        // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                        // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                        // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                        // Create "Android/data/com.termux" symlinks
                        var dirs = context.getExternalFilesDirs(null)
                        if (dirs != null && dirs.size > 0) {
                            for (i in dirs.indices) {
                                val dir = dirs[i] ?: continue
                                val symlinkName = "external-$i"
                                Logger.logInfo(
                                    storageLogTag,
                                    "Setting up storage symlinks at ~/storage/$symlinkName for \"" + dir.getAbsolutePath() + "\".",
                                )
                                Os.symlink(dir.getAbsolutePath(), File(storageDir, symlinkName).getAbsolutePath())
                            }
                        }

                        // Create "Android/media/com.termux" symlinks
                        dirs = context.getExternalMediaDirs()
                        if (dirs != null && dirs.size > 0) {
                            for (i in dirs.indices) {
                                val dir = dirs[i] ?: continue
                                val symlinkName = "media-$i"
                                Logger.logInfo(
                                    storageLogTag,
                                    "Setting up storage symlinks at ~/storage/$symlinkName for \"" + dir.getAbsolutePath() + "\".",
                                )
                                Os.symlink(dir.getAbsolutePath(), File(storageDir, symlinkName).getAbsolutePath())
                            }
                        }

                        Logger.logInfo(storageLogTag, "Storage symlinks created successfully.")
                    } catch (e: Exception) {
                        Logger.logErrorAndShowToast(context, storageLogTag, e.message)
                        Logger.logStackTraceWithMessage(storageLogTag, "Setup Storage Error: Error setting up link", e)
                        TermuxCrashUtils.sendCrashReportNotification(
                            context, storageLogTag, title, null,
                            "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true,
                        )
                    }
                }
            }.start()
        }

        private fun ensureDirectoryExists(directory: File): Error? {
            return FileUtils.createDirectoryFile(directory.getAbsolutePath())
        }

        @JvmStatic
        fun loadZipBytes(): ByteArray {
            // Only load the shared library when necessary to save memory usage.
            System.loadLibrary("termux-bootstrap")
            return getZip()
        }

        @JvmStatic
        external fun getZip(): ByteArray
    }

}
