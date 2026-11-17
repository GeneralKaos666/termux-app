package com.termux.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import com.termux.R
import com.termux.app.data.notifications.ServiceNotification
import com.termux.app.data.wakelock.WakeLockController
import com.termux.app.domain.notifications.shouldStopService
import com.termux.app.domain.sessions.moveItemInPlace
import com.termux.app.event.SystemEventReceiver
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.app.terminal.TermuxTerminalSessionServiceClient
import com.termux.app.ui.floating.FloatController
import com.termux.app.ui.floating.FloatSessionSource
import com.termux.shared.android.PermissionUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.errors.Errno
import com.termux.shared.logger.Logger
import com.termux.shared.net.uri.UriUtils
import com.termux.shared.shell.ShellUtils
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.ExecutionCommand.Runner
import com.termux.shared.shell.command.ExecutionCommand.ShellCreateMode
import com.termux.shared.shell.command.runner.app.AppShell
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.termux.shared.termux.plugins.TermuxPluginUtils
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.shell.TermuxShellManager
import com.termux.shared.termux.shell.TermuxShellUtils
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.util.ArrayList

/**
 * A service holding a list of [TermuxSession] in [TermuxShellManager.mTermuxSessions] and background [AppShell]
 * in [TermuxShellManager.mTermuxTasks], showing a foreground notification while running so that it is not terminated.
 * The user interacts with the session through [TermuxActivity], but this service may outlive
 * the activity when the user or the system disposes of the activity. In that case the user may
 * restart [TermuxActivity] later to yet again access the sessions.
 *
 *
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, [Service.startForeground].
 *
 *
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * [.buildNotification].
 */
class TermuxService : Service(), AppShell.AppShellClient, TermuxSession.TermuxSessionClient, FloatSessionSource {

    /** This service is only bound from inside the same process and never uses IPC. */
    inner class LocalBinder : Binder() {
        @JvmField
        val service: TermuxService = this@TermuxService
    }

    private val mBinder: IBinder = LocalBinder()

    private val mHandler = Handler()


    /** The full implementation of the [TerminalSessionClient] interface to be used by [TerminalSession]
     * that holds activity references for activity related functions.
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    private var mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient? = null

    /** The basic implementation of the [TerminalSessionClient] interface to be used by [TerminalSession]
     * that does not hold activity references and only a service reference.
     */
    private val mTermuxTerminalSessionServiceClient = TermuxTerminalSessionServiceClient(this)

    /**
     * Termux app shared properties manager, loaded from termux.properties
     */
    private lateinit var mProperties: TermuxAppSharedProperties

    /**
     * Termux app shell manager
     */
    private lateinit var mShellManager: TermuxShellManager

    private val mWakeLockController = WakeLockController(this, onChange = { updateNotification() })

    /** If the user has executed the [TERMUX_SERVICE.ACTION_STOP_SERVICE] intent. */
    @JvmField
    var mWantsToStop = false

    /** Lazily built orchestrator for the in-app floating terminal overlay and its session. */
    private var mFloatController: FloatController? = null

    private val mServiceNotification = ServiceNotification(this)

    override fun onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate")

        // Get Termux app SharedProperties without loading from disk since TermuxApplication handles
        // load and TermuxActivity handles reloads
        mProperties = TermuxAppSharedProperties.getProperties()

        mShellManager = TermuxShellManager.getShellManager()

        runStartForeground()

