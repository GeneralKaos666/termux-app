package com.termux.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import com.termux.R
import com.termux.app.activities.HelpActivity
import com.termux.app.activities.SettingsActivity
import com.termux.app.api.file.FileReceiverActivity
import com.termux.app.data.ui.AndroidUiPreferences
import com.termux.app.data.AndroidWallpaperSource
import com.termux.app.data.WallpaperSource
import com.termux.app.data.appearance.AppearanceStyling
import com.termux.app.data.snippets.SnippetStore
import com.termux.app.data.ui.UiPreferenceKeys
import com.termux.app.domain.appearance.buildPreviewLines
import com.termux.app.domain.background.BackgroundFx
import com.termux.app.domain.palette.PaletteAction
import com.termux.app.domain.sessions.SessionIdAllocator
import com.termux.app.domain.sessions.SessionOutputTracker
import com.termux.app.domain.sessions.SessionSnapshot
import com.termux.app.domain.sessions.readForegroundCommand
import com.termux.app.domain.sessions.sessionItems
import com.termux.app.domain.sessions.starterChips
import com.termux.app.domain.sessions.tabSubtitle
import com.termux.app.domain.terminal.LinkAction
import com.termux.app.terminal.TermuxActivityRootView
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.app.terminal.TermuxTerminalViewClient
import com.termux.app.terminal.io.FullScreenWorkAround
import com.termux.app.terminal.io.TermuxTerminalExtraKeys
import com.termux.app.ui.ShellChromeState
import com.termux.app.ui.ShellHostCallbacks
import com.termux.app.ui.SnackbarState
import com.termux.app.ui.appearance.AppearanceActivity
import com.termux.app.ui.background.TerminalBackgroundState
import com.termux.app.ui.firstrun.BootstrapStage
import com.termux.app.ui.firstrun.FirstRunState
import com.termux.app.ui.installTerminalShellHost
import com.termux.app.ui.keys.ExtraKeysState
import com.termux.app.ui.palette.PaletteState
import com.termux.app.ui.palette.isPaletteShortcut
import com.termux.app.ui.sessions.SessionsState
import com.termux.app.ui.snippets.SnippetChipsState
import com.termux.app.ui.terminal.LinkActionState
import com.termux.shared.activities.ReportActivity
import com.termux.shared.activity.ActivityUtils
import com.termux.shared.activity.media.AppCompatActivityUtils
import com.termux.shared.android.PermissionUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.crash.TermuxCrashUtils
import com.termux.shared.termux.extrakeys.ExtraKeysInfo
import com.termux.shared.termux.extrakeys.ExtraKeysView
import com.termux.shared.termux.extrakeys.SpecialButton
import com.termux.shared.termux.extrakeys.SpecialButtonState
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.shared.termux.theme.TermuxThemeUtils
import com.termux.shared.theme.NightMode
import com.termux.shared.view.ViewUtils
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.HashMap

/**
 * A terminal emulator activity.
 *
 *
 * See
 *  * http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android
 *  * https://code.google.com/p/android/issues/detail?id=6426
 *
 * about memory leaks.
 */
class TermuxActivity : AppCompatActivity(), ServiceConnection {

    /**
     * The connection to the [TermuxService]. Requested in [.onCreate] with a call to
     * [.bindService], and obtained and stored in
     * [.onServiceConnected].
     */
    private var mTermuxService: TermuxService? = null

    /**
     * The [TerminalView] shown in  [TermuxActivity] that displays the terminal.
     */
    lateinit var mTerminalView: TerminalView
        private set

    /**
     *  The [com.termux.view.TerminalViewClient] interface implementation to allow for communication between
     *  [TerminalView] and [TermuxActivity].
     */
    private var mTermuxTerminalViewClient: TermuxTerminalViewClient? = null

    /**
     *  The [com.termux.terminal.TerminalSessionClient] interface implementation to allow for communication between
     *  [TerminalSession] and [TermuxActivity].
     */
    private var mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient? = null

    /**
     * Termux app shared preferences manager.
     */
    private lateinit var mPreferences: TermuxAppSharedPreferences

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private lateinit var mProperties: TermuxAppSharedProperties

    /**
     * The root view of the [TermuxActivity].
     */
    private lateinit var mTermuxActivityRootView: TermuxActivityRootView

    /**
     * The space at the bottom of [TermuxActivityRootView] of the [TermuxActivity].
     */
    private var mTermuxActivityBottomSpaceView: View? = null

    /**
     * The terminal extra keys view: the first key grid, and the primary one for the rest of the
     * activity ([TermuxTerminalViewClient.readExtraKeysSpecialButton] reads
     * modifier state through it; both grids share one state map, see [.mExtraKeysSpecialButtons]).
     */
    private var mExtraKeysView: ExtraKeysView? = null

    /**
     * The second key grid, which renders its own `extra-keys-page2` layout. It stays composed next
     * to [.mExtraKeysView] while the bar is up, so both views are alive at the same time.
     */
    private var mExtraKeysView2: ExtraKeysView? = null

    /**
     * The modifier (CTRL/ALT/SHIFT/FN) state map shared by both key grids, so a modifier toggled on
     * one page applies on the other. Created from the first grid to be inflated and handed to every
     * grid after that; `ExtraKeysView.reload()` only clears the buttons of the instance it
     * runs on, so the two grids can share it.
     */
    private var mExtraKeysSpecialButtons: Map<SpecialButton, SpecialButtonState>? = null

    /**
     * The client for the [.mExtraKeysView].
     */
    private var mTermuxTerminalExtraKeys: TermuxTerminalExtraKeys? = null

    /**
     * The Compose-observable snapshot of the sessions shown in the top sessions bar.
     */
    private var mSessionsState: SessionsState? = null

    /**
     * Assigns each live [TerminalSession] the stable id shown by the Compose sessions bar.
     * TerminalSession objects do not change identity across list mutations or title refreshes, so
     * the id stays stable for the lifetime of a session and the bar's LazyRow keys never shift.
     * Ids are never reused within an activity lifetime.
     */
    private val mSessionIds = SessionIdAllocator()

    /** Stable-id marks for sessions that produced output while they were not the active tab. */
    private val mSessionOutputTracker = SessionOutputTracker()

    /**
     * Reverse map from the stable session id back to its session, rebuilt on every refresh.
     */
    private val mSessionsById: MutableMap<Int, TermuxSession> = HashMap()

    /**
     * Compose-observable snackbar requests shown by the shell host.
     */
    private var mSnackbarState: SnackbarState? = null

    /**
     * Compose-observable terminal background picture (null = solid background colour).
     */
    private var mTerminalBackgroundState: TerminalBackgroundState? = null

