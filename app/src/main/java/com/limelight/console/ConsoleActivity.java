package com.limelight.console;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.hardware.BatteryState;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.PcView;
import com.limelight.Game;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.ServerHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** TV-first dashboard adapted from Wake & Play and backed by Moonlight's internal APIs. */
public final class ConsoleActivity extends Activity implements InputManager.InputDeviceListener {
    private static final String PREFS = "console_dashboard";
    private static final int REQUEST_BLUETOOTH_CONNECT = 2201;
    private static final long CONTROLLER_REFRESH_MS = 30_000L;
    private static final String[] LOADING_MESSAGES = {
            "Loading content…", "Combobulating resources…", "Waking the gaming rig…",
            "Negotiating photons…", "Aligning virtual displays…",
            "Preparing controller uplink…", "Calibrating couch coordinates…",
            "Julification in progress…"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final AtomicInteger launchGeneration = new AtomicInteger();
    private final AtomicInteger artworkGeneration = new AtomicInteger();
    private final Map<String, ComputerDetails> hosts = new LinkedHashMap<>();

    private SharedPreferences preferences;
    private DiskAssetLoader assetLoader;
    private AudioManager audioManager;
    private InputManager inputManager;
    private DiscordPanelController discordPanelController;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private ComputerManagerService.ApplistPoller appListPoller;
    private boolean serviceBound;
    private boolean polling;
    private boolean active;
    private boolean inputListenerRegistered;
    private boolean reducedMotion;
    private boolean uiSoundsEnabled;

    private FrameLayout root;
    private FrameLayout homeLayer;
    private FrameLayout loadingLayer;
    private FrameLayout modalLayer;
    private LinearLayout sidePanel;
    private ScrollView sidePanelScroll;
    private ImageView artworkBackdrop;
    private ImageView artworkHero;
    private View artworkScrim;
    private ConsoleBackdrop loadingBackdrop;
    private TextView loadingMessage;
    private TextView loadingStatus;
    private TextView controllersLabel;
    private TextView hostsLabel;
    private TextView appsLabel;
    private LinearLayout controllerRow;
    private LinearLayout hostRow;
    private LinearLayout appRow;
    private HorizontalScrollView controllerScroll;
    private HorizontalScrollView hostScroll;
    private HorizontalScrollView appScroll;
    private String selectedHostUuid;
    private View selectedHostCard;
    private View lastContentFocus;
    private ControllerInfo pendingController;
    private BluetoothAction pendingBluetoothAction;
    private int glassAccent = 0xFF715BA8;

    private final ComputerManagerListener computerListener = (details, fresh) -> {
        ComputerDetails copy = new ComputerDetails(details);
        mainHandler.post(() -> {
            ComputerDetails previous = hosts.get(copy.uuid);
            boolean hostChanged = previous == null
                    || previous.state != copy.state
                    || !Objects.equals(previous.activeAddress, copy.activeAddress);
            boolean appsChanged = previous == null
                    || !Objects.equals(previous.rawAppList, copy.rawAppList);
            hosts.put(copy.uuid, copy);
            if (hostChanged) renderHosts();
            if (appsChanged && copy.uuid.equals(selectedHostUuid)) renderAppsAsync(copy);
        });
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            ComputerManagerService.ComputerManagerBinder binder =
                    (ComputerManagerService.ComputerManagerBinder) service;
            executor.execute(() -> {
                binder.waitForReady();
                mainHandler.post(() -> {
                    managerBinder = binder;
                    if (active) startPolling();
                });
            });
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            managerBinder = null;
            polling = false;
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        reducedMotion = preferences.getBoolean("reduced_motion", false);
        uiSoundsEnabled = preferences.getBoolean("ui_sounds", true);
        selectedHostUuid = preferences.getString("selected_host", null);
        assetLoader = new DiskAssetLoader(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        getWindow().setFormat(PixelFormat.OPAQUE);
        root = buildUi();
        setContentView(root);
        discordPanelController = new DiscordPanelController(this, mainHandler, executor,
                new DiscordPanelController.Ui() {
                    @Override public TextView action(String label) {
                        return panelAction(label);
                    }

                    @Override public TextView label(String label) {
                        TextView view = text(label, 13, 0xFFBDC4D8, false);
                        view.setPadding(dp(8), dp(7), dp(8), dp(7));
                        return view;
                    }

                    @Override public void show(String eyebrow, String title, String details,
                                               View... actions) {
                        showSidePanel(eyebrow, title, details, actions);
                    }

                    @Override public void toast(String message) {
                        Toast.makeText(ConsoleActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
        hideSystemUi();
        loadKnownHosts();
        serviceBound = bindService(new Intent(this, ComputerManagerService.class),
                serviceConnection, Service.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        if (inputManager != null && !inputListenerRegistered) {
            inputManager.registerInputDeviceListener(this, mainHandler);
            inputListenerRegistered = true;
        }
        startPolling();
        ComputerDetails selected = hosts.get(selectedHostUuid);
        if (selected != null && appListPoller == null) startAppListPoller(selected);
        refreshControllers();
        mainHandler.postDelayed(controllerRefresh, CONTROLLER_REFRESH_MS);
        if (loadingLayer != null && loadingLayer.getVisibility() == View.VISIBLE) showHome();
        if (homeLayer != null) homeLayer.setVisibility(View.VISIBLE);
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        active = false;
        launchGeneration.incrementAndGet();
        artworkGeneration.incrementAndGet();
        mainHandler.removeCallbacks(controllerRefresh);
        mainHandler.removeCallbacks(rotateLoadingMessage);
        if (loadingBackdrop != null) loadingBackdrop.stop();
        saveScrollPositions();
        stopAppListPoller();
        if (polling && managerBinder != null) {
            managerBinder.stopPolling();
            polling = false;
        }
        if (inputManager != null && inputListenerRegistered) {
            inputManager.unregisterInputDeviceListener(this);
            inputListenerRegistered = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (discordPanelController != null) discordPanelController.destroy();
        if (serviceBound) unbindService(serviceConnection);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (modalLayer != null && modalLayer.getVisibility() == View.VISIBLE) {
            hideSidePanel();
        } else if (loadingLayer != null && loadingLayer.getVisibility() == View.VISIBLE) {
            launchGeneration.incrementAndGet();
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    private void loadKnownHosts() {
        executor.execute(() -> {
            ComputerDatabaseManager database = new ComputerDatabaseManager(this);
            List<ComputerDetails> known = database.getAllComputers();
            database.close();
            mainHandler.post(() -> {
                for (ComputerDetails host : known) hosts.put(host.uuid, host);
                renderHosts();
            });
        });
    }

    private void startPolling() {
        if (!active || polling || managerBinder == null) return;
        polling = true;
        managerBinder.startPolling(computerListener);
        ComputerDetails selected = hosts.get(selectedHostUuid);
        if (selected != null) startAppListPoller(selected);
    }

    private FrameLayout buildUi() {
        FrameLayout container = new FrameLayout(this);
        homeLayer = new FrameLayout(this);
        homeLayer.addView(new ConsoleBackdrop(this), match());

        artworkBackdrop = new ImageView(this);
        artworkBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkBackdrop.setAlpha(0f);
        homeLayer.addView(artworkBackdrop, match());

        artworkHero = new ImageView(this);
        artworkHero.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artworkHero.setAlpha(0f);
        artworkHero.setPadding(dp(30), dp(56), dp(30), dp(56));
        FrameLayout.LayoutParams hero = new FrameLayout.LayoutParams(dp(500),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL);
        hero.rightMargin = dp(16);
        homeLayer.addView(artworkHero, hero);

        artworkScrim = new View(this);
        artworkScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF405060A, 0xD405060A, 0x5005060A}));
        artworkScrim.setAlpha(0f);
        homeLayer.addView(artworkScrim, match());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(60), dp(22), dp(60), dp(12));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        homeLayer.addView(content, match());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("MOONLIGHT", 30, Color.WHITE, true);
        TextView subtitle = text("Choose a host and application. Moonlight will wake the PC and start the stream.",
                14, 0xFFBCC3DD, false);
        titleBlock.addView(title, wrapLinear());
        titleBlock.addView(subtitle, wrapLinear());
        header.addView(titleBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView options = compactButton("OPTIONS  ›");
        options.setOnClickListener(v -> showOptionsPanel());
        header.addView(options, new LinearLayout.LayoutParams(dp(170), dp(44)));
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        controllersLabel = sectionLabel("CONTROLLERS");
        LinearLayout.LayoutParams section = wrapLinear();
        section.topMargin = dp(13);
        content.addView(controllersLabel, section);
        controllerScroll = horizontalScroll();
        controllerRow = horizontalRow();
        controllerScroll.addView(controllerRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(controllerScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        hostsLabel = sectionLabel("HOSTS");
        content.addView(hostsLabel, sectionWithTop(7));
        hostScroll = horizontalScroll();
        hostRow = horizontalRow();
        hostScroll.addView(hostRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(hostScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(92)));

        appsLabel = sectionLabel("APPLICATIONS");
        content.addView(appsLabel, sectionWithTop(7));
        appScroll = horizontalScroll();
        appRow = horizontalRow();
        appScroll.addView(appRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(appScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(126)));
        appRow.addView(text("Choose a host to see its applications.", 15, 0xFFBDC4D8, false),
                new LinearLayout.LayoutParams(dp(500), ViewGroup.LayoutParams.MATCH_PARENT));

        buildSidePanel();
        container.addView(homeLayer, match());
        buildLoadingLayer(container);
        return container;
    }

    private void buildLoadingLayer(FrameLayout container) {
        loadingLayer = new FrameLayout(this);
        loadingLayer.setVisibility(View.GONE);
        loadingBackdrop = new ConsoleBackdrop(this);
        loadingLayer.addView(loadingBackdrop, match());
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER);
        loadingMessage = text(LOADING_MESSAGES[0], 30, Color.WHITE, true);
        loadingStatus = text("", 15, 0xFFB8C0D9, false);
        loadingStatus.setGravity(Gravity.CENTER);
        copy.addView(loadingMessage, wrapLinear());
        LinearLayout.LayoutParams statusParams = wrapLinear();
        statusParams.topMargin = dp(12);
        copy.addView(loadingStatus, statusParams);
        loadingLayer.addView(copy, match());
        container.addView(loadingLayer, match());
    }

    private void buildSidePanel() {
        modalLayer = new FrameLayout(this);
        modalLayer.setVisibility(View.GONE);
        View dim = new View(this);
        dim.setBackgroundColor(0xA005060A);
        dim.setClickable(true);
        dim.setOnClickListener(v -> hideSidePanel());
        modalLayer.addView(dim, match());
        sidePanelScroll = new ScrollView(this);
        sidePanelScroll.setFillViewport(true);
        sidePanelScroll.setVerticalScrollBarEnabled(false);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xF51A1D2A, 0xF0221B38, 0xFA090B12});
        background.setCornerRadii(new float[]{dp(24), dp(24), 0, 0, 0, 0, dp(24), dp(24)});
        background.setStroke(dp(1), 0x707B6AA9);
        sidePanelScroll.setBackground(background);
        sidePanelScroll.setElevation(dp(18));
        sidePanel = new LinearLayout(this);
        sidePanel.setOrientation(LinearLayout.VERTICAL);
        sidePanel.setPadding(dp(34), dp(26), dp(34), dp(20));
        sidePanelScroll.addView(sidePanel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        modalLayer.addView(sidePanelScroll, new FrameLayout.LayoutParams(dp(510),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        homeLayer.addView(modalLayer, match());
    }

    private void renderHosts() {
        if (hostRow == null) return;
        int scroll = hostScroll.getScrollX();
        Object focusedTag = getCurrentFocus() != null ? getCurrentFocus().getTag() : null;
        hostRow.removeAllViews();
        selectedHostCard = null;
        List<ComputerDetails> sorted = new ArrayList<>(hosts.values());
        sorted.sort(Comparator.comparing(host -> host.name, String.CASE_INSENSITIVE_ORDER));
        hostsLabel.setText(sorted.isEmpty() ? "HOSTS · NONE" : "HOSTS");
        if (selectedHostUuid == null && !sorted.isEmpty()) selectedHostUuid = sorted.get(0).uuid;
        for (ComputerDetails host : sorted) {
            View card = hostCard(host);
            hostRow.addView(card, cardSpacing());
            if (host.uuid.equals(selectedHostUuid)) selectedHostCard = card;
        }
        hostScroll.post(() -> hostScroll.scrollTo(scroll != 0 ? scroll
                : preferences.getInt("host_scroll", 0), 0));
        restoreTaggedFocus(hostRow, focusedTag);
        ComputerDetails selected = hosts.get(selectedHostUuid);
        if (selected != null && appRow.getChildCount() == 0) selectHost(selected, false);
        if (selected != null && appsLabel.getText().toString().equals("APPLICATIONS")) selectHost(selected, false);
    }

    private View hostCard(ComputerDetails host) {
        LinearLayout card = cardBase(dp(250), dp(82));
        card.setTag("host:" + host.uuid);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(host.name, 16, Color.WHITE, true);
        name.setSingleLine(true);
        card.addView(name, wrapLinear());
        String address = host.activeAddress != null ? host.activeAddress.address
                : host.localAddress != null ? host.localAddress.address : "Address unavailable";
        int color = host.state == ComputerDetails.State.ONLINE ? 0xFF69F0AE
                : host.state == ComputerDetails.State.OFFLINE ? 0xFFFFB74D : 0xFFABB3CA;
        TextView state = text(host.state.name() + " · " + address, 11, color, true);
        state.setSingleLine(true);
        LinearLayout.LayoutParams stateParams = wrapLinear();
        stateParams.topMargin = dp(5);
        card.addView(state, stateParams);
        boolean selected = host.uuid.equals(selectedHostUuid);
        styleHostCard(card, false, selected);
        card.setOnClickListener(v -> selectHost(host, true));
        card.setOnFocusChangeListener((v, focused) -> styleHostCard(card, focused,
                host.uuid.equals(selectedHostUuid)));
        return card;
    }

    private void selectHost(ComputerDetails host, boolean focusApps) {
        selectedHostUuid = host.uuid;
        preferences.edit().putString("selected_host", host.uuid).apply();
        clearArtwork();
        appsLabel.setText("APPLICATIONS · " + host.name.toUpperCase(Locale.ROOT));
        renderHosts();
        startAppListPoller(host);
        renderAppsAsync(host, focusApps);
    }

    private void startAppListPoller(ComputerDetails host) {
        stopAppListPoller();
        if (managerBinder == null || !active) return;
        ComputerDetails current = managerBinder.getComputer(host.uuid);
        if (current == null) return;
        appListPoller = managerBinder.createAppListPoller(current);
        appListPoller.start();
    }

    private void stopAppListPoller() {
        if (appListPoller != null) {
            appListPoller.stop();
            appListPoller = null;
        }
    }

    private void renderAppsAsync(ComputerDetails host) {
        renderAppsAsync(host, false);
    }

    private void renderAppsAsync(ComputerDetails host, boolean focusApps) {
        String uuid = host.uuid;
        executor.execute(() -> {
            List<NvApp> apps = loadApps(host);
            mainHandler.post(() -> {
                if (!uuid.equals(selectedHostUuid)) return;
                renderApps(host, apps);
                if (focusApps && !apps.isEmpty()) appRow.getChildAt(0).requestFocus();
            });
        });
    }

    private List<NvApp> loadApps(ComputerDetails host) {
        String raw = host.rawAppList;
        if (raw == null || raw.isEmpty()) {
            try {
                raw = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(
                        getCacheDir(), "applist", host.uuid));
            } catch (IOException ignored) {}
        }
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        try {
            List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(raw));
            apps.sort(Comparator.comparing(NvApp::getAppName, String.CASE_INSENSITIVE_ORDER));
            return apps;
        } catch (IOException | XmlPullParserException error) {
            return Collections.emptyList();
        }
    }

    private void renderApps(ComputerDetails host, List<NvApp> apps) {
        int restored = preferences.getInt("app_scroll." + host.uuid, 0);
        Object focusedTag = getCurrentFocus() != null ? getCurrentFocus().getTag() : null;
        appRow.removeAllViews();
        appsLabel.setText("APPLICATIONS · " + host.name.toUpperCase(Locale.ROOT));
        if (apps.isEmpty()) {
            appRow.addView(text("No cached applications yet. Keep the host online while the list refreshes.",
                    15, 0xFFFFB74D, false), new LinearLayout.LayoutParams(
                    dp(700), ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            for (NvApp app : apps) appRow.addView(appCard(host, app), cardSpacing());
        }
        appScroll.post(() -> appScroll.scrollTo(restored, 0));
        restoreTaggedFocus(appRow, focusedTag);
    }

    private View appCard(ComputerDetails host, NvApp app) {
        LinearLayout card = cardBase(dp(300), dp(110));
        card.setTag("app:" + host.uuid + ":" + app.getAppId());
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setBackground(gradient(0xFF302255, 0xFF142A46, 9));
        card.addView(poster, new LinearLayout.LayoutParams(dp(56), dp(84)));
        File artwork = assetLoader.getFile(host.uuid, app.getAppId());
        loadPoster(host, app, artwork, poster);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(app.getAppName(), 16, Color.WHITE, true);
        name.setSingleLine(true);
        copy.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        long playedAt = preferences.getLong(appHistoryKey(host.uuid, app.getAppId()), 0L);
        String metadata = playedAt > 0
                ? "LAST PLAYED · " + formatRelative(System.currentTimeMillis() - playedAt).toUpperCase(Locale.ROOT)
                : "READY";
        TextView metadataView = text(metadata, 10, 0xFFAAAFC2, true);
        LinearLayout.LayoutParams metadataParams = wrapLinear();
        metadataParams.topMargin = dp(5);
        copy.addView(metadataView, metadataParams);
        TextView action = text("PLAY  ›", 12, 0xFFB99CFF, true);
        action.setAlpha(0f);
        LinearLayout.LayoutParams actionParams = wrapLinear();
        actionParams.topMargin = dp(5);
        copy.addView(action, actionParams);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        copyParams.leftMargin = dp(14);
        card.addView(copy, copyParams);
        card.setOnClickListener(v -> beginLaunch(host, app));
        card.setOnFocusChangeListener((v, focused) -> {
            styleCard(card, focused);
            if (reducedMotion) action.setAlpha(focused ? 1f : 0f);
            else action.animate().alpha(focused ? 1f : 0f).setDuration(120).start();
            if (focused) {
                smoothCenterOn(appScroll, card);
                showArtwork(artwork, poster.getDrawable());
            }
        });
        return card;
    }

    private void loadPoster(ComputerDetails host, NvApp app, File file, ImageView view) {
        executor.execute(() -> {
            if (!file.exists() && managerBinder != null && host.activeAddress != null) {
                CachedAppAssetLoader.LoaderTuple tuple =
                        new CachedAppAssetLoader.LoaderTuple(host, app);
                try (InputStream input = new NetworkAssetLoader(this,
                        managerBinder.getUniqueId()).getBitmapStream(tuple)) {
                    if (input != null) assetLoader.populateCacheWithStream(tuple, input);
                } catch (IOException | RuntimeException ignored) {}
            }
            if (!file.exists()) return;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            mainHandler.post(() -> {
                if (bitmap != null && view.isAttachedToWindow()) view.setImageBitmap(bitmap);
            });
        });
    }

    private void showArtwork(File file, Drawable preview) {
        int token = artworkGeneration.incrementAndGet();
        if (preview != null) {
            Drawable current = artworkHero.getDrawable();
            Drawable next = cloneDrawable(preview);
            artworkHero.animate().cancel();
            if (current != null && !reducedMotion) {
                TransitionDrawable transition = new TransitionDrawable(
                        new Drawable[]{current, next});
                transition.setCrossFadeEnabled(true);
                artworkHero.setImageDrawable(transition);
                transition.startTransition(180);
            } else {
                artworkHero.setImageDrawable(next);
            }
            artworkHero.animate().alpha(.72f).setDuration(reducedMotion ? 0 : 180).start();
            artworkScrim.animate().cancel();
            artworkScrim.animate().alpha(1f).setDuration(reducedMotion ? 0 : 180).start();
        }
        if (!file.exists()) {
            return;
        }
        executor.execute(() -> {
            Bitmap bitmap = decodeArtwork(file, 1200);
            int accent = sampleAccent(bitmap);
            mainHandler.post(() -> {
                if (token != artworkGeneration.get() || bitmap == null) return;
                glassAccent = accent;
                Drawable oldBackdrop = artworkBackdrop.getDrawable();
                BitmapDrawable nextBackdrop = new BitmapDrawable(getResources(), bitmap);
                if (oldBackdrop != null && !reducedMotion) {
                    TransitionDrawable transition = new TransitionDrawable(
                            new Drawable[]{oldBackdrop, nextBackdrop});
                    transition.setCrossFadeEnabled(true);
                    artworkBackdrop.setImageDrawable(transition);
                    transition.startTransition(380);
                    mainHandler.postDelayed(() -> {
                        if (token == artworkGeneration.get()) artworkBackdrop.setImageBitmap(bitmap);
                    }, 420L);
                } else {
                    artworkBackdrop.setImageBitmap(bitmap);
                }
                // The preview and final hero have identical geometry. Replacing the
                // preview avoids a soft double-image while retaining the tile-to-tile crossfade.
                artworkHero.setImageBitmap(bitmap);
                artworkBackdrop.animate().alpha(.16f).setDuration(reducedMotion ? 0 : 260).start();
                artworkHero.animate().alpha(.72f).setDuration(reducedMotion ? 0 : 220).start();
                artworkScrim.animate().alpha(1f).setDuration(reducedMotion ? 0 : 220).start();
                View focused = getCurrentFocus();
                if (focused != null && focused.getTag() instanceof String
                        && ((String) focused.getTag()).startsWith("app:")) {
                    styleCard(focused, true);
                }
            });
        });
    }

    private void clearArtwork() {
        int token = artworkGeneration.incrementAndGet();
        glassAccent = 0xFF715BA8;
        if (artworkBackdrop != null) {
            artworkBackdrop.animate().cancel();
            artworkBackdrop.animate().alpha(0f).setDuration(reducedMotion ? 0 : 260).start();
        }
        if (artworkHero != null) {
            artworkHero.animate().cancel();
            artworkHero.animate().alpha(0f).setDuration(reducedMotion ? 0 : 260)
                    .withEndAction(() -> {
                        if (token != artworkGeneration.get()) return;
                        artworkBackdrop.setImageDrawable(null);
                        artworkHero.setImageDrawable(null);
                    }).start();
        }
        if (artworkScrim != null) {
            artworkScrim.animate().cancel();
            artworkScrim.animate().alpha(0f).setDuration(reducedMotion ? 0 : 260).start();
        }
    }

    private Bitmap decodeArtwork(File file, int maxDimension) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > maxDimension
                || bounds.outHeight / sample > maxDimension) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private Drawable cloneDrawable(Drawable drawable) {
        Drawable.ConstantState state = drawable != null ? drawable.getConstantState() : null;
        return state != null ? state.newDrawable(getResources()).mutate() : drawable;
    }

    private int sampleAccent(Bitmap bitmap) {
        if (bitmap == null) return 0xFF715BA8;
        long red = 0, green = 0, blue = 0, count = 0;
        int stepX = Math.max(1, bitmap.getWidth() / 12);
        int stepY = Math.max(1, bitmap.getHeight() / 12);
        for (int y = 0; y < bitmap.getHeight(); y += stepY) {
            for (int x = 0; x < bitmap.getWidth(); x += stepX) {
                int color = bitmap.getPixel(x, y);
                int brightness = Color.red(color) + Color.green(color) + Color.blue(color);
                if (brightness < 90 || brightness > 690) continue;
                red += Color.red(color); green += Color.green(color); blue += Color.blue(color); count++;
            }
        }
        return count == 0 ? 0xFF715BA8 : Color.rgb((int) (red / count),
                (int) (green / count), (int) (blue / count));
    }

    private void beginLaunch(ComputerDetails host, NvApp app) {
        if (managerBinder == null) {
            Toast.makeText(this, "Moonlight is still initializing.", Toast.LENGTH_SHORT).show();
            return;
        }
        int token = launchGeneration.incrementAndGet();
        showLoading(host.name);
        executor.execute(() -> {
            ComputerDetails ready = HostReadiness.await(
                    () -> managerBinder != null ? managerBinder.getComputer(host.uuid) : null,
                    host,
                    () -> token != launchGeneration.get() || !active,
                    message -> setLoadingStatus(token, message));
            mainHandler.post(() -> {
                if (token != launchGeneration.get() || !active) return;
                if (ready == null) {
                    loadingMessage.setText("The host did not become ready.");
                    loadingMessage.setTextColor(0xFFFF8A80);
                    loadingStatus.setText("No streaming port responded within 90 seconds. Press BACK to return.");
                    return;
                }
                preferences.edit()
                        .putLong(appHistoryKey(host.uuid, app.getAppId()), System.currentTimeMillis())
                        .putLong("host_played." + host.uuid, System.currentTimeMillis())
                        .apply();
                loadingStatus.setText("Opening Moonlight stream…");
                // Keep Moonlight's existing launch path and stream lifecycle unchanged.
                Bundle presentation = new Bundle();
                presentation.putBoolean(Game.EXTRA_CONSOLE_LOADING, true);
                presentation.putString(Game.EXTRA_CONSOLE_LOADING_MESSAGE,
                        loadingMessage.getText().toString());
                presentation.putLong(Game.EXTRA_CONSOLE_LOADING_EPOCH,
                        loadingBackdrop.getStartedAt());
                presentation.putBoolean(Game.EXTRA_CONSOLE_REDUCED_MOTION, reducedMotion);
                ServerHelper.doStart(ConsoleActivity.this, app, ready, managerBinder, presentation);
                overridePendingTransition(0, 0);
            });
        });
    }

    private void showLoading(String hostName) {
        homeLayer.setVisibility(View.GONE);
        loadingLayer.setVisibility(View.VISIBLE);
        loadingMessage.setTextColor(Color.WHITE);
        loadingMessage.setText(LOADING_MESSAGES[0]);
        loadingStatus.setText("Preparing " + hostName + "…");
        loadingBackdrop.start();
        loadingMessage.setTag(0);
        mainHandler.postDelayed(rotateLoadingMessage, 2300L);
    }

    private final Runnable rotateLoadingMessage = new Runnable() {
        @Override public void run() {
            if (loadingLayer.getVisibility() != View.VISIBLE) return;
            int index = loadingMessage.getTag() instanceof Integer ? (Integer) loadingMessage.getTag() : 0;
            index = (index + 1) % LOADING_MESSAGES.length;
            loadingMessage.setTag(index);
            loadingMessage.setText(LOADING_MESSAGES[index]);
            mainHandler.postDelayed(this, 2300L);
        }
    };

    private void setLoadingStatus(int token, String message) {
        mainHandler.post(() -> {
            if (token == launchGeneration.get() && loadingLayer.getVisibility() == View.VISIBLE) {
                loadingStatus.setText(message);
            }
        });
    }

    private void showHome() {
        mainHandler.removeCallbacks(rotateLoadingMessage);
        loadingBackdrop.stop();
        loadingLayer.setVisibility(View.GONE);
        homeLayer.setVisibility(View.VISIBLE);
    }

    private void refreshControllers() {
        executor.execute(() -> {
            List<ControllerInfo> controllers = loadControllers();
            mainHandler.post(() -> renderControllers(controllers));
        });
    }

    private final Runnable controllerRefresh = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refreshControllers();
            mainHandler.postDelayed(this, CONTROLLER_REFRESH_MS);
        }
    };

    private List<ControllerInfo> loadControllers() {
        List<ControllerInfo> controllers = new ArrayList<>();
        Set<String> descriptors = new HashSet<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || !isGamepad(device)) continue;
            String name = device.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith("virtual-")) continue;
            if (device.getDescriptor() != null && !descriptors.add(device.getDescriptor())) continue;
            int percentage = -1;
            boolean charging = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BatteryState battery = device.getBatteryState();
                if (battery.isPresent()) {
                    if (!Float.isNaN(battery.getCapacity())) percentage = Math.round(battery.getCapacity() * 100f);
                    charging = battery.getStatus() == BatteryState.STATUS_CHARGING;
                }
            }
            controllers.add(new ControllerInfo(id, name, percentage, charging));
        }
        controllers.sort(Comparator.comparing(controller -> controller.name,
                String.CASE_INSENSITIVE_ORDER));
        return controllers;
    }

    private void renderControllers(List<ControllerInfo> controllers) {
        int scroll = controllerScroll.getScrollX();
        Object focusedTag = getCurrentFocus() != null ? getCurrentFocus().getTag() : null;
        controllerRow.removeAllViews();
        controllersLabel.setText(controllers.isEmpty() ? "CONTROLLERS · NONE" : "CONTROLLERS");
        int player = 1;
        for (ControllerInfo controller : controllers) {
            controllerRow.addView(controllerCard(player++, controller), cardSpacing());
        }
        controllerScroll.post(() -> controllerScroll.scrollTo(scroll != 0 ? scroll
                : preferences.getInt("controller_scroll", 0), 0));
        restoreTaggedFocus(controllerRow, focusedTag);
    }

    private View controllerCard(int player, ControllerInfo controller) {
        LinearLayout card = cardBase(dp(225), dp(54));
        card.setTag("controller:" + controller.deviceId);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("P" + player + "  " + compactControllerName(controller.name),
                14, Color.WHITE, true);
        name.setSingleLine(true);
        copy.addView(name, wrapLinear());
        int batteryColor = controller.percentage < 0 ? 0xFFB3B8C8
                : controller.charging ? 0xFF64B5F6
                : controller.percentage <= 10 ? 0xFFFF5252
                : controller.percentage <= 30 ? 0xFFFFB74D : 0xFF69F0AE;
        String battery = controller.charging ? "CHARGING · " : "BATTERY · ";
        TextView level = text(battery + (controller.percentage < 0
                ? "UNAVAILABLE" : controller.percentage + "%"), 11, batteryColor, true);
        copy.addView(level, wrapLinear());
        card.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setOnClickListener(v -> showControllerMenu(player, controller));
        card.setOnFocusChangeListener((v, focused) -> styleCard(card, focused));
        return card;
    }

    private void showControllerMenu(int player, ControllerInfo controller) {
        String identify = ControllerActions.canIdentify(controller.deviceId)
                ? "Identify controller" : "Identify controller · unavailable";
        new AlertDialog.Builder(this)
                .setTitle("P" + player + " · " + compactControllerName(controller.name))
                .setItems(new String[]{identify, "Power off controller", "Unpair controller"},
                        (dialog, which) -> {
                            if (which == 0) {
                                if (ControllerActions.canIdentify(controller.deviceId)) {
                                    ControllerActions.identify(controller.deviceId, mainHandler,
                                            (success, message) -> mainHandler.post(() ->
                                                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()));
                                } else {
                                    Toast.makeText(this, "Identification is unavailable for this controller.",
                                            Toast.LENGTH_LONG).show();
                                }
                            } else if (which == 1) confirmBluetoothAction(controller, BluetoothAction.POWER_OFF);
                            else confirmBluetoothAction(controller, BluetoothAction.UNPAIR);
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmBluetoothAction(ControllerInfo controller, BluetoothAction action) {
        boolean unpair = action == BluetoothAction.UNPAIR;
        new AlertDialog.Builder(this)
                .setTitle(unpair ? "Unpair controller?" : "Power off controller?")
                .setMessage(unpair
                        ? "The Bluetooth pairing will be removed. You must pair the controller again before using it."
                        : "The controller will disconnect from the TV. Use its power button to connect it again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(unpair ? "Unpair" : "Power off",
                        (dialog, which) -> runBluetoothAction(controller, action))
                .show();
    }

    private void runBluetoothAction(ControllerInfo controller, BluetoothAction action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            pendingController = controller;
            pendingBluetoothAction = action;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_CONNECT);
            return;
        }
        executeBluetoothAction(controller, action);
    }

    private void executeBluetoothAction(ControllerInfo controller, BluetoothAction action) {
        executor.execute(() -> {
            ControllerActions.ResultCallback callback = (success, message) -> mainHandler.post(() -> {
                if (success) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    refreshControllers();
                } else {
                    new AlertDialog.Builder(this).setTitle("Operation unavailable")
                            .setMessage(message).setNegativeButton("Close", null)
                            .setPositiveButton("Bluetooth settings", (dialog, which) -> {
                                try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
                                catch (RuntimeException error) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                            }).show();
                }
            });
            if (action == BluetoothAction.POWER_OFF) {
                ControllerActions.disconnect(this, controller.deviceId, callback);
            } else {
                ControllerActions.unpair(controller.deviceId, callback);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH_CONNECT) return;
        ControllerInfo controller = pendingController;
        BluetoothAction action = pendingBluetoothAction;
        pendingController = null;
        pendingBluetoothAction = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && controller != null && action != null) {
            executeBluetoothAction(controller, action);
        } else {
            Toast.makeText(this, "Bluetooth access is required for this operation.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showOptionsPanel() {
        TextView sounds = panelAction("UI SOUNDS · " + (uiSoundsEnabled ? "ON" : "OFF"));
        TextView motion = panelAction("REDUCED MOTION · " + (reducedMotion ? "ON" : "OFF"));
        TextView integrations = panelAction("HOST INTEGRATIONS  ›");
        TextView settings = panelAction("STREAMING SETTINGS  ›");
        TextView classic = panelAction("CLASSIC MOONLIGHT UI  ›");
        sounds.setOnClickListener(v -> {
            uiSoundsEnabled = !uiSoundsEnabled;
            preferences.edit().putBoolean("ui_sounds", uiSoundsEnabled).apply();
            sounds.setText("UI SOUNDS · " + (uiSoundsEnabled ? "ON" : "OFF"));
        });
        motion.setOnClickListener(v -> {
            reducedMotion = !reducedMotion;
            preferences.edit().putBoolean("reduced_motion", reducedMotion).apply();
            motion.setText("REDUCED MOTION · " + (reducedMotion ? "ON" : "OFF"));
        });
        integrations.setOnClickListener(v -> showHostIntegrations());
        settings.setOnClickListener(v -> startActivity(new Intent(this, StreamSettings.class)));
        classic.setOnClickListener(v -> startActivity(new Intent(this, PcView.class)));
        showSidePanel("MOONLIGHT", "Options",
                "Tune the console interface or open Moonlight's existing streaming preferences.",
                sounds, motion, integrations, settings, classic);
    }

    private void showHostIntegrations() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) {
            Toast.makeText(this, "Select a host first.", Toast.LENGTH_LONG).show();
            return;
        }
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        discordPanelController.showHostIntegrations(host.uuid, address, host.name);
    }

    private void showSidePanel(String eyebrow, String title, String details, View... actions) {
        if (modalLayer.getVisibility() != View.VISIBLE) lastContentFocus = getCurrentFocus();
        sidePanel.removeAllViews();
        sidePanel.addView(text(eyebrow, 12, 0xFFAFA4C9, true), wrapLinear());
        TextView titleView = text(title, 29, Color.WHITE, true);
        sidePanel.addView(titleView, sectionWithTop(10));
        TextView detailView = text(details, 14, 0xFFC1C5D6, false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(10);
        detailParams.bottomMargin = dp(16);
        sidePanel.addView(detailView, detailParams);
        View first = null;
        for (View action : actions) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(7);
            sidePanel.addView(action, params);
            if (first == null) first = action;
        }
        TextView hint = text("BACK · CLOSE", 11, 0x8FFFFFFF, true);
        sidePanel.addView(hint, sectionWithTop(12));
        modalLayer.setVisibility(View.VISIBLE);
        sidePanelScroll.setTranslationX(reducedMotion ? 0 : dp(510));
        if (!reducedMotion) sidePanelScroll.animate().translationX(0).setDuration(180).start();
        if (first != null) first.post(first::requestFocus);
    }

    private void hideSidePanel() {
        Runnable finish = () -> {
            modalLayer.setVisibility(View.GONE);
            sidePanelScroll.setTranslationX(0);
            if (lastContentFocus != null && lastContentFocus.isShown()) lastContentFocus.requestFocus();
        };
        if (reducedMotion) finish.run();
        else sidePanelScroll.animate().translationX(dp(510)).setDuration(150).withEndAction(finish).start();
    }

    private TextView panelAction(String label) {
        TextView action = text(label, 14, 0xFFF0E9FF, true);
        action.setFocusable(true);
        action.setClickable(true);
        action.setMinHeight(dp(46));
        action.setPadding(dp(16), dp(7), dp(16), dp(7));
        action.setOnFocusChangeListener((view, focused) -> styleCompactButton(action, focused));
        styleCompactButton(action, false);
        return action;
    }

    private TextView compactButton(String label) {
        TextView button = panelAction(label);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private LinearLayout cardBase(int width, int height) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(8), dp(16), dp(8));
        card.setFocusable(true);
        card.setClickable(true);
        card.setSoundEffectsEnabled(uiSoundsEnabled);
        card.setMinimumWidth(width);
        card.setMinimumHeight(height);
        styleCard(card, false);
        return card;
    }

    private void styleCard(View card, boolean focused) {
        int top = focused ? blendColor(0xFF715BA8, glassAccent, .48f) : 0x38242B3D;
        int bottom = focused ? blendColor(0xFF403362, glassAccent, .32f) : 0x70131825;
        GradientDrawable background = gradient(top, bottom, 14);
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFFECE7FF : 0x5C9AA6C4);
        card.setBackground(background);
        card.setElevation(dp(focused ? 9 : 3));
        animateScale(card, focused ? 1.022f : 1f);
    }

    private void styleHostCard(View card, boolean focused, boolean selected) {
        int top = focused ? blendColor(0xFF62577F, glassAccent, .36f)
                : selected ? 0x58212838 : 0x401C2333;
        int bottom = focused ? blendColor(0xFF353047, glassAccent, .24f)
                : selected ? 0x70161B28 : 0x60131825;
        GradientDrawable background = gradient(top, bottom, 12);
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFFDCD5F2
                : selected ? 0x8C8B94AD : 0x607C89B2);
        card.setBackground(background);
        card.setElevation(dp(focused ? 8 : selected ? 4 : 2));
        animateScale(card, focused ? 1.015f : 1f);
    }

