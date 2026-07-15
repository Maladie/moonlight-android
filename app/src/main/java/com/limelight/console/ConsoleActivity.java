package com.limelight.console;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
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

import com.limelight.Game;
import com.limelight.PublicStreamIntent;
import com.limelight.PublicReturnStreamTrampoline;
import com.limelight.ShortcutTrampoline;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Milestone-1 Android TV console shell with a persistent stream surface layer. */
public final class ConsoleActivity extends Activity {
    private final ConsoleStateMachine stateMachine = new ConsoleStateMachine();
    private final InputRouter inputRouter = new InputRouter(InputRouter.Region.HOME);
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger artworkRequest = new AtomicInteger();

    private ConsoleDataRepository repository;
    private FrameLayout root;
    private SurfaceView streamSurface;
    private View privacyLayer;
    private FrameLayout homeLayer;
    private ImageView artworkBackdrop;
    private ImageView artworkHero;
    private TextView sessionStatus;
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
        setContentView(buildRoot());
        renderSnapshot();
    }

    @Override protected void onResume() {
        super.onResume();
        if (repository != null && sessionStatus != null) {
            ConsoleDataRepository.Session session = repository.session();
            renderSession(session);
            if (session != null && session.alive) {
                ConsoleStateMachine.State state = stateMachine.getState();
                if (state == ConsoleStateMachine.State.CONNECTING) {
                    stateMachine.dispatch(ConsoleStateMachine.Event.CONNECTED);
                    stateMachine.dispatch(ConsoleStateMachine.Event.OPEN_CONSOLE);
                } else if (state == ConsoleStateMachine.State.STREAM) {
                    stateMachine.dispatch(ConsoleStateMachine.Event.OPEN_CONSOLE);
                } else if (state == ConsoleStateMachine.State.HOME) {
                    stateMachine.dispatch(ConsoleStateMachine.Event.CONNECTED);
                }
                applyState(stateMachine.getState());
            }
        }
    }

    @Override protected void onDestroy() {
        artworkRequest.incrementAndGet();
        artworkExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
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
        renderSession(repository.session());
        List<ConsoleDataRepository.Host> hosts = repository.hosts();
        hostRow.removeAllViews();
        if (hosts.isEmpty()) {
            hostRow.addView(label("No saved Moonlight hosts", 16, 0xFFFFB74D, false), cardParams());
            renderApps(null);
            return;
        }
        for (ConsoleDataRepository.Host host : hosts) {
            TextView card = card(host.name + "\n" + safe(host.address), dp(250), dp(78));
            card.setOnClickListener(view -> selectHost(host, view.hasFocus()));
            hostRow.addView(card, cardParams());
        }
        selectedHost = hosts.get(0);
        renderApps(selectedHost);
        // One deterministic initial focus; subsequent refreshes never request focus.
        hostRow.getChildAt(0).requestFocus();
    }

    private void selectHost(ConsoleDataRepository.Host host, boolean userFocusedHost) {
        selectedHost = host;
        renderApps(host);
        if (userFocusedHost && appRow.getChildCount() > 0) appRow.getChildAt(0).requestFocus();
    }

    private void renderApps(ConsoleDataRepository.Host host) {
        appRow.removeAllViews();
        List<ConsoleDataRepository.App> apps = repository.apps(host);
        if (apps.isEmpty()) {
            appRow.addView(label("No cached applications. Refresh this host in Moonlight.",
                    16, 0xFFFFB74D, false), cardParams());
            clearArtwork();
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
        card.setBackground(cardBackground(false));
        card.setMinimumWidth(dp(290));

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setBackgroundColor(0xFF251C3F);
        Bitmap cached = decode(app.posterUri, 320);
        if (cached != null) poster.setImageBitmap(cached);
        card.addView(poster, new LinearLayout.LayoutParams(dp(92), dp(138)));

        TextView name = label(app.name + "\n\nPLAY  ›", 16, Color.WHITE, true);
        LinearLayout.LayoutParams copy = new LinearLayout.LayoutParams(dp(180), matchHeight());
        copy.leftMargin = dp(14);
        card.addView(name, copy);
        card.setOnFocusChangeListener((view, focused) -> {
            view.setBackground(cardBackground(focused));
            if (focused) showArtwork(app.posterUri, poster.getDrawable());
        });
        card.setOnClickListener(view -> launchLegacy(host, app));
        return card;
    }

    private void launchLegacy(ConsoleDataRepository.Host host, ConsoleDataRepository.App app) {
        stateMachine.dispatch(ConsoleStateMachine.Event.LAUNCH);
        applyState(ConsoleStateMachine.State.CONNECTING);
        Intent intent = new Intent(this, ShortcutTrampoline.class)
                .setAction(PublicStreamIntent.ACTION_STREAM)
                .putExtra(PublicStreamIntent.EXTRA_HOST_UUID, host.uuid)
                .putExtra(PublicStreamIntent.EXTRA_APP_ID, String.valueOf(app.id))
                .putExtra(PublicStreamIntent.EXTRA_APP_NAME, app.name)
                .putExtra(Game.EXTRA_APP_ID, String.valueOf(app.id))
                .putExtra(Game.EXTRA_APP_NAME, app.name)
                .putExtra(PublicStreamIntent.EXTRA_EXTERNAL_FRONTEND, true)
                .putExtra(PublicStreamIntent.EXTRA_EXTERNAL_FRONTEND_PACKAGE, getPackageName())
                .putExtra(PublicStreamIntent.EXTRA_EXTERNAL_FRONTEND_MESSAGE,
                        "Preparing " + app.name + "…")
                .putExtra(PublicStreamIntent.EXTRA_EXTERNAL_FRONTEND_REDUCED_MOTION, false)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        overridePendingTransition(0, 0);
    }

    private void renderSession(ConsoleDataRepository.Session session) {
        if (session == null || session.state == null) {
            sessionStatus.setText("SESSION · STATUS UNAVAILABLE");
            returnToGame.setVisibility(View.GONE);
            return;
        }
        StringBuilder text = new StringBuilder("SESSION · ").append(session.state.toUpperCase(Locale.ROOT));
        if (session.app != null && !session.app.isEmpty()) text.append(" · ").append(session.app);
        if (session.width > 0) text.append(" · ").append(session.width).append('×').append(session.height)
                .append(" @ ").append(session.fps);
        sessionStatus.setText(text);
        sessionStatus.setTextColor(session.alive ? 0xFF69F0AE : 0xFF9CA6C5);
        returnToGame.setVisibility(session.alive ? View.VISIBLE : View.GONE);
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

    private void showArtwork(Uri uri, android.graphics.drawable.Drawable immediate) {
        int token = artworkRequest.incrementAndGet();
        if (immediate != null) {
            artworkHero.setImageDrawable(immediate);
            artworkHero.setAlpha(0.58f);
        }
        artworkExecutor.execute(() -> {
            Bitmap source = decode(uri, 900);
            if (source == null) return;
            Bitmap small = Bitmap.createScaledBitmap(source, 48, 72, true);
            Bitmap blurred = Bitmap.createScaledBitmap(small, 960, 1080, true);
            mainHandler.post(() -> {
                if (token != artworkRequest.get() || isFinishing() || isDestroyed()) return;
                artworkBackdrop.setImageBitmap(blurred);
                artworkBackdrop.animate().alpha(0.46f).setDuration(220).start();
            });
        });
    }

    private Bitmap decode(Uri uri, int maxDimension) {
        if (uri == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private void clearArtwork() {
        artworkRequest.incrementAndGet();
        artworkHero.setImageDrawable(null);
        artworkHero.setAlpha(0f);
        artworkBackdrop.setImageDrawable(null);
        artworkBackdrop.setAlpha(0f);
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
        FrameLayout modal = (FrameLayout) modalLayer;
        modal.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(36), dp(30), dp(36), dp(30));
        panel.setBackground(cardBackground(false));
        panel.addView(label("EXIT MOONWAKER?", 24, Color.WHITE, true), wrap());
        panel.addView(label("An active host application will not be stopped.", 15, 0xFFBDC4D8, false), top(dp(12)));
        TextView cancel = card("CANCEL", dp(260), dp(58));
        cancel.setOnClickListener(view -> modal.setVisibility(View.GONE));
        TextView exit = card("EXIT APP", dp(260), dp(58));
        exit.setOnClickListener(view -> finish());
        panel.addView(cancel, top(dp(22)));
        panel.addView(exit, top(dp(10)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(560), dp(360), Gravity.CENTER);
        modal.addView(panel, params);
        modal.setVisibility(View.VISIBLE);
        inputRouter.routeTo(InputRouter.Region.MODAL);
        cancel.requestFocus();
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
        view.setBackground(cardBackground(false));
        view.setOnFocusChangeListener((target, focused) -> target.setBackground(cardBackground(focused)));
        return view;
    }

    private GradientDrawable cardBackground(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? 0xFF5D3F92 : 0xE6212638);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(focused ? 3 : 1), focused ? 0xFFE1D4FF : 0xFF48506A);
        return drawable;
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
