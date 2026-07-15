package com.limelight.console;

import android.app.Activity;
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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.StreamView;

import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Milestone-1 Android TV console shell with a persistent stream surface layer. */
public final class ConsoleActivity extends Activity implements SurfaceHolder.Callback {
    private static final long SESSION_REFRESH_MS = 1500L;
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
    private ConsoleOverlayController overlayController;
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
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 &&
                (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B ||
                        event.getKeyCode() == KeyEvent.KEYCODE_BACK)) {
            onBackPressed();
            return true;
        }
        if (inputRouter.isGameplayCaptured() && unifiedSessionInput != null &&
                unifiedSessionInput.handleKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
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
        streamSurface.setBackgroundColor(Color.BLACK);
        streamSurface.setZOrderOnTop(false);
        streamSurface.setZOrderMediaOverlay(false);
        streamSurface.setElevation(0f);
        streamSurface.getHolder().addCallback(this);
        root.addView(streamSurface, match());

        loadingController = new ConsoleLoadingController(this);
        privacyLayer = loadingController.build();
        privacyLayer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        privacyLayer.setElevation(dp(24));
        privacyLayer.setVisibility(View.GONE);
        root.addView(privacyLayer, match());

        homeLayer = buildHome();
        homeLayer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        homeLayer.setElevation(dp(32));
        root.addView(homeLayer, match());

        overlayController = new ConsoleOverlayController(this, consoleTheme);
        overlayLayer = overlayController.build(
                this::returnToActiveStream,
                this::openConsoleHome,
                this::showHostIntegrations);
        overlayLayer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        overlayLayer.setElevation(dp(40));
        root.addView(overlayLayer, match());

        modalLayer = new FrameLayout(this);
        modalLayer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        modalLayer.setElevation(dp(48));
        modalLayer.setBackgroundColor(0xD9000000);
        modalLayer.setVisibility(View.GONE);
        root.addView(modalLayer, match());
        return root;
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
        options.setContentDescription("Open Moonlight streaming options");
        options.setOnClickListener(view -> startActivity(new Intent(this, StreamSettings.class)));
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

    private void renderGatewayProfile(ConsoleDataRepository.Host host) {
        GatewayConnection connection = host == null ? null : hostGatewayStore.load(host.uuid);
        renderGatewayProfile(host, HostIntegrationSummary.from(connection));
    }

    private void renderGatewayProfile(ConsoleDataRepository.Host host,
                                      HostIntegrationSummary summary) {
        if (integrationStatus == null) return;
        if (overlayController != null) overlayController.render(host == null ? null : summary);
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
        overlayLayer.setVisibility(layers.overlayVisible ? View.VISIBLE : View.GONE);
        inputRouter.routeTo(layers.inputRegion);
        updateUnifiedInputSensors();
        if ((state == ConsoleStateMachine.State.HOME ||
                state == ConsoleStateMachine.State.CONSOLE_OVER_STREAM) &&
                streamRuntime instanceof UnifiedConsoleRuntimeBootstrap) {
            ((UnifiedConsoleRuntimeBootstrap) streamRuntime).showHome();
        }
        if (openingOverlay && overlayLayer.getTag() instanceof View) {
            ((View) overlayLayer.getTag()).requestFocus();
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
                selectedHost.name, summary, () -> {
            hostGatewayStore.setSelectedIntegrationProfileId(
                    hostUuid, GatewayConnection.DEFAULT_PROFILE_ID);
            if (selectedHost != null && hostUuid.equals(selectedHost.uuid)) {
                renderGatewayProfile(selectedHost);
                showHostIntegrations();
            }
        }, gatewayProfileRefreshController::cancel);
        if (connection != null) refreshHostIntegrations(hostUuid, connection);
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
