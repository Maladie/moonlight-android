package com.limelight.console;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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
import com.limelight.PublicReturnStreamTrampoline;

import java.util.List;
import java.util.Locale;

/** Milestone-1 Android TV console shell with a persistent stream surface layer. */
public final class ConsoleActivity extends Activity implements SurfaceHolder.Callback {
    private final ConsoleStateMachine stateMachine = new ConsoleStateMachine();
    private final InputRouter inputRouter = new InputRouter(InputRouter.Region.HOME);
    private final StreamSurfaceHost streamSurfaceHost = new StreamSurfaceHost();

    private ConsoleDataRepository repository;
    private HostGatewayStore hostGatewayStore;
    private ConsoleSelectionStore selectionStore;
    private ConsoleHostSelectionController hostSelectionController;
    private ConsoleArtworkController artworkController;
    private ConsoleTheme consoleTheme;
    private ConsoleModalController modalController;
    private FrameLayout root;
    private SurfaceView streamSurface;
    private View privacyLayer;
    private FrameLayout homeLayer;
    private ImageView artworkBackdrop;
    private ImageView artworkHero;
    private TextView sessionStatus;
    private TextView integrationStatus;
    private TextView returnToGame;
    private LinearLayout hostRow;
    private LinearLayout appRow;
    private View overlayLayer;
    private View modalLayer;
    private ConsoleDataRepository.Host selectedHost;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        applyTvWindow();
        repository = new ConsoleDataRepository(this);
        hostGatewayStore = new HostGatewayStore(this);
        selectionStore = new ConsoleSelectionStore(this);
        hostSelectionController = new ConsoleHostSelectionController(repository, selectionStore);
        consoleTheme = new ConsoleTheme(this);
        setContentView(buildRoot());
        artworkController = new ConsoleArtworkController(this, artworkBackdrop, artworkHero);
        modalController = new ConsoleModalController(
                this, (FrameLayout) modalLayer, inputRouter, consoleTheme);
        renderSnapshot();
    }

    @Override protected void onResume() {
        super.onResume();
        ActiveStreamSurfaceBridge.setConsoleForeground(true);
        if (repository != null && sessionStatus != null) {
            ConsoleDataRepository.Session session = repository.session();
            renderSession(session);
            if (session != null && session.alive) {
                for (ConsoleStateMachine.Event event : ConsoleResumePolicy.eventsFor(
                        stateMachine.getState(), true)) {
                    stateMachine.dispatch(event);
                }
                applyState(stateMachine.getState());
            }
        }
    }

    @Override protected void onPause() {
        ActiveStreamSurfaceBridge.setConsoleForeground(false);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Some Android TV dream overlays do not deliver a matching onResume()
        // when the existing Activity window becomes interactive again.
        ActiveStreamSurfaceBridge.setConsoleForeground(hasFocus && !isFinishing());
    }

    @Override protected void onDestroy() {
        ActiveStreamSurfaceBridge.setConsoleForeground(false);
        if (streamSurface != null) {
            ActiveStreamSurfaceBridge.releaseConsoleSurface(streamSurface.getHolder());
            streamSurface.getHolder().removeCallback(this);
        }
        if (artworkController != null) artworkController.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (modalController != null && modalController.dismissIfVisible()) return;
        ConsoleStateMachine.Transition transition = stateMachine.dispatch(ConsoleStateMachine.Event.BACK);
        if (transition.effect == ConsoleStateMachine.Effect.SHOW_EXIT_CONFIRMATION) {
            showExitConfirmation();
        } else {
            applyState(transition.current);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 &&
                event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
            onBackPressed();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private View buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // This surface stays attached and VISIBLE. Console/privacy layers cover it.
        streamSurface = new SurfaceView(this);
        streamSurface.setBackgroundColor(Color.BLACK);
        streamSurface.getHolder().addCallback(this);
        root.addView(streamSurface, match());

        privacyLayer = new View(this);
        privacyLayer.setBackgroundColor(Color.BLACK);
        privacyLayer.setVisibility(View.GONE);
        root.addView(privacyLayer, match());

        homeLayer = buildHome();
        root.addView(homeLayer, match());

        TextView overlay = label("STREAM OVERLAY\nDPAD regions: controls  ↔  Discord", 18, Color.WHITE, true);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xE6101118);
        overlay.setVisibility(View.GONE);
        overlay.setFocusable(true);
        overlayLayer = overlay;
        root.addView(overlayLayer, match());

        modalLayer = new FrameLayout(this);
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
        home.setBackgroundColor(0xFF090D18);

        artworkBackdrop = new ImageView(this);
        artworkBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkBackdrop.setAlpha(0f);
        home.addView(artworkBackdrop, match());

        artworkHero = new ImageView(this);
        artworkHero.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artworkHero.setAlpha(0f);
        FrameLayout.LayoutParams hero = new FrameLayout.LayoutParams(dp(520), matchHeight(), Gravity.RIGHT);
        hero.rightMargin = dp(40);
        home.addView(artworkHero, hero);

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF090D18, 0xF2090D18, 0x55090D18}));
        home.addView(scrim, match());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(54), dp(38), dp(54), dp(30));

        TextView title = label("MOONWAKER", 34, Color.WHITE, true);
        content.addView(title, wrap());
        TextView subtitle = label("GAME APP  ·  ONE CONSOLE", 12, 0xFFB99CFF, true);
        content.addView(subtitle, wrap());

        sessionStatus = label("SESSION · LOADING", 13, 0xFF9CA6C5, true);
        LinearLayout.LayoutParams sessionParams = wrap();
        sessionParams.topMargin = dp(10);
        content.addView(sessionStatus, sessionParams);

        integrationStatus = label("HOST INTEGRATIONS · SELECT A HOST", 12, 0xFF9CA6C5, true);
        content.addView(integrationStatus, top(dp(6)));

        TextView integrations = card("HOST INTEGRATIONS  ›", dp(280), dp(48));
        integrations.setOnClickListener(view -> showHostIntegrations());
        content.addView(integrations, top(dp(10)));

        returnToGame = card("▶  RETURN TO GAME", dp(280), dp(54));
        returnToGame.setVisibility(View.GONE);
        returnToGame.setOnClickListener(view -> returnToActiveStream());
        content.addView(returnToGame, top(dp(12)));

        content.addView(section("STREAMING HOSTS"), top(dp(22)));
        HorizontalScrollView hostScroll = horizontalScroll();
        hostRow = horizontalRow();
        hostScroll.addView(hostRow);
        content.addView(hostScroll, new LinearLayout.LayoutParams(matchWidth(), dp(92)));

        content.addView(section("CACHED APPLICATIONS"), top(dp(18)));
        HorizontalScrollView appScroll = horizontalScroll();
        appRow = horizontalRow();
        appScroll.addView(appRow);
        content.addView(appScroll, new LinearLayout.LayoutParams(matchWidth(), dp(180)));

        TextView hint = label("DPAD to browse  ·  A to launch via the protected legacy stream path  ·  Back to exit", 12,
                0xFF9CA6C5, false);
        content.addView(hint, top(dp(20)));
        home.addView(content, new FrameLayout.LayoutParams(matchWidth(), matchHeight()));
        return home;
    }

    private void renderSnapshot() {
        ConsoleHomeSnapshot snapshot = ConsoleHomeSnapshot.load(
                repository, hostGatewayStore, selectionStore);
        renderSession(snapshot.session);
        hostRow.removeAllViews();
        if (snapshot.hosts.isEmpty()) {
            selectedHost = null;
            hostRow.addView(label("No saved Moonlight hosts", 16, 0xFFFFB74D, false), cardParams());
            renderApps(null, snapshot.apps);
            renderGatewayProfile(null, snapshot.integrations);
            return;
        }
        for (ConsoleDataRepository.Host host : snapshot.hosts) {
            TextView card = card(host.name + "\n" + safe(host.address), dp(250), dp(78));
            card.setOnClickListener(view -> selectHost(host, view.hasFocus()));
            hostRow.addView(card, cardParams());
        }
        selectedHost = snapshot.selectedHost;
        renderApps(selectedHost, snapshot.apps);
        renderGatewayProfile(selectedHost, snapshot.integrations);
        // One deterministic initial focus; subsequent refreshes never request focus.
        hostRow.getChildAt(snapshot.selectedHostIndex).requestFocus();
    }

    private void selectHost(ConsoleDataRepository.Host host, boolean userFocusedHost) {
        ConsoleHostSelectionController.Selection selection =
                hostSelectionController.select(host);
        selectedHost = selection.host;
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
        card.setMinimumWidth(dp(290));
        card.setTag(app.id);

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setBackgroundColor(0xFF251C3F);
        Bitmap cached = artworkController.decodePoster(app.posterUri, 320);
        if (cached != null) poster.setImageBitmap(cached);
        card.addView(poster, new LinearLayout.LayoutParams(dp(92), dp(138)));

        TextView name = label(app.name + "\n\nPLAY  ›", 16, Color.WHITE, true);
        LinearLayout.LayoutParams copy = new LinearLayout.LayoutParams(dp(180), matchHeight());
        copy.leftMargin = dp(14);
        card.addView(name, copy);
        card.setOnFocusChangeListener((view, focused) -> {
            consoleTheme.onCardFocus(view, focused);
            if (focused) {
                hostSelectionController.rememberApp(host, app);
                artworkController.show(app.posterUri, poster.getDrawable());
            }
        });
        card.setOnClickListener(view -> launchLegacy(host, app));
        return card;
    }

    private void launchLegacy(ConsoleDataRepository.Host host, ConsoleDataRepository.App app) {
        stateMachine.dispatch(ConsoleStateMachine.Event.LAUNCH);
        applyState(ConsoleStateMachine.State.CONNECTING);
        ConsoleLaunchContract.Request request =
                ConsoleLaunchContract.create(host, app, getPackageName());
        Intent intent = ConsoleLaunchContract.legacyIntent(this, request, hostGatewayStore);
        startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        overridePendingTransition(0, 0);
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
        ConsoleSessionSummary summary = ConsoleSessionSummary.from(session);
        sessionStatus.setText(summary.label);
        sessionStatus.setTextColor(summary.alive ? 0xFF69F0AE : 0xFF9CA6C5);
        returnToGame.setVisibility(summary.alive ? View.VISIBLE : View.GONE);
    }

    private void returnToActiveStream() {
        ConsoleStateMachine.Transition transition =
                stateMachine.dispatch(ConsoleStateMachine.Event.RETURN_TO_STREAM);
        applyState(transition.current);
        Intent intent = new Intent(this, PublicReturnStreamTrampoline.class)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        overridePendingTransition(0, 0);
    }

    private void applyState(ConsoleStateMachine.State state) {
        boolean home = state == ConsoleStateMachine.State.HOME ||
                state == ConsoleStateMachine.State.CONSOLE_OVER_STREAM;
        homeLayer.setVisibility(home ? View.VISIBLE : View.GONE);
        privacyLayer.setVisibility(state == ConsoleStateMachine.State.CONNECTING ||
                state == ConsoleStateMachine.State.DISCONNECTING ? View.VISIBLE : View.GONE);
        overlayLayer.setVisibility(state == ConsoleStateMachine.State.OVERLAY ? View.VISIBLE : View.GONE);
        if (state == ConsoleStateMachine.State.STREAM) inputRouter.routeTo(InputRouter.Region.GAMEPLAY);
        else if (state == ConsoleStateMachine.State.OVERLAY) inputRouter.routeTo(InputRouter.Region.OVERLAY);
        else if (state == ConsoleStateMachine.State.RECOVERY ||
                state == ConsoleStateMachine.State.DISCONNECTING) inputRouter.routeTo(InputRouter.Region.MODAL);
        else inputRouter.routeTo(InputRouter.Region.HOME);
        // streamSurface intentionally remains VISIBLE and attached.
    }

    private void showExitConfirmation() {
        modalController.showExitConfirmation(getCurrentFocus(), this::finish);
    }

    private void showHostIntegrations() {
        if (selectedHost == null) {
            Toast.makeText(this, "Choose a streaming host first.", Toast.LENGTH_SHORT).show();
            return;
        }

        GatewayConnection connection = hostGatewayStore.load(selectedHost.uuid);
        HostIntegrationSummary summary = HostIntegrationSummary.from(connection);
        String hostUuid = selectedHost.uuid;
        modalController.showHostIntegrations(getCurrentFocus(), selectedHost.name, summary, () -> {
            hostGatewayStore.setSelectedIntegrationProfileId(
                    hostUuid, GatewayConnection.DEFAULT_PROFILE_ID);
            if (selectedHost != null && hostUuid.equals(selectedHost.uuid)) {
                renderGatewayProfile(selectedHost);
                showHostIntegrations();
            }
        });
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
    private static String safe(String value) { return value == null || value.isEmpty() ? "Address unavailable" : value; }
}
