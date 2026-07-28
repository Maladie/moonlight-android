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
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
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
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import com.limelight.ui.ConsoleStreamLoadingView;
import com.limelight.console.transition.LaunchTransitionSpec;
import com.limelight.console.transition.LaunchTransitionType;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Deque;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** TV-first dashboard adapted from Wake & Play and backed by Moonlight's internal APIs. */
public final class ConsoleActivity extends Activity implements InputManager.InputDeviceListener {
    private static final String PREFS = "console_dashboard";
    private static final int REQUEST_BLUETOOTH_CONNECT = 2201;
    private static final long CONTROLLER_REFRESH_MS = 30_000L;
    private static final long DISCOVERY_INTERVAL_MS = 60_000L;
    private static final long DISCOVERY_VISIBLE_MS = 4_000L;
    private static final long PLAYNITE_REFRESH_MS = 60_000L;
    private static final long VIBEPOLLO_APP_WAIT_MS = 5 * 60_000L;
    private static final long VIBEPOLLO_ENSURE_RETRY_MS = 3_000L;
    private static final long VIBEPOLLO_APP_STABLE_MS = 30_000L;
    private static final int VIBEPOLLO_APP_STABLE_POLLS = 5;
    private static final long PREVIOUS_SESSION_CLOSE_TIMEOUT_MS = 20_000L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final ExecutorService playniteExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService playniteArtworkExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger launchGeneration = new AtomicInteger();
    private final AtomicInteger artworkGeneration = new AtomicInteger();
    private final AtomicInteger discordStatusGeneration = new AtomicInteger();
    private final AtomicInteger playniteGeneration = new AtomicInteger();
    private final AtomicInteger playniteArtworkGeneration = new AtomicInteger();
    private final AtomicInteger sunshineAppsGeneration = new AtomicInteger();
    private final Map<String, ComputerDetails> hosts = new LinkedHashMap<>();
    private final Set<String> newlyDiscoveredHosts = new LinkedHashSet<>();

    private SharedPreferences preferences;
    private DiskAssetLoader assetLoader;
    private AudioManager audioManager;
    private InputManager inputManager;
    private DiscordPanelController discordPanelController;
    private HostGatewayClient hostGatewayClient;
    private HostGatewayStore hostGatewayStore;
    private PlayniteLibraryRepository playniteLibraryRepository;
    private PlayniteArtworkCache playniteArtworkCache;
    private PlayniteLaunchTargetStore playniteLaunchTargetStore;
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

    private FrameLayout root;
    private FrameLayout homeLayer;
    private LinearLayout homeContent;
    private FrameLayout loadingLayer;
    private ConsoleStreamLoadingView streamLoadingView;
    private long streamLoadingEpoch;
    private FrameLayout modalLayer;
    private LinearLayout sidePanel;
    private ScrollView sidePanelScroll;
    private android.app.Dialog sideDialog;
    private final Deque<PanelSnapshot> panelHistory = new ArrayDeque<>();
    private String currentPanelKey;
    private View sidePanelBusyBanner;
    private boolean sidePanelTransient;
    private ImageView artworkBackdrop;
    private ImageView artworkHero;
    private View artworkScrim;
    private TextView controllersLabel;
    private TextView appsLabel;
    private TextView optionsButton;
    private TextView hostSelector;
    private TextView discoveryStatus;
    private TextView launchPlayniteButton;
    private TextView installedFilterButton;
    private TextView playniteLibraryStatus;
    private ProgressBar discoverySpinner;
    private TextView quickResumeButton;
    private LinearLayout quickActions;
    private ImageButton discordActionButton;
    private int discordIndicatorColor = 0xFF9FAAB2;
    private LinearLayout controllerRow;
    private LinearLayout appRow;
    private HorizontalScrollView controllerScroll;
    private HorizontalScrollView appScroll;
    private ScrollView appVerticalScroll;
    private boolean portraitLayout;
    private String selectedHostUuid;
    private View lastContentFocus;
    private Object lastContentFocusTag;
    private ControllerInfo pendingController;
    private BluetoothAction pendingBluetoothAction;
    private int glassAccent = 0xFF73D7FF;
    private String renderedAppsSignature;
    private String renderedControllersSignature;
    private boolean discoveryActive;
    private Future<?> playniteRequest;
    private Future<?> playniteArtworkPrefetch;
    private String playniteArtworkPrefetchSignature;
    private List<PlayniteLibraryGame> currentPlayniteGames = Collections.emptyList();
    private List<PlayniteDashboardItem> renderedPlayniteItems = Collections.emptyList();
    private final Set<String> vibepolloEnsureInFlight =
            Collections.synchronizedSet(new HashSet<>());
    private List<NvApp> currentSunshineApps = Collections.emptyList();
    private boolean playniteLibraryCached;
    private boolean playniteLibraryRefreshing;
    private PlayniteLibraryRepository.ErrorKind playniteLibraryError;
    private String currentPlayniteHostUuid;

    private final Runnable playniteRefreshCycle = new Runnable() {
        @Override public void run() {
            if (!active || loadingLayer != null && loadingLayer.getVisibility() == View.VISIBLE) return;
            ComputerDetails host = hosts.get(selectedHostUuid);
            if (host != null) requestPlayniteRefresh(host, false);
        }
    };

    private final Runnable endDiscoveryIndicator = () -> {
        discoveryActive = false;
        updateHostSelector();
    };

    private final Runnable discoveryIndicatorCycle = new Runnable() {
        @Override public void run() {
            if (!active || !polling) return;
            discoveryActive = true;
            updateHostSelector();
            mainHandler.removeCallbacks(endDiscoveryIndicator);
            mainHandler.postDelayed(endDiscoveryIndicator, DISCOVERY_VISIBLE_MS);
            mainHandler.postDelayed(this, DISCOVERY_INTERVAL_MS);
        }
    };