        SystemEventReceiver.registerPackageUpdateEvents(this)
    }

    @SuppressLint("Wakelock")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.logDebug(LOG_TAG, "onStartCommand")

        // Run again in case service is already started and onCreate() is not called
        runStartForeground()

        var action: String? = null
        if (intent != null) {
            Logger.logVerboseExtended(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent))
            action = intent.action
        }

        if (action != null) {
            when (action) {
                TERMUX_SERVICE.ACTION_STOP_SERVICE -> {
                    Logger.logDebug(LOG_TAG, "ACTION_STOP_SERVICE intent received")
                    actionStopService()
                }
                TERMUX_SERVICE.ACTION_WAKE_LOCK -> {
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_LOCK intent received")
                    actionAcquireWakeLock()
                }
                TERMUX_SERVICE.ACTION_WAKE_UNLOCK -> {
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_UNLOCK intent received")
                    actionReleaseWakeLock(true)
                }
                TERMUX_SERVICE.ACTION_SERVICE_EXECUTE -> {
                    Logger.logDebug(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received")
                    actionServiceExecute(intent)
                }
                ACTION_TOGGLE_FLOAT -> {
                    Logger.logDebug(LOG_TAG, "ACTION_TOGGLE_FLOAT intent received")
                    actionToggleFloat()
                }
                else -> Logger.logError(LOG_TAG, "Invalid action: \"" + action + "\"")
            }
        }

        // If this service really do get killed, there is no point restarting it automatically - let the user do on next
        // start of {@link Term):
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Logger.logVerbose(LOG_TAG, "onDestroy")

        TermuxShellUtils.clearTermuxTMPDIR(true)

        actionReleaseWakeLock(false)
        if (!mWantsToStop)
            killAllTermuxExecutionCommands()

        stopFloatController()

        TermuxShellManager.onAppExit(this)

        SystemEventReceiver.unregisterPackageUpdateEvents(this)

        runStopForeground()
    }

    override fun onBind(intent: Intent): IBinder {
        Logger.logVerbose(LOG_TAG, "onBind")
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Logger.logVerbose(LOG_TAG, "onUnbind")

        // Since we cannot rely on {@link TermuxActivity.onDestroy()} to always complete,
        // we unset clients here as well if it failed, so that we do not leave service and session
        // clients with references to the activity.
        if (mTermuxTerminalSessionActivityClient != null)
            unsetTermuxTerminalSessionClient()
        return false
    }

    /** Make service run in foreground mode. */
    private fun runStartForeground() {
        setupNotificationChannel()
        startForeground(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification())
    }

    /** Make service leave foreground mode. */
    private fun runStopForeground() {
        stopForeground(true)
    }

    /** Request to stop service. */
    private fun requestStopService() {
        Logger.logDebug(LOG_TAG, "Requesting to stop service")
        runStopForeground()
        stopSelf()
    }

    /** Process action to stop service. */
    private fun actionStopService() {
        mWantsToStop = true
        stopFloatController()
        killAllTermuxExecutionCommands()
        requestStopService()
    }

    /** Process action to toggle the in-app floating terminal overlay: hide it when shown, otherwise
     * create and show a new float session. */
    private fun actionToggleFloat() {
        val floatController = getFloatController()
        if (floatController.isShown()) {
            floatController.hide()
        } else {
            floatController.showNewSession()
        }
    }

    /**
     * Kill all TermuxSessions and TermuxTasks by sending SIGKILL to their processes.
     *
     * For TermuxSessions, all sessions will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will only be done if user manually exited termux or if the session was started by a plugin
     * which **expects** the result back via a pending intent.
     *
     * For TermuxTasks, only tasks that were started by a plugin which **expects** the result
     * back via a pending intent will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will always be done for the tasks that are killed. The remaining processes will keep on
     * running until the termux app process is killed by android, like by OOM, so we let them run
     * as long as they can.
     *
     * Some plugin execution commands may not have been processed and added to mTermuxSessions and
     * mTermuxTasks lists before the service is killed, so we maintain a separate
     * mPendingPluginExecutionCommands list for those, so that we can notify the pending intent
     * creators that execution was cancelled.
     *
     * Note that if user didn't manually exit Termux and if onDestroy() was directly called because
     * of unintended shutdown, like android deciding to kill the service, then there will be no
     * guarantee that onDestroy() will be allowed to finish and termux app process may be killed before
     * it has finished. This means that in those cases some results may not be sent back to their
     * creators for plugin commands but we still try to process whatever results can be processed
     * despite the unreliable behaviour of onDestroy().
     *
     * Note that if don't kill the processes started by plugins which **expect** the result back
     * and notify their creators that they have been killed, then they may get stuck waiting for
     * the results forever like in case of commands started by Termux:Tasker or RUN_COMMAND intent,
     * since once TermuxService has been killed, no result will be sent back. They may still get
     * stuck if termux app process gets killed, so for this case reasonable timeout values should
     * be used, like in Tasker for the Termux:Tasker actions.
     *
     * We make copies of each list since items are removed inside the loop.
     */
    @Synchronized
    private fun killAllTermuxExecutionCommands() {
        var processResult: Boolean

        Logger.logDebug(
            LOG_TAG, "Killing TermuxSessions=" + mShellManager.mTermuxSessions.size +
                ", TermuxTasks=" + mShellManager.mTermuxTasks.size +
                ", PendingPluginExecutionCommands=" + mShellManager.mPendingPluginExecutionCommands.size,
        )

        val termuxSessions = ArrayList(mShellManager.mTermuxSessions)
        val termuxTasks = ArrayList(mShellManager.mTermuxTasks)
        val pendingPluginExecutionCommands = ArrayList(mShellManager.mPendingPluginExecutionCommands)

        for (i in termuxSessions.indices) {
            val executionCommand = termuxSessions[i].executionCommand
            processResult = mWantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult()
            termuxSessions[i].killIfExecuting(this, processResult)
            if (!processResult)
                mShellManager.mTermuxSessions.remove(termuxSessions[i])
        }


        for (i in termuxTasks.indices) {
            val executionCommand = termuxTasks[i].executionCommand
            if (executionCommand.isPluginExecutionCommandWithPendingResult())
                termuxTasks[i].killIfExecuting(this, true)
            else
                mShellManager.mTermuxTasks.remove(termuxTasks[i])
        }

        for (i in pendingPluginExecutionCommands.indices) {
            val executionCommand = pendingPluginExecutionCommands[i]
            if (!executionCommand.shouldNotProcessResults() && executionCommand.isPluginExecutionCommandWithPendingResult()) {
                if (executionCommand.setStateFailed(Errno.ERRNO_CANCELLED.code, getString(com.termux.shared.R.string.error_execution_cancelled))) {
                    TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)
                }
            }
        }
    }


    /** Process action to acquire Power and Wi-Fi WakeLocks. */
    private fun actionAcquireWakeLock() {
        mWakeLockController.acquire()
    }

    /** Process action to release Power and Wi-Fi WakeLocks. */
    private fun actionReleaseWakeLock(updateNotification: Boolean) {
        mWakeLockController.release(updateNotification)
    }

    /** Process [TERMUX_SERVICE.ACTION_SERVICE_EXECUTE] intent to execute a shell command in
     * a foreground TermuxSession or in a background TermuxTask. */
    private fun actionServiceExecute(intent: Intent?) {
        if (intent == null) {
            Logger.logError(LOG_TAG, "Ignoring null intent to actionServiceExecute")
            return
        }

        val executionCommand = ExecutionCommand(TermuxShellManager.getNextShellId())

        executionCommand.executableUri = intent.data
        executionCommand.isPluginExecutionCommand = true

        // If EXTRA_RUNNER is passed, use that, otherwise check EXTRA_BACKGROUND and default to Runner.TERMINAL_SESSION
        executionCommand.runner = IntentUtils.getStringExtraIfSet(
            intent, TERMUX_SERVICE.EXTRA_RUNNER,
            if (intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false)) Runner.APP_SHELL.getName() else Runner.TERMINAL_SESSION.getName(),
        )
        if (Runner.runnerOf(executionCommand.runner) == null) {
            val errmsg = getString(R.string.error_termux_service_invalid_execution_command_runner, executionCommand.runner)
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.code, errmsg)
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            return
        }

        if (executionCommand.executableUri != null) {
            Logger.logVerbose(LOG_TAG, "uri: \"" + executionCommand.executableUri + "\", path: \"" + executionCommand.executableUri!!.path + "\", fragment: \"" + executionCommand.executableUri!!.fragment + "\"")

            // Get full path including fragment (anything after last "#")
            executionCommand.executable = UriUtils.getUriFilePathWithFragment(executionCommand.executableUri)
            executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(intent, TERMUX_SERVICE.EXTRA_ARGUMENTS, null)
            if (Runner.APP_SHELL.equalsRunner(executionCommand.runner))
                executionCommand.stdin = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_STDIN, null)
            executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(intent, TERMUX_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, null)
        }

        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_WORKDIR, null)
        executionCommand.isFailsafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
        executionCommand.sessionAction = intent.getStringExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION)
        executionCommand.shellName = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_NAME, null)
        executionCommand.shellCreateMode = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, null)
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Execution Intent Command")
        executionCommand.commandDescription = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, null)
        executionCommand.commandHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_HELP, null)
        executionCommand.pluginAPIHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_PLUGIN_API_HELP, null)
        executionCommand.resultConfig.resultPendingIntent = intent.getParcelableExtra(TERMUX_SERVICE.EXTRA_PENDING_INTENT)
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, null)
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, false)
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME, null)
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null)
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null)
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null)
        }

        if (executionCommand.shellCreateMode == null)
            executionCommand.shellCreateMode = ShellCreateMode.ALWAYS.mode

        // Add the execution command to pending plugin execution commands list
        mShellManager.mPendingPluginExecutionCommands.add(executionCommand)

        if (Runner.APP_SHELL.equalsRunner(executionCommand.runner))
            executeTermuxTaskCommand(executionCommand)
        else if (Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner))
            executeTermuxSessionCommand(executionCommand)
        else {
            val errmsg = getString(R.string.error_termux_service_unsupported_execution_command_runner, executionCommand.runner)
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.code, errmsg)
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
        }
    }


    /** Execute a shell command in background TermuxTask. */
    private fun executeTermuxTaskCommand(executionCommand: ExecutionCommand?) {
        if (executionCommand == null) return

        Logger.logDebug(LOG_TAG, "Executing background \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command")

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null)
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable)

        var newTermuxTask: AppShell? = null
        val shellCreateMode = processShellCreateMode(executionCommand) ?: return
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            newTermuxTask = getTermuxTaskForShellName(executionCommand.shellName)
            if (newTermuxTask != null)
                Logger.logVerbose(LOG_TAG, "Existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.mode + "\"")
            else
                Logger.logVerbose(LOG_TAG, "No existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.mode + "\"")
        }

        if (newTermuxTask == null)
            newTermuxTask = createTermuxTask(executionCommand)
    }

    /** Create a TermuxTask. */
    fun createTermuxTask(executablePath: String?, arguments: Array<String>?, stdin: String?, workingDirectory: String?): AppShell? {
        return createTermuxTask(
            ExecutionCommand(
                TermuxShellManager.getNextShellId(), executablePath,
                arguments, stdin, workingDirectory, Runner.APP_SHELL.getName(), false,
            ),
        )
    }

    /** Create a TermuxTask. */
    @Synchronized
    fun createTermuxTask(executionCommand: ExecutionCommand?): AppShell? {
        if (executionCommand == null) return null

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask")

        if (!Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxTask()")
            return null
        }

        executionCommand.setShellCommandShellEnvironment = true

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString())

        val newTermuxTask = AppShell.execute(
            this, executionCommand, this,
            TermuxShellEnvironment(), null, false,
        )
        if (newTermuxTask == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxTask command for:\n" + executionCommand.getCommandIdAndLabelLogString())
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs")
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString())
            }
            return null
        }

        mShellManager.mTermuxTasks.add(newTermuxTask)

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand)

        updateNotification()

        return newTermuxTask
    }

    /** Callback received when a TermuxTask finishes. */
    override fun onAppShellExited(termuxTask: AppShell?) {
        mHandler.post {
            if (termuxTask != null) {
                val executionCommand = termuxTask.executionCommand

                Logger.logVerbose(LOG_TAG, "The onTermuxTaskExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command")

                // If the execution command was started for a plugin, then process the results
                if (executionCommand.isPluginExecutionCommand)
                    TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)

                mShellManager.mTermuxTasks.remove(termuxTask)
            }

            updateNotification()
        }
    }


    /** Execute a shell command in a foreground [TermuxSession]. */
    private fun executeTermuxSessionCommand(executionCommand: ExecutionCommand?) {
        if (executionCommand == null) return

        Logger.logDebug(LOG_TAG, "Executing foreground \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command")

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null)
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable)

        var newTermuxSession: TermuxSession? = null
        val shellCreateMode = processShellCreateMode(executionCommand) ?: return
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            newTermuxSession = getTermuxSessionForShellName(executionCommand.shellName)
            if (newTermuxSession != null)
                Logger.logVerbose(LOG_TAG, "Existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.mode + "\"")
            else
                Logger.logVerbose(LOG_TAG, "No existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.mode + "\"")
        }

        if (newTermuxSession == null)
            newTermuxSession = createTermuxSession(executionCommand)
        if (newTermuxSession == null) return

        handleSessionAction(
            DataUtils.getIntFromString(
                executionCommand.sessionAction,
                TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY,
            ),
            newTermuxSession.terminalSession,
        )
    }

    /**
     * Create a [TermuxSession].
     * Currently called by [TermuxTerminalSessionActivityClient.addNewSession] to add a new [TermuxSession].
     */
    fun createTermuxSession(
        executablePath: String?, arguments: Array<String>?, stdin: String?,
        workingDirectory: String?, isFailSafe: Boolean, sessionName: String?,
    ): TermuxSession? {
        val executionCommand = ExecutionCommand(
            TermuxShellManager.getNextShellId(),
            executablePath, arguments, stdin, workingDirectory, Runner.TERMINAL_SESSION.getName(), isFailSafe,
        )
        executionCommand.shellName = sessionName
        return createTermuxSession(executionCommand)
    }

    /** Create a [TermuxSession] and add it to the sessions list. */
    @Synchronized
    fun createTermuxSession(executionCommand: ExecutionCommand): TermuxSession? {
        val newTermuxSession = executeTermuxSession(executionCommand, getTermuxTerminalSessionClient())
            ?: return null

        mShellManager.mTermuxSessions.add(newTermuxSession)

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand)

        // Notify the activity that the sessions list has been updated if it is in the foreground
        mTermuxTerminalSessionActivityClient?.termuxSessionListNotifyUpdated()

        updateNotification()

        // No need to recreate the activity since it likely just started and theme should already have applied
        TermuxActivity.updateTermuxActivityStyling(this, false)

        return newTermuxSession
    }

    /**
     * Create a [TermuxSession] for the in-app floating terminal using the supplied
     * `terminalSessionClient`, without adding it to the sessions list, updating the
     * notification, or applying activity styling.
     */
    @Synchronized
    override fun createFloatSession(terminalSessionClient: TerminalSessionClient): TermuxSession? {
        val executionCommand = ExecutionCommand(
            TermuxShellManager.getNextShellId(),
            null, null, null, null, Runner.TERMINAL_SESSION.getName(), false,
        )
        return executeTermuxSession(executionCommand, terminalSessionClient)
    }

    /**
     * Terminates the shell of a dedicated float session. It is not in the sessions list, so nothing
     * else will stop it when the user closes the floating window.
     */
    override fun finishFloatSession(session: TermuxSession) {
        session.terminalSession.finishIfRunning()
    }

    /**
     * Lazily builds and returns the [FloatController] that owns the in-app floating terminal
     * overlay and its dedicated session, using this service as the [FloatSessionSource].
     */
    fun getFloatController(): FloatController {
        var floatController = mFloatController
        if (floatController == null) {
            val preferences = TermuxAppSharedPreferences.build(this)
            val fontSize = if (preferences != null) preferences.fontSize else FloatController.DEFAULT_FONT_SIZE
            floatController = FloatController(this, this, fontSize)
            mFloatController = floatController
        }
        return floatController
    }

    /** Stop the in-app floating terminal if it exists, finishing its session and removing overlays. */
    private fun stopFloatController() {
        mFloatController?.stop()
    }

    /** Whether the in-app floating terminal currently has a running session. */
    private fun isFloatSessionRunning(): Boolean {
        return mFloatController != null && mFloatController!!.isSessionRunning()
    }

    /**
     * Execute a [TermuxSession] for the given `executionCommand` using the supplied
     * `terminalSessionClient`. Does not add the session to the sessions list or fire any
     * notification/styling side effects; those are the caller's responsibility.
     */
    private fun executeTermuxSession(
        executionCommand: ExecutionCommand,
        terminalSessionClient: TerminalSessionClient,
    ): TermuxSession? {
        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession")

        if (!Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxSession()")
            return null
        }

        executionCommand.setShellCommandShellEnvironment = true
        executionCommand.terminalTranscriptRows = mProperties.terminalTranscriptRows

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString())

        // If the execution command was started for a plugin, only then will the stdout be set
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout
        val newTermuxSession = TermuxSession.execute(
            this, executionCommand, terminalSessionClient,
            this, TermuxShellEnvironment(), null, executionCommand.isPluginExecutionCommand,
        )
        if (newTermuxSession == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxSession command for:\n" + executionCommand.getCommandIdAndLabelLogString())
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs")
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString())
            }
            return null
        }

        return newTermuxSession
    }

    /** Remove a TermuxSession. */
    @Synchronized
    fun removeTermuxSession(sessionToRemove: TerminalSession): Int {
        val index = getIndexOfSession(sessionToRemove)

        if (index >= 0)
            mShellManager.mTermuxSessions[index].finish()

        return index
    }

    /** Callback received when a [TermuxSession] finishes. */
    override fun onTermuxSessionExited(termuxSession: TermuxSession?) {
        if (termuxSession != null) {
            val executionCommand = termuxSession.executionCommand

            Logger.logVerbose(LOG_TAG, "The onTermuxSessionExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command")

            // If the execution command was started for a plugin, then process the results
            if (executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)

            mShellManager.mTermuxSessions.remove(termuxSession)

            // Notify the activity that the sessions list has been updated if it is in the foreground
            mTermuxTerminalSessionActivityClient?.termuxSessionListNotifyUpdated()
        }

        updateNotification()
    }


    private fun processShellCreateMode(executionCommand: ExecutionCommand): ShellCreateMode? {
        if (ShellCreateMode.ALWAYS.equalsMode(executionCommand.shellCreateMode))
            return ShellCreateMode.ALWAYS // Default
        else if (ShellCreateMode.NO_SHELL_WITH_NAME.equalsMode(executionCommand.shellCreateMode))
            if (DataUtils.isNullOrEmpty(executionCommand.shellName)) {
                TermuxPluginUtils.setAndProcessPluginExecutionCommandError(
                    this, LOG_TAG, executionCommand, false,
                    getString(R.string.error_termux_service_execution_command_shell_name_unset, executionCommand.shellCreateMode),
                )
                return null
            } else {
                return ShellCreateMode.NO_SHELL_WITH_NAME
            }
        else {
            TermuxPluginUtils.setAndProcessPluginExecutionCommandError(
                this, LOG_TAG, executionCommand, false,
                getString(R.string.error_termux_service_unsupported_execution_command_shell_create_mode, executionCommand.shellCreateMode),
            )
            return null
        }
    }

    /** Process session action for new session. */
    private fun handleSessionAction(sessionAction: Int, newTerminalSession: TerminalSession) {
        Logger.logDebug(LOG_TAG, "Processing sessionAction \"" + sessionAction + "\" for session \"" + newTerminalSession.mSessionName + "\"")

        when (sessionAction) {
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY -> {
                setCurrentStoredTerminalSession(newTerminalSession)
                mTermuxTerminalSessionActivityClient?.setCurrentSession(newTerminalSession)
                startTermuxActivity()
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY -> {
                if (getTermuxSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession)
                startTermuxActivity()
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY -> {
                setCurrentStoredTerminalSession(newTerminalSession)
                mTermuxTerminalSessionActivityClient?.setCurrentSession(newTerminalSession)
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY -> {
                if (getTermuxSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession)
            }
            else -> {
                Logger.logError(LOG_TAG, "Invalid sessionAction: \"" + sessionAction + "\". Force using default sessionAction.")
                handleSessionAction(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY, newTerminalSession)
            }
        }
    }

    /** Launch the [TermuxActivity] to bring it to foreground. */
    private fun startTermuxActivity() {
        // For android >= 10, apps require Display over other apps permission to start foreground activities
        // from background (services). If it is not granted, then TermuxSessions that are started will
        // show in Termux notification but will not run until user manually clicks the notification.
        if (PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(this, true)) {
            TermuxActivity.startTermuxActivity(this)
        } else {
            val preferences = TermuxAppSharedPreferences.build(this) ?: return
            if (preferences.arePluginErrorNotificationsEnabled(false))
                Logger.showToast(this, getString(R.string.error_display_over_other_apps_permission_not_granted_to_start_terminal), true)
        }
    }


    /**
     * If [TermuxActivity] has not bound to the [TermuxService] yet or is destroyed, then
     * interface functions requiring the activity should not be available to the terminal sessions,
     * so we just return the [.mTermuxTerminalSessionServiceClient]. Once [TermuxActivity] bind
     * callback is received, it should call [.setTermuxTerminalSessionClient] to set the
     * [TermuxService.mTermuxTerminalSessionActivityClient] so that further terminal sessions are directly
     * passed the [TermuxTerminalSessionActivityClient] object which fully implements the
     * [TerminalSessionClient] interface.
     *
     * @return Returns the [TermuxTerminalSessionActivityClient] if [TermuxActivity] has bound with
     * [TermuxService], otherwise [TermuxTerminalSessionServiceClient].
     */
    @Synchronized
    fun getTermuxTerminalSessionClient(): TermuxTerminalSessionClientBase {
        return mTermuxTerminalSessionActivityClient ?: mTermuxTerminalSessionServiceClient
    }

    /**
     * This should be called when [TermuxActivity.onServiceConnected] is called to set the
     * [TermuxService.mTermuxTerminalSessionActivityClient] variable and update the [TerminalSession]
     * and [TerminalEmulator] clients in case they were passed [TermuxTerminalSessionServiceClient]
     * earlier.
     *
     * @param termuxTerminalSessionActivityClient The [TermuxTerminalSessionActivityClient] object that fully
     * implements the [TerminalSessionClient] interface.
     */
    @Synchronized
    fun setTermuxTerminalSessionClient(termuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient) {
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient

        for (i in mShellManager.mTermuxSessions.indices)
            mShellManager.mTermuxSessions[i].terminalSession.updateTerminalSessionClient(termuxTerminalSessionActivityClient)
    }

    /**
     * This should be called when [TermuxActivity] has been destroyed and in [.onUnbind]
     * so that the [TermuxService] and [TerminalSession] and [TerminalEmulator]
     * clients do not hold an activity references.
     */
    @Synchronized
    fun unsetTermuxTerminalSessionClient() {
        for (i in mShellManager.mTermuxSessions.indices)
            mShellManager.mTermuxSessions[i].terminalSession.updateTerminalSessionClient(mTermuxTerminalSessionServiceClient)

        mTermuxTerminalSessionActivityClient = null
    }


    private fun buildNotification(): Notification {
        return mServiceNotification.build(
            getTermuxSessionsSize(),
            mShellManager.mTermuxTasks.size,
            mWakeLockController.isHeld,
        )!!
    }

    private fun setupNotificationChannel() {
        mServiceNotification.setupChannel()
    }

    /** Update the shown foreground service notification after making any changes that affect it. */
    @Synchronized
    private fun updateNotification() {
        if (shouldStopService(
                mWakeLockController.isHeld,
                getTermuxSessionsSize(),
                mShellManager.mTermuxTasks.size,
                isFloatSessionRunning(),
            )
        ) {
            // Exit if we are updating after the user disabled all locks with no sessions, tasks or float running.
            requestStopService()
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification())
        }
    }


    private fun setCurrentStoredTerminalSession(terminalSession: TerminalSession) {
        // Make the newly created session the current one to be displayed
        val preferences = TermuxAppSharedPreferences.build(this) ?: return
        preferences.setCurrentSession(terminalSession.mHandle)
    }

    @Synchronized
    fun isTermuxSessionsEmpty(): Boolean {
        return mShellManager.mTermuxSessions.isEmpty()
    }

    @Synchronized
    fun getTermuxSessionsSize(): Int {
        return mShellManager.mTermuxSessions.size
    }

    @Synchronized
    fun getTermuxSessions(): List<TermuxSession> {
        return mShellManager.mTermuxSessions
    }

    @Synchronized
    fun getTermuxSession(index: Int): TermuxSession? {
        return if (index >= 0 && index < mShellManager.mTermuxSessions.size)
            mShellManager.mTermuxSessions[index]
        else
            null
    }

    @Synchronized
    fun getTermuxSessionForTerminalSession(terminalSession: TerminalSession?): TermuxSession? {
        if (terminalSession == null) return null

        for (i in mShellManager.mTermuxSessions.indices) {
            if (mShellManager.mTermuxSessions[i].terminalSession == terminalSession)
                return mShellManager.mTermuxSessions[i]
        }

        return null
    }

    @Synchronized
    fun getLastTermuxSession(): TermuxSession? {
        return if (mShellManager.mTermuxSessions.isEmpty()) null else mShellManager.mTermuxSessions[mShellManager.mTermuxSessions.size - 1]
    }

    @Synchronized
    fun getIndexOfSession(terminalSession: TerminalSession?): Int {
        if (terminalSession == null) return -1

        for (i in mShellManager.mTermuxSessions.indices) {
            if (mShellManager.mTermuxSessions[i].terminalSession == terminalSession)
                return i
        }
        return -1
    }

    @Synchronized
    fun getTerminalSessionForHandle(sessionHandle: String?): TerminalSession? {
        var terminalSession: TerminalSession
        var i = 0
        val len = mShellManager.mTermuxSessions.size
        while (i < len) {
            terminalSession = mShellManager.mTermuxSessions[i].terminalSession
            if (terminalSession.mHandle == sessionHandle)
                return terminalSession
            i++
        }
        return null
    }

    /** Moves the session at [fromIndex] to [toIndex], clamping indices to the list bounds. */
    @Synchronized
    fun moveTermuxSession(fromIndex: Int, toIndex: Int) {
        moveItemInPlace(mShellManager.mTermuxSessions, fromIndex, toIndex)
    }

    @Synchronized
    fun getTermuxTaskForShellName(name: String?): AppShell? {
        if (DataUtils.isNullOrEmpty(name)) return null
        var appShell: AppShell
        var i = 0
        val len = mShellManager.mTermuxTasks.size
        while (i < len) {
            appShell = mShellManager.mTermuxTasks[i]
            val shellName = appShell.executionCommand.shellName
            if (shellName != null && shellName == name)
                return appShell
            i++
        }
        return null
    }

    @Synchronized
    fun getTermuxSessionForShellName(name: String?): TermuxSession? {
        if (DataUtils.isNullOrEmpty(name)) return null
        var termuxSession: TermuxSession
        var i = 0
        val len = mShellManager.mTermuxSessions.size
        while (i < len) {
            termuxSession = mShellManager.mTermuxSessions[i]
            val shellName = termuxSession.executionCommand.shellName
            if (shellName != null && shellName == name)
                return termuxSession
            i++
        }
        return null
    }


    fun wantsToStop(): Boolean {
        return mWantsToStop
    }

    companion object {
        private const val LOG_TAG = "TermuxService"

        /**
         * Action for the foreground notification's "Float" button, toggling the in-app floating terminal.
         * Deliberately an [:]app constant (not in `TermuxConstants.TERMUX_SERVICE`) because the float
         * is `:app`-only while `TERMUX_SERVICE` lives in the published `:termux-shared` library.
         * Public so the Settings entry can drive the same toggle.
         */
        const val ACTION_TOGGLE_FLOAT = "com.termux.service_toggle_float"
    }
}
