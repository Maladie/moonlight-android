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
import android.preference.PreferenceManager;
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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.AppView;
import com.limelight.Game;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.AppPreferences;
import com.limelight.preferences.AppStreamSettings;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.QuickLaunchManager;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;
import com.limelight.ui.OverridesView;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final AtomicInteger launchGeneration = new AtomicInteger();
    private final AtomicInteger artworkGeneration = new AtomicInteger();
    private final AtomicInteger discordStatusGeneration = new AtomicInteger();
    private final Map<String, ComputerDetails> hosts = new LinkedHashMap<>();
    private final Set<String> newlyDiscoveredHosts = new LinkedHashSet<>();

    private SharedPreferences preferences;
    private DiskAssetLoader assetLoader;
    private AudioManager audioManager;
    private InputManager inputManager;
    private DiscordPanelController discordPanelController;
    private HostGatewayClient hostGatewayClient;
    private HostGatewayStore hostGatewayStore;
    private QuickLaunchManager quickLaunchManager;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private ComputerManagerService.ApplistPoller appListPoller;
    private boolean serviceBound;
    private boolean polling;
    private boolean active;
    private boolean inputListenerRegistered;
    private boolean reducedMotion;
    private boolean uiSoundsEnabled;
    private boolean refreshHostsOnResume;
    private boolean initialHostsLoaded;
    private boolean showHiddenApps;
    private String[] loadingMessages;

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
    private TextView appsLabel;
    private TextView optionsButton;
    private TextView hostSelector;
    private TextView discoveryStatus;
    private LinearLayout quickActions;
    private ImageButton discordActionButton;
    private LinearLayout controllerRow;
    private LinearLayout appRow;
    private HorizontalScrollView controllerScroll;
    private HorizontalScrollView appScroll;
    private String selectedHostUuid;
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
            if (previous == null && initialHostsLoaded) newlyDiscoveredHosts.add(copy.uuid);
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
        loadingMessages = getResources().getStringArray(R.array.console_loading_messages);
        assetLoader = new DiskAssetLoader(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        hostGatewayClient = new HostGatewayClient();
        hostGatewayStore = new HostGatewayStore(this);
        quickLaunchManager = QuickLaunchManager.getInstance(this);
        shortcutHelper = new ShortcutHelper(this);
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
        if (refreshHostsOnResume) {
            refreshHostsOnResume = false;
            loadKnownHosts();
        }
        if (inputManager != null && !inputListenerRegistered) {
            inputManager.registerInputDeviceListener(this, mainHandler);
            inputListenerRegistered = true;
        }
        startPolling();
        ComputerDetails selected = hosts.get(selectedHostUuid);
        if (selected != null && appListPoller == null) startAppListPoller(selected);
        refreshControllers();
        refreshDiscordIndicator();
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
            showExitConfirmation();
        }
    }

    private void loadKnownHosts() {
        executor.execute(() -> {
            ComputerDatabaseManager database = new ComputerDatabaseManager(this);
            List<ComputerDetails> known = database.getAllComputers();
            database.close();
            mainHandler.post(() -> {
                for (ComputerDetails host : known) hosts.put(host.uuid, host);
                initialHostsLoaded = true;
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
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(getString(R.string.console_title), 30, Color.WHITE, true);
        TextView subtitle = text(getString(R.string.console_subtitle),
                14, 0xFFBCC3DD, false);
        titleBlock.addView(title, wrapLinear());
        titleBlock.addView(subtitle, wrapLinear());
        header.addView(titleBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hostSelector = compactButton(getString(R.string.console_no_hosts));
        hostSelector.setContentDescription(getString(R.string.console_action_hosts));
        hostSelector.setOnClickListener(v -> showHostPanel());
        optionsButton = hostSelector;
        header.addView(hostSelector, new LinearLayout.LayoutParams(dp(360), dp(52)));
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout quickLine = new LinearLayout(this);
        quickLine.setOrientation(LinearLayout.HORIZONTAL);
        quickLine.setGravity(Gravity.CENTER_VERTICAL);
        discoveryStatus = text(getString(R.string.console_discovering), 12, 0xFF9FB4D9, false);
        quickLine.addView(discoveryStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        quickActions = horizontalRow();
        addQuickAction(R.drawable.ic_computer, R.string.console_action_hosts, v -> showHostPanel());
        addQuickAction(R.drawable.ic_overlay_restart, R.string.console_action_refresh, v -> refreshDashboard());
        addQuickAction(R.drawable.ic_add, R.string.console_action_add_host, v -> addHost());
        addQuickAction(R.drawable.ic_play, R.string.console_action_quick_launch, v -> showQuickLaunchPanel());
        addQuickAction(R.drawable.ic_settings, R.string.console_action_stream_settings,
                v -> startActivity(new Intent(this, StreamSettings.class)));
        addQuickAction(R.drawable.ic_overrides, R.string.console_action_overrides, v -> showOverridesPanel());
        addQuickAction(R.drawable.ic_auto_resume, R.string.console_action_auto_resume, v -> toggleAutoResume());
        addQuickAction(R.drawable.ic_lock, R.string.console_action_hidden_apps, v -> toggleHiddenApps());
        discordActionButton = addQuickAction(R.drawable.ic_channel,
                R.string.console_action_discord, v -> showHostIntegrations());
        addQuickAction(R.drawable.ic_help, R.string.console_action_help, v -> HelpLauncher.launchSetupGuide(this));
        quickLine.addView(quickActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        content.addView(quickLine, sectionWithTop(8));

        controllersLabel = sectionLabel(getString(R.string.console_controllers_none));
        LinearLayout.LayoutParams section = wrapLinear();
        section.topMargin = dp(13);
        content.addView(controllersLabel, section);
        controllerScroll = horizontalScroll();
        controllerRow = horizontalRow();
        controllerScroll.addView(controllerRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(controllerScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        appsLabel = sectionLabel(getString(R.string.console_apps));
        content.addView(appsLabel, sectionWithTop(7));
        appScroll = horizontalScroll();
        appRow = horizontalRow();
        appScroll.addView(appRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(appScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(142)));
        appRow.addView(text(getString(R.string.console_choose_host), 15, 0xFFBDC4D8, false),
                new LinearLayout.LayoutParams(dp(500), ViewGroup.LayoutParams.MATCH_PARENT));
        wireHomeFocusNavigation();

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
        loadingMessage = text(loadingMessages[0], 30, Color.WHITE, true);
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
        List<ComputerDetails> sorted = new ArrayList<>(hosts.values());
        sorted.sort(Comparator.comparing(host -> host.name, String.CASE_INSENSITIVE_ORDER));
        if (selectedHostUuid == null && !sorted.isEmpty()) selectedHostUuid = sorted.get(0).uuid;
        updateHostSelector();
        wireHomeFocusNavigation();
        ComputerDetails selected = hosts.get(selectedHostUuid);
        if (selected != null && appRow != null && appRow.getChildCount() <= 1) selectHost(selected, false);
    }

    private void updateHostSelector() {
        if (hostSelector == null) return;
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) {
            hostSelector.setText(getString(R.string.console_no_hosts));
            hostSelector.setEnabled(true);
        } else {
            hostSelector.setText(getString(R.string.console_host_selector,
                    host.name, hostStatus(host)));
            hostSelector.setEnabled(true);
        }
        if (discoveryStatus != null) {
            if (!newlyDiscoveredHosts.isEmpty()) {
                discoveryStatus.setText(getResources().getQuantityString(
                        R.plurals.console_new_hosts, newlyDiscoveredHosts.size(),
                        newlyDiscoveredHosts.size()));
                discoveryStatus.setTextColor(0xFF73D7FF);
            } else if (polling) {
                discoveryStatus.setText(getString(R.string.console_discovering));
                discoveryStatus.setTextColor(0xFF9FB4D9);
            } else {
                discoveryStatus.setText(getString(R.string.console_discovery_idle));
                discoveryStatus.setTextColor(0xFF8790A8);
            }
        }
        refreshDiscordIndicator();
    }

    private String hostStatus(ComputerDetails host) {
        ConsoleDashboardState.HostState state = ConsoleDashboardState.hostState(
                host.state == ComputerDetails.State.ONLINE,
                host.state != ComputerDetails.State.UNKNOWN,
                host.pairState == PairingManager.PairState.PAIRED,
                host.runningGameId);
        switch (state) {
            case ACTIVE_SESSION:
                String appName = findAppName(host, host.runningGameId);
                return appName == null ? getString(R.string.console_status_active_session)
                        : getString(R.string.console_status_active_app, appName);
            case OFFLINE:
                return getString(R.string.console_status_offline);
            case DISCOVERING:
                return getString(R.string.console_status_connecting);
            case UNPAIRED:
                return getString(R.string.console_status_unpaired);
            default:
                return getString(R.string.console_status_online);
        }
    }

    private String findAppName(ComputerDetails host, int appId) {
        for (NvApp app : loadApps(host, true)) {
            if (app.getAppId() == appId) return app.getAppName();
        }
        return null;
    }

    private void showHostPanel() {
        List<ComputerDetails> sorted = new ArrayList<>(hosts.values());
        sorted.sort(Comparator.comparing(host -> host.name, String.CASE_INSENSITIVE_ORDER));
        List<View> actions = new ArrayList<>();
        if (sorted.isEmpty()) actions.add(label(getString(R.string.console_no_hosts_details)));
        for (ComputerDetails host : sorted) {
            String selected = host.uuid.equals(selectedHostUuid)
                    ? getString(R.string.console_selected_suffix) : "";
            TextView choose = panelAction(getString(R.string.console_host_choice,
                    host.name, hostStatus(host), selected));
            choose.setAlpha(host.state == ComputerDetails.State.OFFLINE ? .62f : 1f);
            choose.setOnClickListener(view -> {
                newlyDiscoveredHosts.remove(host.uuid);
                selectHost(host, true);
                hideSidePanel();
            });
            TextView manage = panelAction(getString(R.string.console_manage_host, host.name));
            manage.setOnClickListener(view -> showHostActions(host));
            actions.add(choose);
            actions.add(manage);
        }
        TextView refresh = panelAction(getString(R.string.console_refresh));
        refresh.setOnClickListener(view -> {
            hideSidePanel();
            refreshDashboard();
        });
        TextView add = panelAction(getString(R.string.console_add_host));
        add.setOnClickListener(view -> addHost());
        TextView options = panelAction(getString(R.string.console_options_action));
        options.setOnClickListener(view -> showOptionsPanel());
        actions.add(refresh);
        actions.add(add);
        actions.add(options);
        newlyDiscoveredHosts.clear();
        updateHostSelector();
        showSidePanel(getString(R.string.console_hosts_eyebrow),
                getString(R.string.console_hosts_title),
                polling ? getString(R.string.console_hosts_scanning)
                        : getString(R.string.console_hosts_ready),
                actions.toArray(new View[0]));
    }

    private TextView label(String value) {
        TextView view = text(value, 13, 0xFFBDC4D8, false);
        view.setPadding(dp(8), dp(7), dp(8), dp(7));
        return view;
    }

    private void addHost() {
        refreshHostsOnResume = true;
        Intent intent = new Intent(this, AddComputerManually.class);
        intent.putExtra(AddComputerManually.EXTRA_CONSOLE_APPEARANCE, true);
        startActivity(intent);
    }

    private void refreshDashboard() {
        if (managerBinder == null) {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return;
        }
        for (ComputerDetails host : hosts.values()) {
            managerBinder.invalidateStateForComputer(host.uuid);
        }
        if (appListPoller != null) appListPoller.pollNow();
        discoveryStatus.setText(R.string.console_discovering);
        Toast.makeText(this, R.string.console_refresh_started, Toast.LENGTH_SHORT).show();
    }

    private void showQuickLaunchPanel() {
        List<QuickLaunchManager.QuickLaunchItem> items = quickLaunchManager.getAllQuickLaunchItems();
        List<View> actions = new ArrayList<>();
        if (items.isEmpty()) actions.add(label(getString(R.string.console_quick_launch_empty)));
        for (QuickLaunchManager.QuickLaunchItem item : items) {
            TextView action = panelAction(getString(R.string.console_quick_launch_item,
                    item.getDisplayName(), item.computerName));
            action.setOnClickListener(view -> launchQuickItem(item));
            actions.add(action);
        }
        TextView classic = panelAction(getString(R.string.console_manage_quick_launch));
        classic.setOnClickListener(view -> startActivity(new Intent(this, PcView.class)));
        actions.add(classic);
        showSidePanel(getString(R.string.console_quick_launch_eyebrow),
                getString(R.string.quick_launch_section_title),
                getString(R.string.console_quick_launch_details),
                actions.toArray(new View[0]));
    }

    private void launchQuickItem(QuickLaunchManager.QuickLaunchItem item) {
        ComputerDetails host = hosts.get(item.computerUuid);
        if (host == null) {
            Toast.makeText(this, R.string.console_quick_launch_host_missing, Toast.LENGTH_LONG).show();
            return;
        }
        for (NvApp app : loadApps(host, true)) {
            if (app.getAppId() == item.appId) {
                hideSidePanel();
                launchOrConfirm(host, app);
                return;
            }
        }
        Toast.makeText(this, R.string.console_quick_launch_app_missing, Toast.LENGTH_LONG).show();
    }

    private void showOverridesPanel() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean(OverridesView.PREF_OVERRIDES_ENABLED, false);
        int bitrate = OverridesView.getBitrateOverride(this);
        TextView toggle = panelAction(getString(R.string.console_overrides_toggle,
                enabled ? getString(R.string.console_on) : getString(R.string.console_off)));
        toggle.setOnClickListener(view -> {
            prefs.edit().putBoolean(OverridesView.PREF_OVERRIDES_ENABLED, !enabled).apply();
            showOverridesPanel();
        });
        TextView configure = panelAction(getString(R.string.console_overrides_configure));
        configure.setOnClickListener(view -> startActivity(new Intent(this, PcView.class)));
        showSidePanel(getString(R.string.console_overrides_eyebrow),
                getString(R.string.overrides_section_title),
                bitrate > 0 ? getString(R.string.console_overrides_bitrate, bitrate / 1000)
                        : getString(R.string.console_overrides_default),
                toggle, configure);
    }

    private void toggleAutoResume() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean("checkbox_auto_resume_stream", false);
        prefs.edit().putBoolean("checkbox_auto_resume_stream", !enabled).apply();
        Toast.makeText(this, !enabled ? R.string.console_auto_resume_on
                : R.string.console_auto_resume_off, Toast.LENGTH_SHORT).show();
    }

    private void toggleHiddenApps() {
        showHiddenApps = !showHiddenApps;
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host != null) renderAppsAsync(host, true);
        Toast.makeText(this, showHiddenApps ? R.string.console_hidden_shown
                : R.string.console_hidden_filtered, Toast.LENGTH_SHORT).show();
    }

    private ImageButton addQuickAction(int drawable, int description,
                                       View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setId(View.generateViewId());
        button.setImageResource(drawable);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int target = getResources().getDimensionPixelSize(R.dimen.console_action_target);
        int icon = getResources().getDimensionPixelSize(R.dimen.console_icon_size);
        int inset = Math.max(0, (target - icon) / 2);
        button.setPadding(inset, inset, inset, inset);
        button.setBackground(gradient(0x30242B3D, 0x50131825, 12));
        button.setFocusable(true);
        button.setClickable(true);
        button.setContentDescription(getString(description));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setTooltipText(getString(description));
        }
        button.setOnClickListener(listener);
        button.setOnFocusChangeListener((view, focused) -> styleCompactButton(button, focused));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(target, target);
        params.leftMargin = getResources().getDimensionPixelSize(R.dimen.console_space_xs);
        quickActions.addView(button, params);
        return button;
    }

    private void refreshDiscordIndicator() {
        ImageButton button = discordActionButton;
        if (button == null) return;
        int request = discordStatusGeneration.incrementAndGet();
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) {
            applyDiscordIndicator(button, 0xFF7D8498,
                    getString(R.string.console_discord_no_host));
            return;
        }
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, address);
        if (connection == null) {
            applyDiscordIndicator(button, 0xFF7D8498,
                    getString(R.string.console_discord_unconfigured));
            return;
        }
        if (!hostGatewayStore.isDiscordEnabled(host.uuid, connection.profileId)) {
            applyDiscordIndicator(button, 0xFF697083,
                    getString(R.string.console_discord_disabled));
            return;
        }
        applyDiscordIndicator(button, 0xFF73D7FF,
                getString(R.string.console_discord_connecting));
        executor.execute(() -> {
            int color = 0xFFFF6B6B;
            String state;
            try {
                HostGatewayClient.DiscordStatus status =
                        hostGatewayClient.getDiscordStatus(connection);
                if (!status.bridgeOnline) {
                    state = getString(R.string.console_discord_disconnected);
                    color = 0xFF8D95AA;
                } else if (!status.authenticated) {
                    state = getString(R.string.console_discord_auth_required);
                    color = 0xFFFFB74D;
                } else if (!status.rpcConnected) {
                    state = getString(R.string.console_discord_reconnecting);
                    color = 0xFF73D7FF;
                } else {
                    HostGatewayClient.DiscordVoice voice =
                            hostGatewayClient.getDiscordVoice(connection, false);
                    state = voice.connected
                            ? getString(R.string.console_discord_voice_active, voice.channelName)
                            : getString(R.string.console_discord_connected);
                    color = voice.connected ? 0xFF69F0AE : 0xFF8ED7FF;
                }
            } catch (IOException | RuntimeException error) {
                state = getString(R.string.console_discord_error);
            }
            int finalColor = color;
            String finalState = state;
            mainHandler.post(() -> {
                if (request == discordStatusGeneration.get()) {
                    applyDiscordIndicator(button, finalColor, finalState);
                }
            });
        });
    }

    private void applyDiscordIndicator(ImageButton button, int color, String state) {
        button.setColorFilter(color);
        button.setContentDescription(getString(R.string.console_discord_description, state));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) button.setTooltipText(state);
        GradientDrawable background = gradient(0x30242B3D, 0x50131825, 12);
        background.setStroke(dp(1), color);
        button.setBackground(background);
        button.setAlpha(1f);
    }

    private void showHostActions(ComputerDetails host) {
        boolean online = host.state == ComputerDetails.State.ONLINE;
        List<View> actions = new ArrayList<>();
        if (!host.uuid.equals(selectedHostUuid)) {
            TextView select = panelAction(getString(R.string.console_select_host));
            select.setOnClickListener(view -> {
                selectHost(host, true);
                hideSidePanel();
            });
            actions.add(select);
        }
        if (!online) {
            TextView wake = panelAction(getString(R.string.pcview_menu_send_wol));
            wake.setOnClickListener(view -> {
                hideSidePanel();
                wakeHost(host);
            });
            actions.add(wake);
        } else if (host.pairState != PairingManager.PairState.PAIRED) {
            TextView pair = panelAction(getString(R.string.pcview_menu_pair_pc));
            pair.setOnClickListener(view -> pairHost(host));
            actions.add(pair);
        } else {
            TextView unpair = panelAction(getString(R.string.pcview_menu_unpair_pc));
            unpair.setOnClickListener(view -> unpairHost(host));
            actions.add(unpair);
        }
        if (host.runningGameId != 0) {
            TextView resume = panelAction(getString(R.string.applist_menu_resume));
            resume.setOnClickListener(view -> {
                hideSidePanel();
                ServerHelper.doStart(this, new NvApp("app", host.runningGameId, false),
                        host, managerBinder);
            });
            TextView quit = panelAction(getString(R.string.applist_menu_quit));
            quit.setOnClickListener(view -> UiHelper.displayQuitConfirmationDialog(this, () -> {
                hideSidePanel();
                ServerHelper.doQuit(this, host, new NvApp("app", 0, false),
                        managerBinder, () -> {
                            if (appListPoller != null) appListPoller.pollNow();
                        });
            }, null));
            actions.add(resume);
            actions.add(quit);
        }
        if (online) {
            TextView sleep = panelAction(getString(R.string.console_sleep_host));
            sleep.setOnClickListener(view -> confirmSleepHost(host));
            actions.add(sleep);
        }
        TextView refresh = panelAction(getString(R.string.console_refresh_host));
        refresh.setOnClickListener(view -> {
            if (managerBinder != null) managerBinder.invalidateStateForComputer(host.uuid);
            if (host.uuid.equals(selectedHostUuid) && appListPoller != null) appListPoller.pollNow();
            hideSidePanel();
        });
        TextView network = panelAction(getString(R.string.pcview_menu_test_network));
        network.setOnClickListener(view -> ServerHelper.doNetworkTest(this));
        TextView details = panelAction(getString(R.string.pcview_menu_details));
        details.setOnClickListener(view -> Dialog.displayDialog(this,
                getString(R.string.title_details), host.toString(), false));
        TextView remove = panelAction(getString(R.string.pcview_menu_delete_pc));
        remove.setTextColor(0xFFFF8A80);
        remove.setOnClickListener(view -> confirmRemoveHost(host));
        actions.add(refresh);
        actions.add(network);
        actions.add(details);
        actions.add(remove);
        showSidePanel(getString(R.string.console_host_eyebrow), host.name,
                getString(online ? R.string.console_host_online_details
                        : R.string.console_host_offline_details),
                actions.toArray(new View[0]));
    }

    private void pairHost(ComputerDetails host) {
        if (host.state != ComputerDetails.State.ONLINE || host.activeAddress == null) {
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return;
        }
        hideSidePanel();
        Toast.makeText(this, R.string.pairing, Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            String message = null;
            boolean success = false;
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(host),
                        host.httpsPort, managerBinder.getUniqueId(), host.serverCert,
                        PlatformBinding.getCryptoProvider(this));
                if (http.getPairState() == PairingManager.PairState.PAIRED) {
                    success = true;
                } else {
                    String pin = PairingManager.generatePinString();
                    Dialog.displayDialog(this, getString(R.string.pair_pairing_title),
                            getString(R.string.pair_pairing_msg) + " " + pin + "\n\n" +
                                    getString(R.string.pair_pairing_help), false);
                    PairingManager pairing = http.getPairingManager();
                    PairingManager.PairState state = pairing.pair(http.getServerInfo(true), pin);
                    if (state == PairingManager.PairState.PAIRED) {
                        ComputerDetails managed = managerBinder.getComputer(host.uuid);
                        if (managed != null) managed.serverCert = pairing.getPairedCert();
                        managerBinder.invalidateStateForComputer(host.uuid);
                        success = true;
                    } else if (state == PairingManager.PairState.PIN_WRONG) {
                        message = getString(R.string.pair_incorrect_pin);
                    } else if (state == PairingManager.PairState.ALREADY_IN_PROGRESS) {
                        message = getString(R.string.pair_already_in_progress);
                    } else {
                        message = getString(host.runningGameId != 0
                                ? R.string.pair_pc_ingame : R.string.pair_fail);
                    }
                }
            } catch (UnknownHostException error) {
                message = getString(R.string.error_unknown_host);
            } catch (FileNotFoundException error) {
                message = getString(R.string.error_404);
            } catch (IOException | XmlPullParserException error) {
                message = error.getMessage();
            }
            Dialog.closeDialogs();
            String result = message;
            boolean paired = success;
            mainHandler.post(() -> {
                if (result != null && !result.isEmpty()) {
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                }
                if (paired) {
                    Toast.makeText(this, R.string.console_pair_success, Toast.LENGTH_SHORT).show();
                    managerBinder.invalidateStateForComputer(host.uuid);
                    selectHost(host, true);
                }
            });
        });
    }

    private void unpairHost(ComputerDetails host) {
        if (host.state != ComputerDetails.State.ONLINE || host.activeAddress == null) {
            Toast.makeText(this, R.string.error_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return;
        }
        hideSidePanel();
        Toast.makeText(this, R.string.unpairing, Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            String message;
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(host),
                        host.httpsPort, managerBinder.getUniqueId(), host.serverCert,
                        PlatformBinding.getCryptoProvider(this));
                if (http.getPairState() != PairingManager.PairState.PAIRED) {
                    message = getString(R.string.unpair_error);
                } else {
                    http.unpair();
                    message = getString(http.getPairState() == PairingManager.PairState.NOT_PAIRED
                            ? R.string.unpair_success : R.string.unpair_fail);
                    managerBinder.invalidateStateForComputer(host.uuid);
                }
            } catch (UnknownHostException error) {
                message = getString(R.string.error_unknown_host);
            } catch (FileNotFoundException error) {
                message = getString(R.string.error_404);
            } catch (IOException | XmlPullParserException error) {
                message = error.getMessage();
            }
            String result = message;
            mainHandler.post(() -> Toast.makeText(this,
                    result == null || result.isEmpty() ? getString(R.string.unpair_fail) : result,
                    Toast.LENGTH_LONG).show());
        });
    }

    private void confirmSleepHost(ComputerDetails host) {
        String activeAddress = host.activeAddress != null ? host.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, activeAddress);
        if (connection == null) {
            Toast.makeText(this, R.string.console_gateway_pair_required,
                    Toast.LENGTH_LONG).show();
            return;
        }
        TextView cancel = panelAction(getString(R.string.console_cancel));
        TextView sleep = panelAction(getString(R.string.console_sleep));
        sleep.setTextColor(0xFFFFB74D);
        cancel.setOnClickListener(view -> showHostActions(host));
        sleep.setOnClickListener(view -> {
            hideSidePanel();
            requestHostSleep(host, connection);
        });
        showSidePanel(getString(R.string.console_host_power),
                getString(R.string.console_sleep_title, host.name),
                getString(R.string.console_sleep_details),
                cancel, sleep);
    }

    private void requestHostSleep(ComputerDetails host,
                                  HostGatewayClient.Connection connection) {
        Toast.makeText(this, getString(R.string.console_sleep_request, host.name),
                Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                hostGatewayClient.sleepHost(connection);
                mainHandler.post(() -> Toast.makeText(this,
                        getString(R.string.console_sleep_accepted, host.name),
                        Toast.LENGTH_LONG).show());
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> Toast.makeText(this,
                        getString(R.string.console_sleep_failed, host.name),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void wakeHost(ComputerDetails host) {
        if (host.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(this, R.string.wol_pc_online, Toast.LENGTH_SHORT).show();
            return;
        }
        if (host.macAddress == null) {
            Toast.makeText(this, R.string.wol_no_mac, Toast.LENGTH_LONG).show();
            return;
        }
        executor.execute(() -> {
            int message;
            try {
                WakeOnLanSender.sendWolPacket(host);
                message = R.string.wol_waking_msg;
            } catch (IOException error) {
                message = R.string.wol_fail;
            }
            int toastMessage = message;
            mainHandler.post(() -> Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show());
        });
    }

    private void confirmRemoveHost(ComputerDetails host) {
        TextView cancel = panelAction(getString(R.string.console_cancel));
        TextView remove = panelAction(getString(R.string.console_remove));
        remove.setTextColor(0xFFFF8A80);
        cancel.setOnClickListener(view -> showHostActions(host));
        remove.setOnClickListener(view -> {
            hideSidePanel();
            removeHost(host);
        });
        showSidePanel(getString(R.string.console_host_eyebrow),
                getString(R.string.console_remove_host_title, host.name),
                getString(R.string.console_remove_host_details),
                cancel, remove);
    }

    private void removeHost(ComputerDetails host) {
        if (managerBinder == null) {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return;
        }
        managerBinder.removeComputer(host);
        assetLoader.deleteAssetsForComputer(host.uuid);
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit().remove(host.uuid).apply();
        new ShortcutHelper(this).disableComputerShortcut(
                host, getString(R.string.scut_deleted_pc));
        hostGatewayStore.remove(host.uuid);
        hosts.remove(host.uuid);

        if (host.uuid.equals(selectedHostUuid)) {
            stopAppListPoller();
            clearArtwork();
            selectedHostUuid = hosts.isEmpty() ? null : hosts.keySet().iterator().next();
            SharedPreferences.Editor editor = preferences.edit();
            if (selectedHostUuid == null) editor.remove("selected_host");
            else editor.putString("selected_host", selectedHostUuid);
            editor.apply();
            appRow.removeAllViews();
            if (selectedHostUuid == null) {
                appsLabel.setText(R.string.console_apps);
                appRow.addView(text(getString(R.string.console_choose_host),
                                15, 0xFFBDC4D8, false),
                        new LinearLayout.LayoutParams(dp(500),
                                ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                ComputerDetails selected = hosts.get(selectedHostUuid);
                appsLabel.setText(getString(R.string.console_apps_host,
                        selected.name.toUpperCase(Locale.ROOT)));
                startAppListPoller(selected);
                renderAppsAsync(selected);
            }
        }
        renderHosts();
        Toast.makeText(this, getString(R.string.console_host_removed, host.name),
                Toast.LENGTH_LONG).show();
    }

    private void selectHost(ComputerDetails host, boolean focusApps) {
        selectedHostUuid = host.uuid;
        preferences.edit().putString("selected_host", host.uuid).apply();
        newlyDiscoveredHosts.remove(host.uuid);
        clearArtwork();
        appsLabel.setText(getString(R.string.console_apps_host,
                host.name.toUpperCase(Locale.ROOT)));
        updateHostSelector();
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
        return loadApps(host, false);
    }

    private List<NvApp> loadApps(ComputerDetails host, boolean includeHidden) {
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
            if (!includeHidden && !showHiddenApps) {
                Set<String> hidden = getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                        .getStringSet(host.uuid, Collections.emptySet());
                apps.removeIf(app -> hidden.contains(String.valueOf(app.getAppId())));
            }
            apps.sort(Comparator.comparing(NvApp::getAppName, String.CASE_INSENSITIVE_ORDER));
            return apps;
        } catch (IOException | XmlPullParserException error) {
            return Collections.emptyList();
        }
    }

    private void renderApps(ComputerDetails host, List<NvApp> apps) {
        int restored = preferences.getInt("app_scroll." + host.uuid, 0);
        Object focusedTag = getCurrentFocus() != null ? getCurrentFocus().getTag() : null;
        boolean appHadFocus = focusedTag instanceof String
                && ((String) focusedTag).startsWith("app:" + host.uuid + ":");
        if (!(focusedTag instanceof String) || !((String) focusedTag).startsWith("app:" + host.uuid + ":")) {
            focusedTag = preferences.getString("selected_app." + host.uuid, null);
        }
        appRow.removeAllViews();
        appsLabel.setText(getString(R.string.console_apps_host, host.name.toUpperCase(Locale.ROOT)));
        if (apps.isEmpty()) {
            appRow.addView(text(showHiddenApps ? getString(R.string.console_apps_empty)
                            : getString(R.string.console_apps_empty_or_hidden),
                    15, 0xFFFFB74D, false), new LinearLayout.LayoutParams(
                    dp(700), ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            for (NvApp app : apps) appRow.addView(appCard(host, app), cardSpacing());
        }
        appScroll.post(() -> appScroll.scrollTo(restored, 0));
        List<String> availableTags = new ArrayList<>();
        for (int index = 0; index < appRow.getChildCount(); index++) {
            Object tag = appRow.getChildAt(index).getTag();
            if (tag instanceof String) availableTags.add((String) tag);
        }
        String selection = ConsoleDashboardState.restoreSelection(
                focusedTag instanceof String ? (String) focusedTag : null, availableTags);
        if (selection != null && (appHadFocus || getCurrentFocus() == null)) {
            restoreTaggedFocus(appRow, selection);
        }
        wireHomeFocusNavigation();
    }

    private View appCard(ComputerDetails host, NvApp app) {
        LinearLayout card = cardBase(dp(340), dp(126));
        card.setTag("app:" + host.uuid + ":" + app.getAppId());
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setBackground(gradient(0xFF302255, 0xFF142A46, 9));
        card.addView(poster, new LinearLayout.LayoutParams(dp(64), dp(96)));
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
        boolean running = host.runningGameId == app.getAppId();
        boolean customSettings = !AppPreferences.getAppSettings(this,
                host.uuid + ":" + app.getAppId()).useGlobalSettings;
        boolean quickLaunch = isQuickLaunch(host.uuid, app.getAppId());
        String metadata = running ? getString(R.string.console_app_running)
                : playedAt > 0 ? getString(R.string.console_last_played,
                formatRelative(System.currentTimeMillis() - playedAt).toUpperCase(Locale.ROOT))
                : getString(R.string.console_app_ready);
        if (customSettings) metadata += getString(R.string.console_app_custom_settings_suffix);
        if (quickLaunch) metadata += getString(R.string.console_app_quick_suffix);
        TextView metadataView = text(metadata, 10, 0xFFAAAFC2, true);
        LinearLayout.LayoutParams metadataParams = wrapLinear();
        metadataParams.topMargin = dp(5);
        copy.addView(metadataView, metadataParams);
        TextView action = text(running ? getString(R.string.console_resume)
                : getString(R.string.console_play), 12, 0xFF73D7FF, true);
        action.setAlpha(0f);
        LinearLayout.LayoutParams actionParams = wrapLinear();
        actionParams.topMargin = dp(5);
        copy.addView(action, actionParams);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        copyParams.leftMargin = dp(14);
        card.addView(copy, copyParams);
        TextView more = compactButton(getString(R.string.console_more));
        more.setContentDescription(getString(R.string.console_app_options, app.getAppName()));
        more.setOnClickListener(view -> showAppActions(host, app, poster));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        moreParams.leftMargin = dp(8);
        card.addView(more, moreParams);
        card.setOnClickListener(v -> launchOrConfirm(host, app));
        card.setOnLongClickListener(view -> {
            showAppActions(host, app, poster);
            return true;
        });
        card.setContentDescription(getString(R.string.console_app_description,
                app.getAppName(), metadata, running ? getString(R.string.console_resume)
                        : getString(R.string.console_play)));
        card.setOnFocusChangeListener((v, focused) -> {
            styleCard(card, focused);
            if (reducedMotion) action.setAlpha(focused ? 1f : 0f);
            else action.animate().alpha(focused ? 1f : 0f).setDuration(120).start();
            if (focused) {
                preferences.edit().putString("selected_app." + host.uuid,
                        String.valueOf(card.getTag())).apply();
                smoothCenterOn(appScroll, card);
                showArtwork(artwork, poster.getDrawable());
            }
        });
        return card;
    }

    private boolean isQuickLaunch(String hostUuid, int appId) {
        for (QuickLaunchManager.QuickLaunchItem item : quickLaunchManager.getAllQuickLaunchItems()) {
            if (item.computerUuid.equals(hostUuid) && item.appId == appId) return true;
        }
        return false;
    }

    private void launchOrConfirm(ComputerDetails host, NvApp app) {
        if (host.runningGameId != 0 && host.runningGameId != app.getAppId()) {
            UiHelper.displayQuitConfirmationDialog(this, () -> beginLaunch(host, app), null);
        } else {
            beginLaunch(host, app);
        }
    }

    private void showAppActions(ComputerDetails host, NvApp app, ImageView poster) {
        List<View> actions = new ArrayList<>();
        TextView start = panelAction(host.runningGameId == app.getAppId()
                ? getString(R.string.applist_menu_resume)
                : host.runningGameId != 0 ? getString(R.string.applist_menu_quit_and_start)
                : getString(R.string.console_play));
        start.setOnClickListener(view -> {
            hideSidePanel();
            launchOrConfirm(host, app);
        });
        actions.add(start);
        if (host.runningGameId == app.getAppId()) {
            TextView quit = panelAction(getString(R.string.applist_menu_quit));
            quit.setOnClickListener(view -> UiHelper.displayQuitConfirmationDialog(this, () -> {
                hideSidePanel();
                ServerHelper.doQuit(this, host, app, managerBinder,
                        () -> { if (appListPoller != null) appListPoller.pollNow(); });
            }, null));
            actions.add(quit);
        }
        TextView settings = panelAction(getString(R.string.console_app_stream_settings));
        settings.setOnClickListener(view -> {
            Intent intent = new Intent(this, AppStreamSettings.class);
            intent.putExtra(AppStreamSettings.EXTRA_APP_KEY, host.uuid + ":" + app.getAppId());
            intent.putExtra(AppStreamSettings.EXTRA_APP_NAME, app.getAppName());
            startActivity(intent);
        });
        actions.add(settings);

        Set<String> hidden = new HashSet<>(getSharedPreferences(
                AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .getStringSet(host.uuid, Collections.emptySet()));
        boolean isHidden = hidden.contains(String.valueOf(app.getAppId()));
        TextView hide = panelAction(getString(isHidden
                ? R.string.console_show_app : R.string.applist_menu_hide_app));
        hide.setEnabled(host.runningGameId != app.getAppId() || isHidden);
        hide.setOnClickListener(view -> {
            if (isHidden) hidden.remove(String.valueOf(app.getAppId()));
            else hidden.add(String.valueOf(app.getAppId()));
            getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                    .edit().putStringSet(host.uuid, hidden).apply();
            showHiddenApps = isHidden || showHiddenApps;
            hideSidePanel();
            renderAppsAsync(host, true);
        });
        actions.add(hide);

        TextView quick = panelAction(getString(R.string.applist_menu_add_quick_launch));
        quick.setEnabled(!isQuickLaunch(host.uuid, app.getAppId()));
        quick.setOnClickListener(view -> {
            quickLaunchManager.addQuickLaunchItem(host, app);
            Toast.makeText(this, R.string.quick_launch_added, Toast.LENGTH_SHORT).show();
            showAppActions(host, app, poster);
        });
        actions.add(quick);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && poster.getDrawable() instanceof BitmapDrawable) {
            TextView shortcut = panelAction(getString(R.string.applist_menu_scut));
            shortcut.setOnClickListener(view -> {
                Bitmap bitmap = ((BitmapDrawable) poster.getDrawable()).getBitmap();
                if (!shortcutHelper.createPinnedGameShortcut(host, app, bitmap)) {
                    Toast.makeText(this, R.string.unable_to_pin_shortcut, Toast.LENGTH_LONG).show();
                }
            });
            actions.add(shortcut);
        }
        TextView details = panelAction(getString(R.string.applist_menu_details));
        details.setOnClickListener(view -> Dialog.displayDialog(this,
                getString(R.string.title_details), app.toString(), false));
        actions.add(details);
        showSidePanel(getString(R.string.console_apps_eyebrow), app.getAppName(),
                getString(R.string.console_app_actions_details), actions.toArray(new View[0]));
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
            Toast.makeText(this, R.string.console_initializing, Toast.LENGTH_SHORT).show();
            return;
        }
        int token = launchGeneration.incrementAndGet();
        showLoading(host.name);
        executor.execute(() -> {
            ComputerDetails ready = HostReadiness.await(
                    () -> managerBinder != null ? managerBinder.getComputer(host.uuid) : null,
                    host,
                    () -> token != launchGeneration.get() || !active,
                    message -> setLoadingStatus(token, message),
                    getString(R.string.console_wol_status, host.name),
                    getString(R.string.console_waiting_stream_ports));
            mainHandler.post(() -> {
                if (token != launchGeneration.get() || !active) return;
                if (ready == null) {
                    loadingMessage.setText(R.string.console_host_not_ready);
                    loadingMessage.setTextColor(0xFFFF8A80);
                    loadingStatus.setText(R.string.console_host_timeout);
                    return;
                }
                preferences.edit()
                        .putLong(appHistoryKey(host.uuid, app.getAppId()), System.currentTimeMillis())
                        .putLong("host_played." + host.uuid, System.currentTimeMillis())
                        .apply();
                loadingStatus.setText(R.string.console_opening_stream);
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
        loadingMessage.setText(loadingMessages[0]);
        loadingStatus.setText(getString(R.string.console_preparing_host, hostName));
        loadingBackdrop.start();
        loadingMessage.setTag(0);
        mainHandler.postDelayed(rotateLoadingMessage, 2300L);
    }

    private final Runnable rotateLoadingMessage = new Runnable() {
        @Override public void run() {
            if (loadingLayer.getVisibility() != View.VISIBLE) return;
            int index = loadingMessage.getTag() instanceof Integer ? (Integer) loadingMessage.getTag() : 0;
            index = (index + 1) % loadingMessages.length;
            loadingMessage.setTag(index);
            loadingMessage.setText(loadingMessages[index]);
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
            int batteryStatus = BatteryState.STATUS_UNKNOWN;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BatteryState battery = device.getBatteryState();
                if (battery.isPresent()) {
                    if (!Float.isNaN(battery.getCapacity())) {
                        percentage = Math.max(0, Math.min(100,
                                Math.round(battery.getCapacity() * 100f)));
                    }
                    batteryStatus = battery.getStatus();
                }
            }
            controllers.add(new ControllerInfo(id, name, percentage, batteryStatus));
        }
        controllers.sort(Comparator.comparing(controller -> controller.name,
                String.CASE_INSENSITIVE_ORDER));
        return controllers;
    }

    private void renderControllers(List<ControllerInfo> controllers) {
        int scroll = controllerScroll.getScrollX();
        Object focusedTag = getCurrentFocus() != null ? getCurrentFocus().getTag() : null;
        controllerRow.removeAllViews();
        controllersLabel.setText(controllers.isEmpty()
                ? getString(R.string.console_controllers_none)
                : getResources().getQuantityString(R.plurals.console_controller_count,
                        controllers.size(), controllers.size()));
        int player = 1;
        for (ControllerInfo controller : controllers) {
            controllerRow.addView(controllerCard(player++, controller), cardSpacing());
        }
        controllerScroll.post(() -> controllerScroll.scrollTo(scroll != 0 ? scroll
                : preferences.getInt("controller_scroll", 0), 0));
        restoreTaggedFocus(controllerRow, focusedTag);
        wireHomeFocusNavigation();
    }

    private View controllerCard(int player, ControllerInfo controller) {
        LinearLayout card = cardBase(dp(250), dp(54));
        card.setTag("controller:" + controller.deviceId);
        int batteryColor = controller.percentage < 0 ? 0xFFB3B8C8
                : controller.isCharging() ? 0xFF64B5F6
                : controller.percentage <= 10 ? 0xFFFF5252
                : controller.percentage <= 30 ? 0xFFFFB74D : 0xFF69F0AE;
        ImageView batteryIcon = new ImageView(this);
        batteryIcon.setImageResource(controller.isCharging()
                ? R.drawable.ic_overlay_battery_charging : R.drawable.ic_overlay_battery);
        batteryIcon.setColorFilter(batteryColor);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconParams.rightMargin = dp(10);
        card.addView(batteryIcon, iconParams);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(getString(R.string.console_controller_player_name,
                        player, compactControllerName(controller.name)),
                14, Color.WHITE, true);
        name.setSingleLine(true);
        copy.addView(name, wrapLinear());
        String battery;
        if (controller.percentage < 0) {
            battery = getString(R.string.console_controller_battery_unknown);
        } else if (controller.isFull()) {
            battery = getString(R.string.console_controller_battery_full, controller.percentage);
        } else if (controller.isCharging()) {
            battery = getString(R.string.console_controller_battery_charging, controller.percentage);
        } else {
            battery = getString(R.string.console_controller_battery_level, controller.percentage);
        }
        batteryIcon.setContentDescription(battery);
        TextView level = text(battery, 11, batteryColor, true);
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
        TextView sounds = panelAction(getString(R.string.console_ui_sounds,
                getString(uiSoundsEnabled ? R.string.console_on : R.string.console_off)));
        TextView motion = panelAction(getString(R.string.console_reduced_motion,
                getString(reducedMotion ? R.string.console_on : R.string.console_off)));
        TextView integrations = panelAction(getString(R.string.console_host_integrations));
        TextView settings = panelAction(getString(R.string.console_streaming_settings));
        TextView classic = panelAction(getString(R.string.console_classic_ui));
        sounds.setOnClickListener(v -> {
            uiSoundsEnabled = !uiSoundsEnabled;
            preferences.edit().putBoolean("ui_sounds", uiSoundsEnabled).apply();
            sounds.setText(getString(R.string.console_ui_sounds,
                    getString(uiSoundsEnabled ? R.string.console_on : R.string.console_off)));
        });
        motion.setOnClickListener(v -> {
            reducedMotion = !reducedMotion;
            preferences.edit().putBoolean("reduced_motion", reducedMotion).apply();
            motion.setText(getString(R.string.console_reduced_motion,
                    getString(reducedMotion ? R.string.console_on : R.string.console_off)));
        });
        integrations.setOnClickListener(v -> showHostIntegrations());
        settings.setOnClickListener(v -> startActivity(new Intent(this, StreamSettings.class)));
        classic.setOnClickListener(v -> startActivity(new Intent(this, PcView.class)));
        showSidePanel(getString(R.string.console_title), getString(R.string.console_options_title),
                getString(R.string.console_options_details),
                sounds, motion, integrations, settings, classic);
    }

    private void showExitConfirmation() {
        TextView cancel = panelAction(getString(R.string.console_cancel));
        TextView exit = panelAction(getString(R.string.console_exit));
        exit.setTextColor(0xFFFF8A80);
        cancel.setOnClickListener(view -> hideSidePanel());
        exit.setOnClickListener(view -> {
            hideSidePanel();
            finishAndRemoveTask();
        });
        showSidePanel(getString(R.string.console_title), getString(R.string.console_close_title),
                getString(R.string.console_close_details), cancel, exit);
    }

    private void showHostIntegrations() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) {
            Toast.makeText(this, R.string.console_select_host_first, Toast.LENGTH_LONG).show();
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
        TextView hint = text(getString(R.string.console_back_close), 11, 0x8FFFFFFF, true);
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
        action.setId(View.generateViewId());
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
        card.setId(View.generateViewId());
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

    private void styleCompactButton(View button, boolean focused) {
        int top = focused ? blendColor(0xFF58478F, glassAccent, .38f) : 0x32242B3D;
        int bottom = focused ? blendColor(0xFF342B59, glassAccent, .22f) : 0x65131825;
        GradientDrawable background = gradient(top, bottom, 10);
        background.setStroke(dp(focused ? 2 : 1), focused
                ? getResources().getColor(R.color.console_focus_stroke) : 0x587C89B2);
        button.setBackground(background);
        button.setElevation(dp(focused ? 7 : 2));
    }

    private void animateScale(View view, float scale) {
        view.animate().cancel();
        if (reducedMotion || !view.isLaidOut()) {
            view.setScaleX(scale); view.setScaleY(scale);
        } else {
            view.animate().scaleX(scale).scaleY(scale).setDuration(
                    getResources().getInteger(R.integer.console_motion_focus_ms)).start();
        }
    }

    private void saveScrollPositions() {
        SharedPreferences.Editor editor = preferences.edit()
                .putInt("controller_scroll", controllerScroll != null ? controllerScroll.getScrollX() : 0);
        if (selectedHostUuid != null && appScroll != null) {
            editor.putInt("app_scroll." + selectedHostUuid, appScroll.getScrollX());
        }
        editor.apply();
    }

    private void wireHomeFocusNavigation() {
        if (optionsButton == null) return;
        View quick = firstFocusableChild(quickActions);
        View controller = firstFocusableChild(controllerRow);
        View app = firstFocusableChild(appRow);

        View belowOptions = quick != null ? quick : controller != null ? controller : app;
        if (belowOptions != null) optionsButton.setNextFocusDownId(belowOptions.getId());

        if (quickActions != null) {
            int down = controller != null ? controller.getId()
                    : app != null ? app.getId() : View.NO_ID;
            for (int index = 0; index < quickActions.getChildCount(); index++) {
                View child = quickActions.getChildAt(index);
                if (!child.isFocusable()) continue;
                child.setNextFocusUpId(optionsButton.getId());
                if (down != View.NO_ID) child.setNextFocusDownId(down);
            }
        }

        if (controller != null) {
            controller.setNextFocusUpId(quick != null ? quick.getId() : optionsButton.getId());
            if (app != null) controller.setNextFocusDownId(app.getId());
        }

        if (appRow != null) {
            int up = controller != null ? controller.getId()
                    : quick != null ? quick.getId() : optionsButton.getId();
            for (int index = 0; index < appRow.getChildCount(); index++) {
                View child = appRow.getChildAt(index);
                if (child.isFocusable()) child.setNextFocusUpId(up);
            }
        }
    }

    private View firstFocusableChild(ViewGroup row) {
        if (row == null) return null;
        for (int index = 0; index < row.getChildCount(); index++) {
            View child = row.getChildAt(index);
            if (child.isFocusable() && child.getVisibility() == View.VISIBLE) return child;
        }
        return null;
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
        final int batteryStatus;

        ControllerInfo(int deviceId, String name, int percentage, int batteryStatus) {
            this.deviceId = deviceId;
            this.name = name != null ? name : "Controller";
            this.percentage = percentage;
            this.batteryStatus = batteryStatus;
        }

        boolean isCharging() { return batteryStatus == BatteryState.STATUS_CHARGING; }
        boolean isFull() { return batteryStatus == BatteryState.STATUS_FULL; }
    }

    private enum BluetoothAction { POWER_OFF, UNPAIR }
}