    private final ComputerManagerListener computerListener = (details, fresh) -> {
        ComputerDetails copy = new ComputerDetails(details);
        mainHandler.post(() -> {
            ComputerDetails previous = hosts.get(copy.uuid);
            boolean hostChanged = previous == null
                    || previous.state != copy.state
                    || previous.pairState != copy.pairState
                    || previous.runningGameId != copy.runningGameId
                    || !Objects.equals(previous.activeAddress, copy.activeAddress);
            boolean hasStableAppList = copy.rawAppList != null && !copy.rawAppList.isEmpty();
            boolean appsChanged = hasStableAppList && (previous == null
                    || !Objects.equals(previous.rawAppList, copy.rawAppList)
                    || previous.runningGameId != copy.runningGameId
                    || previous.pairState != copy.pairState
                    || previous.state != copy.state);
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
            discoveryActive = false;
            mainHandler.removeCallbacks(discoveryIndicatorCycle);
            mainHandler.removeCallbacks(endDiscoveryIndicator);
            updateHostSelector();
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
        hostGatewayClient = new HostGatewayClient();
        hostGatewayStore = new HostGatewayStore(this);
        playniteLibraryRepository = new PlayniteLibraryRepository(hostGatewayClient,
                new PlayniteLibraryCache(this));
        playniteArtworkCache = new PlayniteArtworkCache(this);
        playniteLaunchTargetStore = new PlayniteLaunchTargetStore(this);
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

                    @Override public TextView back(String label) {
                        TextView action = panelAction(label);
                        action.setTag("panel.back");
                        action.setOnClickListener(view -> handlePanelBack());
                        return action;
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

                    @Override public void busy(String eyebrow, String title, String details) {
                        showSidePanelBusy(eyebrow, title, details);
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
        if (selected != null) {
            if (appListPoller == null) startAppListPoller(selected);
            if (!renderedPlayniteItems.isEmpty()) {
                schedulePlayniteArtworkPrefetch(selected, renderedPlayniteItems);
            }
            loadPlayniteForHost(selected);
        }
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
        mainHandler.removeCallbacks(discoveryIndicatorCycle);
        mainHandler.removeCallbacks(endDiscoveryIndicator);
        mainHandler.removeCallbacks(playniteRefreshCycle);
        cancelPlayniteRequest();
        cancelPlayniteArtworkPrefetch();
        discoveryActive = false;
        if (streamLoadingView != null) streamLoadingView.stop();
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
        if (sideDialog != null) sideDialog.dismiss();
        if (serviceBound) unbindService(serviceConnection);
        cancelPlayniteRequest();
        cancelPlayniteArtworkPrefetch();
        playniteExecutor.shutdownNow();
        playniteArtworkExecutor.shutdownNow();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (sideDialog != null && sideDialog.isShowing()) {
            handlePanelBack();
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
        mainHandler.removeCallbacks(discoveryIndicatorCycle);
        discoveryIndicatorCycle.run();
        ComputerDetails selected = hosts.get(selectedHostUuid);
        if (selected != null) startAppListPoller(selected);
    }

    private FrameLayout buildUi() {
        portraitLayout = getResources().getDisplayMetrics().heightPixels
                > getResources().getDisplayMetrics().widthPixels;
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

        homeContent = new LinearLayout(this);
        homeContent.setOrientation(LinearLayout.VERTICAL);
        homeContent.setPadding(dp(54), dp(28), dp(54), dp(24));
        homeContent.setClipChildren(false);
        homeContent.setClipToPadding(false);
        homeLayer.addView(homeContent, match());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(portraitLayout ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        header.setGravity(portraitLayout ? Gravity.START : Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(getString(R.string.console_title), 24, Color.WHITE, true);
        TextView subtitle = text(getString(R.string.console_subtitle),
                14, 0xFFBCC3DD, false);
        titleBlock.addView(title, wrapLinear());
        titleBlock.addView(subtitle, wrapLinear());
        header.addView(titleBlock, portraitLayout
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hostSelector = compactButton(getString(R.string.console_no_hosts));
        hostSelector.setContentDescription(getString(R.string.console_action_hosts));
        hostSelector.setOnClickListener(v -> showHostPanel());
        optionsButton = hostSelector;
        hostSelector.setSingleLine(true);
        hostSelector.setMaxWidth(dp(430));
        LinearLayout.LayoutParams selectorParams = new LinearLayout.LayoutParams(
                portraitLayout ? ViewGroup.LayoutParams.MATCH_PARENT
                        : ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        if (portraitLayout) selectorParams.topMargin = dp(12);
        header.addView(hostSelector, selectorParams);
        homeContent.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout quickLine = new LinearLayout(this);
        quickLine.setOrientation(portraitLayout ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        quickLine.setGravity(portraitLayout ? Gravity.START : Gravity.CENTER_VERTICAL);
        LinearLayout discoveryBlock = new LinearLayout(this);
        discoveryBlock.setOrientation(LinearLayout.HORIZONTAL);
        discoveryBlock.setGravity(Gravity.CENTER_VERTICAL);
        discoveryBlock.setFocusable(false);
        discoverySpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        discoverySpinner.setIndeterminate(true);
        discoverySpinner.setIndeterminateTintList(ColorStateList.valueOf(0xFF8DDCFF));
        discoverySpinner.setContentDescription(getString(R.string.console_discovering));
        discoverySpinner.setFocusable(false);
        discoverySpinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        spinnerParams.rightMargin = dp(6);
        discoveryBlock.addView(discoverySpinner, spinnerParams);
        discoveryStatus = text(getString(R.string.console_discovering), 10, 0xFF8F9AAF, false);
        discoveryStatus.setSingleLine(true);
        discoveryStatus.setEllipsize(TextUtils.TruncateAt.END);
        discoveryStatus.setGravity(Gravity.CENTER_VERTICAL);
        discoveryBlock.addView(discoveryStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams discoveryParams = sectionWithTop(2);
        discoveryParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        // Discovery updates must not reflow the dashboard when their translated
        // text or a newly-found-host badge is longer than usual.
        discoveryParams.height = dp(24);
        homeContent.addView(discoveryBlock, discoveryParams);

        LinearLayout discoveryAndSession = new LinearLayout(this);
        discoveryAndSession.setOrientation(LinearLayout.HORIZONTAL);
        discoveryAndSession.setGravity(Gravity.CENTER_VERTICAL);
        quickResumeButton = text(getString(R.string.console_quick_resume),
                12, 0xFFE8F6FF, true);
        quickResumeButton.setId(View.generateViewId());
        quickResumeButton.setTag("session.resume");
        quickResumeButton.setFocusable(true);
        quickResumeButton.setClickable(true);
        quickResumeButton.setGravity(Gravity.CENTER);
        quickResumeButton.setMinHeight(dp(42));
        quickResumeButton.setPadding(dp(13), dp(5), dp(13), dp(5));
        quickResumeButton.setVisibility(View.GONE);
        quickResumeButton.setOnClickListener(view -> resumeSelectedSession());
        quickResumeButton.setOnFocusChangeListener((view, focused) ->
                styleCompactButton(quickResumeButton, focused));
        styleCompactButton(quickResumeButton, false);
        LinearLayout.LayoutParams resumeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        discoveryAndSession.addView(quickResumeButton, resumeParams);
        launchPlayniteButton = text(getString(R.string.playnite_launch),
                12, 0xFFD7E4EA, true);
        launchPlayniteButton.setId(View.generateViewId());
        launchPlayniteButton.setTag("playnite.fullscreen");
        launchPlayniteButton.setFocusable(true);
        launchPlayniteButton.setClickable(true);
        launchPlayniteButton.setGravity(Gravity.CENTER);
        launchPlayniteButton.setMinWidth(dp(48));
        launchPlayniteButton.setMinHeight(dp(42));
        launchPlayniteButton.setPadding(dp(13), dp(5), dp(13), dp(5));
        launchPlayniteButton.setContentDescription(
                getString(R.string.playnite_launch_description));
        launchPlayniteButton.setOnClickListener(view -> launchPlayniteFullscreen());
        launchPlayniteButton.setOnLongClickListener(view -> {
            showPlayniteFullscreenTargetPicker();
            return true;
        });
        launchPlayniteButton.setOnFocusChangeListener((view, focused) ->
                styleCompactButton(launchPlayniteButton, focused));
        styleCompactButton(launchPlayniteButton, false);
        LinearLayout.LayoutParams playniteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        discoveryAndSession.addView(launchPlayniteButton, playniteParams);
        quickLine.addView(discoveryAndSession, portraitLayout
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        quickActions = horizontalRow();
        addQuickAction(globalAction("global.refresh", R.string.console_action_refresh,
                R.drawable.ic_console_refresh, this::refreshDashboard));
        addQuickAction(globalAction("global.add_host", R.string.console_action_add_host,
                R.drawable.ic_console_add, this::addHost));
        addQuickAction(globalAction("global.quick_launch", R.string.console_action_quick_launch,
                R.drawable.ic_console_play, this::showQuickLaunchPanel));
        addQuickAction(globalAction("global.settings", R.string.console_action_stream_settings,
                R.drawable.ic_console_settings, this::showOptionsPanel));
        addQuickAction(globalAction("global.overrides", R.string.console_action_overrides,
                R.drawable.ic_console_sliders, this::showOverridesPanel));
        addQuickAction(globalAction("global.auto_resume", R.string.console_action_auto_resume,
                R.drawable.ic_console_auto_resume, this::toggleAutoResume));
        discordActionButton = addQuickAction(globalAction("global.discord",
                R.string.console_action_discord, R.drawable.ic_console_discord, this::showHostIntegrations));
        addQuickAction(globalAction("global.help", R.string.console_action_help,
                R.drawable.ic_console_help, () -> HelpLauncher.launchSetupGuide(this)));
        if (portraitLayout) {
            HorizontalScrollView quickScroll = horizontalScroll();
            quickScroll.addView(quickActions, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
            quickLine.addView(quickScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        } else {
            quickLine.addView(quickActions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        }
        LinearLayout.LayoutParams quickLineParams = sectionWithTop(10);
        quickLineParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        homeContent.addView(quickLine, quickLineParams);

        controllersLabel = sectionLabel(getString(R.string.console_controllers_none));
        LinearLayout.LayoutParams section = wrapLinear();
        section.topMargin = dp(13);
        homeContent.addView(controllersLabel, section);
        controllerScroll = horizontalScroll();
        controllerRow = horizontalRow();
        controllerScroll.addView(controllerRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        homeContent.addView(controllerScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout libraryHeader = new LinearLayout(this);
        libraryHeader.setOrientation(LinearLayout.HORIZONTAL);
        libraryHeader.setGravity(Gravity.CENTER_VERTICAL);
        appsLabel = sectionLabel(getString(R.string.console_apps));
        libraryHeader.addView(appsLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        playniteLibraryStatus = text("", 11, 0xFF8790A8, false);
        playniteLibraryStatus.setPadding(dp(8), 0, dp(8), 0);
        libraryHeader.addView(playniteLibraryStatus, wrapLinear());
        installedFilterButton = text(getString(R.string.playnite_installed),
                11, 0xFFD7E4EA, true);
        installedFilterButton.setId(View.generateViewId());
        installedFilterButton.setTag("playnite.filter");
        installedFilterButton.setFocusable(true);
        installedFilterButton.setClickable(true);
        installedFilterButton.setGravity(Gravity.CENTER);
        installedFilterButton.setMinHeight(dp(48));
        installedFilterButton.setMinWidth(dp(48));
        installedFilterButton.setPadding(dp(12), dp(4), dp(12), dp(4));
        installedFilterButton.setOnClickListener(view -> togglePlayniteInstalledFilter());
        installedFilterButton.setOnFocusChangeListener((view, focused) ->
                styleInstalledFilter(focused));
        styleInstalledFilter(false);
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        filterParams.leftMargin = dp(8);
        libraryHeader.addView(installedFilterButton, filterParams);
        LinearLayout.LayoutParams libraryHeaderParams = sectionWithTop(2);
        libraryHeaderParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        homeContent.addView(libraryHeader, libraryHeaderParams);
        appRow = horizontalRow();
        if (portraitLayout) {
            appRow.setOrientation(LinearLayout.VERTICAL);
            appVerticalScroll = new ScrollView(this);
            appVerticalScroll.setVerticalScrollBarEnabled(false);
            appVerticalScroll.addView(appRow, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            homeContent.addView(appVerticalScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(235)));
        } else {
            appScroll = horizontalScroll();
            appScroll.addView(appRow, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            homeContent.addView(appScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(235)));
        }
        appRow.addView(text(getString(R.string.console_choose_host), 15, 0xFFBDC4D8, false),
                new LinearLayout.LayoutParams(dp(500), ViewGroup.LayoutParams.MATCH_PARENT));
        wireHomeFocusNavigation();

        container.addView(homeLayer, match());
        buildSidePanel();
        buildLoadingLayer(container);
        return container;
    }

    private void buildLoadingLayer(FrameLayout container) {
        loadingLayer = new FrameLayout(this);
        loadingLayer.setVisibility(View.GONE);
        container.addView(loadingLayer, match());
    }

    private void buildSidePanel() {
        modalLayer = new FrameLayout(this);
        modalLayer.setFocusable(false);
        modalLayer.setClickable(true);
        modalLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        modalLayer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View dim = new View(this);
        dim.setBackgroundColor(0xA005060A);
        dim.setClickable(true);
        dim.setOnClickListener(v -> hideSidePanel());
        modalLayer.addView(dim, match());
        sidePanelScroll = new ScrollView(this);
        sidePanelScroll.setFillViewport(true);
        sidePanelScroll.setVerticalScrollBarEnabled(false);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFC1A2028, 0xFC12171E, 0xFF090C10});
        background.setCornerRadii(new float[]{dp(24), dp(24), 0, 0, 0, 0, dp(24), dp(24)});
        background.setStroke(dp(1), 0x704A6677);
        sidePanelScroll.setBackground(background);
        sidePanelScroll.setElevation(dp(18));
        sidePanel = new LinearLayout(this);
        sidePanel.setOrientation(LinearLayout.VERTICAL);
        sidePanel.setPadding(dp(34), dp(26), dp(34), dp(20));
        sidePanelScroll.addView(sidePanel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        modalLayer.addView(sidePanelScroll, new FrameLayout.LayoutParams(dp(510),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        sideDialog = new android.app.Dialog(this);
        sideDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sideDialog.setContentView(modalLayer);
        sideDialog.setCanceledOnTouchOutside(false);
        sideDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                handlePanelBack();
                return true;
            }
            return false;
        });
        Window window = sideDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
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
                discoveryStatus.setTextColor(0xFFFFC36A);
                discoveryStatus.setBackground(gradient(0x20FFB74D, 0x12FFB74D, 8));
                discoveryStatus.setPadding(dp(8), dp(3), dp(8), dp(3));
                if (discoverySpinner != null) discoverySpinner.setVisibility(View.GONE);
            } else if (discoveryActive) {
                discoveryStatus.setText(getString(R.string.console_discovering));
                discoveryStatus.setTextColor(0xFFB8C9DC);
                discoveryStatus.setBackground(null);
                discoveryStatus.setPadding(0, 0, 0, 0);
                if (discoverySpinner != null) discoverySpinner.setVisibility(View.VISIBLE);
            } else {
                discoveryStatus.setText(getString(R.string.console_discovery_next_scan));
                discoveryStatus.setTextColor(0xFF8790A8);
                discoveryStatus.setBackground(null);
                discoveryStatus.setPadding(0, 0, 0, 0);
                if (discoverySpinner != null) discoverySpinner.setVisibility(View.GONE);
            }
        }
        updateQuickResumeButton(host);
        refreshDiscordIndicator();
    }

    private void updateQuickResumeButton(ComputerDetails host) {
        if (quickResumeButton == null) return;
        boolean visible = host != null && ConsoleActionCatalog.isOnline(host)
                && ConsoleActionCatalog.isPaired(host) && host.runningGameId != 0;
        boolean restoreFocus = quickResumeButton.hasFocus() && !visible;
        boolean moveFocusToResume = launchPlayniteButton != null
                && launchPlayniteButton.hasFocus() && visible;
        quickResumeButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        quickResumeButton.setEnabled(visible);
        if (launchPlayniteButton != null) {
            launchPlayniteButton.setVisibility(visible ? View.GONE : View.VISIBLE);
        }
        if (visible) {
            String appName = findAppName(host, host.runningGameId);
            quickResumeButton.setText(appName == null
                    ? getString(R.string.console_quick_resume)
                    : getString(R.string.console_quick_resume_app, appName));
            quickResumeButton.setContentDescription(getString(
                    R.string.console_quick_resume_description,
                    appName == null ? getString(R.string.console_status_active_session) : appName));
            if (moveFocusToResume) quickResumeButton.post(quickResumeButton::requestFocus);
        } else if (restoreFocus) {
            View fallback = firstFocusableChild(quickActions);
            View target = fallback != null ? fallback : hostSelector;
            target.post(target::requestFocus);
        }
    }

    private void resumeSelectedSession() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null || !ConsoleActionCatalog.isOnline(host)
                || !ConsoleActionCatalog.isPaired(host) || host.runningGameId == 0) {
            updateHostSelector();
            return;
        }
        resumeSession(host);
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
            String discovered = newlyDiscoveredHosts.contains(host.uuid)
                    ? getString(R.string.console_new_host_suffix) : "";
            TextView choose = panelAction(getString(R.string.console_host_choice,
                    host.name, hostStatus(host), selected + discovered));
            choose.setTag("host:" + host.uuid);
            choose.setAlpha(host.state == ComputerDetails.State.OFFLINE ? .62f : 1f);
            choose.setOnClickListener(view -> {
                newlyDiscoveredHosts.remove(host.uuid);
                if (ConsoleActionCatalog.isOnline(host) && ConsoleActionCatalog.isPaired(host)) {
                    selectHost(host, true);
                    hideSidePanel();
                } else {
                    showHostActions(host);
                }
            });
            actions.add(choose);
        }
        ComputerDetails selectedHost = hosts.get(selectedHostUuid);
        if (selectedHost != null) {
            TextView manage = panelAction(getString(R.string.console_manage_host, selectedHost.name));
            manage.setOnClickListener(view -> showHostActions(selectedHost));
            actions.add(manage);
        }
        TextView refresh = panelAction(getString(R.string.console_refresh));
        refresh.setOnClickListener(view -> {
            hideSidePanel();
            refreshDashboard();
        });
        TextView add = panelAction(getString(R.string.console_add_host));
        add.setOnClickListener(view -> addHost());
        actions.add(refresh);
        actions.add(add);
        showSidePanel(getString(R.string.console_hosts_eyebrow),
                getString(R.string.console_hosts_title),
                discoveryActive ? getString(R.string.console_hosts_scanning)
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
        ComputerDetails selected = hosts.get(selectedHostUuid);
        if (selected != null) requestPlayniteRefresh(selected, true);
        mainHandler.removeCallbacks(discoveryIndicatorCycle);
        mainHandler.removeCallbacks(endDiscoveryIndicator);
        discoveryIndicatorCycle.run();
        Toast.makeText(this, R.string.console_refresh_started, Toast.LENGTH_SHORT).show();
    }

    private void showQuickLaunchPanel() {
        List<QuickLaunchManager.QuickLaunchItem> items = quickLaunchManager.getAllQuickLaunchItems();
        List<View> actions = new ArrayList<>();
        TextView add = panelAction(getString(R.string.console_quick_launch_add));
        add.setTag("quick.add");
        add.setOnClickListener(view -> showQuickLaunchAddPanel());
        actions.add(add);
        if (items.isEmpty()) actions.add(label(getString(R.string.console_quick_launch_empty)));
        for (QuickLaunchManager.QuickLaunchItem item : items) {
            TextView action = panelAction(getString(R.string.console_quick_launch_item,
                    item.getDisplayName(), item.computerName));
            action.setOnClickListener(view -> launchQuickItem(item));
            action.setOnLongClickListener(view -> {
                showQuickLaunchItemActions(item);
                return true;
            });
            action.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP
                        && (keyCode == KeyEvent.KEYCODE_MENU
                        || keyCode == KeyEvent.KEYCODE_BUTTON_X)) {
                    showQuickLaunchItemActions(item);
                    return true;
                }
                return false;
            });
            actions.add(action);
        }
        showSidePanel(getString(R.string.console_quick_launch_eyebrow),
                getString(R.string.quick_launch_section_title),
                getString(R.string.console_quick_launch_details),
                actions.toArray(new View[0]));
    }

    private void showQuickLaunchAddPanel() {
        List<ComputerDetails> availableHosts = new ArrayList<>(hosts.values());
        availableHosts.sort(Comparator.comparing(host -> host.name, String.CASE_INSENSITIVE_ORDER));
        List<View> actions = new ArrayList<>();
        for (ComputerDetails host : availableHosts) {
            for (NvApp app : loadApps(host, true)) {
                if (isQuickLaunch(host.uuid, app.getAppId())) continue;
                TextView add = panelAction(getString(R.string.console_quick_launch_add_item,
                        app.getAppName(), host.name));
                add.setTag("quick.add:" + host.uuid + ":" + app.getAppId());
                add.setOnClickListener(view -> {
                    quickLaunchManager.addQuickLaunchItem(host, app);
                    renderedAppsSignature = null;
                    if (host.uuid.equals(selectedHostUuid)) renderAppsAsync(host);
                    Toast.makeText(this, getString(R.string.console_quick_launch_added,
                            app.getAppName()), Toast.LENGTH_SHORT).show();
                    showQuickLaunchPanel();
                });
                actions.add(add);
            }
        }
        if (actions.isEmpty()) {
            actions.add(label(getString(R.string.console_quick_launch_add_empty)));
        }
        showSidePanel(getString(R.string.console_quick_launch_eyebrow),
                getString(R.string.console_quick_launch_add_title),
                getString(R.string.console_quick_launch_add_details),
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
                launchQuickOrConfirm(host, app, item.key);
                return;
            }
        }
        Toast.makeText(this, R.string.console_quick_launch_app_missing, Toast.LENGTH_LONG).show();
    }

    private void showQuickLaunchItemActions(QuickLaunchManager.QuickLaunchItem item) {
        List<View> actions = new ArrayList<>();
        TextView launch = panelAction(getString(R.string.console_play));
        launch.setOnClickListener(view -> launchQuickItem(item));
        TextView settings = panelAction(getString(R.string.quick_launch_settings));
        settings.setOnClickListener(view -> {
            hideSidePanel();
            Intent intent = new Intent(this, AppStreamSettings.class);
            intent.putExtra(AppStreamSettings.EXTRA_APP_KEY, item.key);
            intent.putExtra(AppStreamSettings.EXTRA_APP_NAME,
                    "Quick Launch: " + item.getDisplayNameLong());
            startActivity(intent);
        });
        TextView rename = panelAction(getString(R.string.quick_launch_rename));
        rename.setOnClickListener(view -> renameQuickLaunchItem(item));
        actions.add(launch);
        actions.add(settings);
        actions.add(rename);
        List<QuickLaunchManager.QuickLaunchItem> items = quickLaunchManager.getAllQuickLaunchItems();
        int position = -1;
        for (int index = 0; index < items.size(); index++) {
            if (item.key.equals(items.get(index).key)) { position = index; break; }
        }
        if (position > 0) {
            TextView left = panelAction(getString(R.string.quick_launch_move_left));
            left.setOnClickListener(view -> {
                quickLaunchManager.moveQuickLaunchItemLeft(item.key);
                showQuickLaunchPanel();
            });
            actions.add(left);
        }
        if (position >= 0 && position < items.size() - 1) {
            TextView right = panelAction(getString(R.string.quick_launch_move_right));
            right.setOnClickListener(view -> {
                quickLaunchManager.moveQuickLaunchItemRight(item.key);
                showQuickLaunchPanel();
            });
            actions.add(right);
        }
        ComputerDetails host = hosts.get(item.computerUuid);
        if (host != null && host.runningGameId == item.appId) {
            TextView quit = panelAction(getString(R.string.applist_menu_quit));
            quit.setTextColor(0xFFFF9B92);
            quit.setOnClickListener(view -> UiHelper.displayQuitConfirmationDialog(this, () -> {
                hideSidePanel();
                ServerHelper.doQuit(this, host, new NvApp("app", item.appId, false),
                        managerBinder, () -> {
                            if (appListPoller != null) appListPoller.pollNow();
                        });
            }, null));
            actions.add(quit);
        }
        TextView remove = panelAction(getString(R.string.quick_launch_delete));
        remove.setTextColor(0xFFFF9B92);
        remove.setOnClickListener(view -> confirmRemoveQuickLaunchItem(item));
        actions.add(remove);
        showSidePanel(getString(R.string.console_quick_launch_eyebrow), item.getDisplayName(),
                item.computerName, actions.toArray(new View[0]));
    }

    private void renameQuickLaunchItem(QuickLaunchManager.QuickLaunchItem item) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(item.getDisplayName());
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.quick_launch_rename_title)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) {
                        quickLaunchManager.updateCustomName(item.key, value);
                        Toast.makeText(this, R.string.quick_launch_renamed, Toast.LENGTH_SHORT).show();
                        showQuickLaunchPanel();
                    }
                }).show();
        input.requestFocus();
    }

    private void confirmRemoveQuickLaunchItem(QuickLaunchManager.QuickLaunchItem item) {
        TextView cancel = panelAction(getString(R.string.console_cancel));
        cancel.setOnClickListener(view -> handlePanelBack());
        TextView remove = panelAction(getString(R.string.quick_launch_delete));
        remove.setTextColor(0xFFFF9B92);
        remove.setOnClickListener(view -> {
            quickLaunchManager.removeQuickLaunchItem(item.key);
            Toast.makeText(this, R.string.quick_launch_removed, Toast.LENGTH_SHORT).show();
            showQuickLaunchPanel();
        });
        showSidePanel(getString(R.string.console_quick_launch_eyebrow),
                getString(R.string.quick_launch_remove_confirm_title),
                item.getDisplayNameLong(), cancel, remove);
    }

    private void launchQuickOrConfirm(ComputerDetails host, NvApp app, String quickKey) {
        if (!ConsoleActionCatalog.isOnline(host)) {
            Toast.makeText(this, R.string.error_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ConsoleActionCatalog.isPaired(host)) {
            Toast.makeText(this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
            return;
        }
        if (host.runningGameId != 0 && host.runningGameId != app.getAppId()) {
            UiHelper.displayQuitConfirmationDialog(this,
                    () -> beginLaunch(host, app, quickKey), null);
        } else {
            beginLaunch(host, app, quickKey);
        }
    }

    private void showOverridesPanel() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean(OverridesView.PREF_OVERRIDES_ENABLED, false);
        int bitrate = OverridesView.getBitrateOverride(this);
        int stats = prefs.getInt(OverridesView.PREF_PERF_OVERLAY_OVERRIDE, 0);
        TextView toggle = panelAction(getString(R.string.console_overrides_toggle,
                enabled ? getString(R.string.console_on) : getString(R.string.console_off)));
        toggle.setOnClickListener(view -> {
            prefs.edit().putBoolean(OverridesView.PREF_OVERRIDES_ENABLED, !enabled).apply();
            showOverridesPanel();
        });
        TextView bitrateDown = panelAction(getString(R.string.console_overrides_bitrate_down));
        TextView bitrateUp = panelAction(getString(R.string.console_overrides_bitrate_up));
        TextView bitrateDefault = panelAction(getString(R.string.console_overrides_bitrate_default));
        TextView statsToggle = panelAction(getString(R.string.console_overrides_stats,
                getString(stats == 1 ? R.string.console_override_force_on
                        : stats == 2 ? R.string.console_override_force_off
                        : R.string.console_override_use_default)));
        bitrateDown.setOnClickListener(view -> {
            prefs.edit().putInt(OverridesView.PREF_BITRATE_OVERRIDE,
                    Math.max(0, bitrate - 5000)).apply();
            showOverridesPanel();
        });
        bitrateUp.setOnClickListener(view -> {
            prefs.edit().putInt(OverridesView.PREF_BITRATE_OVERRIDE,
                    Math.min(250000, bitrate + 5000)).apply();
            showOverridesPanel();
        });
        bitrateDefault.setOnClickListener(view -> {
            prefs.edit().putInt(OverridesView.PREF_BITRATE_OVERRIDE, 0).apply();
            showOverridesPanel();
        });
        statsToggle.setOnClickListener(view -> {
            prefs.edit().putInt(OverridesView.PREF_PERF_OVERLAY_OVERRIDE, (stats + 1) % 3).apply();
            showOverridesPanel();
        });
        showSidePanel(getString(R.string.console_overrides_eyebrow),
                getString(R.string.overrides_section_title),
                bitrate > 0 ? getString(R.string.console_overrides_bitrate, bitrate / 1000)
                        : getString(R.string.console_overrides_default),
                toggle, bitrateDown, bitrateUp, bitrateDefault, statsToggle);
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

    private ConsoleAction globalAction(String id, int label, int icon, Runnable handler) {
        return ConsoleAction.enabled(id, getString(label), icon, ConsoleAction.Context.GLOBAL,
                false, handler);
    }

    private ImageButton addQuickAction(ConsoleAction resolved) {
        ImageButton button = new ImageButton(this);
        button.setId(View.generateViewId());
        button.setImageResource(resolved.icon);
        button.setTag(resolved.id);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int target = getResources().getDimensionPixelSize(R.dimen.console_action_target);
        int icon = getResources().getDimensionPixelSize(R.dimen.console_icon_size);
        int inset = Math.max(0, (target - icon) / 2);
        button.setPadding(inset, inset, inset, inset);
        button.setColorFilter(0xFF9FAAB2);
        button.setFocusable(true);
        button.setClickable(true);
        button.setContentDescription(resolved.label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setTooltipText(resolved.label);
        }
        button.setOnClickListener(view -> resolved.handler.run());
        button.setOnFocusChangeListener((view, focused) -> styleQuickAction(button, focused));
        styleQuickAction(button, false);
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
        discordIndicatorColor = color;
        button.setContentDescription(getString(R.string.console_discord_description, state));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) button.setTooltipText(state);
        styleQuickAction(button, button.hasFocus());
        button.setAlpha(1f);
    }

    private void showHostActions(ComputerDetails host) {
        boolean online = ConsoleActionCatalog.isOnline(host);
        boolean known = host.state != ComputerDetails.State.UNKNOWN;
        boolean paired = ConsoleActionCatalog.isPaired(host);
        boolean activeSession = host.runningGameId != 0;
        String activeAddress = host.activeAddress != null ? host.activeAddress.address : null;
        boolean gatewayPaired = hostGatewayStore.loadClientConnection(host.uuid, activeAddress) != null;
        List<ConsoleAction> resolved = new ArrayList<>();
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.SELECT_APPS,
                getString(R.string.pcview_menu_app_list), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, false, () -> {
                    selectHost(host, true);
                    hideSidePanel();
                });
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.PAIR,
                getString(R.string.pcview_menu_pair_pc), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, false, () -> pairHost(host));
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.WAKE,
                getString(R.string.pcview_menu_send_wol), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, false, () -> {
                    hideSidePanel();
                    wakeHost(host);
                });
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.RESUME,
                getString(R.string.applist_menu_resume), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, false, () -> {
                    hideSidePanel();
                    resumeSession(host);
                });
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.QUIT_SESSION,
                getString(R.string.applist_menu_quit), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, true,
                () -> UiHelper.displayQuitConfirmationDialog(this, () -> {
                    hideSidePanel();
                    ServerHelper.doQuit(this, host, new NvApp("app", 0, false),
                            managerBinder, () -> {
                                if (appListPoller != null) appListPoller.pollNow();
                            });
                }, null));
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.REFRESH,
                getString(R.string.console_refresh_host), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, false, () -> {
                    if (managerBinder != null) managerBinder.invalidateStateForComputer(host.uuid);
                    hideSidePanel();
                });
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.NETWORK_TEST,
                getString(R.string.pcview_menu_test_network), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, false,
                () -> ServerHelper.doNetworkTest(this));
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.HOST_INTEGRATIONS,
                getString(R.string.console_host_integrations), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, false, () -> {
                    String address = host.activeAddress != null ? host.activeAddress.address : null;
                    discordPanelController.showHostIntegrations(host.uuid, address, host.name);
                });
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.SLEEP,
                getString(R.string.console_sleep_host), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, false, () -> confirmSleepHost(host));
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.UNPAIR,
                getString(R.string.pcview_menu_unpair_pc), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, true, () -> unpairHost(host));
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.DETAILS,
                getString(R.string.pcview_menu_details), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, false, () -> Dialog.displayDialog(this,
                        getString(R.string.title_details), host.toString(), false));
        addHostAction(resolved, ConsoleActionCatalog.HostCapability.REMOVE,
                getString(R.string.pcview_menu_delete_pc), online, known, paired, activeSession,
                host.macAddress != null, gatewayPaired, true, () -> confirmRemoveHost(host));
        List<View> actions = new ArrayList<>();
        for (ConsoleAction action : resolved) actions.add(actionView(action));
        showSidePanel(getString(R.string.console_host_eyebrow), host.name,
                getString(online ? R.string.console_host_online_details
                        : R.string.console_host_offline_details),
                actions.toArray(new View[0]));
    }

    private void addHostAction(List<ConsoleAction> actions,
                               ConsoleActionCatalog.HostCapability capability,
                               CharSequence label, boolean online, boolean known, boolean paired,
                               boolean activeSession, boolean hasMac, boolean gatewayPaired,
                               boolean destructive, Runnable handler) {
        boolean visible = ConsoleActionCatalog.hostActionVisible(capability, online, known,
                paired, activeSession, hasMac, gatewayPaired);
        if (!visible) return;
        actions.add(ConsoleAction.enabled("host." + capability.name().toLowerCase(Locale.ROOT),
                label, 0, ConsoleAction.Context.HOST, destructive, handler));
    }

    private void resumeSession(ComputerDetails host) {
        if (host == null || host.runningGameId == 0) return;
        NvApp running = null;
        for (NvApp app : loadApps(host, true)) {
            if (app.getAppId() == host.runningGameId) {
                running = app;
                break;
            }
        }
        if (running == null) {
            String name = findAppName(host, host.runningGameId);
            running = new NvApp(name == null ? "Moonlight" : name,
                    host.runningGameId, false);
        }
        String cachedGameId = uniquePlayniteGameIdForRunningApp(host, running.getAppId());
        if (!cachedGameId.isEmpty()) {
            beginLaunch(host, running, null, LaunchTransitionType.GAME, cachedGameId);
            return;
        }

        String address = host.activeAddress != null ? host.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, address);
        if (connection == null) {
            beginLaunch(host, running);
            return;
        }

        NvApp resolvedRunning = running;
        executor.execute(() -> {
            String currentGameId = "";
            try {
                HostGatewayClient.PlayniteCurrentGame current =
                        hostGatewayClient.getPlayniteCurrentGame(connection);
                if (HostGatewayClient.isPlayniteId(current.id)) {
                    currentGameId = current.id;
                }
            } catch (IOException ignored) {
                // A generic resume remains available when the profile bridge is unavailable.
            }
            String playniteGameId = currentGameId;
            mainHandler.post(() -> {
                if (!active) return;
                ComputerDetails latest = hosts.get(host.uuid);
                ComputerDetails targetHost = latest != null ? latest : host;
                if (playniteGameId.isEmpty()) {
                    beginLaunch(targetHost, resolvedRunning);
                } else {
                    beginLaunch(targetHost, resolvedRunning, null,
                            LaunchTransitionType.GAME, playniteGameId);
                }
            });
        });
    }

    private String uniquePlayniteGameIdForRunningApp(ComputerDetails host, int appId) {
        if (host == null || !host.uuid.equals(selectedHostUuid)) return "";
        String match = "";
        for (PlayniteDashboardItem item : renderedPlayniteItems) {
            if (item.sunshineAppId == null || item.sunshineAppId != appId) continue;
            if (!match.isEmpty()) return "";
            match = item.game.playniteGameId;
        }
        return HostGatewayClient.isPlayniteId(match) ? match : "";
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
        cancel.setOnClickListener(view -> handlePanelBack());
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
        cancel.setOnClickListener(view -> handlePanelBack());
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
        boolean changed = !host.uuid.equals(selectedHostUuid);
        if (changed) cancelPlayniteArtworkPrefetch();
        selectedHostUuid = host.uuid;
        preferences.edit().putString("selected_host", host.uuid).apply();
        newlyDiscoveredHosts.remove(host.uuid);
        clearArtwork();
        appsLabel.setText(getString(R.string.console_apps_host,
                host.name.toUpperCase(Locale.ROOT)));
        updateHostSelector();
        startAppListPoller(host);
        renderAppsAsync(host, focusApps);
        if (changed || currentPlayniteGames.isEmpty()) loadPlayniteForHost(host);
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
                currentSunshineApps = Collections.unmodifiableList(new ArrayList<>(apps));
                sunshineAppsGeneration.incrementAndGet();
                updateLaunchPlayniteButton(host, apps);
                if (!currentPlayniteGames.isEmpty() ||
                        hostGatewayStore.loadClientConnection(host.uuid,
                                host.activeAddress != null ? host.activeAddress.address : null) != null) {
                    renderPlayniteLibrary(host, apps);
                } else {
                    renderApps(host, apps);
                }
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

    private void loadPlayniteForHost(ComputerDetails host) {
        if (host == null) return;
        if (host.uuid.equals(currentPlayniteHostUuid) && !currentPlayniteGames.isEmpty()) {
            requestPlayniteRefresh(host, false);
            return;
        }
        cancelPlayniteRequest();
        int token = playniteGeneration.incrementAndGet();
        currentPlayniteHostUuid = host.uuid;
        currentPlayniteGames = Collections.emptyList();
        renderedPlayniteItems = Collections.emptyList();
        playniteLibraryCached = false;
        playniteLibraryError = null;
        updatePlayniteLibraryStatus(host);
        playniteRequest = playniteExecutor.submit(() -> {
            PlayniteLibraryCache.Entry cached = playniteLibraryRepository.cached(host.uuid);
            mainHandler.post(() -> {
                if (token != playniteGeneration.get() ||
                        !host.uuid.equals(selectedHostUuid)) return;
                if (cached != null) {
                    currentPlayniteGames = cached.games;
                    playniteLibraryCached = true;
                    renderPlayniteLibrary(host, currentSunshineApps);
                }
                requestPlayniteRefresh(host, false);
            });
        });
    }

    private void requestPlayniteRefresh(ComputerDetails host, boolean manual) {
        if (!active || host == null || !host.uuid.equals(selectedHostUuid)) return;
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, address);
        mainHandler.removeCallbacks(playniteRefreshCycle);
        if (connection == null) {
            playniteLibraryRefreshing = false;
            playniteLibraryError = PlayniteLibraryRepository.ErrorKind.AUTHENTICATION;
            updatePlayniteLibraryStatus(host);
            mainHandler.postDelayed(playniteRefreshCycle, PLAYNITE_REFRESH_MS);
            return;
        }
        cancelPlayniteRequest();
        int token = playniteGeneration.incrementAndGet();
        playniteLibraryRefreshing = true;
        playniteLibraryError = null;
        updatePlayniteLibraryStatus(host);
        playniteRequest = playniteExecutor.submit(() -> {
            PlayniteLibraryRepository.Result result = playniteLibraryRepository.refresh(
                    host.uuid, connection, () -> token != playniteGeneration.get() ||
                            Thread.currentThread().isInterrupted());
            mainHandler.post(() -> {
                if (token != playniteGeneration.get() || !active ||
                        !host.uuid.equals(selectedHostUuid)) return;
                playniteLibraryRefreshing = false;
                if (result.entry != null) {
                    currentPlayniteGames = result.entry.games;
                    playniteLibraryCached = false;
                    playniteLibraryError = null;
                    renderPlayniteLibrary(host, currentSunshineApps);
                } else {
                    playniteLibraryError = result.error;
                    playniteLibraryCached = !currentPlayniteGames.isEmpty();
                    updatePlayniteLibraryStatus(host);
                }
                mainHandler.removeCallbacks(playniteRefreshCycle);
                mainHandler.postDelayed(playniteRefreshCycle, PLAYNITE_REFRESH_MS);
            });
        });
    }

    private void cancelPlayniteRequest() {
        if (playniteRequest != null) {
            playniteRequest.cancel(true);
            playniteRequest = null;
        }
    }

    private void updatePlayniteLibraryStatus(ComputerDetails host) {
        if (playniteLibraryStatus == null) return;
        int textId;
        if (playniteLibraryRefreshing && currentPlayniteGames.isEmpty()) {
            textId = R.string.playnite_refreshing_library;
        } else if (playniteLibraryError == PlayniteLibraryRepository.ErrorKind.AUTHENTICATION) {
            String address = host != null && host.activeAddress != null
                    ? host.activeAddress.address : null;
            textId = host != null && hostGatewayStore.loadClientConnection(host.uuid, address) == null
                    ? R.string.playnite_gateway_not_configured
                    : R.string.playnite_authentication_error;
        } else if (playniteLibraryError == PlayniteLibraryRepository.ErrorKind.TIMEOUT) {
            textId = R.string.playnite_timeout;
        } else if (playniteLibraryError == PlayniteLibraryRepository.ErrorKind.INVALID_RESPONSE ||
                playniteLibraryError == PlayniteLibraryRepository.ErrorKind.API_VERSION) {
            textId = R.string.playnite_invalid_response;
        } else if (playniteLibraryError == PlayniteLibraryRepository.ErrorKind.SERVER) {
            textId = R.string.playnite_server_error;
        } else if (playniteLibraryError != null) {
            textId = R.string.playnite_gateway_unavailable;
        } else if (playniteLibraryCached) {
            textId = R.string.playnite_cached_library;
        } else if (playniteLibraryRefreshing) {
            textId = R.string.playnite_refreshing_library;
        } else {
            textId = R.string.playnite_data_current;
        }
        playniteLibraryStatus.setText(textId);
        playniteLibraryStatus.setTextColor(playniteLibraryError != null
                ? 0xFFFFB74D : playniteLibraryCached ? 0xFFB8C9DC : 0xFF8790A8);
    }

    private void togglePlayniteInstalledFilter() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) return;
        boolean installedOnly = !hostGatewayStore.isPlayniteInstalledOnly(host.uuid);
        hostGatewayStore.setPlayniteInstalledOnly(host.uuid, installedOnly);
        renderPlayniteLibrary(host, currentSunshineApps);
        styleInstalledFilter(true);
        installedFilterButton.requestFocus();
    }

    private void styleInstalledFilter(boolean focused) {
        if (installedFilterButton == null) return;
        ComputerDetails host = hosts.get(selectedHostUuid);
        boolean activeFilter = host != null &&
                hostGatewayStore.isPlayniteInstalledOnly(host.uuid);
        installedFilterButton.setText(activeFilter
                ? R.string.playnite_installed : R.string.playnite_all_games);
        installedFilterButton.setContentDescription(getString(
                R.string.playnite_filter_description,
                getString(activeFilter ? R.string.playnite_installed : R.string.playnite_all_games)));
        int top = focused ? 0xFF24343F : activeFilter ? 0x55314755 : 0x26242B32;
        int bottom = focused ? 0xFF18242C : activeFilter ? 0x55314755 : 0x4813181D;
        GradientDrawable background = gradient(top, bottom, 10);
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFF8DDCFF
                : activeFilter ? 0x8873D7FF : 0x384C5962);
        installedFilterButton.setBackground(background);
    }

    private void renderPlayniteLibrary(ComputerDetails host, List<NvApp> apps) {
        if (host == null || !host.uuid.equals(selectedHostUuid)) return;
        List<PlayniteLibraryGame> ordered = PlayniteLibraryOrdering.order(
                currentPlayniteGames, hostGatewayStore.isPlayniteInstalledOnly(host.uuid),
                getResources().getConfiguration().getLocales().get(0));
        List<PlayniteDashboardItem> items = new ArrayList<>();
        for (PlayniteLibraryGame game : ordered) {
            items.add(PlayniteTargetResolver.resolve(host.uuid, game, apps,
                    playniteLaunchTargetStore));
        }
        installedFilterButton.setVisibility(View.VISIBLE);
        applyPlayniteDiff(host, apps, items);
        schedulePlayniteArtworkPrefetch(host, items);
        renderedAppsSignature = null;
        appsLabel.setText(getString(R.string.playnite_library,
                host.name.toUpperCase(Locale.ROOT)));
        styleInstalledFilter(installedFilterButton.hasFocus());
        updatePlayniteLibraryStatus(host);
    }

    private void applyPlayniteDiff(ComputerDetails host, List<NvApp> apps,
                                   List<PlayniteDashboardItem> requestedItems) {
        Object focusTag = getCurrentFocus() != null ? getCurrentFocus().getTag() : null;
        String focusedId = focusTag instanceof String &&
                ((String) focusTag).startsWith("playnite:")
                ? ((String) focusTag).substring("playnite:".length()) : null;
        int previousIndex = getCurrentFocus() != null
                ? Math.max(0, appRow.indexOfChild(getCurrentFocus())) : 0;

        List<PlayniteDashboardItem> items = requestedItems;
        if (focusedId != null && containsPlayniteId(requestedItems, focusedId)) {
            items = preserveOrderDuringFocus(requestedItems);
        }
        Map<String, View> existing = new LinkedHashMap<>();
        boolean containsNonPlaynite = false;
        for (int index = 0; index < appRow.getChildCount(); index++) {
            View child = appRow.getChildAt(index);
            Object tag = child.getTag();
            if (tag instanceof String && ((String) tag).startsWith("playnite:")) {
                existing.put(((String) tag).substring("playnite:".length()), child);
            } else {
                containsNonPlaynite = true;
            }
        }
        if (containsNonPlaynite) {
            appRow.removeAllViews();
            existing.clear();
        }
        Map<String, PlayniteDashboardItem> previous = new LinkedHashMap<>();
        for (PlayniteDashboardItem item : renderedPlayniteItems) previous.put(item.stableId(), item);
        Set<String> wanted = new HashSet<>();
        for (PlayniteDashboardItem item : items) wanted.add(item.stableId());
        for (Map.Entry<String, View> entry : existing.entrySet()) {
            if (!wanted.contains(entry.getKey())) appRow.removeView(entry.getValue());
        }
        if (items.isEmpty()) {
            if (appRow.getChildCount() == 0) {
                TextView empty = text(getString(hostGatewayStore.isPlayniteInstalledOnly(host.uuid)
                                ? R.string.playnite_no_installed_games : R.string.playnite_library_empty),
                        15, 0xFFFFB74D, false);
                empty.setTag("playnite.empty");
                appRow.addView(empty, new LinearLayout.LayoutParams(
                        dp(700), ViewGroup.LayoutParams.MATCH_PARENT));
            }
            renderedPlayniteItems = Collections.emptyList();
            wireHomeFocusNavigation();
            return;
        }
        for (int index = 0; index < items.size(); index++) {
            PlayniteDashboardItem item = items.get(index);
            View card = existing.get(item.stableId());
            PlayniteDashboardItem old = previous.get(item.stableId());
            if (card == null || "playnite.empty".equals(card.getTag())) {
                card = playniteCard(host, item, apps);
            } else if (!item.equals(old) || isVibepolloEnsureInFlight(host.uuid, item)) {
                bindPlayniteCard(card, host, item, apps,
                        !item.equals(old) ? playnitePayload(old, item) : PlayniteLibraryDiff.TEXT);
            }
            int currentIndex = appRow.indexOfChild(card);
            if (currentIndex < 0) {
                appRow.addView(card, Math.min(index, appRow.getChildCount()),
                        playniteCardSpacing());
            } else if (currentIndex != index && focusedId == null) {
                appRow.removeViewAt(currentIndex);
                appRow.addView(card, Math.min(index, appRow.getChildCount()),
                        playniteCardSpacing());
            }
        }
        renderedPlayniteItems = Collections.unmodifiableList(new ArrayList<>(items));
        String selection = PlayniteLibraryDiff.selectionAfter(focusedId, previousIndex, items);
        if (focusedId != null && selection != null) {
            restoreTaggedFocus(appRow, "playnite:" + selection);
        }
        wireHomeFocusNavigation();
    }

    private List<PlayniteDashboardItem> preserveOrderDuringFocus(
            List<PlayniteDashboardItem> requested) {
        Map<String, PlayniteDashboardItem> values = new LinkedHashMap<>();
        for (PlayniteDashboardItem item : requested) values.put(item.stableId(), item);
        List<PlayniteDashboardItem> result = new ArrayList<>();
        for (PlayniteDashboardItem old : renderedPlayniteItems) {
            PlayniteDashboardItem replacement = values.remove(old.stableId());
            if (replacement != null) result.add(replacement);
        }
        result.addAll(values.values());
        return result;
    }

    private boolean containsPlayniteId(List<PlayniteDashboardItem> items, String id) {
        for (PlayniteDashboardItem item : items) if (item.stableId().equals(id)) return true;
        return false;
    }

    private int playnitePayload(PlayniteDashboardItem old, PlayniteDashboardItem item) {
        if (old == null) return PlayniteLibraryDiff.TEXT | PlayniteLibraryDiff.ARTWORK |
                PlayniteLibraryDiff.LAUNCH;
        int payload = 0;
        if (!old.game.name.equals(item.game.name) || old.game.installed != item.game.installed ||
                old.game.playtimeSeconds != item.game.playtimeSeconds ||
                !old.game.source.equals(item.game.source)) payload |= PlayniteLibraryDiff.TEXT;
        if (!old.game.coverKey.equals(item.game.coverKey) ||
                !old.game.backgroundKey.equals(item.game.backgroundKey)) {
            payload |= PlayniteLibraryDiff.ARTWORK;
        }
        if (!Objects.equals(old.sunshineAppId, item.sunshineAppId) ||
                old.mappingState != item.mappingState) payload |= PlayniteLibraryDiff.LAUNCH;
        return payload;
    }

    private View playniteCard(ComputerDetails host, PlayniteDashboardItem item, List<NvApp> apps) {
        LinearLayout card = cardBase(dp(portraitLayout ? 220 : 205),
                dp(portraitLayout ? 225 : 190));
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.TOP);
        card.setPadding(0, 0, 0, 0);
        card.setClipToOutline(true);
        FrameLayout artworkFrame = new FrameLayout(this);
        artworkFrame.setBackground(gradient(0xFF26333D, 0xFF172128, 12));
        ImageView backdrop = new ImageView(this);
        backdrop.setTag("playnite.poster.backdrop");
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(.42f);
        backdrop.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    dp(10), dp(10), android.graphics.Shader.TileMode.CLAMP));
        }
        artworkFrame.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView poster = new ImageView(this);
        poster.setTag("playnite.poster");
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setImageResource(R.drawable.ic_computer);
        poster.setPadding(dp(72), dp(54), dp(72), dp(54));
        artworkFrame.addView(poster, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(artworkFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(portraitLayout ? 140 : 115)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(14), dp(10), dp(14), dp(8));
        TextView name = text("", 15, Color.WHITE, true);
        name.setTag("playnite.name");
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(name, matchLinearWidth());
        TextView playtime = text("", 10, 0xFFB8C9DC, false);
        playtime.setTag("playnite.playtime");
        copy.addView(playtime, matchLinearWidth());
        TextView state = text("", 10, 0xFF929BAD, false);
        state.setTag("playnite.state");
        state.setMaxLines(1);
        state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(state, matchLinearWidth());
        card.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bindPlayniteCard(card, host, item, apps,
                PlayniteLibraryDiff.TEXT | PlayniteLibraryDiff.ARTWORK |
                        PlayniteLibraryDiff.LAUNCH);
        return card;
    }

    private LinearLayout.LayoutParams matchLinearWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams playniteCardSpacing() {
        LinearLayout.LayoutParams params = portraitLayout
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(225))
                : new LinearLayout.LayoutParams(dp(205), dp(190));
        if (portraitLayout) params.bottomMargin = dp(14);
        else params.rightMargin = dp(14);
        return params;
    }

    private void bindPlayniteCard(View card, ComputerDetails host,
                                  PlayniteDashboardItem item, List<NvApp> apps, int payload) {
        card.setTag("playnite:" + item.stableId());
        TextView name = (TextView) findTaggedChild((ViewGroup) card, "playnite.name");
        TextView playtime = (TextView) findTaggedChild((ViewGroup) card, "playnite.playtime");
        TextView state = (TextView) findTaggedChild((ViewGroup) card, "playnite.state");
        ImageView poster = (ImageView) findTaggedChild((ViewGroup) card, "playnite.poster");
        String playtimeText = formatPlayniteTime(item.game.playtimeSeconds);
        String stateText = playniteState(host.uuid, item);
        if ((payload & PlayniteLibraryDiff.TEXT) != 0) {
            name.setText(item.game.name);
            playtime.setText(playtimeText);
        }
        if ((payload & (PlayniteLibraryDiff.TEXT | PlayniteLibraryDiff.LAUNCH)) != 0) {
            state.setText(stateText);
        }
        if ((payload & PlayniteLibraryDiff.ARTWORK) != 0) resetPlaynitePoster(poster, item);
        card.setOnClickListener(view -> activatePlayniteItem(host.uuid, item));
        card.setOnLongClickListener(view -> {
            showPlayniteTargetPicker(currentHost(host.uuid), item, currentSunshineApps);
            return true;
        });
        card.setContentDescription(getString(R.string.playnite_card_description,
                item.game.name, playtimeText, stateText));
        card.setOnFocusChangeListener((view, focused) -> {
            styleCard(card, focused);
            if (focused) {
                preferences.edit().putString("selected_playnite." + host.uuid,
                        item.stableId()).apply();
                if (portraitLayout) smoothCenterOn(appVerticalScroll, card);
                else smoothCenterOn(appScroll, card);
                loadPlaynitePoster(host, item, poster, true);
            }
        });
    }

    private View findTaggedChild(ViewGroup parent, String tag) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (tag.equals(child.getTag())) return child;
            if (child instanceof ViewGroup) {
                View result = findTaggedChild((ViewGroup) child, tag);
                if (result != null) return result;
            }
        }
        return null;
    }

    private String formatPlayniteTime(long seconds) {
        return PlaynitePlaytimeFormatter.format(seconds, new PlaynitePlaytimeFormatter.Labels(
                getString(R.string.playnite_never_played),
                getString(R.string.playnite_hours_short),
                getString(R.string.playnite_minutes_short)));
    }

    private String playniteState(String hostUuid, PlayniteDashboardItem item) {
        if (!item.game.installed) return getString(R.string.playnite_not_installed);
        if (isVibepolloEnsureInFlight(hostUuid, item)) {
            return getString(R.string.playnite_creating_vibepollo_app);
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.MAPPED) {
            return getString(R.string.playnite_game_installed);
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.FALLBACK_PLAYNITE) {
            return getString(R.string.playnite_launch_via_playnite);
        }
        return getString(R.string.playnite_launch_not_configured);
    }

    private String vibepolloEnsureKey(String hostUuid, PlayniteDashboardItem item) {
        return hostUuid + ":" + item.game.playniteGameId;
    }

    private boolean isVibepolloEnsureInFlight(String hostUuid, PlayniteDashboardItem item) {
        return vibepolloEnsureInFlight.contains(vibepolloEnsureKey(hostUuid, item));
    }

    private void resetPlaynitePoster(ImageView poster, PlayniteDashboardItem item) {
        ImageView backdrop = playnitePosterBackdrop(poster);
        if (backdrop != null) {
            backdrop.setImageDrawable(null);
            backdrop.setVisibility(View.GONE);
        }
        poster.setImageResource(R.drawable.ic_computer);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setPadding(dp(72), dp(54), dp(72), dp(54));
        poster.setTag(R.id.playnite_artwork_key, playniteArtworkTag(item));
    }

    private void loadPlaynitePoster(ComputerDetails host, PlayniteDashboardItem item,
                                    ImageView poster, boolean allowNetwork) {
        ComputerDetails latestHost = currentHost(host.uuid);
        if (latestHost == null) return;
        PlayniteArtworkSpec spec = PlayniteArtworkSpec.forGame(item.game);
        if (!spec.available()) return;
        ArtworkResult cached = cachedPlayniteArtwork(latestHost.uuid, item, spec);
        String expectedTag = playniteArtworkTag(item);
        if (cached != null) {
            loadPlayniteBitmap(cached.file, poster, expectedTag, cached.kind);
            showArtwork(cached.file, poster.getDrawable());
            if (spec.kind.equals(cached.kind) || !allowNetwork) return;
        }
        if (!allowNetwork) return;
        String address = latestHost.activeAddress != null
                ? latestHost.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(latestHost.uuid, address);
        if (connection == null) return;
        executor.execute(() -> {
            try {
                ArtworkResult result = fetchPlayniteArtwork(connection, latestHost.uuid,
                        item, spec);
                mainHandler.post(() -> {
                    Object expected = poster.getTag(R.id.playnite_artwork_key);
                    if (expectedTag.equals(expected)) {
                        loadPlayniteBitmap(result.file, poster, expectedTag, result.kind);
                        if (poster.hasFocus() || cardParentHasFocus(poster)) {
                            showArtwork(result.file, poster.getDrawable());
                        }
                    }
                });
            } catch (IOException ignored) { }
        });
    }

    private void schedulePlayniteArtworkPrefetch(ComputerDetails host,
                                                  List<PlayniteDashboardItem> items) {
        if (!active || host == null || items.isEmpty()) return;
        StringBuilder signature = new StringBuilder(host.uuid);
        for (PlayniteDashboardItem item : items) {
            signature.append('|').append(item.stableId()).append(':')
                    .append(PlayniteArtworkSpec.forGame(item.game).cacheIdentity());
        }
        String nextSignature = signature.toString();
        if (nextSignature.equals(playniteArtworkPrefetchSignature)) return;
        cancelPlayniteArtworkPrefetch();
        playniteArtworkPrefetchSignature = nextSignature;
        ComputerDetails latestHost = currentHost(host.uuid);
        if (latestHost == null) return;
        String address = latestHost.activeAddress != null
                ? latestHost.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(latestHost.uuid, address);
        if (connection == null) return;
        int token = playniteArtworkGeneration.incrementAndGet();
        List<PlayniteDashboardItem> snapshot = new ArrayList<>(items);
        playniteArtworkPrefetch = playniteArtworkExecutor.submit(() -> {
            for (PlayniteDashboardItem item : snapshot) {
                if (Thread.currentThread().isInterrupted() ||
                        token != playniteArtworkGeneration.get()) return;
                PlayniteArtworkSpec spec = PlayniteArtworkSpec.forGame(item.game);
                if (!spec.available()) continue;
                try {
                    ArtworkResult result = cachedPlayniteArtwork(latestHost.uuid, item, spec);
                    if (result == null || !spec.kind.equals(result.kind)) {
                        try {
                            result = fetchPlayniteArtwork(
                                    connection, latestHost.uuid, item, spec);
                        } catch (IOException primaryFailure) {
                            if (result == null) throw primaryFailure;
                        }
                    }
                    ArtworkResult ready = result;
                    mainHandler.post(() -> applyPrefetchedPlayniteArtwork(
                            token, latestHost.uuid, item, ready));
                } catch (IOException ignored) { }
            }
        });
    }

    private void cancelPlayniteArtworkPrefetch() {
        playniteArtworkGeneration.incrementAndGet();
        if (playniteArtworkPrefetch != null) {
            playniteArtworkPrefetch.cancel(true);
            playniteArtworkPrefetch = null;
        }
        playniteArtworkPrefetchSignature = null;
    }

    private ArtworkResult cachedPlayniteArtwork(String hostUuid, PlayniteDashboardItem item,
                                                 PlayniteArtworkSpec spec) {
        File primary = playniteArtworkCache.get(hostUuid, item.stableId(),
                spec.kind, spec.version);
        if (primary != null && primary.isFile() && primary.length() > 0) {
            return new ArtworkResult(primary, spec.kind);
        }
        if (!spec.hasFallback()) return null;
        File fallback = playniteArtworkCache.get(hostUuid, item.stableId(),
                spec.fallbackKind, spec.fallbackVersion);
        return fallback != null && fallback.isFile() && fallback.length() > 0
                ? new ArtworkResult(fallback, spec.fallbackKind) : null;
    }

    private String cachedLoadingArtworkPath(String hostUuid, String gameId) {
        if (hostUuid == null || gameId == null || gameId.isEmpty()) return null;
        for (PlayniteDashboardItem item : renderedPlayniteItems) {
            if (!gameId.equalsIgnoreCase(item.stableId())
                    || item.game.backgroundKey.isEmpty()) continue;
            File artwork = playniteArtworkCache.get(hostUuid, item.stableId(),
                    "background", item.game.backgroundKey);
            return artwork != null && artwork.isFile() && artwork.length() > 0
                    ? artwork.getAbsolutePath() : null;
        }
        return null;
    }

    private ArtworkResult fetchPlayniteArtwork(HostGatewayClient.Connection connection,
                                                String hostUuid,
                                                PlayniteDashboardItem item,
                                                PlayniteArtworkSpec spec) throws IOException {
        try {
            return new ArtworkResult(playniteArtworkCache.fetch(hostGatewayClient, connection,
                    hostUuid, item.stableId(), spec.kind, spec.version), spec.kind);
        } catch (IOException primaryFailure) {
            if (!spec.hasFallback()) throw primaryFailure;
            return new ArtworkResult(playniteArtworkCache.fetch(hostGatewayClient, connection,
                    hostUuid, item.stableId(), spec.fallbackKind, spec.fallbackVersion),
                    spec.fallbackKind);
        }
    }

    private void applyPrefetchedPlayniteArtwork(int token, String hostUuid,
                                                 PlayniteDashboardItem item,
                                                 ArtworkResult result) {
        if (token != playniteArtworkGeneration.get() || !hostUuid.equals(selectedHostUuid)) return;
        View card = directChildWithTag(appRow, "playnite:" + item.stableId());
        if (!(card instanceof ViewGroup)) return;
        ImageView poster = (ImageView) findTaggedChild((ViewGroup) card, "playnite.poster");
        if (poster == null || !playniteArtworkTag(item).equals(
                poster.getTag(R.id.playnite_artwork_key))) return;
        loadPlayniteBitmap(result.file, poster, playniteArtworkTag(item), result.kind);
        if (card.hasFocus()) showArtwork(result.file, poster.getDrawable());
    }

    private View directChildWithTag(ViewGroup parent, Object tag) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (Objects.equals(tag, child.getTag())) return child;
        }
        return null;
    }

    private String playniteArtworkTag(PlayniteDashboardItem item) {
        return item.stableId() + ":" + PlayniteArtworkSpec.forGame(item.game).cacheIdentity();
    }

    private boolean cardParentHasFocus(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (((View) parent).hasFocus()) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private void loadPlayniteBitmap(File file, ImageView poster,
                                    String expectedTag, String kind) {
        executor.execute(() -> {
            Bitmap bitmap = decodeSampledBitmap(file, 640, 360);
            mainHandler.post(() -> {
                Object expected = poster.getTag(R.id.playnite_artwork_key);
                if (bitmap != null && expectedTag.equals(expected)) {
                    poster.setPadding(0, 0, 0, 0);
                    boolean landscape = "background".equals(kind);
                    poster.setScaleType(landscape
                            ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
                    ImageView backdrop = playnitePosterBackdrop(poster);
                    if (backdrop != null) {
                        if (landscape) {
                            backdrop.setImageDrawable(null);
                            backdrop.setVisibility(View.GONE);
                        } else {
                            backdrop.setImageBitmap(bitmap);
                            backdrop.setVisibility(View.VISIBLE);
                        }
                    }
                    poster.setImageBitmap(bitmap);
                }
            });
        });
    }

    private ImageView playnitePosterBackdrop(ImageView poster) {
        ViewParent parent = poster.getParent();
        if (!(parent instanceof ViewGroup)) return null;
        View value = findTaggedChild((ViewGroup) parent, "playnite.poster.backdrop");
        return value instanceof ImageView ? (ImageView) value : null;
    }

    private static Bitmap decodeSampledBitmap(File file, int requestedWidth,
                                              int requestedHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= requestedWidth &&
                bounds.outHeight / (sample * 2) >= requestedHeight) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static final class ArtworkResult {
        final File file;
        final String kind;

        ArtworkResult(File file, String kind) {
            this.file = file;
            this.kind = kind;
        }
    }

    private void activatePlayniteItem(String hostUuid, PlayniteDashboardItem item) {
        ComputerDetails host = currentHost(hostUuid);
        if (host == null) {
            Toast.makeText(this, R.string.error_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        List<NvApp> apps = currentSunshineApps;
        if (!item.game.installed) {
            Toast.makeText(this, R.string.playnite_not_installed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (isVibepolloEnsureInFlight(hostUuid, item)) {
            Toast.makeText(this, R.string.playnite_creating_vibepollo_app,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.MAPPED) {
            NvApp target = PlayniteTargetResolver.findById(apps, item.sunshineAppId);
            if (target != null) {
                launchOrConfirm(host, target, LaunchTransitionType.GAME,
                        item.game.playniteGameId);
                return;
            }
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.FALLBACK_PLAYNITE) {
            NvApp fallback = PlayniteTargetResolver.findById(apps, item.sunshineAppId);
            if (fallback != null) {
                launchOrConfirm(host, fallback, LaunchTransitionType.GAME,
                        item.game.playniteGameId);
                return;
            }
        }
        NvApp fallback = PlayniteTargetResolver.resolvePlayniteFullscreen(
                host.uuid, apps, playniteLaunchTargetStore);
        if (fallback != null) {
            ensureVibepolloAppThenLaunch(host, item, fallback);
            return;
        }
        showPlayniteTargetPicker(host, item, apps);
    }

    private void ensureVibepolloAppThenLaunch(ComputerDetails host,
                                               PlayniteDashboardItem item,
                                               NvApp fallback) {
        ComputerDetails latestHost = currentHost(host.uuid);
        // The Gateway and Vibepollo live on the host.  When the machine is
        // asleep, wake it first instead of treating an unavailable Gateway as
        // a reason to launch the Playnite fallback.
        if (!ConsoleActionCatalog.isOnline(latestHost)) {
            prepareHostThenEnsureVibepolloApp(host, item, fallback);
            return;
        }
        String address = latestHost != null && latestHost.activeAddress != null
                ? latestHost.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, address);
        if (connection == null) {
            launchPlayniteFallback(host.uuid, item, fallback);
            return;
        }
        String ensureKey = vibepolloEnsureKey(host.uuid, item);
        if (!vibepolloEnsureInFlight.add(ensureKey)) {
            Toast.makeText(this, R.string.playnite_creating_vibepollo_app,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // The first activation only prepares the Sunshine entry. Do not launch
        // an app whose creation is still propagating: that was the source of
        // the initial 404. The card becomes launchable after stable polling.
        renderPlayniteLibrary(host, currentSunshineApps);
        ensureVibepolloApp(host.uuid, item, fallback, connection, ensureKey,
                SystemClock.uptimeMillis() + VIBEPOLLO_APP_WAIT_MS);
    }

    private void ensureVibepolloApp(String hostUuid, PlayniteDashboardItem item,
                                    NvApp fallback, HostGatewayClient.Connection connection,
                                    String ensureKey, long deadline) {
        executor.execute(() -> {
            JSONObject ensuredApp = null;
            try {
                ensuredApp = hostGatewayClient.ensureVibepolloPlayniteApp(connection,
                        item.game.playniteGameId, item.game.name);
            } catch (IOException | IllegalArgumentException ignored) { }
            JSONObject result = ensuredApp;
            mainHandler.post(() -> {
                if (!active || !vibepolloEnsureInFlight.contains(ensureKey)) return;
                if (result != null) {
                    if (appListPoller != null) appListPoller.pollNow();
                    awaitVibepolloTarget(hostUuid, item, fallback,
                            HostGatewayClient.parseVibepolloAppId(result),
                            HostGatewayClient.parseVibepolloAppUuid(result), ensureKey, deadline,
                            null, -1, 0, 0L);
                } else if (SystemClock.uptimeMillis() >= deadline) {
                    vibepolloEnsureInFlight.remove(ensureKey);
                    launchPlayniteFallback(hostUuid, item, fallback);
                } else {
                    mainHandler.postDelayed(() -> ensureVibepolloApp(hostUuid, item, fallback,
                            connection, ensureKey, deadline), VIBEPOLLO_ENSURE_RETRY_MS);
                }
            });
        });
    }

    private void prepareHostThenEnsureVibepolloApp(ComputerDetails host,
                                                    PlayniteDashboardItem item,
                                                    NvApp fallback) {
        if (managerBinder == null) {
            Toast.makeText(this, R.string.console_initializing, Toast.LENGTH_SHORT).show();
            return;
        }
        int token = launchGeneration.incrementAndGet();
        showLoading(host.name, item.game.name, LaunchTransitionType.GAME);
        Runnable prepareAfterOverlayFrame = () -> executor.execute(() -> {
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
                    if (streamLoadingView != null) {
                        streamLoadingView.showError(getString(R.string.console_host_not_ready),
                                getString(R.string.console_host_timeout));
                    }
                    return;
                }
                // This path only wakes the host so Vibepollo can prepare the
                // entry. It is not a stream launch yet, so return to the tile.
                showHome();
                ensureVibepolloAppThenLaunch(ready, item, fallback);
            });
        });
        if (streamLoadingView != null) {
            streamLoadingView.doAfterNextFrame(prepareAfterOverlayFrame);
        } else {
            prepareAfterOverlayFrame.run();
        }
    }

    private void awaitVibepolloTarget(String hostUuid, PlayniteDashboardItem item,
                                      NvApp fallback, Integer expectedAppId,
                                      String expectedAppUuid, String ensureKey, long deadline,
                                      Integer stableAppId, int stableGeneration,
                                      int stablePolls, long stableSince) {
        // The Bridge response identifies the exact record Vibepollo created.
        // Prefer that identity over a similarly named stale Sunshine app;
        // launching the stale record is what produced the first-launch 404.
        NvApp synchronizedTarget = null;
        if (!expectedAppUuid.isEmpty()) {
            synchronizedTarget = PlayniteTargetResolver.findByUuid(
                    currentSunshineApps, expectedAppUuid);
        }
        if (synchronizedTarget == null && expectedAppId != null) {
            synchronizedTarget = PlayniteTargetResolver.findById(
                    currentSunshineApps, expectedAppId);
        }
        if (synchronizedTarget == null) {
            synchronizedTarget = PlayniteTargetResolver.findPlayableExactName(
                    currentSunshineApps, item.game.name);
        }
        long now = SystemClock.uptimeMillis();
        int generation = sunshineAppsGeneration.get();
        Integer nextStableAppId = stableAppId;
        int nextStableGeneration = stableGeneration;
        int nextStablePolls = stablePolls;
        long nextStableSince = stableSince;
        if (synchronizedTarget != null) {
            int candidateId = synchronizedTarget.getAppId();
            if (!Objects.equals(stableAppId, candidateId)) {
                nextStableAppId = candidateId;
                nextStableGeneration = generation;
                nextStablePolls = 1;
                nextStableSince = now;
            } else {
                nextStableGeneration = generation;
                nextStablePolls++;
            }
        } else {
            nextStableAppId = null;
            nextStableGeneration = -1;
            nextStablePolls = 0;
            nextStableSince = 0L;
        }
        if (synchronizedTarget != null &&
                nextStablePolls >= VIBEPOLLO_APP_STABLE_POLLS &&
                now - nextStableSince >= VIBEPOLLO_APP_STABLE_MS) {
            vibepolloEnsureInFlight.remove(ensureKey);
            playniteLaunchTargetStore.setGameTarget(hostUuid, item.stableId(),
                    synchronizedTarget.getAppId());
            renderPlayniteLibrary(currentHost(hostUuid), currentSunshineApps);
            return;
        }
        if (now >= deadline) {
            vibepolloEnsureInFlight.remove(ensureKey);
            launchPlayniteFallback(hostUuid, item, fallback);
            return;
        }
        if (appListPoller != null) appListPoller.pollNow();
        Integer pendingAppId = nextStableAppId;
        int pendingGeneration = nextStableGeneration;
        int pendingPolls = nextStablePolls;
        long pendingSince = nextStableSince;
        mainHandler.postDelayed(() -> awaitVibepolloTarget(
                hostUuid, item, fallback, expectedAppId, expectedAppUuid, ensureKey, deadline,
                pendingAppId, pendingGeneration, pendingPolls, pendingSince), 1_000L);
    }

    private void launchPlayniteFallback(String hostUuid, PlayniteDashboardItem item,
                                        NvApp fallback) {
        playniteLaunchTargetStore.setGameTarget(hostUuid, item.stableId(),
                fallback.getAppId());
        renderPlayniteLibrary(currentHost(hostUuid), currentSunshineApps);
        launchOrConfirm(currentHost(hostUuid), fallback, LaunchTransitionType.GAME,
                item.game.playniteGameId);
    }

    private void showPlayniteTargetPicker(ComputerDetails host, PlayniteDashboardItem item,
                                          List<NvApp> apps) {
        if (host == null) {
            Toast.makeText(this, R.string.error_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!item.game.installed) {
            Toast.makeText(this, R.string.playnite_not_installed, Toast.LENGTH_SHORT).show();
            return;
        }
        List<View> actions = new ArrayList<>();
        for (NvApp app : apps) {
            TextView choose = panelAction(app.getAppName());
            choose.setTag("playnite.target:" + app.getAppId());
            choose.setOnClickListener(view -> {
                playniteLaunchTargetStore.setGameTarget(host.uuid,
                        item.stableId(), app.getAppId());
                hideSidePanel();
                Toast.makeText(this, getString(R.string.playnite_target_saved,
                        item.game.name), Toast.LENGTH_SHORT).show();
                renderPlayniteLibrary(host, currentSunshineApps);
            });
            actions.add(choose);
        }
        Integer saved = playniteLaunchTargetStore.gameTarget(host.uuid, item.stableId());
        if (saved != null) {
            TextView clear = panelAction(getString(R.string.playnite_clear_target));
            clear.setOnClickListener(view -> {
                playniteLaunchTargetStore.clearGameTarget(host.uuid, item.stableId());
                hideSidePanel();
                renderPlayniteLibrary(host, currentSunshineApps);
            });
            actions.add(clear);
        }
        if (actions.isEmpty()) actions.add(label(getString(R.string.playnite_launch_unavailable)));
        showSidePanel(getString(R.string.playnite_select_launch_target), item.game.name,
                getString(R.string.playnite_select_target_details),
                actions.toArray(new View[0]));
    }

    private void updateLaunchPlayniteButton(ComputerDetails host, List<NvApp> apps) {
        if (launchPlayniteButton == null || host == null) return;
        NvApp target = PlayniteTargetResolver.resolvePlayniteFullscreen(
                host.uuid, apps, playniteLaunchTargetStore);
        boolean hasApps = !apps.isEmpty();
        launchPlayniteButton.setEnabled(hasApps);
        launchPlayniteButton.setAlpha(hasApps ? 1f : .48f);
        launchPlayniteButton.setContentDescription(target != null
                ? getString(R.string.playnite_launch_description)
                : getString(R.string.playnite_no_fullscreen_target));
    }

    private void launchPlayniteFullscreen() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) return;
        NvApp target = PlayniteTargetResolver.resolvePlayniteFullscreen(
                host.uuid, currentSunshineApps, playniteLaunchTargetStore);
        if (target != null) {
            playniteLaunchTargetStore.setPlayniteTarget(host.uuid, target.getAppId());
            launchOrConfirm(host, target, LaunchTransitionType.PLAYNITE, "");
        } else {
            showPlayniteFullscreenTargetPicker();
        }
    }

    private void showPlayniteFullscreenTargetPicker() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) return;
        List<NvApp> ordered = new ArrayList<>();
        List<NvApp> preferred = PlayniteTargetResolver.playniteCandidates(currentSunshineApps);
        ordered.addAll(preferred);
        for (NvApp app : currentSunshineApps) if (!preferred.contains(app)) ordered.add(app);
        List<View> actions = new ArrayList<>();
        for (NvApp app : ordered) {
            TextView choose = panelAction(app.getAppName());
            choose.setTag("playnite.fullscreen.target:" + app.getAppId());
            choose.setOnClickListener(view -> {
                playniteLaunchTargetStore.setPlayniteTarget(host.uuid, app.getAppId());
                hideSidePanel();
                updateLaunchPlayniteButton(host, currentSunshineApps);
            });
            actions.add(choose);
        }
        if (actions.isEmpty()) actions.add(label(getString(R.string.playnite_no_fullscreen_target)));
        showSidePanel(getString(R.string.playnite_select_launch_target),
                getString(R.string.playnite_launch),
                getString(R.string.playnite_fullscreen_target_details),
                actions.toArray(new View[0]));
    }

    private void renderApps(ComputerDetails host, List<NvApp> apps) {
        String signature = appRenderSignature(host, apps);
        Object firstTag = appRow.getChildCount() > 0 ? appRow.getChildAt(0).getTag() : null;
        if (signature.equals(renderedAppsSignature) && firstTag instanceof String &&
                ((String) firstTag).startsWith("app:")) return;
        renderedAppsSignature = signature;
        renderedPlayniteItems = Collections.emptyList();
        if (installedFilterButton != null) installedFilterButton.setVisibility(View.GONE);
        int restored = preferences.getInt("app_scroll." + host.uuid, 0);
        Object focusedTag = getCurrentFocus() != null ? getCurrentFocus().getTag() : null;
        int previousFocusedIndex = getCurrentFocus() != null
                ? appRow.indexOfChild(getCurrentFocus()) : -1;
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
        if (portraitLayout) {
            appVerticalScroll.post(() -> appVerticalScroll.scrollTo(0, restored));
        } else {
            appScroll.post(() -> appScroll.scrollTo(restored, 0));
        }
        List<String> availableTags = new ArrayList<>();
        for (int index = 0; index < appRow.getChildCount(); index++) {
            Object tag = appRow.getChildAt(index).getTag();
            if (tag instanceof String) availableTags.add((String) tag);
        }
        String selection = ConsoleDashboardState.restoreSelection(
                focusedTag instanceof String ? (String) focusedTag : null, availableTags);
        if (focusedTag instanceof String && !availableTags.contains(focusedTag)
                && previousFocusedIndex >= 0 && !availableTags.isEmpty()) {
            selection = availableTags.get(Math.min(previousFocusedIndex, availableTags.size() - 1));
        }
        if (selection != null && (appHadFocus || getCurrentFocus() == null)) {
            restoreTaggedFocus(appRow, selection);
        }
        wireHomeFocusNavigation();
    }

    private View appCard(ComputerDetails host, NvApp app) {
        LinearLayout card = cardBase(dp(portraitLayout ? 220 : 205),
                dp(portraitLayout ? 225 : 225));
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.TOP);
        card.setPadding(0, 0, 0, 0);
        card.setClipToOutline(true);
        card.setTag("app:" + host.uuid + ":" + app.getAppId());
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setImageResource(R.drawable.ic_computer);
        poster.setPadding(dp(72), dp(54), dp(72), dp(54));
        poster.setBackground(gradient(0xFF26333D, 0xFF172128, 12));
        card.addView(poster, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(portraitLayout ? 140 : 142)));
        File artwork = assetLoader.getFile(host.uuid, app.getAppId());
        loadPoster(host, app, artwork, poster);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.TOP);
        copy.setPadding(dp(14), dp(12), dp(14), dp(10));
        TextView name = text(app.getAppName(), 15, Color.WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        long playedAt = preferences.getLong(appHistoryKey(host.uuid, app.getAppId()), 0L);
        boolean running = host.runningGameId == app.getAppId();
        boolean hostReady = ConsoleActionCatalog.isOnline(host)
                && ConsoleActionCatalog.isPaired(host);
        boolean customSettings = !AppPreferences.getAppSettings(this,
                host.uuid + ":" + app.getAppId()).useGlobalSettings;
        boolean quickLaunch = isQuickLaunch(host.uuid, app.getAppId());
        String metadata = !ConsoleActionCatalog.isOnline(host)
                ? getString(R.string.console_app_host_offline)
                : !ConsoleActionCatalog.isPaired(host)
                ? getString(R.string.console_app_host_unpaired)
                : running ? getString(R.string.console_app_running)
                : playedAt > 0 ? getString(R.string.console_last_played,
                formatRelative(System.currentTimeMillis() - playedAt))
                : getString(R.string.console_app_ready);
        if (customSettings) metadata += getString(R.string.console_app_custom_settings_suffix);
        if (quickLaunch) metadata += getString(R.string.console_app_quick_suffix);
        TextView metadataView = text(metadata, 10, 0xFF929BAD, false);
        metadataView.setMaxLines(2);
        metadataView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metadataParams = wrapLinear();
        metadataParams.topMargin = dp(5);
        copy.addView(metadataView, metadataParams);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        card.addView(copy, copyParams);
        card.setOnClickListener(v -> {
            // A paired offline host is launchable: beginLaunch() sends WOL and
            // waits for its streaming ports while the five-step loader is shown.
            if (ConsoleActionCatalog.isPaired(host)) launchOrConfirm(host, app);
            else showAppActions(host, app, poster);
        });
        card.setOnLongClickListener(view -> {
            showAppActions(host, app, poster);
            return true;
        });
        card.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_MENU
                    || keyCode == KeyEvent.KEYCODE_BUTTON_X)) {
                showAppActions(host, app, poster);
                return true;
            }
            return false;
        });
        card.setContentDescription(getString(R.string.console_app_description,
                app.getAppName(), metadata, hostReady
                        ? running ? getString(R.string.console_resume) : getString(R.string.console_play)
                        : getString(R.string.console_app_options_action)));
        card.setOnFocusChangeListener((v, focused) -> {
            styleCard(card, focused);
            if (focused) {
                preferences.edit().putString("selected_app." + host.uuid,
                        String.valueOf(card.getTag())).apply();
                if (portraitLayout) smoothCenterOn(appVerticalScroll, card);
                else smoothCenterOn(appScroll, card);
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

    private String appRenderSignature(ComputerDetails host, List<NvApp> apps) {
        StringBuilder value = new StringBuilder(host.uuid).append('|')
                .append(host.state).append('|').append(host.pairState).append('|')
                .append(host.runningGameId).append('|').append(showHiddenApps);
        for (NvApp app : apps) {
            String appKey = host.uuid + ":" + app.getAppId();
            value.append(';').append(app.getAppId()).append(':').append(app.getAppName())
                    .append(':').append(isQuickLaunch(host.uuid, app.getAppId()))
                    .append(':').append(preferences.getLong(appHistoryKey(
                            host.uuid, app.getAppId()), 0L))
                    .append(':').append(AppPreferences.getAppSettings(this, appKey)
                            .useGlobalSettings);
        }
        return value.toString();
    }

    private void launchOrConfirm(ComputerDetails host, NvApp app) {
        launchOrConfirm(host, app, LaunchTransitionType.GENERIC, "");
    }

    private void launchOrConfirm(ComputerDetails host, NvApp app,
                                 LaunchTransitionType transitionType,
                                 String playniteGameId) {
        ComputerDetails latestHost = host == null ? null : currentHost(host.uuid);
        if (!ConsoleActionCatalog.isPaired(latestHost)) {
            Toast.makeText(this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
            return;
        }
        if (latestHost.runningGameId != 0 && latestHost.runningGameId != app.getAppId()) {
            UiHelper.displayQuitConfirmationDialog(this,
                    () -> beginLaunch(latestHost, app, null, transitionType,
                            playniteGameId), null);
        } else {
            beginLaunch(latestHost, app, null, transitionType, playniteGameId);
        }
    }

    private ComputerDetails currentHost(String hostUuid) {
        if (hostUuid == null) return null;
        if (managerBinder != null) {
            ComputerDetails managed = managerBinder.getComputer(hostUuid);
            if (managed != null) {
                ComputerDetails copy = new ComputerDetails(managed);
                hosts.put(hostUuid, copy);
                return copy;
            }
        }
        return hosts.get(hostUuid);
    }

    private void showAppActions(ComputerDetails host, NvApp app, ImageView poster) {
        boolean online = ConsoleActionCatalog.isOnline(host);
        boolean paired = ConsoleActionCatalog.isPaired(host);
        boolean thisAppRunning = host.runningGameId == app.getAppId();
        boolean anotherAppRunning = host.runningGameId != 0 && !thisAppRunning;
        Set<String> hidden = new HashSet<>(getSharedPreferences(
                AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .getStringSet(host.uuid, Collections.emptySet()));
        boolean isHidden = hidden.contains(String.valueOf(app.getAppId()));
        boolean quickLaunch = isQuickLaunch(host.uuid, app.getAppId());
        boolean hasArtwork = poster.getDrawable() instanceof BitmapDrawable;
        boolean shortcutsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
        List<ConsoleAction> resolved = new ArrayList<>();
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.LAUNCH,
                getString(R.string.console_play), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> { hideSidePanel(); launchOrConfirm(host, app); });
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.RESUME,
                getString(R.string.applist_menu_resume), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> { hideSidePanel(); launchOrConfirm(host, app); });
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.QUIT_AND_LAUNCH,
                getString(R.string.applist_menu_quit_and_start), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> { hideSidePanel(); launchOrConfirm(host, app); });
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.QUIT,
                getString(R.string.applist_menu_quit), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                true, () -> UiHelper.displayQuitConfirmationDialog(this, () -> {
                    hideSidePanel();
                    ServerHelper.doQuit(this, host, app, managerBinder,
                            () -> { if (appListPoller != null) appListPoller.pollNow(); });
                }, null));
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.SETTINGS,
                getString(R.string.console_app_stream_settings), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> {
                    hideSidePanel();
                    Intent intent = new Intent(this, AppStreamSettings.class);
                    intent.putExtra(AppStreamSettings.EXTRA_APP_KEY,
                            host.uuid + ":" + app.getAppId());
                    intent.putExtra(AppStreamSettings.EXTRA_APP_NAME, app.getAppName());
                    startActivity(intent);
                });
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.QUICK_ADD,
                getString(R.string.applist_menu_add_quick_launch), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> {
                    quickLaunchManager.addQuickLaunchItem(host, app);
                    Toast.makeText(this, R.string.quick_launch_added, Toast.LENGTH_SHORT).show();
                    showAppActions(host, app, poster);
                });
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.QUICK_REMOVE,
                getString(R.string.quick_launch_delete), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> {
                    removeAppFromQuickLaunch(host.uuid, app.getAppId());
                    Toast.makeText(this, R.string.quick_launch_removed, Toast.LENGTH_SHORT).show();
                    showAppActions(host, app, poster);
                });
        Runnable changeVisibility = () -> {
            if (isHidden) hidden.remove(String.valueOf(app.getAppId()));
            else hidden.add(String.valueOf(app.getAppId()));
            getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                    .edit().putStringSet(host.uuid, hidden).apply();
            showHiddenApps = isHidden || showHiddenApps;
            hideSidePanel();
            renderAppsAsync(host, true);
        };
        addAppAction(resolved, isHidden ? ConsoleActionCatalog.AppCapability.SHOW
                        : ConsoleActionCatalog.AppCapability.HIDE,
                getString(isHidden ? R.string.console_show_app : R.string.applist_menu_hide_app),
                online, paired, thisAppRunning, anotherAppRunning, quickLaunch, isHidden,
                hasArtwork, shortcutsSupported, false, changeVisibility);
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.SHORTCUT,
                getString(R.string.applist_menu_scut), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> {
                    Bitmap bitmap = ((BitmapDrawable) poster.getDrawable()).getBitmap();
                    if (!shortcutHelper.createPinnedGameShortcut(host, app, bitmap)) {
                        Toast.makeText(this, R.string.unable_to_pin_shortcut, Toast.LENGTH_LONG).show();
                    }
                });
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.DETAILS,
                getString(R.string.applist_menu_details), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> Dialog.displayDialog(this,
                        getString(R.string.title_details), app.toString(), false));
        List<View> actions = new ArrayList<>();
        for (ConsoleAction action : resolved) actions.add(actionView(action));
        showSidePanel(getString(R.string.console_apps_eyebrow), app.getAppName(),
                getString(R.string.console_app_actions_details), actions.toArray(new View[0]));
    }

    private void addAppAction(List<ConsoleAction> actions,
                              ConsoleActionCatalog.AppCapability capability,
                              CharSequence label, boolean online, boolean paired,
                              boolean thisAppRunning, boolean anotherAppRunning,
                              boolean quickLaunch, boolean hidden, boolean hasArtwork,
                              boolean shortcutsSupported, boolean destructive, Runnable handler) {
        if (!ConsoleActionCatalog.appActionVisible(capability, online, paired,
                thisAppRunning, anotherAppRunning, quickLaunch, hidden, hasArtwork,
                shortcutsSupported)) return;
        actions.add(ConsoleAction.enabled("app." + capability.name().toLowerCase(Locale.ROOT),
                label, 0, ConsoleAction.Context.APPLICATION, destructive, handler));
    }

    private void removeAppFromQuickLaunch(String hostUuid, int appId) {
        List<QuickLaunchManager.QuickLaunchItem> items =
                new ArrayList<>(quickLaunchManager.getAllQuickLaunchItems());
        for (QuickLaunchManager.QuickLaunchItem item : items) {
            if (hostUuid.equals(item.computerUuid) && appId == item.appId) {
                quickLaunchManager.removeQuickLaunchItem(item.key);
            }
        }
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
                if (bitmap != null && view.isAttachedToWindow()) {
                    view.setPadding(0, 0, 0, 0);
                    view.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    view.clearColorFilter();
                    view.setImageBitmap(bitmap);
                }
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
        glassAccent = 0xFF73D7FF;
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
        beginLaunch(host, app, null);
    }

    private void beginLaunch(ComputerDetails host, NvApp app, String quickLaunchKey) {
        beginLaunch(host, app, quickLaunchKey, LaunchTransitionType.GENERIC, "");
    }

    private void beginLaunch(ComputerDetails host, NvApp app, String quickLaunchKey,
                             LaunchTransitionType transitionType,
                             String playniteGameId) {
        if (managerBinder == null) {
            Toast.makeText(this, R.string.console_initializing, Toast.LENGTH_SHORT).show();
            return;
        }
        int token = launchGeneration.incrementAndGet();
        LaunchTransitionSpec transition = LaunchTransitionSpec.create(
                host.uuid, transitionType, app.getAppId(), playniteGameId,
                System.currentTimeMillis());
        String loadingArtworkPath = cachedLoadingArtworkPath(host.uuid, playniteGameId);
        showLoading(host.name, app.getAppName(), transitionType, loadingArtworkPath);
        Runnable startAfterOverlayFrame = () -> executor.execute(() -> {
            ComputerDetails ready = HostReadiness.await(
                    () -> managerBinder != null ? managerBinder.getComputer(host.uuid) : null,
                    host,
                    () -> token != launchGeneration.get() || !active,
                    message -> setLoadingStatus(token, message),
                    getString(R.string.console_wol_status, host.name),
                    getString(R.string.console_waiting_stream_ports));
            if (ready != null && ready.runningGameId != 0
                    && ready.runningGameId != app.getAppId()) {
                try {
                    closePreviousSession(token, ready, app.getAppId());
                } catch (IOException | XmlPullParserException error) {
                    mainHandler.post(() -> {
                        if (token != launchGeneration.get() || !active) return;
                        if (streamLoadingView != null) {
                            streamLoadingView.showError(
                                    getString(R.string.applist_quit_fail),
                                    getString(R.string.console_previous_session_close_failed));
                        }
                    });
                    return;
                }
            }
            mainHandler.post(() -> {
                if (token != launchGeneration.get() || !active) return;
                if (ready == null) {
                    if (streamLoadingView != null) {
                        streamLoadingView.showError(
                                getString(R.string.console_host_not_ready),
                                getString(R.string.console_host_timeout));
                    }
                    return;
                }
                preferences.edit()
                        .putLong(appHistoryKey(host.uuid, app.getAppId()), System.currentTimeMillis())
                        .putLong("host_played." + host.uuid, System.currentTimeMillis())
                        .apply();
                String openingStatus = getString(R.string.console_opening_stream);
                if (streamLoadingView != null) {
                    streamLoadingView.setStep(2, openingStatus);
                }
                // Keep Moonlight's existing launch path and stream lifecycle unchanged.
                Bundle presentation = new Bundle();
                presentation.putBoolean(Game.EXTRA_CONSOLE_LOADING, true);
                presentation.putString(Game.EXTRA_CONSOLE_LOADING_MESSAGE,
                        streamLoadingView == null ? null
                                : streamLoadingView.getCurrentMessage());
                presentation.putLong(Game.EXTRA_CONSOLE_LOADING_EPOCH,
                        streamLoadingEpoch);
                presentation.putInt(Game.EXTRA_CONSOLE_LOADING_STEP, 2);
                presentation.putString(Game.EXTRA_CONSOLE_LOADING_STATUS, openingStatus);
                presentation.putBoolean(Game.EXTRA_CONSOLE_REDUCED_MOTION, reducedMotion);
                presentation.putString(Game.EXTRA_CONSOLE_LOADING_ARTWORK,
                        loadingArtworkPath);
                presentation.putString(Game.EXTRA_TRANSITION_ID, transition.id);
                presentation.putString(Game.EXTRA_TRANSITION_TYPE, transition.type.name());
                presentation.putString(Game.EXTRA_TRANSITION_HOST_ID, transition.hostId);
                presentation.putString(Game.EXTRA_TRANSITION_PLAYNITE_GAME_ID,
                        transition.playniteGameId);
                presentation.putLong(Game.EXTRA_TRANSITION_CREATED_AT,
                        transition.createdAtMillis);
                ServerHelper.doStart(ConsoleActivity.this, app, ready, managerBinder,
                        quickLaunchKey, presentation);
                overridePendingTransition(0, 0);
            });
        });
        if (streamLoadingView != null) {
            streamLoadingView.doAfterNextFrame(startAfterOverlayFrame);
        } else {
            startAfterOverlayFrame.run();
        }
    }

    private void closePreviousSession(int token, ComputerDetails host, int targetAppId)
            throws IOException, XmlPullParserException {
        if (host.runningGameId == 0 || host.runningGameId == targetAppId) return;
        setLoadingStatus(token, getString(R.string.transition_closing_session));
        NvHTTP connection = new NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(host), host.httpsPort,
                managerBinder.getUniqueId(), host.serverCert,
                PlatformBinding.getCryptoProvider(this));
        if (!connection.quitApp()) {
            throw new IOException("Host rejected closing the previous session.");
        }
        long deadline = SystemClock.uptimeMillis() + PREVIOUS_SESSION_CLOSE_TIMEOUT_MS;
        IOException lastError = null;
        while (token == launchGeneration.get() && active
                && SystemClock.uptimeMillis() < deadline) {
            try {
                ComputerDetails current = connection.getComputerDetails(true);
                if (current.runningGameId == 0) return;
                if (current.runningGameId == targetAppId) return;
                lastError = null;
            } catch (IOException error) {
                lastError = error;
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while closing the previous session.",
                        interrupted);
            }
        }
        if (lastError != null) throw lastError;
        throw new IOException("Previous host session did not close in time.");
    }

    private void showLoading(String hostName, String appName) {
        showLoading(hostName, appName, LaunchTransitionType.GENERIC);
    }

    private void showLoading(String hostName, String appName,
                             LaunchTransitionType transitionType) {
        showLoading(hostName, appName, transitionType, null);
    }

    private void showLoading(String hostName, String appName,
                             LaunchTransitionType transitionType,
                             String loadingArtworkPath) {
        lastContentFocus = getCurrentFocus();
        lastContentFocusTag = lastContentFocus == null ? null : lastContentFocus.getTag();
        homeLayer.setVisibility(View.GONE);
        loadingLayer.setVisibility(View.VISIBLE);
        if (streamLoadingView != null) {
            streamLoadingView.stop();
            loadingLayer.removeView(streamLoadingView);
        }
        streamLoadingEpoch = SystemClock.uptimeMillis();
        streamLoadingView = new ConsoleStreamLoadingView(
                this, appName, null, streamLoadingEpoch, reducedMotion);
        streamLoadingView.setSplashArtwork(loadingArtworkPath);
        loadingLayer.addView(streamLoadingView, match());
        streamLoadingView.configureForPlaynite(
                transitionType == LaunchTransitionType.PLAYNITE);
        streamLoadingView.setActions(new ConsoleStreamLoadingView.Actions() {
            @Override public void onCancel() {
                launchGeneration.incrementAndGet();
                showHome();
            }

            @Override public void onRetry() {
                launchGeneration.incrementAndGet();
                showHome();
            }

            @Override public void onShowStreamAnyway() {
                // Dashboard preparation never exposes a stream. This action becomes
                // available only inside Game after a readiness timeout.
            }
        });
        streamLoadingView.setStep(1, getString(R.string.console_preparing_host, hostName));
        streamLoadingView.bringToFront();
    }

    private void setLoadingStatus(int token, String message) {
        mainHandler.post(() -> {
            if (token == launchGeneration.get() && loadingLayer.getVisibility() == View.VISIBLE) {
                if (streamLoadingView != null) streamLoadingView.setStep(1, message);
            }
        });
    }

    private void showHome() {
        if (streamLoadingView != null) {
            streamLoadingView.stopAndHide();
            loadingLayer.removeView(streamLoadingView);
            streamLoadingView = null;
        }
        loadingLayer.setVisibility(View.GONE);
        homeLayer.setVisibility(View.VISIBLE);
        View restore = lastContentFocus;
        if (restore != null && restore.isAttachedToWindow() && restore.isFocusable()) {
            restore.requestFocus();
        } else if (launchPlayniteButton != null && "playnite.fullscreen".equals(
                lastContentFocusTag)) {
            launchPlayniteButton.requestFocus();
        }
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
        StringBuilder signature = new StringBuilder();
        for (ControllerInfo controller : controllers) {
            signature.append(controller.deviceId).append(':').append(controller.name)
                    .append(':').append(controller.percentage).append(':')
                    .append(controller.batteryStatus).append(';');
        }
        if (signature.toString().equals(renderedControllersSignature)) return;
        renderedControllersSignature = signature.toString();
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
        LinearLayout card = cardBase(dp(220), dp(50));
        card.setTag("controller:" + controller.deviceId);
        card.setFocusable(true);
        card.setClickable(true);
        int batteryColor = controller.percentage < 0 ? 0xFFB3B8C8
                : controller.isCharging() ? 0xFF64B5F6
                : controller.percentage <= 10 ? 0xFFFF5252
                : controller.percentage <= 30 ? 0xFFFFB74D : 0xFF69F0AE;
        ImageView batteryIcon = new ImageView(this);
        batteryIcon.setImageDrawable(new ControllerBatteryDrawable(
                controller.percentage, controller.isCharging(), batteryColor));
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
        card.setContentDescription(getString(R.string.console_controller_description,
                name.getText(), battery));
        card.setOnClickListener(view -> showControllerMenu(player, controller));
        card.setOnFocusChangeListener((view, focused) -> styleCard(card, focused));
        return card;
    }

    private void showControllerMenu(int player, ControllerInfo controller) {
        List<View> actions = new ArrayList<>();
        if (ControllerActions.canIdentify(controller.deviceId)) {
            TextView identify = panelAction(getString(R.string.console_controller_identify));
            identify.setOnClickListener(view -> ControllerActions.identify(
                    controller.deviceId, mainHandler, (success, message) -> mainHandler.post(() ->
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show())));
            actions.add(identify);
        }
        TextView powerOff = panelAction(getString(R.string.console_controller_power_off));
        powerOff.setOnClickListener(view ->
                confirmBluetoothAction(controller, BluetoothAction.POWER_OFF));
        actions.add(powerOff);
        TextView unpair = panelAction(getString(R.string.console_controller_unpair));
        unpair.setTextColor(0xFFFF9B92);
        unpair.setOnClickListener(view ->
                confirmBluetoothAction(controller, BluetoothAction.UNPAIR));
        actions.add(unpair);
        showSidePanel(getString(R.string.console_controller_menu_eyebrow),
                getString(R.string.console_controller_menu_title, player,
                        compactControllerName(controller.name)),
                getString(R.string.console_controller_menu_details),
                actions.toArray(new View[0]));
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
        TextView settings = panelAction(getString(R.string.console_streaming_settings));
        TextView hiddenApps = panelAction(getString(R.string.console_hidden_apps_setting,
                getString(showHiddenApps ? R.string.console_on : R.string.console_off)));
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
        hiddenApps.setOnClickListener(v -> {
            toggleHiddenApps();
            showOptionsPanel();
        });
        settings.setOnClickListener(v -> {
            hideSidePanel();
            startActivity(new Intent(this, StreamSettings.class));
        });
        showSidePanel(getString(R.string.console_title), getString(R.string.console_options_title),
                getString(R.string.console_options_details),
                sounds, motion, hiddenApps, settings);
    }

    private void showExitConfirmation() {
        TextView cancel = panelAction(getString(R.string.console_cancel));
        TextView exit = panelAction(getString(R.string.console_exit));
        exit.setTextColor(0xFFFF8A80);
        cancel.setOnClickListener(view -> handlePanelBack());
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
        discordPanelController.openDiscord(host.uuid, address, host.name);
    }

    private void showSidePanel(String eyebrow, String title, String details, View... actions) {
        if (sidePanelBusyBanner != null) {
            ViewParent parent = sidePanelBusyBanner.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(sidePanelBusyBanner);
            sidePanelBusyBanner = null;
        }
        String nextKey = eyebrow + "\n" + title;
        boolean alreadyShowing = sideDialog != null && sideDialog.isShowing();
        boolean samePanel = alreadyShowing && nextKey.equals(currentPanelKey);
        View previousPanelFocus = samePanel ? getCurrentFocus() : null;
        if (previousPanelFocus != null && !isDescendant(sidePanel, previousPanelFocus)) {
            previousPanelFocus = null;
        }
        Object previousPanelFocusTag = previousPanelFocus != null
                ? previousPanelFocus.getTag() : null;
        int previousPanelFocusIndex = -1;
        if (previousPanelFocus != null) {
            List<View> previousFocusable = new ArrayList<>();
            collectFocusable(sidePanel, previousFocusable);
            previousPanelFocusIndex = previousFocusable.indexOf(previousPanelFocus);
        }
        if (!alreadyShowing) {
            lastContentFocus = getCurrentFocus();
            lastContentFocusTag = lastContentFocus != null ? lastContentFocus.getTag() : null;
            panelHistory.clear();
            homeLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            homeLayer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        } else if (!sidePanelTransient && currentPanelKey != null
                && !currentPanelKey.equals(nextKey)) {
            PanelSnapshot snapshot = capturePanelSnapshot();
            if (snapshot != null) panelHistory.push(snapshot);
        }
        sidePanelTransient = false;
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
        for (View action : actions) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(7);
            sidePanel.addView(action, params);
        }
        TextView hint = text(getString(R.string.console_back_close), 11, 0x8FFFFFFF, true);
        sidePanel.addView(hint, sectionWithTop(12));
        currentPanelKey = nextKey;
        if (!alreadyShowing) {
            sideDialog.show();
            Window window = sideDialog.getWindow();
            if (window != null) window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        if (!alreadyShowing) {
            sidePanelScroll.setTranslationX(reducedMotion ? 0 : dp(510));
            if (!reducedMotion) {
                sidePanelScroll.animate().translationX(0).setDuration(180).start();
            }
        } else {
            sidePanelScroll.animate().cancel();
            sidePanelScroll.setTranslationX(0);
        }
        wireModalFocusTrap(false);
        View preserved = previousPanelFocusTag != null
                ? sidePanel.findViewWithTag(previousPanelFocusTag) : null;
        if ((preserved == null || !preserved.isFocusable() || !preserved.isEnabled())
                && previousPanelFocusIndex >= 0) {
            List<View> currentFocusable = new ArrayList<>();
            collectFocusable(sidePanel, currentFocusable);
            if (!currentFocusable.isEmpty()) {
                preserved = currentFocusable.get(Math.min(
                        previousPanelFocusIndex, currentFocusable.size() - 1));
            }
        }
        if (preserved != null && preserved.isFocusable() && preserved.isEnabled()) {
            preserved.post(preserved::requestFocus);
        } else {
            requestFirstModalFocus();
        }
    }

    private void showSidePanelBusy(String eyebrow, String title, String details) {
        boolean alreadyShowing = sideDialog != null && sideDialog.isShowing();
        if (!alreadyShowing) {
            TextView waiting = text(getString(R.string.console_loading), 13, 0xFF8DDCFF, false);
            waiting.setFocusable(false);
            showSidePanel(eyebrow, title, details, waiting);
            sidePanelTransient = true;
            return;
        }
        if (sidePanelBusyBanner != null) {
            ViewParent parent = sidePanelBusyBanner.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(sidePanelBusyBanner);
        }
        TextView banner = text("\u21BB  " + details, 12, 0xFF8DDCFF, false);
        banner.setFocusable(false);
        banner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        sidePanel.addView(banner, Math.min(3, sidePanel.getChildCount()), params);
        sidePanelBusyBanner = banner;
    }

    private void hideSidePanel() {
        Runnable finish = () -> {
            if (discordPanelController != null) discordPanelController.closePanel();
            if (sideDialog != null) sideDialog.dismiss();
            sidePanelScroll.setTranslationX(0);
            panelHistory.clear();
            currentPanelKey = null;
            sidePanelBusyBanner = null;
            sidePanelTransient = false;
            homeLayer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            homeLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            restoreContentFocus();
        };
        if (reducedMotion) finish.run();
        else sidePanelScroll.animate().translationX(dp(510)).setDuration(150).withEndAction(finish).start();
    }

    private PanelSnapshot capturePanelSnapshot() {
        List<View> focusable = new ArrayList<>();
        collectFocusable(sidePanel, focusable);
        if (focusable.isEmpty()) return null;
        View focused = getCurrentFocus();
        if (focused != null && !isDescendant(sidePanel, focused)) focused = null;
        List<View> children = new ArrayList<>();
        for (int index = 0; index < sidePanel.getChildCount(); index++) {
            children.add(sidePanel.getChildAt(index));
        }
        return new PanelSnapshot(currentPanelKey, children, focused,
                focused != null ? focused.getTag() : null);
    }

    private void handlePanelBack() {
        if (!panelHistory.isEmpty()) {
            PanelSnapshot snapshot = panelHistory.pop();
            sidePanel.removeAllViews();
            for (View child : snapshot.children) sidePanel.addView(child);
            currentPanelKey = snapshot.key;
            wireModalFocusTrap(false);
            View restore = snapshot.focusedTag != null
                    ? sidePanel.findViewWithTag(snapshot.focusedTag) : snapshot.focused;
            if (restore != null && restore.isFocusable() && restore.isEnabled()) {
                restore.requestFocus();
                mainHandler.postDelayed(() -> {
                    if (sideDialog != null && sideDialog.isShowing()
                            && restore.isAttachedToWindow()) restore.requestFocus();
                }, 32);
            } else {
                requestFirstModalFocus();
            }
        } else {
            hideSidePanel();
        }
    }

    private void wireModalFocusTrap(boolean requestFirst) {
        List<View> focusable = new ArrayList<>();
        collectFocusable(sidePanel, focusable);
        if (focusable.isEmpty()) {
            sidePanelScroll.setFocusable(true);
            sidePanelScroll.post(sidePanelScroll::requestFocus);
            return;
        }
        sidePanelScroll.setFocusable(false);
        for (int index = 0; index < focusable.size(); index++) {
            View item = focusable.get(index);
            View up = focusable.get(Math.max(0, index - 1));
            View down = focusable.get(Math.min(focusable.size() - 1, index + 1));
            item.setNextFocusUpId(up.getId());
            item.setNextFocusDownId(down.getId());
        }
        if (requestFirst) focusable.get(0).post(focusable.get(0)::requestFocus);
    }

    private void requestFirstModalFocus() {
        List<View> focusable = new ArrayList<>();
        collectFocusable(sidePanel, focusable);
        if (!focusable.isEmpty()) focusable.get(0).post(focusable.get(0)::requestFocus);
    }

    private boolean isDescendant(ViewGroup ancestor, View view) {
        View current = view;
        while (current != null) {
            if (current == ancestor) return true;
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private void collectFocusable(View view, List<View> output) {
        if (view.getVisibility() != View.VISIBLE || !view.isEnabled()) return;
        if (view.isFocusable()) output.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectFocusable(group.getChildAt(index), output);
            }
        }
    }

    private void restoreContentFocus() {
        View target = null;
        if (lastContentFocus != null && lastContentFocus.isAttachedToWindow()
                && lastContentFocus.isShown() && lastContentFocus.isFocusable()) {
            target = lastContentFocus;
        } else if (lastContentFocusTag != null) {
            View tagged = homeLayer.findViewWithTag(lastContentFocusTag);
            if (tagged != null && tagged.isShown() && tagged.isFocusable()) target = tagged;
        }
        if (target == null) target = firstFocusableChild(appRow);
        if (target == null) target = hostSelector;
        if (target != null) target.post(target::requestFocus);
    }

    private TextView panelAction(String label) {
        TextView action = text(label, 14, 0xFFF0E9FF, true);
        action.setId(View.generateViewId());
        action.setFocusable(true);
        action.setClickable(true);
        action.setTag("panel.action:" + label);
        action.setMinHeight(dp(46));
        action.setPadding(dp(16), dp(7), dp(16), dp(7));
        action.setOnFocusChangeListener((view, focused) -> styleCompactButton(action, focused));
        styleCompactButton(action, false);
        return action;
    }

    private TextView actionView(ConsoleAction resolved) {
        String label = resolved.unavailableReason == null ? resolved.label.toString()
                : resolved.label + "\n" + resolved.unavailableReason;
        TextView action = panelAction(label);
        action.setTag(resolved.id);
        action.setEnabled(resolved.enabled);
        action.setAlpha(resolved.enabled ? 1f : .55f);
        if (resolved.destructive) action.setTextColor(0xFFFF9B92);
        if (resolved.enabled) action.setOnClickListener(view -> resolved.handler.run());
        if (resolved.unavailableReason != null) {
            action.setContentDescription(resolved.label + ". " + resolved.unavailableReason);
        }
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
        int top = focused ? 0xFF202A32 : 0xE01B2026;
        int bottom = focused ? 0xFF151C22 : 0xF012161B;
        GradientDrawable background = gradient(top, bottom, 14);
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFF8DDCFF : 0x425F6D76);
        card.setBackground(background);
        card.setElevation(dp(focused ? 7 : 2));
        animateScale(card, focused ? 1.04f : 1f);
    }

    private void styleCompactButton(View button, boolean focused) {
        int top = focused ? 0xFF24343F : 0x26242B32;
        int bottom = focused ? 0xFF18242C : 0x4813181D;
        GradientDrawable background = gradient(top, bottom, 10);
        background.setStroke(dp(focused ? 2 : 1), focused
                ? getResources().getColor(R.color.console_accent) : 0x384C5962);
        button.setBackground(background);
        button.setElevation(dp(focused ? 4 : 0));
    }

    private void styleQuickAction(ImageButton button, boolean focused) {
        int tint = button == discordActionButton ? discordIndicatorColor : 0xFF9FAAB2;
        button.setColorFilter(focused ? 0xFFBDEBFF : tint);
        GradientDrawable background = gradient(
                focused ? 0xD9233038 : 0x00000000,
                focused ? 0xD9182229 : 0x00000000, 10);
        if (focused) background.setStroke(dp(1), 0xB873D7FF);
        button.setBackground(background);
        button.setElevation(dp(focused ? 2 : 0));
        animateScale(button, focused ? 1.03f : 1f);
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
        if (selectedHostUuid != null) {
            int appPosition = portraitLayout && appVerticalScroll != null
                    ? appVerticalScroll.getScrollY()
                    : appScroll != null ? appScroll.getScrollX() : 0;
            editor.putInt("app_scroll." + selectedHostUuid, appPosition);
        }
        editor.apply();
    }

    private void wireHomeFocusNavigation() {
        if (optionsButton == null) return;
        View resume = quickResumeButton != null
                && quickResumeButton.getVisibility() == View.VISIBLE
                && quickResumeButton.isEnabled() ? quickResumeButton : null;
        View playnite = launchPlayniteButton != null
                && launchPlayniteButton.getVisibility() == View.VISIBLE
                && launchPlayniteButton.isEnabled() ? launchPlayniteButton : null;
        View filter = installedFilterButton != null
                && installedFilterButton.getVisibility() == View.VISIBLE
                && installedFilterButton.isEnabled() ? installedFilterButton : null;
        View quick = firstFocusableChild(quickActions);
        View controller = firstFocusableChild(controllerRow);
        View app = firstFocusableChild(appRow);

        View belowOptions = resume != null ? resume : playnite != null ? playnite
                : quick != null ? quick : controller != null ? controller : app;
        if (belowOptions != null) optionsButton.setNextFocusDownId(belowOptions.getId());

        int down = controller != null ? controller.getId()
                : filter != null ? filter.getId() : app != null ? app.getId() : View.NO_ID;
        if (resume != null) {
            resume.setNextFocusUpId(optionsButton.getId());
            if (playnite != null) resume.setNextFocusRightId(playnite.getId());
            else if (quick != null) resume.setNextFocusRightId(quick.getId());
            if (down != View.NO_ID) resume.setNextFocusDownId(down);
        }
        if (playnite != null) {
            playnite.setNextFocusUpId(optionsButton.getId());
            if (resume != null) playnite.setNextFocusLeftId(resume.getId());
            if (quick != null) playnite.setNextFocusRightId(quick.getId());
            if (down != View.NO_ID) playnite.setNextFocusDownId(down);
        }

        if (quickActions != null) {
            for (int index = 0; index < quickActions.getChildCount(); index++) {
                View child = quickActions.getChildAt(index);
                if (!child.isFocusable()) continue;
                child.setNextFocusUpId(optionsButton.getId());
                if (down != View.NO_ID) child.setNextFocusDownId(down);
                if (index == 0 && playnite != null) child.setNextFocusLeftId(playnite.getId());
                else if (index == 0 && resume != null) child.setNextFocusLeftId(resume.getId());
            }
        }

        if (controller != null) {
            controller.setNextFocusUpId(quick != null ? quick.getId() : optionsButton.getId());
            if (filter != null) controller.setNextFocusDownId(filter.getId());
            else if (app != null) controller.setNextFocusDownId(app.getId());
        }

        if (filter != null) {
            filter.setNextFocusUpId(controller != null ? controller.getId()
                    : quick != null ? quick.getId() : optionsButton.getId());
            if (app != null) filter.setNextFocusDownId(app.getId());
        }

        if (appRow != null) {
            int up = filter != null ? filter.getId() : controller != null ? controller.getId()
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

    private void smoothCenterOn(ScrollView scroll, View tile) {
        if (scroll == null || tile == null) return;
        tile.post(() -> {
            int target = Math.max(0, tile.getTop() - (scroll.getHeight() - tile.getHeight()) / 2);
            if (reducedMotion) scroll.scrollTo(0, target);
            else scroll.smoothScrollTo(0, target);
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
        LinearLayout.LayoutParams params = portraitLayout
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                : wrapLinear();
        if (portraitLayout) params.bottomMargin = dp(14);
        else params.rightMargin = dp(14);
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

    private String formatRelative(long milliseconds) {
        long minutes = Math.max(0L, milliseconds / 60_000L);
        if (minutes < 1) return getString(R.string.console_relative_now);
        if (minutes < 60) {
            int count = (int) minutes;
            return getResources().getQuantityString(
                    R.plurals.console_relative_minutes, count, count);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            int count = (int) hours;
            return getResources().getQuantityString(
                    R.plurals.console_relative_hours, count, count);
        }
        if (hours < 48) return getString(R.string.console_relative_yesterday);
        int days = (int) Math.min(Integer.MAX_VALUE, hours / 24);
        return getResources().getQuantityString(
                R.plurals.console_relative_days, days, days);
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

    private static final class ControllerBatteryDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int percentage;
        private final boolean charging;
        private final int color;

        ControllerBatteryDrawable(int percentage, boolean charging, int color) {
            this.percentage = percentage;
            this.charging = charging;
            this.color = color;
        }

        @Override public void draw(Canvas canvas) {
            float scale = Math.min(getBounds().width(), getBounds().height()) / 24f;
            float x = getBounds().left + (getBounds().width() - 24f * scale) / 2f;
            float y = getBounds().top + (getBounds().height() - 24f * scale) / 2f;
            canvas.save();
            canvas.translate(x, y);
            canvas.scale(scale, scale);
            paint.setColor(color);
            paint.setStrokeWidth(1.7f);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(new RectF(5f, 4f, 19f, 22f), 2f, 2f, paint);
            canvas.drawLine(10f, 2.5f, 14f, 2.5f, paint);
            if (percentage >= 0) {
                float fill = Math.max(0f, Math.min(1f, percentage / 100f));
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(new RectF(7f, 20f - 14f * fill, 17f, 20f),
                        1f, 1f, paint);
            }
            if (charging) {
                Path bolt = new Path();
                bolt.moveTo(14f, 6f);
                bolt.lineTo(9.5f, 13f);
                bolt.lineTo(13f, 13f);
                bolt.lineTo(10.5f, 19f);
                bolt.lineTo(16f, 11f);
                bolt.lineTo(12.5f, 11f);
                bolt.close();
                paint.setColor(Color.WHITE);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(bolt, paint);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 24; }
    }

    private static final class PanelSnapshot {
        final String key;
        final List<View> children;
        final View focused;
        final Object focusedTag;

        PanelSnapshot(String key, List<View> children, View focused, Object focusedTag) {
            this.key = key;
            this.children = children;
            this.focused = focused;
            this.focusedTag = focusedTag;
        }
    }

    private enum BluetoothAction { POWER_OFF, UNPAIR }
}
