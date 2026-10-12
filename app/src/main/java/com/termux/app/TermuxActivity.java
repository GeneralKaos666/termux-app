package com.termux.app;

import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.app.ui.ShellHostCallbacks;
import com.termux.app.ui.ShellHostKt;
import com.termux.app.ui.SnackbarState;
import com.termux.app.ui.background.TerminalBackgroundState;
import com.termux.app.ui.firstrun.FirstRunState;
import com.termux.app.ui.keys.ExtraKeysState;
import com.termux.app.ui.keys.QuickAction;
import com.termux.app.ui.sessions.SessionItem;
import com.termux.app.ui.sessions.SessionItemKt;
import com.termux.app.ui.sessions.SessionsState;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.io.FullScreenWorkAround;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The Compose-observable snapshot of the sessions shown in the top sessions bar.
     */
    private SessionsState mSessionsState;

    /**
     * Compose-observable snackbar requests shown by the shell host.
     */
    private SnackbarState mSnackbarState;

    /**
     * Compose-observable terminal background picture (null = solid background colour).
     */
    private TerminalBackgroundState mTerminalBackgroundState;

    /**
     * Whether a background wallpaper decode is currently running on the background thread.
     */
    private boolean mTerminalBackgroundLoading;

    /**
     * Compose-observable first-run onboarding state (overlay + install progress).
     */
    private FirstRunState mFirstRunState;

    /**
     * Whether the first session to be created after the bootstrap install should be a failsafe session.
     */
    private boolean mLaunchFailsafe;

    /**
     * The extra-keys preset last applied to the key matrix, used to detect Settings changes on resume.
     */
    private String mAppliedExtraKeysPreset = "";

    /**
     * The action to run if the user presses undo on the current snackbar, or {@code null}.
     */
    private Runnable mSnackbarUndoAction;

    /**
     * The callbacks from the Compose sessions bar back into this activity.
     */
    private final ShellHostCallbacks mShellHostCallbacks = new ShellHostCallbacks() {
        @Override
        public void onSessionSelected(int id) {
            selectSessionAt(id);
        }

        @Override
        public void onSessionRename(int id) {
            renameSessionAt(id);
        }

        @Override
        public void onSessionClose(int id) {
            closeSessionAt(id);
        }

        @Override
        public void onNewSession() {
            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
        }

        @Override
        public void onExtraKeysViewCreated(ExtraKeysView extraKeysView) {
            setExtraKeysView(extraKeysView);
            if (mTermuxTerminalExtraKeys == null) return;

            extraKeysView.setExtraKeysViewClient(mTermuxTerminalExtraKeys);
            extraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
            extraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);

            // Applied once, only while the bar is shown, matching the old toolbar behaviour.
            if (!mFullScreenWorkAroundApplied && mProperties.isUsingFullScreen() && mProperties.isUsingFullScreenWorkAround()) {
                mFullScreenWorkAroundApplied = true;
                FullScreenWorkAround.apply(TermuxActivity.this);
            }
        }

        @Override
        public void onExtraKeysViewReleased() {
            setExtraKeysView(null);
        }

        @Override
        public void onExtraKeysAction(QuickAction action) {
            switch (action) {
                case KEYBOARD:
                    if (mTermuxTerminalViewClient != null)
                        mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
                    break;
                case TEXT_INPUT:
                    setExtraKeysTextInputVisible(mExtraKeysState != null && !mExtraKeysState.getTextInputVisible());
                    break;
                case SETTINGS:
                    startActivity(new Intent(TermuxActivity.this, SettingsActivity.class));
                    break;

            }
        }

        @Override
        public void onExtraKeysTextSend(String text) {
            TerminalSession session = getCurrentSession();
            if (session == null) return;

            if (session.isRunning()) {
                session.write(text.length() == 0 ? "\r" : text);
            } else if (mTermuxTerminalSessionActivityClient != null) {
                mTermuxTerminalSessionActivityClient.removeFinishedSession(session);
            }
        }

        @Override
        public void onSnackbarUndo() {
            if (mSnackbarUndoAction != null) {
                mSnackbarUndoAction.run();
                mSnackbarUndoAction = null;
            }
        }

        @Override
        public void onFirstRunInstall() {
            startFirstRunBootstrapInstall();
        }
    };

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;

    /**
     * The Compose-observable state of the bottom extra-keys bar.
     */
    private ExtraKeysState mExtraKeysState;

    /**
     * Whether the fullscreen soft-keyboard workaround has already been applied.
     */
    private boolean mFullScreenWorkAroundApplied;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients(savedInstanceState);

        registerForContextMenu(mTerminalView);

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Refresh the terminal background (e.g. the user changed it in Settings or the system
        // wallpaper changed while we were in the background).
        syncTerminalBackground(false);

        // Apply an extra-keys preset change made in Settings. A custom extra-keys property in the
        // properties file keeps winning over presets (see TermuxTerminalExtraKeys.setExtraKeys()).
        String extraKeysPreset = mPreferences.getExtraKeysPreset();
        if (!extraKeysPreset.equals(mAppliedExtraKeysPreset)) {
            mAppliedExtraKeysPreset = extraKeysPreset;
            reloadProperties();
            if (mTermuxTerminalExtraKeys != null) {
                mTermuxTerminalExtraKeys.reloadExtraKeys();
                if (mExtraKeysView != null)
                    mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
                updateExtraKeysBar();
            }
        }

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        refreshSessionsBar();

        final Intent intent = getIntent();
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                mLaunchFailsafe = intent != null && intent.getExtras() != null
                    && intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);

                if (TermuxInstaller.isBootstrapInstalled()) {
                    // Existing install: start a session straight away (setupBootstrapIfNeeded
                    // returns immediately when the prefix is present).
                    TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> addSessionAfterBootstrap());
                } else {
                    // First run: show the onboarding overlay instead of dropping straight into the
                    // bootstrap install dialog. The overlay's Install action calls
                    // startFirstRunBootstrapInstall().
                    if (mFirstRunState != null) mFirstRunState.setVisible(true);
                    requestStoragePermission(false);
                }
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    /** Adds the first session once the bootstrap install has finished. */
    private void addSessionAfterBootstrap() {
        if (mTermuxService == null) return; // Activity might have been destroyed.
        try {
            mTermuxTerminalSessionActivityClient.addNewSession(mLaunchFailsafe, null);
        } catch (WindowManager.BadTokenException e) {
            // Activity finished - ignore.
        }
    }

    /** Starts the bootstrap install from the first-run overlay; the first session appears when done. */
    public void startFirstRunBootstrapInstall() {
        if (mFirstRunState != null) mFirstRunState.setInstalling(true);
        TermuxInstaller.setupBootstrapIfNeeded(this, this::addSessionAfterBootstrap);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients(Bundle savedInstanceState) {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = (TerminalView) getLayoutInflater().inflate(R.layout.view_terminal, null);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        // Set the extra-keys client. It is created before the Compose shell is installed because the
        // shell's extra-keys bar calls back into it as soon as its first frame is composed.
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);
        mAppliedExtraKeysPreset = mPreferences.getExtraKeysPreset();

        // Base height of a single extra-key row, in pixels. The total grid height scales with the
        // number of rows and the terminal-toolbar height scale factor (see updateExtraKeysBar).
        mTerminalToolbarDefaultHeight = getResources().getDimensionPixelSize(R.dimen.terminal_toolbar_extra_keys_row_height);

        // Host the terminal and the extra-keys bar through the Compose shell. The terminal view is
        // the single leaf Android view inside the Compose tree.
        mSessionsState = new SessionsState();
        mExtraKeysState = new ExtraKeysState();
        mExtraKeysState.setVisible(mPreferences.shouldShowTerminalToolbar());
        updateExtraKeysBar();

        mSnackbarState = new SnackbarState();
        mTerminalBackgroundState = new TerminalBackgroundState();
        mFirstRunState = new FirstRunState();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        ComposeView terminalShellHost = findViewById(R.id.terminal_shell_host);
        ShellHostKt.installTerminalShellHost(terminalShellHost, mTerminalView, mSessionsState,
            mExtraKeysState, mSnackbarState, mTerminalBackgroundState, mFirstRunState, savedTextInput, mShellHostCallbacks);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    /**
     * Rebuilds the top sessions bar snapshot from the service session list and the currently
     * attached session. Called on service connect and whenever the session list or titles change.
     */
    public void refreshSessionsBar() {
        if (mSessionsState == null) return;

        final List<SessionItem> items = new ArrayList<>();
        final List<TermuxSession> sessions = (mTermuxService != null) ? mTermuxService.getTermuxSessions() : null;
        final TerminalSession currentSession = getCurrentSession();

        if (sessions != null) {
            for (int i = 0; i < sessions.size(); i++) {
                TermuxSession termuxSession = sessions.get(i);
                TerminalSession terminalSession = (termuxSession != null) ? termuxSession.getTerminalSession() : null;
                if (terminalSession == null) continue;

                items.add(SessionItemKt.sessionItem(
                    i,
                    i + 1,
                    terminalSession.mSessionName,
                    terminalSession.getTitle(),
                    terminalSession.isRunning(),
                    terminalSession.getExitStatus(),
                    terminalSession == currentSession));
            }
        }

        mSessionsState.setSessions(items);
    }

    /** Returns the service session at {@code index}, or {@code null} if out of range. */
    @Nullable
    private TermuxSession getTermuxSessionAt(int index) {
        if (mTermuxService == null) return null;
        List<TermuxSession> sessions = mTermuxService.getTermuxSessions();
        if (index < 0 || index >= sessions.size()) return null;
        return sessions.get(index);
    }

    private void selectSessionAt(int index) {
        if (mTermuxTerminalSessionActivityClient == null) return;
        TermuxSession termuxSession = getTermuxSessionAt(index);
        if (termuxSession == null || termuxSession.getTerminalSession() == null) return;
        mTermuxTerminalSessionActivityClient.setCurrentSession(termuxSession.getTerminalSession());
    }

    private void renameSessionAt(int index) {
        if (mTermuxTerminalSessionActivityClient == null) return;
        TermuxSession termuxSession = getTermuxSessionAt(index);
        if (termuxSession == null || termuxSession.getTerminalSession() == null) return;

        final TerminalSession session = termuxSession.getTerminalSession();
        final String oldName = session.mSessionName;
        mTermuxTerminalSessionActivityClient.renameSession(session, () -> {
            mSnackbarUndoAction = () -> {
                if (mTermuxTerminalSessionActivityClient != null)
                    mTermuxTerminalSessionActivityClient.renameSession(session, oldName);
                refreshSessionsBar();
            };
            if (mSnackbarState != null)
                mSnackbarState.post(getString(R.string.msg_session_renamed), getString(R.string.action_undo));
        });
    }

    private void closeSessionAt(int index) {
        if (mTermuxTerminalSessionActivityClient == null) return;
        TermuxSession termuxSession = getTermuxSessionAt(index);
        if (termuxSession == null || termuxSession.getTerminalSession() == null) return;

        TerminalSession session = termuxSession.getTerminalSession();
        if (session.isRunning()) {
            // Confirm before closing a session whose process is still running.
            showCloseSessionDialog(session);
        } else {
            mTermuxTerminalSessionActivityClient.closeSession(session);
            if (mSnackbarState != null)
                mSnackbarState.post(getString(R.string.msg_session_closed));
        }
    }



    /**
     * Updates the Compose extra-keys bar state from the active key set: the grid height is the
     * per-row height times the number of rows and the configured height scale factor.
     */
    private void updateExtraKeysBar() {
        if (mExtraKeysState == null) return;

        final int rows = (mTermuxTerminalExtraKeys == null || mTermuxTerminalExtraKeys.getExtraKeysInfo() == null)
            ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length;

        mExtraKeysState.setGridHeightPx(Math.round(mTerminalToolbarDefaultHeight * rows *
            mProperties.getTerminalToolbarHeightScaleFactor()));
    }

    /**
     * Applies the configured terminal background to the Compose state. In solid mode the picture is
     * cleared; in wallpaper mode the system wallpaper is decoded on a background thread and pushed
     * in when ready. The dim is always applied immediately.
     *
     * @param forceReload Reload the picture even if one is already showing (e.g. the wallpaper changed).
     */
    public void syncTerminalBackground(boolean forceReload) {
        if (mTerminalBackgroundState == null || mPreferences == null) return;

        final boolean wallpaperMode = mPreferences.shouldUseWallpaperTerminalBackground();
        final boolean imageMode = mPreferences.shouldUseCustomImageTerminalBackground();
        final int dim = mPreferences.getTerminalBackgroundDim();

        if (!wallpaperMode && !imageMode) {
            mTerminalBackgroundLoading = false;
            if (mTerminalBackgroundState.getBitmap() != null)
                mTerminalBackgroundState.update(null, dim);
            return;
        }

        if (forceReload) mTerminalBackgroundState.update(null, dim);
        else mTerminalBackgroundState.update(mTerminalBackgroundState.getBitmap(), dim);

        if (mTerminalBackgroundState.getBitmap() != null || mTerminalBackgroundLoading) return;
        mTerminalBackgroundLoading = true;

        new Thread(() -> {
            final Bitmap backgroundBitmap = wallpaperMode ? loadSystemWallpaperBitmap() : loadCustomImageBitmap();
            runOnUiThread(() -> {
                mTerminalBackgroundLoading = false;
                if (mTerminalBackgroundState != null)
                    mTerminalBackgroundState.update(backgroundBitmap, mPreferences.getTerminalBackgroundDim());
            });
        }, "terminal-background-loader").start();
    }

    /** Whether the system wallpaper is configured as the terminal background. */
    public boolean shouldUseWallpaperTerminalBackground() {
        return mPreferences != null && mPreferences.shouldUseWallpaperTerminalBackground();
    }

    /** Whether a custom image is configured as the terminal background. */
    public boolean shouldUseCustomImageTerminalBackground() {
        return mPreferences != null && mPreferences.shouldUseCustomImageTerminalBackground();
    }

    /** Loads the current system wallpaper as a bitmap downsampled to at most the screen size. */
    @Nullable
    @SuppressWarnings("deprecation")
    private Bitmap loadSystemWallpaperBitmap() {
        try {
            WallpaperManager wallpaperManager = WallpaperManager.getInstance(this);
            Drawable wallpaperDrawable = wallpaperManager.getDrawable();
            if (wallpaperDrawable == null) return null;
            return drawableToScreenBitmap(wallpaperDrawable);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load the system wallpaper", e);
            return null;
        }
    }

    @Nullable
    private Bitmap drawableToScreenBitmap(@NonNull Drawable drawable) {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        if (displayMetrics.widthPixels <= 0 || displayMetrics.heightPixels <= 0) return null;

        // Only downscale; smaller wallpapers keep their intrinsic size and are cropped by the shell.
        int srcW = Math.max(drawable.getIntrinsicWidth(), 1);
        int srcH = Math.max(drawable.getIntrinsicHeight(), 1);
        float scale = Math.min(1f, Math.min((float) displayMetrics.widthPixels / srcW, (float) displayMetrics.heightPixels / srcH));
        int dstW = Math.max(1, Math.round(srcW * scale));
        int dstH = Math.max(1, Math.round(srcH * scale));

        Bitmap bitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, dstW, dstH);
        drawable.draw(canvas);
        return bitmap;
    }

    /** Loads the custom background image from the persisted app-private path, downsampled to about the screen size. */
    @Nullable
    private Bitmap loadCustomImageBitmap() {
        String path = mPreferences != null ? mPreferences.getTerminalBackgroundImagePath() : null;
        if (path == null || path.isEmpty()) return null;
        try {
            DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
            int targetW = displayMetrics.widthPixels;
            int targetH = displayMetrics.heightPixels;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            int srcW = Math.max(options.outWidth, 1);
            int srcH = Math.max(options.outHeight, 1);
            int inSampleSize = 1;
            while ((srcW / inSampleSize) > targetW * 2 || (srcH / inSampleSize) > targetH * 2)
                inSampleSize *= 2;

            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            return bitmap;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load the custom terminal background image", e);
            return null;
        }
    }

    public void toggleTerminalToolbar() {
        if (mExtraKeysState == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        mExtraKeysState.setVisible(showNow);
        if (!showNow) setExtraKeysTextInputVisible(false);
    }

    /** Shows or hides the inline text-send field, moving focus where the soft keyboard follows it. */
    private void setExtraKeysTextInputVisible(boolean visible) {
        if (mExtraKeysState == null) return;

        mExtraKeysState.setTextInputVisible(visible);
        if (!visible && mTerminalView != null) mTerminalView.requestFocus();
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    @Override
    public void onBackPressed() {
        finishActivityIfNotFinishing();
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void showCloseSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_close_session);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            mTermuxTerminalSessionActivityClient.closeSession(session);
            if (mSnackbarState != null)
                mSnackbarState.post(getString(R.string.msg_session_closed));
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return !isTerminalToolbarTextInputViewSelected();
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return mExtraKeysState != null && mExtraKeysState.getTextInputVisible();
    }


    public void termuxSessionListNotifyUpdated() {
        refreshSessionsBar();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        intentFilter.addAction(Intent.ACTION_WALLPAPER_CHANGED);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    case Intent.ACTION_WALLPAPER_CHANGED:
                        Logger.logDebug(LOG_TAG, "Received intent that the system wallpaper changed");
                        syncTerminalBackground(true);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        updateExtraKeysBar();
        syncTerminalBackground(true);

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
