package com.termux.app.event

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.file.TermuxFileUtils
import com.termux.shared.termux.shell.TermuxShellManager
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment

class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(@NonNull context: Context, @Nullable intent: Intent?) {
        if (intent == null) return
        Logger.logDebug(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent))

        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED ->
                onActionBootCompleted(context, intent)
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED ->
                onActionPackageUpdated(context, intent)
            else ->
                Logger.logError(LOG_TAG, "Invalid action \"$action\" passed to $LOG_TAG")
        }
    }

    @Synchronized
    fun onActionBootCompleted(@NonNull context: Context, @NonNull intent: Intent) {
        TermuxShellManager.onActionBootCompleted(context, intent)
    }

    @Synchronized
    fun onActionPackageUpdated(@NonNull context: Context, @NonNull intent: Intent) {
        val data = intent.data
        if (data != null && TermuxUtils.isUriDataForTermuxPluginPackage(data)) {
            Logger.logDebug(
                LOG_TAG,
                intent.action!!.replace(Regex("^android.intent.action."), "") +
                    " event received for \"" + data.toString().replace(Regex("^package:"), "") + "\"",
            )
            if (TermuxFileUtils.isTermuxFilesDirectoryAccessible(context, false, false) == null)
                TermuxShellEnvironment.writeEnvironmentToFile(context)
        }
    }

    companion object {
        private const val LOG_TAG = "SystemEventReceiver"

        private var mInstance: SystemEventReceiver? = null

        @JvmStatic
        @Synchronized
        fun getInstance(): SystemEventReceiver {
            if (mInstance == null) {
                mInstance = SystemEventReceiver()
            }
            return mInstance!!
        }

        /**
         * Register [SystemEventReceiver] to listen to [Intent.ACTION_PACKAGE_ADDED],
         * [Intent.ACTION_PACKAGE_REMOVED] and [Intent.ACTION_PACKAGE_REPLACED] broadcasts.
         * They must be registered dynamically and cannot be registered implicitly in
         * the AndroidManifest.xml due to Android 8+ restrictions.
         *
         *  https://developer.android.com/guide/components/broadcast-exceptions
         */
        @JvmStatic
        @Synchronized
        fun registerPackageUpdateEvents(@NonNull context: Context) {
            val intentFilter = IntentFilter()
            intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
            intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
            intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED)
            intentFilter.addDataScheme("package")
            context.registerReceiver(getInstance(), intentFilter)
        }

        @JvmStatic
        @Synchronized
        fun unregisterPackageUpdateEvents(@NonNull context: Context) {
            context.unregisterReceiver(getInstance())
        }
    }

}