    /**
     * Compose-observable transparency of the shell chrome bars (sessions tab bar, extra-keys bar).
     */
    private var mShellChromeState: ShellChromeState? = null

    /**
     * Source of wallpaper and custom background bitmaps.
     */
    private lateinit var mWallpaperSource: WallpaperSource

    /**
     * Whether a background wallpaper decode is currently running on the background thread.
     */
    private var mTerminalBackgroundLoading = false

    /**
     * The blur radius the current picture was loaded with. Below API 31 the blur is baked into the
     * bitmap as a load-time downscale, so a changed radius needs a reload to take effect.
     */
    private var mTerminalBackgroundLoadedBlur = 0f

    /**
     * Compose-observable first-run onboarding state (overlay + install progress).
     */
    private var mFirstRunState: FirstRunState? = null

    /** State of the command palette overlay; owned here so its shortcut can open it from a key event. */
    private var mPaletteState: PaletteState? = null

    /** Quick-insert chips above the extra-keys grid, reloaded when the snippet editor closes. */
    private var mSnippetChipsState: SnippetChipsState? = null

    /** The link action sheet, shown when a tapped link asks the user what to do with it. */
    private var mLinkActionState: LinkActionState? = null
    private var mUiPreferences: AndroidUiPreferences? = null
    private var mSnippetStore: SnippetStore? = null

    /**
     * Whether the first session to be created after the bootstrap install should be a failsafe session.
     */
    private var mLaunchFailsafe = false

    /**
     * The extra-keys preset last applied to the first key grid, used to detect Settings changes on
     * resume.
     */
    private var mAppliedExtraKeysPreset = ""

    /**
     * The second extra-keys page's preset last applied to the second key grid, used to detect
     * Settings changes on resume.
     */
    private var mAppliedExtraKeysPage2Preset = ""

    /**
     * The appearance revision last applied to the terminal, used to detect an appearance change made
     * while this activity was not visible (from the Style screen or Settings) on resume.
     */
    private var mAppliedAppearanceRevision = 0L

    /**
     * The action to run if the user presses undo on the current snackbar, or `null`.
     */
    private var mSnackbarUndoAction: Runnable? = null

    /**
     * The callbacks from the Compose sessions bar back into this activity.
     */
    private val mShellHostCallbacks: ShellHostCallbacks = object : ShellHostCallbacks {
        override fun onSessionSelected(id: Int) {
            selectSessionById(id)
        }

        override fun onSessionRename(id: Int) {
            renameSessionById(id)
        }

        override fun onSessionClose(id: Int) {
            closeSessionById(id)
        }

        override fun onSessionMoved(id: Int, toIndex: Int) {
            moveSessionById(id, toIndex)
        }

        override fun onNewSession() {
            mTermuxTerminalSessionActivityClient?.addNewSession(false, null)
        }

        override fun onNewFloatSession() {
            getTermuxService()?.getFloatController()?.showNewSession()
        }

        override fun onStarterCommand(command: String) {
            val client = mTermuxTerminalSessionActivityClient ?: return
            client.addNewSession(false, null)
            val session = getCurrentSession() ?: return
            // The shell process is created asynchronously; write once it is running, and only if
            // the user has not switched away in the meantime.
            Handler(Looper.getMainLooper()).postDelayed({
                if (getCurrentSession() === session && session.isRunning()) {
                    session.write(command + "\r")
                }
            }, 300)
        }

        override fun onInsertText(text: String) {
            val session = getCurrentSession()
            // Insert only: the user decides when to run it.
            if (session != null) session.write(text)
        }

        override fun onOpenPalette() {
            mPaletteState?.open()
        }

        override fun onPaletteAction(action: PaletteAction, payload: String?) {
            when (action) {
                PaletteAction.NEW_SESSION ->
                    mTermuxTerminalSessionActivityClient?.addNewSession(false, null)
                PaletteAction.NEW_FLOAT_SESSION -> {
                    getTermuxService()?.getFloatController()?.showNewSession()
                }
                PaletteAction.JUMP_SESSION ->
                    if (payload != null) selectSessionById(Integer.parseInt(payload))
                PaletteAction.OPEN_APPEARANCE ->
                    launchAppearanceScreen()
                PaletteAction.OPEN_SETTINGS ->
                    ActivityUtils.startActivity(
                        this@TermuxActivity,
                        Intent(this@TermuxActivity, SettingsActivity::class.java),
                    )
                PaletteAction.TOGGLE_KEYS ->
                    toggleTerminalToolbar()
                PaletteAction.PASTE ->
                    mTermuxTerminalSessionActivityClient?.onPasteTextFromClipboard(getCurrentSession())
                PaletteAction.RUN_SNIPPET ->
                    // Insert the command only: the user decides when to press Enter.
                    if (payload != null) {
                        val session = getCurrentSession()
                        if (session != null) session.write(payload)
                    }
            }
        }

        override fun onExtraKeysViewCreated(page: Int, view: ExtraKeysView) {
            if (page == ExtraKeysState.PAGE_KEYS_2) setExtraKeysView2(view) else setExtraKeysView(view)

            val extraKeys = mTermuxTerminalExtraKeys ?: return

            view.setExtraKeysViewClient(extraKeys)
            view.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps())
            view.setSpecialButtons(getSharedExtraKeysSpecialButtons(view))
            view.reload(extraKeysInfoForPage(page), mTerminalToolbarDefaultHeight)

            // Applied once, only while the bar is shown, matching the old toolbar behaviour.
            if (!mFullScreenWorkAroundApplied && mProperties.isUsingFullScreen() && mProperties.isUsingFullScreenWorkAround()) {
                mFullScreenWorkAroundApplied = true
                FullScreenWorkAround.apply(this@TermuxActivity)
            }
        }

        override fun onExtraKeysViewReleased(page: Int) {
            if (page == ExtraKeysState.PAGE_KEYS_2) setExtraKeysView2(null) else setExtraKeysView(null)
        }

        override fun onExtraKeysTextSend(text: String) {
            val session = getCurrentSession() ?: return

            if (session.isRunning()) {
                session.write(if (text.isEmpty()) "\r" else text)
            } else {
                mTermuxTerminalSessionActivityClient?.removeFinishedSession(session)
            }
        }

        override fun onSnackbarUndo() {
            val undoAction = mSnackbarUndoAction
            if (undoAction != null) {
                undoAction.run()
                mSnackbarUndoAction = null
            }
        }

        override fun onLinkAction(action: LinkAction, url: String) {
            when (action) {
                LinkAction.OPEN ->
                    ShareUtils.openUrl(this@TermuxActivity, url)
                LinkAction.COPY ->
                    ShareUtils.copyTextToClipboard(
                        this@TermuxActivity,
                        url,
                        getString(R.string.link_action_copied),
                    )
                LinkAction.SHARE ->
                    ShareUtils.shareText(
                        this@TermuxActivity,
                        getString(R.string.title_share_link),
                        url,
                    )
            }
        }