    private void styleCompactButton(View button, boolean focused) {
        int top = focused ? blendColor(0xFF58478F, glassAccent, .38f) : 0x32242B3D;
        int bottom = focused ? blendColor(0xFF342B59, glassAccent, .22f) : 0x65131825;
        GradientDrawable background = gradient(top, bottom, 10);
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFFE9E3FF : 0x587C89B2);
        button.setBackground(background);
        button.setElevation(dp(focused ? 7 : 2));
    }

    private void animateScale(View view, float scale) {
        view.animate().cancel();
        if (reducedMotion || !view.isLaidOut()) {
            view.setScaleX(scale); view.setScaleY(scale);
        } else {
            view.animate().scaleX(scale).scaleY(scale).setDuration(125).start();
        }
    }

    private void saveScrollPositions() {
        SharedPreferences.Editor editor = preferences.edit()
                .putInt("controller_scroll", controllerScroll != null ? controllerScroll.getScrollX() : 0)
                .putInt("host_scroll", hostScroll != null ? hostScroll.getScrollX() : 0);
        if (selectedHostUuid != null && appScroll != null) {
            editor.putInt("app_scroll." + selectedHostUuid, appScroll.getScrollX());
        }
        editor.apply();
    }

    private void restoreTaggedFocus(ViewGroup row, Object tag) {
        if (tag == null || row == null) return;
        for (int index = 0; index < row.getChildCount(); index++) {
            View child = row.getChildAt(index);
            if (tag.equals(child.getTag())) {
                child.post(child::requestFocus);
                return;
            }
        }
    }

    private void smoothCenterOn(HorizontalScrollView scroll, View tile) {
        if (scroll == null || tile == null) return;
        tile.post(() -> {
            int target = Math.max(0, tile.getLeft() - (scroll.getWidth() - tile.getWidth()) / 2);
            if (reducedMotion) scroll.scrollTo(target, 0);
            else scroll.smoothScrollTo(target, 0);
        });
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override public void onInputDeviceAdded(int deviceId) { refreshControllers(); }
    @Override public void onInputDeviceRemoved(int deviceId) { refreshControllers(); }
    @Override public void onInputDeviceChanged(int deviceId) { refreshControllers(); }

    private HorizontalScrollView horizontalScroll() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFocusable(false);
        return scroll;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        return row;
    }

    private TextView sectionLabel(String value) { return text(value, 12, 0xFF9CA6C5, true); }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams cardSpacing() {
        LinearLayout.LayoutParams params = wrapLinear();
        params.rightMargin = dp(14);
        return params;
    }

    private LinearLayout.LayoutParams sectionWithTop(int top) {
        LinearLayout.LayoutParams params = wrapLinear();
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout.LayoutParams wrapLinear() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private GradientDrawable gradient(int top, int bottom, int radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom});
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int blendColor(int from, int to, float amount) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }

    private static boolean isGamepad(InputDevice device) {
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static String compactControllerName(String name) {
        if (name == null) return "Controller";
        return name.toLowerCase(Locale.ROOT).contains("dualsense") ? "DualSense" : name;
    }

    private static String appHistoryKey(String hostUuid, int appId) {
        return "played_at.app." + hostUuid + "." + appId;
    }

    private static String formatRelative(long milliseconds) {
        long minutes = Math.max(0L, milliseconds / 60_000L);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private static final class ControllerInfo {
        final int deviceId;
        final String name;
        final int percentage;
        final boolean charging;

        ControllerInfo(int deviceId, String name, int percentage, boolean charging) {
            this.deviceId = deviceId;
            this.name = name != null ? name : "Controller";
            this.percentage = percentage;
            this.charging = charging;
        }
    }

    private enum BluetoothAction { POWER_OFF, UNPAIR }
}
