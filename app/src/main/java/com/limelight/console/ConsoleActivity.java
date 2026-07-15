package com.limelight.console;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.StreamView;
import com.limelight.ui.overlay.CustomCommand;
import com.limelight.ui.overlay.OverlayMenuView;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Milestone-1 Android TV console shell with a persistent stream surface layer. */
public final class ConsoleActivity extends Activity implements SurfaceHolder.Callback {
    private static final long SESSION_REFRESH_MS = 1500L;
    private static final int REQUEST_BLUETOOTH_CONNECT = 7001;
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
    private final Runnable sessionRefresh = this::refreshVisibleSession;

    private ConsoleDataRepository repository;
    private HostGatewayStore hostGatewayStore;
    private HostAvailabilityProbeController hostAvailabilityProbeController;
    private ConsoleHostLaunchPreparationController launchPreparationController;
    private ConsoleStreamRuntime streamRuntime;
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
    private PreferenceConfiguration overlayPreferences;
    private ConsoleLoadingController loadingController;
    private GatewayProfileRefreshController gatewayProfileRefreshController;
    private ConsoleControllerRepository controllerRepository;
    private InputManager inputManager;
    private boolean controllerListenerRegistered;
    private final Map<String, TextView> hostStatusViews = new HashMap<>();
    private List<ConsoleDataRepository.Host> visibleHosts = Collections.emptyList();
    private FrameLayout root;
    private StreamView streamSurface;
    private View privacyLayer;
    private FrameLayout homeLayer;
    private ImageView artworkBackdrop;
    private ImageView artworkHero;
    private TextView sessionStatus;
    private TextView integrationStatus;
    private TextView returnToGame;
    private TextView sessionButton;
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
    private ConsoleControllerRepository.Controller pendingController;

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
        setContentView(buildRoot());
        streamRuntime = createStreamRuntime();
        artworkController = new ConsoleArtworkController(this, artworkBackdrop, artworkHero);
        modalController = new ConsoleModalController(
                this, (FrameLayout) modalLayer, inputRouter, consoleTheme);
        gatewayProfileRefreshController = new GatewayProfileRefreshController();
        controllerRepository = new ConsoleControllerRepository();
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        renderSnapshot();
    }

    @Override protected void onResume() {
        super.onResume();
        if (inputManager != null && !controllerListenerRegistered) {
            inputManager.registerInputDeviceListener(controllerDeviceListener, null);
            controllerListenerRegistered = true;
        }
        renderControllers();
        refreshHostAvailability();
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
        mainHandler.postDelayed(sessionRefresh, SESSION_REFRESH_MS);
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
        if (hostAvailabilityProbeController != null) hostAvailabilityProbeController.cancel();
        if (launchPreparationController != null) launchPreparationController.cancel();
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
        integrationExecutor.shutdownNow();
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
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (modalController != null && modalController.dismissIfVisible()) return;
        if (stateMachine.getState() == ConsoleStateMachine.State.CONNECTING &&
                launchPreparationController != null) {
            launchPreparationController.cancel();
            cancelUnifiedPendingLaunch();
            unifiedHomeSession.clear();
        }
        ConsoleStateMachine.Transition transition = stateMachine.dispatch(ConsoleStateMachine.Event.BACK);
        if (transition.effect == ConsoleStateMachine.Effect.SHOW_EXIT_CONFIRMATION) {
            showExitConfirmation();
        } else {
            applyState(transition.current);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (overlayMenuView != null && overlayMenuView.getVisibility() == View.VISIBLE &&
                overlayMenuView.dispatchKeyEvent(event)) {
            return true;
        }
        int keyCode = event.getKeyCode();
        android.view.InputDevice device = event.getDevice();
        boolean gamepadB = keyCode == KeyEvent.KEYCODE_BUTTON_B && device != null &&
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

        overlayMenuView = new OverlayMenuView(this);
        overlayMenuView.setVisibility(View.GONE);
        overlayLayer = overlayMenuView;
        configureMoonlightOverlay();
        overlayLayer.setElevation(dp(40));
        root.addView(overlayLayer, match());

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
        // Runtime bitrate changes require reconnecting the in-Activity pipeline. Keep the
        // existing Moonlight X control hidden until that reconnect path is connected.
        overlayMenuView.setBitrateControlEnabled(false);
        overlayMenuView.setExternalFrontend(true);
        overlayMenuView.setDiscordShortcuts(overlayPreferences.discordMuteShortcut,
                overlayPreferences.discordLeaveShortcut);
        overlayMenuView.setDiscordConfigured(false);
        overlayMenuView.setMenuActionListener(new OverlayMenuView.MenuActionListener() {
            @Override public void onDisconnect() { endActiveSession(false); }

            @Override public void onQuitSession() { endActiveSession(true); }

            @Override public void onToggleStats() {
                overlayPreferences.enablePerfOverlay = !overlayPreferences.enablePerfOverlay;
                Toast.makeText(ConsoleActivity.this,
                        overlayPreferences.enablePerfOverlay ?
                                "Performance statistics enabled" :
                                "Performance statistics disabled",
                        Toast.LENGTH_SHORT).show();
            }

            @Override public void onToggleMouseEmulation() {
                if (unifiedSessionInput != null) unifiedSessionInput.toggleMouseEmulation();
            }

            @Override public void onShowKeyboard() { toggleStreamKeyboard(); }

            @Override public void onSendGuideButton() {
                if (unifiedSessionInput != null) unifiedSessionInput.sendGuideButton();
            }

            @Override public void onApplyBitrate(int bitrateKbps) {
                // Hidden until the unified reconnect path supports runtime bitrate changes.
            }

            @Override public void onCustomCommand(CustomCommand command) {
                runOverlayCustomCommand(command);
            }

            @Override public void onReturnToFrontend() { openConsoleHome(); }

            @Override public void onDiscordMute() { showDiscordPanel(); }

            @Override public void onDiscordLeave() { showDiscordPanel(); }

            @Override public void onDiscordRejoin() { showDiscordPanel(); }

            @Override public void onDiscordDockToggle() { showDiscordPanel(); }

            @Override public void onMenuClosed() { closeMoonlightOverlayState(); }
        });
    }

    private void showMoonlightOverlay() {
        if (overlayMenuView == null) return;
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

        TextView title = label("MOONWAKER GAME APP", 30, Color.WHITE, true);
        content.addView(title, wrap());
        TextView subtitle = label(
                "Choose a host and application. MoonWaker will prepare and protect the stream.",
                15, 0xFFBCC3DD, false);
        content.addView(subtitle, top(dp(5)));

        sessionStatus = label("SESSION · LOADING", 13, 0xFF9CA6C5, true);
        LinearLayout.LayoutParams sessionParams = wrap();
        sessionParams.topMargin = dp(10);
        content.addView(sessionStatus, sessionParams);

        integrationStatus = label("HOST INTEGRATIONS · SELECT A HOST", 12, 0xFF9CA6C5, true);

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(quickActions, top(dp(8)));

        returnToGame = card("▶  RETURN TO GAME", dp(280), dp(54));
        returnToGame.setId(View.generateViewId());
        returnToGame.setContentDescription("Return to active game");
        returnToGame.setVisibility(View.GONE);
        returnToGame.setOnClickListener(view -> {
            if (currentSession != null && currentSession.alive) {
                returnToActiveStream();
            } else if (resumeHost != null && resumeApp != null) {
                launchLegacy(resumeHost, resumeApp);
            }
        });
        quickActions.addView(returnToGame, wrap());

        sessionButton = card("SESSION", dp(190), dp(54));
        sessionButton.setId(View.generateViewId());
        sessionButton.setContentDescription("Open active session details");
        sessionButton.setVisibility(View.GONE);
        sessionButton.setOnClickListener(view -> showSessionDetails());
        LinearLayout.LayoutParams sessionButtonParams = wrap();
        sessionButtonParams.leftMargin = dp(10);
        quickActions.addView(sessionButton, sessionButtonParams);

        TextView integrations = card("INTEGRATIONS", dp(170), dp(44));
        integrations.setId(View.generateViewId());
        integrations.setContentDescription("Open host integrations");
        integrations.setOnClickListener(view -> showHostIntegrations());
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

        content.addView(section("APPS"), top(dp(10)));
        HorizontalScrollView appScroll = horizontalScroll();
        appScroll.setPadding(0, 0, dp(12), dp(10));
        appRow = horizontalRow();
        appScroll.addView(appRow);
        LinearLayout.LayoutParams appScrollParams =
                new LinearLayout.LayoutParams(matchWidth(), dp(120));
        appScrollParams.topMargin = dp(5);
        content.addView(appScroll, appScrollParams);

        home.addView(content, new FrameLayout.LayoutParams(matchWidth(), matchHeight()));

        TextView options = card("⚙  OPTIONS", dp(150), dp(44));
        options.setId(View.generateViewId());
        options.setContentDescription("Open MoonWaker options");
        options.setOnClickListener(view -> showOptions());
        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.HORIZONTAL);
        topActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams integrationsParams = wrap();
        integrationsParams.rightMargin = dp(10);
        topActions.addView(integrations, integrationsParams);
        topActions.addView(options, wrap());
        integrations.setNextFocusRightId(options.getId());
        options.setNextFocusLeftId(integrations.getId());
        integrations.setNextFocusDownId(returnToGame.getId());
        options.setNextFocusDownId(returnToGame.getId());
        FrameLayout.LayoutParams optionsParams = new FrameLayout.LayoutParams(
                wrapSize(), wrapSize(), Gravity.TOP | Gravity.RIGHT);
        optionsParams.topMargin = dp(30);
        optionsParams.rightMargin = dp(64);
        home.addView(topActions, optionsParams);
        return home;
    }

    private void renderSnapshot() {
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
            hostRow.addView(label("No saved Moonlight hosts", 16, 0xFFFFB74D, false), cardParams());
            renderApps(null, snapshot.apps);
            renderGatewayProfile(null, snapshot.integrations);
            return;
        }
        for (ConsoleDataRepository.Host host : snapshot.hosts) {
            hostRow.addView(hostCard(host,
                    host.uuid.equals(snapshot.selectedHost.uuid)), cardParams());
        }
        selectedHost = snapshot.selectedHost;
        renderApps(selectedHost, snapshot.apps);
        renderGatewayProfile(selectedHost, snapshot.integrations);
        // One deterministic initial focus matching Wake: prefer Resume/Return
        // when it exists. Subsequent refreshes never request focus.
        View initialFocus = returnToGame.getVisibility() == View.VISIBLE ?
                returnToGame : hostRow.getChildAt(snapshot.selectedHostIndex);
        initialFocus.requestFocus();
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
        TextView icon = label("▣", 16, 0xFF9E8ACB, true);
        card.addView(icon, wrap());
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
        card.setOnFocusChangeListener(consoleTheme::onCardFocus);
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
                    status.setText(availability.label());
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
        for (int index = 0; index < hostRow.getChildCount(); index++) {
            View hostCard = hostRow.getChildAt(index);
            hostCard.setSelected(host.uuid.equals(hostCard.getTag()));
        }
        renderApps(host, selection.apps);
        renderGatewayProfile(host);
        if (userFocusedHost && selection.focusAppIndex >= 0 &&
                selection.focusAppIndex < appRow.getChildCount()) {
            appRow.getChildAt(selection.focusAppIndex).requestFocus();
        }
    }

    private void renderApps(ConsoleDataRepository.Host host,
                            List<ConsoleDataRepository.App> apps) {
        appRow.removeAllViews();
        if (apps.isEmpty()) {
            appRow.addView(label("No cached applications. Refresh this host in Moonlight.",
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
        TextView metadata = label(launchHistoryStore.metadata(
                host.uuid, app.id, System.currentTimeMillis()), 10, 0xFFAAAFC2, true);
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
            action.animate().alpha(focused ? 1f : 0f).setDuration(120).start();
            if (focused) {
                hostSelectionController.rememberApp(host, app);
                artworkController.show(app.posterUri, poster.getDrawable());
            }
        });
        card.setOnClickListener(view -> launchLegacy(host, app));
        return card;
    }

    private void launchLegacy(ConsoleDataRepository.Host host, ConsoleDataRepository.App app) {
        LimeLog.info("Unified Console launch requested");
        launchHistoryStore.record(host, app, System.currentTimeMillis());
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
                        modalController.showHostWakeTimeout(getCurrentFocus(), host.name,
                                () -> launchLegacy(host, app));
                    }
                });
    }

    private void launchPreparedLegacy(ConsoleDataRepository.Host host,
                                      ConsoleDataRepository.App app) {
        ConsoleLaunchContract.Request request =
                ConsoleLaunchContract.create(host, app, getPackageName());
        unifiedTransportConnected = false;
        unifiedFirstFrameRendered = false;
        unifiedSessionInput = null;
        LimeLog.info("Unified Console runtime launch: " +
                streamRuntime.getClass().getSimpleName());
        streamRuntime.launch(request);
    }

    private void renderControllers() {
        if (controllerRepository == null || controllerRow == null) return;
        List<ConsoleControllerRepository.Controller> controllers = controllerRepository.load();
        controllerRow.removeAllViews();
        controllersLabel.setText(controllers.isEmpty() ? "CONTROLLERS · NONE" : "CONTROLLERS");
        int player = 1;
        for (ConsoleControllerRepository.Controller controller : controllers) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(16), dp(5), dp(16), dp(5));
            chip.setMinimumWidth(dp(220));
            chip.setMinimumHeight(dp(50));
            chip.setBackground(consoleTheme.cardBackground());
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setOnFocusChangeListener(consoleTheme::onCardFocus);
            chip.setOnClickListener(view -> openControllerActions(controller));
            chip.addView(label("P" + player++ + "  " + controller.name,
                    14, Color.WHITE, true), wrap());
            int batteryColor = controller.batteryPercentage < 0 ? 0xFFB3B8C8 :
                    controller.charging ? 0xFF64B5F6 :
                            controller.batteryPercentage <= 10 ? 0xFFFF5252 :
                                    controller.batteryPercentage <= 30 ? 0xFFFFB74D : 0xFF69F0AE;
            chip.addView(label(controller.batteryLabel(), 12, batteryColor, false), wrap());
            controllerRow.addView(chip, cardParams());
        }
    }

    private void openControllerActions(ConsoleControllerRepository.Controller controller) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingController = controller;
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_BLUETOOTH_CONNECT);
            return;
        }
        modalController.showControllerActions(getCurrentFocus(), controller,
                () -> ControllerActions.identify(controller.deviceId, mainHandler,
                        this::showControllerActionResult),
                () -> ControllerActions.disconnect(this, controller.deviceId,
                        this::showControllerActionResult),
                () -> ControllerActions.unpair(controller.deviceId,
                        this::showControllerActionResult));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH_CONNECT) return;
        ConsoleControllerRepository.Controller controller = pendingController;
        pendingController = null;
        if (controller != null && grantResults.length > 0 &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            openControllerActions(controller);
        } else {
            Toast.makeText(this, "Bluetooth permission is required to disconnect a controller.",
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
            return;
        }
        if (!summary.gatewayPaired) {
            integrationStatus.setText("HOST INTEGRATIONS · GATEWAY NOT PAIRED");
            integrationStatus.setTextColor(0xFF9CA6C5);
        }
        else {
            integrationStatus.setText("HOST INTEGRATIONS · PROFILE " +
                    summary.profileId.toUpperCase(Locale.ROOT) + " · GATEWAY PAIRED");
            integrationStatus.setTextColor(0xFF69F0AE);
        }
    }

    private void renderSession(ConsoleDataRepository.Session session) {
        currentSession = session;
        ConsoleSessionSummary summary = ConsoleSessionSummary.from(session);
        sessionStatus.setText(summary.label);
        sessionStatus.setTextColor(summary.alive ? 0xFF69F0AE : 0xFF9CA6C5);
        if (summary.alive) {
            String app = session != null && session.app != null ? session.app : "ACTIVE SESSION";
            returnToGame.setText("▶  RETURN TO GAME\n" + app);
            returnToGame.setContentDescription("Return to active game, " + app);
            returnToGame.setVisibility(View.VISIBLE);
        } else if (resumeHost != null && resumeApp != null) {
            returnToGame.setText("▶  RESUME LAST\n" + resumeApp.name);
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
        if (layers.overlayVisible) {
            if (openingOverlay) showMoonlightOverlay();
        } else if (overlayMenuView != null) {
            overlayMenuView.hide(null);
        }
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

        AndroidConsoleSessionEnvironment environment =
                new AndroidConsoleSessionEnvironment(
                        this,
                        streamSurface,
                        new AndroidConsoleSessionEnvironment.Callbacks() {
                            @Override public void onInputReady(ConsoleSessionInput input) {
                                unifiedSessionInput = input;
                                updateUnifiedInputSensors();
                            }

                            @Override public void onFirstFrameRendered() {
                                unifiedFirstFrameRendered = true;
                                maybeRevealUnifiedStream();
                            }

                            @Override public void onPerformanceUpdate(String text) {
                                LimeLog.info("Unified stream performance: " + text);
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
                new MoonlightConsoleSessionFactory(this, environment);
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
                loadingController.updateStatus("Preparing Moonlight session…");
                break;
            case CONNECTED:
                unifiedTransportConnected = true;
                unifiedHomeSession.connected();
                renderSession(visibleSession());
                loadingController.updateStatus("Waiting for the first video frame…");
                maybeRevealUnifiedStream();
                break;
            case FAILED:
                unifiedTransportConnected = false;
                unifiedFirstFrameRendered = false;
                unifiedSessionInput = null;
                unifiedHomeSession.clear();
                renderSession(visibleSession());
                stateMachine.dispatch(ConsoleStateMachine.Event.CONNECTION_FAILED);
                applyState(stateMachine.getState());
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
        loadingController.updateStatus("Connection failed: " + reason);
        modalController.showConnectionRecovery(getCurrentFocus(), reason,
                () -> {
                    if (resumeHost != null && resumeApp != null) {
                        launchLegacy(resumeHost, resumeApp);
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
        if (streamRuntime instanceof UnifiedConsoleRuntimeBootstrap) {
            ((UnifiedConsoleRuntimeBootstrap) streamRuntime).cancelPendingLaunch();
        }
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
        modalController.showExitConfirmation(getCurrentFocus(), this::finish);
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
                () -> {
                    preferences.edit().putBoolean("ui_sounds", !uiSounds).apply();
                    showOptions();
                },
                () -> {
                    preferences.edit().putBoolean("reduced_motion", !reducedMotion).apply();
                    showOptions();
                },
                this::showHostIntegrations,
                () -> startActivity(new Intent(this, StreamSettings.class)));
    }

    private void endActiveSession(boolean quitHostApplication) {
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
        unifiedHomeSession.clear();
        stateMachine.dispatch(ConsoleStateMachine.Event.DISCONNECTED);
        renderSession(visibleSession());
        applyState(stateMachine.getState());
    }

    private void showHostIntegrations() {
        if (selectedHost == null) {
            Toast.makeText(this, "Choose a streaming host first.", Toast.LENGTH_SHORT).show();
            return;
        }

        GatewayConnection connection = hostGatewayStore.load(selectedHost.uuid);
        HostIntegrationSummary summary = HostIntegrationSummary.from(connection);
        String hostUuid = selectedHost.uuid;
        modalController.showHostIntegrations(getCurrentFocus(), hostUuid,
                selectedHost.name, summary, this::showGatewayPairing, () -> {
            hostGatewayStore.setSelectedIntegrationProfileId(
                    hostUuid, GatewayConnection.DEFAULT_PROFILE_ID);
            if (selectedHost != null && hostUuid.equals(selectedHost.uuid)) {
                renderGatewayProfile(selectedHost);
                showHostIntegrations();
            }
        }, this::showDiscordPanel, this::showVibepolloPanel,
                this::showVirtualHerePanel, gatewayProfileRefreshController::cancel);
        if (connection != null) {
            refreshHostIntegrations(hostUuid, connection);
            HostGatewayClient.Connection clientConnection =
                    hostGatewayStore.loadClientConnection(hostUuid);
            if (clientConnection != null) {
                refreshIntegrationCapabilities(hostUuid, clientConnection);
            }
        }
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
                hostGatewayStore.loadClientConnection(selectedHost.uuid);
    }

    private void showDiscordPanel() {
        HostGatewayClient.Connection connection = selectedGatewayConnection();
        if (connection == null) {
            showHostIntegrations();
            return;
        }
        modalController.showActionPanel(getCurrentFocus(), "HOST INTEGRATIONS", "Discord",
                "Loading Discord Bridge, voice and saved channels...",
                Collections.emptyList(), this::showHostIntegrations, null);
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.DiscordStatus status =
                        hostGatewayClient.getDiscordStatus(connection);
                HostGatewayClient.DiscordHome home = status.authenticated ?
                        hostGatewayClient.getDiscordHome(connection, false) :
                        new HostGatewayClient.DiscordHome(Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList());
                HostGatewayClient.DiscordVoice voice = status.authenticated ?
                        hostGatewayClient.getDiscordVoice(connection, false) : null;
                mainHandler.post(() -> renderDiscordPanel(connection, status, home, voice));
            } catch (Exception error) {
                mainHandler.post(() -> showIntegrationError("Discord", error,
                        this::showDiscordPanel));
            }
        });
    }

    private void renderDiscordPanel(HostGatewayClient.Connection connection,
                                    HostGatewayClient.DiscordStatus status,
                                    HostGatewayClient.DiscordHome home,
                                    HostGatewayClient.DiscordVoice voice) {
        List<ConsoleModalController.PanelAction> actions = new ArrayList<>();
        if (!status.bridgeOnline) {
            actions.add(new ConsoleModalController.PanelAction("START DISCORD",
                    () -> runGatewayOperation("Starting Discord...",
                            () -> hostGatewayClient.startDiscord(connection),
                            this::showDiscordPanel)));
        }
        if (status.bridgeOnline && !status.authenticated) {
            actions.add(new ConsoleModalController.PanelAction("CONNECT DISCORD RPC",
                    () -> runGatewayOperation("Connecting Discord RPC...",
                            () -> hostGatewayClient.connectDiscord(connection, false),
                            this::showDiscordPanel)));
        }
        if (voice != null && voice.connected) {
            actions.add(new ConsoleModalController.PanelAction(
                    voice.muted ? "UNMUTE MICROPHONE" : "MUTE MICROPHONE",
                    () -> runGatewayOperation("Updating microphone...",
                            () -> hostGatewayClient.setDiscordVoiceFlag(
                                    connection, "mute", "toggle"), this::showDiscordPanel)));
            actions.add(new ConsoleModalController.PanelAction(
                    voice.deafened ? "UNDEAFEN" : "DEAFEN",
                    () -> runGatewayOperation("Updating deafen state...",
                            () -> hostGatewayClient.setDiscordVoiceFlag(
                                    connection, "deafen", "toggle"), this::showDiscordPanel)));
            actions.add(new ConsoleModalController.PanelAction("LEAVE VOICE",
                    () -> runGatewayOperation("Leaving Discord voice...",
                            () -> hostGatewayClient.leaveDiscordChannel(connection),
                            this::showDiscordPanel)));
        }
        HashSet<String> renderedChannels = new HashSet<>();
        for (HostGatewayClient.DiscordChannel channel : home.favorites) {
            addDiscordChannelAction(actions, renderedChannels, connection, channel, "★  ");
        }
        for (HostGatewayClient.DiscordChannel channel : home.recent) {
            addDiscordChannelAction(actions, renderedChannels, connection, channel, "RECENT  ·  ");
        }
        for (HostGatewayClient.DiscordGuild guild : home.guilds) {
            actions.add(new ConsoleModalController.PanelAction(
                    "SERVER  ·  " + guild.name + "  ›",
                    () -> showDiscordGuild(connection, guild)));
        }
        if (status.authenticated) {
            actions.add(new ConsoleModalController.PanelAction("AUDIO DEVICES  ›",
                    () -> showDiscordAudio(connection)));
        }
        actions.add(new ConsoleModalController.PanelAction("REFRESH",
                this::showDiscordPanel));
        String description;
        if (!status.bridgeOnline) description = "Discord Bridge is offline";
        else if (!status.rpcConnected) description = "Discord Bridge online · RPC disconnected";
        else if (!status.authenticated) description = "Discord RPC requires authorization";
        else if (voice != null && voice.connected) {
            description = "ONLINE · #" + voice.channelName + " · " +
                    voice.participants + " participant(s)";
        } else description = "ONLINE · Not connected to voice";
        modalController.showActionPanel(getCurrentFocus(), "HOST INTEGRATIONS", "Discord",
                description, actions, this::showHostIntegrations, null);
    }

    private void addDiscordChannelAction(List<ConsoleModalController.PanelAction> actions,
                                         HashSet<String> rendered,
                                         HostGatewayClient.Connection connection,
                                         HostGatewayClient.DiscordChannel channel,
                                         String prefix) {
        if (!rendered.add(channel.id)) return;
        actions.add(new ConsoleModalController.PanelAction(
                prefix + channel.guildName + " · #" + channel.name,
                () -> runGatewayOperation("Joining #" + channel.name + "...",
                        () -> hostGatewayClient.joinDiscordChannel(connection, channel),
                        this::showDiscordPanel)));
    }

    private void showDiscordGuild(HostGatewayClient.Connection connection,
                                  HostGatewayClient.DiscordGuild guild) {
        modalController.showActionPanel(getCurrentFocus(), "DISCORD SERVER", guild.name,
                "Loading voice channels...", Collections.emptyList(),
                this::showDiscordPanel, null);
        integrationExecutor.execute(() -> {
            try {
                List<HostGatewayClient.DiscordChannel> channels =
                        hostGatewayClient.getDiscordChannels(connection, guild, false);
                mainHandler.post(() -> {
                    List<ConsoleModalController.PanelAction> actions = new ArrayList<>();
                    for (HostGatewayClient.DiscordChannel channel : channels) {
                        actions.add(new ConsoleModalController.PanelAction(
                                "# " + channel.name + " · " + channel.people + " people",
                                () -> runGatewayOperation("Joining #" + channel.name + "...",
                                        () -> hostGatewayClient.joinDiscordChannel(
                                                connection, channel), this::showDiscordPanel)));
                    }
                    modalController.showActionPanel(getCurrentFocus(), "DISCORD SERVER",
                            guild.name, channels.isEmpty() ? "No voice channels available" :
                                    "Choose a voice channel", actions,
                            this::showDiscordPanel, null);
                });
            } catch (Exception error) {
                mainHandler.post(() -> showIntegrationError(guild.name, error,
                        () -> showDiscordGuild(connection, guild)));
            }
        });
    }

    private void showDiscordAudio(HostGatewayClient.Connection connection) {
        modalController.showActionPanel(getCurrentFocus(), "DISCORD", "Audio Devices",
                "Loading Windows and Discord audio devices...", Collections.emptyList(),
                this::showDiscordPanel, null);
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.DiscordAudioState audio =
                        hostGatewayClient.getDiscordAudioState(connection);
                mainHandler.post(() -> renderDiscordAudio(connection, audio));
            } catch (Exception error) {
                mainHandler.post(() -> showIntegrationError("Audio Devices", error,
                        () -> showDiscordAudio(connection)));
            }
        });
    }

    private void renderDiscordAudio(HostGatewayClient.Connection connection,
                                    HostGatewayClient.DiscordAudioState audio) {
        List<ConsoleModalController.PanelAction> actions = new ArrayList<>();
        if (audio.systemAvailable) {
            actions.add(new ConsoleModalController.PanelAction("SYSTEM VOLUME  -5",
                    () -> runGatewayOperation("Lowering volume...",
                            () -> hostGatewayClient.changeSystemVolume(connection, -5),
                            () -> showDiscordAudio(connection))));
            actions.add(new ConsoleModalController.PanelAction("SYSTEM VOLUME  +5",
                    () -> runGatewayOperation("Raising volume...",
                            () -> hostGatewayClient.changeSystemVolume(connection, 5),
                            () -> showDiscordAudio(connection))));
            actions.add(new ConsoleModalController.PanelAction(
                    audio.systemMuted ? "UNMUTE SYSTEM" : "MUTE SYSTEM",
                    () -> runGatewayOperation("Updating system mute...",
                            () -> hostGatewayClient.toggleSystemMute(connection),
                            () -> showDiscordAudio(connection))));
        }
        for (HostGatewayClient.AudioDevice device : audio.systemDevices) {
            actions.add(audioDeviceAction(connection, device));
        }
        for (HostGatewayClient.AudioDevice device : audio.discordDevices) {
            actions.add(audioDeviceAction(connection, device));
        }
        modalController.showActionPanel(getCurrentFocus(), "DISCORD", "Audio Devices",
                "Windows volume " + audio.systemVolume + "%" +
                        (audio.systemMuted ? " · MUTED" : ""), actions,
                this::showDiscordPanel, null);
    }

    private ConsoleModalController.PanelAction audioDeviceAction(
            HostGatewayClient.Connection connection, HostGatewayClient.AudioDevice device) {
        return new ConsoleModalController.PanelAction(
                (device.current ? "✓  " : "") +
                        (device.system ? "SYSTEM " : "DISCORD ") +
                        device.flow.toUpperCase(Locale.ROOT) + " · " + device.name,
                () -> runGatewayOperation("Selecting " + device.name + "...",
                        () -> hostGatewayClient.selectAudioDevice(connection, device),
                        () -> showDiscordAudio(connection)));
    }

    private void showVibepolloPanel() {
        HostGatewayClient.Connection connection = selectedGatewayConnection();
        if (connection == null) { showHostIntegrations(); return; }
        modalController.showActionPanel(getCurrentFocus(), "HOST INTEGRATIONS",
                "Vibepollo FIX", "Loading Vibepollo status...", Collections.emptyList(),
                this::showHostIntegrations, null);
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.RepairStatus status =
                        hostGatewayClient.getVibepolloRepairStatus(connection);
                mainHandler.post(() -> {
                    List<ConsoleModalController.PanelAction> actions = new ArrayList<>();
                    actions.add(new ConsoleModalController.PanelAction("RESTART VIBEPOLLO",
                            () -> runGatewayOperation("Restarting Vibepollo...",
                                    () -> hostGatewayClient.runVibepolloRepair(
                                            connection, "restart"), this::showVibepolloPanel)));
                    actions.add(new ConsoleModalController.PanelAction("RESET REMEMBERED DISPLAY",
                            () -> runGatewayOperation("Resetting remembered display...",
                                    () -> hostGatewayClient.runVibepolloRepair(
                                            connection, "reset-display"), this::showVibepolloPanel)));
                    actions.add(new ConsoleModalController.PanelAction("EXPORT LOGS",
                            () -> runGatewayOperation("Exporting Vibepollo logs...",
                                    () -> hostGatewayClient.runVibepolloRepair(
                                            connection, "export-logs"), this::showVibepolloPanel)));
                    actions.add(new ConsoleModalController.PanelAction("REFRESH",
                            this::showVibepolloPanel));
                    modalController.showActionPanel(getCurrentFocus(), "HOST INTEGRATIONS",
                            "Vibepollo FIX", status.online ?
                                    "Vibepollo online · " + status.version :
                                    "Vibepollo API unavailable" +
                                            (status.error.isEmpty() ? "" : " · " + status.error),
                            actions, this::showHostIntegrations, null);
                });
            } catch (Exception error) {
                mainHandler.post(() -> showIntegrationError("Vibepollo FIX", error,
                        this::showVibepolloPanel));
            }
        });
    }

    private void showVirtualHerePanel() {
        HostGatewayClient.Connection connection = selectedGatewayConnection();
        if (connection == null) { showHostIntegrations(); return; }
        modalController.showActionPanel(getCurrentFocus(), "HOST INTEGRATIONS",
                "VirtualHere USB", "Loading USB devices...", Collections.emptyList(),
                this::showHostIntegrations, null);
        integrationExecutor.execute(() -> {
            try {
                HostGatewayClient.VirtualHereState state =
                        hostGatewayClient.getVirtualHereState(connection, false);
                mainHandler.post(() -> renderVirtualHere(connection, state));
            } catch (Exception error) {
                mainHandler.post(() -> showIntegrationError("VirtualHere USB", error,
                        this::showVirtualHerePanel));
            }
        });
    }

    private void renderVirtualHere(HostGatewayClient.Connection connection,
                                   HostGatewayClient.VirtualHereState state) {
        List<ConsoleModalController.PanelAction> actions = new ArrayList<>();
        for (HostGatewayClient.VirtualHereServer server : state.servers) {
            for (HostGatewayClient.VirtualHereDevice device : server.devices) {
                String stateLabel = device.inUseByMe ? "CONNECTED" :
                        device.inUse ? "IN USE" : device.available ? "AVAILABLE" : "OFFLINE";
                String action = device.inUseByMe ? "stop" : "use";
                actions.add(new ConsoleModalController.PanelAction(
                        server.name + " · " + device.name + " · " + stateLabel,
                        device.inUseByMe || (device.available && !device.inUse),
                        () -> runGatewayOperation("Updating " + device.name + "...",
                                () -> hostGatewayClient.runVirtualHereAction(
                                        connection, action, device.address),
                                this::showVirtualHerePanel)));
                actions.add(new ConsoleModalController.PanelAction(
                        (device.autoUse ? "DISABLE" : "ENABLE") +
                                " AUTO-USE · " + device.name,
                        () -> runGatewayOperation("Updating auto-use...",
                                () -> hostGatewayClient.runVirtualHereAction(
                                        connection, "auto", device.address),
                                this::showVirtualHerePanel)));
            }
        }
        actions.add(new ConsoleModalController.PanelAction("RESTART VIRTUALHERE CLIENT",
                () -> runGatewayOperation("Restarting VirtualHere...",
                        () -> hostGatewayClient.runVirtualHereAction(
                                connection, "restart", null), this::showVirtualHerePanel)));
        actions.add(new ConsoleModalController.PanelAction("REFRESH",
                this::showVirtualHerePanel));
        String description = !state.installed ? "VirtualHere client is not installed" :
                state.running ? "VirtualHere client running" : "VirtualHere client stopped";
        if (!state.error.isEmpty()) description += " · " + state.error;
        modalController.showActionPanel(getCurrentFocus(), "HOST INTEGRATIONS",
                "VirtualHere USB", description, actions, this::showHostIntegrations, null);
    }

    private void showIntegrationError(String title, Throwable error, Runnable retry) {
        List<ConsoleModalController.PanelAction> actions = new ArrayList<>();
        actions.add(new ConsoleModalController.PanelAction("RETRY", retry));
        modalController.showActionPanel(getCurrentFocus(), "HOST INTEGRATIONS", title,
                friendlyGatewayError(error), actions, this::showHostIntegrations, null);
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

    private TextView section(String value) { return label(value, 12, 0xFFB99CFF, true); }
    private TextView label(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.DEFAULT, bold ? 1 : 0);
        return view;
    }
    private HorizontalScrollView horizontalScroll() {
        HorizontalScrollView view = new HorizontalScrollView(this);
        view.setHorizontalScrollBarEnabled(false);
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