        override fun onFirstRunInstall() {
            startFirstRunBootstrapInstall()
        }
    }

    /**
     * The [TermuxActivity] broadcast receiver for various things like terminal style configuration changes.
     */
    private val mTermuxActivityBroadcastReceiver = TermuxActivityBroadcastReceiver()

    /**
     * The last toast shown, used cancel current toast before showing new in [.showToast].
     */
    private var mLastToast: Toast? = null

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private var mIsVisible = false

    /**
     * If onResume() was called after onCreate().
     */
    private var mIsOnResumeAfterOnCreate = false

    /**
     * If activity was restarted like due to call to [.recreate] after receiving
     * [TERMUX_ACTIVITY.ACTION_RELOAD_STYLE], system dark night mode was changed or activity
     * was killed by android.
     */
    private var mIsActivityRecreated = false

    /**
     * The [TermuxActivity] is in an invalid state and must not be run.
     */
    private var mIsInvalidState = false

    private var mNavBarHeight = 0

    private var mTerminalToolbarDefaultHeight = 0f

    /**
     * The Compose-observable state of the bottom extra-keys bar.
     */
    private var mExtraKeysState: ExtraKeysState? = null

    /**
     * Whether the fullscreen soft-keyboard workaround has already been applied.
     */
    private var mFullScreenWorkAroundApplied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Logger.logDebug(LOG_TAG, "onCreate")
        mIsOnResumeAfterOnCreate = true

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false)

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false)

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties()
        reloadProperties()

        setActivityTheme()

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_termux)

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true) ?: run {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true
            return
        }

        setMargins()

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view)
        mTermuxActivityRootView.setActivity(this)
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view)
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(TermuxActivityRootView.WindowInsetsListener())

        val content: View = findViewById(android.R.id.content)
        content.setOnApplyWindowInsetsListener { v, insets ->
            mNavBarHeight = insets.systemWindowInsetBottom
            insets
        }

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        setTermuxTerminalViewAndClients(savedInstanceState)

        registerForContextMenu(mTerminalView)

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this)

        try {
            // Start the [TermuxService] and make it run regardless of who is bound to it
            val serviceIntent = Intent(this, TermuxService::class.java)
            startService(serviceIntent)

            // Attempt to bind to the service, this will call the [.onServiceConnected]
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw RuntimeException("bindService() failed")
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "TermuxActivity failed to start TermuxService", e)
            Logger.showToast(
                this,
                getString(
                    if (e.message?.contains("app is in background") == true)
                        R.string.error_termux_service_start_failed_bg
                    else
                        R.string.error_termux_service_start_failed_general,
                ),
                true,
            )
            mIsInvalidState = true
            return
        }

        // Send the [TermuxConstants.BROADCAST_TERMUX_OPENED] broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this)
    }

    override fun onStart() {
        super.onStart()

        Logger.logDebug(LOG_TAG, "onStart")

        if (mIsInvalidState) return

        mIsVisible = true

        mTermuxTerminalSessionActivityClient?.onStart()

        mTermuxTerminalViewClient?.onStart()

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener()

        registerTermuxActivityBroadcastReceiver()
    }

    override fun onResume() {
        super.onResume()

        Logger.logVerbose(LOG_TAG, "onResume")

        if (mIsInvalidState) return

        mTermuxTerminalSessionActivityClient?.onResume()

        mTermuxTerminalViewClient?.onResume()

        // Refresh the terminal background (e.g. the user changed it in Settings or the system
        // wallpaper changed while we were in the background).
        syncTerminalBackground(false)

        // Pick up an interface-transparency change made in Settings.
        syncInterfaceTransparency()

        // Pick up snippets saved in the snippet editor.
        refreshSnippetChips()

        // Apply an extra-keys preset change made in Settings, for either key grid. A custom
        // extra-keys / extra-keys-page2 property in the properties file keeps winning over presets
        // (see TermuxTerminalExtraKeys.setExtraKeys()).
        val extraKeysPreset = mPreferences.getExtraKeysPreset()
        val extraKeysPage2Preset = mPreferences.getExtraKeysPage2Preset()
        if (extraKeysPreset != mAppliedExtraKeysPreset || extraKeysPage2Preset != mAppliedExtraKeysPage2Preset) {
            mAppliedExtraKeysPreset = extraKeysPreset
            mAppliedExtraKeysPage2Preset = extraKeysPage2Preset
            reloadProperties()
            mTermuxTerminalExtraKeys?.let {
                it.reloadExtraKeys()
                reloadExtraKeysViews()
                updateExtraKeysBar()
            }
        }

        // Apply an appearance change made while this activity was not visible (from the Style screen
        // or Settings). The ACTION_RELOAD_STYLE broadcast is dropped while the activity is stopped, so
        // the stored appearance revision is the reliable signal.
        if (AppearanceStyling.hasChanged(this, mAppliedAppearanceRevision)) {
            mAppliedAppearanceRevision = AppearanceStyling.revision(this)
            reloadActivityStyling(false)
        }

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG)

        mIsOnResumeAfterOnCreate = false
    }

    override fun onStop() {
        super.onStop()

        Logger.logDebug(LOG_TAG, "onStop")

        if (mIsInvalidState) return

        mIsVisible = false

        mTermuxTerminalSessionActivityClient?.onStop()

        mTermuxTerminalViewClient?.onStop()

        removeTermuxActivityRootViewGlobalLayoutListener()

        unregisterTermuxActivityBroadcastReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()

        Logger.logDebug(LOG_TAG, "onDestroy")

        if (mIsInvalidState) return

        val service = mTermuxService
        if (service != null) {
            // Do not leave service and session clients with references to activity.
            service.unsetTermuxTerminalSessionClient()
            mTermuxService = null
        }

        try {
            unbindService(this)
        } catch (e: Exception) {
            // ignore.
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState")

        super.onSaveInstanceState(outState)
        saveTerminalToolbarTextInput(outState)
        outState.putBoolean(ARG_ACTIVITY_RECREATED, true)
    }


    /**
     * Part of the [ServiceConnection] interface. The service is bound with
     * [.bindService] in [.onCreate] which will cause a call to this
     * callback method.
     */
    override fun onServiceConnected(componentName: ComponentName?, service: IBinder) {
        Logger.logDebug(LOG_TAG, "onServiceConnected")

        val termuxService = (service as TermuxService.LocalBinder).service
        mTermuxService = termuxService

        refreshSessionsBar()

        val startedIntent = intent
        setIntent(null)

        if (termuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                mLaunchFailsafe = startedIntent?.extras?.getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false) ?: false

                if (TermuxInstaller.isBootstrapInstalled()) {
                    // Existing install: start a session straight away (setupBootstrapIfNeeded
                    // returns immediately when the prefix is present).
                    TermuxInstaller.setupBootstrapIfNeeded(this) { addSessionAfterBootstrap() }
                } else {
                    // First run: show the onboarding overlay instead of dropping straight into the
                    // bootstrap install dialog. The overlay's Install action calls
                    // startFirstRunBootstrapInstall().
                    mFirstRunState?.visible = true
                    requestStoragePermission(false)
                }
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing()
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && startedIntent != null && Intent.ACTION_RUN == startedIntent.action) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                val isFailSafe = startedIntent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
                mTermuxTerminalSessionActivityClient!!.addNewSession(isFailSafe, null)
            } else {
                mTermuxTerminalSessionActivityClient!!.setCurrentSession(
                    mTermuxTerminalSessionActivityClient!!.getCurrentStoredSessionOrLast(),
                )
            }
        }

        // Update the [TerminalSession] and [TerminalEmulator] clients.
        termuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient!!)
    }

    /** Adds the first session once the bootstrap install has finished. */
    private fun addSessionAfterBootstrap() {
        if (mTermuxService == null) return // Activity might have been destroyed.
        try {
            mTermuxTerminalSessionActivityClient!!.addNewSession(mLaunchFailsafe, null)
        } catch (e: WindowManager.BadTokenException) {
            // Activity finished - ignore.
        }
    }

    /** Starts the bootstrap install from the first-run overlay; the first session appears when done. */
    fun startFirstRunBootstrapInstall() {
        mFirstRunState?.beginInstall()
        TermuxInstaller.setupBootstrapIfNeeded(this, object : TermuxInstaller.BootstrapProgressListener {
            override fun onStage(stage: BootstrapStage) {
                runOnUiThread {
                    mFirstRunState?.stage = stage
                }
            }

            override fun onFailure() {
                // Without this the overlay would keep showing progress after the installer gave up.
                runOnUiThread {
                    mFirstRunState?.installFailed()
                }
            }
        }, Runnable { addSessionAfterBootstrap() })
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected")

        // Respect being stopped from the [TermuxService] notification action.
        finishActivityIfNotFinishing()
    }


    private fun reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk()

        mTermuxTerminalViewClient?.onReloadProperties()
    }


    private fun setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode())

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true)
    }

    private fun setMargins() {
        val relativeLayout: RelativeLayout = findViewById(R.id.activity_termux_root_relative_layout)
        // The terminal margin is applied to the terminal area inside the Compose shell (see
        // ShellHost), not to the shell itself. Insetting the shell would expose a strip of the decor
        // background - which TermuxTerminalSessionActivityClient sets to the terminal background
        // colour - above the sessions bar and below the extra-keys bar, i.e. a thin line of the
        // terminal colour over the chrome.
        ViewUtils.setLayoutMarginsInDp(relativeLayout, 0, 0, 0, 0)
        mShellChromeState?.updateTerminalMargins(
            mProperties.getTerminalMarginHorizontal(), mProperties.getTerminalMarginVertical(),
        )
    }


    fun addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().viewTreeObserver.addOnGlobalLayoutListener(getTermuxActivityRootView())
    }

    fun removeTermuxActivityRootViewGlobalLayoutListener() {
        if (this::mTermuxActivityRootView.isInitialized)
            getTermuxActivityRootView().viewTreeObserver.removeOnGlobalLayoutListener(getTermuxActivityRootView())
    }


    private fun setTermuxTerminalViewAndClients(savedInstanceState: Bundle?) {
        // Set termux terminal view and session clients
        val sessionActivityClient = TermuxTerminalSessionActivityClient(this)
        val viewClient = TermuxTerminalViewClient(this, sessionActivityClient)
        mTermuxTerminalSessionActivityClient = sessionActivityClient
        mTermuxTerminalViewClient = viewClient

        // Set termux terminal view
        mTerminalView = layoutInflater.inflate(R.layout.view_terminal, null) as TerminalView
        mTerminalView.setTerminalViewClient(viewClient)

        // Set the extra-keys client. It is created before the Compose shell is installed because the
        // shell's extra-keys bar calls back into it as soon as its first frame is composed.
        mTermuxTerminalExtraKeys = TermuxTerminalExtraKeys(
            this, mTerminalView,
            viewClient, sessionActivityClient,
        )
        mAppliedExtraKeysPreset = mPreferences.getExtraKeysPreset()
        mAppliedExtraKeysPage2Preset = mPreferences.getExtraKeysPage2Preset()
        mAppliedAppearanceRevision = AppearanceStyling.revision(this)

        // Base height of a single extra-key row, in pixels. The total grid height scales with the
        // number of rows and the terminal-toolbar height scale factor (see updateExtraKeysBar).
        mTerminalToolbarDefaultHeight = resources.getDimensionPixelSize(R.dimen.terminal_toolbar_extra_keys_row_height).toFloat()

        // Host the terminal and the extra-keys bar through the Compose shell. The terminal view is
        // the single leaf Android view inside the Compose tree.
        val sessionsState = SessionsState()
        mSessionsState = sessionsState
        val extraKeysState = ExtraKeysState()
        mExtraKeysState = extraKeysState
        extraKeysState.visible = mPreferences.shouldShowTerminalToolbar()
        updateExtraKeysBar()

        val snackbarState = SnackbarState()
        mSnackbarState = snackbarState
        val terminalBackgroundState = TerminalBackgroundState()
        mTerminalBackgroundState = terminalBackgroundState
        val shellChromeState = ShellChromeState()
        mShellChromeState = shellChromeState
        mWallpaperSource = AndroidWallpaperSource(this)
        // The shell chrome state now exists, so the terminal margins (read in setMargins()) can be
        // pushed into the Compose shell. The earlier setMargins() call in onCreate() only zeroed the
        // layout margins, because this state did not exist yet.
        setMargins()
        syncInterfaceTransparency()
        val firstRunState = FirstRunState()
        mFirstRunState = firstRunState
        // Only a cold start shows the intro; rotation and process restore go straight to the shell.
        shellChromeState.splashVisible = savedInstanceState == null
        val paletteState = PaletteState()
        mPaletteState = paletteState
        val uiPreferences = AndroidUiPreferences(this)
        mUiPreferences = uiPreferences
        mSnippetStore = SnippetStore(uiPreferences)
        val snippetChipsState = SnippetChipsState()
        mSnippetChipsState = snippetChipsState
        val linkActionState = LinkActionState()
        mLinkActionState = linkActionState
        refreshSnippetChips()

        val savedTextInput = savedInstanceState?.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT)

        val terminalShellHost: ComposeView = findViewById(R.id.terminal_shell_host)
        installTerminalShellHost(
            terminalShellHost, mTerminalView, sessionsState,
            extraKeysState, snackbarState, terminalBackgroundState, shellChromeState, firstRunState,
            paletteState, snippetChipsState, linkActionState, savedTextInput, mShellHostCallbacks,
        )

        mTermuxTerminalViewClient?.onCreate()

        mTermuxTerminalSessionActivityClient?.onCreate()
    }

    /** Reloads the snippet chips from the store and applies the row visibility preference. */
    private fun refreshSnippetChips() {
        val chipsState = mSnippetChipsState ?: return
        val snippetStore = mSnippetStore ?: return
        val uiPreferences = mUiPreferences ?: return
        chipsState.items = snippetStore.load()
        chipsState.rowVisible = uiPreferences.getBoolean(UiPreferenceKeys.SNIPPETS_ROW, true)
    }

    /**
     * Rebuilds the top sessions bar snapshot from the service session list and the currently
     * attached session. Called on service connect and whenever the session list or titles change.
     */
    fun refreshSessionsBar() {
        val sessionsState = mSessionsState ?: return

        val sessions = mTermuxService?.getTermuxSessions()
        val currentSession = getCurrentSession()

        mSessionsById.clear()
        if (sessions == null) {
            sessionsState.starterCommands = starterChips(true, TermuxInstaller.isBootstrapInstalled())
            sessionsState.sessions = ArrayList()
            return
        }

        val snapshots = ArrayList<SessionSnapshot>()
        val ids = ArrayList<Int>()
        val contexts = HashMap<Int, String>()
        var activeIndex = -1

        for (i in sessions.indices) {
            val termuxSession = sessions[i]
            val terminalSession: TerminalSession? = termuxSession.getTerminalSession()
            if (terminalSession == null) continue

            val stableId = mSessionIds.idFor(terminalSession)
            mSessionsById[stableId] = termuxSession
            snapshots.add(
                SessionSnapshot(
                    terminalSession.mSessionName,
                    terminalSession.getTitle(),
                    terminalSession.isRunning(),
                    terminalSession.getExitStatus(),
                ),
            )
            ids.add(stableId)
            val isActive = terminalSession === currentSession
            if (isActive) {
                activeIndex = snapshots.size - 1
            }

            val subtitle = tabSubtitle(
                terminalSession.getCwd(),
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                if (isActive) readForegroundCommand(terminalSession.getPid()) else null,
            )
            if (subtitle != null) contexts[stableId] = subtitle
        }

        sessionsState.starterCommands = if (snapshots.isEmpty())
            starterChips(true, TermuxInstaller.isBootstrapInstalled())
        else
            Collections.emptyList()
        sessionsState.unseen = mSessionOutputTracker.unseen()
        sessionsState.sessions = sessionItems(
            snapshots, ids, activeIndex, contexts, mSessionOutputTracker.unseen(),
        )
    }

    /** Returns the session with the given stable bar id, or `null` if it was closed. */
    @Nullable
    private fun getTermuxSessionById(id: Int): TermuxSession? {
        return mSessionsById[id]
    }

    /**
     * Records output from [session] for the sessions bar. Skipped for the active tab, and for an id
     * that is already marked, so a chatty background session costs one state write, not one per
     * chunk.
     */
    fun recordSessionOutput(session: TerminalSession) {
        if (mSessionsState == null || session == getCurrentSession()) return
        val id = mSessionIds.idFor(session)
        if (mSessionOutputTracker.hasUnseen(id)) return
        mSessionOutputTracker.recordOutput(id, false)
        mSessionsState?.markUnseen(id)
    }

    private fun selectSessionById(id: Int) {
        mSessionOutputTracker.clear(id)
        mSessionsState?.markSelected(id)
        val client = mTermuxTerminalSessionActivityClient ?: return
        val termuxSession = getTermuxSessionById(id) ?: return
        val terminalSession = termuxSession.getTerminalSession() ?: return
        client.setCurrentSession(terminalSession)
    }

    private fun renameSessionById(id: Int) {
        val client = mTermuxTerminalSessionActivityClient ?: return
        val termuxSession = getTermuxSessionById(id) ?: return
        val session: TerminalSession = termuxSession.getTerminalSession() ?: return

        val oldName = session.mSessionName
        client.renameSession(session, Runnable {
            mSnackbarUndoAction = Runnable {
                mTermuxTerminalSessionActivityClient?.renameSession(session, oldName)
                refreshSessionsBar()
            }
            mSnackbarState?.post(getString(R.string.msg_session_renamed), getString(R.string.action_undo))
        })
    }

    private fun closeSessionById(id: Int) {
        mSessionOutputTracker.remove(id)
        val client = mTermuxTerminalSessionActivityClient ?: return
        val termuxSession = getTermuxSessionById(id) ?: return
        val session: TerminalSession = termuxSession.getTerminalSession() ?: return

        if (session.isRunning()) {
            // Confirm before closing a session whose process is still running.
            showCloseSessionDialog(session)
        } else {
            client.closeSession(session)
            mSnackbarState?.post(getString(R.string.msg_session_closed))
        }
    }

    private fun moveSessionById(id: Int, toIndex: Int) {
        val service = mTermuxService ?: return
        val termuxSession = getTermuxSessionById(id) ?: return
        val terminalSession: TerminalSession = termuxSession.getTerminalSession() ?: return

        val fromIndex = service.getIndexOfSession(terminalSession)
        if (fromIndex < 0) return
        if (fromIndex == toIndex) return
        service.moveTermuxSession(fromIndex, toIndex)
        refreshSessionsBar()
    }


    /**
     * Updates the Compose extra-keys bar state from the active key sets: each grid's height is the
     * per-row height times its own number of rows and the configured height scale factor.
     */
    private fun updateExtraKeysBar() {
        val extraKeysState = mExtraKeysState ?: return

        val rowHeight = mTerminalToolbarDefaultHeight * mProperties.getTerminalToolbarHeightScaleFactor()

        val rows = mTermuxTerminalExtraKeys?.getExtraKeysInfo()?.getMatrix()?.size ?: 0
        extraKeysState.gridHeightPx = Math.round(rowHeight * rows)

        val rowsPage2 = mTermuxTerminalExtraKeys?.getExtraKeysInfoPage2()?.getMatrix()?.size ?: 0
        extraKeysState.grid2HeightPx = Math.round(rowHeight * rowsPage2)
    }

    /**
     * Applies the configured terminal background to the Compose state. In solid mode the picture is
     * cleared; in wallpaper mode the system wallpaper is decoded on a background thread and pushed
     * in when ready. The dim, blur and opacity are applied immediately.
     *
     * @param forceReload Reload the picture even if one is already showing (e.g. the wallpaper changed).
     */
    fun syncTerminalBackground(forceReload: Boolean) {
        val terminalBackgroundState = mTerminalBackgroundState ?: return
        if (!this::mPreferences.isInitialized) return

        val wallpaperMode = mPreferences.shouldUseWallpaperTerminalBackground()
        val imageMode = mPreferences.shouldUseCustomImageTerminalBackground()
        val dim = mPreferences.getTerminalBackgroundDim()
        val blur = BackgroundFx.clampBlurRadius(mPreferences.getTerminalBackgroundBlur())
        val opacity = BackgroundFx.clampOpacity(mPreferences.getTerminalBackgroundOpacity())

        if (!wallpaperMode && !imageMode) {
            mTerminalBackgroundLoading = false
            mTerminalBackgroundLoadedBlur = 0f
            if (terminalBackgroundState.bitmap != null)
                terminalBackgroundState.update(null, dim)
            return
        }

        // Below API 31 the blur is a load-time downscale of the bitmap, so a changed radius has to
        // reload the picture; above it the RenderEffect is applied per draw, so only the state moves.
        val blurChanged = terminalBackgroundState.bitmap != null &&
            blur != mTerminalBackgroundLoadedBlur

        if (forceReload || (blurChanged && Build.VERSION.SDK_INT < Build.VERSION_CODES.S))
            terminalBackgroundState.update(null, dim)
        else terminalBackgroundState.update(terminalBackgroundState.bitmap, dim, blur, opacity)

        if (terminalBackgroundState.bitmap != null || mTerminalBackgroundLoading) return
        mTerminalBackgroundLoading = true

        Thread({
            val backgroundBitmap: Bitmap? = mWallpaperSource.downscaleForBlur(
                if (wallpaperMode) mWallpaperSource.systemWallpaper() else mWallpaperSource.customImage(mPreferences.getTerminalBackgroundImagePath()), blur,
            )
            runOnUiThread {
                mTerminalBackgroundLoading = false
                val currentState = mTerminalBackgroundState ?: return@runOnUiThread
                // The radius is baked into the bitmap by the load-time downscale, so the state keeps
                // the request-time value; dim and opacity apply per draw, so read their current
                // preferences in case the user moved a slider while the picture was loading.
                val currentDim = if (!this::mPreferences.isInitialized)
                    dim else mPreferences.getTerminalBackgroundDim()
                val currentOpacity = if (!this::mPreferences.isInitialized)
                    opacity else BackgroundFx.clampOpacity(mPreferences.getTerminalBackgroundOpacity())
                mTerminalBackgroundLoadedBlur = blur
                currentState.update(backgroundBitmap, currentDim, blur, currentOpacity)
            }
        }, "terminal-background-loader").start()
    }

    /**
     * Applies the configured interface transparency (0-100) to the Compose shell chrome state, so
     * the sessions tab bar and the extra-keys bar fade over the terminal background.
     */
    fun syncInterfaceTransparency() {
        val shellChromeState = mShellChromeState ?: return
        if (!this::mPreferences.isInitialized) return
        shellChromeState.update(mPreferences.getInterfaceTransparency())
    }


    /** Whether the system wallpaper is configured as the terminal background. */
    fun shouldUseWallpaperTerminalBackground(): Boolean {
        return this::mPreferences.isInitialized && mPreferences.shouldUseWallpaperTerminalBackground()
    }

    /** Whether a custom image is configured as the terminal background. */
    fun shouldUseCustomImageTerminalBackground(): Boolean {
        return this::mPreferences.isInitialized && mPreferences.shouldUseCustomImageTerminalBackground()
    }

    fun toggleTerminalToolbar() {
        val extraKeysState = mExtraKeysState ?: return

        val showNow = mPreferences.toogleShowTerminalToolbar()
        Logger.showToast(this, (if (showNow) getString(R.string.msg_enabling_terminal_toolbar) else getString(R.string.msg_disabling_terminal_toolbar)), true)
        extraKeysState.visible = showNow
        if (!showNow) setExtraKeysTextInputVisible(false)
    }

    /** Shows or hides the inline text-send field, moving focus where the soft keyboard follows it. */
    private fun setExtraKeysTextInputVisible(visible: Boolean) {
        val extraKeysState = mExtraKeysState ?: return

        extraKeysState.textInputVisible = visible
        if (!visible && this::mTerminalView.isInitialized) mTerminalView.requestFocus()
    }

    private fun saveTerminalToolbarTextInput(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return

        val textInputView: EditText? = findViewById(R.id.terminal_toolbar_text_input)
        if (textInputView != null) {
            val textInput = textInputView.text.toString()
            if (textInput.isNotEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput)
        }
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Ctrl+Shift+K opens the palette. Plain Ctrl+K and Ctrl+Alt+K are left to the shell, where
        // they reach readline as kill-line. Handled here rather than per-view so it works whether
        // the terminal or the extra-keys bar has focus.
        if (mPaletteState != null &&
            event.action == KeyEvent.ACTION_DOWN &&
            isPaletteShortcut(
                event.keyCode, event.isCtrlPressed,
                event.isShiftPressed, event.isAltPressed,
            )
        ) {
            // A second press while the palette is up must not reopen it: open() resets the query,
            // so the user's filter would be wiped by a stray keypress.
            if (mPaletteState?.visible == false) mPaletteState?.open()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Deliberately does not call super.onBackPressed(): the finish-if-not-finishing guard below is
    // the intended back behavior and calling super would finish() unconditionally.
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // Back dismisses the palette instead of leaving the activity while it is up.
        if (mPaletteState?.visible == true) {
            mPaletteState?.close()
            return
        }
        finishActivityIfNotFinishing()
    }

    fun finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!isFinishing) {
            finish()
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    fun showToast(text: String?, longDuration: Boolean) {
        if (text == null || text.isEmpty()) return
        mLastToast?.cancel()
        mLastToast = Toast.makeText(this, text, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        mLastToast?.setGravity(Gravity.TOP, 0, 0)
        mLastToast?.show()
    }


    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo?) {
        val currentSession = getCurrentSession() ?: return

        val autoFillEnabled = mTerminalView.isAutoFillEnabled()

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url)
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript)
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text)
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username)
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password)
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal)
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getString(R.string.action_kill_process, currentSession.getPid())).setEnabled(currentSession.isRunning())
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal)
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn())
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help)
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings)
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue)
    }

    /** Hook system menu to show context menu instead. */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        mTerminalView.showContextMenu()
        return false
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val session = getCurrentSession()

        return when (item.itemId) {
            CONTEXT_MENU_SELECT_URL_ID -> {
                mTermuxTerminalViewClient!!.showUrlSelection()
                true
            }
            CONTEXT_MENU_SHARE_TRANSCRIPT_ID -> {
                mTermuxTerminalViewClient!!.shareSessionTranscript()
                true
            }
            CONTEXT_MENU_SHARE_SELECTED_TEXT -> {
                mTermuxTerminalViewClient!!.shareSelectedText()
                true
            }
            CONTEXT_MENU_AUTOFILL_USERNAME -> {
                mTerminalView.requestAutoFillUsername()
                true
            }
            CONTEXT_MENU_AUTOFILL_PASSWORD -> {
                mTerminalView.requestAutoFillPassword()
                true
            }
            CONTEXT_MENU_RESET_TERMINAL_ID -> {
                onResetTerminalSession(session)
                true
            }
            CONTEXT_MENU_KILL_PROCESS_ID -> {
                showKillSessionDialog(session)
                true
            }
            CONTEXT_MENU_STYLING_ID -> {
                launchAppearanceScreen()
                true
            }
            CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON -> {
                toggleKeepScreenOn()
                true
            }
            CONTEXT_MENU_HELP_ID -> {
                ActivityUtils.startActivity(this, Intent(this, HelpActivity::class.java))
                true
            }
            CONTEXT_MENU_SETTINGS_ID -> {
                ActivityUtils.startActivity(this, Intent(this, SettingsActivity::class.java))
                true
            }
            CONTEXT_MENU_REPORT_ID -> {
                mTermuxTerminalViewClient!!.reportIssueFromTranscript()
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onContextMenuClosed(menu: Menu) {
        super.onContextMenuClosed(menu)
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu)
    }

    private fun showKillSessionDialog(session: TerminalSession?) {
        if (session == null) return

        val b = AlertDialog.Builder(this)
        b.setIcon(android.R.drawable.ic_dialog_alert)
        b.setMessage(R.string.title_confirm_kill_process)
        b.setPositiveButton(android.R.string.yes) { dialog, id ->
            dialog.dismiss()
            session.finishIfRunning()
        }
        b.setNegativeButton(android.R.string.no, null)
        b.show()
    }

    private fun showCloseSessionDialog(session: TerminalSession?) {
        if (session == null) return

        val b = AlertDialog.Builder(this)
        b.setIcon(android.R.drawable.ic_dialog_alert)
        b.setMessage(R.string.title_confirm_close_session)
        b.setPositiveButton(android.R.string.yes) { dialog, id ->
            dialog.dismiss()
            mTermuxTerminalSessionActivityClient!!.closeSession(session)
            mSnackbarState?.post(getString(R.string.msg_session_closed))
        }
        b.setNegativeButton(android.R.string.no, null)
        b.show()
    }

    private fun onResetTerminalSession(session: TerminalSession?) {
        if (session != null) {
            session.reset()
            showToast(resources.getString(R.string.msg_terminal_reset), true)

            mTermuxTerminalSessionActivityClient?.onResetTerminalSession()
        }
    }

    /**
     * Shows the visual bell: the sessions bar lights up for a moment and a snackbar names the
     * event. The snackbar itself is throttled by [SnackbarState.showBell], so a
     * program ringing on every prompt cannot bury the terminal.
     */
    fun showBellFlash() {
        mShellChromeState?.flashBell()
        mSnackbarState?.showBell(getString(R.string.msg_bell))
    }

    /**
     * Opens [url] directly, or shows the link action sheet when the user asked to confirm first.
     * Called from the terminal view client for both a tapped URL and a link click.
     */
    fun openUrlOrShowLinkActions(url: String) {
        val askFirst = mUiPreferences?.getBoolean(UiPreferenceKeys.LINK_ACTION_SHEET, true) ?: true
        val linkActionState = mLinkActionState
        if (!askFirst || linkActionState == null) {
            ShareUtils.openUrl(this, url)
            return
        }
        linkActionState.show(url)
    }

    private fun launchAppearanceScreen() {
        val intent = Intent(this, AppearanceActivity::class.java)
        // Hand the current output to the preview so a scheme is judged against real text rather
        // than a fixed sample; without a session the screen keeps its sample.
        val session = getCurrentSession()
        val emulator = session?.getEmulator()
        if (emulator != null) {
            val lines: List<String> = buildPreviewLines(
                emulator.getScreen().getTranscriptText(),
            )
            intent.putStringArrayListExtra(AppearanceActivity.EXTRA_PREVIEW_LINES, ArrayList(lines))
        }
        ActivityUtils.startActivity(this, intent)
    }

    private fun toggleKeepScreenOn() {
        if (mTerminalView.keepScreenOn) {
            mTerminalView.keepScreenOn = false
            mPreferences.setKeepScreenOn(false)
        } else {
            mTerminalView.keepScreenOn = true
            mPreferences.setKeepScreenOn(true)
        }
    }


    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    fun requestStoragePermission(isPermissionCallback: Boolean) {
        object : Thread() {
            override fun run() {
                // Do not ask for permission again
                val requestCode = if (isPermissionCallback) -1 else PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION

                // If permission is granted, then also setup storage symlinks.
                if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                        this@TermuxActivity, requestCode, !isPermissionCallback,
                    )
                ) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(
                            this@TermuxActivity, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request),
                        )

                    TermuxInstaller.setupStorageSymlinks(this@TermuxActivity)
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(
                            this@TermuxActivity, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request),
                        )
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, @Nullable data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: " + resultCode + ", data: " + IntentUtils.getIntentString(data))
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, @NonNull permissions: Array<String>, @NonNull grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: " + Arrays.toString(permissions) + ", grantResults: " + Arrays.toString(grantResults))
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }


    fun getNavBarHeight(): Int {
        return mNavBarHeight
    }

    fun getTermuxActivityRootView(): TermuxActivityRootView {
        return mTermuxActivityRootView
    }

    fun getTermuxActivityBottomSpaceView(): View? {
        return mTermuxActivityBottomSpaceView
    }

    fun getExtraKeysView(): ExtraKeysView? {
        return mExtraKeysView
    }

    fun getTermuxTerminalExtraKeys(): TermuxTerminalExtraKeys? {
        return mTermuxTerminalExtraKeys
    }

    fun setExtraKeysView(extraKeysView: ExtraKeysView?) {
        mExtraKeysView = extraKeysView
    }

    fun getExtraKeysView2(): ExtraKeysView? {
        return mExtraKeysView2
    }

    fun setExtraKeysView2(extraKeysView: ExtraKeysView?) {
        mExtraKeysView2 = extraKeysView
    }

    /**
     * Returns the key layout a key-grid page renders: the `extra-keys` layout for
     * [ExtraKeysState.PAGE_KEYS] and the independent `extra-keys-page2` layout for
     * [ExtraKeysState.PAGE_KEYS_2].
     */
    private fun extraKeysInfoForPage(page: Int): ExtraKeysInfo? {
        val extraKeys = mTermuxTerminalExtraKeys ?: return null
        if (page == ExtraKeysState.PAGE_KEYS_2) return extraKeys.getExtraKeysInfoPage2()
        return extraKeys.getExtraKeysInfo()
    }

    /**
     * Returns the modifier state map shared by both key grids. The first grid to be inflated
     * creates it, so its own states (which read the pressed/unpressed tint from a view of the same
     * theme) are reused by the second grid; passing the same map to both means a modifier toggled
     * on one page applies on the other.
     */
    private fun getSharedExtraKeysSpecialButtons(extraKeysView: ExtraKeysView): Map<SpecialButton, SpecialButtonState> {
        var specialButtons = mExtraKeysSpecialButtons
        if (specialButtons == null) {
            specialButtons = extraKeysView.getDefaultSpecialButtons(extraKeysView)
            mExtraKeysSpecialButtons = specialButtons
        }
        return specialButtons
    }

    /** Reloads every live key grid with the layout of its own page. */
    private fun reloadExtraKeysViews() {
        val extraKeys = mTermuxTerminalExtraKeys
        if (mExtraKeysView != null)
            mExtraKeysView?.reload(extraKeys?.getExtraKeysInfo(), mTerminalToolbarDefaultHeight)
        if (mExtraKeysView2 != null)
            mExtraKeysView2?.reload(extraKeys?.getExtraKeysInfoPage2(), mTerminalToolbarDefaultHeight)
    }

    fun getTerminalToolbarDefaultHeight(): Float {
        return mTerminalToolbarDefaultHeight
    }

    fun isTerminalViewSelected(): Boolean {
        return !isTerminalToolbarTextInputViewSelected()
    }

    fun isTerminalToolbarTextInputViewSelected(): Boolean {
        return mExtraKeysState?.textInputVisible == true
    }


    fun termuxSessionListNotifyUpdated() {
        refreshSessionsBar()
    }

    fun isVisible(): Boolean {
        return mIsVisible
    }

    fun isOnResumeAfterOnCreate(): Boolean {
        return mIsOnResumeAfterOnCreate
    }

    fun isActivityRecreated(): Boolean {
        return mIsActivityRecreated
    }


    fun getTermuxService(): TermuxService? {
        return mTermuxService
    }

    fun getTerminalView(): TerminalView {
        return mTerminalView
    }

    fun getTermuxTerminalViewClient(): TermuxTerminalViewClient? {
        return mTermuxTerminalViewClient
    }

    fun getTermuxTerminalSessionClient(): TermuxTerminalSessionActivityClient? {
        return mTermuxTerminalSessionActivityClient
    }

    @Nullable
    fun getCurrentSession(): TerminalSession? {
        return if (this::mTerminalView.isInitialized)
            mTerminalView.getCurrentSession()
        else
            null
    }

    fun getPreferences(): TermuxAppSharedPreferences {
        return mPreferences
    }

    fun getProperties(): TermuxAppSharedProperties {
        return mProperties
    }


    private fun registerTermuxActivityBroadcastReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH)
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS)
        intentFilter.addAction(Intent.ACTION_WALLPAPER_CHANGED)

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter)
    }

    private fun unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver)
    }

    private fun fixTermuxActivityBroadcastReceiverIntent(intent: Intent) {
        val extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
        if ("storage" == extraReloadStyle) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
            intent.action = TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS
        }
    }

    inner class TermuxActivityBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent == null) return

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent)

                when (intent.action) {
                    TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH -> {
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash")
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG)
                        return
                    }
                    TERMUX_ACTIVITY.ACTION_RELOAD_STYLE -> {
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling")
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true))
                        return
                    }
                    TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS -> {
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions")
                        requestStoragePermission(false)
                        return
                    }
                    Intent.ACTION_WALLPAPER_CHANGED -> {
                        Logger.logDebug(LOG_TAG, "Received intent that the system wallpaper changed")
                        syncTerminalBackground(true)
                        return
                    }
                }
            }
        }
    }

    private fun reloadActivityStyling(recreateActivity: Boolean) {
        if (this::mProperties.isInitialized) {
            reloadProperties()

            val allCaps = mProperties.shouldExtraKeysTextBeAllCaps()
            mExtraKeysView?.setButtonTextAllCaps(allCaps)
            mExtraKeysView2?.setButtonTextAllCaps(allCaps)
            reloadExtraKeysViews()

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode())
        }

        setMargins()
        updateExtraKeysBar()
        syncTerminalBackground(true)

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this)

        mTermuxTerminalSessionActivityClient?.onReloadActivityStyling()

        mTermuxTerminalViewClient?.onReloadActivityStyling()

        // To change the activity theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity")
            recreate()
        }
    }

    companion object {
        private const val CONTEXT_MENU_SELECT_URL_ID = 0
        private const val CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1
        private const val CONTEXT_MENU_SHARE_SELECTED_TEXT = 10
        private const val CONTEXT_MENU_AUTOFILL_USERNAME = 11
        private const val CONTEXT_MENU_AUTOFILL_PASSWORD = 2
        private const val CONTEXT_MENU_RESET_TERMINAL_ID = 3
        private const val CONTEXT_MENU_KILL_PROCESS_ID = 4
        private const val CONTEXT_MENU_STYLING_ID = 5
        private const val CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6
        private const val CONTEXT_MENU_HELP_ID = 7
        private const val CONTEXT_MENU_SETTINGS_ID = 8
        private const val CONTEXT_MENU_REPORT_ID = 9

        private const val ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input"
        private const val ARG_ACTIVITY_RECREATED = "activity_recreated"

        private const val LOG_TAG = "TermuxActivity"

        @JvmStatic
        fun updateTermuxActivityStyling(context: Context, recreateActivity: Boolean) {
            // Make sure that terminal styling is always applied.
            val stylingIntent = Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
            stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity)
            context.sendBroadcast(stylingIntent)
        }

        @JvmStatic
        fun startTermuxActivity(@NonNull context: Context) {
            ActivityUtils.startActivity(context, newInstance(context))
        }

        @JvmStatic
        fun newInstance(@NonNull context: Context): Intent {
            val intent = Intent(context, TermuxActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            return intent
        }
    }
}
