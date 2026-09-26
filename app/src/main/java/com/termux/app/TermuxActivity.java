package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
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
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
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
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import java.util.Arrays;

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
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

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
    // Actions on highlighted text. High ids so they cannot collide with the menu ids above.
    private static final int CONTEXT_MENU_SEND_TO_TERMINAL_ID = 20;
    private static final int CONTEXT_MENU_ASK_GEMINI_ID = 21;
    private static final int CONTEXT_MENU_ASK_BRAVE_ID = 22;
    private static final int CONTEXT_MENU_SEARCH_BRAVE_ID = 23;

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

        setupFullScreenLeftDrawer();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        setupEdgeToEdge();

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();
        setupFloatingControls();

        setRootlessVmSectionView();

        setupRightPane();
        setupRightPaneWeb();
        handleOpenUrlIntent(getIntent());

        setupDrawerSections();

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

        maybeRunRootlessSetup();
    }

    /**
     * First-run setup for the rootless Wi-Fi VM, so no PC is ever involved.
     *
     * If the assets are already installed we just make sure the guest is running, because USB
     * passthrough attaches through the guest's QMP socket - with no VM there is no socket and the
     * attach silently does nothing. If the assets are missing we offer a download; the app fetches
     * ~1.3 GB from a public GitHub release, verifies every file by sha256, and installs it in place.
     */
    private void maybeRunRootlessSetup() {
        if (com.termux.app.rootless.QemuInstaller.isInstalled(this)) {
            startRootlessVmQuietly();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.rootless_install_title)
            .setMessage(R.string.rootless_install_message)
            .setCancelable(false)
            .setPositiveButton(R.string.rootless_install_confirm, (d, w) -> runRootlessInstall())
            .setNegativeButton(R.string.rootless_install_later, null)
            .show();
    }

    private void runRootlessInstall() {
        final AlertDialog progress = new AlertDialog.Builder(this)
            .setTitle(R.string.rootless_install_progress_title)
            .setMessage(getString(R.string.rootless_install_preparing))
            .setCancelable(false)
            .create();
        progress.show();
        final java.util.concurrent.atomic.AtomicInteger lastLevel = new java.util.concurrent.atomic.AtomicInteger(0);
        new Thread(() -> {
            boolean ok = com.termux.app.rootless.QemuInstaller.install(this,
                new com.termux.app.rootless.QemuInstaller.Progress() {
                    @Override
                    public void onStage(com.termux.app.rootless.QemuInstaller.Stage stage) {
                        runOnUiThread(() -> progress.setMessage(stage.title + "\u2026"));
                    }

                    @Override
                    public void onBytes(String label, long done) {
                        final String text = label + "  " + (done / 1024 / 1024) + " MB";
                        runOnUiThread(() -> progress.setMessage(text));
                    }

                    @Override
                    public void onLog(int level, String message) {
                        Logger.logDebug(LOG_TAG, "rootless-install: " + message);
                        if (level >= 3) {
                            lastLevel.set(level);
                            runOnUiThread(() -> progress.setMessage(message));
                        }
                    }
                });
            runOnUiThread(() -> {
                progress.dismiss();
                if (ok) {
                    Logger.showToast(this, getString(R.string.rootless_install_done), false);
                    startRootlessVmQuietly();
                } else {
                    new AlertDialog.Builder(this)
                        .setTitle(R.string.rootless_install_failed)
                        .setMessage(getString(R.string.rootless_install_failed_message))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                }
            });
        }, "rootless-install").start();
    }

    private void startRootlessVmQuietly() {
        new Thread(() -> {
            try {
                boolean up = com.termux.app.rootless.RootlessVm.start(this);
                Logger.logDebug(LOG_TAG, "rootless VM started, qmp ready=" + up);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "rootless VM start failed", e);
            }
        }, "rootless-start").start();
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

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;

        // Re-arm the band's readout. It is stopped in onStop, so a trip through another screen
        // (the target picker, a dialog) would otherwise leave the band frozen on stale text.
        mRootlessPollHandler.removeCallbacks(mSessionStatusTicker);
        mRootlessPollHandler.post(mSessionStatusTicker);

        // Back in Termux, so the tab that brought us here has done its job. It is deliberately not
        // hidden in onStop: that is exactly when the pinned app is coming to the front.
        hideReturnTab();
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        mRootlessPollHandler.removeCallbacks(mSessionStatusTicker);

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();
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

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
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



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            // The drawer stays open on purpose: the user closes it by swiping, not by tapping.
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }

    private static final String PREFS_CONTROLS = "floating_controls";
    private static final String KEY_CONTROLS_X = "x_frac";
    private static final String KEY_CONTROLS_Y = "y_frac";
    private static final String KEY_CONTROLS_COLLAPSED = "collapsed";
    private static final String KEY_CONTROLS_LEFT = "on_left";

    private boolean mControlsCollapsed;
    private boolean mControlsOnLeft = true;

    /**
     * The keyboard and settings buttons: dragged anywhere on the terminal, snapping to the nearer side
     * edge, and collapsible into a bar against that edge that opens again when tapped.
     *
     * <p>Position and collapsed state are kept in preferences, so the arrangement survives restarts.</p>
     */
    private void setupFloatingControls() {
        final View controls = findViewById(R.id.bottom_left_controls);
        final View grip = findViewById(R.id.controls_grip);
        if (controls == null || grip == null) return;

        final android.content.SharedPreferences prefs = getSharedPreferences(PREFS_CONTROLS, MODE_PRIVATE);
        mControlsCollapsed = prefs.getBoolean(KEY_CONTROLS_COLLAPSED, false);
        mControlsOnLeft = prefs.getBoolean(KEY_CONTROLS_LEFT, true);
        applyControlsCollapsed(controls, grip);

        controls.post(() -> {
            View parent = (View) controls.getParent();
            if (parent == null) return;
            float x = prefs.getFloat(KEY_CONTROLS_X, -1f) * parent.getWidth();
            float y = prefs.getFloat(KEY_CONTROLS_Y, -1f) * parent.getHeight();
            if (x >= 0 && y >= 0) moveControls(controls, clampControlsX(controls, x), clampControlsY(controls, y));
            // First run: sit just above the extra-keys bar rather than in the corner.
            else {
                View keys = findViewById(R.id.terminal_toolbar_view_pager);
                int floor = (keys != null && keys.getVisibility() == View.VISIBLE) ? keys.getTop() : parent.getHeight();
                moveControls(controls, dp(4), clampControlsY(controls, floor - controls.getHeight() - dp(6)));
            }
            // Restoring means hugging the edge again: a plain stored x could be inside the gesture zone.
            snapControlsToEdge(controls, prefs);

            // The space above the extra-keys bar changes size whenever the keyboard opens or closes, so
            // keep re-clamping: otherwise a cluster placed with the keyboard down ends up under the keys.
            parent.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                float currentY = controlsY(controls);
                float clampedY = clampControlsY(controls, currentY);
                if (clampedY != currentY) controls.post(() -> pinControls(controls, mControlsOnLeft, clampedY));
            });

        });

        grip.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY, startX, startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = controlsX(controls);
                        startY = controlsY(controls);
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > dp(6)) dragging = true;
                        if (dragging) {
                            moveControls(controls, clampControlsX(controls, startX + dx),
                                clampControlsY(controls, startY + dy));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // A press that never really moved is a tap: collapse or open.
                        if (dragging) snapControlsToEdge(controls, prefs);
                        else toggleControlsCollapsed(controls, grip, prefs);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private float clampControlsX(View controls, float x) {
        View parent = (View) controls.getParent();
        if (parent == null) return x;
        return Math.max(0, Math.min(x, parent.getWidth() - controls.getWidth()));
    }

    /**
     * Positioning goes through layout margins: setX/setY on a RelativeLayout child does not stick.
     * Dragging always anchors left, because a finger-driven x is a left offset.
     */
    private void moveControls(View controls, float x, float y) {
        ViewGroup.LayoutParams params = controls.getLayoutParams();
        if (!(params instanceof RelativeLayout.LayoutParams)) return;
        RelativeLayout.LayoutParams margins = (RelativeLayout.LayoutParams) params;
        margins.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        margins.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        margins.rightMargin = 0;
        margins.leftMargin = Math.round(x);
        margins.topMargin = Math.round(y);
        controls.setLayoutParams(margins);
    }

    /**
     * Pin the cluster to a screen edge.
     *
     * <p>The offset is measured from the pinned edge and the width is pinned to the cluster's natural
     * size. Both matters: with wrap_content the parent measures the cluster against the space left to
     * the right of it, and pinned near the right edge that space is a few dozen pixels - which is
     * what squeezed the row and clipped the settings button off the screen when it expanded.</p>
     */
    private void pinControls(View controls, boolean left, float y) {
        View parent = (View) controls.getParent();
        if (parent == null || parent.getWidth() == 0) return;
        ViewGroup.LayoutParams params = controls.getLayoutParams();
        if (!(params instanceof RelativeLayout.LayoutParams)) return;

        controls.measure(
            View.MeasureSpec.makeMeasureSpec(parent.getWidth(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = Math.max(controls.getMeasuredWidth(), dp(20));
        // Well in from the edge on purpose: any closer and the grip sits inside the system's
        // back-gesture zone, where the system takes the touch and the controls never see the drag.
        int inset = dp(24);

        RelativeLayout.LayoutParams margins = (RelativeLayout.LayoutParams) params;
        margins.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        margins.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        margins.width = width;
        margins.rightMargin = 0;
        // Pinned right, the left offset is derived from the width, so a width change grows the
        // cluster leftwards and the settings button stays on screen.
        margins.leftMargin = left ? inset : Math.max(inset, parent.getWidth() - width - inset);
        margins.topMargin = Math.round(clampControlsY(controls, y));
        controls.setLayoutParams(margins);
        updateControlsGrip(findViewById(R.id.controls_grip));
    }

    /** Remember where the cluster sits, so the next launch restores it there. */
    private void persistControls(View controls, android.content.SharedPreferences prefs) {
        View parent = (View) controls.getParent();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) return;
        controls.post(() -> prefs.edit()
            .putBoolean(KEY_CONTROLS_LEFT, mControlsOnLeft)
            .putFloat(KEY_CONTROLS_X, controls.getLeft() / (float) parent.getWidth())
            .putFloat(KEY_CONTROLS_Y, controls.getTop() / (float) parent.getHeight())
            .apply());
    }

    private float controlsX(View controls) {
        ViewGroup.LayoutParams params = controls.getLayoutParams();
        // Anchored right, leftMargin is 0 and meaningless - the laid-out position is the real one.
        if (params instanceof RelativeLayout.LayoutParams
            && ((RelativeLayout.LayoutParams) params).getRule(RelativeLayout.ALIGN_PARENT_RIGHT) != 0) {
            return controls.getLeft();
        }
        return ((ViewGroup.MarginLayoutParams) params).leftMargin;
    }

    private float controlsY(View controls) {
        return ((ViewGroup.MarginLayoutParams) controls.getLayoutParams()).topMargin;
    }

    /** Keeps the buttons out of the extra-keys bar, so a drag cannot bury them under the key row. */
    private float clampControlsY(View controls, float y) {
        View parent = (View) controls.getParent();
        if (parent == null) return y;
        View keys = findViewById(R.id.terminal_toolbar_view_pager);
        int floor = (keys != null && keys.getVisibility() == View.VISIBLE) ? keys.getTop() : parent.getHeight();
        return Math.max(0, Math.min(y, floor - controls.getHeight() - dp(4)));
    }

    private void snapControlsToEdge(View controls, android.content.SharedPreferences prefs) {
        View parent = (View) controls.getParent();
        if (parent == null || parent.getWidth() == 0) return;
        mControlsOnLeft = controlsX(controls) + controls.getWidth() / 2f < parent.getWidth() / 2f;
        pinControls(controls, mControlsOnLeft, controlsY(controls));
        persistControls(controls, prefs);
    }

    private void toggleControlsCollapsed(View controls, View grip, android.content.SharedPreferences prefs) {
        mControlsCollapsed = !mControlsCollapsed;
        applyControlsCollapsed(controls, grip);
        prefs.edit().putBoolean(KEY_CONTROLS_COLLAPSED, mControlsCollapsed).apply();
        // Re-pin at the new natural width: showing or hiding the two buttons changes it, and pinControls
        // measures rather than assuming a width, so this is right straight away (no waiting for layout).
        pinControls(controls, mControlsOnLeft, controlsY(controls));
        persistControls(controls, prefs);
    }

    private void applyControlsCollapsed(View controls, View grip) {
        View keyboard = controls.findViewById(R.id.toggle_keyboard_button);
        View settings = controls.findViewById(R.id.settings_button);
        int visibility = mControlsCollapsed ? View.GONE : View.VISIBLE;
        if (keyboard != null) keyboard.setVisibility(visibility);
        if (settings != null) settings.setVisibility(visibility);

        ViewGroup.LayoutParams params = grip.getLayoutParams();
        params.width = dp(mControlsCollapsed ? 20 : 22);
        params.height = dp(mControlsCollapsed ? 72 : 34);
        grip.setLayoutParams(params);
        updateControlsGrip(grip);
    }

    /** Expanded it shows a drag handle; collapsed it points back into the screen. */
    private void updateControlsGrip(View grip) {
        if (!(grip instanceof TextView)) return;
        TextView text = (TextView) grip;
        text.setText(mControlsCollapsed ? (mControlsOnLeft ? "›" : "‹") : "⋮");
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else if (rightPaneWebVisible()) {
            // Back walks the isolated pane: up one page, then closes the pane itself.
            if (mRightPaneWeb.canGoBack()) mRightPaneWeb.goBack();
            else closeRightPaneWeb();
        } else {
            finishActivityIfNotFinishing();
        }
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

        // Highlighted text gets its own actions, first in the list. They show up both from a long-press
        // and from the selection toolbar's MORE button, which is what stores the selection.
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText())) {
            menu.add(Menu.NONE, CONTEXT_MENU_SEND_TO_TERMINAL_ID, Menu.NONE, R.string.action_send_to_terminal);
            menu.add(Menu.NONE, CONTEXT_MENU_ASK_GEMINI_ID, Menu.NONE, R.string.action_ask_gemini);
            menu.add(Menu.NONE, CONTEXT_MENU_ASK_BRAVE_ID, Menu.NONE, R.string.action_ask_brave);
            menu.add(Menu.NONE, CONTEXT_MENU_SEARCH_BRAVE_ID, Menu.NONE, R.string.action_search_brave);
        }

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
            case CONTEXT_MENU_SEND_TO_TERMINAL_ID:
                sendSelectedTextToTerminal();
                return true;
            case CONTEXT_MENU_ASK_GEMINI_ID:
                askAiAboutSelectedText();
                return true;
            case CONTEXT_MENU_ASK_BRAVE_ID:
                runConsoleAiCommand("ask-brave", mTerminalView.getStoredSelectedText());
                return true;
            case CONTEXT_MENU_SEARCH_BRAVE_ID:
                runConsoleAiCommand("search-brave", mTerminalView.getStoredSelectedText());
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

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }

    /**
     * DrawerLayout caps how wide it will measure a drawer, so a match_parent drawer does not
     * actually fill the screen. Force the sessions drawer to the full display width so it reads as
     * a fullscreen panel rather than a side sheet.
     */
    private void setupFullScreenLeftDrawer() {
        View leftDrawer = findViewById(R.id.left_drawer);
        if (leftDrawer != null) {
            DrawerLayout.LayoutParams params = (DrawerLayout.LayoutParams) leftDrawer.getLayoutParams();
            params.width = getResources().getDisplayMetrics().widthPixels;
            leftDrawer.setLayoutParams(params);
        }
        // The right pane is a fullscreen surface as well, so it gets the same treatment: a drawer
        // left to DrawerLayout's own measuring comes out as a narrow sheet, not a screen.
        View rightPane = findViewById(R.id.right_pane);
        if (rightPane != null) {
            DrawerLayout.LayoutParams params = (DrawerLayout.LayoutParams) rightPane.getLayoutParams();
            params.width = getResources().getDisplayMetrics().widthPixels;
            rightPane.setLayoutParams(params);
        }
    }

    /** The app pinned to the right-to-left swipe. */
    private static final String PREFS_RIGHT_PANE = "right_pane_swipe";
    private static final String KEY_PINNED_PACKAGE = "pinned_package";
    private static final String KEY_RETURN_HINT_SHOWN = "return_hint_shown";
    private static final String KEY_RETURN_TAB = "return_tab";

    /** The always-on-top return tab, while a pinned app is in front. */
    private View mReturnTab;
    private android.view.WindowManager mReturnTabManager;

    /** The sandboxed web view in the right pane, for URLs the terminal opens. */
    private android.webkit.WebView mRightPaneWeb;
    /** What that view is showing, for the explicit "open in a browser" escape hatch. */
    private String mRightPaneUrl;

    /**
     * A small tab Termux draws over the pinned app.
     *
     * <p>This is the only way a swipe can work from inside another app: touches in a foreign window are
     * delivered to that app, never to us, so the gesture has to happen on something of ours that is on
     * screen at the same time. Requires the display-over-other-apps permission.</p>
     */
    private void showReturnTab() {
        if (mReturnTab != null) return;
        if (!returnTabEnabled()) return;
        if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) return;

        mReturnTabManager = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        TextView tab = new TextView(this);
        tab.setText(getString(R.string.right_pane_return_tab));
        tab.setTextSize(12);
        tab.setTextColor(0xFFFFFFFF);
        tab.setBackgroundColor(0xCC101010);
        tab.setPadding(dp(12), dp(22), dp(12), dp(22));
        tab.setGravity(android.view.Gravity.CENTER);
        tab.setOnClickListener(v -> returnFromPinnedApp());
        tab.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        // A rightward drag on the tab is the same request as tapping it.
                        if (e.getRawX() - downX > dp(30)) {
                            returnFromPinnedApp();
                            return true;
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        v.performClick();
                        return true;
                    default:
                        return false;
                }
            }
        });

        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.os.Build.VERSION.SDK_INT >= 26
                ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : android.view.WindowManager.LayoutParams.TYPE_PHONE,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
        try {
            mReturnTabManager.addView(tab, lp);
            mReturnTab = tab;
        } catch (Exception e) {
            mReturnTab = null;
        }
    }

    private void hideReturnTab() {
        if (mReturnTab == null || mReturnTabManager == null) return;
        try {
            mReturnTabManager.removeView(mReturnTab);
        } catch (Exception ignored) {
        }
        mReturnTab = null;
    }

    /** Bring the console back and take the tab away. */
    private void returnFromPinnedApp() {
        hideReturnTab();
        Intent back = new Intent(this, TermuxActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(back);
        } catch (Exception ignored) {
        }
    }

    private boolean returnTabEnabled() {
        return getSharedPreferences(PREFS_RIGHT_PANE, MODE_PRIVATE)
            .getBoolean(KEY_RETURN_TAB, true);
    }

    private void setReturnTabEnabled(boolean enabled) {
        getSharedPreferences(PREFS_RIGHT_PANE, MODE_PRIVATE).edit()
            .putBoolean(KEY_RETURN_TAB, enabled)
            .apply();
    }

    /** Ask for display-over-other-apps, which the tab cannot exist without. */
    private void requestOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        if (android.provider.Settings.canDrawOverlays(this)) return;
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, R.string.right_pane_return_tab_denied, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /**
     * Run one of the on-device AI commands with the highlighted text as its argument.
     *
     * <p>These run rather than stage: the point is the answer appearing in the session, since that is
     * where the console - and anything reading it - sees it arrive.</p>
     */
    private void runConsoleAiCommand(String command, String text) {
        if (DataUtils.isNullOrEmpty(text)) return;
        mTerminalView.unsetStoredSelectedText();

        TerminalSession session = getCurrentSession();
        if (session == null || !session.isRunning()) {
            Toast.makeText(this, R.string.action_ai_needs_session, Toast.LENGTH_SHORT).show();
            return;
        }

        // Base64, because a selection can carry quotes, newlines and anything else that would
        // otherwise end up interpreted by the shell.
        String encoded = android.util.Base64.encodeToString(
            text.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        String line = command + " \"$(printf '%s' '" + encoded + "' | base64 -d)\"";
        byte[] bytes = (line + CR).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        session.write(bytes, 0, bytes.length);
    }

    /** Put the highlighted text on the current session's command line, left unexecuted. */
    private void sendSelectedTextToTerminal() {
        String text = mTerminalView.getStoredSelectedText();
        if (DataUtils.isNullOrEmpty(text)) return;
        // The emulator's paste path, so a shell with bracketed paste enabled gets it as one paste
        // instead of as N separate lines to run. Deliberately no trailing CR: this stages the text,
        // the user decides whether to run it.
        mTerminalView.mEmulator.paste(text);
        mTerminalView.unsetStoredSelectedText();
        Toast.makeText(this, R.string.action_send_to_terminal_done, Toast.LENGTH_SHORT).show();
    }

    /**
     * Hand the highlighted text to Gemini.
     *
     * <p>The Gemini app takes it straight into a chat via ACTION_SEND. Without it, the browser cannot
     * receive text, so it opens Google's AI Mode with the text already as the query - an answer comes
     * back without any pasting. The clipboard gets it either way, so nothing is lost if both fail.</p>
     */
    private void askAiAboutSelectedText() {
        String text = mTerminalView.getStoredSelectedText();
        if (DataUtils.isNullOrEmpty(text)) return;

        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Termux selection", text));

        mTerminalView.unsetStoredSelectedText();

        // Same deal as a pinned app: we are about to send the user out of the app, so give them the
        // one-gesture way back.
        showReturnTab();

        Intent toGeminiApp = new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .setPackage("com.google.android.apps.bard");
        try {
            startActivity(toGeminiApp);
            return;
        } catch (Exception noGeminiApp) {
            // Falls through to the browser.
        }

        try {
            // udm=50 is Google's AI Mode: the query is the prompt, so no paste step is needed.
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://www.google.com/search?udm=50&q=" + Uri.encode(text))));
        } catch (Exception noBrowser) {
            Toast.makeText(this, R.string.action_ask_ai_no_target, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The package pinned to the right-to-left swipe, or null when nothing is pinned.
     *
     * <p>Pinning to a swipe can only mean launching the app, not hosting it: Android gives an app no
     * way to embed another app's UI inside one of its own views. So a pinned app opens fullscreen
     * over this one, and back returns here.</p>
     */
    private String pinnedRightPanePackage() {
        String pkg = getSharedPreferences(PREFS_RIGHT_PANE, MODE_PRIVATE)
            .getString(KEY_PINNED_PACKAGE, null);
        return pkg == null || pkg.isEmpty() ? null : pkg;
    }

    private void setPinnedRightPanePackage(String pkg) {
        getSharedPreferences(PREFS_RIGHT_PANE, MODE_PRIVATE).edit()
            .putString(KEY_PINNED_PACKAGE, pkg == null ? "" : pkg)
            .apply();
        refreshRightPaneControls();
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Launch the pinned app; returns false so the caller can open the pane instead. */
    private boolean openPinnedRightPaneApp() {
        String pkg = pinnedRightPanePackage();
        if (pkg == null) return false;
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Toast.makeText(this, getString(R.string.right_pane_pin_failed, appLabel(pkg)),
                Toast.LENGTH_LONG).show();
            setPinnedRightPanePackage(null);
            return false;
        }
        // Deliberately not FLAG_ACTIVITY_NEW_TASK: launched into our own task, the pinned app's back
        // (button or back-gesture swipe) returns to Termux instead of to whatever was underneath it.
        try {
            startActivity(launch);
            // Say it once. While another app is in front our activity gets no touches at all, so the
            // Termux swipe cannot bring you back - the system back gesture can, and that is worth not
            // having to guess.
            android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS_RIGHT_PANE, MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_RETURN_HINT_SHOWN, false)) {
                prefs.edit().putBoolean(KEY_RETURN_HINT_SHOWN, true).apply();
                Toast.makeText(this, getString(R.string.right_pane_return_hint, appLabel(pkg)),
                    Toast.LENGTH_LONG).show();
            }
            // The tab is what makes a swipe work from inside the other app.
            showReturnTab();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.right_pane_pin_failed, appLabel(pkg)),
                Toast.LENGTH_LONG).show();
            setPinnedRightPanePackage(null);
            return false;
        }
    }

    /** Every launchable app, alphabetical, as the pin chooser. */
    private void showRightPaneAppChooser() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps;
        try {
            apps = getPackageManager().queryIntentActivities(launcher, 0);
        } catch (Exception e) {
            apps = new java.util.ArrayList<>();
        }
        java.util.TreeMap<String, String> byLabel = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (android.content.pm.ResolveInfo info : apps) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (getPackageName().equals(pkg) || byLabel.containsValue(pkg)) continue;
            byLabel.put(info.loadLabel(getPackageManager()).toString(), pkg);
        }
        if (byLabel.isEmpty()) return;
        final String[] labels = byLabel.keySet().toArray(new String[0]);
        final String[] packages = new String[labels.length];
        int i = 0;
        for (String pkg : byLabel.values()) packages[i++] = pkg;

        new AlertDialog.Builder(this)
            .setTitle(R.string.right_pane_pin_title)
            .setItems(labels, (d, which) -> setPinnedRightPanePackage(packages[which]))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Keep the drawer line and the pane itself honest about what the swipe will do. */
    private void refreshRightPaneControls() {
        String pkg = pinnedRightPanePackage();
        TextView drawerLine = findViewById(R.id.right_pane_pinned);
        if (drawerLine != null) {
            drawerLine.setText(pkg == null
                ? getString(R.string.right_pane_none)
                : getString(R.string.right_pane_pinned, appLabel(pkg)));
        }
        TextView hint = findViewById(R.id.right_pane_hint);
        if (hint != null && pkg != null) {
            hint.setText(getString(R.string.right_pane_pinned, appLabel(pkg)));
        }
    }

    /** Extra used to hand a URL to the isolated right pane instead of an external app. */
    public static final String EXTRA_OPEN_URL = "com.termux.app.OPEN_URL";

    /** Open a URL that arrived with an intent, once, in the isolated pane. */
    private void handleOpenUrlIntent(Intent intent) {
        if (intent == null) return;
        String url = intent.getStringExtra(EXTRA_OPEN_URL);
        if (url == null) return;
        // Consume it so a resume or a rotation does not re-open the pane.
        intent.removeExtra(EXTRA_OPEN_URL);
        openUrlInRightPane(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenUrlIntent(intent);
    }

    /**
     * Open a URL in the right pane's sandboxed WebView, never in another app.
     *
     * <p>This is the isolation boundary between the console and the web. A tap on a link in the
     * terminal used to call out to whatever browser the system felt like - which meant a stray tap
     * could take over the screen. Everything from the console now renders in a WebView inside our own
     * task, with no route to an external app.</p>
     */
    public void openUrlInRightPane(String url) {
        if (url == null || url.isEmpty()) return;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return;
        View bar = findViewById(R.id.right_pane_web_bar);
        View hint = findViewById(R.id.right_pane_hint);
        TextView urlText = findViewById(R.id.right_pane_web_url);
        mRightPaneWeb.loadUrl(url);
        mRightPaneUrl = url;
        if (urlText != null) urlText.setText(url);
        if (hint != null) hint.setVisibility(View.GONE);
        if (bar != null) bar.setVisibility(View.VISIBLE);
        mRightPaneWeb.setVisibility(View.VISIBLE);
        getDrawer().openDrawer(android.view.Gravity.RIGHT);
    }

    /** True while the isolated web pane is showing. */
    private boolean rightPaneWebVisible() {
        return mRightPaneWeb != null && mRightPaneWeb.getVisibility() == View.VISIBLE;
    }

    private void closeRightPaneWeb() {
        if (mRightPaneWeb != null) {
            mRightPaneWeb.loadUrl("about:blank");
            mRightPaneWeb.setVisibility(View.GONE);
        }
        View bar = findViewById(R.id.right_pane_web_bar);
        if (bar != null) bar.setVisibility(View.GONE);
        View hint = findViewById(R.id.right_pane_hint);
        if (hint != null) hint.setVisibility(View.VISIBLE);
        getDrawer().closeDrawer(android.view.Gravity.RIGHT);
    }

    /**
     * One WebView, wrapped in every restriction that keeps it from becoming an escape hatch: no file
     * or content access, no mixed content, no popups, no javascript-opened windows, and no navigation
     * that leaves the view for another app.
     */
    private void setupRightPaneWeb() {
        mRightPaneWeb = findViewById(R.id.right_pane_web);
        if (mRightPaneWeb == null) return;
        android.webkit.WebSettings s = mRightPaneWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        mRightPaneWeb.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView view,
                                                    android.webkit.WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                // Anything that is not plain web traffic is refused outright rather than handed on:
                // this is the line that stops the console reaching the rest of the system.
                return !("http".equals(scheme) || "https".equals(scheme));
            }
        });
        mRightPaneWeb.setWebChromeClient(new android.webkit.WebChromeClient());
        View close = findViewById(R.id.right_pane_web_close);
        if (close != null) close.setOnClickListener(v -> closeRightPaneWeb());
        // The one deliberate way out of the pane. Everything else refuses to leave the app.
        View browser = findViewById(R.id.right_pane_web_browser);
        if (browser != null) browser.setOnClickListener(v -> {
            if (mRightPaneUrl == null) return;
            try {
                Intent out = new Intent(Intent.ACTION_VIEW, Uri.parse(mRightPaneUrl));
                out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(out);
            } catch (Exception e) {
                showToast(getString(R.string.right_pane_web_no_app), true);
            }
        });
    }

    private void setupRightPane() {
        View pin = findViewById(R.id.right_pane_pin);
        if (pin != null) pin.setOnClickListener(v -> {
            showRightPaneAppChooser();
            // The tab is optional but pointless to offer without the permission behind it.
            if (!returnTabEnabled()) return;
            if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
                requestOverlayPermission();
            }
        });
        View unpin = findViewById(R.id.right_pane_unpin);
        if (unpin != null) unpin.setOnClickListener(v -> {
            setPinnedRightPanePackage(null);
            Toast.makeText(this, R.string.right_pane_none, Toast.LENGTH_SHORT).show();
        });
        View tab = findViewById(R.id.right_pane_return_tab_toggle);
        if (tab != null) tab.setOnClickListener(v -> {
            boolean enabled = !returnTabEnabled();
            setReturnTabEnabled(enabled);
            if (enabled) {
                requestOverlayPermission();
                Toast.makeText(this, R.string.right_pane_return_tab_on, Toast.LENGTH_SHORT).show();
            } else {
                hideReturnTab();
                Toast.makeText(this, R.string.right_pane_return_tab_off, Toast.LENGTH_SHORT).show();
            }
            refreshRightPaneControls();
        });
        refreshRightPaneControls();
    }

    /** Start position and state for the launcher-style drawer swipe. */
    private float mDrawerSwipeStartX, mDrawerSwipeStartY;
    private boolean mDrawerSwipeTracking;

    /**
     * Launcher-style gestures: swiping left-to-right anywhere on screen pulls the sessions drawer
     * out fullscreen, and swiping right-to-left pulls the right pane out the same way.
     *
     * <p>Purely observational - no event is ever consumed, so terminal touches, text selection,
     * scrolling and the extra-keys row behave exactly as before. The swipe is ignored while a
     * drawer is already open or locked, so dragging a drawer closed still works normally.</p>
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final DrawerLayout drawer = getDrawer();
        if (drawer != null) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDrawerSwipeStartX = ev.getX();
                    mDrawerSwipeStartY = ev.getY();
                    mDrawerSwipeTracking = !drawer.isDrawerOpen(Gravity.LEFT)
                        && !drawer.isDrawerOpen(Gravity.RIGHT)
                        && drawer.getDrawerLockMode(Gravity.LEFT) != DrawerLayout.LOCK_MODE_LOCKED_CLOSED;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mDrawerSwipeTracking) {
                        float dx = ev.getX() - mDrawerSwipeStartX;
                        float dy = ev.getY() - mDrawerSwipeStartY;
                        float slop = 60f * getResources().getDisplayMetrics().density;
                        // Clearly horizontal and clearly directional, so vertical scrolling in the
                        // terminal is never mistaken for the gesture.
                        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 2) {
                            mDrawerSwipeTracking = false;
                            if (dx > 0) {
                                drawer.openDrawer(Gravity.LEFT);
                            } else if (drawer.getDrawerLockMode(Gravity.RIGHT)
                                != DrawerLayout.LOCK_MODE_LOCKED_CLOSED) {
                                // Whatever has been pinned to this swipe opens; with nothing pinned,
                                // the pane itself comes out.
                                if (!openPinnedRightPaneApp()) drawer.openDrawer(Gravity.RIGHT);
                            }
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mDrawerSwipeTracking = false;
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
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
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    // ---------------------------------------------------------------------------------------------
    // Rootless VM: the controls live in the drawer's VM section and the console is a real session.
    // ---------------------------------------------------------------------------------------------

    private boolean mVmSectionExpanded = true;
    private boolean mSessionsSectionExpanded = true;
    private TextView mRootlessStatusView;
    private TextView mRootlessStatusShortView;
    private TextView mRootlessLogView;
    /** The guest's serial tty is in canonical mode: a command line ends with CR, not LF. */
    private static final String CR = String.valueOf((char) 13);

    private final android.os.Handler mRootlessPollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mRootlessPoller = new Runnable() {
        @Override
        public void run() {
            refreshRootlessStatus();
            mRootlessPollHandler.postDelayed(this, 2500);
        }
    };

    /**
     * Edge-to-edge: draw behind the status bar and into the display cutout, applying only the
     * side and bottom insets.
     *
     * The empty bar that used to sit above the terminal came from {@code android:fitsSystemWindows}
     * on the root view, which padded the whole app down by the status bar height. The top inset is
     * deliberately NOT applied any more, so the terminal (and the drawer's top row, which holds the
     * settings/keyboard buttons beside the camera) reaches into that band.
     */
    private void setupEdgeToEdge() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        mTermuxActivityRootView.setOnApplyWindowInsetsListener((v, insets) -> {
            TermuxActivityRootView.recordStatusBarHeight(insets);
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    right = Math.max(right, cutout.getSafeInsetRight());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
            }
            // The band across the top of the session covers the camera band exactly, so the first
            // line of a session can never sit under the camera again.
            int top = insets.getSystemWindowInsetTop();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) top = Math.max(top, cutout.getSafeInsetTop());
            }
            View band = findViewById(R.id.session_status_bar);
            if (band != null) {
                android.view.ViewGroup.LayoutParams bandParams = band.getLayoutParams();
                int height = Math.max(top, (int) (getResources().getDisplayMetrics().density * 24));
                if (bandParams.height != height) {
                    bandParams.height = height;
                    band.setLayoutParams(bandParams);
                }
            }
            v.setPadding(left, 0, right, bottom);
            return insets;
        });

        // Keep the band's readout live: VM state and the current target now, CPU/RAM use and capture
        // alerts in the same place later.
        mRootlessPollHandler.post(mSessionStatusTicker);
    }

    /** Collapsible drawer sections: tap a header to expand or collapse it. */
    private void setupDrawerSections() {
        View sessionsHeader = findViewById(R.id.sessions_header);
        View sessionsList = findViewById(R.id.terminal_sessions_list);
        TextView sessionsChevron = findViewById(R.id.sessions_section_chevron);
        View vmHeader = findViewById(R.id.vm_header);
        View vmPanel = findViewById(R.id.vm_panel_scroll);
        TextView vmChevron = findViewById(R.id.vm_section_chevron);

        if (sessionsHeader != null && sessionsList != null) {
            sessionsHeader.setOnClickListener(v -> {
                mSessionsSectionExpanded = !mSessionsSectionExpanded;
                setSectionExpanded(sessionsList, sessionsChevron, mSessionsSectionExpanded);
            });
            setSectionExpanded(sessionsList, sessionsChevron, mSessionsSectionExpanded);
        }
        if (vmHeader != null && vmPanel != null) {
            vmHeader.setOnClickListener(v -> {
                mVmSectionExpanded = !mVmSectionExpanded;
                setSectionExpanded(vmPanel, vmChevron, mVmSectionExpanded);
            });
            setSectionExpanded(vmPanel, vmChevron, mVmSectionExpanded);
        }

        // Only poll the VM state while the drawer is actually open.
        getDrawer().addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                refreshRootlessStatus();
                updateSessionStatusBar();
                mRootlessPollHandler.post(mRootlessPoller);
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                mRootlessPollHandler.removeCallbacks(mRootlessPoller);
            }
        });
    }

    private void setSectionExpanded(View content, TextView chevron, boolean expanded) {
        if (content != null) content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (chevron != null) chevron.setText(expanded ? "\u25be" : "\u25b8");
    }

    /** Wire every VM control into the drawer section (this replaced the separate VM screen). */
    private void setRootlessVmSectionView() {
        mRootlessStatusView = findViewById(R.id.rootless_status);
        mRootlessStatusShortView = findViewById(R.id.rootless_status_short);
        mRootlessLogView = findViewById(R.id.rootless_log);

        View start = findViewById(R.id.rootless_start);
        if (start == null) return;

        start.setOnClickListener(v -> startRootlessVmFromDrawer());
        findViewById(R.id.rootless_stop).setOnClickListener(v -> confirmStopRootlessVm());
        findViewById(R.id.rootless_console).setOnClickListener(v -> openRootlessConsoleSession());
        findViewById(R.id.rootless_attach).setOnClickListener(v -> sendRootlessServiceAction(
            TermuxService.ACTION_ATTACH_USB, getString(R.string.rootless_attach_requested)));
        findViewById(R.id.rootless_detach).setOnClickListener(v -> sendRootlessServiceAction(
            TermuxService.ACTION_DETACH_USB, getString(R.string.rootless_detach_requested)));

        findViewById(R.id.rootless_action_scan).setOnClickListener(v ->
            runRootlessAction(com.termux.app.rootless.RootlessActions.scanCommand(), true));
        // Mass deauth and mass handshake work on everything in range: no target is needed, so they
        // never open the target dialog.
        findViewById(R.id.rootless_action_deauth).setOnClickListener(v ->
            runRootlessAction(com.termux.app.rootless.RootlessActions.massDeauthCommand(), false));
        findViewById(R.id.rootless_action_mass_handshake).setOnClickListener(v ->
            runRootlessAction(com.termux.app.rootless.RootlessActions.massHandshakeCommand(), true));
        findViewById(R.id.rootless_action_handshake).setOnClickListener(v -> {
            if (!requireRootlessTarget()) return;
            runRootlessAction(com.termux.app.rootless.RootlessActions.handshakeCommand(this), true);
        });
        findViewById(R.id.rootless_action_pmkid).setOnClickListener(v -> {
            if (!requireRootlessTarget()) return;
            runRootlessAction(com.termux.app.rootless.RootlessActions.pmkidCommand(this), true);
        });
        findViewById(R.id.rootless_action_target).setOnClickListener(v -> openRootlessScanPicker());

        refreshRootlessStatus();
    }

    /** Refreshes the static band at the top of the session. */
    private void updateSessionStatusBar() {
        TextView status = findViewById(R.id.session_status_text);
        if (status == null) return;
        StringBuilder text = new StringBuilder();
        TerminalSession session = getCurrentSession();
        if (session == null) {
            text.append("no session");
        } else {
            // Numbered the way the sessions list numbers them: an unnamed session shows as "[n]".
            int number = 0;
            TermuxService service = getTermuxService();
            if (service != null) {
                int position = 1;
                for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession s : service.getTermuxSessions()) {
                    if (s.getTerminalSession() == session) {
                        number = position;
                        break;
                    }
                    position++;
                }
            }
            text.append(number > 0 ? "[" + number + "]" : "[session]");
            String name = session.mSessionName;
            if (name != null && !name.isEmpty()) text.append(" ").append(name);
        }
        // A run in progress is the only thing worth putting in the band: the session's name, the VM
        // state, and - while something is actually running or staged - what it is pointed at. An idle
        // bar with a target and a finished-run badge in it is just noise.
        com.termux.app.rootless.RootlessRuns.State runs = com.termux.app.rootless.RootlessRuns.read(this);
        boolean scanning = runs.busy();
        if (scanning) {
            text.append("  ▶ ").append(runs.active.label())
                .append(" ").append(com.termux.app.rootless.RootlessRuns.ago(runs.active.startedAt));
        }
        if (scanning && runs.handshakes > 0) text.append("  ✚ ").append(runs.handshakes).append(" hs");
        if (scanning && runs.pmkids > 0) text.append("  ✚ ").append(runs.pmkids).append(" pmkid");
        // The VM's state belongs to the VM console session. In an ordinary Termux session it is not
        // what the user is working in - saying "VM running" there is just misleading.
        boolean onVmConsole = session != null && session == findRootlessConsoleSession();
        if (onVmConsole) {
            text.append(com.termux.app.rootless.RootlessVm.pidOf(this) > 0 ? "  VM running" : "  VM stopped");
        }
        if (scanning) {
            String target = com.termux.app.rootless.RootlessActions.targetLabel(this);
            if (!"no target set".equals(target)) text.append("  target: ").append(target);
        }

        status.setText(text.toString());
        // Keep the readout scrolling instead of cutting off mid-word: the band carries the run, the
        // VM state and the target, and those do not fit a phone's width.
        status.setSelected(true);
    }

    /**
     * The band is always on screen, so its readout is refreshed on a slow tick instead of only while
     * the drawer is open.
     */
    private final Runnable mSessionStatusTicker = new Runnable() {
        @Override public void run() {
            updateSessionStatusBar();
            mRootlessPollHandler.postDelayed(this, 5000);
        }
    };

    private boolean requireRootlessTarget() {
        if (com.termux.app.rootless.RootlessActions.hasTarget(this)) return true;
        rootlessAppendLog(getString(R.string.rootless_action_needs_target));
        // No target yet: answer it by showing what is actually in range instead of asking the user
        // to find a BSSID themselves.
        openRootlessScanPicker();
        return false;
    }

    /**
     * Sweep the band and open the picker on what it finds.
     *
     * The sweep runs in the guest and prints framed rows; the picker reads them back out of the
     * console mirror log, so filtering and sorting happen on the app side while the radio works.
     */
    private void openRootlessScanPicker() {
        if (com.termux.app.rootless.RootlessVm.pidOf(this) <= 0) {
            rootlessAppendLog(getString(R.string.rootless_action_needs_vm));
            return;
        }
        // The picker starts the sweep itself, once it has a console to type into. Sending it from
        // here races the guest's own boot, and a command typed before the shell is listening is
        // dropped without a trace.
        getDrawer().closeDrawers();
        startActivity(new Intent(this, com.termux.app.rootless.RootlessScanActivity.class));
    }

    private void refreshRootlessStatus() {
        if (mRootlessStatusShortView == null) return;
        boolean installed = com.termux.app.rootless.QemuInstaller.isInstalled(this);
        int pid = com.termux.app.rootless.RootlessVm.pidOf(this);
        boolean socketUp = com.termux.app.rootless.RootlessPaths.qmpSock(this).exists();

        String status;
        if (!installed) {
            status = getString(R.string.rootless_status_not_installed);
        } else if (pid > 0 && socketUp) {
            status = getString(R.string.rootless_status_running, pid);
        } else if (pid > 0) {
            status = getString(R.string.rootless_status_no_socket);
        } else {
            status = getString(R.string.rootless_status_stopped);
        }
        if (mRootlessStatusView != null) mRootlessStatusView.setText(status);

        // The drawer's own readout: what is running, and what has been captured.
        TextView activity = findViewById(R.id.rootless_activity_line);
        if (activity != null) {
            com.termux.app.rootless.RootlessRuns.State runs = com.termux.app.rootless.RootlessRuns.read(this);
            if (runs.busy()) {
                activity.setText(getString(R.string.rootless_activity_running,
                    runs.active.label(), com.termux.app.rootless.RootlessRuns.ago(runs.active.startedAt)));
            } else {
                StringBuilder line = new StringBuilder(getString(R.string.rootless_activity_idle));
                if (runs.handshakes > 0) {
                    line.append(" · ").append(getString(R.string.rootless_activity_hashes, runs.handshakes));
                }
                if (runs.pmkids > 0) {
                    line.append(" · ").append(getString(R.string.rootless_activity_pmkids, runs.pmkids));
                }
                if (!runs.capturedSsids.isEmpty()) {
                    int shown = Math.min(3, runs.capturedSsids.size());
                    line.append(" · ").append(getString(R.string.rootless_activity_ssids,
                        android.text.TextUtils.join(", ", runs.capturedSsids.subList(0, shown))));
                }
                activity.setText(line.toString());
            }
        }

        if (pid > 0) mRootlessStatusShortView.setText(R.string.rootless_short_running);
        else if (installed) mRootlessStatusShortView.setText(R.string.rootless_short_stopped);
        else mRootlessStatusShortView.setText(R.string.rootless_short_not_installed);

        // Starting an already-running VM does nothing but churn the disk, so the control says so:
        // whichever of the two is not applicable is greyed out.
        boolean running = pid > 0;
        View startButton = findViewById(R.id.rootless_start);
        if (startButton != null) {
            startButton.setEnabled(!running);
            startButton.setAlpha(running ? 0.4f : 1f);
        }
        View stopButton = findViewById(R.id.rootless_stop);
        if (stopButton != null) {
            stopButton.setEnabled(running);
            stopButton.setAlpha(running ? 1f : 0.4f);
        }

        refreshRightPaneControls();
    }

    private void rootlessAppendLog(String line) {
        if (mRootlessLogView == null) return;
        CharSequence current = mRootlessLogView.getText();
        String merged = (current == null || current.length() == 0) ? line : current + "\n" + line;
        String[] parts = merged.split("\n");
        if (parts.length > 4) {
            merged = parts[parts.length - 4] + "\n" + parts[parts.length - 3] + "\n"
                + parts[parts.length - 2] + "\n" + parts[parts.length - 1];
        }
        mRootlessLogView.setText(merged);
    }

    private void startRootlessVmFromDrawer() {
        if (!com.termux.app.rootless.QemuInstaller.isInstalled(this)) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.rootless_vm_title)
                .setMessage(R.string.rootless_install_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        rootlessAppendLog(getString(R.string.rootless_starting));
        new Thread(() -> {
            boolean started;
            try {
                started = com.termux.app.rootless.RootlessVm.start(this);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "rootless start failed", e);
                started = false;
            }
            final boolean up = started;
            runOnUiThread(() -> {
                rootlessAppendLog(getString(up ? R.string.rootless_started : R.string.rootless_start_failed));
                refreshRootlessStatus();
                // Starting the VM drops the user straight into its console session.
                if (up) openRootlessConsoleSession();
            });
        }, "rootless-start").start();
    }

    private void confirmStopRootlessVm() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.rootless_stop_vm)
            .setMessage(R.string.rootless_stop_confirm)
            .setPositiveButton(R.string.rootless_stop_vm, (d, w) -> new Thread(() -> {
                com.termux.app.rootless.RootlessVm.stop(TermuxActivity.this);
                runOnUiThread(() -> {
                    rootlessAppendLog(getString(R.string.rootless_stopped));
                    refreshRootlessStatus();
                });
            }, "rootless-stop").start())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void sendRootlessServiceAction(String action, String note) {
        if (com.termux.app.rootless.RootlessVm.pidOf(this) <= 0) {
            rootlessAppendLog(getString(R.string.rootless_attach_needs_vm));
            return;
        }
        Intent serviceIntent = new Intent(this, TermuxService.class);
        serviceIntent.setAction(action);
        startService(serviceIntent);
        rootlessAppendLog(note);
    }

    private TerminalSession findRootlessConsoleSession() {
        TermuxService service = getTermuxService();
        if (service == null) return null;
        String wanted = getString(R.string.rootless_console_session_name);
        for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession session : service.getTermuxSessions()) {
            TerminalSession terminalSession = session.getTerminalSession();
            if (terminalSession != null && wanted.equals(terminalSession.mSessionName) && terminalSession.isRunning()) {
                return terminalSession;
            }
        }
        return null;
    }

    /**
     * Open (or focus) the VM console as a normal Termux session.
     *
     * The session runs the bridge binary, which pipes the guest's serial root shell into the pty the
     * terminal view is attached to. That means the console behaves like every other session - extra
     * keys, scrollback, selection, Ctrl-C actually reaching the guest - with no bespoke screen and no
     * credentials anywhere.
     */
    private void openRootlessConsoleSession() {
        if (com.termux.app.rootless.RootlessVm.pidOf(this) <= 0) {
            rootlessAppendLog(getString(R.string.rootless_console_needs_vm));
            return;
        }
        TermuxService service = getTermuxService();
        if (service == null || mTermuxTerminalSessionActivityClient == null) {
            rootlessAppendLog(getString(R.string.rootless_console_needs_vm));
            return;
        }
        if (!com.termux.app.rootless.RootlessConsole.ensureInstalled(this)) {
            rootlessAppendLog(getString(R.string.rootless_console_bridge_missing));
            return;
        }

        // Creating the session through RootlessConsole keeps the bridge's arguments in one place -
        // serial socket plus the mirror log the target picker parses results out of.
        TerminalSession existing = com.termux.app.rootless.RootlessConsole.openSession(this, service);
        if (existing == null) {
            rootlessAppendLog(getString(R.string.rootless_console_bridge_missing));
            return;
        }

        mTermuxTerminalSessionActivityClient.setCurrentSession(existing);
        // Opening or starting the VM is the one case that does close the drawer: the point of it is
        // to put the user in front of the console.
        getDrawer().closeDrawers();
    }

    /**
     * Run a guest command line in the VM console session.
     *
     * Everything happens over the guest's serial root shell, so there is nothing to install or log
     * into. A CR is appended, not an LF: the serial tty is in canonical mode.
     */
    private void runRootlessAction(String command, boolean offerLiveWatch) {
        if (com.termux.app.rootless.RootlessVm.pidOf(this) <= 0) {
            rootlessAppendLog(getString(R.string.rootless_action_needs_vm));
            return;
        }

        // One adapter, one tool. Starting a second run does not queue it, it makes both deaf, so
        // this is the one case worth interrupting the user for.
        com.termux.app.rootless.RootlessRuns.Run active =
            com.termux.app.rootless.RootlessRuns.read(this).active;
        if (active != null) {
            String when = com.termux.app.rootless.RootlessRuns.ago(active.startedAt);
            // White text, with the part that matters in red: this is the one prompt in the app whose
            // whole job is to stop the user doing something.
            CharSequence message = android.text.Html.fromHtml(
                "<font color='#FF3B30'>"
                    + android.text.TextUtils.htmlEncode(active.label() + " started " + when)
                    + "</font>"
                    + android.text.TextUtils.htmlEncode(" " + getString(R.string.rootless_busy_tail)));
            new AlertDialog.Builder(this)
                .setTitle(R.string.rootless_busy_title)
                .setMessage(message)
                .setPositiveButton(R.string.rootless_busy_stop, (d, w) -> {
                    // Ctrl-C first: the running tool is what is reading the console, so it is the
                    // thing that has to be told to stop before the shell can take a command again.
                    sendRootlessCommand("\u0003" + command, offerLiveWatch);
                })
                .setNeutralButton(R.string.rootless_busy_anyway, (d, w) -> sendRootlessCommand(command, offerLiveWatch))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }

        sendRootlessCommand(command, offerLiveWatch);
    }

    private void sendRootlessCommand(String command, boolean offerLiveWatch) {
        TerminalSession console = findRootlessConsoleSession();
        if (console == null) {
            openRootlessConsoleSession();
            console = findRootlessConsoleSession();
        }
        if (console == null) {
            rootlessAppendLog(getString(R.string.rootless_console_bridge_missing));
            return;
        }

        final TerminalSession session = console;
        final byte[] line = (command + CR).getBytes(StandardCharsets.UTF_8);
        // A short delay so a freshly created bridge has connected to the serial socket first.
        mRootlessPollHandler.postDelayed(() -> session.write(line, 0, line.length), 900);
        // Show the run immediately rather than waiting for the next tick: this is the moment the
        // user wants to see that something started.
        mRootlessPollHandler.postDelayed(this::updateSessionStatusBar, 2500);
        mRootlessPollHandler.postDelayed(() -> {
            updateSessionStatusBar();
            refreshRootlessStatus();
        }, 6000);

        // Get out of the way: the drawer has served its purpose once the run is on its way, and the
        // console is what the user wants to be looking at.
        getDrawer().closeDrawers();
    }

    private void showRootlessTargetDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_target, null);
        EditText ssidInput = view.findViewById(R.id.target_ssid);
        EditText bssidInput = view.findViewById(R.id.target_bssid);
        EditText channelInput = view.findViewById(R.id.target_channel);

        ssidInput.setText(com.termux.app.rootless.RootlessActions.getTargetSsid(this));
        bssidInput.setText(com.termux.app.rootless.RootlessActions.getTargetBssid(this));
        channelInput.setText(String.valueOf(com.termux.app.rootless.RootlessActions.getTargetChannel(this)));

        new AlertDialog.Builder(this)
            .setTitle(R.string.rootless_target_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String ssid = ssidInput.getText().toString().trim();
                String bssid = bssidInput.getText().toString().trim();
                if (!com.termux.app.rootless.RootlessActions.isValidBssid(bssid)) {
                    Toast.makeText(this, R.string.rootless_target_bssid_hint, Toast.LENGTH_LONG).show();
                    return;
                }
                int channel;
                try {
                    channel = Integer.parseInt(channelInput.getText().toString().trim());
                } catch (NumberFormatException e) {
                    channel = com.termux.app.rootless.RootlessActions.DEFAULT_CHANNEL;
                }
                if (channel < 1 || channel > 196) channel = com.termux.app.rootless.RootlessActions.DEFAULT_CHANNEL;
                com.termux.app.rootless.RootlessActions.setTarget(this, bssid, channel, ssid);
                // Name the target the way the scan tables do: SSID first, then BSSID and channel.
                rootlessAppendLog(ssid.isEmpty()
                    ? getString(R.string.rootless_target_saved, bssid, channel)
                    : getString(R.string.rootless_target_saved_ssid, ssid, bssid, channel));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
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
