package com.termux.app.api.file

import android.content.ContentResolver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.termux.R
import com.termux.app.TermuxService
import com.termux.shared.android.PackageUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.interact.MessageDialogUtils
import com.termux.shared.logger.Logger
import com.termux.shared.net.uri.UriScheme
import com.termux.shared.net.uri.UriUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.termux.shared.termux.interact.TextInputDialogUtils
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class FileReceiverActivity : AppCompatActivity() {

    /**
     * If the activity should be finished when the name input dialog is dismissed. This is disabled
     * before showing an error dialog, since the act of showing the error dialog will cause the
     * name input dialog to be implicitly dismissed, and we do not want to finish the activity directly
     * when showing the error dialog.
     */
    private var mFinishOnDismissNameDialog = true

    /**
     * Whether a shared file is being copied to disk. The name dialog is dismissed as soon as its
     * button is pressed, so the dismiss listener must not close a stream the copy is still reading.
     */
    private var mSaveInProgress = false

    /** `onResume` runs again after every dialog, so the launch intent must be handled only once. */
    private val mIntentGate = ReceivedIntentGate()

    override fun onResume() {
        super.onResume()

        if (!mIntentGate.begin()) return

        val intent = getIntent()
        val action = intent.action
        val type = intent.type
        val scheme = intent.scheme

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent))

        val sharedTitle = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null)

        if (Intent.ACTION_SEND == action && type != null) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val sharedUri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)

            if (sharedUri != null) {
                handleContentUri(sharedUri, sharedTitle)
            } else if (sharedText != null) {
                if (isSharedTextAnUrl(sharedText)) {
                    handleUrlAndFinish(sharedText)
                } else {
                    var subject = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, null)
                    if (subject == null) subject = sharedTitle
                    if (subject != null) subject += ".txt"
                    promptNameAndSave(ByteArrayInputStream(sharedText.toByteArray(StandardCharsets.UTF_8)), subject)
                }
            } else {
                showErrorDialogAndQuit("Send action without content - nothing to save.")
            }
        } else {
            val dataUri = intent.data

            if (dataUri == null) {
                showErrorDialogAndQuit("Data uri not passed.")
                return
            }

            if (UriScheme.SCHEME_CONTENT == scheme) {
                handleContentUri(dataUri, sharedTitle)
            } else if (UriScheme.SCHEME_FILE == scheme) {
                Logger.logVerbose(
                    LOG_TAG,
                    "uri: \"$dataUri\", path: \"${dataUri.path}\", fragment: \"${dataUri.fragment}\"",
                )

                // Get full path including fragment (anything after last "#")
                val path = UriUtils.getUriFilePathWithFragment(dataUri)
                if (DataUtils.isNullOrEmpty(path)) {
                    showErrorDialogAndQuit("File path from data uri is null, empty or invalid.")
                    return
                }

                val file = try {
                    File(path!!).canonicalFile
                } catch (e: IOException) {
                    showErrorDialogAndQuit("Cannot resolve file path: " + e.message + ".")
                    return
                }

                // A file:// URI comes from another app and is read with this app's privileges, so
                // never let it point into /data, where all app-private storage lives.
                if (file.path.startsWith("/data/")) {
                    showErrorDialogAndQuit("Cannot receive a file from app-private storage.")
                    return
                }

                try {
                    val inputStream = FileInputStream(file)
                    promptNameAndSave(inputStream, file.name)
                } catch (e: FileNotFoundException) {
                    showErrorDialogAndQuit("Cannot open file: " + e.message + ".")
                }
            } else {
                showErrorDialogAndQuit("Unable to receive any file or URL.")
            }
        }
    }

    fun showErrorDialogAndQuit(message: String) {
        mFinishOnDismissNameDialog = false
        MessageDialogUtils.showMessage(
            this,
            API_TAG, message,
            null, { dialog, which -> finish() },
            null, null,
            { dialog -> finish() },
        )
    }

    fun handleContentUri(uri: Uri, subjectFromIntent: String?) {
        try {
            Logger.logVerbose(
                LOG_TAG,
                "uri: \"$uri\", path: \"${uri.path}\", fragment: \"${uri.fragment}\"",
            )

            var attachmentFileName: String? = null

            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            (contentResolver.query(uri, projection, null, null, null) as Cursor?).use { c ->
                if (c != null && c.moveToFirst()) {
                    val fileNameColumnId = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (fileNameColumnId >= 0) attachmentFileName = c.getString(fileNameColumnId)
                }
            }

            if (attachmentFileName == null) attachmentFileName = subjectFromIntent
            if (attachmentFileName == null) attachmentFileName = UriUtils.getUriFileBasename(uri, true)

            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                showErrorDialogAndQuit("Unable to open the shared content")
                return
            }
            promptNameAndSave(inputStream, attachmentFileName)
        } catch (e: Exception) {
            showErrorDialogAndQuit("Unable to handle shared content:\n\n" + e.message)
            Logger.logStackTraceWithMessage(LOG_TAG, "handleContentUri(uri=$uri) failed", e)
        }
    }

    fun promptNameAndSave(inputStream: InputStream, attachmentFileName: String?) {
        TextInputDialogUtils.textInput(
            this, R.string.title_file_received, attachmentFileName,
            R.string.action_file_received_edit,
            TextInputDialogUtils.TextSetListener { text ->
                saveStreamWithNameAsync(inputStream, text) { outFile ->
                    if (outFile == null) return@saveStreamWithNameAsync

                    val editorProgramFile = File(EDITOR_PROGRAM)
                    if (!editorProgramFile.isFile) {
                        showErrorDialogAndQuit(
                            "The following file does not exist:\n\$HOME/bin/termux-file-editor\n\n" +
                                "Create this file as a script or a symlink - it will be called with the received file as only argument.",
                        )
                        return@saveStreamWithNameAsync
                    }

                    // Do this for the user if necessary:
                    //noinspection ResultOfMethodCallIgnored
                    editorProgramFile.setExecutable(true)

                    val scriptUri = UriUtils.getFileUri(EDITOR_PROGRAM)

                    val executeIntent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri)
                    executeIntent.setClass(this, TermuxService::class.java)
                    executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf(outFile.getAbsolutePath()))
                    startService(executeIntent)
                    finish()
                }
            },
            R.string.action_file_received_open_directory,
            TextInputDialogUtils.TextSetListener { text ->
                saveStreamWithNameAsync(inputStream, text) { outFile ->
                    if (outFile == null) return@saveStreamWithNameAsync

                    val executeIntent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE)
                    executeIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TERMUX_RECEIVEDIR)
                    executeIntent.setClass(this, TermuxService::class.java)
                    startService(executeIntent)
                    finish()
                }
            },
            android.R.string.cancel,
            TextInputDialogUtils.TextSetListener {
                // The dialog can be cancelled before the stream is copied: close it here so a
                // cancelled share does not leak the content descriptor.
                runCatching { inputStream.close() }
                finish()
            },
            DialogInterface.OnDismissListener {
                // The copy owns the stream until it reports back, and it finishes the activity then:
                // finishing here would drop that report before the editor or the directory opens.
                if (mSaveInProgress) return@OnDismissListener
                runCatching { inputStream.close() }
                if (mFinishOnDismissNameDialog) finish()
            },
        )
    }

    /**
     * Copies [inputStream] to the received directory off the main thread and delivers the file, or
     * `null` after reporting the error, back on the main thread.
     */
    fun saveStreamWithNameAsync(inputStream: InputStream, attachmentFileName: String?, onResult: (File?) -> Unit) {
        mSaveInProgress = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { copyStreamToFile(inputStream, attachmentFileName) }
            mSaveInProgress = false
            // The activity may be gone while the copy runs; do not touch its UI then.
            if (isFinishing || isDestroyed) return@launch
            when (result) {
                is SaveResult.Saved -> onResult(result.file)
                is SaveResult.Failed -> {
                    showErrorDialogAndQuit(result.message)
                    onResult(null)
                }
            }
        }
    }

    fun saveStreamWithName(inputStream: InputStream, attachmentFileName: String?): File? {
        return when (val result = copyStreamToFile(inputStream, attachmentFileName)) {
            is SaveResult.Saved -> result.file
            is SaveResult.Failed -> {
                showErrorDialogAndQuit(result.message)
                null
            }
        }
    }

    /** The result of copying a shared stream, without any UI so it can run off the main thread. */
    private sealed class SaveResult {
        data class Saved(val file: File) : SaveResult()
        data class Failed(val message: String) : SaveResult()
    }

    private fun copyStreamToFile(inputStream: InputStream, attachmentFileName: String?): SaveResult {
        // The stream is closed on every path, including a rejected name or directory: once the copy
        // has started, the dialog's dismiss listener leaves the stream alone.
        inputStream.use { input ->
            val receiveDir = File(TERMUX_RECEIVEDIR)

            val fileName = safeReceivedFileName(attachmentFileName)
            if (fileName == null) {
                return SaveResult.Failed("File name cannot be empty or contain a path")
            }

            if (!receiveDir.isDirectory && !receiveDir.mkdirs()) {
                return SaveResult.Failed("Cannot create directory: " + receiveDir.getAbsolutePath())
            }

            val outFile = File(receiveDir, fileName)
            if (!isDirectChildOf(receiveDir, outFile)) {
                return SaveResult.Failed("File name cannot be empty or contain a path")
            }

            try {
                FileOutputStream(outFile).use { f ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val readBytes = input.read(buffer)
                        if (readBytes <= 0) break
                        f.write(buffer, 0, readBytes)
                    }
                }
                return SaveResult.Saved(outFile)
            } catch (e: IOException) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Error saving file", e)
                return SaveResult.Failed("Error saving file:\n\n$e")
            }
        }
    }

    fun handleUrlAndFinish(url: String) {
        val urlOpenerProgramFile = File(URL_OPENER_PROGRAM)
        if (!urlOpenerProgramFile.isFile) {
            showErrorDialogAndQuit(
                "The following file does not exist:\n\$HOME/bin/termux-url-opener\n\n" +
                    "Create this file as a script or a symlink - it will be called with the shared URL as the first argument.",
            )
            return
        }

        // Do this for the user if necessary:
        //noinspection ResultOfMethodCallIgnored
        urlOpenerProgramFile.setExecutable(true)

        val urlOpenerProgramUri = UriUtils.getFileUri(URL_OPENER_PROGRAM)

        val executeIntent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, urlOpenerProgramUri)
        executeIntent.setClass(this, TermuxService::class.java)
        executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf(url))
        startService(executeIntent)
        finish()
    }

    companion object {
        private val TERMUX_RECEIVEDIR: String = TermuxConstants.TERMUX_FILES_DIR_PATH + "/home/downloads"
        private val EDITOR_PROGRAM: String = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-file-editor"
        private val URL_OPENER_PROGRAM: String = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-url-opener"

        private const val API_TAG = TermuxConstants.TERMUX_APP_NAME + "FileReceiver"

        private const val LOG_TAG = "FileReceiverActivity"

        @JvmStatic
        fun isSharedTextAnUrl(sharedText: String?): Boolean {
            if (sharedText == null || sharedText.isEmpty()) return false

            return Patterns.WEB_URL.matcher(sharedText).matches() ||
                Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText)
        }

        /**
         * Update [TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME] component state depending on
         * [TermuxPropertyConstants.KEY_DISABLE_FILE_SHARE_RECEIVER] value and
         * [TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME] component state depending on
         * [TermuxPropertyConstants.KEY_DISABLE_FILE_VIEW_RECEIVER] value.
         */
        @JvmStatic
        fun updateFileReceiverActivityComponentsState(context: Context) {
            object : Thread() {
                override fun run() {
                    val properties = TermuxAppSharedProperties.getProperties()

                    var errmsg: String?
                    var state: Boolean

                    state = !properties.isFileShareReceiverDisabled()
                    Logger.logVerbose(
                        LOG_TAG,
                        "Setting " + TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME + " component state to " + state,
                    )
                    errmsg = PackageUtils.setComponentState(
                        context, TermuxConstants.TERMUX_PACKAGE_NAME,
                        TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME,
                        state, null, false, false,
                    )
                    if (errmsg != null)
                        Logger.logError(LOG_TAG, errmsg)

                    state = !properties.isFileViewReceiverDisabled()
                    Logger.logVerbose(
                        LOG_TAG,
                        "Setting " + TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME + " component state to " + state,
                    )
                    errmsg = PackageUtils.setComponentState(
                        context, TermuxConstants.TERMUX_PACKAGE_NAME,
                        TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME,
                        state, null, false, false,
                    )
                    if (errmsg != null)
                        Logger.logError(LOG_TAG, errmsg)
                }
            }.start()
        }
    }

}
