package com.limelight.console;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.StreamView;
import com.limelight.ui.overlay.CustomCommand;
import com.limelight.ui.overlay.DiscordGatewayClient;
import com.limelight.ui.overlay.OverlayMenuView;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Milestone-1 Android TV console shell with a persistent stream surface layer. */
public final class ConsoleActivity extends Activity implements SurfaceHolder.Callback {
    private static final long SESSION_REFRESH_MS = 1500L;
    private static final long HOME_STATUS_REFRESH_MS = 5000L;
    private static final long STREAM_CONNECT_GRACE_MS = 30_000L;
    private static final long STREAM_CONNECT_RETRY_MS = 2_500L;
    private static final int REQUEST_BLUETOOTH_CONNECT = 7001;
    private static final int CONTROLLER_ACTION_NONE = 0;
    private static final int CONTROLLER_ACTION_DISCONNECT = 1;
    private static final int CONTROLLER_ACTION_UNPAIR = 2;
    private static final int DISCORD_BLURPLE = 0xFF5865F2;
    private static final int DISCORD_GREEN = 0xFF23A559;
    private static final int DISCORD_RED = 0xFFDA373C;
    private static final int DISCORD_SURFACE = 0xFF313338;
    private static final int DISCORD_TOOL = 0xFF404249;
    private static final String DISCORD_OVERLAY_PREFS = "discord_overlay_state";
    private static final String DISCORD_DOCK_ENABLED_KEY = "dock_enabled";
    private final ConsoleStateMachine stateMachine = new ConsoleStateMachine();
    private final InputRouter inputRouter = new InputRouter(InputRouter.Region.HOME);
    private final StreamSurfaceHost streamSurfaceHost = new StreamSurfaceHost();
    private final UnifiedConsoleHomeSession unifiedHomeSession =
            new UnifiedConsoleHomeSession();
    private final InputManager.InputDeviceListener controllerDeviceListener =
            new InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int deviceId) { renderControllers(); }
                @Override public void onInputDeviceRemoved(int deviceId) { renderControllers(); }
                @Override public void onInputDeviceChanged(int deviceId) { renderControllers(); }
            };
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService integrationExecutor = Executors.newSingleThreadExecutor();
    private final HostGatewayClient hostGatewayClient = new HostGatewayClient();
    private final AtomicInteger integrationPanelRequest = new AtomicInteger();
    private final AtomicInteger playniteLibraryRequest = new AtomicInteger();
    private final DiscordGatewayClient discordOverlayClient = new DiscordGatewayClient();
    private final AtomicBoolean discordOverlayRefreshInFlight = new AtomicBoolean();
    private final AtomicBoolean discordOverlayActionInFlight = new AtomicBoolean();
    private final Runnable discordOverlayRefresh = () -> refreshDiscordStreamOverlay(false);
    private final Runnable sessionRefresh = this::refreshVisibleSession;
    private final Runnable homeStatusRefresh = this::refreshHomeStatus;
    private final Runnable unifiedConnectRetry = this::retryUnifiedConnection;
    private final ComputerManagerListener computerManagerListener =
            new ComputerManagerListener() {
                @Override public void notifyComputerUpdated(
                        ComputerDetails details, boolean isFreshPoll) {
                    runOnUiThread(() -> refreshForDiscoveredHost(details));
                }
            };
    private final ServiceConnection computerManagerConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            ComputerManagerService.ComputerManagerBinder binder =
                    (ComputerManagerService.ComputerManagerBinder) service;
            new Thread(() -> {
                binder.waitForReady();
                computerManagerBinder = binder;
                runOnUiThread(ConsoleActivity.this::resumeHostPolling);
            }, "MoonWaker host discovery").start();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            hostPollingActive = false;
            computerManagerBinder = null;
        }
    };

    private ConsoleDataRepository repository;
    private HostGatewayStore hostGatewayStore;
    private HostAvailabilityProbeController hostAvailabilityProbeController;
    private ConsoleHostLaunchPreparationController launchPreparationController;
    private ConsoleStreamRuntime streamRuntime;
    private AndroidConsoleSessionEnvironment sessionEnvironment;
    private ConsoleSessionInput unifiedSessionInput;
    private boolean unifiedTransportConnected;
    private boolean unifiedFirstFrameRendered;
    private ConsoleSelectionStore selectionStore;
    private ConsoleLaunchHistoryStore launchHistoryStore;
    private ConsoleHostSelectionController hostSelectionController;
    private ConsoleArtworkController artworkController;
    private ConsoleTheme consoleTheme;
    private ConsoleModalController modalController;
    private OverlayMenuView overlayMenuView;
    private TextView performanceOverlayView;
    private PreferenceConfiguration overlayPreferences;
    private LinearLayout discordDockView;
    private DiscordGatewayClient.Connection discordOverlayConnection;
    private DiscordGatewayClient.VoiceState discordOverlayVoice;
    private DiscordGatewayClient.ChannelTarget lastDiscordOverlayChannel;
    private boolean discordDockEnabled;
    private ConsoleLoadingController loadingController;
    private GatewayProfileRefreshController gatewayProfileRefreshController;
    private ConsoleControllerRepository controllerRepository;
    private InputManager inputManager;
    private boolean controllerListenerRegistered;
    private boolean refreshHostsOnResume;
    private ComputerManagerService.ComputerManagerBinder computerManagerBinder;
    private ComputerManagerService.ApplistPoller appListPoller;
    private String appListPollerHostUuid;
    private String renderedRawAppList;
    private boolean computerManagerBound;
    private boolean hostPollingActive;
    private final Map<String, TextView> hostStatusViews = new HashMap<>();
    private final Map<String, List<ConsoleDataRepository.App>> playniteLibraries =
            new HashMap<>();
    private List<ConsoleDataRepository.Host> visibleHosts = Collections.emptyList();
    private FrameLayout root;
    private StreamView streamSurface;
    private View privacyLayer;
    private FrameLayout homeLayer;
    private ImageView artworkBackdrop;
    private ImageView artworkHero;
    private TextView sessionStatus;
    private TextView integrationStatus;
    private LinearLayout returnToGame;
    private TextView returnToGameTitle;
    private TextView returnToGameSubtitle;
    private LinearLayout sessionButton;
    private TextView communityButton;
    private TextView appsLabel;
    private ConsoleDataRepository.Session currentSession;
    private LinearLayout hostRow;
    private LinearLayout appRow;
    private LinearLayout controllerRow;
    private TextView controllersLabel;
    private View overlayLayer;
    private View modalLayer;
    private ConsoleDataRepository.Host selectedHost;
    private ConsoleDataRepository.Host resumeHost;
    private ConsoleDataRepository.App resumeApp;
    private ConsoleDataRepository.Host pendingPlayniteHost;
    private ConsoleDataRepository.App pendingPlayniteApp;
    private ConsoleDataRepository.Host playnitePreviousResumeHost;
    private ConsoleDataRepository.App playnitePreviousResumeApp;
    private PlayniteLaunchOrchestrator playniteLaunchOrchestrator;
    private final LoadingPrivacyGate playnitePrivacyGate = new LoadingPrivacyGate(3);
    private ConsoleLaunchContract.Request activeLaunchRequest;
    private int runtimeBitrateKbps;
    private long unifiedConnectDeadlineMs;
    private ConsoleControllerRepository.Controller pendingController;
    private int pendingControllerAction = CONTROLLER_ACTION_NONE;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        applyTvWindow();
        repository = new ConsoleDataRepository(this);
        hostGatewayStore = new HostGatewayStore(this);
        hostAvailabilityProbeController = new HostAvailabilityProbeController();
        launchPreparationController = new ConsoleHostLaunchPreparationController();
        selectionStore = new ConsoleSelectionStore(this);
        launchHistoryStore = new ConsoleLaunchHistoryStore(this);
        hostSelectionController = new ConsoleHostSelectionController(
                repository, selectionStore, launchHistoryStore);
        consoleTheme = new ConsoleTheme(this);
        applyInterfacePreferences();
        setContentView(buildRoot());
        streamRuntime = createStreamRuntime();
        artworkController = new ConsoleArtworkController(this, artworkBackdrop, artworkHero);
        modalController = new ConsoleModalController(
                this, (FrameLayout) modalLayer, inputRouter, consoleTheme);
        gatewayProfileRefreshController = new GatewayProfileRefreshController();
        controllerRepository = new ConsoleControllerRepository();
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        renderSnapshot();
        computerManagerBound = bindService(
                new Intent(this, ComputerManagerService.class),
                computerManagerConnection, Service.BIND_AUTO_CREATE);
    }

    @Override protected void onResume() {
        super.onResume();
        if (refreshHostsOnResume) {
            refreshHostsOnResume = false;
            renderSnapshot();
        }
        if (inputManager != null && !controllerListenerRegistered) {
            inputManager.registerInputDeviceListener(controllerDeviceListener, null);
            controllerListenerRegistered = true;
        }
        renderControllers();
        refreshHostAvailability();
        startSelectedAppListPolling();
        ActiveStreamSurfaceBridge.setConsoleForeground(true);
        if (repository != null && sessionStatus != null) {
            ConsoleDataRepository.Session session = visibleSession();
            renderSession(session);
            if (session != null && session.alive) {
                for (ConsoleStateMachine.Event event : ConsoleResumePolicy.eventsFor(
                        stateMachine.getState(), true)) {
                    stateMachine.dispatch(event);
                }
                applyState(stateMachine.getState());
            }
        }
        mainHandler.removeCallbacks(sessionRefresh);
        mainHandler.removeCallbacks(homeStatusRefresh);
        mainHandler.removeCallbacks(discordOverlayRefresh);
        mainHandler.postDelayed(sessionRefresh, SESSION_REFRESH_MS);
        mainHandler.post(homeStatusRefresh);
        updateUnifiedInputSensors();
    }

    @Override protected void onPause() {
        if (unifiedSessionInput != null) unifiedSessionInput.disableSensors();
        ActiveStreamSurfaceBridge.setConsoleForeground(false);
        if (inputManager != null && controllerListenerRegistered) {
            inputManager.unregisterInputDeviceListener(controllerDeviceListener);
            controllerListenerRegistered = false;
        }
        mainHandler.removeCallbacks(sessionRefresh);
        mainHandler.removeCallbacks(homeStatusRefresh);
        if (hostAvailabilityProbeController != null) hostAvailabilityProbeController.cancel();
        if (launchPreparationController != null) launchPreparationController.cancel();
        stopSelectedAppListPolling();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Some Android TV dream overlays do not deliver a matching onResume()
        // when the existing Activity window becomes interactive again.
        ActiveStreamSurfaceBridge.setConsoleForeground(hasFocus && !isFinishing());
        updateUnifiedInputSensors();
    }

    @Override protected void onDestroy() {
        if (loadingController != null) loadingController.stop();
        cancelPlayniteLaunch();
        integrationExecutor.shutdownNow();
        stopSelectedAppListPolling();
        if (computerManagerBinder != null) computerManagerBinder.stopPolling();
        if (computerManagerBound) unbindService(computerManagerConnection);
        ActiveStreamSurfaceBridge.setConsoleForeground(false);
        if (streamSurface != null) {
            ActiveStreamSurfaceBridge.releaseConsoleSurface(streamSurface.getHolder());
            streamSurface.getHolder().removeCallback(this);
        }
        if (artworkController != null) artworkController.destroy();
        if (gatewayProfileRefreshController != null) gatewayProfileRefreshController.destroy();
        if (hostAvailabilityProbeController != null) hostAvailabilityProbeController.destroy();
        if (launchPreparationController != null) launchPreparationController.destroy();
        if (streamRuntime instanceof AutoCloseable) {
            try {
                ((AutoCloseable) streamRuntime).close();
            } catch (Exception error) {
                LimeLog.warning("Unable to close unified Console runtime: " + error);
            }
        }
        unifiedSessionInput = null;
        mainHandler.removeCallbacks(sessionRefresh);
        mainHandler.removeCallbacks(homeStatusRefresh);
        mainHandler.removeCallbacks(unifiedConnectRetry);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (modalController != null && modalController.dismissIfVisible()) return;
        if (stateMachine.getState() == ConsoleStateMachine.State.CONNECTING &&
                launchPreparationController != null) {
            launchPreparationController.cancel();
            cancelUnifiedPendingLaunch();
            cancelPlayniteLaunch();
            unifiedHomeSession.clear();
        }
        ConsoleStateMachine.Transition transition = stateMachine.dispatch(ConsoleStateMachine.Event.BACK);
        if (transition.effect == ConsoleStateMachine.Effect.SHOW_EXIT_CONFIRMATION) {
            showExitConfirmation();
        } else {
            applyState(transition.current);
            resumeHostPolling();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (overlayMenuView != null && overlayMenuView.getVisibility() == View.VISIBLE &&
                overlayMenuView.dispatchKeyEvent(event)) {
            return true;
        }
        int keyCode = event.getKeyCode();
        android.view.InputDevice device = event.getDevice();
        boolean gamepadB = keyCode == KeyEvent.KEYCODE_BUTTON_B &&
                ControllerHandler.isGameControllerDevice(device);
        boolean navigationBack = ConsoleKeyRouting.isNavigationBack(
                keyCode, inputRouter.isGameplayCaptured(), gamepadB);
        if (navigationBack) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                onBackPressed();
            }
            return true;
        }
        if (inputRouter.isGameplayCaptured() && unifiedSessionInput != null &&
                unifiedSessionInput.handleKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (overlayMenuView != null && overlayMenuView.getVisibility() == View.VISIBLE &&
                overlayMenuView.onGenericMotionEvent(event)) {
            return true;
        }
        if (inputRouter.isGameplayCaptured() && unifiedSessionInput != null &&
                unifiedSessionInput.handleMotionEvent(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private View buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // This surface stays attached and VISIBLE. Console/privacy layers cover it.
        streamSurface = new StreamView(this);
        // Match Game's proven SurfaceView composition exactly. An opaque View
        // background here prevents SurfaceView from contributing its transparent
        // region on some Sony/MediaTek compositors, leaving decoded video hidden
        // behind a black app-window layer. The root remains black between frames.
        streamSurface.getHolder().addCallback(this);
        root.addView(streamSurface, match());

        loadingController = new ConsoleLoadingController(this);
        privacyLayer = loadingController.build();
        privacyLayer.setElevation(dp(24));
        privacyLayer.setVisibility(View.GONE);
        root.addView(privacyLayer, match());

        homeLayer = buildHome();
        homeLayer.setElevation(dp(32));
        root.addView(homeLayer, match());

        performanceOverlayView = label("", 10, Color.WHITE, false);
        performanceOverlayView.setTypeface(android.graphics.Typeface.MONOSPACE);
        performanceOverlayView.setGravity(Gravity.LEFT);
        performanceOverlayView.setPadding(dp(2), dp(2), dp(2), dp(2));
        performanceOverlayView.setBackgroundColor(0x60000000);
        performanceOverlayView.setVisibility(View.GONE);
        performanceOverlayView.setElevation(dp(38));
        FrameLayout.LayoutParams performanceParams = new FrameLayout.LayoutParams(
                wrapSize(), wrapSize(), Gravity.LEFT | Gravity.TOP);
        performanceParams.leftMargin = dp(5);
        performanceParams.topMargin = dp(5);
        root.addView(performanceOverlayView, performanceParams);

        overlayMenuView = new OverlayMenuView(this);
        overlayMenuView.setVisibility(View.GONE);
        overlayLayer = overlayMenuView;
        configureMoonlightOverlay();
        overlayLayer.setElevation(dp(40));
        root.addView(overlayLayer, match());

        discordDockView = new LinearLayout(this);
        discordDockView.setOrientation(LinearLayout.VERTICAL);
        discordDockView.setPadding(dp(14), dp(14), dp(14), dp(14));
        discordDockView.setBackgroundColor(0xE6101118);
        discordDockView.setElevation(dp(39));
        discordDockView.setVisibility(View.GONE);
        FrameLayout.LayoutParams dock = new FrameLayout.LayoutParams(
                dp(290), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.TOP);
        dock.topMargin = dp(18);
        dock.rightMargin = dp(18);
        root.addView(discordDockView, dock);

        modalLayer = new FrameLayout(this);
        modalLayer.setElevation(dp(48));
        modalLayer.setBackgroundColor(0xD9000000);
        modalLayer.setVisibility(View.GONE);
        root.addView(modalLayer, match());
        return root;
    }

    private void configureMoonlightOverlay() {
        overlayPreferences = PreferenceConfiguration.readPreferences(this);
        overlayMenuView.setFlipFaceButtons(overlayPreferences.flipFaceButtons);
        runtimeBitrateKbps = overlayPreferences.bitrate;
        overlayMenuView.setBitrateKbps(runtimeBitrateKbps);
        overlayMenuView.setBitrateControlEnabled(
                overlayPreferences.runtimeBitrateControl);
        overlayMenuView.setExternalFrontend(true);
        overlayMenuView.setDiscordShortcuts(overlayPreferences.discordMuteShortcut,
                overlayPreferences.discordLeaveShortcut);
        discordDockEnabled = getSharedPreferences(DISCORD_OVERLAY_PREFS, MODE_PRIVATE)
                .getBoolean(DISCORD_DOCK_ENABLED_KEY, false);
        overlayMenuView.setDiscordDocked(discordDockEnabled);
        overlayMenuView.setDiscordConfigured(false);
        overlayMenuView.setMenuActionListener(new OverlayMenuView.MenuActionListener() {
            @Override public void onDisconnect() { endActiveSession(false); }

            @Override public void onQuitSession() { endActiveSession(true); }

            @Override public void onToggleStats() {
                overlayPreferences.enablePerfOverlay = !overlayPreferences.enablePerfOverlay;
                if (sessionEnvironment != null) {
                    sessionEnvironment.setPerformanceOverlayEnabled(
                            overlayPreferences.enablePerfOverlay);
                }
                updatePerformanceOverlayVisibility();
            }

            @Override public void onToggleMouseEmulation() {
                if (unifiedSessionInput != null) unifiedSessionInput.toggleMouseEmulation();
            }

            @Override public void onShowKeyboard() { toggleStreamKeyboard(); }

            @Override public void onSendGuideButton() {
                if (unifiedSessionInput != null) unifiedSessionInput.sendGuideButton();
            }

            @Override public void onApplyBitrate(int bitrateKbps) {
                applyRuntimeBitrate(bitrateKbps);
            }

            @Override public void onCustomCommand(CustomCommand command) {
                runOverlayCustomCommand(command);
            }

            @Override public void onReturnToFrontend() { openConsoleHome(); }

            @Override public void onDiscordMute() { runDiscordStreamOverlayAction(true); }

            @Override public void onDiscordLeave() { runDiscordStreamOverlayAction(false); }

            @Override public void onDiscordRejoin() { rejoinDiscordStreamOverlay(); }

            @Override public void onDiscordDockToggle() {
                setDiscordDockEnabled(!discordDockEnabled);
            }

            @Override public void onMenuClosed() { closeMoonlightOverlayState(); }
        });
    }

    private void showMoonlightOverlay() {
        if (overlayMenuView == null) return;
        overlayMenuView.setBitrateKbps(runtimeBitrateKbps);
        configureDiscordStreamOverlay();
        if (unifiedSessionInput != null) {
            overlayMenuView.setControllerBatteryInfo(
                    unifiedSessionInput.controllerBatteryInfo());
            unifiedSessionInput.refreshControllerBatteryInfo(() -> runOnUiThread(() -> {
                if (overlayMenuView.getVisibility() == View.VISIBLE &&
                        unifiedSessionInput != null) {
                    overlayMenuView.setControllerBatteryInfo(
                            unifiedSessionInput.controllerBatteryInfo());
                }
            }));
        }
        overlayMenuView.show();
        refreshDiscordStreamOverlay(true);
    }

    private void configureDiscordStreamOverlay() {
        GatewayConnection stored = selectedHost == null ? null :
                hostGatewayStore.loadForHost(selectedHost.uuid, selectedHost.address);
        try {
            discordOverlayConnection = stored == null ? null :
                    new DiscordGatewayClient.Connection(stored.endpoint, stored.token,
                            stored.certificateSha256, stored.profileId);
        } catch (IllegalArgumentException error) {
            discordOverlayConnection = null;
        }
        overlayMenuView.setDiscordConfigured(discordOverlayConnection != null);
    }

    private void refreshDiscordStreamOverlay(boolean force) {
        mainHandler.removeCallbacks(discordOverlayRefresh);
        if (discordOverlayConnection == null ||
                !discordOverlayRefreshInFlight.compareAndSet(false, true)) return;
        DiscordGatewayClient.Connection connection = discordOverlayConnection;
        overlayMenuView.setDiscordState(discordOverlayVoice, null, true);
        integrationExecutor.execute(() -> {
            DiscordGatewayClient.VoiceState voice = null;
            DiscordGatewayClient.ChannelTarget recent = lastDiscordOverlayChannel;
            String error = null;
            try {
                voice = discordOverlayClient.getVoice(connection, force);
                if (voice.connected && !voice.channelId.isEmpty() && !voice.guildId.isEmpty()) {
                    recent = new DiscordGatewayClient.ChannelTarget(voice.channelId,
                            voice.channelName, voice.guildId, "Discord");
                } else if (recent == null) {
                    recent = discordOverlayClient.getRecentChannel(connection);
                }
            } catch (Exception failure) {
                error = friendlyGatewayError(failure);
            }
            DiscordGatewayClient.VoiceState result = voice;
            DiscordGatewayClient.ChannelTarget resultRecent = recent;
            String resultError = error;
            mainHandler.post(() -> {
                discordOverlayRefreshInFlight.set(false);
                if (connection != discordOverlayConnection) return;
                if (result != null) discordOverlayVoice = result;
                if (resultRecent != null) lastDiscordOverlayChannel = resultRecent;
                boolean connected = discordOverlayVoice != null && discordOverlayVoice.connected;
                overlayMenuView.setDiscordRejoinTarget(!connected &&
                                lastDiscordOverlayChannel != null,
                        lastDiscordOverlayChannel == null ? "" :
                                lastDiscordOverlayChannel.channelName);
                if (overlayMenuView.getVisibility() == View.VISIBLE) {
                    overlayMenuView.setDiscordState(discordOverlayVoice, resultError, false);
                }
                renderDiscordStreamDock();
                scheduleDiscordStreamOverlayRefresh();
            });
        });
    }

    private void scheduleDiscordStreamOverlayRefresh() {
        mainHandler.removeCallbacks(discordOverlayRefresh);
        if (discordOverlayConnection != null &&
                (overlayMenuView.getVisibility() == View.VISIBLE || discordDockEnabled) &&
                (stateMachine.getState() == ConsoleStateMachine.State.STREAM ||
                        stateMachine.getState() == ConsoleStateMachine.State.OVERLAY)) {
            mainHandler.postDelayed(discordOverlayRefresh, 2000L);
        }
    }

    private void runDiscordStreamOverlayAction(boolean mute) {
        if (discordOverlayConnection == null || discordOverlayVoice == null ||
                !discordOverlayVoice.connected ||
                !discordOverlayActionInFlight.compareAndSet(false, true)) return;
        if (!mute) lastDiscordOverlayChannel = new DiscordGatewayClient.ChannelTarget(
                discordOverlayVoice.channelId, discordOverlayVoice.channelName,
                discordOverlayVoice.guildId, "Discord");
        DiscordGatewayClient.Connection connection = discordOverlayConnection;
        integrationExecutor.execute(() -> {
            try {
                if (mute) discordOverlayClient.toggleMute(connection);
                else discordOverlayClient.leaveVoice(connection);
            } catch (Exception error) {
                mainHandler.post(() -> Toast.makeText(this,
                        friendlyGatewayError(error), Toast.LENGTH_LONG).show());
            }
            mainHandler.post(() -> {
                discordOverlayActionInFlight.set(false);
                refreshDiscordStreamOverlay(true);
            });
        });
    }

    private void rejoinDiscordStreamOverlay() {
        if (discordOverlayConnection == null || lastDiscordOverlayChannel == null ||
                !discordOverlayActionInFlight.compareAndSet(false, true)) return;
        DiscordGatewayClient.Connection connection = discordOverlayConnection;
        DiscordGatewayClient.ChannelTarget target = lastDiscordOverlayChannel;
        integrationExecutor.execute(() -> {
            try {
                discordOverlayClient.joinChannel(connection, target);
            } catch (Exception error) {
                mainHandler.post(() -> Toast.makeText(this,
                        friendlyGatewayError(error), Toast.LENGTH_LONG).show());
            }
            mainHandler.post(() -> {
                discordOverlayActionInFlight.set(false);
                refreshDiscordStreamOverlay(true);
            });
        });
    }

    private void setDiscordDockEnabled(boolean enabled) {
        discordDockEnabled = enabled;
        getSharedPreferences(DISCORD_OVERLAY_PREFS, MODE_PRIVATE).edit()
                .putBoolean(DISCORD_DOCK_ENABLED_KEY, enabled).apply();
        overlayMenuView.setDiscordDocked(enabled);
        renderDiscordStreamDock();
        if (enabled) refreshDiscordStreamOverlay(true);
        else scheduleDiscordStreamOverlayRefresh();
    }

    private void renderDiscordStreamDock() {
        if (discordDockView == null) return;
        discordDockView.removeAllViews();
        if (!discordDockEnabled || discordOverlayConnection == null ||
                stateMachine.getState() != ConsoleStateMachine.State.STREAM ||
                overlayMenuView.getVisibility() == View.VISIBLE) {
            discordDockView.setVisibility(View.GONE);
            return;
        }
        discordDockView.setVisibility(View.VISIBLE);
        boolean connected = discordOverlayVoice != null && discordOverlayVoice.connected;
        discordDockView.addView(discordDockLine(connected ?
                "DISCORD  \u00B7  " + discordOverlayVoice.channelName : "DISCORD",
                14, 0xFFB69CFF, true));
        if (!connected) {
            discordDockView.addView(discordDockLine("Not connected to a voice channel",
                    13, 0xFFC5C8D3, false));
            return;
        }
        for (DiscordGatewayClient.Participant participant : discordOverlayVoice.participants) {
            discordDockView.addView(discordDockLine(
                    (participant.speaking ? "\u25CF  " : "   ") + participant.name +
                            (participant.self ? "  \u00B7  YOU" : ""), 13,
                    participant.speaking ? 0xFF69F0AE : 0xFFE6E1E9, false));
        }
    }

    private TextView discordDockLine(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private void updatePerformanceOverlayVisibility() {
        if (performanceOverlayView == null || overlayPreferences == null) return;
        ConsoleStateMachine.State state = stateMachine.getState();
        boolean streamVisible = state == ConsoleStateMachine.State.STREAM ||
                state == ConsoleStateMachine.State.OVERLAY;
        performanceOverlayView.setVisibility(
                overlayPreferences.enablePerfOverlay && streamVisible ?
                        View.VISIBLE : View.GONE);
    }

    private void closeMoonlightOverlayState() {
        if (stateMachine.getState() != ConsoleStateMachine.State.OVERLAY) return;
        ConsoleStateMachine.Transition transition = stateMachine.dispatch(
                ConsoleStateMachine.Event.CLOSE_OVERLAY);
        applyState(transition.current);
    }

    private void toggleStreamKeyboard() {
        InputMethodManager keyboard = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.toggleSoftInput(0, 0);
    }

    private void runOverlayCustomCommand(CustomCommand command) {
        if (unifiedSessionInput == null || command == null) return;
        Runnable postAction = null;
        switch (command.getPostAction()) {
            case CustomCommand.POST_ACTION_CLOSE_MENU:
                postAction = overlayMenuView::closeMenu;
                break;
            case CustomCommand.POST_ACTION_DISCONNECT:
                postAction = () -> endActiveSession(false);
                break;
            case CustomCommand.POST_ACTION_QUIT:
                postAction = () -> endActiveSession(true);
                break;
            default:
                break;
        }
        unifiedSessionInput.sendCustomCommand(command, postAction);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        streamSurfaceHost.onWindowSurfaceCreated();
        ActiveStreamSurfaceBridge.registerConsoleSurface(holder);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        streamSurfaceHost.requireWindowSurfaceForChange();
        ActiveStreamSurfaceBridge.registerConsoleSurface(holder);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        boolean sessionAttached = ActiveStreamSurfaceBridge.hasSession();
        boolean safeAlternateSurface = ActiveStreamSurfaceBridge.releaseConsoleSurface(holder);
        StreamSurfaceHost.LossAction action = streamSurfaceHost.onWindowSurfaceDestroyed(
                sessionAttached, safeAlternateSurface);
        if (action == StreamSurfaceHost.LossAction.STOP_SESSION) {
            LimeLog.severe("Console stream Surface was lost without a safe decoder target");
        }
    }

    private FrameLayout buildHome() {
        FrameLayout home = new FrameLayout(this);
        home.setBackgroundColor(0xFF05060A);
        home.addView(new ConsoleGenerativeBackdrop(this), match());

        artworkBackdrop = new ImageView(this);
        artworkBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkBackdrop.setAlpha(0f);
        home.addView(artworkBackdrop, match());

        artworkHero = new ImageView(this);
        artworkHero.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artworkHero.setPadding(dp(34), dp(66), dp(34), dp(66));
        artworkHero.setAlpha(0f);
        FrameLayout.LayoutParams hero = new FrameLayout.LayoutParams(dp(520), matchHeight(), Gravity.RIGHT);
        hero.rightMargin = dp(18);
        home.addView(artworkHero, hero);

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF405060A, 0xC405060A, 0x7005060A}));
        home.addView(scrim, match());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(64), dp(26), dp(64), dp(8));
        content.setClipChildren(false);
        content.setClipToPadding(false);

        TextView title = label("MOONWAKER", 30, Color.WHITE, true);
        content.addView(title, wrap());
        TextView subtitle = label(
                "Choose a host and application. We will wake the PC and start the stream.",
                15, 0xFFBCC3DD, false);
        content.addView(subtitle, top(dp(5)));

        sessionStatus = label("MOONLIGHT · IDLE", 13, 0xFF9CA6C5, true);
        LinearLayout.LayoutParams sessionParams = wrap();
        sessionParams.topMargin = dp(9);
        content.addView(sessionStatus, sessionParams);

        integrationStatus = label("HOST INTEGRATIONS · SELECT A HOST", 12, 0xFF9CA6C5, true);

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(quickActions, top(dp(6)));

        returnToGame = new LinearLayout(this);
        returnToGame.setOrientation(LinearLayout.VERTICAL);
        returnToGame.setGravity(Gravity.CENTER_VERTICAL);
        returnToGame.setFocusable(true);
        returnToGame.setClickable(true);
        returnToGame.setSoundEffectsEnabled(false);
        returnToGame.setMinimumWidth(dp(260));
        returnToGame.setMinimumHeight(dp(54));
        returnToGame.setPadding(dp(20), dp(6), dp(20), dp(6));
        returnToGameTitle = label("\u25B6  RETURN TO GAME", 15, 0xFFF7F2FF, true);
        returnToGameSubtitle = label("", 11, 0xFFC8BCE8, false);
        returnToGame.addView(returnToGameTitle, wrap());
        returnToGame.addView(returnToGameSubtitle, top(dp(2)));
        returnToGame.setOnFocusChangeListener((view, focused) ->
                stylePrimaryButton(returnToGame, focused));
        stylePrimaryButton(returnToGame, false);
        returnToGame.setId(View.generateViewId());
        returnToGame.setContentDescription("Return to active game");
        returnToGame.setVisibility(View.GONE);
        returnToGame.setOnClickListener(view -> {
            if (currentSession != null && currentSession.alive) {
                returnToActiveStream();
            } else if (resumeHost != null && resumeApp != null) {
                if (resumeApp.isPlayniteGame()) launchPlayniteGame(resumeHost, resumeApp);
                else launchLegacy(resumeHost, resumeApp);
            }
        });
        quickActions.addView(returnToGame, wrap());

        sessionButton = new LinearLayout(this);
        sessionButton.setOrientation(LinearLayout.HORIZONTAL);
        sessionButton.setGravity(Gravity.CENTER);
        sessionButton.setFocusable(true);
        sessionButton.setClickable(true);
        sessionButton.setSoundEffectsEnabled(false);
        sessionButton.setMinimumHeight(dp(54));
        sessionButton.setPadding(dp(16), dp(6), dp(16), dp(6));
        ImageView sessionIcon = new ImageView(this);
        sessionIcon.setImageResource(com.limelight.R.drawable.ic_active_session);
        sessionIcon.setColorFilter(0xFFDCCFFF);
        LinearLayout.LayoutParams sessionIconParams =
                new LinearLayout.LayoutParams(dp(24), dp(24));
        sessionIconParams.rightMargin = dp(9);
        sessionButton.addView(sessionIcon, sessionIconParams);
        sessionButton.addView(label("SESSION", 14, 0xFFF1EAFF, true), wrap());
        sessionButton.setOnFocusChangeListener((view, focused) ->
                styleCompactButton(sessionButton, focused));
        styleCompactButton(sessionButton, false);
        sessionButton.setId(View.generateViewId());
        sessionButton.setContentDescription("Open active session details");
        sessionButton.setVisibility(View.GONE);
        sessionButton.setOnClickListener(view -> showSessionDetails());
        LinearLayout.LayoutParams sessionButtonParams = wrap();
        sessionButtonParams.leftMargin = dp(10);
        quickActions.addView(sessionButton, sessionButtonParams);

        returnToGame.setNextFocusRightId(sessionButton.getId());
        sessionButton.setNextFocusLeftId(returnToGame.getId());

        controllersLabel = section("CONTROLLERS · NONE");
        content.addView(controllersLabel, top(dp(11)));
        HorizontalScrollView controllerScroll = horizontalScroll();
        controllerRow = horizontalRow();
        controllerScroll.addView(controllerRow);
        LinearLayout.LayoutParams controllerParams =
                new LinearLayout.LayoutParams(matchWidth(), dp(58));
        controllerParams.topMargin = dp(5);
        content.addView(controllerScroll, controllerParams);

        content.addView(section("STREAMING HOSTS"), top(dp(11)));
        HorizontalScrollView hostScroll = horizontalScroll();
        hostRow = horizontalRow();
        hostScroll.addView(hostRow);
        LinearLayout.LayoutParams hostScrollParams =
                new LinearLayout.LayoutParams(matchWidth(), dp(96));
        hostScrollParams.topMargin = dp(6);
        content.addView(hostScroll, hostScrollParams);

        appsLabel = section("APPS");
        content.addView(appsLabel, top(dp(10)));
        HorizontalScrollView appScroll = horizontalScroll();
        appScroll.setPadding(0, 0, dp(12), dp(10));
        appRow = horizontalRow();
        appScroll.addView(appRow);
        LinearLayout.LayoutParams appScrollParams =
                new LinearLayout.LayoutParams(matchWidth(), dp(120));
        appScrollParams.topMargin = dp(5);
        content.addView(appScroll, appScrollParams);

        home.addView(content, new FrameLayout.LayoutParams(matchWidth(), matchHeight()));

        communityButton = compactHomeAction("\u25CF  DISCORD");
        communityButton.setVisibility(View.GONE);
        communityButton.setContentDescription("Open Discord");
        communityButton.setOnClickListener(view -> showDiscordPanel());
        TextView options = compactHomeAction("\u2699  OPTIONS");
        options.setId(View.generateViewId());
        options.setContentDescription("Open MoonWaker options");
        options.setOnClickListener(view -> showOptions());
        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.HORIZONTAL);
        topActions.setGravity(Gravity.CENTER_VERTICAL);
        communityButton.setId(View.generateViewId());
        LinearLayout.LayoutParams communityParams = wrap();
        communityParams.rightMargin = dp(10);
        topActions.addView(communityButton, communityParams);
        topActions.addView(options, wrap());
        communityButton.setNextFocusRightId(options.getId());
        options.setNextFocusLeftId(communityButton.getId());
        communityButton.setNextFocusDownId(returnToGame.getId());
        options.setNextFocusDownId(returnToGame.getId());
        FrameLayout.LayoutParams optionsParams = new FrameLayout.LayoutParams(
                wrapSize(), wrapSize(), Gravity.TOP | Gravity.RIGHT);
        optionsParams.topMargin = dp(30);
        optionsParams.rightMargin = dp(64);
        home.addView(topActions, optionsParams);
        return home;
    }

    private void renderSnapshot() {
        renderSnapshot(true);
    }

    private void renderSnapshot(boolean requestInitialFocus) {
        renderControllers();
        ConsoleHomeSnapshot snapshot = ConsoleHomeSnapshot.load(
                repository, hostGatewayStore, selectionStore, launchHistoryStore);
        resolveResumeTarget(snapshot);
        renderSession(unifiedHomeSession.visibleOr(snapshot.session));
        hostRow.removeAllViews();
        hostStatusViews.clear();
        visibleHosts = snapshot.hosts;
        if (snapshot.hosts.isEmpty()) {
            selectedHost = null;
            stopSelectedAppListPolling();
            hostRow.addView(addHostCard(), cardParams());
            renderApps(null, snapshot.apps);
            renderGatewayProfile(null, snapshot.integrations);
            hostRow.getChildAt(0).requestFocus();
            return;
        }
        for (ConsoleDataRepository.Host host : snapshot.hosts) {
            hostRow.addView(hostCard(host,
                    host.uuid.equals(snapshot.selectedHost.uuid)), cardParams());
        }
        hostRow.addView(addHostCard(), cardParams());
        selectedHost = snapshot.selectedHost;
        startSelectedAppListPolling();
        renderApps(selectedHost, visibleApps(selectedHost, snapshot.apps));
        refreshPlayniteLibrary(selectedHost);
        renderGatewayProfile(selectedHost, snapshot.integrations);
        // One deterministic initial focus matching Wake: prefer Resume/Return
        // when it exists. Subsequent refreshes never request focus.
        View initialFocus = returnToGame.getVisibility() == View.VISIBLE ?
                returnToGame : hostRow.getChildAt(snapshot.selectedHostIndex);
        if (requestInitialFocus) initialFocus.requestFocus();
    }

    private void refreshForDiscoveredHost(ComputerDetails details) {
        if (details == null || details.uuid == null || isFinishing()) return;
        for (ConsoleDataRepository.Host host : visibleHosts) {
            if (!details.uuid.equals(host.uuid)) continue;
            if (selectedHost != null && details.uuid.equals(selectedHost.uuid) &&
                    details.rawAppList != null &&
                    !details.rawAppList.equals(renderedRawAppList)) {
                renderedRawAppList = details.rawAppList;
                renderSnapshot(false);
            }
            return;
        }
        renderSnapshot(false);
        refreshHostAvailability();
    }

    private void resolveResumeTarget(ConsoleHomeSnapshot snapshot) {
        resumeHost = null;
        resumeApp = null;
        ConsoleLaunchHistoryStore.LastLaunch last = launchHistoryStore.lastLaunch();
        if (last == null) return;
        for (ConsoleDataRepository.Host host : snapshot.hosts) {
            if (!last.hostUuid.equals(host.uuid)) continue;
            List<ConsoleDataRepository.App> apps = host == snapshot.selectedHost ?
                    snapshot.apps : repository.apps(host);
            for (ConsoleDataRepository.App app : apps) {
                if (app.id == last.appId) {
                    resumeHost = host;
                    resumeApp = app;
                    return;
                }
            }
        }
    }

    private View hostCard(ConsoleDataRepository.Host host, boolean selected) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(7), dp(16), dp(7));
        card.setMinimumWidth(dp(250));
        card.setMinimumHeight(dp(82));
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(host.uuid);
        card.setSelected(selected);
        card.setBackground(consoleTheme.hostCardBackground());
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("▣", 16, 0xFF9E8ACB, true), wrap());
        TextView sleepHint = label("HOLD OK · SLEEP", 8, 0xFF9E8ACB, true);
        LinearLayout.LayoutParams sleepHintParams = wrap();
        sleepHintParams.leftMargin = dp(10);
        header.addView(sleepHint, sleepHintParams);
        card.addView(header, wrap());
        TextView name = label(host.name, 16, Color.WHITE, true);
        name.setSingleLine(true);
        card.addView(name, top(dp(1)));
        HostAvailability checking = new HostAvailability(
                HostAvailability.State.CHECKING, host.address);
        TextView status = label(checking.label(), 10, checking.color(), false);
        status.setSingleLine(true);
        card.addView(status, top(dp(1)));
        hostStatusViews.put(host.uuid, status);
        card.setOnClickListener(view -> selectHost(host, view.hasFocus()));
        card.setOnLongClickListener(view -> {
            confirmSleepHost(host);
            return true;
        });
        card.setOnFocusChangeListener(consoleTheme::onCardFocus);
        return card;
    }

    private void confirmSleepHost(ConsoleDataRepository.Host host) {
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, host.address);
        if (connection == null) {
            Toast.makeText(this, "Pair this host's Gateway before using Sleep.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Sleep " + host.name + "?")
                .setMessage("The host will sleep without starting a Moonlight stream.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SLEEP", (dialog, which) ->
                        requestHostSleep(host, connection))
                .show();
    }

    private void requestHostSleep(ConsoleDataRepository.Host host,
                                  HostGatewayClient.Connection connection) {
        Toast.makeText(this, "Requesting sleep for " + host.name + "…",
                Toast.LENGTH_SHORT).show();
        integrationExecutor.execute(() -> {
            try {
                hostGatewayClient.sleepHost(connection);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Sleep request accepted by " + host.name,
                            Toast.LENGTH_LONG).show();
                    mainHandler.postDelayed(this::refreshHostAvailability, 1_500L);
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Unable to sleep " + host.name + ": " + friendlyGatewayError(error),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private View addHostCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(7), dp(18), dp(7));
        card.setMinimumWidth(dp(190));
        card.setMinimumHeight(dp(82));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(consoleTheme.hostCardBackground());
        TextView plus = label("+", 28, 0xFFC8BCE8, false);
        card.addView(plus, wrap());
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("ADD HOST", 15, Color.WHITE, true);
        title.setSingleLine(true);
        copy.addView(title, wrap());
        copy.addView(label("SEARCHING LOCAL NETWORK  ·  ADD MANUALLY",
                10, 0xFFC8BCE8, false), top(dp(1)));
        LinearLayout.LayoutParams copyParams = wrap();
        copyParams.leftMargin = dp(12);
        card.addView(copy, copyParams);
        card.setContentDescription("Add a Moonlight host");
        card.setOnFocusChangeListener(consoleTheme::onCardFocus);
        card.setOnClickListener(view -> {
            refreshHostsOnResume = true;
            Intent intent = new Intent(this, AddComputerManually.class);
            intent.putExtra(AddComputerManually.EXTRA_CONSOLE_APPEARANCE, true);
            startActivity(intent);
        });
        return card;
    }

    private void refreshHostAvailability() {
        if (hostAvailabilityProbeController == null || visibleHosts.isEmpty()) return;
        List<ConsoleDataRepository.Host> hosts = visibleHosts;
        hostAvailabilityProbeController.refresh(hosts, result -> {
            if (hosts != visibleHosts) return;
            for (ConsoleDataRepository.Host host : hosts) {
                HostAvailability availability = isActiveForHost(currentSession, host) ?
                        new HostAvailability(HostAvailability.State.ACTIVE, host.address) :
                        result.get(host.uuid);
                TextView status = hostStatusViews.get(host.uuid);
                if (availability != null && status != null) {
                    String played = launchHistoryStore.hostMetadata(
                            host.uuid, System.currentTimeMillis());
                    status.setText(availability.label() +
                            (played.isEmpty() ? "" : " · " + played));
                    status.setTextColor(availability.color());
                }
            }
        });
    }

    private static boolean isActiveForHost(ConsoleDataRepository.Session session,
                                           ConsoleDataRepository.Host host) {
        if (session == null || !session.alive || host == null) return false;
        return session.host != null && (session.host.equalsIgnoreCase(host.address) ||
                session.host.equalsIgnoreCase(host.name));
    }

    private void selectHost(ConsoleDataRepository.Host host, boolean userFocusedHost) {
        ConsoleHostSelectionController.Selection selection =
                hostSelectionController.select(host);
        selectedHost = selection.host;
        renderedRawAppList = null;
        startSelectedAppListPolling();
        for (int index = 0; index < hostRow.getChildCount(); index++) {
            View hostCard = hostRow.getChildAt(index);
            hostCard.setSelected(host.uuid.equals(hostCard.getTag()));
        }
        renderApps(host, visibleApps(host, selection.apps));
        refreshPlayniteLibrary(host);
        renderGatewayProfile(host);
        if (userFocusedHost && selection.focusAppIndex >= 0 &&
                selection.focusAppIndex < appRow.getChildCount()) {
            appRow.getChildAt(selection.focusAppIndex).requestFocus();
        }
    }

    private void renderApps(ConsoleDataRepository.Host host,
                            List<ConsoleDataRepository.App> apps) {
        appRow.removeAllViews();
        appsLabel.setText(host == null ? "APPS" :
                "APPS · " + host.name.toUpperCase(Locale.ROOT));
        if (apps.isEmpty()) {
            appRow.addView(label("No cached applications. Refresh this host in MoonWaker.",
                    16, 0xFFFFB74D, false), cardParams());
            artworkController.clear();
            return;
        }
        for (ConsoleDataRepository.App app : apps) {
            LinearLayout card = appCard(host, app);
            appRow.addView(card, cardParams());
        }
    }

    private LinearLayout appCard(ConsoleDataRepository.Host host, ConsoleDataRepository.App app) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(16), dp(10));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(consoleTheme.cardBackground());
        card.setMinimumWidth(dp(300));
        card.setMinimumHeight(dp(110));
        card.setTag(app.id);

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable placeholder = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF302255, 0xFF142A46});
        placeholder.setCornerRadius(dp(9));
        poster.setBackground(placeholder);
        Bitmap cached = artworkController.decodePoster(app.posterUri, 320);
        if (cached != null) poster.setImageBitmap(cached);
        card.addView(poster, new LinearLayout.LayoutParams(dp(56), dp(84)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(app.name, 16, Color.WHITE, true);
        name.setSingleLine(true);
        copy.addView(name, new LinearLayout.LayoutParams(matchWidth(), wrapSize()));
        String metadataValue = app.isPlayniteGame() ?
                "PLAYNITE · " + (app.installed ? "INSTALLED" : "NOT INSTALLED") :
                launchHistoryStore.metadata(host.uuid, app.id, System.currentTimeMillis());
        TextView metadata = label(metadataValue, 10, 0xFFAAAFC2, true);
        copy.addView(metadata, top(dp(5)));
        TextView action = label("PLAY  ›", 12, 0xFFB99CFF, true);
        action.setAlpha(0f);
        copy.addView(action, top(dp(5)));
        LinearLayout.LayoutParams copyParams =
                new LinearLayout.LayoutParams(0, matchHeight(), 1f);
        copyParams.leftMargin = dp(14);
        card.addView(copy, copyParams);
        card.setOnFocusChangeListener((view, focused) -> {
            consoleTheme.onCardFocus(view, focused);
            action.animate().cancel();
            if (consoleTheme.isReducedMotion()) {
                action.setAlpha(focused ? 1f : 0f);
            } else {
                action.animate().alpha(focused ? 1f : 0f).setDuration(120).start();
            }
            if (focused) {
                hostSelectionController.rememberApp(host, app);
                artworkController.show(app.posterUri, poster.getDrawable());
            }
        });
        card.setOnClickListener(view -> {
            if (app.isPlayniteGame()) launchPlayniteGame(host, app);
            else launchLegacy(host, app);
        });
        return card;
    }

    private List<ConsoleDataRepository.App> visibleApps(
            ConsoleDataRepository.Host host, List<ConsoleDataRepository.App> fallback) {
        List<ConsoleDataRepository.App> games = host == null ? null :
                playniteLibraries.get(host.uuid);
        return games == null || games.isEmpty() ? fallback : games;
    }

    private void refreshPlayniteLibrary(ConsoleDataRepository.Host host) {
        if (host == null) return;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid);
        if (connection == null) return;
        int request = playniteLibraryRequest.incrementAndGet();
        integrationExecutor.execute(() -> {
            try {
                List<ConsoleDataRepository.App> result = new ArrayList<>();
                String cursor = "";
                for (int page = 0; page < 20; page++) {
                    HostGatewayClient.PlayniteLibrary library =
                            hostGatewayClient.getPlayniteLibrary(connection, cursor, 100);
                    for (HostGatewayClient.PlayniteGame game : library.games) {
                        int id = game.id.hashCode() & 0x7fffffff;
                        if (id == 0) id = 1;
                        result.add(new ConsoleDataRepository.App(id, game.name, null,
                                false, game.id, game.installed));
                    }
                    if (library.nextCursor.isEmpty() ||
                            library.nextCursor.equals(cursor)) break;
                    cursor = library.nextCursor;
                }
                runOnUiThread(() -> {
                    if (request != playniteLibraryRequest.get() || isFinishing()) return;
                    playniteLibraries.put(host.uuid, result);
                    if (selectedHost != null && selectedHost.uuid.equals(host.uuid)) {
                        renderApps(host, visibleApps(host, repository.apps(host)));
                    }
                });
            } catch (Exception error) {
                LimeLog.info("Playnite library unavailable; keeping Apollo fallback: " +
                        error.getMessage());
            }
        });
    }

    private void launchPlayniteGame(ConsoleDataRepository.Host host,
                                    ConsoleDataRepository.App app) {
        if (!app.installed) {
            Toast.makeText(this, "This Playnite game is not installed.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid);
        if (connection == null) {
            Toast.makeText(this, "Pair this host Gateway to use Playnite.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        boolean active = unifiedTransportConnected && activeLaunchRequest != null;
        if (active && (resumeHost == null || !resumeHost.uuid.equals(host.uuid))) {
            Toast.makeText(this, "Disconnect the current host before switching PCs.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        cancelPlayniteLaunch();
        playnitePreviousResumeHost = resumeHost;
        playnitePreviousResumeApp = resumeApp;
        pendingPlayniteHost = host;
        pendingPlayniteApp = app;
        playnitePrivacyGate.reset(true);
        if (unifiedFirstFrameRendered) playnitePrivacyGate.onFirstDecodedFrame();
        pauseHostPolling();
        stateMachine.dispatch(ConsoleStateMachine.Event.LAUNCH);
        applyState(ConsoleStateMachine.State.CONNECTING);
        loadingController.show(app.name);
        loadingController.updateStatus(active ? "Switching game in Playnite…" :
                "Connecting the console session…");
        if (active) {
            beginPlayniteLaunch(connection);
            return;
        }
        ConsoleDataRepository.App transport = playniteTransportApp(host);
        if (transport == null) {
            handlePlayniteFailure("No Apollo transport app is available for this host.");
            return;
        }
        launchLegacy(host, transport, false);
        loadingController.show(app.name);
        loadingController.updateStatus("Connecting stream before starting the game…");
    }

    private ConsoleDataRepository.App playniteTransportApp(ConsoleDataRepository.Host host) {
        ConsoleDataRepository.App best = null;
        int bestScore = Integer.MAX_VALUE;
        for (ConsoleDataRepository.App candidate : repository.apps(host)) {
            String name = candidate.name.toLowerCase(Locale.ROOT);
            int score = name.contains("playnite") ? 0 : name.contains("desktop") ? 1 :
                    name.contains("steam big picture") ? 2 : name.equals("steam") ? 3 : 10;
            if (score <= 3 && score < bestScore) { best = candidate; bestScore = score; }
        }
        return best;
    }

    private void beginPlayniteLaunch(HostGatewayClient.Connection connection) {
        if (pendingPlayniteHost == null || pendingPlayniteApp == null ||
                playniteLaunchOrchestrator != null) return;
        ConsoleDataRepository.Host host = pendingPlayniteHost;
        ConsoleDataRepository.App app = pendingPlayniteApp;
        playniteLaunchOrchestrator = new PlayniteLaunchOrchestrator(
                hostGatewayClient, connection);
        playniteLaunchOrchestrator.launch(new LaunchOrchestrator.Request(
                host.uuid, connection.profileId, Integer.toString(app.id),
                app.playniteGameGuid), new LaunchOrchestrator.Listener() {
            @Override public void onStarting() {
                runOnUiThread(() -> loadingController.updateStatus("Preparing Playnite…"));
            }

            @Override public void onProgress(String status) {
                runOnUiThread(() -> loadingController.updateStatus(status));
            }

            @Override public void onRunning(LaunchOrchestrator.ReadinessSample sample) {
                runOnUiThread(() -> {
                    playnitePrivacyGate.onReadiness(sample);
                    if (playnitePrivacyGate.mayReveal()) completePlayniteLaunch(host, app);
                });
            }

            @Override public void onStopped() {
                runOnUiThread(() -> loadingController.updateStatus(
                        "Returning to Playnite…"));
            }

            @Override public void onFailure(String safeMessage) {
                runOnUiThread(() -> handlePlayniteFailure(safeMessage));
            }
        });
    }

    private void completePlayniteLaunch(ConsoleDataRepository.Host host,
                                        ConsoleDataRepository.App app) {
        if (pendingPlayniteApp != app) return;
        if (playniteLaunchOrchestrator != null) playniteLaunchOrchestrator.close();
        playniteLaunchOrchestrator = null;
        pendingPlayniteHost = null;
        pendingPlayniteApp = null;
        playnitePreviousResumeHost = null;
        playnitePreviousResumeApp = null;
        resumeHost = host;
        resumeApp = app;
        launchHistoryStore.record(host, app, System.currentTimeMillis());
        unifiedHomeSession.begin(host, app);
        unifiedHomeSession.connected();
        stateMachine.dispatch(ConsoleStateMachine.Event.CONNECTED);
        applyState(stateMachine.getState());
        renderSession(visibleSession());
    }

    private void handlePlayniteFailure(String reason) {
        ConsoleDataRepository.Host host = pendingPlayniteHost;
        ConsoleDataRepository.App app = pendingPlayniteApp;
        cancelPlayniteLaunch();
        stateMachine.dispatch(ConsoleStateMachine.Event.HOME);
        applyState(stateMachine.getState());
        modalController.showConnectionRecovery(getCurrentFocus(), reason,
                () -> { if (host != null && app != null) launchPlayniteGame(host, app); },
                () -> endActiveSession(false));
    }

    private void cancelPlayniteLaunch() {
        if (playniteLaunchOrchestrator != null) playniteLaunchOrchestrator.close();
        playniteLaunchOrchestrator = null;
        if (pendingPlayniteApp != null) {
            resumeHost = playnitePreviousResumeHost;
            resumeApp = playnitePreviousResumeApp;
        }
        pendingPlayniteHost = null;
        pendingPlayniteApp = null;
        playnitePreviousResumeHost = null;
        playnitePreviousResumeApp = null;
    }

    private void launchLegacy(ConsoleDataRepository.Host host, ConsoleDataRepository.App app) {
        launchLegacy(host, app, true);
    }

    private void launchLegacy(ConsoleDataRepository.Host host, ConsoleDataRepository.App app,
                              boolean recordHistory) {
        LimeLog.info("Unified Console launch requested");
        pauseHostPolling();
        if (recordHistory) launchHistoryStore.record(host, app, System.currentTimeMillis());
        resumeHost = host;
        resumeApp = app;
        if (streamRuntime instanceof UnifiedConsoleRuntimeBootstrap) {
            unifiedHomeSession.begin(host, app);
        }
        stateMachine.dispatch(ConsoleStateMachine.Event.LAUNCH);
        applyState(ConsoleStateMachine.State.CONNECTING);
        loadingController.show(app.name);
        launchPreparationController.prepare(host,
                new ConsoleHostLaunchPreparationController.Callback() {
                    @Override public void onStatus(String status) {
                        LimeLog.info("Unified Console host preparation: " + status);
                        loadingController.updateStatus(status);
                    }

                    @Override public void onReady() {
                        LimeLog.info("Unified Console host preparation complete");
                        launchPreparedLegacy(host, app);
                    }

                    @Override public void onTimeout() {
                        LimeLog.warning("Unified Console host preparation timed out");
                        unifiedHomeSession.clear();
                        stateMachine.dispatch(ConsoleStateMachine.Event.BACK);
                        applyState(stateMachine.getState());
                        resumeHostPolling();
                        modalController.showHostWakeTimeout(getCurrentFocus(), host.name,
                                () -> launchLegacy(host, app));
                    }
                });
    }

    private void launchPreparedLegacy(ConsoleDataRepository.Host host,
                                      ConsoleDataRepository.App app) {
        ConsoleLaunchContract.Request request =
                ConsoleLaunchContract.create(host, app, getPackageName());
        activeLaunchRequest = request;
        runtimeBitrateKbps = PreferenceConfiguration.readPreferences(this).bitrate;
        unifiedTransportConnected = false;
        unifiedFirstFrameRendered = false;
        unifiedSessionInput = null;
        clearUnifiedConnectRetry();
        LimeLog.info("Unified Console runtime launch: " +
                streamRuntime.getClass().getSimpleName());
        streamRuntime.launch(request);
    }

    private void applyRuntimeBitrate(int bitrateKbps) {
        if (activeLaunchRequest == null || !unifiedTransportConnected ||
                !(streamRuntime instanceof UnifiedConsoleRuntimeBootstrap)) {
            Toast.makeText(this, "No active unified stream to reconnect.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int target = StreamBitratePolicy.clamp(bitrateKbps);
        if (target == runtimeBitrateKbps) return;

        runtimeBitrateKbps = target;
        activeLaunchRequest = activeLaunchRequest.withRuntimeBitrate(target);
        overlayMenuView.setBitrateKbps(target);
        overlayMenuView.closeMenu();
        stateMachine.dispatch(ConsoleStateMachine.Event.RECONNECT);
        applyState(stateMachine.getState());
        unifiedTransportConnected = false;
        unifiedFirstFrameRendered = false;
        unifiedSessionInput = null;
        clearUnifiedConnectRetry();
        if (resumeHost != null && resumeApp != null) {
            unifiedHomeSession.begin(resumeHost, resumeApp);
            loadingController.show(resumeApp.name);
        }
        loadingController.updateStatus("Reconnecting at " +
                Math.round(target / 1000f) + " Mbps…");
        streamRuntime.reconnectAtBitrate(activeLaunchRequest, target);
    }

    private void renderControllers() {
        if (controllerRepository == null || controllerRow == null) return;
        List<ConsoleControllerRepository.Controller> controllers = controllerRepository.load();
        controllerRow.removeAllViews();
        controllersLabel.setText(controllers.isEmpty() ? "CONTROLLERS · NONE" : "CONTROLLERS");
        int player = 1;
        for (ConsoleControllerRepository.Controller controller : controllers) {
            final int playerNumber = player++;
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(12), dp(5), dp(16), dp(5));
            chip.setMinimumWidth(dp(220));
            chip.setMinimumHeight(dp(50));
            chip.setBackground(consoleTheme.cardBackground());
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setOnFocusChangeListener(consoleTheme::onCardFocus);
            chip.setOnClickListener(view -> openControllerActions(playerNumber, controller));
            TextView icon = label("\uD83C\uDFAE", 23, Color.WHITE, false);
            chip.addView(icon, new LinearLayout.LayoutParams(dp(36), matchHeight()));
            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setGravity(Gravity.CENTER_VERTICAL);
            TextView controllerLabel = label("P" + playerNumber + "  " +
                    compactControllerName(controller.name), 14, Color.WHITE, true);
            controllerLabel.setSingleLine(true);
            copy.addView(controllerLabel, wrap());
            int batteryColor = controller.batteryPercentage < 0 ? 0xFFB3B8C8 :
                    controller.charging ? 0xFF64B5F6 :
                            controller.batteryPercentage <= 10 ? 0xFFFF5252 :
                                    controller.batteryPercentage <= 30 ? 0xFFFFB74D : 0xFF69F0AE;
            String battery = controller.charging ? "\u26A1 " : "\u25B0 ";
            TextView level = label(battery + (controller.batteryPercentage < 0 ?
                    "Battery unavailable" : controller.batteryPercentage + "%"),
                    14, batteryColor, false);
            level.setSingleLine(true);
            copy.addView(level, wrap());
            chip.addView(copy, new LinearLayout.LayoutParams(0, wrapSize(), 1f));
            controllerRow.addView(chip, cardParams());
        }
    }

    private static String compactControllerName(String name) {
        if (name == null) return "Controller";
        return name.toLowerCase(Locale.ROOT).contains("dualsense") ? "DualSense" : name;
    }

    private void openControllerActions(int player,
                                       ConsoleControllerRepository.Controller controller) {
        modalController.showControllerActions(getCurrentFocus(), player, controller,
                () -> ControllerActions.identify(controller.deviceId, mainHandler,
                        this::showControllerActionResult),
                () -> runControllerBluetoothAction(controller,
                        CONTROLLER_ACTION_DISCONNECT),
                () -> runControllerBluetoothAction(controller,
                        CONTROLLER_ACTION_UNPAIR));
    }

    private void runControllerBluetoothAction(
            ConsoleControllerRepository.Controller controller, int action) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingController = controller;
            pendingControllerAction = action;
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_BLUETOOTH_CONNECT);
            return;
        }
        if (action == CONTROLLER_ACTION_DISCONNECT) {
            ControllerActions.disconnect(this, controller.deviceId,
                    this::showControllerActionResult);
        } else if (action == CONTROLLER_ACTION_UNPAIR) {
            ControllerActions.unpair(controller.deviceId, this::showControllerActionResult);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH_CONNECT) return;
        ConsoleControllerRepository.Controller controller = pendingController;
        int action = pendingControllerAction;
        pendingController = null;
        pendingControllerAction = CONTROLLER_ACTION_NONE;
        if (controller != null && grantResults.length > 0 &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runControllerBluetoothAction(controller, action);
        } else {
            Toast.makeText(this, "Bluetooth permission is required for this controller action.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showControllerActionResult(boolean success, String message) {
        mainHandler.post(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (success) mainHandler.postDelayed(this::renderControllers, 800L);
        });
    }

    private void renderGatewayProfile(ConsoleDataRepository.Host host) {
        GatewayConnection connection = host == null ? null : hostGatewayStore.load(host.uuid);
        renderGatewayProfile(host, HostIntegrationSummary.from(connection));
    }

    private void renderGatewayProfile(ConsoleDataRepository.Host host,
                                      HostIntegrationSummary summary) {
        if (integrationStatus == null) return;
        if (host == null) {
            integrationStatus.setText("HOST INTEGRATIONS · SELECT A HOST");
            communityButton.setVisibility(View.GONE);
            return;
        }
        if (!summary.gatewayPaired) {
            integrationStatus.setText("HOST INTEGRATIONS · GATEWAY NOT PAIRED");
            integrationStatus.setTextColor(0xFF9CA6C5);
            communityButton.setVisibility(View.GONE);
        }
        else {
            integrationStatus.setText("HOST INTEGRATIONS · PROFILE " +
                    summary.profileId.toUpperCase(Locale.ROOT) + " · GATEWAY PAIRED");
            integrationStatus.setTextColor(0xFF69F0AE);
            communityButton.setVisibility(View.VISIBLE);
        }
    }

    private void renderSession(ConsoleDataRepository.Session session) {
        currentSession = session;
        ConsoleSessionSummary summary = ConsoleSessionSummary.from(session);
        String state = session != null && session.state != null ?
                session.state.toUpperCase(Locale.ROOT) : "IDLE";
        sessionStatus.setText("MOONWAKER · " + state);
        sessionStatus.setTextColor(summary.alive ? 0xFF69F0AE : 0xFF9CA6C5);
        if (summary.alive) {
            String app = session != null && session.app != null ? session.app : "ACTIVE SESSION";
            returnToGameTitle.setText("\u25B6  RETURN TO GAME");
            returnToGameSubtitle.setText(app);
            returnToGame.setContentDescription("Return to active game, " + app);
            returnToGame.setVisibility(View.VISIBLE);
        } else if (resumeHost != null && resumeApp != null) {
            returnToGameTitle.setText("\u25B6  RESUME LAST");
            returnToGameSubtitle.setText(resumeApp.name);
            returnToGame.setContentDescription("Resume last game, " + resumeApp.name);
            returnToGame.setVisibility(View.VISIBLE);
        } else {
            returnToGame.setVisibility(View.GONE);
        }
        sessionButton.setVisibility(summary.alive ? View.VISIBLE : View.GONE);
    }

    private void refreshVisibleSession() {
        if (repository == null || homeLayer == null ||
                homeLayer.getVisibility() != View.VISIBLE || isFinishing()) return;
        renderSession(visibleSession());
        mainHandler.postDelayed(sessionRefresh, SESSION_REFRESH_MS);
    }

    private void refreshHomeStatus() {
        if (repository != null && homeLayer != null &&
                homeLayer.getVisibility() == View.VISIBLE && !isFinishing()) {
            renderControllers();
            refreshHostAvailability();
        }
        if (!isFinishing()) {
            mainHandler.postDelayed(homeStatusRefresh, HOME_STATUS_REFRESH_MS);
        }
    }

    private ConsoleDataRepository.Session visibleSession() {
        return unifiedHomeSession.visibleOr(repository.session());
    }

    private void returnToActiveStream() {
        ConsoleStateMachine.Event event = stateMachine.getState() ==
                ConsoleStateMachine.State.OVERLAY ?
                ConsoleStateMachine.Event.CLOSE_OVERLAY :
                ConsoleStateMachine.Event.RETURN_TO_STREAM;
        ConsoleStateMachine.Transition transition =
                stateMachine.dispatch(event);
        applyState(transition.current);
        streamRuntime.returnToActiveStream();
    }

    private void openConsoleHome() {
        ConsoleStateMachine.Transition transition =
                stateMachine.dispatch(ConsoleStateMachine.Event.OPEN_CONSOLE);
        applyState(transition.current);
    }

    private void applyState(ConsoleStateMachine.State state) {
        ConsoleLayerState layers = ConsoleLayerState.from(state);
        boolean openingOverlay = layers.overlayVisible &&
                overlayLayer.getVisibility() != View.VISIBLE;
        homeLayer.setVisibility(layers.homeVisible ? View.VISIBLE : View.GONE);
        privacyLayer.setVisibility(layers.privacyVisible ? View.VISIBLE : View.GONE);
        if (layers.homeVisible && repository != null && returnToGame != null) {
            renderSession(visibleSession());
        }
        if (layers.overlayVisible) {
            if (openingOverlay) showMoonlightOverlay();
        } else if (overlayMenuView != null) {
            overlayMenuView.hide(null);
        }
        renderDiscordStreamDock();
        updatePerformanceOverlayVisibility();
        scheduleDiscordStreamOverlayRefresh();
        inputRouter.routeTo(layers.inputRegion);
        updateUnifiedInputSensors();
        if ((state == ConsoleStateMachine.State.HOME ||
                state == ConsoleStateMachine.State.CONSOLE_OVER_STREAM) &&
                streamRuntime instanceof UnifiedConsoleRuntimeBootstrap) {
            ((UnifiedConsoleRuntimeBootstrap) streamRuntime).showHome();
        }
        // streamSurface intentionally remains VISIBLE and attached.
    }

    private ConsoleStreamRuntime createStreamRuntime() {
        if (!UnifiedConsoleRuntimeGate.isEnabled()) {
            return new LegacyConsoleStreamRuntime(this, hostGatewayStore);
        }

        sessionEnvironment = new AndroidConsoleSessionEnvironment(
                        this,
                        streamSurface,
                        new AndroidConsoleSessionEnvironment.Callbacks() {
                            @Override public void onInputReady(ConsoleSessionInput input) {
                                unifiedSessionInput = input;
                                updateUnifiedInputSensors();
                            }

                            @Override public void onFirstFrameRendered() {
                                unifiedFirstFrameRendered = true;
                                playnitePrivacyGate.onFirstDecodedFrame();
                                maybeRevealUnifiedStream();
                            }

                            @Override public void onPerformanceUpdate(String text) {
                                if (performanceOverlayView != null) {
                                    performanceOverlayView.setText(text);
                                }
                            }

                            @Override public void onStatus(String status) {
                                if (loadingController != null) {
                                    loadingController.updateStatus(status);
                                }
                            }

                            @Override public void onConnectionStatus(int status) {
                                LimeLog.info("Unified stream connection status: " + status);
                            }

                            @Override public void onMessage(
                                    String message, boolean transientMessage) {
                                Toast.makeText(ConsoleActivity.this, message,
                                        transientMessage ? Toast.LENGTH_SHORT :
                                                Toast.LENGTH_LONG).show();
                            }

                            @Override public void onConfigurationPlanned(
                                    StreamSessionConfigurationPlanner.Plan plan) {
                                unifiedHomeSession.planned(
                                        plan.configuration.getWidth(),
                                        plan.configuration.getHeight(),
                                        plan.configuration.getRefreshRate());
                                showUnifiedConfigurationWarning(plan);
                            }

                            @Override public void toggleKeyboard() {
                                Toast.makeText(ConsoleActivity.this,
                                        "Keyboard overlay is not available in Console yet.",
                                        Toast.LENGTH_SHORT).show();
                            }

                            @Override public void onOverlayOpen() {
                                ConsoleStateMachine.Transition transition = stateMachine.dispatch(
                                        ConsoleStateMachine.Event.OPEN_OVERLAY);
                                applyState(transition.current);
                            }
                        });
        MoonlightConsoleSessionFactory sessionFactory =
                new MoonlightConsoleSessionFactory(this, sessionEnvironment);
        return new UnifiedConsoleRuntimeBootstrap(
                this,
                sessionFactory,
                new UnifiedConsoleStreamRuntimeAdapter.Listener() {
                    @Override public void onStage(
                            UnifiedConsoleLaunchPipeline.Stage stage) {
                        runOnUiThread(() -> handleUnifiedStage(stage));
                    }

                    @Override public void onFailure(
                            UnifiedConsoleLaunchPipeline.Failure failure) {
                        runOnUiThread(() -> handleUnifiedFailure(failure));
                    }
                });
    }

    private void handleUnifiedStage(UnifiedConsoleLaunchPipeline.Stage stage) {
        LimeLog.info("Unified Console stage: " + stage.name());
        switch (stage) {
            case RESOLVING_HOST:
                loadingController.updateStatus("Resolving streaming host…");
                break;
            case PREPARING_SESSION:
                if (unifiedConnectDeadlineMs == 0L) {
                    unifiedConnectDeadlineMs = SystemClock.elapsedRealtime() +
                            STREAM_CONNECT_GRACE_MS;
                }
                loadingController.updateStatus("Preparing MoonWaker session…");
                break;
            case CONNECTED:
                clearUnifiedConnectRetry();
                unifiedTransportConnected = true;
                if (unifiedSessionInput != null) {
                    unifiedSessionInput.ensureControllersReported();
                }
                unifiedHomeSession.connected();
                renderSession(visibleSession());
                loadingController.updateStatus("Waiting for the first video frame…");
                maybeRevealUnifiedStream();
                break;
            case FAILED:
                unifiedTransportConnected = false;
                unifiedFirstFrameRendered = false;
                unifiedSessionInput = null;
                break;
        }
    }

    private void handleUnifiedFailure(UnifiedConsoleLaunchPipeline.Failure failure) {
        String reason;
        if (failure.runtimeReason != null && !failure.runtimeReason.isBlank()) {
            reason = failure.runtimeReason;
        } else if (failure.resolutionError != null) {
            reason = failure.resolutionError.name().toLowerCase(Locale.ROOT)
                    .replace('_', ' ');
        } else {
            reason = "unknown connection error";
        }
        LimeLog.warning("Unified Console failure: " + reason);
        if (unifiedConnectDeadlineMs > SystemClock.elapsedRealtime() &&
                activeLaunchRequest != null &&
                stateMachine.getState() == ConsoleStateMachine.State.CONNECTING) {
            loadingController.updateStatus("Host is starting streaming services… Retrying");
            mainHandler.removeCallbacks(unifiedConnectRetry);
            mainHandler.postDelayed(unifiedConnectRetry, STREAM_CONNECT_RETRY_MS);
            return;
        }
        clearUnifiedConnectRetry();
        activeLaunchRequest = null;
        unifiedHomeSession.clear();
        renderSession(visibleSession());
        stateMachine.dispatch(ConsoleStateMachine.Event.CONNECTION_FAILED);
        applyState(stateMachine.getState());
        resumeHostPolling();
        loadingController.updateStatus("Connection failed: " + reason);
        modalController.showConnectionRecovery(getCurrentFocus(), reason,
                () -> {
                    if (resumeHost != null && resumeApp != null) {
                        if (resumeApp.isPlayniteGame()) {
                            launchPlayniteGame(resumeHost, resumeApp);
                        } else {
                            launchLegacy(resumeHost, resumeApp);
                        }
                    }
                }, () -> {
                    cancelUnifiedPendingLaunch();
                    unifiedHomeSession.clear();
                    renderSession(visibleSession());
                });
    }

    private void maybeRevealUnifiedStream() {
        if (!unifiedTransportConnected || !unifiedFirstFrameRendered ||
                stateMachine.getState() != ConsoleStateMachine.State.CONNECTING) {
            return;
        }
        if (pendingPlayniteApp != null) {
            HostGatewayClient.Connection connection = pendingPlayniteHost == null ? null :
                    hostGatewayStore.loadClientConnection(pendingPlayniteHost.uuid);
            if (connection == null) {
                handlePlayniteFailure("The Playnite profile is no longer available.");
            } else {
                beginPlayniteLaunch(connection);
            }
            return;
        }
        stateMachine.dispatch(ConsoleStateMachine.Event.CONNECTED);
        applyState(stateMachine.getState());
    }

    private void updateUnifiedInputSensors() {
        if (unifiedSessionInput == null) return;
        if (inputRouter.isGameplayCaptured() && hasWindowFocus()) {
            unifiedSessionInput.enableSensors();
        } else {
            unifiedSessionInput.disableSensors();
        }
    }

    private void cancelUnifiedPendingLaunch() {
        clearUnifiedConnectRetry();
        if (streamRuntime instanceof UnifiedConsoleRuntimeBootstrap) {
            ((UnifiedConsoleRuntimeBootstrap) streamRuntime).cancelPendingLaunch();
        }
        resumeHostPolling();
    }

    private void pauseHostPolling() {
        stopSelectedAppListPolling();
        if (!hostPollingActive || computerManagerBinder == null) return;
        computerManagerBinder.stopPolling();
        hostPollingActive = false;
        LimeLog.info("Paused host discovery polling for streaming");
    }

    private void resumeHostPolling() {
        if (computerManagerBinder == null || isFinishing() ||
                stateMachine.getState() == ConsoleStateMachine.State.CONNECTING ||
                activeLaunchRequest != null) {
            return;
        }
        if (!hostPollingActive) {
            computerManagerBinder.startPolling(computerManagerListener);
            hostPollingActive = true;
            LimeLog.info("Resumed host discovery polling on Home");
        }
        startSelectedAppListPolling();
    }

    private void startSelectedAppListPolling() {
        if (computerManagerBinder == null || selectedHost == null ||
                stateMachine.getState() == ConsoleStateMachine.State.CONNECTING ||
                activeLaunchRequest != null || isFinishing()) return;
        if (appListPoller != null && selectedHost.uuid.equals(appListPollerHostUuid)) {
            appListPoller.pollNow();
            return;
        }
        stopSelectedAppListPolling();
        ComputerDetails details = computerManagerBinder.getComputer(selectedHost.uuid);
        if (details == null) return;
        appListPoller = computerManagerBinder.createAppListPoller(details);
        appListPollerHostUuid = selectedHost.uuid;
        appListPoller.start();
        LimeLog.info("Started Moonlight app list polling for " + selectedHost.name);
    }

    private void stopSelectedAppListPolling() {
        if (appListPoller != null) {
            appListPoller.stop();
            appListPoller = null;
        }
        appListPollerHostUuid = null;
    }

    private void retryUnifiedConnection() {
        if (activeLaunchRequest == null ||
                stateMachine.getState() != ConsoleStateMachine.State.CONNECTING) {
            clearUnifiedConnectRetry();
            return;
        }
        if (SystemClock.elapsedRealtime() >= unifiedConnectDeadlineMs) {
            clearUnifiedConnectRetry();
            handleUnifiedFailure(UnifiedConsoleLaunchPipeline.Failure.runtime(
                    "Streaming services did not become ready"));
            return;
        }
        LimeLog.info("Retrying unified stream connection during startup grace period");
        streamRuntime.launch(activeLaunchRequest);
    }

    private void clearUnifiedConnectRetry() {
        mainHandler.removeCallbacks(unifiedConnectRetry);
        unifiedConnectDeadlineMs = 0L;
    }

    private void showUnifiedConfigurationWarning(
            StreamSessionConfigurationPlanner.Plan plan) {
        String warning = null;
        if (plan.hdrDecoderUnavailable) {
            warning = "HDR was disabled because no compatible decoder is available.";
        } else if (plan.forcedAv1Unavailable) {
            warning = "AV1 was forced but no AV1 decoder is available.";
        } else if (plan.forcedHevcUnavailable) {
            warning = "HEVC was forced but no HEVC decoder is available.";
        }
        if (warning != null) {
            Toast.makeText(this, warning, Toast.LENGTH_LONG).show();
        }
    }

    private void showExitConfirmation() {
        boolean streamActive = unifiedTransportConnected ||
                ConsoleSessionSummary.from(currentSession).alive;
        modalController.showExitConfirmation(getCurrentFocus(), streamActive,
                () -> {
                    if (streamActive) endActiveSession(false);
                    finishAndRemoveTask();
                });
    }

    private void showSessionDetails() {
        ConsoleSessionSummary summary = ConsoleSessionSummary.from(currentSession);
        if (!summary.alive) return;
        modalController.showSessionDetails(getCurrentFocus(), summary,
                this::returnToActiveStream,
                () -> endActiveSession(false),
                () -> endActiveSession(true));
    }

    private void showOptions() {
        android.content.SharedPreferences preferences =
                getSharedPreferences("launch_history", MODE_PRIVATE);
        boolean uiSounds = preferences.getBoolean("ui_sounds", true);
        boolean reducedMotion = preferences.getBoolean("reduced_motion", false);
        modalController.showOptions(getCurrentFocus(), uiSounds, reducedMotion,
                enabled -> {
                    preferences.edit().putBoolean("ui_sounds", enabled).apply();
                    applyInterfacePreferences();
                },
                enabled -> {
                    preferences.edit().putBoolean("reduced_motion", enabled).apply();
                    applyInterfacePreferences();
                },
                this::showHostIntegrations,
                () -> startActivity(new Intent(this, StreamSettings.class)));
    }

    private void applyInterfacePreferences() {
        android.content.SharedPreferences preferences =
                getSharedPreferences("launch_history", MODE_PRIVATE);
        consoleTheme.setInterfacePreferences(
                preferences.getBoolean("ui_sounds", true),
                preferences.getBoolean("reduced_motion", false));
        consoleTheme.applyInterfacePreferences(root);
    }

    private void endActiveSession(boolean quitHostApplication) {
        modalController.hide();
        clearUnifiedConnectRetry();
        stateMachine.dispatch(ConsoleStateMachine.Event.DISCONNECT);
        applyState(stateMachine.getState());
        if (quitHostApplication) {
            streamRuntime.quitHostApplication();
        } else {
            streamRuntime.disconnectTransport();
        }
        unifiedTransportConnected = false;
        unifiedFirstFrameRendered = false;
        unifiedSessionInput = null;
        activeLaunchRequest = null;
        unifiedHomeSession.clear();
        stateMachine.dispatch(ConsoleStateMachine.Event.DISCONNECTED);
        renderSession(visibleSession());
        applyState(stateMachine.getState());
        resumeHostPolling();
    }

    private void showHostIntegrations() {
        ConsoleDataRepository.Host host = selectedHost;
        if (host == null || host.uuid == null || host.address == null || host.address.isEmpty()) {
            Toast.makeText(this, "Choose a streaming host first.", Toast.LENGTH_LONG).show();
            return;
        }
        GatewayConnection stored = hostGatewayStore.load(host.uuid);
        if (stored == null) {
            TextView pair = modalController.wakeAction("PAIR HOST GATEWAY");
            pair.setOnClickListener(view -> showGatewayPairing());
            modalController.showWakePanel(getCurrentFocus(), "HOST INTEGRATIONS", host.name,
                    "Start the Wake & Play Host Gateway on this PC, then enter its six-digit " +
                            "pairing code. Discord and repair options remain hidden until the " +
                            "corresponding Bridge is detected.",
                    null, pair);
            return;
        }

        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, host.address);
        int request = integrationPanelRequest.incrementAndGet();
        TextView status = modalController.wakeStatus("Gateway: checking\u2026");
        TextView profile = modalController.wakeAction("INTEGRATION PROFILE  \u00B7  " +
                connection.profileId.toUpperCase(Locale.ROOT) + "  \u203A");
        TextView vibepolloStatus = modalController.wakeStatus(
                "Vibepollo Bridge: checking\u2026");
        TextView discordStatus = modalController.wakeStatus("Discord Bridge: checking\u2026");
        TextView vibepollo = modalController.wakeAction("VIBEPOLLO FIX  \u203A");
        TextView discord = modalController.wakeAction("OPEN DISCORD  \u203A");
        TextView refresh = modalController.wakeAction("REFRESH STATUS");
        TextView forget = modalController.wakeAction("FORGET THIS GATEWAY");
        vibepollo.setVisibility(View.GONE);
        discord.setVisibility(View.GONE);
        profile.setOnClickListener(view -> refreshHostIntegrationsForProfile(host, stored));
        vibepollo.setOnClickListener(view -> showVibepolloPanel());
        discord.setOnClickListener(view -> showDiscordPanel());
        refresh.setOnClickListener(view -> showHostIntegrations());
        forget.setOnClickListener(view -> confirmForgetGateway(host));
        modalController.showWakePanel(getCurrentFocus(), "HOST INTEGRATIONS", host.name,
                "Paired gateway: " + connection.endpoint, null,
                status, profile, vibepolloStatus, discordStatus,
                vibepollo, discord, refresh, forget);

        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.IntegrationProfiles profiles =
                        hostGatewayClient.getIntegrationProfiles(connection);
                HostGatewayClient.IntegrationProfile selected = profiles.find(connection.profileId);
                HostGatewayClient.Capabilities capabilities =
                        hostGatewayClient.getCapabilities(connection);
                mainHandler.post(() -> {
                    if (request != integrationPanelRequest.get() || selectedHost == null ||
                            !host.uuid.equals(selectedHost.uuid)) return;
                    status.setText(selected == null ?
                            "Gateway: online\nSelected profile is no longer registered." :
                            "Gateway: online");
                    if (selected != null) {
                        boolean active = profiles.suggestedProfileId.equals(selected.id);
                        profile.setText("INTEGRATION PROFILE  \u00B7  " +
                                selected.name.toUpperCase(Locale.ROOT) +
                                (active ? "  \u00B7  ACTIVE" : "") + "  \u203A");
                    } else {
                        profile.setText("SELECT INTEGRATION PROFILE  \u203A");
                    }
                    vibepolloStatus.setText("Vibepollo Bridge: " +
                            (capabilities.vibepolloFix ? "online" : "offline"));
                    discordStatus.setText("Discord Bridge: " +
                            (capabilities.discord ? "online" : "offline"));
                    vibepollo.setVisibility(capabilities.vibepolloFix ?
                            View.VISIBLE : View.GONE);
                    discord.setVisibility(capabilities.discord ? View.VISIBLE : View.GONE);
                    modalController.rebuildWakeFocusNavigation();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (request != integrationPanelRequest.get()) return;
                    status.setText("Gateway: unavailable\n" + friendlyGatewayError(error));
                    vibepolloStatus.setText("Vibepollo Bridge: status unavailable");
                    discordStatus.setText("Discord Bridge: status unavailable");
                });
            }
        });
    }

    private void refreshHostIntegrationsForProfile(
            ConsoleDataRepository.Host host, GatewayConnection connection) {
        gatewayProfileRefreshController.refresh(connection,
                new GatewayProfileRefreshController.Callback() {
                    @Override public void onLoaded(IntegrationProfileCatalog catalog) {
                        if (selectedHost != null && host.uuid.equals(selectedHost.uuid)) {
                            showProfileChooser(host.uuid, connection, catalog);
                        }
                    }

                    @Override public void onUnavailable() {
                        Toast.makeText(ConsoleActivity.this,
                                "Integration profiles are unavailable.", Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void confirmForgetGateway(ConsoleDataRepository.Host host) {
        TextView cancel = modalController.wakeAction("CANCEL");
        TextView forget = modalController.wakeAction("FORGET GATEWAY");
        cancel.setOnClickListener(view -> showHostIntegrations());
        forget.setOnClickListener(view -> {
            integrationPanelRequest.incrementAndGet();
            hostGatewayStore.remove(host.uuid);
            renderGatewayProfile(host);
            showHostIntegrations();
        });
        modalController.showWakePanel(getCurrentFocus(), "HOST INTEGRATIONS",
                "Forget paired gateway?",
                "MoonWaker will delete the local client token and certificate pin. " +
                        "The host can be paired again with a new code.",
                this::showHostIntegrations, cancel, forget);
    }

    private void refreshIntegrationCapabilities(
            String hostUuid, HostGatewayClient.Connection connection) {
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.Capabilities capabilities =
                        hostGatewayClient.getCapabilities(connection);
                mainHandler.post(() -> modalController.updateHostIntegrationCapabilities(
                        hostUuid, capabilities));
            } catch (Exception error) {
                LimeLog.warning("Unable to refresh Gateway capabilities: " +
                        friendlyGatewayError(error));
            }
        });
    }

    private void showGatewayPairing() {
        ConsoleDataRepository.Host host = selectedHost;
        if (host == null || host.address == null || host.address.isEmpty()) return;
        EditText code = new EditText(this);
        code.setHint("Six-digit code");
        code.setSingleLine(true);
        code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        code.setPadding(dp(24), dp(12), dp(24), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("Pair Host Gateway")
                .setMessage("Enter the code displayed by Start-WakePlayGateway.ps1 on the host PC.")
                .setView(code)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Pair", (dialog, which) ->
                        pairGateway(host, code.getText().toString().trim()))
                .show();
    }

    private void pairGateway(ConsoleDataRepository.Host host, String code) {
        if (!code.matches("[0-9]{6}")) {
            Toast.makeText(this, "The pairing code must contain six digits.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Pairing Host Gateway...", Toast.LENGTH_SHORT).show();
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.Pairing pairing = hostGatewayClient.pair(
                        HostGatewayClient.endpointForHost(host.address), code,
                        "MoonWaker Game App");
                hostGatewayStore.save(host.uuid, pairing.connection);
                mainHandler.post(() -> {
                    if (selectedHost != null && host.uuid.equals(selectedHost.uuid)) {
                        renderGatewayProfile(selectedHost);
                        showHostIntegrations();
                    }
                    Toast.makeText(this, "Host Gateway paired.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                mainHandler.post(() -> Toast.makeText(this,
                        "Pairing failed: " + friendlyGatewayError(error),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private static String friendlyGatewayError(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ?
                "Host Gateway is unavailable." : message;
    }

    private void refreshHostIntegrations(String hostUuid, GatewayConnection connection) {
        gatewayProfileRefreshController.refresh(connection,
                new GatewayProfileRefreshController.Callback() {
                    @Override public void onLoaded(IntegrationProfileCatalog catalog) {
                        if (selectedHost == null || !hostUuid.equals(selectedHost.uuid)) return;
                        IntegrationProfileStatus profile = catalog.find(connection.profileId);
                        HostIntegrationSummary refreshed =
                                HostIntegrationSummary.from(connection, profile);
                        if (modalController.updateHostIntegrations(hostUuid, refreshed, catalog,
                                () -> showProfileChooser(hostUuid, connection, catalog))) {
                            renderGatewayProfile(selectedHost, refreshed);
                        }
                    }

                    @Override public void onUnavailable() {
                        // The existing local summary already communicates refresh availability.
                    }
                });
    }

    private HostGatewayClient.Connection selectedGatewayConnection() {
        return selectedHost == null ? null :
                hostGatewayStore.loadClientConnection(selectedHost.uuid, selectedHost.address);
    }

    private void showDiscordPanel() {
        ConsoleDataRepository.Host host = selectedHost;
        HostGatewayClient.Connection connection = selectedGatewayConnection();
        if (host == null || connection == null) { showHostIntegrations(); return; }
        showDiscordServersPanel(host, connection, false);
    }

    private void showDiscordServersPanel(ConsoleDataRepository.Host host,
                                         HostGatewayClient.Connection connection,
                                         boolean force) {
        int request = integrationPanelRequest.incrementAndGet();
        TextView loading = modalController.wakeStatus("Loading your Discord servers\u2026");
        modalController.showWakePanel(getCurrentFocus(), "DISCORD", "Servers",
                host.name + "  \u00B7  Profile " + connection.profileId,
                null, loading);
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.DiscordHome home =
                        hostGatewayClient.getDiscordHome(connection, force);
                HostGatewayClient.DiscordVoice voice = null;
                try {
                    voice = hostGatewayClient.getDiscordVoice(connection, force);
                } catch (Exception ignored) { }
                HostGatewayClient.DiscordVoice currentVoice = voice;
                mainHandler.post(() -> {
                    if (request != integrationPanelRequest.get()) return;
                    List<View> actions = new ArrayList<>();
                    if (currentVoice != null && currentVoice.connected) {
                        actions.add(modalController.wakeSection("CURRENT VOICE"));
                        actions.add(modalController.wakeStatus("\u25CF  " +
                                currentVoice.channelName + "  \u00B7  " +
                                currentVoice.participants + " participant" +
                                (currentVoice.participants == 1 ? "" : "s")));
                        TextView mute = modalController.discordAction(
                                currentVoice.muted ? "MIC MUTED  \u00B7  UNMUTE" :
                                        "MIC ON  \u00B7  MUTE",
                                currentVoice.muted ? DISCORD_RED : DISCORD_GREEN);
                        TextView leave = modalController.discordAction("LEAVE", DISCORD_RED);
                        mute.setOnClickListener(view -> runGatewayOperation(
                                "Toggling microphone\u2026",
                                () -> hostGatewayClient.setDiscordVoiceFlag(
                                        connection, "mute", "toggle"),
                                () -> showDiscordServersPanel(host, connection, true)));
                        leave.setOnClickListener(view -> runGatewayOperation(
                                "Leaving voice\u2026",
                                () -> hostGatewayClient.leaveDiscordChannel(connection),
                                () -> showDiscordServersPanel(host, connection, true)));
                        actions.add(modalController.wakeActionRow(mute, leave));
                    } else {
                        actions.add(modalController.wakeStatus(
                                "Voice disconnected  \u00B7  Select a server and channel to join."));
                    }
                    actions.add(modalController.wakeSection("TOOLS"));
                    TextView settings = modalController.discordAction("SETTINGS", DISCORD_TOOL);
                    TextView usb = modalController.discordAction("USB", DISCORD_TOOL);
                    TextView refresh = modalController.discordAction("REFRESH", DISCORD_SURFACE);
                    settings.setOnClickListener(view -> showDiscordSettingsPanel(host, connection));
                    usb.setOnClickListener(view -> showVirtualHerePanel(host, connection, false));
                    refresh.setOnClickListener(view ->
                            showDiscordServersPanel(host, connection, true));
                    actions.add(modalController.wakeActionRow(settings, usb));
                    actions.add(refresh);
                    actions.add(modalController.wakeSection("YOUR SERVERS"));
                    if (home.guilds.isEmpty()) {
                        actions.add(modalController.wakeStatus(
                                "No Discord servers are available for this account."));
                    } else {
                        for (HostGatewayClient.DiscordGuild guild : home.guilds) {
                            TextView action = modalController.discordAction(
                                    guild.name + "  \u203A", DISCORD_BLURPLE);
                            action.setOnClickListener(view ->
                                    showDiscordChannelsPanel(host, connection, guild, false));
                            actions.add(action);
                        }
                    }
                    modalController.showWakePanel(getCurrentFocus(), "DISCORD", "Servers",
                            host.name + "  \u00B7  Profile " + connection.profileId,
                            null, actions.toArray(new View[0]));
                });
            } catch (Exception error) {
                mainHandler.post(() -> showDiscordStartupPanel(
                        host, connection, error));
            }
        });
    }

    private void showDiscordStartupPanel(ConsoleDataRepository.Host host,
                                         HostGatewayClient.Connection connection,
                                         Throwable error) {
        TextView status = modalController.wakeStatus(
                "Discord is not ready on this host.\n" + friendlyGatewayError(error));
        TextView start = modalController.wakeAction("START DISCORD ON HOST");
        TextView retry = modalController.wakeAction("RETRY");
        start.setOnClickListener(view -> runGatewayOperation("Starting Discord\u2026",
                () -> hostGatewayClient.startDiscord(connection),
                () -> mainHandler.postDelayed(
                        () -> showDiscordServersPanel(host, connection, true), 2_500L)));
        retry.setOnClickListener(view ->
                showDiscordServersPanel(host, connection, true));
        modalController.showWakePanel(getCurrentFocus(), "DISCORD", "Start Discord",
                host.name + "  \u00B7  Profile " + connection.profileId,
                null, status, start, retry);
    }

    private void showDiscordChannelsPanel(ConsoleDataRepository.Host host,
                                          HostGatewayClient.Connection connection,
                                          HostGatewayClient.DiscordGuild guild,
                                          boolean force) {
        int request = integrationPanelRequest.incrementAndGet();
        TextView loading = modalController.wakeStatus("Loading voice channels\u2026");
        modalController.showWakePanel(getCurrentFocus(), "DISCORD", guild.name, null,
                () -> showDiscordServersPanel(host, connection, false), loading);
        integrationExecutor.execute(() -> {
            try {
                List<HostGatewayClient.DiscordChannel> channels =
                        hostGatewayClient.getDiscordChannels(connection, guild, force);
                HostGatewayClient.DiscordVoice voice = null;
                try { voice = hostGatewayClient.getDiscordVoice(connection, force); }
                catch (Exception ignored) { }
                HostGatewayClient.DiscordVoice currentVoice = voice;
                mainHandler.post(() -> {
                    if (request != integrationPanelRequest.get()) return;
                    List<View> actions = new ArrayList<>();
                    if (currentVoice != null && currentVoice.connected) {
                        actions.add(modalController.wakeSection("CURRENT VOICE"));
                        actions.add(modalController.wakeStatus("\u25CF  " +
                                currentVoice.channelName + "  \u00B7  " +
                                currentVoice.participants + " participants"));
                        TextView people = modalController.discordAction(
                                "PEOPLE  \u00B7  " + currentVoice.participants, DISCORD_BLURPLE);
                        TextView leave = modalController.discordAction("LEAVE", DISCORD_RED);
                        people.setOnClickListener(view ->
                                showDiscordParticipantsPanel(host, connection, true));
                        leave.setOnClickListener(view -> runGatewayOperation(
                                "Leaving voice\u2026",
                                () -> hostGatewayClient.leaveDiscordChannel(connection),
                                () -> showDiscordChannelsPanel(host, connection, guild, true)));
                        actions.add(modalController.wakeActionRow(people, leave));
                    }
                    actions.add(modalController.wakeSection("TOOLS"));
                    TextView settings = modalController.discordAction("SETTINGS", DISCORD_TOOL);
                    TextView usb = modalController.discordAction("USB", DISCORD_TOOL);
                    TextView refresh = modalController.discordAction("REFRESH", DISCORD_SURFACE);
                    settings.setOnClickListener(view -> showDiscordSettingsPanel(host, connection));
                    usb.setOnClickListener(view -> showVirtualHerePanel(host, connection, false));
                    refresh.setOnClickListener(view ->
                            showDiscordChannelsPanel(host, connection, guild, true));
                    actions.add(modalController.wakeActionRow(settings, usb));
                    actions.add(refresh);
                    actions.add(modalController.wakeSection("VOICE CHANNELS"));
                    if (channels.isEmpty()) {
                        actions.add(modalController.wakeStatus(
                                "No voice channels are available."));
                    }
                    for (HostGatewayClient.DiscordChannel channel : channels) {
                        boolean active = currentVoice != null && currentVoice.connected &&
                                channel.id.equals(currentVoice.channelId);
                        String count = channel.people >= 0 ?
                                "  \u00B7  " + channel.people + " people" : "";
                        String prefix = active ? "\u25CF  " : channel.favorite ? "\u2605  " : "#  ";
                        TextView action = modalController.discordAction(
                                prefix + channel.name + count,
                                active ? DISCORD_GREEN : DISCORD_SURFACE);
                        action.setOnClickListener(view -> showDiscordChannelPanel(
                                host, connection, guild, channel, false));
                        actions.add(action);
                    }
                    modalController.showWakePanel(getCurrentFocus(), "DISCORD", guild.name,
                            "Select a channel to see its people.",
                            () -> showDiscordServersPanel(host, connection, false),
                            actions.toArray(new View[0]));
                });
            } catch (Exception error) {
                mainHandler.post(() -> showDiscordLoadError(guild.name, error,
                        () -> showDiscordChannelsPanel(host, connection, guild, true),
                        () -> showDiscordServersPanel(host, connection, false)));
            }
        });
    }

    private void showDiscordLoadError(String title, Throwable error,
                                      Runnable retry, Runnable back) {
        TextView status = modalController.wakeStatus(
                "Unable to load Discord\n" + friendlyGatewayError(error));
        TextView retryAction = modalController.wakeAction("RETRY");
        TextView backAction = modalController.wakeAction("BACK");
        retryAction.setOnClickListener(view -> retry.run());
        backAction.setOnClickListener(view -> back.run());
        modalController.showWakePanel(getCurrentFocus(), "DISCORD", title, null, back,
                status, retryAction, backAction);
    }

    private void showDiscordChannelPanel(ConsoleDataRepository.Host host,
                                         HostGatewayClient.Connection connection,
                                         HostGatewayClient.DiscordGuild guild,
                                         HostGatewayClient.DiscordChannel channel,
                                         boolean force) {
        int request = integrationPanelRequest.incrementAndGet();
        TextView loading = modalController.wakeStatus("Loading channel\u2026");
        Runnable back = () -> showDiscordChannelsPanel(host, connection, guild, false);
        modalController.showWakePanel(getCurrentFocus(), "DISCORD", "# " + channel.name,
                guild.name, back, loading);
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.DiscordVoice voice =
                        hostGatewayClient.getDiscordVoice(connection, force);
                mainHandler.post(() -> {
                    if (request != integrationPanelRequest.get()) return;
                    boolean active = voice.connected && channel.id.equals(voice.channelId);
                    List<View> actions = new ArrayList<>();
                    actions.add(modalController.wakeSection("VOICE CHANNEL"));
                    if (active) {
                        actions.add(modalController.wakeStatus("\u25CF  Connected  \u00B7  " +
                                voice.participants + " participant" +
                                (voice.participants == 1 ? "" : "s")));
                        TextView mute = modalController.discordAction(
                                voice.muted ? "MIC MUTED  \u00B7  UNMUTE" : "MIC ON  \u00B7  MUTE",
                                voice.muted ? DISCORD_RED : DISCORD_GREEN);
                        TextView leave = modalController.discordAction("LEAVE", DISCORD_RED);
                        mute.setOnClickListener(view -> runGatewayOperation(
                                "Toggling microphone\u2026",
                                () -> hostGatewayClient.setDiscordVoiceFlag(
                                        connection, "mute", "toggle"),
                                () -> showDiscordChannelPanel(
                                        host, connection, guild, channel, true)));
                        leave.setOnClickListener(view -> runGatewayOperation(
                                "Leaving #" + channel.name + "\u2026",
                                () -> hostGatewayClient.leaveDiscordChannel(connection),
                                () -> showDiscordChannelPanel(
                                        host, connection, guild, channel, true)));
                        actions.add(modalController.wakeActionRow(mute, leave));
                        TextView people = modalController.discordAction(
                                "PEOPLE  \u00B7  " + voice.participants, DISCORD_BLURPLE);
                        people.setOnClickListener(view ->
                                showDiscordParticipantsPanel(host, connection, true));
                        actions.add(people);
                    } else {
                        if (voice.connected) {
                            actions.add(modalController.wakeStatus(
                                    "You are currently connected to \u25CF " +
                                            voice.channelName +
                                            ". Joining here will switch channels."));
                        } else {
                            actions.add(modalController.wakeStatus(
                                    (channel.people >= 0 ? channel.people +
                                            " people visible  \u00B7  " : "") +
                                            "Join to see and control participants."));
                        }
                        TextView join = modalController.discordAction(
                                "JOIN #" + channel.name, DISCORD_GREEN);
                        join.setOnClickListener(view -> runGatewayOperation(
                                "Joining #" + channel.name + "\u2026",
                                () -> hostGatewayClient.joinDiscordChannel(connection, channel),
                                () -> {
                                    hostGatewayStore.saveLastDiscordChannel(
                                            host.uuid, connection.profileId, channel.id,
                                            channel.guildId, channel.guildName, channel.name);
                                    showDiscordChannelPanel(
                                            host, connection, guild, channel, true);
                                }));
                        actions.add(join);
                    }
                    actions.add(modalController.wakeSection("TOOLS"));
                    TextView settings = modalController.discordAction("SETTINGS", DISCORD_TOOL);
                    TextView usb = modalController.discordAction("USB", DISCORD_TOOL);
                    settings.setOnClickListener(view -> showDiscordSettingsPanel(host, connection));
                    usb.setOnClickListener(view -> showVirtualHerePanel(host, connection, false));
                    actions.add(modalController.wakeActionRow(settings, usb));
                    modalController.showWakePanel(getCurrentFocus(), "DISCORD",
                            "# " + channel.name, guild.name, back,
                            actions.toArray(new View[0]));
                });
            } catch (Exception error) {
                mainHandler.post(() -> showDiscordLoadError("# " + channel.name, error,
                        () -> showDiscordChannelPanel(
                                host, connection, guild, channel, true), back));
            }
        });
    }

    private void showDiscordParticipantsPanel(ConsoleDataRepository.Host host,
                                              HostGatewayClient.Connection connection,
                                              boolean force) {
        int request = integrationPanelRequest.incrementAndGet();
        TextView loading = modalController.wakeStatus(
                "Loading people in the voice channel\u2026");
        Runnable back = () -> showDiscordServersPanel(host, connection, false);
        modalController.showWakePanel(getCurrentFocus(), "DISCORD", "People", null,
                back, loading);
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.DiscordVoice voice =
                        hostGatewayClient.getDiscordVoice(connection, force);
                mainHandler.post(() -> {
                    if (request != integrationPanelRequest.get()) return;
                    List<View> actions = new ArrayList<>();
                    if (!voice.connected) {
                        actions.add(modalController.wakeStatus(
                                "Discord is not connected to a voice channel."));
                    } else {
                        actions.add(modalController.wakeStatus("\u25CF  " + voice.channelName +
                                "  \u00B7  " + voice.participants + " participants"));
                        for (HostGatewayClient.DiscordParticipant participant :
                                voice.participantList) {
                            actions.add(modalController.wakeStatus(
                                    (participant.speaking ? "\u25CF  " : "") +
                                            participant.name +
                                            (participant.self ? "  \u00B7  YOU" : "") +
                                            "\nVolume " + participant.volume + "%" +
                                            (participant.muted ? "  \u00B7  MUTED" : "")));
                            if (!participant.self) {
                                SeekBar volume = modalController.wakeVolumeSlider(
                                        participant.volume);
                                TextView mute = modalController.discordAction(
                                        participant.muted ? "UNMUTE" : "MUTE",
                                        participant.muted ? DISCORD_GREEN : DISCORD_RED);
                                volume.setOnSeekBarChangeListener(
                                        new SeekBar.OnSeekBarChangeListener() {
                                            @Override public void onProgressChanged(
                                                    SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                                                if (!fromUser) return;
                                                int snapped = Math.max(0, Math.min(200,
                                                        Math.round(progress / 10f) * 10));
                                                if (snapped != progress) {
                                                    seekBar.setProgress(snapped);
                                                }
                                            }

                                            @Override public void onStartTrackingTouch(
                                                    SeekBar seekBar) { }

                                            @Override public void onStopTrackingTouch(
                                                    SeekBar seekBar) {
                                                setDiscordParticipantVolume(connection,
                                                        participant, seekBar.getProgress(),
                                                        host);
                                            }
                                        });
                                volume.setOnKeyListener((view, keyCode, event) -> {
                                    if (event.getAction() == KeyEvent.ACTION_UP &&
                                            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                                                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                                        setDiscordParticipantVolume(connection, participant,
                                                volume.getProgress(), host);
                                    }
                                    return false;
                                });
                                mute.setOnClickListener(view -> runGatewayOperation(
                                        "Updating participant mute\u2026",
                                        () -> hostGatewayClient.toggleDiscordParticipantMute(
                                                connection, participant.id),
                                        () -> showDiscordParticipantsPanel(host, connection, true)));
                                actions.add(modalController.wakeWeightedActionRow(volume, mute));
                            }
                        }
                    }
                    TextView refresh = modalController.discordAction("REFRESH", DISCORD_SURFACE);
                    TextView done = modalController.discordAction("BACK", DISCORD_TOOL);
                    refresh.setOnClickListener(view ->
                            showDiscordParticipantsPanel(host, connection, true));
                    done.setOnClickListener(view -> back.run());
                    actions.add(modalController.wakeActionRow(refresh, done));
                    modalController.showWakePanel(getCurrentFocus(), "DISCORD", "People",
                            voice.connected ? voice.channelName : null, back,
                            actions.toArray(new View[0]));
                });
            } catch (Exception error) {
                mainHandler.post(() -> showDiscordLoadError("People", error,
                        () -> showDiscordParticipantsPanel(host, connection, true), back));
            }
        });
    }

    private void setDiscordParticipantVolume(
            HostGatewayClient.Connection connection,
            HostGatewayClient.DiscordParticipant participant,
            int volume, ConsoleDataRepository.Host host) {
        int snapped = Math.max(0, Math.min(200, Math.round(volume / 10f) * 10));
        runGatewayOperation("Updating participant volume\u2026",
                () -> hostGatewayClient.setDiscordParticipantVolume(
                        connection, participant.id, snapped),
                () -> showDiscordParticipantsPanel(host, connection, true));
    }

    private void showDiscordSettingsPanel(ConsoleDataRepository.Host host,
                                          HostGatewayClient.Connection connection) {
        int request = integrationPanelRequest.incrementAndGet();
        boolean autoConnect = hostGatewayStore.isDiscordAutoConnectEnabled(
                host.uuid, connection.profileId);
        boolean autoJoin = hostGatewayStore.isDiscordAutoJoinLastEnabled(
                host.uuid, connection.profileId);
        TextView status = modalController.wakeStatus(
                "Discord profile " + connection.profileId + ": checking\u2026");
        TextView autoConnectAction = modalController.wakeAction(
                "START DISCORD WITH STREAM  \u00B7  " + (autoConnect ? "ON" : "OFF"));
        TextView autoJoinAction = modalController.wakeAction(
                "AUTO-JOIN LAST CHANNEL  \u00B7  " + (autoJoin ? "ON" : "OFF"));
        TextView start = modalController.wakeAction("START DISCORD ON HOST");
        TextView connect = modalController.wakeAction("CONNECT / AUTHORIZE RPC");
        TextView audio = modalController.wakeAction("AUDIO DEVICES");
        TextView refresh = modalController.wakeAction("REFRESH PROFILE STATUS");
        TextView back = modalController.wakeAction("BACK TO DISCORD");
        autoConnectAction.setOnClickListener(view -> {
            hostGatewayStore.setDiscordAutoConnectEnabled(
                    host.uuid, connection.profileId, !autoConnect);
            showDiscordSettingsPanel(host, connection);
        });
        autoJoinAction.setOnClickListener(view -> {
            hostGatewayStore.setDiscordAutoJoinLastEnabled(
                    host.uuid, connection.profileId, !autoJoin);
            showDiscordSettingsPanel(host, connection);
        });
        start.setOnClickListener(view -> runGatewayOperation("Starting Discord\u2026",
                () -> hostGatewayClient.startDiscord(connection),
                () -> showDiscordSettingsPanel(host, connection)));
        connect.setOnClickListener(view -> runGatewayOperation("Connecting Discord RPC\u2026",
                () -> hostGatewayClient.connectDiscord(connection, false),
                () -> showDiscordSettingsPanel(host, connection)));
        audio.setOnClickListener(view -> showDiscordAudio(connection));
        refresh.setOnClickListener(view -> showDiscordSettingsPanel(host, connection));
        back.setOnClickListener(view -> showDiscordServersPanel(host, connection, false));
        modalController.showWakePanel(getCurrentFocus(), "DISCORD", "Settings",
                "Integration profile: " + connection.profileId,
                () -> showDiscordServersPanel(host, connection, false),
                status, autoConnectAction, autoJoinAction, start, connect,
                audio, refresh, back);
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.DiscordStatus discord =
                        hostGatewayClient.getDiscordStatus(connection);
                mainHandler.post(() -> {
                    if (request != integrationPanelRequest.get()) return;
                    status.setText(!discord.bridgeOnline ? "Discord Bridge: offline" :
                            !discord.rpcConnected ? "Discord Bridge: online\nRPC disconnected" :
                                    !discord.authenticated ?
                                            "Discord RPC: authorization required" :
                                            "Discord connected and authorized");
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (request == integrationPanelRequest.get()) {
                        status.setText("Discord status unavailable\n" +
                                friendlyGatewayError(error));
                    }
                });
            }
        });
    }

    private void showVirtualHerePanel(ConsoleDataRepository.Host host,
                                      HostGatewayClient.Connection connection,
                                      boolean force) {
        showVirtualHereWakePanel(host, connection, force);
    }

    private void showVirtualHereWakePanel(ConsoleDataRepository.Host host,
                                          HostGatewayClient.Connection connection,
                                          boolean force) {
        int request = integrationPanelRequest.incrementAndGet();
        TextView loading = modalController.wakeStatus("Loading USB devices\u2026");
        Runnable back = () -> showDiscordServersPanel(host, connection, false);
        modalController.showWakePanel(getCurrentFocus(), "VIRTUALHERE", "USB devices",
                "Integration profile: " + connection.profileId +
                        "\nConnect host USB devices without leaving MoonWaker.",
                back, loading);
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.VirtualHereState state =
                        hostGatewayClient.getVirtualHereState(connection, force);
                mainHandler.post(() -> {
                    if (request != integrationPanelRequest.get()) return;
                    List<View> actions = new ArrayList<>();
                    String summary = state.installed ?
                            state.running ? "VirtualHere client: online" :
                                    "VirtualHere client: not running" :
                            "VirtualHere client: not installed";
                    if (!state.error.isEmpty()) summary += "\n" + state.error;
                    actions.add(modalController.wakeStatus(summary));
                    int devices = 0;
                    for (HostGatewayClient.VirtualHereServer server : state.servers) {
                        actions.add(modalController.wakeStatus("SERVER  \u00B7  " +
                                (!server.name.isEmpty() ? server.name : server.hostname)));
                        for (HostGatewayClient.VirtualHereDevice device : server.devices) {
                            devices++;
                            String stateLabel = device.inUseByMe ? "CONNECTED" :
                                    device.available ? "AVAILABLE" :
                                            device.inUse ? "IN USE" : "OFFLINE";
                            TextView use = modalController.wakeAction(
                                    (device.inUseByMe ? "\u25A0  " : "USB  ") +
                                            device.name + "  \u00B7  " + stateLabel);
                            TextView auto = modalController.wakeAction(
                                    device.autoUse ? "AUTO  \u00B7  ON" : "AUTO USE");
                            if (device.inUseByMe) {
                                use.setOnClickListener(view -> runVirtualHereOperation(
                                        host, connection, "stop", device.address,
                                        "Disconnecting " + device.name + "\u2026"));
                            } else if (device.available) {
                                use.setOnClickListener(view -> runVirtualHereOperation(
                                        host, connection, "use", device.address,
                                        "Connecting " + device.name + "\u2026"));
                            } else {
                                use.setOnClickListener(view -> Toast.makeText(this,
                                        device.boundHostname.isEmpty() ?
                                                "This USB device is unavailable." :
                                                "In use by " + device.boundHostname + ".",
                                        Toast.LENGTH_LONG).show());
                            }
                            auto.setOnClickListener(view -> {
                                if (device.autoUse) {
                                    Toast.makeText(this, "Auto use is already enabled.",
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    runVirtualHereOperation(host, connection, "auto",
                                            device.address,
                                            "Enabling auto use for " + device.name + "\u2026");
                                }
                            });
                            actions.add(modalController.wakeActionRow(use, auto));
                        }
                    }
                    if (devices == 0) actions.add(modalController.wakeStatus(
                            "No USB devices are currently advertised by a VirtualHere server."));
                    TextView restart = modalController.wakeAction("RESTART VIRTUALHERE");
                    TextView refresh = modalController.wakeAction("REFRESH");
                    TextView done = modalController.wakeAction("BACK");
                    restart.setOnClickListener(view -> runVirtualHereOperation(
                            host, connection, "restart", null, "Restarting VirtualHere\u2026"));
                    refresh.setOnClickListener(view ->
                            showVirtualHereWakePanel(host, connection, true));
                    done.setOnClickListener(view -> back.run());
                    actions.add(modalController.wakeActionRow(restart, refresh));
                    actions.add(done);
                    modalController.showWakePanel(getCurrentFocus(), "VIRTUALHERE",
                            "USB devices", null, back, actions.toArray(new View[0]));
                });
            } catch (Exception error) {
                mainHandler.post(() -> showDiscordLoadError("USB devices", error,
                        () -> showVirtualHereWakePanel(host, connection, true), back));
            }
        });
    }

    private void runVirtualHereOperation(ConsoleDataRepository.Host host,
                                         HostGatewayClient.Connection connection,
                                         String action, String address, String progress) {
        int request = integrationPanelRequest.incrementAndGet();
        TextView status = modalController.wakeStatus(progress);
        modalController.showWakePanel(getCurrentFocus(), "VIRTUALHERE", "USB devices",
                null, () -> showVirtualHereWakePanel(host, connection, true), status);
        integrationExecutor.execute(() -> {
            try {
                hostGatewayClient.runVirtualHereAction(connection, action, address);
            } catch (Exception error) {
                mainHandler.post(() -> Toast.makeText(this,
                        "VirtualHere: " + friendlyGatewayError(error),
                        Toast.LENGTH_LONG).show());
            }
            mainHandler.post(() -> {
                if (request == integrationPanelRequest.get()) {
                    showVirtualHereWakePanel(host, connection, true);
                }
            });
        });
    }

    private void showDiscordAudio(HostGatewayClient.Connection connection) {
        ConsoleDataRepository.Host host = selectedHost;
        if (host == null) { showDiscordPanel(); return; }
        int request = integrationPanelRequest.incrementAndGet();
        TextView loading = modalController.wakeStatus(
                "Loading Discord and Windows audio devices\u2026");
        Runnable back = () -> showDiscordSettingsPanel(host, connection);
        modalController.showWakePanel(getCurrentFocus(), "DISCORD", "Audio devices",
                null, back, loading);
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.DiscordAudioState audio =
                        hostGatewayClient.getDiscordAudioState(connection);
                mainHandler.post(() -> {
                    if (request != integrationPanelRequest.get()) return;
                    List<View> actions = new ArrayList<>();
                    if (audio.systemAvailable) {
                        actions.add(modalController.wakeSection("WINDOWS AUDIO"));
                        actions.add(modalController.wakeStatus("System volume: " +
                                audio.systemVolume + "%" +
                                (audio.systemMuted ? "  \u00B7  MUTED" : "")));
                        TextView down = modalController.wakeAction("\u22125");
                        TextView up = modalController.wakeAction("+5");
                        TextView mute = modalController.wakeAction(
                                audio.systemMuted ? "UNMUTE" : "MUTE");
                        down.setOnClickListener(view -> runGatewayOperation(
                                "Lowering volume\u2026",
                                () -> hostGatewayClient.changeSystemVolume(connection, -5),
                                () -> showDiscordAudio(connection)));
                        up.setOnClickListener(view -> runGatewayOperation(
                                "Raising volume\u2026",
                                () -> hostGatewayClient.changeSystemVolume(connection, 5),
                                () -> showDiscordAudio(connection)));
                        mute.setOnClickListener(view -> runGatewayOperation(
                                "Updating system mute\u2026",
                                () -> hostGatewayClient.toggleSystemMute(connection),
                                () -> showDiscordAudio(connection)));
                        actions.add(modalController.wakeActionRow(down, up, mute));
                    }
                    addAudioDeviceActions(actions, connection, "WINDOWS DEVICES",
                            audio.systemDevices);
                    addAudioDeviceActions(actions, connection, "DISCORD DEVICES",
                            audio.discordDevices);
                    if (!audio.error.isEmpty()) {
                        actions.add(modalController.wakeStatus(audio.error));
                    }
                    TextView refresh = modalController.wakeAction("REFRESH");
                    TextView done = modalController.wakeAction("BACK");
                    refresh.setOnClickListener(view -> showDiscordAudio(connection));
                    done.setOnClickListener(view -> back.run());
                    actions.add(modalController.wakeActionRow(refresh, done));
                    modalController.showWakePanel(getCurrentFocus(), "DISCORD",
                            "Audio devices", null, back, actions.toArray(new View[0]));
                });
            } catch (Exception error) {
                mainHandler.post(() -> showDiscordLoadError("Audio devices", error,
                        () -> showDiscordAudio(connection), back));
            }
        });
    }

    private void addAudioDeviceActions(List<View> actions,
                                       HostGatewayClient.Connection connection,
                                       String title,
                                       List<HostGatewayClient.AudioDevice> devices) {
        actions.add(modalController.wakeSection(title));
        if (devices.isEmpty()) {
            actions.add(modalController.wakeStatus("No devices reported."));
            return;
        }
        for (HostGatewayClient.AudioDevice device : devices) {
            TextView action = modalController.wakeAction(
                    (device.current ? "\u2713  " : "") + device.name);
            action.setOnClickListener(view -> runGatewayOperation(
                    "Selecting " + device.name + "\u2026",
                    () -> hostGatewayClient.selectAudioDevice(connection, device),
                    () -> showDiscordAudio(connection)));
            actions.add(action);
        }
    }

    private void showVibepolloPanel() {
        ConsoleDataRepository.Host host = selectedHost;
        HostGatewayClient.Connection connection = selectedGatewayConnection();
        if (host == null || connection == null) { showHostIntegrations(); return; }
        int request = integrationPanelRequest.incrementAndGet();
        TextView status = modalController.wakeStatus("Loading Vibepollo status\u2026");
        TextView restart = modalController.wakeAction("RESTART VIBEPOLLO");
        TextView resetDisplay = modalController.wakeAction("RESET REMEMBERED DISPLAY");
        TextView exportLogs = modalController.wakeAction("EXPORT VIBEPOLLO LOGS");
        TextView refresh = modalController.wakeAction("REFRESH STATUS");
        restart.setOnClickListener(view -> confirmVibepolloAction(connection, "restart",
                "Restart Vibepollo?",
                "The active host service will be restarted. An active stream may be interrupted."));
        resetDisplay.setOnClickListener(view -> confirmVibepolloAction(connection,
                "reset-display", "Reset remembered display?",
                "Vibepollo will forget its persisted display choice and select it again " +
                        "on the next session."));
        exportLogs.setOnClickListener(view -> runVibepolloAction(connection,
                "export-logs", "Exporting Vibepollo logs\u2026"));
        refresh.setOnClickListener(view -> showVibepolloPanel());
        modalController.showWakePanel(getCurrentFocus(), "VIBEPOLLO FIX", host.name,
                "Integration profile: " + connection.profileId +
                        "\nRepair actions run on the paired host. Restart and display reset " +
                        "always require confirmation.",
                this::showHostIntegrations, status, restart, resetDisplay, exportLogs, refresh);
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.RepairStatus repair =
                        hostGatewayClient.getVibepolloRepairStatus(connection);
                mainHandler.post(() -> {
                    if (request != integrationPanelRequest.get()) return;
                    StringBuilder value = new StringBuilder(repair.online ?
                            "Vibepollo online" : "Vibepollo API unavailable");
                    if (!repair.version.isEmpty()) value.append("\nVersion ").append(repair.version);
                    if (!repair.error.isEmpty()) value.append("\nLast API error: ").append(repair.error);
                    status.setText(value.toString());
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (request == integrationPanelRequest.get()) {
                        status.setText("Unable to load Vibepollo status\n" +
                                friendlyGatewayError(error));
                    }
                });
            }
        });
    }

    private void confirmVibepolloAction(HostGatewayClient.Connection connection,
                                        String action, String title, String warning) {
        TextView cancel = modalController.wakeAction("CANCEL");
        TextView confirm = modalController.wakeAction("CONFIRM");
        cancel.setOnClickListener(view -> showVibepolloPanel());
        confirm.setOnClickListener(view -> runVibepolloAction(
                connection, action, title + "\u2026"));
        modalController.showWakePanel(getCurrentFocus(), "VIBEPOLLO FIX", title, warning,
                this::showVibepolloPanel, cancel, confirm);
    }

    private void runVibepolloAction(HostGatewayClient.Connection connection,
                                     String action, String progress) {
        int request = integrationPanelRequest.incrementAndGet();
        TextView status = modalController.wakeStatus(progress);
        TextView back = modalController.wakeAction("BACK TO VIBEPOLLO FIX");
        TextView close = modalController.wakeAction("CLOSE");
        back.setVisibility(View.GONE);
        back.setOnClickListener(view -> showVibepolloPanel());
        close.setOnClickListener(view -> modalController.hide());
        modalController.showWakePanel(getCurrentFocus(), "VIBEPOLLO FIX", "Host operation",
                null, this::showVibepolloPanel, status, back, close);
        integrationExecutor.execute(() -> {
            String result;
            try {
                hostGatewayClient.runVibepolloRepair(connection, action);
                result = "Operation completed successfully.";
            } catch (Exception error) {
                result = "Operation failed\n" + friendlyGatewayError(error);
            }
            String rendered = result;
            mainHandler.post(() -> {
                if (request != integrationPanelRequest.get()) return;
                status.setText(rendered);
                back.setVisibility(View.VISIBLE);
                modalController.rebuildWakeFocusNavigation();
            });
        });
    }

    private void runGatewayOperation(String progress, GatewayOperation operation,
                                     Runnable success) {
        Toast.makeText(this, progress, Toast.LENGTH_SHORT).show();
        integrationExecutor.execute(() -> {
            try {
                operation.run();
                mainHandler.post(success);
            } catch (Exception error) {
                mainHandler.post(() -> Toast.makeText(this,
                        friendlyGatewayError(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private interface GatewayOperation {
        void run() throws Exception;
    }

    private void showProfileChooser(String hostUuid, GatewayConnection connection,
                                    IntegrationProfileCatalog catalog) {
        if (selectedHost == null || !hostUuid.equals(selectedHost.uuid)) return;
        String hostName = selectedHost.name;
        modalController.showProfileChooser(getCurrentFocus(), hostUuid, hostName, catalog,
                connection.profileId, profileId -> {
                    hostGatewayStore.setSelectedIntegrationProfileId(hostUuid, profileId);
                    if (selectedHost != null && hostUuid.equals(selectedHost.uuid)) {
                        renderGatewayProfile(selectedHost);
                        showHostIntegrations();
                    }
                }, this::showHostIntegrations, gatewayProfileRefreshController::cancel);
    }

    private void applyTvWindow() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.systemBars());
        }
    }

    private TextView compactHomeAction(String value) {
        TextView action = label(value, 13, 0xFFD2C4FF, true);
        action.setFocusable(true);
        action.setClickable(true);
        action.setSoundEffectsEnabled(false);
        action.setPadding(dp(12), dp(5), dp(12), dp(5));
        action.setOnFocusChangeListener((view, focused) ->
                styleCompactButton(action, focused));
        styleCompactButton(action, false);
        return action;
    }

    private void stylePrimaryButton(View button, boolean focused) {
        int top = ConsolePalette.withAlpha(ConsolePalette.blend(
                0xFF644FA0, ConsolePalette.ACCENT, 0.42f), focused ? 0xE0 : 0xA9);
        int bottom = ConsolePalette.withAlpha(ConsolePalette.blend(
                0xFF42366D, ConsolePalette.ACCENT, 0.28f), focused ? 0xCA : 0x8F);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        background.setCornerRadius(dp(12));
        background.setStroke(dp(focused ? 2 : 1),
                focused ? 0xFFF0EBFF : 0x706E5AA4);
        button.setBackground(background);
        button.setElevation(dp(focused ? 8 : 3));
        button.animate().cancel();
        float scale = focused ? 1.01f : 1f;
        if (button.isLaidOut()) {
            button.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        } else {
            button.setScaleX(scale);
            button.setScaleY(scale);
        }
    }

    private void styleCompactButton(View button, boolean focused) {
        int top = ConsolePalette.withAlpha(ConsolePalette.blend(
                0xFF58478F, ConsolePalette.ACCENT, 0.38f), focused ? 0xC7 : 0x32);
        int bottom = ConsolePalette.withAlpha(ConsolePalette.blend(
                0xFF342B59, ConsolePalette.ACCENT, 0.22f), focused ? 0xB0 : 0x65);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        background.setCornerRadius(dp(10));
        background.setStroke(dp(focused ? 2 : 1),
                focused ? 0xFFE9E3FF : 0x387C89B2);
        button.setBackground(background);
        button.animate().cancel();
        button.setScaleX(1f);
        button.setScaleY(1f);
    }

    private TextView card(String value, int width, int height) {
        TextView view = label(value, 15, Color.WHITE, true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        view.setFocusable(true);
        view.setClickable(true);
        view.setMinWidth(width);
        view.setMinHeight(height);
        view.setBackground(consoleTheme.cardBackground());
        view.setOnFocusChangeListener(consoleTheme::onCardFocus);
        return view;
    }

    private TextView section(String value) { return label(value, 13, 0xFF9CA6C5, true); }
    private TextView label(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        view.setTypeface(android.graphics.Typeface.DEFAULT, bold ? 1 : 0);
        return view;
    }
    private HorizontalScrollView horizontalScroll() {
        HorizontalScrollView view = new HorizontalScrollView(this);
        view.setHorizontalScrollBarEnabled(false);
        view.setClipToPadding(false);
        view.setClipChildren(false);
        view.setPadding(0, 0, dp(12), 0);
        view.setFocusable(false);
        return view;
    }
    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }
    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = wrap();
        params.rightMargin = dp(12);
        return params;
    }
    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams params = wrap();
        params.topMargin = margin;
        return params;
    }
    private FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(matchWidth(), matchHeight()); }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(wrapSize(), wrapSize()); }
    private static int matchWidth() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int matchHeight() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrapSize() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
