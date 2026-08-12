package com.limelight.console;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.Manifest;
import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.hardware.BatteryState;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.AppView;
import com.limelight.BuildConfig;
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
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.AppPreferences;
import com.limelight.preferences.AppStreamSettings;
import com.limelight.preferences.StreamSettings;
import com.limelight.stream.BackgroundStreamPreferences;
import com.limelight.stream.BackgroundStreamService;
import com.limelight.stream.RetainedStreamSessionCoordinator;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.QuickLaunchManager;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.SessionResumeManager;
import com.limelight.utils.ShortcutHelper;
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
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Deque;
import java.util.Date;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** TV-first dashboard adapted from Wake & Play and backed by Moonlight's internal APIs. */
public class ConsoleActivity extends Activity implements InputManager.InputDeviceListener {
    public static final String EXTRA_RETAINED_STREAM_HOME =
            "com.limelight.console.RETAINED_STREAM_HOME";
    public static final String EXTRA_RETAINED_STREAM_HOST_ID =
            "com.limelight.console.RETAINED_STREAM_HOST_ID";
    public static final String EXTRA_RETAINED_STREAM_APP_ID =
            "com.limelight.console.RETAINED_STREAM_APP_ID";
    public static final String EXTRA_RETAINED_STREAM_PLAYNITE_GAME_ID =
            "com.limelight.console.RETAINED_STREAM_PLAYNITE_GAME_ID";
    // The console UI is a product feature and must be identical in debug and release builds.
    private static final boolean CONSOLE_UI_V2 = true;
    private static final String PREFS = "console_dashboard";
    private static final int REQUEST_BLUETOOTH_CONNECT = 2201;
    private static final long CONTROLLER_REFRESH_MS = 30_000L;
    private static final long DUPLICATE_NAVIGATION_WINDOW_MS = 70L;
    private static final long PLAYNITE_REFRESH_MS = 60_000L;
    private static final long PLAYNITE_INSTALL_REFRESH_MS = 3_000L;
    private static final long VIBEPOLLO_APP_WAIT_MS = 5 * 60_000L;
    private static final long VIBEPOLLO_ENSURE_RETRY_MS = 3_000L;
    private static final long VIBEPOLLO_APP_STABLE_MS = 30_000L;
    private static final int VIBEPOLLO_APP_STABLE_POLLS = 5;
    private static final long PREVIOUS_SESSION_CLOSE_TIMEOUT_MS = 20_000L;
    private static final long LIBRARY_ENTER_TRANSITION_MS = 280L;
    private static final long LIBRARY_EXIT_TRANSITION_MS = 200L;
    private static final long LIBRARY_SHARED_ARTWORK_MS = 260L;
    private static final long HOST_WAKING_TIMEOUT_MS = 45_000L;
    private static final int EXPANDED_VISIBLE_ROWS = 3;
    private static final int EXPANDED_CACHE_ROWS_EACH_SIDE = 3;
    private static final int EXPANDED_PREFETCH_ROWS_EACH_SIDE = 5;
    private static final int EXPANDED_WINDOW_ROWS = EXPANDED_VISIBLE_ROWS
            + EXPANDED_CACHE_ROWS_EACH_SIDE * 2;
    private static final int EXPANDED_PREFETCH_ROWS = EXPANDED_VISIBLE_ROWS
            + EXPANDED_PREFETCH_ROWS_EACH_SIDE * 2;
    private static final int EXPANDED_WINDOW_WARMUP_ROWS = 3;
    private static final long EXPANDED_WINDOW_WARMUP_DELAY_MS = 150L;
    private static final long ARTWORK_FOCUS_SETTLE_MS = 260L;
    private static final long ARTWORK_CROSSFADE_MS = 480L;
    private static final long EXPANDED_NAVIGATION_INTERVAL_MS = 105L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final ExecutorService playniteExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService playniteArtworkExecutor = Executors.newFixedThreadPool(3);
    private final AtomicInteger launchGeneration = new AtomicInteger();
    private final AtomicInteger artworkGeneration = new AtomicInteger();
    private final AtomicInteger discordStatusGeneration = new AtomicInteger();
    private final AtomicInteger playniteGeneration = new AtomicInteger();
    private final AtomicInteger playniteArtworkGeneration = new AtomicInteger();
    private final AtomicInteger sunshineAppsGeneration = new AtomicInteger();
    private final Map<String, ComputerDetails> hosts = new LinkedHashMap<>();
    private final Set<String> newlyDiscoveredHosts = new LinkedHashSet<>();

    private SharedPreferences preferences;
    private ConsoleLibraryViewStateStore libraryViewStateStore;
    private ConsoleLibraryTransitionCoordinator libraryTransitionCoordinator;
    private DiskAssetLoader assetLoader;
    private ConsoleAudioEngine consoleAudioEngine;
    private ConsoleUiFeedback consoleFeedback;
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
    private boolean retainedStreamHome;
    private String retainedStreamHostId = "";
    private int retainedStreamAppId = StreamConfiguration.INVALID_APP_ID;
    private String retainedStreamPlayniteGameId = "";
    private boolean inputListenerRegistered;
    private boolean reducedMotion;
    private boolean uiSoundsEnabled;
    private boolean ambientSoundsEnabled;
    private int hostMusicVolume;
    private int menuMusicVolume;
    private int effectsVolume;
    private int backgroundStreamRetentionMinutes;
    private boolean showCarouselGameDescription;
    private boolean refreshHostsOnResume;
    private boolean initialHostsLoaded;
    private boolean showHiddenApps;
    private boolean hostSelectionVisible = true;
    private boolean initialHostSelectionResolved;
    private boolean hostSelectionLongPressConsumed;
    private String autoLoginHostUuid;

    private FrameLayout root;
    private FrameLayout homeLayer;
    private FrameLayout hostSelectionLayer;
    private HorizontalScrollView hostSelectionScroll;
    private LinearLayout hostSelectionRow;
    private LinearLayout hostSelectionLegend;
    private TextView hostSelectionClock;
    private String renderedHostSelectionSignature;
    private String hostSelectionFocusUuid;
    private final Map<String, HostSelectionTile> hostSelectionTiles = new LinkedHashMap<>();
    private final ConsoleHostStateController hostStateController =
            new ConsoleHostStateController(SystemClock::uptimeMillis);
    private LinearLayout homeContent;
    private LinearLayout dashboardHeader;
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
    private ImageView artworkBackdropNext;
    private ImageView artworkHero;
    private View artworkScrim;
    private ValueAnimator artworkScrimAnimator;
    private float artworkScrimLuminance = .42f;
    private Runnable pendingArtworkCommit;
    private File pendingArtworkFile;
    private Drawable pendingArtworkPreview;
    private String pendingArtworkKey;
    private String loadingArtworkKey;
    private String displayedArtworkKey;
    private TextView controllersLabel;
    private TextView appsLabel;
    private TextView optionsButton;
    private TextView hostSelector;
    private TextView launchPlayniteButton;
    private TextView launchDesktopButton;
    private TextView installedFilterButton;
    private TextView playniteLibraryStatus;
    private LinearLayout debugLibraryActions;
    private View debugLibrarySpacer;
    private LinearLayout selectedGameMetadata;
    private TextView selectedGameTitle;
    private TextView selectedGameFacts;
    private TextView selectedGameDescription;
    private LinearLayout expandedLibrary;
    private LinearLayout expandedLibraryHeader;
    private ScrollView expandedGridScroll;
    private GridLayout expandedGrid;
    private TextView expandedGameTitle;
    private TextView expandedGameFacts;
    private TextView expandedGameDescription;
    private TextView expandedFilterButton;
    private TextView expandedSourceFilterButton;
    private TextView expandedSortButton;
    private TextView expandedSearchButton;
    private TextView expandedPageIndicator;
    private TextView expandedCacheStatus;
    private LinearLayout expandedNavigationLegend;
    private PopupWindow playniteFilterPopup;
    private ScrollView expandedDescriptionScroll;
    private int expandedDescriptionScrollGeneration;
    private boolean expandedLibraryMode;
    private boolean libraryTransitionRunning;
    private final List<LibraryTransitionGhost> libraryTransitionGhosts = new ArrayList<>();
    private int expandedGridWindowStartRow;
    private int pendingExpandedFocusIndex = -1;
    private int pendingExpandedWindowWarmupIndex = -1;
    private boolean expandedWindowWarmupPosted;
    private Runnable expandedWindowWarmupRunnable;
    private Runnable expandedFocusRestoreRunnable;
    private boolean expandedFocusTransitionInProgress;
    private List<PlayniteDashboardItem> renderedExpandedItems = Collections.emptyList();
    private int renderedExpandedWindowStartRow = -1;
    private long lastExpandedGridNavigationAt;
    private ValueAnimator expandedGridScrollAnimator;
    private TextView quickResumeButton;
    private LinearLayout quickActions;
    private TextView quickActionHint;
    private ImageButton discordActionButton;
    private int discordIndicatorColor = 0xFF9FAAB2;
    private LinearLayout controllerRow;
    private LinearLayout appRow;
    private HorizontalScrollView controllerScroll;
    private HorizontalScrollView appScroll;
    private ScrollView appVerticalScroll;
    private boolean portraitLayout;
    private String selectedHostUuid;
    private boolean pendingInitialGameFocus;
    private View lastContentFocus;
    private Object lastContentFocusTag;
    private ControllerInfo pendingController;
    private final NavigationInputDeduplicator navigationInputDeduplicator =
            new NavigationInputDeduplicator(DUPLICATE_NAVIGATION_WINDOW_MS);
    private BluetoothAction pendingBluetoothAction;
    private int glassAccent = 0xFF73D7FF;
    private String renderedAppsSignature;
    private String renderedControllersSignature;
    private String renderedControllerDevicesSignature;
    private Future<?> playniteRequest;
    private Future<?> playniteArtworkPrefetch;
    private String playniteArtworkPrefetchSignature;
    private final List<Future<?>> playniteArtworkPrefetchTasks =
            Collections.synchronizedList(new ArrayList<>());
    private final LruCache<String, Bitmap> playniteBitmapCache =
            new LruCache<String, Bitmap>(32 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap == null ? 0 : bitmap.getAllocationByteCount();
                }
            };
    private String expandedSearchQuery = "";
    private List<PlayniteLibraryGame> currentPlayniteGames = Collections.emptyList();
    private List<PlayniteDashboardItem> renderedPlayniteItems = Collections.emptyList();
    private List<PlayniteDashboardItem> allPlayniteItems = Collections.emptyList();
    private List<PlayniteDashboardItem> unfilteredPlayniteItems = Collections.emptyList();
    private String resumePlayniteGameId = "";
    private String suspendedPlayniteGameId = "";
    private String renderedCarouselSessionSignature = "";
    private String renderedExpandedSessionSignature = "";
    private final Map<String, String> activePlayniteGameIds = new LinkedHashMap<>();
    private final Map<String, Long> activePlayniteGameResolvedAt = new LinkedHashMap<>();
    private final Map<String, Integer> lastFreshRunningAppIds = new LinkedHashMap<>();
    private final Set<String> activePlayniteGameResolutionInFlight =
            Collections.synchronizedSet(new HashSet<>());
    private String lastCarouselGameId = "";
    private String libraryTransitionGameId = "";
    private boolean pendingExpandedLibraryRestore;
    private final Set<String> vibepolloEnsureInFlight =
            Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> playniteInstallRequests = new LinkedHashMap<>();
    private final Set<String> playniteInstallObserved = new HashSet<>();
    private final Set<String> completedPlayniteInstallAnimations = new HashSet<>();
    private List<NvApp> currentSunshineApps = Collections.emptyList();
    private boolean playniteLibraryCached;
    private long playniteLibraryCachedAt;
    private boolean playniteLibraryRefreshing;
    private boolean playniteInitialLoadPending;
    private PlayniteLibraryRepository.ErrorKind playniteLibraryError;
    private String currentPlayniteHostUuid;
    private int pendingConsoleUpdateChannels;
    private boolean consoleUpdatePosted;
    private long lastDirectionalAudioInputAt;
    private long lastVolumeAxisAdjustmentAt;
    private final ViewTreeObserver.OnGlobalFocusChangeListener consoleFocusSoundListener =
            (oldFocus, newFocus) -> {
                if (newFocus != null) newFocus.setSoundEffectsEnabled(false);
                if (newFocus != null && newFocus.getTag() instanceof String) {
                    android.util.Log.d("MoonWakerFocus",
                            "focus=" + newFocus.getTag()
                                    + " expanded=" + expandedLibraryMode
                                    + " transitioning=" + expandedFocusTransitionInProgress);
                }
                if (newFocus != null && oldFocus != null
                        && SystemClock.uptimeMillis() - lastDirectionalAudioInputAt < 320L
                        && consoleAudioEngine != null) {
                    consoleAudioEngine.play(ConsoleAudioSynthesis.Cue.NAVIGATE);
                }
            };

    private final Runnable playniteRefreshCycle = new Runnable() {
        @Override public void run() {
            if (!active || loadingLayer != null && loadingLayer.getVisibility() == View.VISIBLE) return;
            ComputerDetails host = hosts.get(selectedHostUuid);
            if (host != null) requestPlayniteRefresh(host, false);
        }
    };

    private final ComputerManagerListener computerListener = (details, fresh) -> {
        ComputerDetails copy = new ComputerDetails(details);
        mainHandler.post(() -> {
            ComputerDetails previous = hosts.get(copy.uuid);
            if (fresh) reconcileAuthoritativeSession(copy);
            hostStateController.observe(copy);
            if (previous == null && initialHostsLoaded) newlyDiscoveredHosts.add(copy.uuid);
            hosts.put(copy.uuid, copy);
            queueConsoleUpdates(ConsoleUpdateChannels.diff(
                    previous, copy, selectedHostUuid));
        });
    };

    private void reconcileAuthoritativeSession(ComputerDetails host) {
        Integer previousRunningAppId = lastFreshRunningAppIds.put(host.uuid, host.runningGameId);
        if (!AuthoritativeSessionTransition.ended(previousRunningAppId,
                host.runningGameId)) return;
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        if (!host.uuid.equalsIgnoreCase(retained.hostId)) return;
        android.util.Log.i("MoonWakerSession",
                "Authoritative session ended host=" + host.uuid
                        + " previousApp=" + previousRunningAppId);
        RetainedStreamSessionCoordinator.clear();
        SessionResumeManager.clear(this);
        stopService(new Intent(this, BackgroundStreamService.class));
    }

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
            updateHostSelector();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        retainedStreamHome = this instanceof StreamHomeActivity
                || getIntent().getBooleanExtra(EXTRA_RETAINED_STREAM_HOME, false);
        if (retainedStreamHome) setTheme(R.style.ConsoleStreamHomeTheme);
        super.onCreate(state);
        if (!retainedStreamHome
                && RetainedStreamSessionCoordinator.canResumeInstantly()) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        retainedStreamHostId = normalizeId(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_HOST_ID));
        retainedStreamAppId = getIntent().getIntExtra(EXTRA_RETAINED_STREAM_APP_ID,
                StreamConfiguration.INVALID_APP_ID);
        retainedStreamPlayniteGameId = normalizeId(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_PLAYNITE_GAME_ID));
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        libraryViewStateStore = new ConsoleLibraryViewStateStore(preferences);
        reducedMotion = preferences.getBoolean("reduced_motion", false);
        libraryTransitionCoordinator = new ConsoleLibraryTransitionCoordinator(
                mainHandler, reducedMotion);
        uiSoundsEnabled = preferences.getBoolean("ui_sounds", true);
        ambientSoundsEnabled = preferences.getBoolean("ambient_sounds", true);
        hostMusicVolume = preferences.getInt("host_music_volume", 30);
        menuMusicVolume = preferences.getInt("menu_music_volume", 16);
        effectsVolume = preferences.getInt("effects_volume", 40);
        backgroundStreamRetentionMinutes = BackgroundStreamPreferences.readMinutes(this);
        showCarouselGameDescription = preferences.getBoolean(
                "show_carousel_game_description", true);
        selectedHostUuid = preferences.getString("selected_host", null);
        if (retainedStreamHome && !retainedStreamHostId.isEmpty()) {
            selectedHostUuid = retainedStreamHostId;
            hostSelectionVisible = false;
        }
        autoLoginHostUuid = preferences.getString("auto_login_host", "");
        assetLoader = new DiskAssetLoader(this);
        consoleAudioEngine = new ConsoleAudioEngine(this);
        consoleAudioEngine.setEffectsEnabled(uiSoundsEnabled);
        consoleAudioEngine.setAmbientEnabled(ambientSoundsEnabled);
        consoleAudioEngine.setHostSelectionVolume(hostMusicVolume / 100f);
        consoleAudioEngine.setMenuVolume(menuMusicVolume / 100f);
        consoleAudioEngine.setEffectsVolume(effectsVolume / 100f);
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        hostGatewayClient = new HostGatewayClient();
        hostGatewayStore = new HostGatewayStore(this);
        playniteLibraryRepository = new PlayniteLibraryRepository(hostGatewayClient,
                new PlayniteLibraryCache(this));
        playniteArtworkCache = new PlayniteArtworkCache(this);
        playniteLaunchTargetStore = new PlayniteLaunchTargetStore(this);
        quickLaunchManager = QuickLaunchManager.getInstance(this);
        shortcutHelper = new ShortcutHelper(this);
        getWindow().setFormat(retainedStreamHome
                ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE);
        root = buildUi();
        setContentView(root);
        consoleFeedback = new ConsoleUiFeedback(this, root, consoleAudioEngine, reducedMotion);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener(consoleFocusSoundListener);
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
                        ConsoleUiFeedback.makeText(ConsoleActivity.this, message, Toast.LENGTH_LONG).show();
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
        // The retained stream Home always belongs to the host backing the live stream.
        // A stale "return to hosts" request from an earlier suspend flow must not move it
        // away from that host's carousel.
        if (!retainedStreamHome) {
            String returnToHosts = SuspendedSessionStore.consumeHostSelectionRequest(this);
            if (!returnToHosts.isEmpty()) showHostSelection(returnToHosts);
        }
        if (consoleAudioEngine != null) {
            consoleAudioEngine.setMenuVisible(loadingLayer == null
                    || loadingLayer.getVisibility() != View.VISIBLE);
            consoleAudioEngine.setHostSelectionVisible(hostSelectionVisible);
            consoleAudioEngine.resume();
        }
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
        if (homeLayer != null) {
            homeLayer.setVisibility(hostSelectionVisible ? View.GONE : View.VISIBLE);
        }
        if (hostSelectionLayer != null) {
            hostSelectionLayer.setVisibility(hostSelectionVisible ? View.VISIBLE : View.GONE);
        }
        hideSystemUi();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (libraryTransitionRunning && event != null
                && (isDirectionalNavigationKey(event.getKeyCode())
                || event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A)) {
            return true;
        }
        handleConsoleAudioKey(event);
        if (hostSelectionVisible && event != null && isHostSelectionConfirmKey(
                event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (event.getRepeatCount() > 0 || event.isLongPress())) {
                ComputerDetails host = focusedHostSelection();
                if (host != null && !hostSelectionLongPressConsumed) {
                    hostSelectionLongPressConsumed = true;
                    showHostSelectionOptions(host);
                }
                return host != null;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && hostSelectionLongPressConsumed) {
                hostSelectionLongPressConsumed = false;
                return true;
            }
        }
        if (hostSelectionVisible && event != null
                && event.getAction() == KeyEvent.ACTION_UP
                && (event.getKeyCode() == KeyEvent.KEYCODE_MENU
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_START)) {
            View focused = getCurrentFocus();
            Object tag = focused != null ? focused.getTag() : null;
            if (tag instanceof String && ((String) tag).startsWith("host.select:")) {
                ComputerDetails host = hosts.get(((String) tag).substring("host.select:".length()));
                if (host != null) showHostSelectionOptions(host);
                return true;
            }
        }
        if (!hostSelectionVisible && event != null
                && event.getAction() == KeyEvent.ACTION_UP
                && (event.getKeyCode() == KeyEvent.KEYCODE_MENU
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_START)) {
            PlayniteDashboardItem item = focusedPlayniteItem();
            ComputerDetails host = hosts.get(selectedHostUuid);
            if (item != null && host != null) {
                showPlayniteGameActions(host, item);
                return true;
            }
        }
        if (playniteFilterPopup != null && playniteFilterPopup.isShowing()
                && event != null && event.getAction() == KeyEvent.ACTION_UP
                && (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            playniteFilterPopup.dismiss();
            return true;
        }
        if (expandedLibraryMode && event != null
                && event.getAction() == KeyEvent.ACTION_UP
                && (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            exitExpandedLibrary();
            return true;
        }
        if (expandedLibraryMode && event != null
                && isExpandedLibraryShortcutKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_UP) return true;
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                if (navigationInputDeduplicator.shouldSuppress(
                        event.getKeyCode(), event.getEventTime())) return true;
                return handleExpandedLibraryShortcut(event.getKeyCode());
            }
        }
        if (CONSOLE_UI_V2 && event != null
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && isDirectionalNavigationKey(event.getKeyCode())) {
            if (navigationInputDeduplicator.shouldSuppress(
                    event.getKeyCode(), event.getEventTime())) {
                android.util.Log.d("MoonWakerInput",
                        "Suppressed duplicate navigation key " + event.getKeyCode()
                                + " from device " + event.getDeviceId());
                return true;
            }
        }
        if (handleCarouselNavigation(event)) return true;
        if (handleExpandedGridNavigation(event)) return true;
        if (handleVolumeAdjustment(event)) return true;
        if (handleManualFocusNavigation(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /**
     * Moves focus without delegating directional input to ViewRootImpl. Some Android TV
     * builds play their own navigation sound there even when sound effects are disabled
     * on the focused view, which otherwise doubles MoonWaker's console cue.
     */
    private boolean handleManualFocusNavigation(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                || !isDirectionalNavigationKey(event.getKeyCode())) return false;
        View focused = getCurrentFocus();
        if (focused == null) return false;
        int direction;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                direction = View.FOCUS_LEFT;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                direction = View.FOCUS_RIGHT;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                direction = View.FOCUS_UP;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                direction = View.FOCUS_DOWN;
                break;
            default:
                return false;
        }
        View target = focused.focusSearch(direction);
        if (target != null && target != focused) target.requestFocus(direction);
        return true;
    }

    private boolean handleVolumeAdjustment(KeyEvent event) {
        return handleVolumeAdjustment(event, getCurrentFocus());
    }

    private boolean handleVolumeAdjustment(KeyEvent event, View focused) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT
                && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (focused == null || !(focused.getTag() instanceof VolumeControl)) return false;
        VolumeControl control = (VolumeControl) focused.getTag();
        int step = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -2 : 2;
        updateVolumeControl(control, control.progress.getProgress() + step, true);
        return true;
    }

    private boolean handleVolumeMotion(VolumeControl control, MotionEvent event) {
        if (control == null || event == null || event.getAction() != MotionEvent.ACTION_MOVE
                || (event.getSource() & InputDevice.SOURCE_JOYSTICK) == 0) return false;
        float axis = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        if (Math.abs(axis) < .35f) axis = event.getAxisValue(MotionEvent.AXIS_X);
        if (Math.abs(axis) < .35f) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastVolumeAxisAdjustmentAt < 40L) return true;
        lastVolumeAxisAdjustmentAt = now;
        int step = Math.abs(axis) >= .75f ? 2 : 1;
        if (axis < 0f) step = -step;
        updateVolumeControl(control, control.progress.getProgress() + step, true);
        return true;
    }

    private boolean handleCarouselNavigation(KeyEvent event) {
        if (expandedLibraryMode || hostSelectionVisible || appRow == null || event == null
                || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT
                && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        View focused = getCurrentFocus();
        if (focused == null || focused.getParent() != appRow || !focused.isFocusable()) {
            return false;
        }
        int current = appRow.indexOfChild(focused);
        if (current < 0) return false;
        int step = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
        int target = current + step;
        while (target >= 0 && target < appRow.getChildCount()) {
            View candidate = appRow.getChildAt(target);
            if (candidate.getVisibility() == View.VISIBLE && candidate.isFocusable()) {
                candidate.requestFocus();
                return true;
            }
            target += step;
        }
        return true;
    }

    private void handleConsoleAudioKey(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                || consoleAudioEngine == null) return;
        int keyCode = event.getKeyCode();
        if (isDirectionalNavigationKey(keyCode)) {
            lastDirectionalAudioInputAt = SystemClock.uptimeMillis();
            return;
        }
        if (event.getRepeatCount() != 0) return;
        if (isHostSelectionConfirmKey(keyCode)) {
            consoleAudioEngine.playSystemConfirm();
        } else if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            consoleAudioEngine.playSystemBack();
        } else if (keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            consoleAudioEngine.playSystemConfirm();
        }
    }

    private static boolean isHostSelectionConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private ComputerDetails focusedHostSelection() {
        View focused = getCurrentFocus();
        Object tag = focused != null ? focused.getTag() : null;
        if (!(tag instanceof String) || !((String) tag).startsWith("host.select:")) {
            return null;
        }
        return hosts.get(((String) tag).substring("host.select:".length()));
    }

    private PlayniteDashboardItem focusedPlayniteItem() {
        View focused = getCurrentFocus();
        Object tag = focused != null ? focused.getTag() : null;
        if (!(tag instanceof String) || !((String) tag).startsWith("playnite:")) return null;
        String stableId = ((String) tag).substring("playnite:".length());
        int index = PlayniteLibraryQuery.indexOf(allPlayniteItems, stableId);
        if (index >= 0) return allPlayniteItems.get(index);
        index = PlayniteLibraryQuery.indexOf(unfilteredPlayniteItems, stableId);
        return index >= 0 ? unfilteredPlayniteItems.get(index) : null;
    }

    private boolean handleExpandedLibraryShortcut(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                dismissPlaynitePopup();
                if (expandedFilterButton != null) {
                    expandedFilterButton.post(expandedFilterButton::requestFocus);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                resetExpandedLibraryFilters();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                dismissPlaynitePopup();
                showPlayniteSearchDialog();
                return true;
            default:
                return false;
        }
    }

    private void resetExpandedLibraryFilters() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) return;
        dismissPlaynitePopup();
        hostGatewayStore.setPlayniteLibraryFilter(host.uuid, PlayniteLibraryFilter.ALL);
        hostGatewayStore.clearPlayniteLibrarySources(host.uuid);
        hostGatewayStore.setPlayniteLibrarySort(host.uuid, PlayniteLibrarySort.RECENT);
        expandedSearchQuery = "";
        libraryViewStateStore.saveSearch(host.uuid, "");
        expandedGridWindowStartRow = 0;
        pendingExpandedFocusIndex = 0;
        renderedExpandedItems = Collections.emptyList();
        renderPlayniteLibrary(host, currentSunshineApps);
        styleFilterButton(expandedFilterButton, true);
        styleSourceFilterButton(false);
        styleSortButton(false);
        styleSearchButton(false);
        if (expandedFilterButton != null) {
            expandedFilterButton.post(expandedFilterButton::requestFocus);
        }
        ConsoleUiFeedback.makeText(this, R.string.playnite_filters_reset,
                Toast.LENGTH_SHORT).show();
    }

    private static boolean isExpandedLibraryShortcutKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR
                || keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL
                || keyCode == KeyEvent.KEYCODE_BUTTON_Y;
    }

    @Override
    protected void onPause() {
        active = false;
        if (consoleAudioEngine != null) consoleAudioEngine.pause();
        launchGeneration.incrementAndGet();
        artworkGeneration.incrementAndGet();
        mainHandler.removeCallbacks(controllerRefresh);
        mainHandler.removeCallbacks(playniteRefreshCycle);
        cancelPlayniteRequest();
        cancelPlayniteArtworkPrefetch();
        stopExpandedDescriptionAutoScroll();
        if (libraryTransitionCoordinator != null) libraryTransitionCoordinator.cancel();
        removeLibraryTransitionGhost();
        libraryTransitionRunning = false;
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
        if (root != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalFocusChangeListener(
                    consoleFocusSoundListener);
        }
        if (consoleAudioEngine != null) consoleAudioEngine.release();
        if (consoleFeedback != null) consoleFeedback.release();
        if (artworkScrimAnimator != null) artworkScrimAnimator.cancel();
        if (discordPanelController != null) discordPanelController.destroy();
        if (playniteFilterPopup != null) playniteFilterPopup.dismiss();
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
        } else if (expandedLibraryMode) {
            exitExpandedLibrary();
        } else if (retainedStreamHome
                || RetainedStreamSessionCoordinator.hasRetainedSession()
                || SessionResumeManager.hasPendingSession(this)) {
            showRetainedStreamExitConfirmation();
        } else if (hostSelectionVisible) {
            showExitConfirmation();
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
                resolveInitialHostSelection();
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
        portraitLayout = getResources().getDisplayMetrics().heightPixels
                > getResources().getDisplayMetrics().widthPixels;
        FrameLayout container = new FrameLayout(this);
        homeLayer = new FrameLayout(this);
        homeLayer.addView(new ConsoleBackdrop(this), match());

        artworkBackdrop = new ImageView(this);
        artworkBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkBackdrop.setAlpha(0f);
        homeLayer.addView(artworkBackdrop, match());

        artworkBackdropNext = new ImageView(this);
        artworkBackdropNext.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkBackdropNext.setAlpha(0f);
        homeLayer.addView(artworkBackdropNext, match());

        artworkHero = new ImageView(this);
        artworkHero.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artworkHero.setAlpha(0f);
        artworkHero.setPadding(dp(30), dp(56), dp(30), dp(56));
        if (CONSOLE_UI_V2) artworkHero.setVisibility(View.GONE);
        FrameLayout.LayoutParams hero = new FrameLayout.LayoutParams(dp(500),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL);
        hero.rightMargin = dp(16);
        homeLayer.addView(artworkHero, hero);

        artworkScrim = new View(this);
        artworkScrim.setBackground(artworkScrimDrawable(artworkScrimLuminance));
        artworkScrim.setAlpha(0f);
        homeLayer.addView(artworkScrim, match());

        homeContent = new LinearLayout(this);
        homeContent.setOrientation(LinearLayout.VERTICAL);
        homeContent.setPadding(dp(54), dp(28), dp(54), dp(24));
        homeContent.setClipChildren(false);
        homeContent.setClipToPadding(false);
        homeLayer.addView(homeContent, match());

        buildQuickActions();
        LinearLayout header = new LinearLayout(this);
        dashboardHeader = header;
        header.setOrientation(portraitLayout ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        header.setGravity(portraitLayout ? Gravity.START : Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        header.setClipToPadding(false);
        if (!CONSOLE_UI_V2) {
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
        }
        hostSelector = compactButton(getString(R.string.console_no_hosts));
        hostSelector.setOnClickListener(v -> toggleCurrentHostPower());
        optionsButton = hostSelector;
        hostSelector.setSingleLine(true);
        hostSelector.setMaxWidth(dp(CONSOLE_UI_V2 ? 260 : 430));
        if (CONSOLE_UI_V2) {
            hostSelector.setTextSize(9);
            hostSelector.setMinHeight(dp(28));
            hostSelector.setMinWidth(0);
            hostSelector.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            hostSelector.setPadding(dp(10), dp(3), dp(10), dp(3));
            hostSelector.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_console_host_power, 0, 0, 0);
            hostSelector.setCompoundDrawablePadding(dp(8));
            hostSelector.setOnFocusChangeListener((view, focused) -> {
                styleHostSelector(focused);
                updateHostPowerLabel(focused);
            });
            styleHostSelector(false);
        }
        LinearLayout.LayoutParams selectorParams = new LinearLayout.LayoutParams(
                portraitLayout ? ViewGroup.LayoutParams.MATCH_PARENT
                        : ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        if (portraitLayout) selectorParams.topMargin = dp(12);
        if (CONSOLE_UI_V2) {
            FrameLayout hostPowerSlot = new FrameLayout(this);
            FrameLayout.LayoutParams hostPowerButtonParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.CENTER_VERTICAL);
            hostPowerSlot.addView(hostSelector, hostPowerButtonParams);
            header.addView(hostPowerSlot, new LinearLayout.LayoutParams(
                    dp(300), dp(56)));

            quickActions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams toolsParams = portraitLayout
                    ? new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
                    : new LinearLayout.LayoutParams(
                            0, dp(56), 1f);
            toolsParams.leftMargin = dp(14);
            header.addView(quickActions, toolsParams);
        } else {
            header.addView(hostSelector, selectorParams);
        }
        homeContent.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (CONSOLE_UI_V2) {
            quickActionHint = text("", 9, 0xFFC5D2DC, true);
            quickActionHint.setGravity(Gravity.CENTER);
            quickActionHint.setSingleLine(true);
            quickActionHint.setVisibility(View.INVISIBLE);
            FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(16), Gravity.TOP | Gravity.START);
            hintParams.topMargin = dp(80);
            homeLayer.addView(quickActionHint, hintParams);
        }

        if (CONSOLE_UI_V2) {
            controllersLabel = sectionLabel("");
            controllerScroll = horizontalScroll();
            controllerScroll.setFillViewport(true);
            controllerRow = horizontalRow();
            controllerRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            controllerScroll.addView(controllerRow, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout quickLine = new LinearLayout(this);
        quickLine.setOrientation(portraitLayout ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        quickLine.setGravity(portraitLayout ? Gravity.START : Gravity.CENTER_VERTICAL);
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
        if (!CONSOLE_UI_V2) {
            discoveryAndSession.addView(quickResumeButton, resumeParams);
        }
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
        launchDesktopButton = text(getString(R.string.console_launch_desktop),
                12, 0xFFD7E4EA, true);
        launchDesktopButton.setId(View.generateViewId());
        launchDesktopButton.setTag("session.desktop");
        launchDesktopButton.setFocusable(true);
        launchDesktopButton.setClickable(true);
        launchDesktopButton.setGravity(Gravity.CENTER);
        launchDesktopButton.setMinWidth(dp(48));
        launchDesktopButton.setMinHeight(dp(42));
        launchDesktopButton.setPadding(dp(13), dp(5), dp(13), dp(5));
        launchDesktopButton.setOnClickListener(view -> launchDesktopSession());
        launchDesktopButton.setOnFocusChangeListener((view, focused) ->
                styleCompactButton(launchDesktopButton, focused));
        styleCompactButton(launchDesktopButton, false);
        launchDesktopButton.setEnabled(false);
        launchDesktopButton.setAlpha(.48f);
        if (!CONSOLE_UI_V2) {
            discoveryAndSession.addView(launchPlayniteButton, playniteParams);
            quickLine.addView(discoveryAndSession, portraitLayout
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (!CONSOLE_UI_V2 && portraitLayout) {
            HorizontalScrollView quickScroll = horizontalScroll();
            quickScroll.addView(quickActions, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
            quickLine.addView(quickScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        } else if (!CONSOLE_UI_V2) {
            quickLine.addView(quickActions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        }
        if (!CONSOLE_UI_V2) {
            LinearLayout.LayoutParams quickLineParams = sectionWithTop(10);
            quickLineParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            homeContent.addView(quickLine, quickLineParams);
        }

        if (!CONSOLE_UI_V2) {
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
        }

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
        installedFilterButton.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_console_filter, 0, 0, 0);
        installedFilterButton.setCompoundDrawablePadding(dp(7));
        installedFilterButton.setOnClickListener(view -> {
            if (CONSOLE_UI_V2) showPlayniteFilterSelector(installedFilterButton);
            else togglePlayniteInstalledFilter();
        });
        installedFilterButton.setOnFocusChangeListener((view, focused) ->
                styleInstalledFilter(focused));
        styleInstalledFilter(false);
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        filterParams.leftMargin = dp(8);
        installedFilterButton.setVisibility(View.GONE);
        if (!CONSOLE_UI_V2) {
            LinearLayout.LayoutParams libraryHeaderParams = sectionWithTop(2);
            libraryHeaderParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            homeContent.addView(libraryHeader, libraryHeaderParams);
        }
        appRow = horizontalRow();
        if (portraitLayout) {
            appRow.setOrientation(LinearLayout.VERTICAL);
            appVerticalScroll = new ScrollView(this);
            appVerticalScroll.setVerticalScrollBarEnabled(false);
            appVerticalScroll.addView(appRow, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            homeContent.addView(appVerticalScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(CONSOLE_UI_V2 ? 180 : 235)));
        } else {
            appScroll = horizontalScroll();
            appScroll.addView(appRow, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams appScrollParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(CONSOLE_UI_V2 ? 180 : 235));
            if (CONSOLE_UI_V2) appScrollParams.topMargin = dp(8);
            homeContent.addView(appScroll, appScrollParams);
        }
        appRow.addView(text(getString(R.string.console_choose_host), 15, 0xFFBDC4D8, false),
                new LinearLayout.LayoutParams(dp(500), ViewGroup.LayoutParams.MATCH_PARENT));
        if (CONSOLE_UI_V2) {
            debugLibraryActions = new LinearLayout(this);
            debugLibraryActions.setOrientation(LinearLayout.HORIZONTAL);
            debugLibraryActions.setGravity(Gravity.CENTER_VERTICAL);
            debugLibraryActions.setClipChildren(false);
            debugLibraryActions.setClipToPadding(false);
            LinearLayout.LayoutParams libraryActionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            libraryActionParams.rightMargin = dp(8);
            debugLibraryActions.addView(quickResumeButton, libraryActionParams);
            LinearLayout.LayoutParams playniteActionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            playniteActionParams.rightMargin = dp(8);
            debugLibraryActions.addView(launchPlayniteButton, playniteActionParams);
            debugLibraryActions.addView(launchDesktopButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
            debugLibraryActions.addView(new View(this), new LinearLayout.LayoutParams(
                    0, 1, 1f));
            libraryHeader.removeView(playniteLibraryStatus);
            playniteLibraryStatus.setTextSize(9);
            debugLibraryActions.addView(playniteLibraryStatus, wrapLinear());
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
            actionParams.topMargin = dp(5);
            homeContent.addView(debugLibraryActions, actionParams);

            selectedGameMetadata = new LinearLayout(this);
            selectedGameMetadata.setOrientation(LinearLayout.VERTICAL);
            selectedGameMetadata.setPadding(dp(12), dp(7), dp(12), dp(7));
            selectedGameMetadata.setBackground(gradient(0x30131A20, 0x180A0E12, 10));
            selectedGameTitle = text("", 14, Color.WHITE, true);
            selectedGameTitle.setSingleLine(true);
            selectedGameTitle.setEllipsize(TextUtils.TruncateAt.END);
            selectedGameFacts = text("", 9, 0xFFB8C9DC, false);
            selectedGameDescription = text("", 9, 0xFFD2D9E2, false);
            selectedGameDescription.setMaxLines(3);
            selectedGameDescription.setEllipsize(TextUtils.TruncateAt.END);
            selectedGameDescription.setVisibility(
                    showCarouselGameDescription ? View.VISIBLE : View.GONE);
            selectedGameMetadata.addView(selectedGameTitle, matchLinearWidth());
            LinearLayout.LayoutParams factsParams = matchLinearWidth();
            factsParams.topMargin = dp(4);
            selectedGameMetadata.addView(selectedGameFacts, factsParams);
            LinearLayout.LayoutParams descriptionParams = matchLinearWidth();
            descriptionParams.topMargin = dp(5);
            selectedGameMetadata.addView(selectedGameDescription, descriptionParams);
            selectedGameMetadata.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams metadataParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(showCarouselGameDescription ? 108 : 62));
            metadataParams.topMargin = dp(3);
            homeContent.addView(selectedGameMetadata, metadataParams);

            debugLibrarySpacer = new View(this);
            homeContent.addView(debugLibrarySpacer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            LinearLayout.LayoutParams controllerStrip = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
            homeContent.addView(controllerScroll, controllerStrip);

            buildExpandedLibrary();
            homeContent.addView(expandedLibrary, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        wireHomeFocusNavigation();

        container.addView(homeLayer, match());
        buildHostSelectionLayer(container);
        buildSidePanel();
        buildLoadingLayer(container);
        return container;
    }

    private void buildHostSelectionLayer(FrameLayout container) {
        hostSelectionLayer = new FrameLayout(this);
        hostSelectionLayer.setVisibility(View.VISIBLE);
        hostSelectionLayer.setBackground(new HostSelectionBackdropDrawable());

        TextView clock = text("", 11, 0xFFDDE3EB, false);
        hostSelectionClock = clock;
        clock.setGravity(Gravity.END);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(
                dp(150), dp(40), Gravity.TOP | Gravity.END);
        clockParams.topMargin = dp(28);
        clockParams.rightMargin = dp(38);
        hostSelectionLayer.addView(clock, clockParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);

        TextView title = text(getString(R.string.console_host_selection_title),
                23, Color.WHITE, false);
        title.setGravity(Gravity.CENTER);
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = text(getString(R.string.console_host_selection_subtitle),
                11, 0xFFB8C0CD, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(5);
        content.addView(subtitle, subtitleParams);

        hostSelectionScroll = new HorizontalScrollView(this);
        hostSelectionScroll.setFillViewport(true);
        hostSelectionScroll.setHorizontalScrollBarEnabled(false);
        hostSelectionScroll.setClipChildren(false);
        hostSelectionScroll.setClipToPadding(false);
        hostSelectionScroll.setPadding(dp(24), dp(10), dp(24), dp(10));
        hostSelectionRow = new LinearLayout(this);
        hostSelectionRow.setOrientation(LinearLayout.HORIZONTAL);
        hostSelectionRow.setGravity(Gravity.CENTER);
        hostSelectionRow.setClipChildren(false);
        hostSelectionRow.setClipToPadding(false);
        hostSelectionScroll.addView(hostSelectionRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(176));
        rowParams.topMargin = dp(18);
        content.addView(hostSelectionScroll, rowParams);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(250), Gravity.CENTER);
        contentParams.leftMargin = dp(34);
        contentParams.rightMargin = dp(34);
        hostSelectionLayer.addView(content, contentParams);

        hostSelectionLegend = new LinearLayout(this);
        hostSelectionLegend.setOrientation(LinearLayout.HORIZONTAL);
        hostSelectionLegend.setGravity(Gravity.CENTER_VERTICAL);
        hostSelectionLegend.setPadding(dp(9), dp(5), dp(9), dp(5));
        GradientDrawable hostLegendBackground = gradient(
                0xB8161E25, 0xD00C1116, 14);
        hostLegendBackground.setStroke(dp(1), 0x3D6E8291);
        hostSelectionLegend.setBackground(hostLegendBackground);
        rebuildHostSelectionLegend(Collections.emptyList());
        FrameLayout.LayoutParams legendParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        legendParams.rightMargin = dp(38);
        legendParams.bottomMargin = dp(20);
        hostSelectionLayer.addView(hostSelectionLegend, legendParams);

        homeLayer.setVisibility(View.GONE);
        container.addView(hostSelectionLayer, match());
        renderHostSelection();
    }

    private void resolveInitialHostSelection() {
        if (initialHostSelectionResolved) return;
        initialHostSelectionResolved = true;
        if (retainedStreamHome) {
            ComputerDetails retainedHost = hosts.get(retainedStreamHostId);
            if (retainedHost != null) {
                selectHost(retainedHost, true);
                return;
            }
        }
        ComputerDetails automatic = autoLoginHostUuid == null || autoLoginHostUuid.isEmpty()
                ? null : hosts.get(autoLoginHostUuid);
        if (automatic != null) {
            selectHost(automatic, false);
        } else {
            showHostSelection(selectedHostUuid);
        }
    }

    private void showHostSelection(String focusUuid) {
        hostSelectionVisible = true;
        if (consoleAudioEngine != null) {
            consoleAudioEngine.setHostSelectionVisible(true);
        }
        hostSelectionFocusUuid = focusUuid;
        stopAppListPoller();
        cancelPlayniteArtworkPrefetch();
        if (loadingLayer != null) loadingLayer.setVisibility(View.GONE);
        if (homeLayer != null) homeLayer.setVisibility(View.GONE);
        if (hostSelectionLayer != null) hostSelectionLayer.setVisibility(View.VISIBLE);
        renderHostSelection();
        requestHostSelectionFocus();
    }

    private void renderHostSelection() {
        if (hostSelectionRow == null) return;
        if (hostSelectionClock != null) {
            hostSelectionClock.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
        }
        List<ComputerDetails> sorted = new ArrayList<>(hosts.values());
        sorted.removeIf(host -> newlyDiscoveredHosts.contains(host.uuid));
        sorted.sort(Comparator.comparing(host -> host.name, String.CASE_INSENSITIVE_ORDER));
        StringBuilder signature = new StringBuilder();
        for (ComputerDetails host : sorted) {
            signature.append(host.uuid).append(':').append(host.name).append('|');
        }
        if (signature.toString().equals(renderedHostSelectionSignature)) {
            for (ComputerDetails host : sorted) updateHostSelectionTile(host);
            return;
        }

        renderedHostSelectionSignature = signature.toString();
        hostSelectionTiles.clear();
        hostSelectionRow.removeAllViews();
        hostSelectionRow.addView(createAddHostTile(), hostSelectionTileParams());
        for (ComputerDetails host : sorted) {
            View tile = createHostSelectionTile(host);
            hostSelectionRow.addView(tile, hostSelectionTileParams());
        }
        wireHostSelectionFocus();
        if (hostSelectionVisible) requestHostSelectionFocus();
    }

    private LinearLayout.LayoutParams hostSelectionTileParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(112), dp(160));
        params.leftMargin = dp(7);
        params.rightMargin = dp(7);
        return params;
    }

    private View createAddHostTile() {
        LinearLayout tile = hostSelectionTileBase();
        tile.setTag("host.add");
        FrameLayout avatar = new FrameLayout(this);
        TextView plus = text("+", 31, Color.WHITE, false);
        plus.setGravity(Gravity.CENTER);
        avatar.addView(plus, match());
        styleHostSelectionAvatar(avatar, false, true);
        tile.addView(avatar, new LinearLayout.LayoutParams(dp(78), dp(78)));
        TextView label = text(getString(R.string.console_add_host_short),
                10, Color.WHITE, false);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        labelParams.topMargin = dp(7);
        tile.addView(label, labelParams);
        TextView hint = text("", 8, 0xFFC8D0DB, false);
        hint.setGravity(Gravity.CENTER);
        tile.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
        tile.setOnClickListener(view -> showAddHostPanel());
        tile.setOnFocusChangeListener((view, focused) -> {
            styleHostSelectionAvatar(avatar, focused, true);
            hint.setText(focused ? getString(R.string.console_select_hint) : "");
            animateScale(tile, focused ? 1.07f : 1f);
            if (focused) smoothCenterOn(hostSelectionScroll, tile);
        });
        return tile;
    }

    private View createHostSelectionTile(ComputerDetails host) {
        LinearLayout tile = hostSelectionTileBase();
        tile.setTag("host.select:" + host.uuid);
        FrameLayout avatar = new FrameLayout(this);
        ImageView artwork = new ImageView(this);
        artwork.setImageDrawable(new HostAvatarDrawable(host.uuid));
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setAlpha(.88f);
        FrameLayout.LayoutParams artworkParams = new FrameLayout.LayoutParams(
                dp(70), dp(70), Gravity.CENTER);
        avatar.addView(artwork, artworkParams);
        TextView initials = text(hostInitials(host.name), 14, Color.WHITE, true);
        initials.setGravity(Gravity.CENTER);
        initials.setShadowLayer(dp(3), 0f, dp(1), 0xE0000000);
        avatar.addView(initials, match());
        styleHostSelectionAvatar(avatar, false, false);
        tile.addView(avatar, new LinearLayout.LayoutParams(dp(78), dp(78)));

        TextView name = text(host.name, 10, Color.WHITE, false);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        nameParams.topMargin = dp(5);
        tile.addView(name, nameParams);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER);
        View dot = new View(this);
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        TextView status = text("", 8, 0xFFC7CED8, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(20));
        statusParams.leftMargin = dp(4);
        statusRow.addView(status, statusParams);
        tile.addView(statusRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));

        TextView options = text(getString(R.string.console_host_options_hint),
                8, 0xFFDCE3ED, false);
        options.setGravity(Gravity.CENTER);
        options.setVisibility(View.INVISIBLE);
        tile.addView(options, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));

        HostSelectionTile views = new HostSelectionTile(tile, avatar, dot, status, options);
        hostSelectionTiles.put(host.uuid, views);
        updateHostSelectionTile(host);
        tile.setOnClickListener(view -> activateHostFromSelection(host.uuid));
        tile.setOnLongClickListener(view -> {
            ComputerDetails current = hosts.get(host.uuid);
            if (current != null) showHostSelectionOptions(current);
            return true;
        });
        tile.setOnFocusChangeListener((view, focused) -> {
            styleHostSelectionAvatar(avatar, focused, false);
            options.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            animateScale(tile, focused ? 1.07f : 1f);
            if (focused) {
                hostSelectionFocusUuid = host.uuid;
                smoothCenterOn(hostSelectionScroll, tile);
            }
        });
        return tile;
    }

    private String hostInitials(String name) {
        if (name == null || name.trim().isEmpty()) return "PC";
        String[] words = name.trim().split("\\s+");
        if (words.length > 1) {
            return (words[0].substring(0, 1)
                    + words[words.length - 1].substring(0, 1)).toUpperCase(Locale.ROOT);
        }
        String value = words[0];
        return value.substring(0, Math.min(2, value.length())).toUpperCase(Locale.ROOT);
    }

    private LinearLayout hostSelectionTileBase() {
        LinearLayout tile = new LinearLayout(this);
        tile.setId(View.generateViewId());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(5), dp(5), dp(5), dp(2));
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClickable(true);
        tile.setSoundEffectsEnabled(false);
        tile.setClipChildren(false);
        tile.setClipToPadding(false);
        return tile;
    }

    private void styleHostSelectionAvatar(View avatar, boolean focused, boolean add) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(add ? (focused ? 0x704D5968 : 0x45434A54) : 0x24000000);
        ring.setStroke(dp(focused ? 2 : 1),
                focused ? 0xFFF2F6FA : add ? 0x406E7782 : 0x507C8792);
        avatar.setBackground(ring);
        avatar.setElevation(dp(focused ? 8 : 1));
    }

    private void updateHostSelectionTile(ComputerDetails host) {
        HostSelectionTile tile = hostSelectionTiles.get(host.uuid);
        if (tile == null) return;
        ConsoleHostPresentation.State state = consoleHostState(host);
        String status = hostStatus(host);
        tile.status.setText(status);
        GradientDrawable lamp = new GradientDrawable();
        lamp.setShape(GradientDrawable.OVAL);
        lamp.setColor(ConsoleHostPresentation.color(state));
        tile.dot.setBackground(lamp);
        tile.root.setContentDescription(getString(R.string.console_host_selection_description,
                host.name, status));
    }

    private void requestHostSelectionFocus() {
        if (!hostSelectionVisible || hostSelectionRow == null) return;
        View target = null;
        String uuid = hostSelectionFocusUuid != null ? hostSelectionFocusUuid : selectedHostUuid;
        if (uuid != null) target = hostSelectionRow.findViewWithTag("host.select:" + uuid);
        if (target == null) target = hostSelectionRow.findViewWithTag("host.add");
        if (target != null) target.post(target::requestFocus);
    }

    private void wireHostSelectionFocus() {
        List<View> focusable = new ArrayList<>();
        collectFocusable(hostSelectionRow, focusable);
        for (int index = 0; index < focusable.size(); index++) {
            View current = focusable.get(index);
            current.setNextFocusLeftId(focusable.get(Math.max(0, index - 1)).getId());
            current.setNextFocusRightId(
                    focusable.get(Math.min(focusable.size() - 1, index + 1)).getId());
            current.setNextFocusUpId(current.getId());
            current.setNextFocusDownId(current.getId());
        }
    }

    private void activateHostFromSelection(String uuid) {
        ComputerDetails host = hosts.get(uuid);
        if (host == null) return;
        if (SuspendedSessionStore.load(this, host.uuid) != null) {
            newlyDiscoveredHosts.remove(uuid);
            selectHost(host, true);
            return;
        }
        if (host.pairState != PairingManager.PairState.PAIRED) {
            pairHost(host);
            return;
        }
        newlyDiscoveredHosts.remove(uuid);
        selectHost(host, true);
    }

    private void buildExpandedLibrary() {
        expandedLibrary = new LinearLayout(this);
        expandedLibrary.setOrientation(LinearLayout.VERTICAL);
        expandedLibrary.setGravity(Gravity.TOP);
        expandedLibrary.setVisibility(View.GONE);
        expandedLibrary.setClipChildren(false);
        expandedLibrary.setClipToPadding(false);

        expandedLibraryHeader = new LinearLayout(this);
        expandedLibraryHeader.setOrientation(LinearLayout.HORIZONTAL);
        expandedLibraryHeader.setGravity(Gravity.CENTER_VERTICAL);
        expandedLibraryHeader.setClipChildren(false);
        expandedLibraryHeader.setClipToPadding(false);
        expandedLibrary.addView(expandedLibraryHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.TOP);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        expandedLibrary.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(4), dp(16), dp(18), dp(12));
        expandedGameTitle = text("", 18, Color.WHITE, true);
        expandedGameTitle.setMaxLines(2);
        expandedGameTitle.setEllipsize(TextUtils.TruncateAt.END);
        expandedGameFacts = text("", 10, 0xFFB8C9DC, false);
        expandedGameFacts.setMaxLines(3);
        expandedGameDescription = text("", 10, 0xFFE0E5EA, false);
        expandedGameDescription.setLineSpacing(dp(1), 1f);
        details.addView(expandedGameTitle, matchLinearWidth());
        LinearLayout.LayoutParams expandedFactsParams = matchLinearWidth();
        expandedFactsParams.topMargin = dp(12);
        details.addView(expandedGameFacts, expandedFactsParams);
        View divider = new View(this);
        divider.setBackgroundColor(0x6073D7FF);
        LinearLayout.LayoutParams dividerParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(12);
        dividerParams.bottomMargin = dp(12);
        details.addView(divider, dividerParams);
        expandedDescriptionScroll = new ScrollView(this);
        expandedDescriptionScroll.setFocusable(false);
        expandedDescriptionScroll.setClickable(false);
        expandedDescriptionScroll.setVerticalScrollBarEnabled(false);
        expandedDescriptionScroll.setFadingEdgeLength(dp(20));
        expandedDescriptionScroll.setVerticalFadingEdgeEnabled(true);
        expandedDescriptionScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        expandedDescriptionScroll.setPadding(0, dp(5), dp(7), dp(5));
        expandedDescriptionScroll.addView(expandedGameDescription,
                new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        details.addView(expandedDescriptionScroll,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        expandedFilterButton = text(getString(R.string.playnite_installed),
                11, 0xFFD7E4EA, true);
        expandedFilterButton.setId(View.generateViewId());
        expandedFilterButton.setFocusable(true);
        expandedFilterButton.setClickable(true);
        expandedFilterButton.setGravity(Gravity.CENTER);
        expandedFilterButton.setMinHeight(dp(42));
        expandedFilterButton.setPadding(dp(12), dp(4), dp(12), dp(4));
        expandedFilterButton.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_console_filter, 0, 0, 0);
        expandedFilterButton.setCompoundDrawablePadding(dp(7));
        expandedFilterButton.setOnClickListener(view ->
                showPlayniteFilterSelector(expandedFilterButton));
        expandedFilterButton.setOnFocusChangeListener((view, focused) ->
        {
            styleFilterButton(expandedFilterButton, focused);
            if (focused) stopExpandedDescriptionAutoScroll();
        });
        LinearLayout.LayoutParams expandedFilterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        expandedFilterParams.rightMargin = dp(8);
        expandedLibraryHeader.addView(expandedFilterButton, expandedFilterParams);

        expandedSourceFilterButton = text(getString(R.string.playnite_sources_all),
                11, 0xFFD7E4EA, true);
        expandedSourceFilterButton.setId(View.generateViewId());
        expandedSourceFilterButton.setFocusable(true);
        expandedSourceFilterButton.setClickable(true);
        expandedSourceFilterButton.setGravity(Gravity.CENTER);
        expandedSourceFilterButton.setMinHeight(dp(42));
        expandedSourceFilterButton.setPadding(dp(12), dp(4), dp(12), dp(4));
        expandedSourceFilterButton.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_console_platforms, 0, 0, 0);
        expandedSourceFilterButton.setCompoundDrawablePadding(dp(7));
        expandedSourceFilterButton.setOnClickListener(view ->
                showPlayniteSourceSelector(expandedSourceFilterButton));
        expandedSourceFilterButton.setOnFocusChangeListener((view, focused) -> {
            styleSourceFilterButton(focused);
            if (focused) stopExpandedDescriptionAutoScroll();
        });
        LinearLayout.LayoutParams expandedSourceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        expandedSourceParams.rightMargin = dp(8);
        expandedLibraryHeader.addView(expandedSourceFilterButton, expandedSourceParams);

        expandedSortButton = expandedHeaderButton(getString(R.string.playnite_sort_recent),
                R.drawable.ic_console_sort);
        expandedSortButton.setOnClickListener(view -> showPlayniteSortSelector(
                expandedSortButton));
        expandedSortButton.setOnFocusChangeListener((view, focused) -> {
            styleSortButton(focused);
            if (focused) stopExpandedDescriptionAutoScroll();
        });
        LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        sortParams.rightMargin = dp(8);
        expandedLibraryHeader.addView(expandedSortButton, sortParams);

        expandedSearchButton = expandedHeaderButton(getString(R.string.playnite_search),
                R.drawable.ic_console_search);
        expandedSearchButton.setOnClickListener(view -> showPlayniteSearchDialog());
        expandedSearchButton.setOnFocusChangeListener((view, focused) ->
                styleSearchButton(focused));
        expandedLibraryHeader.addView(expandedSearchButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));

        expandedPageIndicator = text("", 9, 0xFF8F9AAF, false);
        expandedPageIndicator.setGravity(Gravity.CENTER_HORIZONTAL);
        expandedPageIndicator.setFocusable(false);
        LinearLayout.LayoutParams pageIndicatorParams = matchLinearWidth();
        pageIndicatorParams.topMargin = dp(7);
        details.addView(expandedPageIndicator, pageIndicatorParams);

        expandedCacheStatus = text("", 8, 0xFF9EADBD, false);
        expandedCacheStatus.setGravity(Gravity.CENTER_HORIZONTAL);
        expandedCacheStatus.setFocusable(false);
        expandedCacheStatus.setMaxLines(2);
        LinearLayout.LayoutParams cacheStatusParams = matchLinearWidth();
        cacheStatusParams.topMargin = dp(3);
        details.addView(expandedCacheStatus, cacheStatusParams);

        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(
                dp(205), ViewGroup.LayoutParams.MATCH_PARENT);
        detailsParams.rightMargin = dp(16);
        body.addView(details, detailsParams);

        FrameLayout expandedGridFrame = new FrameLayout(this);
        expandedGridFrame.setClipChildren(true);
        expandedGridFrame.setClipToPadding(true);

        expandedGridScroll = new ScrollView(this);
        expandedGridScroll.setVerticalScrollBarEnabled(false);
        expandedGridScroll.setFadingEdgeLength(dp(48));
        expandedGridScroll.setVerticalFadingEdgeEnabled(true);
        expandedGridScroll.setClipChildren(true);
        expandedGridScroll.setClipToPadding(true);
        expandedGridScroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                       oldLeft, oldTop, oldRight, oldBottom) ->
                view.setClipBounds(new Rect(0, 0,
                        Math.max(0, right - left), Math.max(0, bottom - top))));
        expandedGrid = new GridLayout(this);
        expandedGrid.setOrientation(GridLayout.HORIZONTAL);
        expandedGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        expandedGrid.setUseDefaultMargins(false);
        expandedGrid.setClipChildren(false);
        expandedGrid.setClipToPadding(false);
        expandedGrid.setPadding(dp(5), dp(14), dp(5), dp(70));
        expandedGridScroll.addView(expandedGrid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        expandedGridFrame.addView(expandedGridScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        expandedNavigationLegend = new LinearLayout(this);
        expandedNavigationLegend.setOrientation(LinearLayout.HORIZONTAL);
        expandedNavigationLegend.setGravity(Gravity.CENTER_VERTICAL);
        expandedNavigationLegend.setPadding(dp(10), dp(6), dp(10), dp(6));
        expandedNavigationLegend.setFocusable(false);
        expandedNavigationLegend.setClickable(false);
        GradientDrawable legendBackground = gradient(0xE61A242B, 0xF20C1116, 14);
        legendBackground.setStroke(dp(1), 0x4D6E8291);
        expandedNavigationLegend.setBackground(legendBackground);
        rebuildExpandedNavigationLegend(Collections.emptyList());
        FrameLayout.LayoutParams legendParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        legendParams.setMargins(dp(8), dp(8), dp(14), dp(12));
        expandedGridFrame.addView(expandedNavigationLegend, legendParams);

        body.addView(expandedGridFrame, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void rebuildExpandedNavigationLegend(List<ControllerInfo> controllers) {
        if (expandedNavigationLegend == null) return;
        boolean playStation = usesPlayStationButtons(controllers);
        expandedNavigationLegend.removeAllViews();
        addNavigationLegendItem("R3", 0xFF26343E,
                getString(R.string.playnite_legend_filters));
        addNavigationLegendItem("L3", 0xFF26343E,
                getString(R.string.playnite_legend_reset));
        addNavigationLegendItem(playStation ? "△" : "Y",
                playStation ? 0xFF26343E : 0xFFD3A900,
                getString(R.string.playnite_legend_search));
        addNavigationLegendItem(playStation ? "○" : "B",
                playStation ? 0xFF26343E : 0xFFC74444,
                getString(R.string.playnite_legend_back));
    }

    private boolean usesPlayStationButtons(List<ControllerInfo> controllers) {
        for (ControllerInfo controller : controllers) {
            String name = controller.name.toLowerCase(Locale.ROOT);
            if (name.contains("dualsense") || name.contains("dualshock")
                    || name.contains("playstation") || name.contains("sony interactive")
                    || name.contains("wireless controller")) {
                return true;
            }
        }
        return false;
    }

    private void rebuildHostSelectionLegend(List<ControllerInfo> controllers) {
        if (hostSelectionLegend == null) return;
        boolean playStation = usesPlayStationButtons(controllers);
        String selectGlyph = controllers.isEmpty() ? "OK" : playStation ? "×" : "A";
        hostSelectionLegend.removeAllViews();
        addHostSelectionLegendItem(selectGlyph,
                getString(R.string.console_select_hint));
        addHostSelectionLegendItem("≡",
                getString(R.string.console_host_options_label));
    }

    private void addHostSelectionLegendItem(String glyph, String label) {
        if (hostSelectionLegend.getChildCount() > 0) {
            View separator = new View(this);
            separator.setBackgroundColor(0x336E8291);
            LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(
                    dp(1), dp(18));
            separatorParams.setMargins(dp(9), 0, dp(9), 0);
            hostSelectionLegend.addView(separator, separatorParams);
        }
        TextView button = text(glyph, glyph.length() > 1 ? 7 : 11,
                Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0xFF26343E);
        background.setStroke(dp(1), 0xBFE8F1F5);
        button.setBackground(background);
        hostSelectionLegend.addView(button, new LinearLayout.LayoutParams(dp(23), dp(23)));
        TextView description = text(label, 9, 0xFFD7E4EA, true);
        LinearLayout.LayoutParams descriptionParams = wrapLinear();
        descriptionParams.leftMargin = dp(5);
        hostSelectionLegend.addView(description, descriptionParams);
    }

    private void addNavigationLegendItem(String glyph, int glyphColor, String label) {
        if (expandedNavigationLegend.getChildCount() > 0) {
            View separator = new View(this);
            separator.setBackgroundColor(0x336E8291);
            LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(
                    dp(1), dp(20));
            separatorParams.setMargins(dp(10), 0, dp(10), 0);
            expandedNavigationLegend.addView(separator, separatorParams);
        }
        TextView button = text(glyph, glyph.length() > 1 ? 8 : 12, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setShape(GradientDrawable.OVAL);
        buttonBackground.setColor(glyphColor);
        buttonBackground.setStroke(dp(1), 0xBFE8F1F5);
        button.setBackground(buttonBackground);
        expandedNavigationLegend.addView(button, new LinearLayout.LayoutParams(dp(25), dp(25)));
        TextView description = text(label, 10, 0xFFD7E4EA, true);
        LinearLayout.LayoutParams descriptionParams = wrapLinear();
        descriptionParams.leftMargin = dp(6);
        expandedNavigationLegend.addView(description, descriptionParams);
    }

    private TextView expandedHeaderButton(String label, int icon) {
        TextView button = text(label, 11, 0xFFD7E4EA, true);
        button.setId(View.generateViewId());
        button.setFocusable(true);
        button.setClickable(true);
        button.setSoundEffectsEnabled(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(42));
        button.setPadding(dp(12), dp(4), dp(12), dp(4));
        button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(7));
        return button;
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
            if (handleVolumeAdjustment(event, sideDialog.getCurrentFocus())) return true;
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
        if (selectedHostUuid != null && !hosts.containsKey(selectedHostUuid)) {
            selectedHostUuid = null;
        }
        renderHostSelection();
        updateHostSelector();
        refreshDiscordIndicator();
        wireHomeFocusNavigation();
        ComputerDetails selected = hosts.get(selectedHostUuid);
        if (!hostSelectionVisible && selected != null
                && appRow != null && appRow.getChildCount() <= 1) {
            selectHost(selected, false);
        }
    }

    private void queueConsoleUpdates(int channels) {
        if (channels == ConsoleUpdateChannels.NONE) return;
        pendingConsoleUpdateChannels |= channels;
        if (consoleUpdatePosted) return;
        consoleUpdatePosted = true;
        mainHandler.post(this::flushConsoleUpdates);
    }

    private void flushConsoleUpdates() {
        consoleUpdatePosted = false;
        int channels = pendingConsoleUpdateChannels;
        pendingConsoleUpdateChannels = ConsoleUpdateChannels.NONE;
        ComputerDetails selected = hosts.get(selectedHostUuid);

        if (ConsoleUpdateChannels.has(channels, ConsoleUpdateChannels.HOST_SELECTION)) {
            renderHostSelection();
        }
        if (ConsoleUpdateChannels.has(channels, ConsoleUpdateChannels.SELECTED_HOST)) {
            updateHostSelector();
        }
        if (ConsoleUpdateChannels.has(channels, ConsoleUpdateChannels.INTEGRATIONS)) {
            refreshDiscordIndicator();
        }
        if (selected != null && ConsoleUpdateChannels.has(
                channels, ConsoleUpdateChannels.APPLICATIONS)) {
            renderAppsAsync(selected);
        } else if (selected != null && ConsoleUpdateChannels.has(
                channels, ConsoleUpdateChannels.SESSION)
                && !currentPlayniteGames.isEmpty()) {
            renderPlayniteLibrary(selected, currentSunshineApps);
        }
        if (ConsoleUpdateChannels.has(channels, ConsoleUpdateChannels.HOST_SELECTION)) {
            wireHomeFocusNavigation();
        }
    }

    private void updateHostSelector() {
        if (hostSelector == null) return;
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) {
            hostSelector.setText(getString(R.string.console_no_hosts));
            hostSelector.setContentDescription(getString(R.string.console_no_hosts));
            hostSelector.setEnabled(false);
            hostSelector.setAlpha(.45f);
        } else {
            ConsoleHostPresentation.State state = consoleHostState(host);
            updateHostPowerLabel(hostSelector.hasFocus());
            hostSelector.setContentDescription(state == ConsoleHostPresentation.State.WAKING
                    ? getString(R.string.console_host_waking_description, host.name)
                    : getString(host.state == ComputerDetails.State.ONLINE
                            ? R.string.console_host_power_sleep_description
                            : R.string.console_host_power_wake_description, host.name));
            hostSelector.setEnabled(true);
            hostSelector.setAlpha(1f);
        }
        updateQuickResumeButton(host);
    }

    private void updateHostPowerLabel(boolean focused) {
        if (hostSelector == null) return;
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) return;
        ConsoleHostPresentation.State state = consoleHostState(host);
        boolean online = host.state == ComputerDetails.State.ONLINE;
        String value;
        if (focused && state == ConsoleHostPresentation.State.WAKING) {
            value = getString(R.string.console_host_power_waking, host.name);
        } else if (focused) {
            value = getString(online ? R.string.console_host_power_focused_sleep
                    : R.string.console_host_power_focused_wake, host.name);
        } else {
            value = getString(R.string.console_host_power_indicator,
                    host.name, compactHostStatus(host));
        }
        SpannableString label = new SpannableString(value);
        label.setSpan(new ForegroundColorSpan(ConsoleHostPresentation.color(state)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        hostSelector.setText(label);
    }

    private String compactHostStatus(ComputerDetails host) {
        SuspendedSessionStore.Session suspended = SuspendedSessionStore.load(this, host.uuid);
        if (suspended != null && suspended.resumedAt == 0L) {
            String title = suspended.title.isEmpty()
                    ? getString(R.string.console_game) : suspended.title;
            if (host.state != ComputerDetails.State.ONLINE) {
                SuspendedSessionStore.markSleepObserved(this, suspended);
                return getString(R.string.console_status_suspended_short, title);
            }
            return suspended.sleepObservedAt == 0L
                    && !hostStateController.isWaking(host.uuid)
                    ? getString(R.string.console_status_suspending_short)
                    : getString(R.string.console_status_suspended_short, title);
        }
        return hostStatus(host);
    }

    private void toggleCurrentHostPower() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) return;
        if (host.state == ComputerDetails.State.ONLINE) {
            confirmSleepHost(host);
        } else {
            wakeHost(host);
        }
    }

    private void updateQuickResumeButton(ComputerDetails host) {
        if (quickResumeButton == null) return;
        boolean activeSession = host != null && ConsoleActionCatalog.isOnline(host)
                && ConsoleActionCatalog.isPaired(host) && host.runningGameId != 0;
        boolean visible = activeSession && !CONSOLE_UI_V2;
        if (CONSOLE_UI_V2) resolveActivePlayniteGame(host, activeSession);
        boolean restoreFocus = quickResumeButton.hasFocus() && !visible;
        quickResumeButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        quickResumeButton.setEnabled(visible);
        if (!CONSOLE_UI_V2 && launchPlayniteButton != null) {
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
        } else if (restoreFocus) {
            View fallback = launchPlayniteButton != null && launchPlayniteButton.isEnabled()
                    ? launchPlayniteButton : launchDesktopButton;
            if (fallback == null) fallback = firstFocusableChild(quickActions);
            View target = fallback != null ? fallback : hostSelector;
            target.post(target::requestFocus);
        }
        wireHomeFocusNavigation();
    }

    private void resolveActivePlayniteGame(ComputerDetails host, boolean activeSession) {
        if (host == null) return;
        if (!activeSession) {
            boolean changed = activePlayniteGameIds.remove(host.uuid) != null;
            activePlayniteGameResolvedAt.remove(host.uuid);
            activePlayniteGameResolutionInFlight.remove(host.uuid);
            boolean visibleResumeMarker = host.uuid.equals(selectedHostUuid)
                    && !resumePlayniteGameId.isEmpty();
            if ((changed || visibleResumeMarker) && host.uuid.equals(selectedHostUuid)) {
                android.util.Log.i("MoonWakerSession",
                        "Clearing resume marker because Sunshine reports no active session"
                                + " host=" + host.uuid + " game=" + resumePlayniteGameId);
                renderPlayniteLibrary(host, currentSunshineApps);
            }
            return;
        }
        long now = SystemClock.uptimeMillis();
        Long lastResolvedValue = activePlayniteGameResolvedAt.get(host.uuid);
        long lastResolved = lastResolvedValue != null ? lastResolvedValue : 0L;
        if (now - lastResolved < 5_000L
                || !activePlayniteGameResolutionInFlight.add(host.uuid)) return;
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, address);
        if (connection == null) {
            activePlayniteGameResolutionInFlight.remove(host.uuid);
            return;
        }
        int expectedRunningGameId = host.runningGameId;
        executor.execute(() -> {
            String gameId = "";
            try {
                HostGatewayClient.PlayniteCurrentGame current =
                        hostGatewayClient.getPlayniteCurrentGame(connection);
                if ("running".equalsIgnoreCase(current.state)
                        && HostGatewayClient.isPlayniteId(current.id)) {
                    gameId = current.id;
                }
            } catch (IOException ignored) {
                // The Sunshine app mapping remains available as a fallback.
            }
            String resolvedGameId = gameId;
            mainHandler.post(() -> {
                activePlayniteGameResolutionInFlight.remove(host.uuid);
                ComputerDetails latestHost = currentHost(host.uuid);
                boolean sessionStillMatches = latestHost != null
                        && ConsoleActionCatalog.isOnline(latestHost)
                        && ConsoleActionCatalog.isPaired(latestHost)
                        && latestHost.runningGameId == expectedRunningGameId
                        && expectedRunningGameId != 0;
                if (!sessionStillMatches) {
                    android.util.Log.i("MoonWakerSession",
                            "Discarding stale Playnite session response host=" + host.uuid
                                    + " expectedApp=" + expectedRunningGameId
                                    + " resolvedGame=" + resolvedGameId);
                    boolean removed = activePlayniteGameIds.remove(host.uuid) != null;
                    activePlayniteGameResolvedAt.remove(host.uuid);
                    if (host.uuid.equals(selectedHostUuid)
                            && (removed || !resumePlayniteGameId.isEmpty())) {
                        renderPlayniteLibrary(latestHost, currentSunshineApps);
                    }
                    return;
                }
                activePlayniteGameResolvedAt.put(host.uuid, SystemClock.uptimeMillis());
                String previous = activePlayniteGameIds.get(host.uuid);
                android.util.Log.i("MoonWakerSession",
                        "Resolved active Playnite session host=" + host.uuid
                                + " app=" + expectedRunningGameId
                                + " game=" + resolvedGameId);
                if (resolvedGameId.isEmpty()) activePlayniteGameIds.remove(host.uuid);
                else activePlayniteGameIds.put(host.uuid, resolvedGameId);
                if (active && host.uuid.equals(selectedHostUuid)
                        && !Objects.equals(previous, resolvedGameId)) {
                    renderPlayniteLibrary(currentHost(host.uuid), currentSunshineApps);
                }
            });
        });
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
        SuspendedSessionStore.Session suspended = SuspendedSessionStore.load(this, host.uuid);
        if (suspended != null && suspended.resumedAt > 0L
                && host.state == ComputerDetails.State.ONLINE && host.runningGameId == 0) {
            SuspendedSessionStore.clear(this, host.uuid);
            suspended = null;
        }
        if (suspended != null && suspended.resumedAt == 0L) {
            String title = suspended.title.isEmpty()
                    ? getString(R.string.console_game) : suspended.title;
            if (host.state != ComputerDetails.State.ONLINE) {
                SuspendedSessionStore.markSleepObserved(this, suspended);
                return getString(R.string.console_status_sleeping_suspended_game, title);
            }
            if (suspended.sleepObservedAt == 0L
                    && !hostStateController.isWaking(host.uuid)) {
                return getString(R.string.console_status_suspending_game, title);
            }
            return getString(R.string.console_status_suspended_game, title);
        }
        HostSleepStateStore.State sleepState = HostSleepStateStore.load(this, host.uuid);
        if (sleepState != null) {
            if (host.state != ComputerDetails.State.ONLINE) {
                HostSleepStateStore.markObserved(this, host.uuid, sleepState);
                return getString(R.string.console_status_asleep);
            }
            if (sleepState.sleepObservedAt == 0L) {
                return getString(R.string.console_status_suspending_short);
            }
            HostSleepStateStore.clear(this, host.uuid);
        }
        ConsoleHostPresentation.State state = consoleHostState(host);
        switch (state) {
            case ACTIVE_SESSION:
                String appName = findAppName(host, host.runningGameId);
                return appName == null ? getString(R.string.console_status_active_session)
                        : getString(R.string.console_status_active_app, appName);
            case WAKING:
                return getString(R.string.console_status_waking);
            case ASLEEP:
                return getString(R.string.console_status_asleep);
            case UNREACHABLE:
                return getString(R.string.console_status_unreachable);
            case CONNECTING:
                return getString(R.string.console_status_connecting);
            case UNPAIRED:
                return getString(R.string.console_status_unpaired);
            case OFFLINE:
                return getString(R.string.console_status_offline);
            default:
                return getString(R.string.console_status_online);
        }
    }

    private ConsoleHostPresentation.State consoleHostState(ComputerDetails host) {
        SuspendedSessionStore.Session suspended = SuspendedSessionStore.load(this, host.uuid);
        if (suspended != null && suspended.resumedAt == 0L) {
            if (host.state != ComputerDetails.State.ONLINE) {
                return ConsoleHostPresentation.State.ASLEEP;
            }
            return suspended.sleepObservedAt == 0L
                    ? ConsoleHostPresentation.State.CONNECTING
                    : ConsoleHostPresentation.State.WAKING;
        }
        HostSleepStateStore.State sleep = HostSleepStateStore.load(this, host.uuid);
        if (sleep != null) {
            return host.state == ComputerDetails.State.ONLINE
                    ? ConsoleHostPresentation.State.CONNECTING
                    : ConsoleHostPresentation.State.ASLEEP;
        }
        return hostStateController.state(host);
    }

    private String findAppName(ComputerDetails host, int appId) {
        for (NvApp app : loadApps(host, true)) {
            if (app.getAppId() == appId) return app.getAppName();
        }
        return null;
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

    private void showAddHostPanel() {
        List<View> actions = new ArrayList<>();
        List<ComputerDetails> discovered = new ArrayList<>();
        for (String uuid : new ArrayList<>(newlyDiscoveredHosts)) {
            ComputerDetails host = hosts.get(uuid);
            if (host != null) discovered.add(host);
        }
        discovered.sort(Comparator.comparing(host -> host.name, String.CASE_INSENSITIVE_ORDER));
        if (discovered.isEmpty()) {
            actions.add(label(getString(R.string.console_add_host_none_found)));
        } else {
            for (ComputerDetails host : discovered) {
                TextView result = panelAction(getString(R.string.console_add_discovered_host,
                        host.name, host.state == ComputerDetails.State.ONLINE
                                ? getString(R.string.console_status_online)
                                : getString(R.string.console_status_offline)));
                result.setTag("host.discovered:" + host.uuid);
                result.setEnabled(host.state == ComputerDetails.State.ONLINE);
                result.setAlpha(result.isEnabled() ? 1f : .5f);
                result.setOnClickListener(view -> activateHostFromSelection(host.uuid));
                actions.add(result);
            }
        }
        TextView refresh = panelAction(getString(R.string.console_add_host_refresh_results));
        refresh.setOnClickListener(view -> {
            loadKnownHosts();
            ConsoleUiFeedback.makeText(this, R.string.console_add_host_searching, Toast.LENGTH_SHORT).show();
            mainHandler.postDelayed(this::showAddHostPanel, 900L);
        });
        TextView manual = panelAction(getString(R.string.console_add_host_by_ip));
        manual.setOnClickListener(view -> {
            hideSidePanel();
            addHost();
        });
        actions.add(refresh);
        actions.add(manual);
        showSidePanel(getString(R.string.console_hosts_eyebrow),
                getString(R.string.console_add_host_short),
                getString(R.string.console_add_host_details),
                actions.toArray(new View[0]));
    }

    private void showHostSelectionOptions(ComputerDetails host) {
        boolean online = host.state == ComputerDetails.State.ONLINE;
        boolean paired = host.pairState == PairingManager.PairState.PAIRED;
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        boolean canSleep = online
                && hostGatewayStore.loadClientConnection(host.uuid, address) != null;

        TextView wake = hostSelectionMenuAction(getString(R.string.console_wake_host),
                ConsoleHostPresentation.canWake(host)
                        && !hostStateController.isWaking(host.uuid));
        wake.setOnClickListener(view -> {
            hideSidePanel();
            wakeHost(host);
        });
        TextView unpair = hostSelectionMenuAction(getString(R.string.console_unpair_host),
                online && paired);
        unpair.setTextColor(unpair.isEnabled() ? 0xFFFF9B92 : 0x88FF9B92);
        unpair.setOnClickListener(view -> unpairHost(host));
        TextView test = hostSelectionMenuAction(
                getString(R.string.pcview_menu_test_network), true);
        test.setOnClickListener(view -> {
            hideSidePanel();
            ServerHelper.doNetworkTest(this);
        });
        TextView sleep = hostSelectionMenuAction(getString(R.string.console_sleep_host), canSleep);
        sleep.setOnClickListener(view -> confirmSleepHost(host));
        TextView terminate = hostSelectionMenuAction(
                getString(R.string.overlay_menu_quit_session),
                online && paired && host.runningGameId != 0);
        terminate.setTextColor(terminate.isEnabled() ? 0xFFFF9B92 : 0x88FF9B92);
        terminate.setOnClickListener(view -> confirmTerminateSession(host));
        showSidePanel(getString(R.string.console_host_eyebrow), host.name,
                getString(online ? R.string.console_host_online_details
                        : R.string.console_host_offline_details),
                wake, terminate, unpair, test, sleep);
    }

    private TextView hostSelectionMenuAction(String label, boolean enabled) {
        TextView action = panelAction(label);
        action.setEnabled(enabled);
        action.setAlpha(enabled ? 1f : .42f);
        return action;
    }

    private void refreshDashboard() {
        if (managerBinder == null) {
            ConsoleUiFeedback.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return;
        }
        refreshHostStates();
        refreshSelectedApplications();
        refreshSelectedPlayniteLibrary();
        ConsoleUiFeedback.makeText(this, R.string.console_refresh_started, Toast.LENGTH_SHORT).show();
    }

    private void refreshHostStates() {
        for (ComputerDetails host : hosts.values()) {
            managerBinder.invalidateStateForComputer(host.uuid);
        }
    }

    private void refreshSelectedApplications() {
        if (appListPoller != null) appListPoller.pollNow();
    }

    private void refreshSelectedPlayniteLibrary() {
        ComputerDetails selected = hosts.get(selectedHostUuid);
        if (selected != null) requestPlayniteRefresh(selected, true);
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
                    ConsoleUiFeedback.makeText(this, getString(R.string.console_quick_launch_added,
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
            ConsoleUiFeedback.makeText(this, R.string.console_quick_launch_host_missing, Toast.LENGTH_LONG).show();
            return;
        }
        for (NvApp app : loadApps(host, true)) {
            if (app.getAppId() == item.appId) {
                hideSidePanel();
                launchQuickOrConfirm(host, app, item.key);
                return;
            }
        }
        ConsoleUiFeedback.makeText(this, R.string.console_quick_launch_app_missing, Toast.LENGTH_LONG).show();
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
            quit.setOnClickListener(view -> confirmQuitAction(() -> {
                hideSidePanel();
                ServerHelper.doQuit(this, host, new NvApp("app", item.appId, false),
                        managerBinder, () -> {
                            if (appListPoller != null) appListPoller.pollNow();
                        });
            }));
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
        consoleFeedback.showInput(getString(R.string.quick_launch_rename_title), null,
                item.getDisplayName(), null, InputType.TYPE_CLASS_TEXT, 80,
                getString(android.R.string.cancel), null, null,
                getString(android.R.string.ok), value -> {
                    if (value.isEmpty()) {
                        return getString(R.string.console_input_required);
                    }
                    quickLaunchManager.updateCustomName(item.key, value);
                    consoleFeedback.notify(ConsoleUiFeedback.Kind.SUCCESS,
                            getString(R.string.quick_launch_renamed));
                    showQuickLaunchPanel();
                    return null;
                }, null);
    }

    private void confirmRemoveQuickLaunchItem(QuickLaunchManager.QuickLaunchItem item) {
        TextView cancel = panelAction(getString(R.string.console_cancel));
        cancel.setOnClickListener(view -> handlePanelBack());
        TextView remove = panelAction(getString(R.string.quick_launch_delete));
        remove.setTextColor(0xFFFF9B92);
        remove.setOnClickListener(view -> {
            quickLaunchManager.removeQuickLaunchItem(item.key);
            ConsoleUiFeedback.makeText(this, R.string.quick_launch_removed, Toast.LENGTH_SHORT).show();
            showQuickLaunchPanel();
        });
        showSidePanel(getString(R.string.console_quick_launch_eyebrow),
                getString(R.string.quick_launch_remove_confirm_title),
                item.getDisplayNameLong(), cancel, remove);
    }

    private void launchQuickOrConfirm(ComputerDetails host, NvApp app, String quickKey) {
        if (!ConsoleActionCatalog.isOnline(host)) {
            ConsoleUiFeedback.makeText(this, R.string.error_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ConsoleActionCatalog.isPaired(host)) {
            ConsoleUiFeedback.makeText(this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
            return;
        }
        if (host.runningGameId != 0 && host.runningGameId != app.getAppId()) {
            confirmQuitAction(() -> beginLaunch(host, app, quickKey));
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

    private void toggleHiddenApps() {
        showHiddenApps = !showHiddenApps;
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host != null) renderAppsAsync(host, true);
        ConsoleUiFeedback.makeText(this, showHiddenApps ? R.string.console_hidden_shown
                : R.string.console_hidden_filtered, Toast.LENGTH_SHORT).show();
    }

    private ConsoleAction globalAction(String id, int label, int icon, Runnable handler) {
        return ConsoleAction.enabled(id, getString(label), icon, ConsoleAction.Context.GLOBAL,
                false, handler);
    }

    private void buildQuickActions() {
        quickActions = horizontalRow();
        addQuickAction(globalAction("global.refresh", R.string.console_action_refresh,
                R.drawable.ic_console_refresh, this::refreshDashboard));
        addQuickAction(globalAction("global.quick_launch", R.string.console_action_quick_launch,
                R.drawable.ic_console_play, this::showQuickLaunchPanel));
        addQuickAction(globalAction("global.settings", R.string.console_action_stream_settings,
                R.drawable.ic_console_settings, this::showOptionsPanel));
        discordActionButton = addQuickAction(globalAction("global.discord",
                R.string.console_action_discord, R.drawable.ic_console_discord,
                this::showDiscordPanel));
        addQuickAction(globalAction("global.leave_host", R.string.console_action_leave_host,
                R.drawable.ic_console_leave_host,
                () -> showHostSelection(selectedHostUuid)));
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
        button.setSoundEffectsEnabled(false);
        button.setContentDescription(resolved.label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setTooltipText(resolved.label);
        }
        button.setOnClickListener(view -> resolved.handler.run());
        button.setOnFocusChangeListener((view, focused) -> {
            styleQuickAction(button, focused);
            if (quickActionHint != null) {
                quickActionHint.setText(focused ? resolved.label : "");
                quickActionHint.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
                if (focused) positionQuickActionHint(button);
            }
        });
        styleQuickAction(button, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(target, target);
        params.leftMargin = getResources().getDimensionPixelSize(R.dimen.console_space_xs);
        quickActions.addView(button, params);
        return button;
    }

    private void positionQuickActionHint(View anchor) {
        if (quickActionHint == null || anchor == null || homeLayer == null) return;
        quickActionHint.post(() -> {
            int[] location = new int[2];
            anchor.getLocationInWindow(location);
            int width = quickActionHint.getWidth();
            int minimum = dp(54);
            int maximum = Math.max(minimum,
                    homeLayer.getWidth() - dp(54) - width);
            int left = location[0] + anchor.getWidth() / 2 - width / 2;
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) quickActionHint.getLayoutParams();
            params.leftMargin = Math.max(minimum, Math.min(maximum, left));
            quickActionHint.setLayoutParams(params);
        });
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
            beginLaunch(host, running, null, LaunchTransitionType.GENERIC,
                    "", cachedGameId);
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
                            LaunchTransitionType.GENERIC, "", playniteGameId);
                }
            });
        });
    }

    private String uniquePlayniteGameIdForRunningApp(ComputerDetails host, int appId) {
        if (host == null || !host.uuid.equals(selectedHostUuid)) return "";
        String match = "";
        for (PlayniteDashboardItem item : allPlayniteItems) {
            if (item.sunshineAppId == null || item.sunshineAppId != appId) continue;
            if (!match.isEmpty()) return "";
            match = item.game.playniteGameId;
        }
        return HostGatewayClient.isPlayniteId(match) ? match : "";
    }

    private void pairHost(ComputerDetails host) {
        if (host.state != ComputerDetails.State.ONLINE || host.activeAddress == null) {
            ConsoleUiFeedback.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            ConsoleUiFeedback.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return;
        }
        String address = host.activeAddress.address;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, address);
        if (connection != null) {
            beginAutomaticHostPairing(host, connection, null);
            return;
        }

        consoleFeedback.showInput(getString(R.string.console_pair_host_title),
                getString(R.string.console_pair_host_details, host.name), "",
                getString(R.string.gateway_pair_code_hint), InputType.TYPE_CLASS_NUMBER, 6,
                getString(android.R.string.cancel),
                getString(R.string.console_pair_stream_manually), value -> {
                    pairHostManually(host);
                    return null;
                }, getString(R.string.gateway_pair_confirm), value -> {
                    if (!value.matches("[0-9]{6}")) {
                        return getString(R.string.gateway_pair_code_error);
                    }
                    pairGatewayAndStream(host, value);
                    return null;
                }, null);
    }

    private void pairGatewayAndStream(ComputerDetails host, String gatewayCode) {
        ConsoleUiFeedback.makeText(this, R.string.console_pair_host_gateway, Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                String endpoint = HostGatewayClient.endpointForHost(host.activeAddress.address);
                HostGatewayClient.Pairing pairing = hostGatewayClient.pair(
                        endpoint, gatewayCode, moonWakerClientName());
                hostGatewayStore.save(host.uuid, pairing.connection);
                beginAutomaticHostPairing(host, pairing.connection,
                        pairing.streamPairTicket);
            } catch (IOException | RuntimeException error) {
                showAutomaticPairingFailure(host, error);
            }
        });
    }

    private void beginAutomaticHostPairing(ComputerDetails host,
                                           HostGatewayClient.Connection connection,
                                           String initialTicket) {
        mainHandler.post(() -> ConsoleUiFeedback.makeText(this, R.string.console_pair_host_stream,
                Toast.LENGTH_SHORT).show());
        executor.execute(() -> {
            Future<PairingManager.PairState> pairFuture = null;
            NvHTTP http = null;
            try {
                String ticket = initialTicket == null || initialTicket.isEmpty()
                        ? hostGatewayClient.requestVibepolloPairingTicket(connection)
                        : initialTicket;
                http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(host),
                        host.httpsPort, managerBinder.getUniqueId(), host.serverCert,
                        PlatformBinding.getCryptoProvider(this));
                if (http.getPairState() == PairingManager.PairState.PAIRED) {
                    finishAutomaticHostPairing(host);
                    return;
                }
                String pin = PairingManager.generatePinString();
                PairingManager pairing = http.getPairingManager();
                String serverInfo = http.getServerInfo(true);
                pairFuture = executor.submit(() -> pairing.pair(serverInfo, pin));
                SystemClock.sleep(200L);
                JSONObject pairedClient = hostGatewayClient.pairVibepolloClient(
                        connection, ticket, pin, moonWakerClientName());
                int permissions = pairedClient.optInt("permissions", 0);
                if ((permissions & HostGatewayClient.REQUIRED_GAMEPLAY_PERMISSIONS)
                        != HostGatewayClient.REQUIRED_GAMEPLAY_PERMISSIONS) {
                    throw new IOException("Vibepollo did not grant the required client permissions.");
                }
                PairingManager.PairState state = pairFuture.get(8, TimeUnit.SECONDS);
                if (state != PairingManager.PairState.PAIRED) {
                    throw new IOException(state == PairingManager.PairState.PIN_WRONG
                            ? getString(R.string.pair_incorrect_pin)
                            : getString(R.string.pair_fail));
                }
                ComputerDetails managed = managerBinder.getComputer(host.uuid);
                if (managed != null) managed.serverCert = pairing.getPairedCert();
                managerBinder.invalidateStateForComputer(host.uuid);
                finishAutomaticHostPairing(host);
            } catch (TimeoutException error) {
                if (pairFuture != null) pairFuture.cancel(true);
                cancelPendingPairing(http);
                showAutomaticPairingFailure(host,
                        new IOException(getString(R.string.console_pair_host_timeout)));
            } catch (Exception error) {
                if (pairFuture != null && !pairFuture.isDone()) pairFuture.cancel(true);
                cancelPendingPairing(http);
                showAutomaticPairingFailure(host, error);
            }
        });
    }

    private void cancelPendingPairing(NvHTTP http) {
        if (http == null) return;
        try { http.unpair(); } catch (Exception ignored) { }
    }

    private String moonWakerClientName() {
        String model = Build.MODEL == null ? "Android-TV" : Build.MODEL;
        model = model.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        String uniqueId = managerBinder != null ? managerBinder.getUniqueId() : "client";
        String suffix = uniqueId.length() > 6
                ? uniqueId.substring(uniqueId.length() - 6) : uniqueId;
        String result = "MoonWaker " + (model.isEmpty() ? "Android-TV" : model) + " " + suffix;
        return result.length() > 80 ? result.substring(0, 80) : result;
    }

    private void finishAutomaticHostPairing(ComputerDetails host) {
        mainHandler.post(() -> {
            if (consoleAudioEngine != null) {
                consoleAudioEngine.play(ConsoleAudioSynthesis.Cue.SUCCESS);
            }
            ConsoleUiFeedback.makeText(this, R.string.console_pair_host_success,
                    Toast.LENGTH_SHORT).show();
            if (managerBinder != null) managerBinder.invalidateStateForComputer(host.uuid);
            selectHost(host, true);
        });
    }

    private void showAutomaticPairingFailure(ComputerDetails host, Throwable error) {
        String detail = error == null || error.getMessage() == null
                ? getString(R.string.pair_fail) : error.getMessage();
        mainHandler.post(() -> {
            if (consoleAudioEngine != null) {
                consoleAudioEngine.play(ConsoleAudioSynthesis.Cue.ERROR);
            }
            consoleFeedback.showConfirm(getString(R.string.console_pair_host_failed_title),
                    getString(R.string.console_pair_host_failed_details, detail),
                    getString(android.R.string.cancel),
                    getString(R.string.console_pair_stream_manually), false,
                    () -> pairHostManually(host));
        });
    }

    private void pairHostManually(ComputerDetails host) {
        if (sideDialog != null && sideDialog.isShowing()) hideSidePanel();
        ConsoleUiFeedback.makeText(this, R.string.pairing, Toast.LENGTH_SHORT).show();
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
                    mainHandler.post(() -> consoleFeedback.showMessage(
                            getString(R.string.pair_pairing_title),
                            getString(R.string.pair_pairing_msg) + " " + pin + "\n\n" +
                                    getString(R.string.pair_pairing_help),
                            getString(R.string.console_close), null));
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
            mainHandler.post(() -> consoleFeedback.dismissModal());
            String result = message;
            boolean paired = success;
            mainHandler.post(() -> {
                if (result != null && !result.isEmpty()) {
                    if (consoleAudioEngine != null) {
                        consoleAudioEngine.play(ConsoleAudioSynthesis.Cue.ERROR);
                    }
                    ConsoleUiFeedback.makeText(this, result, Toast.LENGTH_LONG).show();
                }
                if (paired) {
                    if (consoleAudioEngine != null) {
                        consoleAudioEngine.play(ConsoleAudioSynthesis.Cue.SUCCESS);
                    }
                    ConsoleUiFeedback.makeText(this, R.string.console_pair_success, Toast.LENGTH_SHORT).show();
                    managerBinder.invalidateStateForComputer(host.uuid);
                    selectHost(host, true);
                }
            });
        });
    }

    private void unpairHost(ComputerDetails host) {
        if (host.state != ComputerDetails.State.ONLINE || host.activeAddress == null) {
            ConsoleUiFeedback.makeText(this, R.string.error_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            ConsoleUiFeedback.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return;
        }
        hideSidePanel();
        ConsoleUiFeedback.makeText(this, R.string.unpairing, Toast.LENGTH_SHORT).show();
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
            mainHandler.post(() -> ConsoleUiFeedback.makeText(this,
                    result == null || result.isEmpty() ? getString(R.string.unpair_fail) : result,
                    Toast.LENGTH_LONG).show());
        });
    }

    private void confirmSleepHost(ComputerDetails host) {
        String activeAddress = host.activeAddress != null ? host.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, activeAddress);
        if (connection == null) {
            ConsoleUiFeedback.makeText(this, R.string.console_gateway_pair_required,
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
        if (host.runningGameId != 0) {
            String gameId = uniquePlayniteGameIdForRunningApp(host, host.runningGameId);
            if (gameId.isEmpty()) {
                gameId = preferences.getString("selected_playnite." + host.uuid, "");
            }
            String title = findAppName(host, host.runningGameId);
            SuspendedSessionStore.save(this, new SuspendedSessionStore.Session(
                    host.uuid, host.runningGameId, gameId,
                    title == null ? getString(R.string.console_game) : title,
                    "", System.currentTimeMillis()));
        } else {
            HostSleepStateStore.request(this, host.uuid);
        }
        renderHostSelection();
        if (host.uuid.equals(selectedHostUuid)) updateHostSelector();
        ConsoleUiFeedback.makeText(this, getString(R.string.console_sleep_request, host.name),
                Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                hostGatewayClient.sleepHost(connection);
                mainHandler.post(() -> ConsoleUiFeedback.makeText(this,
                        getString(R.string.console_sleep_accepted, host.name),
                        Toast.LENGTH_LONG).show());
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> {
                    if (host.runningGameId != 0) SuspendedSessionStore.clear(this, host.uuid);
                    else HostSleepStateStore.clear(this, host.uuid);
                    renderHostSelection();
                    updateHostSelector();
                    ConsoleUiFeedback.makeText(this,
                            getString(R.string.console_sleep_failed, host.name),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void wakeHost(ComputerDetails host) {
        if (host.state == ComputerDetails.State.ONLINE) {
            ConsoleUiFeedback.makeText(this, R.string.wol_pc_online, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ConsoleHostPresentation.canWake(host)) {
            ConsoleUiFeedback.makeText(this, R.string.wol_no_mac, Toast.LENGTH_LONG).show();
            return;
        }
        if (hostStateController.isWaking(host.uuid)) {
            ConsoleUiFeedback.makeText(this, R.string.console_status_waking, Toast.LENGTH_SHORT).show();
            return;
        }
        HostSleepStateStore.clear(this, host.uuid);
        long wakeToken = hostStateController.beginWaking(host.uuid);
        if (wakeToken < 0L) return;
        renderHostSelection();
        if (host.uuid.equals(selectedHostUuid)) updateHostSelector();
        executor.execute(() -> {
            int message;
            try {
                WakeOnLanSender.sendWolPacket(host);
                message = R.string.wol_waking_msg;
            } catch (IOException error) {
                message = R.string.wol_fail;
            }
            int toastMessage = message;
            mainHandler.post(() -> {
                ConsoleUiFeedback.makeText(this, toastMessage, Toast.LENGTH_LONG).show();
                if (toastMessage == R.string.wol_fail) {
                    finishHostWaking(host.uuid, wakeToken);
                } else {
                    if (managerBinder != null) managerBinder.invalidateStateForComputer(host.uuid);
                    mainHandler.postDelayed(() -> finishHostWaking(host.uuid, wakeToken),
                            HOST_WAKING_TIMEOUT_MS);
                }
            });
        });
    }

    private void resumeSuspendedSession(ComputerDetails host) {
        if (host == null) return;
        SuspendedSessionStore.Session suspended = SuspendedSessionStore.load(this, host.uuid);
        if (suspended == null) {
            requestPlayniteRefresh(host, false);
            return;
        }
        if (host.state != ComputerDetails.State.ONLINE) {
            suspended = SuspendedSessionStore.markSleepObserved(this, suspended);
        }
        suspendedPlayniteGameId = suspended.playniteGameId;
        resumePlayniteGameId = suspended.playniteGameId;
        NvApp target = new NvApp(suspended.title.isEmpty()
                ? getString(R.string.console_game) : suspended.title,
                suspended.sunshineAppId, false);
        beginLaunch(host, target, null, LaunchTransitionType.GENERIC,
                "", suspended.playniteGameId);
    }

    private void confirmTerminateSession(ComputerDetails host) {
        if (host == null || host.state != ComputerDetails.State.ONLINE
                || host.runningGameId == 0 || managerBinder == null) return;
        TextView cancel = panelAction(getString(R.string.console_cancel));
        TextView terminate = panelAction(getString(R.string.overlay_menu_quit_session));
        terminate.setTextColor(0xFFFF8A80);
        cancel.setOnClickListener(view -> handlePanelBack());
        terminate.setOnClickListener(view -> {
            hideSidePanel();
            requestTerminateSession(host);
        });
        showSidePanel(getString(R.string.console_status_active_session),
                getString(R.string.console_terminate_session_title),
                getString(R.string.console_terminate_session_details),
                cancel, terminate);
    }

    private void requestTerminateSession(ComputerDetails host) {
        ConsoleUiFeedback.makeText(this, R.string.console_terminate_session_request,
                Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean stopped = false;
            try {
                NvHTTP connection = new NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(host), host.httpsPort,
                        managerBinder.getUniqueId(), host.serverCert,
                        PlatformBinding.getCryptoProvider(this));
                stopped = connection.quitApp();
            } catch (IOException | XmlPullParserException ignored) { }
            boolean success = stopped;
            mainHandler.post(() -> {
                if (success) {
                    SuspendedSessionStore.markSessionEnded(this, host.uuid);
                    activePlayniteGameIds.remove(host.uuid);
                    activePlayniteGameResolvedAt.remove(host.uuid);
                    activePlayniteGameResolutionInFlight.remove(host.uuid);
                    if (managerBinder != null) {
                        managerBinder.invalidateStateForComputer(host.uuid);
                    }
                }
                ConsoleUiFeedback.makeText(this, getString(success
                                ? R.string.console_terminate_session_success
                                : R.string.console_terminate_session_failed),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void finishHostWaking(String hostUuid, long token) {
        if (!hostStateController.finishWaking(hostUuid, token)) return;
        renderHostSelection();
        if (hostUuid.equals(selectedHostUuid)) updateHostSelector();
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
            ConsoleUiFeedback.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return;
        }
        managerBinder.removeComputer(host);
        assetLoader.deleteAssetsForComputer(host.uuid);
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit().remove(host.uuid).apply();
        new ShortcutHelper(this).disableComputerShortcut(
                host, getString(R.string.scut_deleted_pc));
        hostGatewayStore.remove(host.uuid);
        libraryViewStateStore.clear(host.uuid);
        preferences.edit()
                .remove("selected_playnite." + host.uuid)
                .remove("app_scroll." + host.uuid)
                .remove("playnite_hidden." + host.uuid)
                .apply();
        hosts.remove(host.uuid);
        if (host.uuid.equals(autoLoginHostUuid)) {
            autoLoginHostUuid = "";
            preferences.edit().remove("auto_login_host").apply();
        }

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
        ConsoleUiFeedback.makeText(this, getString(R.string.console_host_removed, host.name),
                Toast.LENGTH_LONG).show();
    }

    private void selectHost(ComputerDetails host, boolean focusApps) {
        boolean enteringHost = hostSelectionVisible;
        boolean changed = !host.uuid.equals(selectedHostUuid);
        if (changed) cancelPlayniteArtworkPrefetch();
        hostSelectionVisible = false;
        if (consoleAudioEngine != null) {
            consoleAudioEngine.setHostSelectionVisible(false);
        }
        if (enteringHost || focusApps) pendingInitialGameFocus = true;
        hostSelectionFocusUuid = host.uuid;
        if (hostSelectionLayer != null) hostSelectionLayer.setVisibility(View.GONE);
        if (homeLayer != null) homeLayer.setVisibility(View.VISIBLE);
        selectedHostUuid = host.uuid;
        if (changed) {
            currentSunshineApps = Collections.emptyList();
            updateLaunchPlayniteButton(host, currentSunshineApps);
        }
        restoreHostLibraryState(host.uuid);
        preferences.edit().putString("selected_host", host.uuid).apply();
        newlyDiscoveredHosts.remove(host.uuid);
        clearArtwork();
        appsLabel.setText(getString(R.string.console_apps_host,
                host.name.toUpperCase(Locale.ROOT)));
        updateHostSelector();
        refreshDiscordIndicator();
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
                ComputerDetails latestHost = currentHost(uuid);
                if (latestHost == null) return;
                currentSunshineApps = Collections.unmodifiableList(new ArrayList<>(apps));
                sunshineAppsGeneration.incrementAndGet();
                updateLaunchPlayniteButton(latestHost, apps);
                boolean playniteAvailable = !currentPlayniteGames.isEmpty() ||
                        hostGatewayStore.loadClientConnection(latestHost.uuid,
                                latestHost.activeAddress != null ? latestHost.activeAddress.address : null) != null;
                if (playniteAvailable) {
                    renderPlayniteLibrary(latestHost, apps);
                } else {
                    renderApps(latestHost, apps);
                }
                if (!playniteAvailable) requestPendingInitialGameFocus(latestHost);
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
        allPlayniteItems = Collections.emptyList();
        unfilteredPlayniteItems = Collections.emptyList();
        playniteLibraryCached = false;
        playniteLibraryCachedAt = 0L;
        playniteLibraryError = null;
        playniteInitialLoadPending = true;
        showCarouselLoadingGhosts();
        updatePlayniteLibraryStatus(host);
        playniteRequest = playniteExecutor.submit(() -> {
            PlayniteLibraryCache.Entry cached = playniteLibraryRepository.cached(host.uuid);
            mainHandler.post(() -> {
                if (token != playniteGeneration.get() ||
                        !host.uuid.equals(selectedHostUuid)) return;
                if (cached != null) {
                    currentPlayniteGames = cached.games;
                    playniteLibraryCached = true;
                    playniteLibraryCachedAt = cached.savedAt;
                    renderPlayniteLibrary(currentHost(host.uuid), currentSunshineApps);
                }
                requestPlayniteRefresh(currentHost(host.uuid), false);
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
            playniteInitialLoadPending = false;
            playniteLibraryRefreshing = false;
            playniteLibraryError = PlayniteLibraryRepository.ErrorKind.AUTHENTICATION;
            updatePlayniteLibraryStatus(host);
            scheduleNextPlayniteRefresh();
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
                            Thread.currentThread().isInterrupted(), manual);
            mainHandler.post(() -> {
                if (token != playniteGeneration.get() || !active ||
                        !host.uuid.equals(selectedHostUuid)) return;
                playniteLibraryRefreshing = false;
                playniteInitialLoadPending = false;
                ComputerDetails latestHost = currentHost(host.uuid);
                if (latestHost == null) return;
                if (result.entry != null) {
                    List<PlayniteLibraryGame> previousGames = currentPlayniteGames;
                    boolean libraryChanged = !result.entry.games.equals(currentPlayniteGames);
                    currentPlayniteGames = result.entry.games;
                    playniteLibraryCachedAt = result.entry.savedAt;
                    playniteLibraryCached = false;
                    playniteLibraryError = null;
                    reconcilePlayniteInstallations(latestHost, previousGames,
                            currentPlayniteGames);
                    if (libraryChanged) renderPlayniteLibrary(latestHost, currentSunshineApps);
                    else updatePlayniteLibraryStatus(latestHost);
                } else {
                    playniteLibraryError = result.error;
                    playniteLibraryCached = !currentPlayniteGames.isEmpty();
                    updatePlayniteLibraryStatus(latestHost);
                }
                mainHandler.removeCallbacks(playniteRefreshCycle);
                scheduleNextPlayniteRefresh();
            });
        });
    }

    private void scheduleNextPlayniteRefresh() {
        mainHandler.removeCallbacks(playniteRefreshCycle);
        mainHandler.postDelayed(playniteRefreshCycle,
                hasActivePlayniteInstallation()
                        ? PLAYNITE_INSTALL_REFRESH_MS : PLAYNITE_REFRESH_MS);
    }

    private boolean hasActivePlayniteInstallation() {
        if (!playniteInstallRequests.isEmpty()) return true;
        for (PlayniteLibraryGame game : currentPlayniteGames) {
            if (game.installing) return true;
        }
        return false;
    }

    private void reconcilePlayniteInstallations(ComputerDetails host,
                                                List<PlayniteLibraryGame> previous,
                                                List<PlayniteLibraryGame> current) {
        if (host == null || playniteInstallRequests.isEmpty()) return;
        Map<String, PlayniteLibraryGame> currentById = new LinkedHashMap<>();
        for (PlayniteLibraryGame game : current) {
            currentById.put(game.playniteGameId, game);
        }
        List<String> completed = new ArrayList<>();
        for (Map.Entry<String, String> request : playniteInstallRequests.entrySet()) {
            String prefix = host.uuid + ":";
            if (!request.getKey().startsWith(prefix)) continue;
            String gameId = request.getKey().substring(prefix.length());
            PlayniteLibraryGame game = currentById.get(gameId);
            if (game == null) continue;
            if (game.installing) {
                playniteInstallObserved.add(request.getKey());
                continue;
            }
            if (game.installed) {
                boolean alreadyShownInStream = preferences.getLong(
                        playniteInstallNotificationKey(host.uuid, gameId), 0L) > 0L;
                if (!alreadyShownInStream) {
                    if (consoleAudioEngine != null) {
                        consoleAudioEngine.play(ConsoleAudioSynthesis.Cue.SUCCESS);
                    }
                    ConsoleUiFeedback.makeText(this, getString(R.string.playnite_install_complete,
                            request.getValue()), Toast.LENGTH_LONG).show();
                }
                preferences.edit().remove(
                        playniteInstallNotificationKey(host.uuid, gameId))
                        .remove(playniteInstallPendingKey(host.uuid, gameId)).apply();
                completedPlayniteInstallAnimations.add(request.getKey());
                completed.add(request.getKey());
            } else if (playniteInstallObserved.contains(request.getKey())) {
                if (consoleAudioEngine != null) {
                    consoleAudioEngine.play(ConsoleAudioSynthesis.Cue.ERROR);
                }
                ConsoleUiFeedback.makeText(this, getString(R.string.playnite_install_cancelled,
                        request.getValue()), Toast.LENGTH_LONG).show();
                completed.add(request.getKey());
            }
        }
        for (String key : completed) {
            playniteInstallRequests.remove(key);
            playniteInstallObserved.remove(key);
        }
    }

    private static String playniteInstallNotificationKey(String hostUuid, String gameId) {
        return "playnite_install_notified." + hostUuid + ":" + gameId;
    }

    private static String playniteInstallPendingKey(String hostUuid, String gameId) {
        return "playnite_install_pending." + hostUuid + ":" + gameId;
    }

    private void cancelPlayniteRequest() {
        if (playniteRequest != null) {
            playniteRequest.cancel(true);
            playniteRequest = null;
        }
    }

    private void updatePlayniteLibraryStatus(ComputerDetails host) {
        if (playniteLibraryStatus == null) return;
        String address = host != null && host.activeAddress != null
                ? host.activeAddress.address : null;
        boolean gatewayConfigured = host != null
                && hostGatewayStore.loadClientConnection(host.uuid, address) != null;
        ConsoleLibraryStatus.State libraryStatus = ConsoleLibraryStatus.resolve(
                playniteLibraryRefreshing, !currentPlayniteGames.isEmpty(),
                playniteLibraryCached, playniteLibraryError, gatewayConfigured);
        int textId;
        switch (libraryStatus) {
            case REFRESHING:
                textId = R.string.playnite_refreshing_library;
                break;
            case GATEWAY_NOT_CONFIGURED:
                textId = R.string.playnite_gateway_not_configured;
                break;
            case AUTHENTICATION_ERROR:
                textId = R.string.playnite_authentication_error;
                break;
            case TIMEOUT:
                textId = R.string.playnite_timeout;
                break;
            case INVALID_RESPONSE:
                textId = R.string.playnite_invalid_response;
                break;
            case SERVER_ERROR:
                textId = R.string.playnite_server_error;
                break;
            case GATEWAY_UNAVAILABLE:
                textId = R.string.playnite_gateway_unavailable;
                break;
            case CACHED:
                textId = R.string.playnite_cached_library;
                break;
            case CURRENT:
            default:
                textId = R.string.playnite_data_current;
                break;
        }
        CharSequence status = textId == R.string.playnite_cached_library
                ? cachedLibraryStatusText() : getText(textId);
        boolean hiddenBackgroundStatus = playniteInitialLoadPending
                || libraryStatus == ConsoleLibraryStatus.State.REFRESHING
                || (CONSOLE_UI_V2 && textId == R.string.playnite_data_current);
        playniteLibraryStatus.setText(hiddenBackgroundStatus ? "" : status);
        playniteLibraryStatus.setVisibility(
                hiddenBackgroundStatus ? View.GONE : View.VISIBLE);
        int color = ConsoleLibraryStatus.isError(libraryStatus)
                ? 0xFFFFB74D : libraryStatus == ConsoleLibraryStatus.State.CACHED
                ? 0xFFB8C9DC : 0xFF8790A8;
        playniteLibraryStatus.setTextColor(color);
        if (expandedCacheStatus != null) {
            boolean showExpandedStatus = !playniteInitialLoadPending
                    && (libraryStatus == ConsoleLibraryStatus.State.CACHED
                    || ConsoleLibraryStatus.isError(libraryStatus));
            expandedCacheStatus.setText(showExpandedStatus ? status : "");
            expandedCacheStatus.setTextColor(color);
            expandedCacheStatus.setVisibility(
                    showExpandedStatus ? View.VISIBLE : View.GONE);
        }
    }

    private CharSequence cachedLibraryStatusText() {
        if (playniteLibraryCachedAt <= 0L) return getText(R.string.playnite_cached_library);
        long now = System.currentTimeMillis();
        long savedAt = Math.min(now, playniteLibraryCachedAt);
        CharSequence age = DateUtils.getRelativeTimeSpanString(savedAt, now,
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
        return getString(R.string.playnite_cached_library_age, age);
    }

    private void togglePlayniteInstalledFilter() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) return;
        PlayniteLibraryFilter current = hostGatewayStore.playniteLibraryFilter(host.uuid);
        hostGatewayStore.setPlayniteLibraryFilter(host.uuid,
                current == PlayniteLibraryFilter.INSTALLED
                        ? PlayniteLibraryFilter.ALL : PlayniteLibraryFilter.INSTALLED);
        renderPlayniteLibrary(host, currentSunshineApps);
        styleInstalledFilter(true);
        TextView target = expandedLibraryMode && expandedFilterButton != null
                ? expandedFilterButton : installedFilterButton;
        if (target != null) target.requestFocus();
    }

    private void styleInstalledFilter(boolean focused) {
        styleFilterButton(installedFilterButton,
                installedFilterButton != null && installedFilterButton.hasFocus()
                        || focused && !expandedLibraryMode);
        styleFilterButton(expandedFilterButton,
                expandedFilterButton != null && expandedFilterButton.hasFocus()
                        || focused && expandedLibraryMode);
        styleSourceFilterButton(expandedSourceFilterButton != null
                && expandedSourceFilterButton.hasFocus());
    }

    private void styleFilterButton(TextView button, boolean focused) {
        if (button == null) return;
        ComputerDetails host = hosts.get(selectedHostUuid);
        PlayniteLibraryFilter filter = host == null ? PlayniteLibraryFilter.ALL
                : hostGatewayStore.playniteLibraryFilter(host.uuid);
        boolean activeFilter = filter != PlayniteLibraryFilter.ALL;
        int label = playniteFilterLabel(filter);
        button.setText(label);
        button.setContentDescription(getString(
                R.string.playnite_filter_description,
                getString(label)));
        int top = focused ? 0xFF24343F : activeFilter ? 0x55314755 : 0x26242B32;
        int bottom = focused ? 0xFF18242C : activeFilter ? 0x55314755 : 0x4813181D;
        GradientDrawable background = gradient(top, bottom, 10);
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFF8DDCFF
                : activeFilter ? 0x8873D7FF : 0x384C5962);
        button.setBackground(background);
    }

    private void styleSourceFilterButton(boolean focused) {
        if (expandedSourceFilterButton == null) return;
        ComputerDetails host = hosts.get(selectedHostUuid);
        Set<String> selected = host == null ? null
                : hostGatewayStore.playniteLibrarySources(host.uuid);
        Map<String, String> available = PlayniteLibrarySources.available(
                currentPlayniteGames,
                getResources().getConfiguration().getLocales().get(0));
        boolean activeFilter = selected != null
                && !selected.equals(available.keySet());
        String label = activeFilter
                ? getString(R.string.playnite_sources_selected,
                selected.size(), available.size())
                : getString(R.string.playnite_sources_all);
        expandedSourceFilterButton.setText(label);
        expandedSourceFilterButton.setContentDescription(getString(
                R.string.playnite_sources_filter_description, label));
        int top = focused ? 0xFF24343F : activeFilter ? 0x55314755 : 0x26242B32;
        int bottom = focused ? 0xFF18242C : activeFilter ? 0x55314755 : 0x4813181D;
        GradientDrawable background = gradient(top, bottom, 10);
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFF8DDCFF
                : activeFilter ? 0x8873D7FF : 0x384C5962);
        expandedSourceFilterButton.setBackground(background);
    }

    private void styleSortButton(boolean focused) {
        if (expandedSortButton == null) return;
        ComputerDetails host = hosts.get(selectedHostUuid);
        PlayniteLibrarySort sort = host == null ? PlayniteLibrarySort.RECENT
                : hostGatewayStore.playniteLibrarySort(host.uuid);
        expandedSortButton.setText(playniteSortLabel(sort));
        styleExpandedHeaderControl(expandedSortButton, focused,
                sort != PlayniteLibrarySort.RECENT);
    }

    private void styleSearchButton(boolean focused) {
        if (expandedSearchButton == null) return;
        expandedSearchButton.setText(expandedSearchQuery.isEmpty()
                ? getString(R.string.playnite_search)
                : getString(R.string.playnite_search_active, expandedSearchQuery));
        styleExpandedHeaderControl(expandedSearchButton, focused,
                !expandedSearchQuery.isEmpty());
    }

    private void styleExpandedHeaderControl(TextView button, boolean focused,
                                            boolean activeControl) {
        int top = focused ? 0xFF24343F : activeControl ? 0x55314755 : 0x26242B32;
        int bottom = focused ? 0xFF18242C : activeControl ? 0x55314755 : 0x4813181D;
        GradientDrawable background = gradient(top, bottom, 10);
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFF8DDCFF
                : activeControl ? 0x8873D7FF : 0x384C5962);
        button.setBackground(background);
    }

    private int playniteSortLabel(PlayniteLibrarySort sort) {
        switch (sort) {
            case NAME: return R.string.playnite_sort_name;
            case LIBRARY: return R.string.playnite_sort_library;
            case GENRE: return R.string.playnite_sort_genre;
            case PLAYTIME: return R.string.playnite_sort_playtime;
            case MOST_LAUNCHED: return R.string.playnite_sort_most_launched;
            case RECENT:
            default: return R.string.playnite_sort_recent;
        }
    }

    private int playniteFilterLabel(PlayniteLibraryFilter filter) {
        switch (filter) {
            case INSTALLED:
                return R.string.playnite_installed;
            case RECENTLY_PLAYED:
                return R.string.playnite_recently_played;
            case UNINSTALLED:
                return R.string.playnite_uninstalled;
            case MOST_LAUNCHED:
                return R.string.playnite_most_launched;
            case NEVER_LAUNCHED:
                return R.string.playnite_never_launched;
            case ALL:
            default:
                return R.string.playnite_all_games;
        }
    }

    private void showPlayniteFilterSelector(TextView anchor) {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null || anchor == null) return;
        if (playniteFilterPopup != null && playniteFilterPopup.isShowing()) {
            playniteFilterPopup.dismiss();
        }

        PlayniteLibraryFilter selected = hostGatewayStore.playniteLibraryFilter(host.uuid);
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(7), dp(7), dp(7), dp(7));
        GradientDrawable panel = gradient(0xFC182129, 0xFC0C1116, 12);
        panel.setStroke(dp(1), 0x886E8291);
        menu.setBackground(panel);

        final TextView[] selectedOption = new TextView[1];
        for (PlayniteLibraryFilter option : PlayniteLibraryFilter.values()) {
            TextView item = text(getString(playniteFilterLabel(option)), 15, Color.WHITE, false);
            item.setId(View.generateViewId());
            item.setFocusable(true);
            item.setClickable(true);
            item.setSingleLine(true);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinHeight(dp(42));
            item.setPadding(dp(14), 0, dp(14), 0);
            item.setCompoundDrawablePadding(dp(8));
            stylePlayniteFilterOption(item, option == selected, false);
            item.setOnFocusChangeListener((view, focused) ->
                    stylePlayniteFilterOption((TextView) view,
                            option == selected, focused));
            item.setOnClickListener(view -> applyPlayniteFilter(host, option, anchor));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
            if (menu.getChildCount() > 0) params.topMargin = dp(3);
            menu.addView(item, params);
            if (option == selected) selectedOption[0] = item;
        }

        trapPopupFocus(menu);
        playniteFilterPopup = new PopupWindow(menu, dp(244),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        playniteFilterPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        playniteFilterPopup.setOutsideTouchable(true);
        playniteFilterPopup.setClippingEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            playniteFilterPopup.setElevation(dp(12));
        }
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int menuHeight = dp(7 + 7 + 6 * 42 + 5 * 3);
        int x = Math.max(dp(12), location[0]);
        int y = expandedLibraryMode ? location[1] + anchor.getHeight() + dp(5)
                : Math.max(dp(12), location[1] - menuHeight - dp(6));
        playniteFilterPopup.showAtLocation(homeLayer, Gravity.NO_GRAVITY, x, y);
        if (selectedOption[0] != null) {
            selectedOption[0].post(selectedOption[0]::requestFocus);
        }
    }

    private void showPlayniteSourceSelector(TextView anchor) {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (!expandedLibraryMode || host == null || anchor == null) return;
        if (playniteFilterPopup != null && playniteFilterPopup.isShowing()) {
            playniteFilterPopup.dismiss();
        }
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        Map<String, String> available = PlayniteLibrarySources.available(
                currentPlayniteGames, locale);
        Set<String> stored = hostGatewayStore.playniteLibrarySources(host.uuid);
        Set<String> selected = new LinkedHashSet<>(stored == null
                ? available.keySet() : stored);
        selected.retainAll(available.keySet());

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(7), dp(7), dp(7), dp(7));
        GradientDrawable panel = gradient(0xFC182129, 0xFC0C1116, 12);
        panel.setStroke(dp(1), 0x886E8291);
        menu.setBackground(panel);

        Map<String, TextView> sourceItems = new LinkedHashMap<>();
        TextView all = sourceFilterOption(getString(R.string.playnite_sources_all));
        menu.addView(all, sourceFilterOptionParams(menu));
        for (Map.Entry<String, String> source : available.entrySet()) {
            TextView item = sourceFilterOption(source.getValue());
            sourceItems.put(source.getKey(), item);
            menu.addView(item, sourceFilterOptionParams(menu));
        }
        Runnable refreshStyles = () -> {
            styleSourceFilterOption(all,
                    selected.size() == available.size(), all.hasFocus());
            for (Map.Entry<String, TextView> source : sourceItems.entrySet()) {
                TextView item = source.getValue();
                styleSourceFilterOption(item, selected.contains(source.getKey()),
                        item.hasFocus());
            }
        };
        all.setOnClickListener(view -> {
            if (selected.size() == available.size()) selected.clear();
            else {
                selected.clear();
                selected.addAll(available.keySet());
            }
            refreshStyles.run();
        });
        all.setOnFocusChangeListener((view, focused) -> refreshStyles.run());
        for (Map.Entry<String, TextView> source : sourceItems.entrySet()) {
            source.getValue().setOnClickListener(view -> {
                if (!selected.add(source.getKey())) selected.remove(source.getKey());
                refreshStyles.run();
            });
            source.getValue().setOnFocusChangeListener((view, focused) ->
                    refreshStyles.run());
        }
        TextView done = sourceFilterOption(getString(R.string.playnite_sources_done));
        done.setOnClickListener(view -> playniteFilterPopup.dismiss());
        done.setOnFocusChangeListener((view, focused) ->
                styleSourceFilterOption(done, false, focused));
        menu.addView(done, sourceFilterOptionParams(menu));
        refreshStyles.run();

        trapPopupFocus(menu);
        playniteFilterPopup = new PopupWindow(menu, dp(270),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        playniteFilterPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        playniteFilterPopup.setOutsideTouchable(true);
        playniteFilterPopup.setClippingEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            playniteFilterPopup.setElevation(dp(12));
        }
        playniteFilterPopup.setOnDismissListener(() -> {
            hostGatewayStore.setPlayniteLibrarySources(host.uuid, selected);
            expandedGridWindowStartRow = 0;
            pendingExpandedFocusIndex = 0;
            renderPlayniteLibrary(host, currentSunshineApps);
            if (expandedSourceFilterButton != null) {
                expandedSourceFilterButton.post(
                        expandedSourceFilterButton::requestFocus);
            }
        });
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int optionCount = available.size() + 2;
        int menuHeight = dp(14 + optionCount * 42
                + Math.max(0, optionCount - 1) * 3);
        int x = Math.max(dp(12), location[0]);
        int y = location[1] + anchor.getHeight() + dp(5);
        playniteFilterPopup.showAtLocation(homeLayer, Gravity.NO_GRAVITY, x, y);
        all.post(all::requestFocus);
    }

    private void showPlayniteSortSelector(TextView anchor) {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (!expandedLibraryMode || host == null || anchor == null) return;
        dismissPlaynitePopup();
        PlayniteLibrarySort selected = hostGatewayStore.playniteLibrarySort(host.uuid);
        LinearLayout menu = popupMenu();
        TextView selectedView = null;
        for (PlayniteLibrarySort option : PlayniteLibrarySort.values()) {
            TextView item = sourceFilterOption(getString(playniteSortLabel(option)));
            stylePlayniteFilterOption(item, option == selected, false);
            item.setOnFocusChangeListener((view, focused) ->
                    stylePlayniteFilterOption((TextView) view,
                            option == selected, focused));
            item.setOnClickListener(view -> {
                hostGatewayStore.setPlayniteLibrarySort(host.uuid, option);
                expandedGridWindowStartRow = 0;
                pendingExpandedFocusIndex = 0;
                dismissPlaynitePopup();
                renderExpandedLibrary(host);
                expandedSortButton.post(expandedSortButton::requestFocus);
            });
            menu.addView(item, sourceFilterOptionParams(menu));
            if (option == selected) selectedView = item;
        }
        showExpandedPopup(menu, anchor, dp(260));
        TextView focus = selectedView != null ? selectedView : (TextView) menu.getChildAt(0);
        focus.post(focus::requestFocus);
    }

    private void showPlayniteSearchDialog() {
        if (!expandedLibraryMode) return;
        consoleFeedback.showInput(getString(R.string.playnite_search_title), null,
                expandedSearchQuery, getString(R.string.playnite_search_hint),
                InputType.TYPE_CLASS_TEXT, 120,
                getString(android.R.string.cancel),
                getString(R.string.playnite_search_clear), value -> {
                    applyExpandedSearch("");
                    return null;
                }, getString(android.R.string.ok), value -> {
                    applyExpandedSearch(value);
                    return null;
                }, () -> {
                    if (expandedSearchButton != null) {
                        expandedSearchButton.post(expandedSearchButton::requestFocus);
                    }
                });
    }

    private void applyExpandedSearch(String query) {
        expandedSearchQuery = query == null ? "" : query.trim();
        if (selectedHostUuid != null) {
            libraryViewStateStore.saveSearch(selectedHostUuid, expandedSearchQuery);
        }
        expandedGridWindowStartRow = 0;
        pendingExpandedFocusIndex = 0;
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host != null) renderExpandedLibrary(host);
        styleSearchButton(true);
    }

    private LinearLayout popupMenu() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(7), dp(7), dp(7), dp(7));
        GradientDrawable panel = gradient(0xFC182129, 0xFC0C1116, 12);
        panel.setStroke(dp(1), 0x886E8291);
        menu.setBackground(panel);
        return menu;
    }

    private void dismissPlaynitePopup() {
        if (playniteFilterPopup != null && playniteFilterPopup.isShowing()) {
            playniteFilterPopup.dismiss();
        }
    }

    private void showExpandedPopup(LinearLayout menu, View anchor, int width) {
        trapPopupFocus(menu);
        playniteFilterPopup = new PopupWindow(menu, width,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        playniteFilterPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        playniteFilterPopup.setOutsideTouchable(true);
        playniteFilterPopup.setClippingEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            playniteFilterPopup.setElevation(dp(12));
        }
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        playniteFilterPopup.showAtLocation(homeLayer, Gravity.NO_GRAVITY,
                Math.max(dp(12), location[0]), location[1] + anchor.getHeight() + dp(5));
    }

    private void trapPopupFocus(LinearLayout menu) {
        List<View> focusable = new ArrayList<>();
        for (int index = 0; index < menu.getChildCount(); index++) {
            View child = menu.getChildAt(index);
            if (child.isFocusable()) focusable.add(child);
        }
        for (int index = 0; index < focusable.size(); index++) {
            View child = focusable.get(index);
            child.setNextFocusLeftId(child.getId());
            child.setNextFocusRightId(child.getId());
            child.setNextFocusUpId(index == 0 ? child.getId()
                    : focusable.get(index - 1).getId());
            child.setNextFocusDownId(index + 1 == focusable.size() ? child.getId()
                    : focusable.get(index + 1).getId());
        }
    }

    private TextView sourceFilterOption(String label) {
        TextView item = text(label, 15, Color.WHITE, false);
        item.setId(View.generateViewId());
        item.setFocusable(true);
        item.setClickable(true);
        item.setSingleLine(true);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setMinHeight(dp(42));
        item.setPadding(dp(14), 0, dp(14), 0);
        item.setCompoundDrawablePadding(dp(8));
        return item;
    }

    private LinearLayout.LayoutParams sourceFilterOptionParams(LinearLayout menu) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        if (menu.getChildCount() > 0) params.topMargin = dp(3);
        return params;
    }

    private void styleSourceFilterOption(TextView item, boolean selected,
                                         boolean focused) {
        GradientDrawable background = gradient(
                focused ? 0xFF334653 : selected ? 0xAA294252 : 0x00182129,
                focused ? 0xFF1E2C35 : selected ? 0xAA1B303D : 0x000C1116, 8);
        background.setStroke(dp(focused ? 2 : 1),
                focused ? 0xFF8DDCFF : selected ? 0x8873D7FF : 0x223F4C54);
        item.setBackground(background);
        item.setTextColor(focused || selected ? Color.WHITE : 0xFFCFD8DE);
        item.setCompoundDrawablesWithIntrinsicBounds(
                selected ? android.R.drawable.checkbox_on_background
                        : android.R.drawable.checkbox_off_background, 0, 0, 0);
    }

    private void stylePlayniteFilterOption(TextView item, boolean selected, boolean focused) {
        GradientDrawable background = gradient(
                focused ? 0xFF334653 : selected ? 0xAA294252 : 0x00182129,
                focused ? 0xFF1E2C35 : selected ? 0xAA1B303D : 0x000C1116, 8);
        background.setStroke(dp(focused ? 2 : 1),
                focused ? 0xFF8DDCFF : selected ? 0x8873D7FF : 0x223F4C54);
        item.setBackground(background);
        item.setTextColor(focused || selected ? Color.WHITE : 0xFFCFD8DE);
        item.setCompoundDrawablesWithIntrinsicBounds(
                selected ? android.R.drawable.checkbox_on_background : 0, 0, 0, 0);
    }

    private void applyPlayniteFilter(ComputerDetails host, PlayniteLibraryFilter filter,
                                     TextView anchor) {
        hostGatewayStore.setPlayniteLibraryFilter(host.uuid, filter);
        expandedGridWindowStartRow = 0;
        pendingExpandedFocusIndex = 0;
        if (playniteFilterPopup != null) playniteFilterPopup.dismiss();
        renderPlayniteLibrary(host, currentSunshineApps);
        TextView target = expandedLibraryMode && expandedFilterButton != null
                ? expandedFilterButton : installedFilterButton;
        if (target == null) target = anchor;
        if (target != null) target.post(target::requestFocus);
    }

    private void renderPlayniteLibrary(ComputerDetails host, List<NvApp> apps) {
        if (host == null || !host.uuid.equals(selectedHostUuid)) return;
        String previousResumeGameId = resumePlayniteGameId;
        String previousSuspendedGameId = suspendedPlayniteGameId;
        String previousSessionSignature = renderedCarouselSessionSignature;
        Set<String> locallyHidden = locallyHiddenPlayniteGames(host.uuid);
        List<PlayniteLibraryGame> visibleLibraryGames = new ArrayList<>();
        for (PlayniteLibraryGame game : currentPlayniteGames) {
            if (showHiddenApps || (!game.hidden
                    && !locallyHidden.contains(game.playniteGameId))) {
                visibleLibraryGames.add(game);
            }
        }
        List<PlayniteLibraryGame> sourceFiltered = PlayniteLibrarySources.filter(
                visibleLibraryGames,
                hostGatewayStore.playniteLibrarySources(host.uuid));
        List<PlayniteLibraryGame> ordered = PlayniteLibraryOrdering.order(
                sourceFiltered, hostGatewayStore.playniteLibraryFilter(host.uuid),
                getResources().getConfiguration().getLocales().get(0));
        ordered = promoteInstallingGames(host, ordered, sourceFiltered);
        boolean appListAuthoritative = ConsoleActionCatalog.isOnline(host);
        Map<String, PlayniteDashboardItem> resolvedById = new LinkedHashMap<>();
        List<PlayniteDashboardItem> unfilteredItems = new ArrayList<>();
        for (PlayniteLibraryGame game : visibleLibraryGames) {
            PlayniteDashboardItem item = PlayniteTargetResolver.resolve(host.uuid, game, apps,
                    playniteLaunchTargetStore, appListAuthoritative);
            unfilteredItems.add(item);
            resolvedById.put(item.stableId(), item);
        }
        List<PlayniteDashboardItem> items = new ArrayList<>();
        for (PlayniteLibraryGame game : ordered) {
            PlayniteDashboardItem item = resolvedById.get(game.playniteGameId);
            if (item != null) items.add(item);
        }
        if (CONSOLE_UI_V2) {
            items = putInstallingItemsFirst(host, items);
            items = putActiveSessionFirst(host, items);
        } else {
            resumePlayniteGameId = "";
        }
        allPlayniteItems = Collections.unmodifiableList(new ArrayList<>(items));
        unfilteredPlayniteItems = Collections.unmodifiableList(
                new ArrayList<>(unfilteredItems));
        List<PlayniteDashboardItem> carouselSource = CONSOLE_UI_V2
                ? putActiveSessionFirst(host, unfilteredItems) : unfilteredItems;
        List<PlayniteDashboardItem> dashboardItems = carouselPlayniteItems(
                host, carouselSource, maxCarouselGameCount());
        String sessionSignature = playniteSessionSignature(host);
        boolean libraryTileFocused = getCurrentFocus() != null
                && "playnite:__library__".equals(getCurrentFocus().getTag());
        installedFilterButton.setVisibility(View.GONE);
        applyPlayniteDiff(host, apps, dashboardItems, previousResumeGameId,
                previousSuspendedGameId,
                !previousSessionSignature.equals(sessionSignature));
        renderedCarouselSessionSignature = sessionSignature;
        if (CONSOLE_UI_V2 && !portraitLayout && !unfilteredItems.isEmpty()) {
            addFullLibraryCard();
            if (libraryTileFocused) {
                View libraryCard = directChildWithTag(appRow, "playnite:__library__");
                if (libraryCard != null) libraryCard.post(libraryCard::requestFocus);
            }
        }
        if (expandedLibraryMode) renderExpandedLibrary(host);
        else {
            List<PlayniteDashboardItem> gridPreview = expandedLibraryItems(host);
            int previewCount = Math.min(gridPreview.size(),
                    expandedGridColumns() * EXPANDED_WINDOW_ROWS);
            List<PlayniteDashboardItem> warmItems = mergeArtworkWarmup(
                    dashboardItems, gridPreview.subList(0, previewCount));
            schedulePlayniteArtworkPrefetch(host, warmItems,
                    stableIds(dashboardItems));
        }
        renderedAppsSignature = null;
        appsLabel.setText(getString(R.string.playnite_library,
                host.name.toUpperCase(Locale.ROOT)));
        updatePlayniteLibraryStatus(host);
        if (pendingExpandedLibraryRestore && CONSOLE_UI_V2
                && !unfilteredItems.isEmpty()) {
            pendingExpandedLibraryRestore = false;
            appRow.post(() -> enterExpandedLibrary(true));
        } else {
            requestPendingInitialGameFocus(host);
        }
    }

    private void requestPendingInitialGameFocus(ComputerDetails host) {
        if (!pendingInitialGameFocus || hostSelectionVisible || host == null || appRow == null
                || !host.uuid.equals(selectedHostUuid)) return;
        View target = null;
        if (!resumePlayniteGameId.isEmpty()) {
            target = directChildWithTag(appRow, "playnite:" + resumePlayniteGameId);
        }
        if (target == null && !lastCarouselGameId.isEmpty()) {
            target = directChildWithTag(appRow, "playnite:" + lastCarouselGameId);
        }
        if (target == null && !renderedPlayniteItems.isEmpty()) {
            PlayniteDashboardItem mostRecent = null;
            long latest = Long.MIN_VALUE;
            for (PlayniteDashboardItem item : renderedPlayniteItems) {
                if (!item.game.installed || isPlayniteInstalling(host.uuid, item)) continue;
                long activity = PlayniteLibraryOrdering.activityEpoch(item.game.lastActivity);
                if (activity > latest) {
                    latest = activity;
                    mostRecent = item;
                }
            }
            if (mostRecent != null && latest != Long.MIN_VALUE) {
                target = directChildWithTag(appRow, "playnite:" + mostRecent.stableId());
            }
        }
        if (target == null && !renderedPlayniteItems.isEmpty()) {
            String selected = preferences.getString("selected_playnite." + host.uuid, "");
            target = directChildWithTag(appRow, "playnite:" + selected);
        }
        if (target == null) {
            long latest = Long.MIN_VALUE;
            for (NvApp app : currentSunshineApps) {
                long played = preferences.getLong(appHistoryKey(host.uuid, app.getAppId()), 0L);
                View candidate = directChildWithTag(appRow,
                        "app:" + host.uuid + ":" + app.getAppId());
                if (candidate != null && played > latest) {
                    latest = played;
                    target = candidate;
                }
            }
        }
        if (target == null) target = firstFocusableChild(appRow);
        if (target == null) return;
        pendingInitialGameFocus = false;
        View resolved = target;
        resolved.post(resolved::requestFocus);
    }

    private int maxCarouselGameCount() {
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        int available = Math.max(1, (int) widthDp - 108);
        int slots = Math.max(2, available / 94);
        return Math.max(1, slots - 1);
    }

    private List<PlayniteDashboardItem> carouselPlayniteItems(ComputerDetails host,
            List<PlayniteDashboardItem> items, int limit) {
        if (host == null || items.isEmpty() || limit <= 0) return Collections.emptyList();
        Comparator<PlayniteDashboardItem> recentFirst = Comparator
                .<PlayniteDashboardItem>comparingLong(item ->
                        carouselActivityEpoch(host.uuid, item)).reversed()
                .thenComparing(item -> item.game.name, String.CASE_INSENSITIVE_ORDER);
        List<PlayniteDashboardItem> installing = new ArrayList<>();
        List<PlayniteDashboardItem> recent = new ArrayList<>();
        for (PlayniteDashboardItem item : items) {
            if (isPlayniteInstalling(host.uuid, item)) {
                String activityKey = playniteCarouselInstallActivityKey(
                        host.uuid, item.stableId());
                if (preferences.getLong(activityKey, 0L) == 0L) {
                    preferences.edit().putLong(
                            activityKey, System.currentTimeMillis()).apply();
                }
                installing.add(item);
            }
            else if (carouselActivityEpoch(host.uuid, item) > 0L) recent.add(item);
        }
        installing.sort(recentFirst);
        recent.sort(recentFirst);
        List<PlayniteDashboardItem> result = new ArrayList<>();
        Set<String> included = new HashSet<>();
        if (!resumePlayniteGameId.isEmpty()) {
            for (PlayniteDashboardItem item : items) {
                if (resumePlayniteGameId.equals(item.stableId()) && result.size() < limit) {
                    result.add(item);
                    included.add(item.stableId());
                    break;
                }
            }
        }
        for (int index = 0; index < Math.min(3, installing.size())
                && result.size() < limit; index++) {
            PlayniteDashboardItem item = installing.get(index);
            if (included.add(item.stableId())) result.add(item);
        }
        for (PlayniteDashboardItem item : recent) {
            if (result.size() >= limit) break;
            if (included.add(item.stableId())) result.add(item);
        }
        return result;
    }

    private long carouselActivityEpoch(String hostUuid, PlayniteDashboardItem item) {
        long playActivity = PlayniteLibraryOrdering.activityEpoch(item.game.lastActivity);
        long installActivity = preferences.getLong(
                playniteCarouselInstallActivityKey(hostUuid, item.stableId()), 0L);
        return Math.max(playActivity, installActivity);
    }

    private static String playniteCarouselInstallActivityKey(String hostUuid, String gameId) {
        return "playnite_carousel_install_activity." + hostUuid + ':' + gameId;
    }

    private void addFullLibraryCard() {
        View existing = directChildWithTag(appRow, "playnite:__library__");
        if (existing != null) appRow.removeView(existing);
        LinearLayout card = cardBase(dp(84), dp(150));
        card.setTag("playnite:__library__");
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(14), dp(18), dp(14), dp(18));
        card.setContentDescription(getString(R.string.playnite_open_full_library));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_console_library_grid);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        card.addView(icon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setOnFocusChangeListener((view, focused) -> {
            styleCard(card, focused);
            if (focused && lastCarouselGameId.isEmpty()) showLibraryMetadataPlaceholder();
        });
        card.setOnClickListener(view -> enterExpandedLibrary());
        appRow.addView(card, playniteCardSpacing());
        wireHomeFocusNavigation();
    }

    private void enterExpandedLibrary() {
        enterExpandedLibrary(false);
    }

    private void enterExpandedLibrary(boolean restoreSavedGridPosition) {
        if (!CONSOLE_UI_V2 || expandedLibrary == null || unfilteredPlayniteItems.isEmpty()
                || libraryTransitionRunning) return;
        expandedLibraryMode = true;
        pendingInitialGameFocus = false;
        ComputerDetails host = hosts.get(selectedHostUuid);
        List<PlayniteDashboardItem> expandedItems = host != null
                ? expandedLibraryItems(host) : Collections.emptyList();
        libraryTransitionGameId = restoreSavedGridPosition && host != null
                ? libraryViewStateStore.load(host.uuid, "").expandedGameId
                : lastCarouselGameId;
        if (libraryTransitionGameId.isEmpty() && host != null) {
            libraryTransitionGameId = preferences.getString(
                    "selected_playnite." + host.uuid, "");
        }
        if (host != null) {
            libraryViewStateStore.saveExpandedMode(host.uuid, true);
        }
        int transitionIndex = PlayniteLibraryQuery.indexOf(
                expandedItems, libraryTransitionGameId);
        pendingExpandedFocusIndex = transitionIndex >= 0 ? transitionIndex : 0;
        int columns = Math.max(1, expandedGridColumns());
        expandedGridWindowStartRow = Math.max(0,
                pendingExpandedFocusIndex / columns - EXPANDED_WINDOW_ROWS / 2);
        if (host != null) renderExpandedLibrary(host);
        if (reducedMotion) {
            setNormalLibraryVisibility(View.GONE);
            expandedLibrary.setAlpha(1f);
            expandedLibrary.setTranslationX(0f);
            expandedLibrary.setVisibility(View.VISIBLE);
            focusExpandedLibrary();
            return;
        }
        libraryTransitionRunning = true;
        int transitionToken = libraryTransitionCoordinator.beginTransition(
                libraryTransitionGameId);
        createCarouselToGridGhosts();
        expandedLibrary.setAlpha(.18f);
        expandedLibrary.setTranslationX(dp(28));
        expandedLibrary.setScaleX(.985f);
        expandedLibrary.setScaleY(.985f);
        expandedLibrary.setVisibility(View.VISIBLE);
        staggerExpandedGridEntrance();
        expandedLibrary.post(() -> animateLibraryTransitionGhosts(
                transitionToken, expandedGrid, true));
        animateNormalLibraryOut();
        expandedLibrary.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                .setDuration(LIBRARY_ENTER_TRANSITION_MS)
                .withEndAction(() -> {
                    setNormalLibraryVisibility(View.GONE);
                    resetNormalLibraryTransforms();
                    libraryTransitionRunning = false;
                    focusExpandedLibrary();
                }).start();
    }

    private void exitExpandedLibrary() {
        if (!expandedLibraryMode || libraryTransitionRunning) return;
        View gridFocus = getCurrentFocus();
        if (gridFocus != null && expandedGrid != null
                && isDescendant(expandedGrid, gridFocus)
                && gridFocus.getTag() instanceof String
                && ((String) gridFocus.getTag()).startsWith("playnite:")) {
            String focusedGameId = ((String) gridFocus.getTag())
                    .substring("playnite:".length());
            if (!focusedGameId.isEmpty()) {
                libraryTransitionGameId = focusedGameId;
                lastCarouselGameId = focusedGameId;
                if (selectedHostUuid != null) {
                    libraryViewStateStore.saveCarouselGame(
                            selectedHostUuid, focusedGameId);
                }
            }
        }
        expandedLibraryMode = false;
        if (selectedHostUuid != null) {
            libraryViewStateStore.saveExpandedMode(selectedHostUuid, false);
        }
        stopExpandedDescriptionAutoScroll();
        if (reducedMotion) {
            expandedLibrary.setVisibility(View.GONE);
            releaseExpandedGrid();
            revealNormalLibraryAfterTransition();
            return;
        }
        libraryTransitionRunning = true;
        setNormalLibraryVisibility(View.VISIBLE);
        prepareCarouselReturnPosition();
        int transitionToken = libraryTransitionCoordinator.beginTransition(
                libraryTransitionGameId);
        createGridToCarouselGhosts();
        root.post(() -> animateLibraryTransitionGhosts(
                transitionToken, appRow, false));
        prepareNormalLibraryForEntrance();
        expandedLibrary.animate().alpha(0f).translationX(dp(28))
                .setDuration(LIBRARY_EXIT_TRANSITION_MS)
                .withEndAction(() -> {
                    expandedLibrary.setVisibility(View.GONE);
                    expandedLibrary.setAlpha(1f);
                    expandedLibrary.setTranslationX(0f);
                    expandedLibrary.setScaleX(1f);
                    expandedLibrary.setScaleY(1f);
                    releaseExpandedGrid();
                }).start();
        animateNormalLibraryIn();
    }

    private void setNormalLibraryVisibility(int visibility) {
        dashboardHeader.setVisibility(visibility);
        appScroll.setVisibility(visibility);
        debugLibraryActions.setVisibility(visibility);
        selectedGameMetadata.setVisibility(visibility);
        debugLibrarySpacer.setVisibility(visibility);
        controllerScroll.setVisibility(visibility);
    }

    private void animateNormalLibraryOut() {
        for (View view : normalLibraryViews()) {
            view.animate().alpha(0f).translationX(-dp(18)).setDuration(150L).start();
        }
    }

    private void prepareNormalLibraryForEntrance() {
        for (View view : normalLibraryViews()) {
            view.setAlpha(0f);
            view.setTranslationX(-dp(22));
        }
    }

    private void animateNormalLibraryIn() {
        final List<View> views = normalLibraryViews();
        for (int index = 0; index < views.size(); index++) {
            View view = views.get(index);
            view.animate().alpha(1f).translationX(0f).setStartDelay(index * 14L)
                    .setDuration(220L).start();
        }
        appScroll.animate().withEndAction(() -> {
            libraryTransitionRunning = false;
            revealNormalLibraryAfterTransition();
        }).start();
    }

    private List<View> normalLibraryViews() {
        List<View> views = new ArrayList<>();
        views.add(dashboardHeader);
        views.add(appScroll);
        views.add(debugLibraryActions);
        views.add(selectedGameMetadata);
        views.add(debugLibrarySpacer);
        views.add(controllerScroll);
        return views;
    }

    private void resetNormalLibraryTransforms() {
        for (View view : normalLibraryViews()) {
            view.animate().cancel();
            view.animate().setStartDelay(0L);
            view.setAlpha(1f);
            view.setTranslationX(0f);
        }
    }

    private void staggerExpandedGridEntrance() {
        if (reducedMotion || expandedGrid == null) return;
        int targetIndex = -1;
        for (int index = 0; index < expandedGrid.getChildCount(); index++) {
            Object tag = expandedGrid.getChildAt(index).getTag();
            if (("playnite:" + libraryTransitionGameId).equals(tag)) {
                targetIndex = index;
                break;
            }
        }
        for (int index = 0; index < expandedGrid.getChildCount(); index++) {
            View card = expandedGrid.getChildAt(index);
            Object tag = card.getTag();
            if (!(tag instanceof String) || !((String) tag).startsWith("playnite:")) continue;
            float targetAlpha = card.getAlpha();
            int distance = targetIndex >= 0 ? Math.abs(index - targetIndex) : index;
            card.animate().cancel();
            card.setAlpha(0f);
            card.setTranslationY(dp(11));
            card.animate().alpha(targetAlpha).translationY(0f)
                    .setStartDelay(Math.min(165L, 70L + distance * 16L))
                    .setDuration(220L).start();
        }
    }

    private void revealNormalLibraryAfterTransition() {
        setNormalLibraryVisibility(View.VISIBLE);
        resetNormalLibraryTransforms();
        View target = libraryTransitionGameId.isEmpty() ? null
                : directChildWithTag(appRow, "playnite:" + libraryTransitionGameId);
        if (target == null) target = directChildWithTag(appRow, "playnite:__library__");
        if (target == null) target = firstFocusableChild(appRow);
        if (target != null) target.post(target::requestFocus);
        wireHomeFocusNavigation();
    }

    private void prepareCarouselReturnPosition() {
        if (appScroll == null || appRow == null || libraryTransitionGameId.isEmpty()) return;
        View target = directChildWithTag(appRow, "playnite:" + libraryTransitionGameId);
        if (target == null) return;
        int viewport = appScroll.getWidth();
        int desired = Math.max(0, target.getLeft() - Math.max(0,
                (viewport - target.getWidth()) / 2));
        appScroll.scrollTo(desired, 0);
    }

    private void createCarouselToGridGhosts() {
        removeLibraryTransitionGhost();
        if (reducedMotion || appRow == null) return;
        for (int index = 0; index < appRow.getChildCount(); index++) {
            View card = appRow.getChildAt(index);
            String gameId = playniteCardGameId(card);
            if (gameId == null) continue;
            addLibraryTransitionGhost(gameId, card, null, null, true);
        }
    }

    private void createGridToCarouselGhosts() {
        removeLibraryTransitionGhost();
        if (reducedMotion || appRow == null || expandedGrid == null) return;
        for (int index = 0; index < appRow.getChildCount(); index++) {
            View destination = appRow.getChildAt(index);
            String gameId = playniteCardGameId(destination);
            if (gameId == null) continue;
            View source = directChildWithTag(expandedGrid, "playnite:" + gameId);
            if (source != null && source.isLaidOut()) {
                addLibraryTransitionGhost(gameId, source, null, null, true);
                continue;
            }
            View poster = findPlaynitePoster(destination);
            if (poster == null || !poster.isLaidOut()) continue;
            int[] rootLocation = new int[2];
            int[] destinationLocation = new int[2];
            root.getLocationInWindow(rootLocation);
            poster.getLocationInWindow(destinationLocation);
            boolean above = expandedGameIndex(gameId) < expandedGameIndex(
                    libraryTransitionGameId);
            float startX = destinationLocation[0] - rootLocation[0];
            float startY = above ? -poster.getHeight() - dp(12)
                    : root.getHeight() + dp(12);
            addLibraryTransitionGhost(gameId, destination, startX, startY, false);
        }
    }

    private void addLibraryTransitionGhost(String gameId, View card,
                                           Float startX, Float startY,
                                           boolean hideSource) {
        if (card == null || !card.isLaidOut()) return;
        View posterView = card instanceof ViewGroup
                ? findTaggedChild((ViewGroup) card, "playnite.poster") : null;
        if (!(posterView instanceof ImageView)) return;
        Drawable drawable = ((ImageView) posterView).getDrawable();
        if (drawable == null) return;
        int[] rootLocation = new int[2];
        int[] cardLocation = new int[2];
        root.getLocationInWindow(rootLocation);
        posterView.getLocationInWindow(cardLocation);
        ImageView ghost = new ImageView(this);
        ghost.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ghost.setImageDrawable(cloneDrawable(drawable));
        ghost.setBackground(gradient(0xFF172128, 0xFF0D151A, 12));
        ghost.setClipToOutline(true);
        ghost.setElevation(dp(20));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                posterView.getWidth(), posterView.getHeight());
        params.leftMargin = Math.round(startX != null
                ? startX : cardLocation[0] - rootLocation[0]);
        params.topMargin = Math.round(startY != null
                ? startY : cardLocation[1] - rootLocation[1]);
        root.addView(ghost, params);
        LibraryTransitionGhost transitionGhost = new LibraryTransitionGhost(
                gameId, ghost, hideSource ? posterView : null);
        libraryTransitionGhosts.add(transitionGhost);
        if (hideSource) posterView.setAlpha(0f);
    }

    private void animateLibraryTransitionGhosts(int token, ViewGroup targetContainer,
                                                boolean enteringGrid) {
        if (!libraryTransitionCoordinator.isCurrent(token, libraryTransitionGameId)) {
            removeLibraryTransitionGhost();
            return;
        }
        List<LibraryTransitionGhost> ghosts = new ArrayList<>(libraryTransitionGhosts);
        for (int index = 0; index < ghosts.size(); index++) {
            LibraryTransitionGhost transitionGhost = ghosts.get(index);
            View target = directChildWithTag(targetContainer,
                    "playnite:" + transitionGhost.gameId);
            View targetPoster = findPlaynitePoster(target);
            if (targetPoster != null && targetPoster.isLaidOut()) {
                animateLibraryTransitionGhostToTarget(
                        transitionGhost, targetPoster, index);
            } else if (enteringGrid) {
                animateLibraryTransitionGhostToEdge(transitionGhost, index);
            } else {
                removeLibraryTransitionGhost(transitionGhost);
            }
        }
    }

    private void animateLibraryTransitionGhostToTarget(
            LibraryTransitionGhost transitionGhost, View targetPoster, int order) {
        ImageView ghost = transitionGhost.view;
        transitionGhost.target = targetPoster;
        targetPoster.setAlpha(0f);
        int[] rootLocation = new int[2];
        int[] targetLocation = new int[2];
        root.getLocationInWindow(rootLocation);
        targetPoster.getLocationInWindow(targetLocation);
        float x = targetLocation[0] - rootLocation[0];
        float y = targetLocation[1] - rootLocation[1];
        float scaleX = targetPoster.getWidth() / (float) Math.max(1, ghost.getWidth());
        float scaleY = targetPoster.getHeight() / (float) Math.max(1, ghost.getHeight());
        ghost.animate().x(x).y(y).scaleX(scaleX).scaleY(scaleY).alpha(.18f)
                .setStartDelay(Math.min(70L, order * 9L))
                .setDuration(LIBRARY_SHARED_ARTWORK_MS)
                .withEndAction(() -> removeLibraryTransitionGhost(transitionGhost)).start();
    }

    private void animateLibraryTransitionGhostToEdge(
            LibraryTransitionGhost transitionGhost, int order) {
        boolean above = expandedGameIndex(transitionGhost.gameId)
                < expandedGameIndex(libraryTransitionGameId);
        float y = above ? -transitionGhost.view.getHeight() - dp(12)
                : root.getHeight() + dp(12);
        transitionGhost.view.animate().y(y).alpha(0f).scaleX(.82f).scaleY(.82f)
                .setStartDelay(Math.min(70L, order * 9L))
                .setDuration(LIBRARY_SHARED_ARTWORK_MS)
                .withEndAction(() -> removeLibraryTransitionGhost(transitionGhost)).start();
    }

    private int expandedGameIndex(String gameId) {
        ComputerDetails host = currentHost(selectedHostUuid);
        if (host == null || gameId == null) return Integer.MAX_VALUE;
        return PlayniteLibraryQuery.indexOf(expandedLibraryItems(host), gameId);
    }

    private String playniteCardGameId(View card) {
        Object tag = card != null ? card.getTag() : null;
        if (!(tag instanceof String) || !((String) tag).startsWith("playnite:")) return null;
        String gameId = ((String) tag).substring("playnite:".length());
        return HostGatewayClient.isPlayniteId(gameId) ? gameId : null;
    }

    private View findPlaynitePoster(View card) {
        if (!(card instanceof ViewGroup)) return null;
        return findTaggedChild((ViewGroup) card, "playnite.poster");
    }

    private void removeLibraryTransitionGhost() {
        for (LibraryTransitionGhost ghost :
                new ArrayList<>(libraryTransitionGhosts)) {
            removeLibraryTransitionGhost(ghost);
        }
    }

    private void removeLibraryTransitionGhost(LibraryTransitionGhost ghost) {
        if (ghost == null || !libraryTransitionGhosts.remove(ghost)) return;
        if (ghost.source != null) ghost.source.setAlpha(1f);
        if (ghost.target != null) ghost.target.setAlpha(1f);
        ghost.view.animate().cancel();
        ViewParent parent = ghost.view.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(ghost.view);
    }

    private static final class LibraryTransitionGhost {
        final String gameId;
        final ImageView view;
        final View source;
        View target;

        LibraryTransitionGhost(String gameId, ImageView view, View source) {
            this.gameId = gameId;
            this.view = view;
            this.source = source;
        }
    }

    private void focusExpandedLibrary() {
        View target = libraryTransitionGameId.isEmpty() ? null
                : directChildWithTag(expandedGrid,
                        "playnite:" + libraryTransitionGameId);
        if (target == null) target = firstFocusableChild(expandedGrid);
        if (target != null) {
            View focusTarget = target;
            focusTarget.post(() -> {
                focusTarget.requestFocus();
                smoothRevealExpandedCard(focusTarget);
            });
        }
    }

    private void renderExpandedLibrary(ComputerDetails host) {
        if (!expandedLibraryMode || expandedGrid == null || host == null) return;
        String sessionSignature = playniteSessionSignature(host);
        boolean sessionPresentationChanged =
                !renderedExpandedSessionSignature.equals(sessionSignature);
        List<PlayniteDashboardItem> expandedItems = expandedLibraryItems(host);
        boolean filterFocused = expandedFilterButton.hasFocus();
        boolean sourceFilterFocused = expandedSourceFilterButton != null
                && expandedSourceFilterButton.hasFocus();
        boolean sortFocused = expandedSortButton != null && expandedSortButton.hasFocus();
        boolean searchFocused = expandedSearchButton != null && expandedSearchButton.hasFocus();
        Object focusedTag = getCurrentFocus() != null ? getCurrentFocus().getTag() : null;
        int columns = expandedGridColumns();
        int totalRows = Math.max(1, (expandedItems.size() + columns - 1) / columns);
        int focusedItem = pendingExpandedFocusIndex;
        if (focusedItem < 0 && focusedTag instanceof String
                && ((String) focusedTag).startsWith("playnite:")
                && isDescendant(expandedGrid, getCurrentFocus())) {
            String stableId = ((String) focusedTag).substring("playnite:".length());
            focusedItem = PlayniteLibraryQuery.indexOf(expandedItems, stableId);
        }
        if (focusedItem >= 0) {
            int focusedRow = focusedItem / columns;
            expandedGridWindowStartRow = Math.max(0, Math.min(
                    focusedRow - EXPANDED_WINDOW_ROWS / 2,
                    Math.max(0, totalRows - EXPANDED_WINDOW_ROWS)));
        } else {
            expandedGridWindowStartRow = Math.max(0, Math.min(
                    expandedGridWindowStartRow,
                    Math.max(0, totalRows - EXPANDED_WINDOW_ROWS)));
        }
        int windowEndRow = Math.min(totalRows,
                expandedGridWindowStartRow + EXPANDED_WINDOW_ROWS);
        int windowStart = Math.min(expandedItems.size(),
                expandedGridWindowStartRow * columns);
        int windowEnd = Math.min(expandedItems.size(), windowEndRow * columns);
        List<PlayniteDashboardItem> windowItems = new ArrayList<>(
                expandedItems.subList(windowStart, windowEnd));
        updateExpandedPositionIndicator(focusedItem, expandedItems.size());
        if (windowItems.equals(renderedExpandedItems)
                && renderedExpandedWindowStartRow == expandedGridWindowStartRow
                && !sessionPresentationChanged) {
            styleFilterButton(expandedFilterButton, filterFocused);
            styleSourceFilterButton(sourceFilterFocused);
            styleSortButton(sortFocused);
            styleSearchButton(searchFocused);
            View cachedTarget = focusedTag != null
                    ? directChildWithTag(expandedGrid, focusedTag) : firstFocusableChild(expandedGrid);
            if (filterFocused) requestExpandedFocusOnce(expandedFilterButton);
            else if (sourceFilterFocused) {
                requestExpandedFocusOnce(expandedSourceFilterButton);
            }
            else if (sortFocused) requestExpandedFocusOnce(expandedSortButton);
            else if (searchFocused) requestExpandedFocusOnce(expandedSearchButton);
            else if (cachedTarget != null) requestExpandedFocusOnce(cachedTarget);
            pendingExpandedFocusIndex = -1;
            return;
        }
        Map<String, View> reusableCards = new LinkedHashMap<>();
        for (int childIndex = 0; childIndex < expandedGrid.getChildCount(); childIndex++) {
            View child = expandedGrid.getChildAt(childIndex);
            Object childTag = child.getTag();
            if (childTag instanceof String
                    && ((String) childTag).startsWith("playnite:")) {
                reusableCards.put(((String) childTag).substring("playnite:".length()), child);
            }
        }
        Map<String, PlayniteDashboardItem> previousWindowItems = new LinkedHashMap<>();
        for (PlayniteDashboardItem previousItem : renderedExpandedItems) {
            previousWindowItems.put(previousItem.stableId(), previousItem);
        }
        boolean preservingGridFocus = focusedTag instanceof String
                && ((String) focusedTag).startsWith("playnite:")
                && getCurrentFocus() != null
                && isDescendant(expandedGrid, getCurrentFocus());
        if (preservingGridFocus) {
            expandedFocusTransitionInProgress = true;
            expandedGrid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        expandedGrid.removeAllViews();
        expandedGrid.setColumnCount(columns);
        boolean hasPreviousRows = expandedGridWindowStartRow > 0;
        boolean hasMoreRows = windowEndRow < totalRows;
        int cardRowOffset = hasPreviousRows ? 2 : 1;
        expandedGrid.setRowCount(Math.max(2,
                windowEndRow - expandedGridWindowStartRow + 5));
        View topSpacer = new View(this);
        GridLayout.LayoutParams topParams = new GridLayout.LayoutParams(
                GridLayout.spec(0), GridLayout.spec(0, columns));
        topParams.width = 1;
        topParams.height = dp(Math.max(0, expandedGridWindowStartRow
                - (hasPreviousRows ? 1 : 0)) * 140);
        expandedGrid.addView(topSpacer, topParams);
        if (hasPreviousRows) addExpandedLoadingGhostRow(1, columns);
        List<View> gridCards = new ArrayList<>();
        for (int index = 0; index < windowItems.size(); index++) {
            PlayniteDashboardItem item = windowItems.get(index);
            View card = reusableCards.remove(item.stableId());
            PlayniteDashboardItem previousItem = previousWindowItems.get(item.stableId());
            boolean needsArtwork = card == null || previousItem == null
                    || (playnitePayload(previousItem, item) & PlayniteLibraryDiff.ARTWORK) != 0;
            if (card == null) {
                card = playniteCard(host, item, currentSunshineApps, true);
            } else {
                int payload = previousItem == null ? PlayniteLibraryDiff.TEXT
                        | PlayniteLibraryDiff.ARTWORK | PlayniteLibraryDiff.LAUNCH
                        : playnitePayload(previousItem, item);
                if (sessionPresentationChanged) {
                    payload |= PlayniteLibraryDiff.TEXT | PlayniteLibraryDiff.LAUNCH;
                }
                bindPlayniteCard(card, host, item, currentSunshineApps,
                        payload);
            }
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(index / columns + cardRowOffset),
                    GridLayout.spec(index % columns));
            params.width = dp(76);
            params.height = dp(132);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            expandedGrid.addView(card, params);
            gridCards.add(card);
            ImageView poster = (ImageView) findTaggedChild((ViewGroup) card, "playnite.poster");
            if (poster != null && needsArtwork) loadPlaynitePoster(host, item, poster, false);
        }
        for (View discarded : reusableCards.values()) {
            releaseExpandedCardArtwork(discarded);
        }
        if (hasMoreRows) {
            addExpandedLoadingGhostRow(cardRowOffset
                    + windowEndRow - expandedGridWindowStartRow, columns);
        }
        View bottomSpacer = new View(this);
        GridLayout.LayoutParams bottomParams = new GridLayout.LayoutParams(
                GridLayout.spec(cardRowOffset + windowEndRow
                        - expandedGridWindowStartRow + (hasMoreRows ? 1 : 0)),
                GridLayout.spec(0, columns));
        bottomParams.width = 1;
        bottomParams.height = dp(Math.max(0, totalRows - windowEndRow
                - (hasMoreRows ? 1 : 0)) * 140);
        expandedGrid.addView(bottomSpacer, bottomParams);
        for (int index = 0; index < gridCards.size(); index++) {
            View card = gridCards.get(index);
            int column = index % columns;
            int row = index / columns;
            int lastRow = (gridCards.size() - 1) / columns;
            card.setNextFocusLeftId(column == 0
                    ? expandedFilterButton.getId() : gridCards.get(index - 1).getId());
            card.setNextFocusRightId(index + 1 < gridCards.size() && column + 1 < columns
                    ? gridCards.get(index + 1).getId() : card.getId());
            if (row == lastRow) {
                card.setNextFocusDownId(card.getId());
            } else {
                int below = Math.min(index + columns, gridCards.size() - 1);
                card.setNextFocusDownId(gridCards.get(below).getId());
            }
        }
        renderedExpandedItems = Collections.unmodifiableList(
                new ArrayList<>(windowItems));
        renderedExpandedWindowStartRow = expandedGridWindowStartRow;
        renderedExpandedSessionSignature = sessionSignature;
        styleFilterButton(expandedFilterButton, false);
        styleSourceFilterButton(false);
        styleSortButton(false);
        styleSearchButton(false);
        int localFocusIndex = pendingExpandedFocusIndex - windowStart;
        View target = localFocusIndex >= 0 && localFocusIndex < gridCards.size()
                ? gridCards.get(localFocusIndex)
                : focusedTag != null ? directChildWithTag(expandedGrid, focusedTag) : null;
        pendingExpandedFocusIndex = -1;
        if (target == null) target = firstFocusableChild(expandedGrid);
        View first = firstFocusableChild(expandedGrid);
        if (first != null) {
            expandedFilterButton.setNextFocusRightId(first.getId());
            expandedFilterButton.setNextFocusUpId(expandedFilterButton.getId());
            expandedFilterButton.setNextFocusDownId(expandedSourceFilterButton.getId());
            expandedSourceFilterButton.setNextFocusUpId(expandedFilterButton.getId());
            expandedSourceFilterButton.setNextFocusDownId(
                    expandedSourceFilterButton.getId());
            expandedSourceFilterButton.setNextFocusRightId(first.getId());
            expandedFilterButton.setNextFocusDownId(first.getId());
            expandedSourceFilterButton.setNextFocusDownId(first.getId());
            expandedSortButton.setNextFocusDownId(first.getId());
            expandedSearchButton.setNextFocusDownId(first.getId());
            expandedFilterButton.setNextFocusLeftId(expandedFilterButton.getId());
            expandedFilterButton.setNextFocusRightId(expandedSourceFilterButton.getId());
            expandedSourceFilterButton.setNextFocusLeftId(expandedFilterButton.getId());
            expandedSourceFilterButton.setNextFocusRightId(expandedSortButton.getId());
            expandedSortButton.setNextFocusLeftId(expandedSourceFilterButton.getId());
            expandedSortButton.setNextFocusRightId(expandedSearchButton.getId());
            expandedSearchButton.setNextFocusLeftId(expandedSortButton.getId());
            expandedSearchButton.setNextFocusRightId(expandedSearchButton.getId());
        }
        if (preservingGridFocus) {
            expandedGrid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        if (filterFocused) {
            requestExpandedFocusOnce(expandedFilterButton);
        } else if (sourceFilterFocused) {
            requestExpandedFocusOnce(expandedSourceFilterButton);
        } else if (sortFocused) {
            requestExpandedFocusOnce(expandedSortButton);
        } else if (searchFocused) {
            requestExpandedFocusOnce(expandedSearchButton);
        } else if (target != null) {
            requestExpandedFocusOnce(target);
        }
        List<PlayniteDashboardItem> carouselItems = new ArrayList<>(renderedPlayniteItems);
        schedulePlayniteArtworkPrefetch(host, mergeArtworkWarmup(
                expandedArtworkWindow(expandedItems, focusedItem, columns), carouselItems),
                stableIds(carouselItems));
    }

    private List<PlayniteDashboardItem> mergeArtworkWarmup(
            List<PlayniteDashboardItem> primary, List<PlayniteDashboardItem> secondary) {
        LinkedHashMap<String, PlayniteDashboardItem> merged = new LinkedHashMap<>();
        for (PlayniteDashboardItem item : primary) merged.put(item.stableId(), item);
        for (PlayniteDashboardItem item : secondary) merged.putIfAbsent(item.stableId(), item);
        return new ArrayList<>(merged.values());
    }

    private Set<String> stableIds(List<PlayniteDashboardItem> items) {
        Set<String> result = new HashSet<>();
        for (PlayniteDashboardItem item : items) result.add(item.stableId());
        return result;
    }

    private List<PlayniteDashboardItem> expandedLibraryItems(ComputerDetails host) {
        return PlayniteLibraryQuery.apply(allPlayniteItems, expandedSearchQuery,
                hostGatewayStore.playniteLibrarySort(host.uuid), Locale.getDefault());
    }

    private List<PlayniteDashboardItem> expandedArtworkWindow(
            List<PlayniteDashboardItem> items, int focusedItem, int columns) {
        if (items.isEmpty()) return Collections.emptyList();
        int totalRows = (items.size() + columns - 1) / columns;
        int centerRow = focusedItem >= 0 ? focusedItem / columns
                : expandedGridWindowStartRow + EXPANDED_WINDOW_ROWS / 2;
        int firstRow = Math.max(0, centerRow - EXPANDED_PREFETCH_ROWS / 2);
        int lastRow = Math.min(totalRows, firstRow + EXPANDED_PREFETCH_ROWS);
        firstRow = Math.max(0, lastRow - EXPANDED_PREFETCH_ROWS);
        return new ArrayList<>(items.subList(firstRow * columns,
                Math.min(items.size(), lastRow * columns)));
    }

    private void updateExpandedPositionIndicator(int focusedItem, int itemCount) {
        if (expandedPageIndicator == null) return;
        if (itemCount <= 0) expandedPageIndicator.setText("");
        else expandedPageIndicator.setText(String.format(Locale.getDefault(), "%d / %d",
                Math.max(1, Math.min(itemCount, focusedItem + 1)), itemCount));
    }

    private boolean handleExpandedGridNavigation(KeyEvent event) {
        if (!expandedLibraryMode || expandedGrid == null || event == null
                || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        int direction = event.getKeyCode();
        if (direction != KeyEvent.KEYCODE_DPAD_DOWN && direction != KeyEvent.KEYCODE_DPAD_UP
                && direction != KeyEvent.KEYCODE_DPAD_LEFT
                && direction != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        // Do not let repeated input outrun the virtual window. The next navigation event is
        // accepted only after the newly materialized card has focus and is visible.
        if (expandedFocusTransitionInProgress) return true;
        View focused = getCurrentFocus();
        if (focused == null || !(focused.getTag() instanceof String)
                || !((String) focused.getTag()).startsWith("playnite:")
                || !isDescendant(expandedGrid, focused)) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastExpandedGridNavigationAt < EXPANDED_NAVIGATION_INTERVAL_MS) return true;
        lastExpandedGridNavigationAt = now;
        int columns = expandedGridColumns();
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) return false;
        List<PlayniteDashboardItem> expandedItems = expandedLibraryItems(host);
        String stableId = ((String) focused.getTag()).substring("playnite:".length());
        int index = PlayniteLibraryQuery.indexOf(expandedItems, stableId);
        if (index < 0) return false;
        int column = index % columns;
        int target = index;
        switch (direction) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (column == 0) return false;
                target = index - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (column + 1 >= columns || index + 1 >= expandedItems.size()) return true;
                target = index + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (index < columns) {
                    pendingExpandedFocusIndex = -1;
                    expandedFilterButton.requestFocus();
                    return true;
                }
                target = index - columns;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (index + columns >= expandedItems.size()) {
                    int lastRowStart = ((expandedItems.size() - 1) / columns) * columns;
                    if (index >= lastRowStart) return true;
                    target = expandedItems.size() - 1;
                } else target = index + columns;
                break;
            default:
                return false;
        }
        int targetRow = target / columns;
        int windowEndRow = expandedGridWindowStartRow + EXPANDED_WINDOW_ROWS;
        boolean shiftWindow = targetRow < expandedGridWindowStartRow;
        shiftWindow |= targetRow >= windowEndRow;
        pendingExpandedFocusIndex = target;
        if (shiftWindow) scheduleExpandedBoundaryLoad(host, target);
        else {
            View targetView = directChildWithTag(expandedGrid,
                    "playnite:" + expandedItems.get(target).stableId());
            pendingExpandedFocusIndex = -1;
            if (targetView != null) targetView.requestFocus();
            updateExpandedPositionIndicator(target, expandedItems.size());
            scheduleExpandedWindowWarmup(host, target, expandedItems.size(), columns);
        }
        return true;
    }

    private void addExpandedLoadingGhostRow(int row, int columns) {
        for (int column = 0; column < columns; column++) {
            FrameLayout ghost = new FrameLayout(this);
            ghost.setFocusable(false);
            ghost.setClickable(false);
            ghost.setAlpha(.30f);
            ghost.setBackground(gradient(0xFF28343D, 0xFF151D23, 10));
            if (column == 0) {
                ProgressBar spinner = new ProgressBar(this);
                ghost.addView(spinner, new FrameLayout.LayoutParams(
                        dp(22), dp(22), Gravity.CENTER));
            }
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(row), GridLayout.spec(column));
            params.width = dp(76);
            params.height = dp(132);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            expandedGrid.addView(ghost, params);
        }
    }

    private void showCarouselLoadingGhosts() {
        if (appRow == null) return;
        appRow.removeAllViews();
        int count = Math.max(4, Math.min(8, maxCarouselGameCount()));
        for (int index = 0; index < count; index++) {
            FrameLayout ghost = new FrameLayout(this);
            ghost.setTag("playnite.loading." + index);
            ghost.setFocusable(false);
            ghost.setClickable(false);
            ghost.setAlpha(index == 0 ? .42f : .28f);
            ghost.setBackground(gradient(0xFF28343D, 0xFF151D23, 12));
            if (index == 0) {
                ProgressBar spinner = new ProgressBar(this);
                ghost.addView(spinner, new FrameLayout.LayoutParams(
                        dp(20), dp(20), Gravity.CENTER));
            }
            appRow.addView(ghost, playniteCardSpacing());
        }
        renderedPlayniteItems = Collections.emptyList();
        wireHomeFocusNavigation();
    }

    private void scheduleExpandedBoundaryLoad(ComputerDetails host, int targetIndex) {
        if (expandedWindowWarmupRunnable != null) {
            mainHandler.removeCallbacks(expandedWindowWarmupRunnable);
        }
        pendingExpandedWindowWarmupIndex = targetIndex;
        expandedFocusTransitionInProgress = true;
        if (expandedGridScroll != null) {
            boolean loadingAbove = targetIndex / Math.max(1, expandedGridColumns())
                    < expandedGridWindowStartRow;
            int revealDistance = dp(loadingAbove ? -54 : 54);
            if (expandedGridScrollAnimator != null) expandedGridScrollAnimator.cancel();
            expandedGridScroll.post(() -> {
                if (expandedLibraryMode) {
                    if (reducedMotion) expandedGridScroll.scrollBy(0, revealDistance);
                    else expandedGridScroll.smoothScrollBy(0, revealDistance);
                }
            });
        }
        expandedWindowWarmupPosted = true;
        expandedWindowWarmupRunnable = () -> {
            expandedWindowWarmupPosted = false;
            expandedWindowWarmupRunnable = null;
            if (!expandedLibraryMode || host == null
                    || !host.uuid.equals(selectedHostUuid)) {
                expandedFocusTransitionInProgress = false;
                return;
            }
            List<PlayniteDashboardItem> latestItems = expandedLibraryItems(host);
            if (latestItems.isEmpty()) {
                expandedFocusTransitionInProgress = false;
                return;
            }
            int safeTarget = Math.max(0, Math.min(
                    latestItems.size() - 1, pendingExpandedWindowWarmupIndex));
            pendingExpandedWindowWarmupIndex = -1;
            pendingExpandedFocusIndex = safeTarget;
            renderExpandedLibrary(host);
        };
        mainHandler.postDelayed(expandedWindowWarmupRunnable, 70L);
    }

    /**
     * Re-centres the small virtual grid window before focus reaches its edge. This spreads
     * card creation across ordinary navigation steps instead of building a whole new row
     * after the user has already arrived at the bottom of the rendered window.
     */
    private void scheduleExpandedWindowWarmup(ComputerDetails host, int focusedItem,
            int itemCount, int columns) {
        if (host == null || itemCount <= 0 || columns <= 0) return;
        int focusedRow = focusedItem / columns;
        int totalRows = (itemCount + columns - 1) / columns;
        int windowEndRow = Math.min(totalRows,
                expandedGridWindowStartRow + EXPANDED_WINDOW_ROWS);
        boolean approachingTop = expandedGridWindowStartRow > 0
                && focusedRow < expandedGridWindowStartRow + EXPANDED_WINDOW_WARMUP_ROWS;
        boolean approachingBottom = windowEndRow < totalRows
                && focusedRow >= windowEndRow - EXPANDED_WINDOW_WARMUP_ROWS;
        if (expandedWindowWarmupRunnable != null) {
            mainHandler.removeCallbacks(expandedWindowWarmupRunnable);
            expandedWindowWarmupRunnable = null;
            expandedWindowWarmupPosted = false;
        }
        if (!approachingTop && !approachingBottom) return;
        pendingExpandedWindowWarmupIndex = focusedItem;
        expandedWindowWarmupPosted = true;
        expandedWindowWarmupRunnable = () -> {
            expandedWindowWarmupPosted = false;
            expandedWindowWarmupRunnable = null;
            if (!expandedLibraryMode || expandedGrid == null) return;
            ComputerDetails currentHost = hosts.get(selectedHostUuid);
            if (currentHost == null || !currentHost.uuid.equals(host.uuid)) return;
            int latestIndex = pendingExpandedWindowWarmupIndex;
            pendingExpandedWindowWarmupIndex = -1;
            List<PlayniteDashboardItem> latestItems = expandedLibraryItems(currentHost);
            if (latestIndex < 0 || latestIndex >= latestItems.size()) return;
            View focused = getCurrentFocus();
            if (focused != null && focused.getTag() instanceof String
                    && ((String) focused.getTag()).startsWith("playnite:")) {
                int actual = PlayniteLibraryQuery.indexOf(latestItems,
                        ((String) focused.getTag()).substring("playnite:".length()));
                if (actual >= 0) latestIndex = actual;
            }
            pendingExpandedFocusIndex = latestIndex;
            expandedFocusTransitionInProgress = true;
            renderExpandedLibrary(currentHost);
        };
        mainHandler.postDelayed(expandedWindowWarmupRunnable,
                EXPANDED_WINDOW_WARMUP_DELAY_MS);
    }

    private void requestExpandedFocusOnce(View target) {
        if (target == null) return;
        if (expandedFocusRestoreRunnable != null) {
            mainHandler.removeCallbacks(expandedFocusRestoreRunnable);
            if (expandedGrid != null) expandedGrid.removeCallbacks(expandedFocusRestoreRunnable);
            expandedFocusRestoreRunnable = null;
        }
        if (!expandedLibraryMode || !target.isAttachedToWindow()
                || !target.isShown() || !target.isFocusable()) return;
        // Keep focus inside the grid while its virtual window is being rebuilt. Android may
        // otherwise move focus to the header when removeAllViews() temporarily detaches the
        // previously focused card.
        if (getCurrentFocus() != target) target.requestFocus();

        // requestFocus() runs before GridLayout has assigned the recycled card its new row.
        // Re-check on the next frame and reveal it only after those coordinates are settled.
        // This keeps focus and the ScrollView viewport together in both scroll directions.
        expandedFocusRestoreRunnable = () -> {
            expandedFocusRestoreRunnable = null;
            if (!expandedLibraryMode || expandedGrid == null
                    || !target.isAttachedToWindow() || !target.isShown()
                    || !target.isFocusable() || target.getParent() != expandedGrid) {
                expandedFocusTransitionInProgress = false;
                return;
            }
            if (getCurrentFocus() != target) target.requestFocus();
            target.postOnAnimation(() -> {
                if (expandedLibraryMode && target.isAttachedToWindow()
                        && target.getParent() == expandedGrid && target.hasFocus()) {
                    Runnable focusTransitionTimeout = () ->
                            expandedFocusTransitionInProgress = false;
                    mainHandler.postDelayed(focusTransitionTimeout, 650L);
                    smoothRevealExpandedCard(target, () -> {
                        mainHandler.removeCallbacks(focusTransitionTimeout);
                        expandedFocusTransitionInProgress = false;
                    });
                } else {
                    expandedFocusTransitionInProgress = false;
                }
            });
        };
        expandedGrid.postOnAnimation(expandedFocusRestoreRunnable);
    }

    private void releaseExpandedGrid() {
        if (expandedGrid == null) return;
        releaseExpandedGridArtwork();
        expandedGrid.removeAllViews();
        if (expandedGridScroll != null) expandedGridScroll.scrollTo(0, 0);
        renderedExpandedItems = Collections.emptyList();
        renderedExpandedWindowStartRow = -1;
        expandedGridWindowStartRow = 0;
        pendingExpandedFocusIndex = -1;
        pendingExpandedWindowWarmupIndex = -1;
        expandedWindowWarmupPosted = false;
        expandedFocusTransitionInProgress = false;
        if (expandedWindowWarmupRunnable != null) {
            mainHandler.removeCallbacks(expandedWindowWarmupRunnable);
            expandedWindowWarmupRunnable = null;
        }
        if (expandedFocusRestoreRunnable != null) {
            mainHandler.removeCallbacks(expandedFocusRestoreRunnable);
            expandedGrid.removeCallbacks(expandedFocusRestoreRunnable);
            expandedFocusRestoreRunnable = null;
        }
        if (expandedGridScrollAnimator != null) expandedGridScrollAnimator.cancel();
    }

    private void releaseExpandedGridArtwork() {
        if (expandedGrid == null) return;
        for (int index = 0; index < expandedGrid.getChildCount(); index++) {
            View child = expandedGrid.getChildAt(index);
            releaseExpandedCardArtwork(child);
        }
    }

    private void releaseExpandedCardArtwork(View child) {
        if (!(child instanceof ViewGroup)) return;
        ImageView poster = (ImageView) findTaggedChild(
                (ViewGroup) child, "playnite.poster");
        ImageView backdrop = (ImageView) findTaggedChild(
                (ViewGroup) child, "playnite.poster.backdrop");
        if (poster != null) {
            poster.setTag(R.id.playnite_artwork_key, "released");
            poster.setImageDrawable(null);
        }
        if (backdrop != null) backdrop.setImageDrawable(null);
    }

    private int expandedGridColumns() {
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        int available = Math.max(252, (int) widthDp - 108 - 221);
        return Math.max(3, available / 84);
    }

    private void smoothRevealExpandedCard(View card) {
        smoothRevealExpandedCard(card, null);
    }

    private void smoothRevealExpandedCard(View card, Runnable completion) {
        if (expandedGridScroll == null || card == null) {
            if (completion != null) completion.run();
            return;
        }
        card.post(() -> {
            if (!card.isAttachedToWindow() || card.getParent() != expandedGrid) {
                if (completion != null) completion.run();
                return;
            }
            int viewport = expandedGridScroll.getHeight();
            int current = expandedGridScroll.getScrollY();
            int topGuard = current + dp(12);
            int bottomGuard = current + viewport - dp(32);
            int target = current;
            if (card.getTop() < topGuard) target = Math.max(0, card.getTop() - dp(12));
            else if (card.getBottom() > bottomGuard) {
                target = Math.max(0, card.getBottom() - viewport + dp(32));
            }
            int maxScroll = Math.max(0, expandedGrid.getHeight() - viewport);
            target = Math.min(target, maxScroll);
            if (target == current) {
                if (completion != null) completion.run();
                return;
            }
            if (expandedGridScrollAnimator != null) expandedGridScrollAnimator.cancel();
            if (reducedMotion) {
                expandedGridScroll.scrollTo(0, target);
                if (completion != null) completion.run();
                return;
            }
            int distance = Math.abs(target - current);
            expandedGridScrollAnimator = ValueAnimator.ofInt(current, target);
            expandedGridScrollAnimator.setDuration(Math.min(220L,
                    135L + distance / Math.max(1, dp(2))));
            expandedGridScrollAnimator.addUpdateListener(animation ->
                    expandedGridScroll.scrollTo(0, (Integer) animation.getAnimatedValue()));
            if (completion != null) {
                expandedGridScrollAnimator.addListener(new AnimatorListenerAdapter() {
                    private boolean completed;

                    private void completeOnce() {
                        if (completed) return;
                        completed = true;
                        completion.run();
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        completeOnce();
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        completeOnce();
                    }
                });
            }
            expandedGridScrollAnimator.start();
        });
    }

    private List<PlayniteDashboardItem> putActiveSessionFirst(
            ComputerDetails host, List<PlayniteDashboardItem> items) {
        resumePlayniteGameId = "";
        suspendedPlayniteGameId = "";
        if (host == null || items.isEmpty()) return items;
        ActiveSessionSnapshot activeSession = activeSessionSnapshot(host);
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        boolean retainedForHost = retained.state != RetainedStreamSessionCoordinator.State.NONE
                && retained.state != RetainedStreamSessionCoordinator.State.TERMINATING
                && host.uuid.equalsIgnoreCase(retained.hostId);
        if (SuspendedSessionStore.recentlyEnded(this, host.uuid)) {
            if (host.runningGameId == 0) SuspendedSessionStore.clearEnded(this, host.uuid);
            else if (retainedForHost || activePlayniteGameIds.containsKey(host.uuid)) {
                SuspendedSessionStore.clearEnded(this, host.uuid);
            } else {
                return items;
            }
        }
        if (activeSession.isActive()) {
            for (PlayniteDashboardItem item : items) {
                if (activeSession.matches(item.stableId(), item.sunshineAppId)) {
                    resumePlayniteGameId = item.stableId();
                    if (items.get(0) == item) return items;
                    List<PlayniteDashboardItem> reordered = new ArrayList<>(items);
                    reordered.remove(item);
                    reordered.add(0, item);
                    return reordered;
                }
            }
        }
        SuspendedSessionStore.Session suspended = SuspendedSessionStore.load(this, host.uuid);
        if (suspended != null && suspended.resumedAt == 0L
                && !suspended.playniteGameId.isEmpty()) {
            for (PlayniteDashboardItem item : items) {
                if (suspended.playniteGameId.equalsIgnoreCase(item.stableId())) {
                    suspendedPlayniteGameId = item.stableId();
                    if (items.get(0) == item) return items;
                    List<PlayniteDashboardItem> reordered = new ArrayList<>(items);
                    reordered.remove(item);
                    reordered.add(0, item);
                    return reordered;
                }
            }
        }
        if (host.runningGameId == 0) return items;
        if (suspended != null && suspended.resumedAt > 0L
                && suspended.sunshineAppId == host.runningGameId
                && !suspended.playniteGameId.isEmpty()) {
            for (PlayniteDashboardItem item : items) {
                if (suspended.playniteGameId.equalsIgnoreCase(item.stableId())) {
                    resumePlayniteGameId = item.stableId();
                    if (items.get(0) == item) return items;
                    List<PlayniteDashboardItem> reordered = new ArrayList<>(items);
                    reordered.remove(item);
                    reordered.add(0, item);
                    return reordered;
                }
            }
        }
        String bridgeGameId = activePlayniteGameIds.get(host.uuid);
        if (HostGatewayClient.isPlayniteId(bridgeGameId)) {
            for (PlayniteDashboardItem item : items) {
                if (bridgeGameId.equalsIgnoreCase(item.stableId())) {
                    resumePlayniteGameId = item.stableId();
                    if (items.get(0) == item) return items;
                    List<PlayniteDashboardItem> reordered = new ArrayList<>(items);
                    reordered.remove(item);
                    reordered.add(0, item);
                    return reordered;
                }
            }
        }
        List<PlayniteDashboardItem> matches = new ArrayList<>();
        for (PlayniteDashboardItem item : items) {
            if (item.sunshineAppId != null && item.sunshineAppId == host.runningGameId) {
                matches.add(item);
            }
        }
        PlayniteDashboardItem active = matches.size() == 1 ? matches.get(0) : null;
        if (active == null && !matches.isEmpty()) {
            String selectedId = preferences.getString(
                    "selected_playnite." + host.uuid, "");
            for (PlayniteDashboardItem item : matches) {
                if (item.stableId().equals(selectedId)) {
                    active = item;
                    break;
                }
            }
        }
        if (active == null) return items;
        resumePlayniteGameId = active.stableId();
        if (items.get(0) == active) return items;
        List<PlayniteDashboardItem> reordered = new ArrayList<>(items);
        reordered.remove(active);
        reordered.add(0, active);
        return reordered;
    }

    private List<PlayniteLibraryGame> promoteInstallingGames(
            ComputerDetails host, List<PlayniteLibraryGame> ordered,
            List<PlayniteLibraryGame> sourceFiltered) {
        if (host == null || sourceFiltered.isEmpty()) return ordered;
        List<PlayniteLibraryGame> result = new ArrayList<>();
        Set<String> included = new HashSet<>();
        for (PlayniteLibraryGame game : sourceFiltered) {
            if (isPlayniteInstalling(host.uuid, game)) {
                result.add(game);
                included.add(game.playniteGameId);
            }
        }
        for (PlayniteLibraryGame game : ordered) {
            if (included.add(game.playniteGameId)) result.add(game);
        }
        return result;
    }

    private List<PlayniteDashboardItem> putInstallingItemsFirst(
            ComputerDetails host, List<PlayniteDashboardItem> items) {
        if (host == null || items.isEmpty()) return items;
        List<PlayniteDashboardItem> installing = new ArrayList<>();
        List<PlayniteDashboardItem> remaining = new ArrayList<>();
        for (PlayniteDashboardItem item : items) {
            if (isPlayniteInstalling(host.uuid, item)) installing.add(item);
            else remaining.add(item);
        }
        if (installing.isEmpty()) return items;
        installing.addAll(remaining);
        return installing;
    }

    private void applyPlayniteDiff(ComputerDetails host, List<NvApp> apps,
                                   List<PlayniteDashboardItem> requestedItems,
                                   String previousResumeGameId,
                                   String previousSuspendedGameId,
                                   boolean sessionPresentationChanged) {
        Object focusTag = getCurrentFocus() != null ? getCurrentFocus().getTag() : null;
        String focusedId = focusTag instanceof String &&
                ((String) focusTag).startsWith("playnite:")
                ? ((String) focusTag).substring("playnite:".length()) : null;
        int previousIndex = getCurrentFocus() != null
                ? Math.max(0, appRow.indexOfChild(getCurrentFocus())) : 0;

        List<PlayniteDashboardItem> items = requestedItems;
        if (focusedId != null && resumePlayniteGameId.isEmpty()
                && containsPlayniteId(requestedItems, focusedId)) {
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
            if (playniteInitialLoadPending || playniteLibraryRefreshing) {
                showCarouselLoadingGhosts();
                return;
            }
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
            boolean resumeStateChanged = item.stableId().equals(previousResumeGameId)
                    != item.stableId().equals(resumePlayniteGameId);
            boolean suspendedStateChanged = item.stableId().equals(previousSuspendedGameId)
                    != item.stableId().equals(suspendedPlayniteGameId);
            if (card == null || "playnite.empty".equals(card.getTag())) {
                card = playniteCard(host, item, apps, false);
            } else if (!item.equals(old)
                    || resumeStateChanged
                    || suspendedStateChanged
                    || sessionPresentationChanged
                    || isVibepolloEnsureInFlight(host.uuid, item)
                    || isPlayniteInstalling(host.uuid, item)) {
                int payload = !item.equals(old) ? playnitePayload(old, item)
                        : PlayniteLibraryDiff.TEXT;
                if (resumeStateChanged || suspendedStateChanged
                        || sessionPresentationChanged) {
                    payload |= PlayniteLibraryDiff.TEXT | PlayniteLibraryDiff.LAUNCH;
                }
                bindPlayniteCard(card, host, item, apps,
                        payload);
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
                old.game.installing != item.game.installing ||
                old.game.installRequiresAttention != item.game.installRequiresAttention ||
                !old.game.installAttentionReason.equals(item.game.installAttentionReason) ||
                !old.game.installWindowTitle.equals(item.game.installWindowTitle) ||
                !old.game.installLauncher.equals(item.game.installLauncher) ||
                old.game.playtimeSeconds != item.game.playtimeSeconds ||
                old.game.playCount != item.game.playCount ||
                !old.game.description.equals(item.game.description) ||
                !old.game.source.equals(item.game.source)) payload |= PlayniteLibraryDiff.TEXT;
        if (!old.game.coverKey.equals(item.game.coverKey) ||
                !old.game.backgroundKey.equals(item.game.backgroundKey)) {
            payload |= PlayniteLibraryDiff.ARTWORK;
        }
        if (!Objects.equals(old.sunshineAppId, item.sunshineAppId) ||
                old.mappingState != item.mappingState) payload |= PlayniteLibraryDiff.LAUNCH;
        return payload;
    }

    private View playniteCard(ComputerDetails host, PlayniteDashboardItem item,
                              List<NvApp> apps, boolean expandedCard) {
        boolean debugCarousel = CONSOLE_UI_V2 && !portraitLayout;
        expandedCard = debugCarousel && expandedCard;
        LinearLayout card = cardBase(
                dp(portraitLayout ? 220 : expandedCard ? 76 : debugCarousel ? 84 : 205),
                dp(portraitLayout ? 225 : expandedCard ? 132 : debugCarousel ? 150 : 190));
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
        poster.setScaleType(debugCarousel
                ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        poster.setImageResource(R.drawable.ic_computer);
        poster.setPadding(dp(expandedCard ? 20 : debugCarousel ? 24 : 72),
                dp(expandedCard ? 29 : debugCarousel ? 36 : 54),
                dp(expandedCard ? 20 : debugCarousel ? 24 : 72),
                dp(expandedCard ? 29 : debugCarousel ? 36 : 54));
        artworkFrame.addView(poster, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(artworkFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(portraitLayout ? 140 : expandedCard ? 94 : debugCarousel ? 110 : 115)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(debugCarousel ? 7 : 14), dp(debugCarousel ? 4 : 10),
                dp(debugCarousel ? 7 : 14), dp(debugCarousel ? 3 : 8));
        TextView name = text("", debugCarousel ? 9 : 15, Color.WHITE, true);
        name.setTag("playnite.name");
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(name, matchLinearWidth());
        TextView playtime = text("", debugCarousel ? 8 : 10, 0xFFB8C9DC, false);
        playtime.setTag("playnite.playtime");
        if (debugCarousel) playtime.setVisibility(View.GONE);
        copy.addView(playtime, matchLinearWidth());
        TextView state = text("", debugCarousel ? 7 : 10, 0xFF929BAD, false);
        state.setTag("playnite.state");
        state.setSingleLine(true);
        state.setMaxWidth(dp(debugCarousel ? 70 : expandedCard ? 118 : 170));
        state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams stateParams = wrapLinear();
        stateParams.topMargin = dp(2);
        copy.addView(state, stateParams);
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
                : new LinearLayout.LayoutParams(
                        dp(CONSOLE_UI_V2 ? 84 : 205),
                        dp(CONSOLE_UI_V2 ? 150 : 190));
        if (portraitLayout) params.bottomMargin = dp(14);
        else params.rightMargin = dp(CONSOLE_UI_V2 ? 10 : 14);
        return params;
    }

    private String playniteSessionSignature(ComputerDetails host) {
        if (host == null) return "";
        SuspendedSessionStore.Session stored = SuspendedSessionStore.load(this, host.uuid);
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        return host.runningGameId + "|"
                + activePlayniteGameIds.getOrDefault(host.uuid, "") + "|"
                + retained.state + "|" + retained.hostId + "|" + retained.appId + "|"
                + retained.playniteGameId + "|"
                + SuspendedSessionStore.recentlyEnded(this, host.uuid) + "|"
                + (stored == null ? "" : stored.playniteGameId + "|"
                + stored.sunshineAppId + "|" + stored.resumedAt + "|"
                + stored.suspendedAt);
    }

    private PlayniteSessionPresentation.State playniteSessionState(
            ComputerDetails host, PlayniteDashboardItem item) {
        ActiveSessionSnapshot active = activeSessionSnapshot(host);
        if (active.matches(item.stableId(), item.sunshineAppId)) {
            return PlayniteSessionPresentation.State.RESUME_ACTIVE;
        }
        SuspendedSessionStore.Session stored = host == null ? null
                : SuspendedSessionStore.load(this, host.uuid);
        return PlayniteSessionPresentation.resolve(CONSOLE_UI_V2,
                host != null && SuspendedSessionStore.recentlyEnded(this, host.uuid),
                item.stableId(), item.sunshineAppId,
                stored == null ? "" : stored.playniteGameId,
                stored == null ? 0 : stored.sunshineAppId,
                stored == null ? -1L : stored.resumedAt,
                host == null ? 0 : host.runningGameId,
                resumePlayniteGameId);
    }

    private ActiveSessionSnapshot activeSessionSnapshot(ComputerDetails host) {
        if (host == null) return new ActiveSessionSnapshot("", 0, "", false);
        String gameId = activePlayniteGameIds.getOrDefault(host.uuid, "");
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        boolean retainedForHost = retained.state != RetainedStreamSessionCoordinator.State.NONE
                && retained.state != RetainedStreamSessionCoordinator.State.TERMINATING
                && host.uuid.equalsIgnoreCase(retained.hostId);
        if (gameId.isEmpty() && retainedForHost) {
            gameId = retained.playniteGameId;
        }
        int runningAppId = retainedForHost && retained.appId != 0
                ? retained.appId : host.runningGameId;
        return new ActiveSessionSnapshot(host.uuid, runningAppId, gameId,
                retainedForHost);
    }

    private void bindPlayniteCard(View card, ComputerDetails host,
                                  PlayniteDashboardItem item, List<NvApp> apps, int payload) {
        card.setTag("playnite:" + item.stableId());
        styleCard(card, card.hasFocus());
        TextView name = (TextView) findTaggedChild((ViewGroup) card, "playnite.name");
        TextView playtime = (TextView) findTaggedChild((ViewGroup) card, "playnite.playtime");
        TextView state = (TextView) findTaggedChild((ViewGroup) card, "playnite.state");
        ImageView poster = (ImageView) findTaggedChild((ViewGroup) card, "playnite.poster");
        String playtimeText = formatPlayniteTime(item.game.playtimeSeconds);
        PlayniteSessionPresentation.State sessionState = playniteSessionState(host, item);
        boolean resumeSession = sessionState
                == PlayniteSessionPresentation.State.RESUME_ACTIVE;
        boolean suspendedSession = sessionState
                == PlayniteSessionPresentation.State.RESUME_SUSPENDED;
        stylePlayniteSessionCard(card, card.hasFocus(), resumeSession || suspendedSession);
        boolean installing = isPlayniteInstalling(host.uuid, item);
        float cardAlpha = item.game.installRequiresAttention ? .94f
                : installing ? .68f : item.game.installed ? 1f : .80f;
        card.setAlpha(cardAlpha);
        String stateText = suspendedSession ? getString(R.string.console_resume_suspended)
                : resumeSession ? getString(R.string.console_resume_session)
                : playniteState(host.uuid, item);
        if ((payload & PlayniteLibraryDiff.TEXT) != 0) {
            name.setText(item.game.name);
            playtime.setText(playtimeText);
        }
        if ((payload & (PlayniteLibraryDiff.TEXT | PlayniteLibraryDiff.LAUNCH)) != 0) {
            state.setText(suspendedSession ? "◷  " + getString(R.string.console_resume_suspended)
                    : playniteStateGlyph(host.uuid, item, resumeSession)
                    + playniteStateChipLabel(host.uuid, item, resumeSession));
            stylePlayniteStateChip(state, host.uuid, item, resumeSession || suspendedSession);
        }
        if ((payload & PlayniteLibraryDiff.ARTWORK) != 0) resetPlaynitePoster(poster, item);
        String installKey = playniteInstallKey(host.uuid, item);
        if (completedPlayniteInstallAnimations.remove(installKey)) {
            card.animate().cancel();
            if (reducedMotion) {
                card.setAlpha(1f);
            } else {
                card.setAlpha(.55f);
                card.setTranslationY(dp(5));
                card.animate().alpha(1f).translationY(0f).setDuration(420L).start();
            }
        }
        card.setOnClickListener(view -> {
            if (suspendedSession) resumeSuspendedSession(currentHost(host.uuid));
            else if (resumeSession) resumeSession(currentHost(host.uuid));
            else activatePlayniteItem(host.uuid, item);
        });
        card.setOnLongClickListener(view -> {
            showPlayniteGameActions(currentHost(host.uuid), item);
            return true;
        });
        card.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_MENU
                    || keyCode == KeyEvent.KEYCODE_BUTTON_START)) {
                showPlayniteGameActions(currentHost(host.uuid), item);
                return true;
            }
            return false;
        });
        card.setContentDescription(getString(R.string.playnite_card_description,
                item.game.name, playtimeText, stateText));
        card.setOnFocusChangeListener((view, focused) -> {
            stylePlayniteSessionCard(card, focused, resumeSession || suspendedSession);
            card.setAlpha(item.game.installRequiresAttention ? .94f
                    : isPlayniteInstalling(host.uuid, item)
                    ? .68f : item.game.installed ? 1f : .80f);
            updateCarouselMarquee(name, focused);
            if (focused) {
                libraryTransitionCoordinator.beginSelection(item.stableId(),
                        () -> {
                            prepareGameMetadataTransition();
                            loadPlaynitePoster(host, item, poster, true);
                        },
                        () -> showGameMetadataHeader(item),
                        () -> showGameMetadataDescription(item));
                if (!expandedLibraryMode && card.getParent() == appRow) {
                    lastCarouselGameId = item.stableId();
                    libraryViewStateStore.saveCarouselGame(host.uuid, lastCarouselGameId);
                } else if (expandedLibraryMode && card.getParent() == expandedGrid) {
                    libraryViewStateStore.saveExpandedGame(host.uuid, item.stableId());
                }
                preferences.edit().putString("selected_playnite." + host.uuid,
                        item.stableId()).apply();
                if (expandedLibraryMode && card.getParent() == expandedGrid) {
                    if (!expandedFocusTransitionInProgress) {
                        smoothRevealExpandedCard(card);
                    }
                } else if (portraitLayout) {
                    smoothCenterOn(appVerticalScroll, card);
                } else {
                    smoothCenterOn(appScroll, card);
                }
            }
        });
    }

    private void stylePlayniteSessionCard(View card, boolean focused, boolean resumeSession) {
        styleCard(card, focused);
        if (!resumeSession) return;
        GradientDrawable background = gradient(
                focused ? 0xF0203C43 : 0xE0173037,
                focused ? 0xF011252B : 0xE00D2026, 12);
        background.setStroke(dp(2), focused ? 0xFFEAFBFF : 0xFF54D4DE);
        card.setBackground(background);
        card.setElevation(dp(focused ? 14 : 8));
        float scale = focused ? 1.055f : 1.025f;
        if (reducedMotion || !card.isLaidOut()) {
            card.setScaleX(scale);
            card.setScaleY(scale);
        } else {
            card.animate().cancel();
            card.animate().scaleX(scale).scaleY(scale).translationY(0f)
                    .setDuration(getResources().getInteger(
                            R.integer.console_motion_focus_ms)).start();
        }
    }

    private void showGameMetadataHeader(PlayniteDashboardItem item) {
        if (item == null) return;
        String played = formatLastActivity(item.game.lastActivity);
        String playtime = formatPlayniteTime(item.game.playtimeSeconds);
        String facts = getString(R.string.playnite_selected_game_facts, played, playtime);
        bindGameMetadataHeader(selectedGameMetadata, selectedGameTitle,
                selectedGameFacts, item.game.name, facts);
        bindGameMetadataHeader(expandedLibrary, expandedGameTitle,
                expandedGameFacts, item.game.name, facts);
    }

    private void prepareGameMetadataTransition() {
        if (reducedMotion) return;
        prepareMetadataViewsForTransition(selectedGameTitle, selectedGameFacts,
                selectedGameDescription);
        prepareMetadataViewsForTransition(expandedGameTitle, expandedGameFacts,
                expandedGameDescription);
    }

    private void prepareMetadataViewsForTransition(View... views) {
        for (View view : views) {
            if (view == null) continue;
            view.animate().cancel();
            view.animate().alpha(0f).translationY(dp(3))
                    .setStartDelay(0L).setDuration(80L).start();
        }
    }

    private void showGameMetadataDescription(PlayniteDashboardItem item) {
        if (item == null) return;
        String description = item.game.description.isEmpty()
                ? getString(R.string.playnite_description_unavailable)
                : item.game.description;
        bindGameMetadataDescription(selectedGameDescription, description);
        bindGameMetadataDescription(expandedGameDescription, description);
    }

    private void bindGameMetadataHeader(View panel, TextView title, TextView facts,
                                        String name, String factsText) {
        if (panel == null || title == null || facts == null) return;
        title.setText(name);
        facts.setText(factsText);
        if (panel == selectedGameMetadata && !expandedLibraryMode) {
            panel.setVisibility(View.VISIBLE);
        }
        title.animate().cancel();
        facts.animate().cancel();
        if (!reducedMotion) {
            title.setAlpha(0f);
            title.setTranslationY(dp(4));
            facts.setAlpha(0f);
            facts.setTranslationY(dp(3));
        }
        title.animate().alpha(1f).translationY(0f)
                .setDuration(reducedMotion ? 0L : 150L).start();
        facts.animate().alpha(1f).translationY(0f)
                .setDuration(reducedMotion ? 0L : 170L).start();
    }

    private void bindGameMetadataDescription(TextView target, String value) {
        if (target == null) return;
        target.setText(value);
        target.animate().cancel();
        if (!reducedMotion) {
            target.setAlpha(0f);
            target.setTranslationY(dp(3));
        }
        target.animate().alpha(1f).translationY(0f)
                .setDuration(reducedMotion ? 0L : 180L).start();
        if (target == expandedGameDescription && expandedDescriptionScroll != null) {
            expandedDescriptionScroll.scrollTo(0, 0);
            startExpandedDescriptionAutoScroll();
        }
    }

    private void bindGameMetadata(View panel, TextView title, TextView facts,
                                  TextView description, String name,
                                  String factsText, String descriptionText) {
        if (panel == null || title == null || facts == null || description == null) return;
        title.setText(name);
        facts.setText(factsText);
        description.setText(descriptionText);
        if (description == expandedGameDescription && expandedDescriptionScroll != null) {
            expandedDescriptionScroll.scrollTo(0, 0);
            startExpandedDescriptionAutoScroll();
        }
        if (panel == selectedGameMetadata && !expandedLibraryMode) {
            panel.setVisibility(View.VISIBLE);
        }
    }

    private void startExpandedDescriptionAutoScroll() {
        int generation = ++expandedDescriptionScrollGeneration;
        if (!expandedLibraryMode || expandedDescriptionScroll == null) return;
        expandedDescriptionScroll.scrollTo(0, 0);
        mainHandler.postDelayed(() -> advanceExpandedDescriptionAutoScroll(generation),
                1_500L);
    }

    private void advanceExpandedDescriptionAutoScroll(int generation) {
        if (generation != expandedDescriptionScrollGeneration
                || !expandedLibraryMode || expandedDescriptionScroll == null) return;
        View focused = getCurrentFocus();
        if (focused == null || expandedGrid == null
                || !isDescendant(expandedGrid, focused)) return;
        if (expandedDescriptionScroll.canScrollVertically(1)) {
            expandedDescriptionScroll.scrollBy(0, Math.max(1, dp(1)));
            mainHandler.postDelayed(
                    () -> advanceExpandedDescriptionAutoScroll(generation), 48L);
            return;
        }
        if (expandedDescriptionScroll.getScrollY() > 0) {
            mainHandler.postDelayed(
                    () -> {
                        if (generation != expandedDescriptionScrollGeneration
                                || !expandedLibraryMode
                                || expandedDescriptionScroll == null) return;
                        View currentFocus = getCurrentFocus();
                        if (currentFocus == null || expandedGrid == null
                                || !isDescendant(expandedGrid, currentFocus)) return;
                        expandedDescriptionScroll.smoothScrollTo(0, 0);
                        mainHandler.postDelayed(
                                () -> advanceExpandedDescriptionAutoScroll(generation),
                                2_000L);
                    },
                    2_500L);
        }
    }

    private void stopExpandedDescriptionAutoScroll() {
        expandedDescriptionScrollGeneration++;
    }

    private void showLibraryMetadataPlaceholder() {
        bindGameMetadata(selectedGameMetadata, selectedGameTitle,
                selectedGameFacts, selectedGameDescription,
                getString(R.string.playnite_full_library),
                getString(R.string.playnite_full_library_hint), "");
    }

    private String formatLastActivity(String value) {
        long epoch = PlayniteLibraryOrdering.activityEpoch(value);
        if (epoch == Long.MIN_VALUE) return getString(R.string.playnite_never_played);
        return DateFormat.getDateInstance(DateFormat.MEDIUM,
                getResources().getConfiguration().getLocales().get(0))
                .format(new Date(epoch));
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
        if (item.game.installRequiresAttention) {
            return getString(R.string.playnite_install_attention);
        }
        if (isPlayniteInstalling(hostUuid, item)) {
            return getString(R.string.playnite_installing);
        }
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

    private String playniteStateGlyph(String hostUuid, PlayniteDashboardItem item,
                                      boolean resumeSession) {
        if (resumeSession) return "▶  ";
        if (item.game.installRequiresAttention) return "!  ";
        if (isPlayniteInstalling(hostUuid, item)) return "↓  ";
        if (!item.game.installed) return "+  ";
        if (isVibepolloEnsureInFlight(hostUuid, item)) return "…  ";
        if (item.mappingState == PlayniteDashboardItem.MappingState.MAPPED) return "✓  ";
        if (item.mappingState == PlayniteDashboardItem.MappingState.FALLBACK_PLAYNITE) {
            return "↗  ";
        }
        return "!  ";
    }

    private String playniteStateChipLabel(String hostUuid, PlayniteDashboardItem item,
                                          boolean resumeSession) {
        if (resumeSession) return getString(R.string.console_resume);
        if (item.game.installRequiresAttention) {
            return getString(R.string.playnite_install_attention_short);
        }
        if (isPlayniteInstalling(hostUuid, item)) return getString(R.string.playnite_installing);
        if (!item.game.installed) return getString(R.string.playnite_install_action_short);
        if (isVibepolloEnsureInFlight(hostUuid, item)) {
            return getString(R.string.playnite_preparing_short);
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.MAPPED) {
            return getString(R.string.playnite_ready_short);
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.FALLBACK_PLAYNITE) {
            return getString(R.string.playnite_via_playnite_short);
        }
        return getString(R.string.playnite_configuration_short);
    }

    private void stylePlayniteStateChip(TextView state, String hostUuid,
                                        PlayniteDashboardItem item,
                                        boolean resumeSession) {
        boolean installing = isPlayniteInstalling(hostUuid, item);
        boolean unavailable = item.game.installed
                && item.mappingState != PlayniteDashboardItem.MappingState.MAPPED
                && item.mappingState != PlayniteDashboardItem.MappingState.FALLBACK_PLAYNITE
                && !isVibepolloEnsureInFlight(hostUuid, item);
        int textColor;
        int fill;
        int stroke;
        if (resumeSession) {
            textColor = 0xFFC8F2FF; fill = 0x80306B80; stroke = 0xB873D7FF;
        } else if (item.game.installRequiresAttention) {
            textColor = 0xFFFFE1A6; fill = 0x905E4315; stroke = 0xFFE3A93F;
        } else if (installing) {
            textColor = 0xFFFFE2A8; fill = 0x805C451A; stroke = 0xB8E4B34B;
        } else if (!item.game.installed) {
            textColor = 0xFFD7DCE1; fill = 0x70404950; stroke = 0x805F6A73;
        } else if (unavailable) {
            textColor = 0xFFFFB5AE; fill = 0x805A2926; stroke = 0xB8E16D63;
        } else if (isVibepolloEnsureInFlight(hostUuid, item)) {
            textColor = 0xFFFFE2A8; fill = 0x805C451A; stroke = 0xB8E4B34B;
        } else {
            textColor = 0xFFBFE9CD; fill = 0x70304D3A; stroke = 0x9062C985;
        }
        state.setTextColor(textColor);
        state.setPadding(dp(4), dp(1), dp(4), dp(1));
        GradientDrawable background = gradient(fill, fill, 6);
        background.setStroke(dp(1), stroke);
        state.setBackground(background);
    }

    private String vibepolloEnsureKey(String hostUuid, PlayniteDashboardItem item) {
        return hostUuid + ":" + item.game.playniteGameId;
    }

    private boolean isVibepolloEnsureInFlight(String hostUuid, PlayniteDashboardItem item) {
        return vibepolloEnsureInFlight.contains(vibepolloEnsureKey(hostUuid, item));
    }

    private String playniteInstallKey(String hostUuid, PlayniteDashboardItem item) {
        return playniteInstallKey(hostUuid, item.game.playniteGameId);
    }

    private String playniteInstallKey(String hostUuid, String gameId) {
        return hostUuid + ":" + gameId;
    }

    private boolean isPlayniteInstalling(String hostUuid, PlayniteDashboardItem item) {
        return item.game.installing
                || playniteInstallRequests.containsKey(playniteInstallKey(hostUuid, item));
    }

    private boolean isPlayniteInstalling(String hostUuid, PlayniteLibraryGame game) {
        return game.installing || playniteInstallRequests.containsKey(
                playniteInstallKey(hostUuid, game.playniteGameId));
    }

    private void resetPlaynitePoster(ImageView poster, PlayniteDashboardItem item) {
        ImageView backdrop = playnitePosterBackdrop(poster);
        if (backdrop != null) {
            backdrop.setImageDrawable(null);
            backdrop.setVisibility(View.GONE);
        }
        poster.setImageResource(R.drawable.ic_computer);
        poster.setScaleType(CONSOLE_UI_V2
                ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        poster.setPadding(dp(CONSOLE_UI_V2 ? 24 : 72),
                dp(CONSOLE_UI_V2 ? 36 : 54),
                dp(CONSOLE_UI_V2 ? 24 : 72),
                dp(CONSOLE_UI_V2 ? 36 : 54));
        poster.setTag(R.id.playnite_artwork_key, playniteArtworkTag(item));
    }

    private void loadPlaynitePoster(ComputerDetails host, PlayniteDashboardItem item,
                                    ImageView poster, boolean allowNetwork) {
        ComputerDetails latestHost = currentHost(host.uuid);
        if (latestHost == null) return;
        PlayniteArtworkSpec spec = playniteCardArtworkSpec(item.game);
        if (!spec.available()) return;
        ArtworkResult cached = cachedPlayniteArtwork(latestHost.uuid, item, spec);
        if (CONSOLE_UI_V2 && allowNetwork && cached != null
                && !spec.kind.equals(cached.kind)) {
            cached = null;
        }
        String expectedTag = playniteArtworkTag(item);
        if (cached != null) {
            loadPlayniteBitmap(cached.file, poster, expectedTag, cached.kind);
            if (CONSOLE_UI_V2) {
                showPlayniteBackdrop(latestHost, item, poster, allowNetwork);
            } else {
                showArtwork(cached.file, poster.getDrawable());
            }
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
                            if (CONSOLE_UI_V2) {
                                showPlayniteBackdrop(latestHost, item, poster, true);
                            } else {
                                showArtwork(result.file, poster.getDrawable());
                            }
                        }
                    }
                });
            } catch (IOException ignored) { }
        });
    }

    private void schedulePlayniteArtworkPrefetch(ComputerDetails host,
                                                  List<PlayniteDashboardItem> items) {
        schedulePlayniteArtworkPrefetch(host, items, stableIds(items));
    }

    private void schedulePlayniteArtworkPrefetch(ComputerDetails host,
                                                  List<PlayniteDashboardItem> items,
                                                  boolean includeBackdrops) {
        schedulePlayniteArtworkPrefetch(host, items,
                includeBackdrops ? stableIds(items) : Collections.emptySet());
    }

    private void schedulePlayniteArtworkPrefetch(ComputerDetails host,
                                                  List<PlayniteDashboardItem> items,
                                                  Set<String> backdropIds) {
        if (!active || host == null || items.isEmpty()) return;
        StringBuilder signature = new StringBuilder(host.uuid);
        for (PlayniteDashboardItem item : items) {
            signature.append('|').append(item.stableId()).append(':')
                    .append(playniteCardArtworkSpec(item.game).cacheIdentity());
            if (CONSOLE_UI_V2 && backdropIds.contains(item.stableId())) {
                signature.append(':')
                        .append(PlayniteArtworkSpec.forBackdrop(
                                item.game).cacheIdentity());
            }
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
        int workers = Math.min(3, snapshot.size());
        for (int worker = 0; worker < workers; worker++) {
            final int offset = worker;
            Future<?> task = playniteArtworkExecutor.submit(() -> {
                for (int index = offset; index < snapshot.size(); index += workers) {
                    if (Thread.currentThread().isInterrupted()
                            || token != playniteArtworkGeneration.get()) return;
                    prefetchPlayniteArtwork(connection, latestHost, snapshot.get(index),
                            backdropIds.contains(snapshot.get(index).stableId()), token);
                }
            });
            playniteArtworkPrefetchTasks.add(task);
            if (playniteArtworkPrefetch == null) playniteArtworkPrefetch = task;
        }
    }

    private void prefetchPlayniteArtwork(HostGatewayClient.Connection connection,
                                         ComputerDetails host,
                                         PlayniteDashboardItem item,
                                         boolean includeBackdrops, int token) {
        PlayniteArtworkSpec spec = playniteCardArtworkSpec(item.game);
        if (!spec.available()) return;
        try {
            ArtworkResult result = cachedPlayniteArtwork(host.uuid, item, spec);
            if (result == null || !spec.kind.equals(result.kind)) {
                try {
                    result = fetchPlayniteArtwork(connection, host.uuid, item, spec);
                } catch (IOException primaryFailure) {
                    if (result == null) throw primaryFailure;
                }
            }
            cachePlayniteBitmap(result.file);
            ArtworkResult ready = result;
            mainHandler.post(() -> applyPrefetchedPlayniteArtwork(
                    token, host.uuid, item, ready));
            if (!CONSOLE_UI_V2 || !includeBackdrops) return;
            PlayniteArtworkSpec backdropSpec = PlayniteArtworkSpec.forBackdrop(item.game);
            if (!backdropSpec.available()
                    || backdropSpec.cacheIdentity().equals(spec.cacheIdentity())) return;
            ArtworkResult backdrop = cachedPlayniteArtwork(host.uuid, item, backdropSpec);
            if (backdrop == null || !backdropSpec.kind.equals(backdrop.kind)) {
                try {
                    backdrop = fetchPlayniteArtwork(connection, host.uuid, item, backdropSpec);
                } catch (IOException primaryFailure) {
                    if (backdrop == null) throw primaryFailure;
                }
            }
            ArtworkResult readyBackdrop = backdrop;
            mainHandler.post(() -> {
                View card = directChildWithTag(appRow, "playnite:" + item.stableId());
                if (token == playniteArtworkGeneration.get()
                        && card != null && card.hasFocus()) {
                    showArtwork(readyBackdrop.file, null);
                }
            });
        } catch (IOException ignored) { }
    }

    private void cancelPlayniteArtworkPrefetch() {
        playniteArtworkGeneration.incrementAndGet();
        if (playniteArtworkPrefetch != null) {
            playniteArtworkPrefetch.cancel(true);
            playniteArtworkPrefetch = null;
        }
        synchronized (playniteArtworkPrefetchTasks) {
            for (Future<?> task : playniteArtworkPrefetchTasks) task.cancel(true);
            playniteArtworkPrefetchTasks.clear();
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
        for (PlayniteDashboardItem item : allPlayniteItems) {
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
        if (card == null && expandedGrid != null) {
            card = directChildWithTag(expandedGrid, "playnite:" + item.stableId());
        }
        if (!(card instanceof ViewGroup)) return;
        ImageView poster = (ImageView) findTaggedChild((ViewGroup) card, "playnite.poster");
        if (poster == null || !playniteArtworkTag(item).equals(
                poster.getTag(R.id.playnite_artwork_key))) return;
        loadPlayniteBitmap(result.file, poster, playniteArtworkTag(item), result.kind);
        if (card.hasFocus()) {
            if (CONSOLE_UI_V2) {
                showPlayniteBackdrop(currentHost(hostUuid), item, poster, true);
            } else {
                showArtwork(result.file, poster.getDrawable());
            }
        }
    }

    private View directChildWithTag(ViewGroup parent, Object tag) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (Objects.equals(tag, child.getTag())) return child;
        }
        return null;
    }

    private String playniteArtworkTag(PlayniteDashboardItem item) {
        return item.stableId() + ":" + playniteCardArtworkSpec(
                item.game).cacheIdentity();
    }

    private PlayniteArtworkSpec playniteCardArtworkSpec(PlayniteLibraryGame game) {
        return CONSOLE_UI_V2
                ? PlayniteArtworkSpec.forCard(game)
                : PlayniteArtworkSpec.forGame(game);
    }

    private void showPlayniteBackdrop(ComputerDetails host, PlayniteDashboardItem item,
                                      ImageView poster, boolean allowNetwork) {
        if (host == null) return;
        PlayniteArtworkSpec spec = PlayniteArtworkSpec.forBackdrop(item.game);
        if (!spec.available()) return;
        ArtworkResult cached = cachedPlayniteArtwork(host.uuid, item, spec);
        if (cached != null) {
            showArtwork(cached.file, poster.getDrawable());
            if (spec.kind.equals(cached.kind) || !allowNetwork) return;
        }
        if (!allowNetwork) return;
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, address);
        if (connection == null) return;
        String expectedTag = playniteArtworkTag(item);
        executor.execute(() -> {
            try {
                ArtworkResult result = fetchPlayniteArtwork(
                        connection, host.uuid, item, spec);
                mainHandler.post(() -> {
                    if (!expectedTag.equals(poster.getTag(R.id.playnite_artwork_key))
                            || !(poster.hasFocus() || cardParentHasFocus(poster))) return;
                    showArtwork(result.file, poster.getDrawable());
                });
            } catch (IOException ignored) { }
        });
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
        Bitmap cached = playniteBitmapCache.get(playniteBitmapCacheKey(file));
        if (cached != null) {
            applyPlayniteBitmap(poster, expectedTag, kind, cached);
            return;
        }
        executor.execute(() -> {
            Bitmap bitmap = cachePlayniteBitmap(file);
            mainHandler.post(() -> applyPlayniteBitmap(
                    poster, expectedTag, kind, bitmap));
        });
    }

    private Bitmap cachePlayniteBitmap(File file) {
        if (file == null || !file.isFile()) return null;
        String key = playniteBitmapCacheKey(file);
        Bitmap cached = playniteBitmapCache.get(key);
        if (cached != null) return cached;
        boolean landscape = file.getName().contains("_background_");
        Bitmap decoded = decodeSampledBitmap(file,
                landscape ? 512 : 256, landscape ? 288 : 344);
        if (decoded != null) {
            int maxWidth = landscape ? 512 : 256;
            int maxHeight = landscape ? 288 : 344;
            float scale = Math.min(1f, Math.min(maxWidth / (float) decoded.getWidth(),
                    maxHeight / (float) decoded.getHeight()));
            if (scale < .999f) {
                Bitmap scaled = Bitmap.createScaledBitmap(decoded,
                        Math.max(1, Math.round(decoded.getWidth() * scale)),
                        Math.max(1, Math.round(decoded.getHeight() * scale)), true);
                if (scaled != decoded) decoded.recycle();
                decoded = scaled;
            }
        }
        if (decoded != null) playniteBitmapCache.put(key, decoded);
        return decoded;
    }

    private String playniteBitmapCacheKey(File file) {
        return file == null ? "" : file.getAbsolutePath() + ':' + file.length()
                + ':' + file.lastModified();
    }

    private void applyPlayniteBitmap(ImageView poster, String expectedTag,
                                     String kind, Bitmap bitmap) {
        Object expected = poster.getTag(R.id.playnite_artwork_key);
        if (bitmap == null || !expectedTag.equals(expected)) return;
        poster.setPadding(0, 0, 0, 0);
        boolean landscape = "background".equals(kind);
        poster.setScaleType(CONSOLE_UI_V2
                ? ImageView.ScaleType.CENTER_CROP
                : landscape ? ImageView.ScaleType.CENTER_CROP
                : ImageView.ScaleType.FIT_CENTER);
        BitmapDrawable drawable = filteredBitmapDrawable(bitmap);
        ImageView backdrop = playnitePosterBackdrop(poster);
        if (backdrop != null) {
            if (CONSOLE_UI_V2 || landscape) {
                backdrop.setImageDrawable(null);
                backdrop.setVisibility(View.GONE);
            } else {
                backdrop.setImageDrawable(filteredBitmapDrawable(bitmap));
                backdrop.setVisibility(View.VISIBLE);
            }
        }
        poster.setImageDrawable(drawable);
    }

    private BitmapDrawable filteredBitmapDrawable(Bitmap bitmap) {
        BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
        drawable.setFilterBitmap(true);
        drawable.setDither(true);
        drawable.setAntiAlias(true);
        return drawable;
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
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inDither = true;
        options.inScaled = false;
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

    private void showPlayniteGameActions(ComputerDetails host,
                                         PlayniteDashboardItem item) {
        if (host == null || item == null) return;
        List<View> actions = new ArrayList<>();
        boolean installing = isPlayniteInstalling(host.uuid, item);

        if (item.game.installed) {
            TextView launch = panelAction(getString(R.string.console_play));
            launch.setEnabled(!installing);
            launch.setAlpha(installing ? .42f : 1f);
            launch.setOnClickListener(view -> {
                hideSidePanel();
                activatePlayniteItem(host.uuid, item);
            });
            actions.add(launch);
            if (item.stableId().equals(resumePlayniteGameId)
                    && host.runningGameId != 0) {
                TextView terminate = panelAction(
                        getString(R.string.overlay_menu_quit_session));
                terminate.setTextColor(0xFFFF9B92);
                terminate.setOnClickListener(view -> confirmTerminateSession(host));
                actions.add(terminate);
            }
        } else if (installing) {
            if (item.game.installRequiresAttention) {
                boolean localOnly = "secure_desktop".equals(
                        item.game.installAttentionReason);
                TextView finish = panelAction(getString(localOnly
                        ? R.string.playnite_install_confirm_on_pc
                        : R.string.playnite_install_continue));
                finish.setEnabled(!localOnly);
                finish.setAlpha(localOnly ? .58f : 1f);
                if (!localOnly) {
                    finish.setOnClickListener(view -> {
                        hideSidePanel();
                        continuePlayniteInstallation(host, item);
                    });
                }
                actions.add(finish);
            } else {
                TextView status = panelAction(getString(R.string.playnite_installing));
                status.setEnabled(false);
                status.setAlpha(.52f);
                actions.add(status);
            }
        } else {
            TextView install = panelAction(getString(R.string.playnite_install_action_short));
            install.setOnClickListener(view -> {
                hideSidePanel();
                startPlayniteInstallation(host, item);
            });
            actions.add(install);
        }

        if (item.game.installed) {
            TextView launchTarget = panelAction(
                    getString(R.string.playnite_choose_launch_method));
            launchTarget.setOnClickListener(view ->
                    showPlayniteTargetPicker(host, item, currentSunshineApps));
            actions.add(launchTarget);
        }

        boolean locallyHidden = locallyHiddenPlayniteGames(host.uuid)
                .contains(item.game.playniteGameId);
        TextView visibility = panelAction(getString(locallyHidden
                ? R.string.console_show_app : R.string.applist_menu_hide_app));
        visibility.setOnClickListener(view -> {
            hideSidePanel();
            setPlayniteGameHidden(host.uuid, item.game.playniteGameId, !locallyHidden);
            ComputerDetails current = currentHost(host.uuid);
            if (current != null) renderPlayniteLibrary(current, currentSunshineApps);
        });
        actions.add(visibility);

        TextView details = panelAction(getString(R.string.applist_menu_details));
        details.setOnClickListener(view -> showPlayniteGameDetails(item));
        actions.add(details);

        String panelDetails = getString(R.string.playnite_game_actions_details);
        if (item.game.installRequiresAttention) {
            String prompt = !item.game.installWindowTitle.isEmpty()
                    ? item.game.installWindowTitle : item.game.installLauncher;
            panelDetails = getString("secure_desktop".equals(
                            item.game.installAttentionReason)
                            ? R.string.playnite_install_attention_details_pc
                            : R.string.playnite_install_attention_details,
                    prompt.isEmpty() ? item.game.name : prompt);
        }
        showSidePanel(getString(R.string.playnite_game_options), item.game.name,
                panelDetails,
                actions.toArray(new View[0]));
    }

    private void showPlayniteGameDetails(PlayniteDashboardItem item) {
        String source = item.game.source.isEmpty()
                ? getString(R.string.playnite_metadata_unknown) : item.game.source;
        String genres = item.game.genres.isEmpty()
                ? getString(R.string.playnite_metadata_unknown) : item.game.genres;
        String description = item.game.description.isEmpty()
                ? getString(R.string.playnite_description_unavailable) : item.game.description;
        String body = getString(R.string.playnite_game_details_body,
                item.game.installed ? getString(R.string.playnite_installed)
                        : getString(R.string.playnite_uninstalled),
                formatLastActivity(item.game.lastActivity),
                formatPlayniteTime(item.game.playtimeSeconds), source, genres, description);
        TextView back = panelAction(getString(R.string.console_back_close));
        back.setTag("panel.back");
        back.setOnClickListener(view -> handlePanelBack());
        showScrollableDetailsSidePanel(getString(R.string.playnite_game_details), item.game.name,
                body, back);
        animateDetailsPanelIn();
    }

    private void animateDetailsPanelIn() {
        if (sidePanelScroll == null) return;
        if (reducedMotion) {
            sidePanelScroll.animate().cancel();
            sidePanelScroll.setAlpha(1f);
            sidePanelScroll.setScaleX(1f);
            sidePanelScroll.setScaleY(1f);
            sidePanelScroll.setTranslationX(0f);
            resetDetailsPanelChildren();
            return;
        }
        sidePanelScroll.post(() -> {
            if (sidePanelScroll == null || sideDialog == null || !sideDialog.isShowing()) return;
            sidePanelScroll.animate().cancel();
            float pivotY = sidePanelScroll.getHeight() / 2f;
            if (lastContentFocus != null && lastContentFocus.isAttachedToWindow()) {
                int[] source = new int[2];
                int[] panel = new int[2];
                lastContentFocus.getLocationOnScreen(source);
                sidePanelScroll.getLocationOnScreen(panel);
                pivotY = Math.max(0f, Math.min(sidePanelScroll.getHeight(),
                        source[1] + lastContentFocus.getHeight() / 2f - panel[1]));
            }
            sidePanelScroll.setPivotX(0f);
            sidePanelScroll.setPivotY(pivotY);
            sidePanelScroll.setAlpha(.28f);
            sidePanelScroll.setScaleX(.91f);
            sidePanelScroll.setScaleY(.91f);
            sidePanelScroll.setTranslationX(dp(42));
            prepareDetailsPanelChildren();
            sidePanelScroll.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .translationX(0f).setDuration(260L).start();
            animateDetailsPanelChildrenIn();
        });
    }

    private void prepareDetailsPanelChildren() {
        if (sidePanel == null) return;
        for (int index = 0; index < sidePanel.getChildCount(); index++) {
            View child = sidePanel.getChildAt(index);
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(dp(7));
        }
    }

    private void animateDetailsPanelChildrenIn() {
        if (sidePanel == null) return;
        for (int index = 0; index < sidePanel.getChildCount(); index++) {
            View child = sidePanel.getChildAt(index);
            child.animate().alpha(1f).translationY(0f)
                    .setStartDelay(65L + index * 32L)
                    .setDuration(170L).start();
        }
    }

    private void resetDetailsPanelChildren() {
        if (sidePanel == null) return;
        for (int index = 0; index < sidePanel.getChildCount(); index++) {
            View child = sidePanel.getChildAt(index);
            child.animate().cancel();
            child.setAlpha(1f);
            child.setTranslationY(0f);
        }
    }

    private Set<String> locallyHiddenPlayniteGames(String hostUuid) {
        if (hostUuid == null) return Collections.emptySet();
        return new HashSet<>(preferences.getStringSet(
                "playnite_hidden." + hostUuid, Collections.emptySet()));
    }

    private void setPlayniteGameHidden(String hostUuid, String gameId, boolean hidden) {
        Set<String> values = locallyHiddenPlayniteGames(hostUuid);
        if (hidden) values.add(gameId);
        else values.remove(gameId);
        preferences.edit().putStringSet("playnite_hidden." + hostUuid, values).apply();
    }

    private void activatePlayniteItem(String hostUuid, PlayniteDashboardItem item) {
        ComputerDetails host = currentHost(hostUuid);
        if (host == null) {
            ConsoleUiFeedback.makeText(this, R.string.error_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (item.game.installRequiresAttention) {
            continuePlayniteInstallation(host, item);
            return;
        }
        if (isPlayniteInstalling(hostUuid, item)) {
            ConsoleUiFeedback.makeText(this, getString(R.string.playnite_install_in_progress,
                    item.game.name), Toast.LENGTH_SHORT).show();
            return;
        }
        List<NvApp> apps = currentSunshineApps;
        if (!item.game.installed) {
            startPlayniteInstallation(host, item);
            return;
        }
        if (isVibepolloEnsureInFlight(hostUuid, item)) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_creating_vibepollo_app,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.MAPPED) {
            NvApp target = PlayniteTargetResolver.launchTarget(item, apps,
                    ConsoleActionCatalog.isOnline(host));
            if (target != null) {
                launchOrConfirm(host, target, LaunchTransitionType.GAME,
                        item.game.playniteGameId);
                return;
            }
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.FALLBACK_PLAYNITE) {
            NvApp fallback = PlayniteTargetResolver.launchTarget(item, apps,
                    ConsoleActionCatalog.isOnline(host));
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

    private void startPlayniteInstallation(ComputerDetails host,
                                           PlayniteDashboardItem item) {
        if (host == null || item == null) return;
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, address);
        if (connection == null) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_gateway_not_configured,
                    Toast.LENGTH_LONG).show();
            return;
        }
        String key = playniteInstallKey(host.uuid, item);
        playniteInstallRequests.put(key, item.game.name);
        playniteInstallObserved.remove(key);
        preferences.edit().putLong(playniteCarouselInstallActivityKey(
                        host.uuid, item.game.playniteGameId), System.currentTimeMillis())
                .remove(playniteInstallNotificationKey(
                host.uuid, item.game.playniteGameId))
                .putString(playniteInstallPendingKey(host.uuid,
                        item.game.playniteGameId), item.game.name).apply();
        renderPlayniteLibrary(host, currentSunshineApps);
        ConsoleUiFeedback.makeText(this, getString(R.string.playnite_install_starting,
                item.game.name), Toast.LENGTH_LONG).show();
        executor.execute(() -> {
            try {
                hostGatewayClient.installPlayniteGame(
                        connection, item.game.playniteGameId);
                mainHandler.post(() -> {
                    if (!active || !host.uuid.equals(selectedHostUuid)) return;
                    requestPlayniteRefresh(currentHost(host.uuid), false);
                });
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> {
                    playniteInstallRequests.remove(key);
                    playniteInstallObserved.remove(key);
                    preferences.edit().remove(playniteInstallPendingKey(
                            host.uuid, item.game.playniteGameId)).apply();
                    ComputerDetails current = currentHost(host.uuid);
                    if (current != null && host.uuid.equals(selectedHostUuid)) {
                        renderedPlayniteItems = Collections.emptyList();
                        renderPlayniteLibrary(current, currentSunshineApps);
                    }
                    if (consoleAudioEngine != null) {
                        consoleAudioEngine.play(ConsoleAudioSynthesis.Cue.ERROR);
                    }
                    ConsoleUiFeedback.makeText(this, getString(R.string.playnite_install_failed,
                            item.game.name), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void continuePlayniteInstallation(ComputerDetails host,
                                              PlayniteDashboardItem item) {
        if (host == null || item == null) return;
        if ("secure_desktop".equals(item.game.installAttentionReason)) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_install_confirm_on_pc_details,
                    Toast.LENGTH_LONG).show();
            return;
        }
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        HostGatewayClient.Connection connection =
                hostGatewayStore.loadClientConnection(host.uuid, address);
        if (connection == null) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_gateway_not_configured,
                    Toast.LENGTH_LONG).show();
            return;
        }
        NvApp streamTarget = PlayniteTargetResolver.resolveInstallationStream(
                host.uuid, currentSunshineApps, playniteLaunchTargetStore);
        if (streamTarget == null) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_no_fullscreen_target,
                    Toast.LENGTH_LONG).show();
            return;
        }
        executor.execute(() -> {
            try {
                hostGatewayClient.focusPlayniteInstallation(
                        connection, item.game.playniteGameId);
                mainHandler.post(() -> {
                    ConsoleUiFeedback.makeText(this, getString(
                            R.string.playnite_install_opening_confirmation,
                            item.game.name), Toast.LENGTH_LONG).show();
                    beginLaunch(currentHost(host.uuid), streamTarget, null,
                            LaunchTransitionType.GENERIC, item.game.playniteGameId);
                });
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> ConsoleUiFeedback.makeText(this,
                        R.string.playnite_install_window_unavailable,
                        Toast.LENGTH_LONG).show());
            }
        });
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
            ConsoleUiFeedback.makeText(this, R.string.playnite_creating_vibepollo_app,
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
            ConsoleUiFeedback.makeText(this, R.string.console_initializing, Toast.LENGTH_SHORT).show();
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
            ConsoleUiFeedback.makeText(this, R.string.error_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!item.game.installed) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_not_installed, Toast.LENGTH_SHORT).show();
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
                ConsoleUiFeedback.makeText(this, getString(R.string.playnite_target_saved,
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
        updateLaunchDesktopButton(host, apps);
    }

    private void updateLaunchDesktopButton(ComputerDetails host, List<NvApp> apps) {
        if (launchDesktopButton == null) return;
        NvApp desktop = host == null ? null
                : PlayniteTargetResolver.findPlayableExactName(apps, "Desktop");
        boolean available = host != null && ConsoleActionCatalog.isPaired(host)
                && desktop != null;
        launchDesktopButton.setEnabled(available);
        launchDesktopButton.setAlpha(available ? 1f : .48f);
        launchDesktopButton.setContentDescription(getString(available
                ? R.string.console_launch_desktop_description
                : R.string.console_launch_desktop_unavailable));
    }

    private void launchDesktopSession() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        NvApp desktop = PlayniteTargetResolver.findPlayableExactName(
                currentSunshineApps, "Desktop");
        if (host == null || desktop == null || !ConsoleActionCatalog.isPaired(host)) {
            ConsoleUiFeedback.makeText(this, R.string.console_launch_desktop_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        launchOrConfirm(host, desktop, LaunchTransitionType.GENERIC, "");
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
        allPlayniteItems = Collections.emptyList();
        unfilteredPlayniteItems = Collections.emptyList();
        if (expandedLibraryMode) exitExpandedLibrary();
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
        boolean debugCarousel = CONSOLE_UI_V2 && !portraitLayout;
        LinearLayout card = cardBase(dp(portraitLayout ? 220 : debugCarousel ? 84 : 205),
                dp(portraitLayout ? 225 : debugCarousel ? 150 : 225));
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.TOP);
        card.setPadding(0, 0, 0, 0);
        card.setClipToOutline(true);
        card.setTag("app:" + host.uuid + ":" + app.getAppId());
        styleCard(card, false);
        ImageView poster = new ImageView(this);
        poster.setScaleType(debugCarousel
                ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        poster.setImageResource(R.drawable.ic_computer);
        poster.setPadding(dp(debugCarousel ? 24 : 72), dp(debugCarousel ? 36 : 54),
                dp(debugCarousel ? 24 : 72), dp(debugCarousel ? 36 : 54));
        poster.setBackground(gradient(0xFF26333D, 0xFF172128, 12));
        card.addView(poster, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(portraitLayout ? 140 : debugCarousel ? 110 : 142)));
        File artwork = assetLoader.getFile(host.uuid, app.getAppId());
        loadPoster(host, app, artwork, poster);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.TOP);
        copy.setPadding(dp(debugCarousel ? 7 : 14), dp(debugCarousel ? 4 : 12),
                dp(debugCarousel ? 7 : 14), dp(debugCarousel ? 3 : 10));
        TextView name = text(app.getAppName(), debugCarousel ? 9 : 15, Color.WHITE, true);
        name.setMaxLines(debugCarousel ? 1 : 2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        updateCarouselMarquee(name, false);
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
        TextView metadataView = text(metadata, debugCarousel ? 7 : 10, 0xFF929BAD, false);
        metadataView.setMaxLines(2);
        metadataView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metadataParams = wrapLinear();
        metadataParams.topMargin = dp(debugCarousel ? 1 : 5);
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
            updateCarouselMarquee(name, focused);
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

    private void updateCarouselMarquee(TextView title, boolean focused) {
        if (!CONSOLE_UI_V2 || title == null) return;
        title.setSingleLine(true);
        title.setHorizontallyScrolling(focused);
        title.setEllipsize(focused
                ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        title.setMarqueeRepeatLimit(focused ? -1 : 0);
        title.setSelected(focused);
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
            ConsoleUiFeedback.makeText(this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
            return;
        }
        if (latestHost.runningGameId != 0 && latestHost.runningGameId != app.getAppId()) {
            confirmQuitAction(() -> beginLaunch(latestHost, app, null, transitionType,
                    playniteGameId));
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

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim();
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
                true, () -> confirmQuitAction(() -> {
                    hideSidePanel();
                    ServerHelper.doQuit(this, host, app, managerBinder,
                            () -> { if (appListPoller != null) appListPoller.pollNow(); });
                }));
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
                    ConsoleUiFeedback.makeText(this, R.string.quick_launch_added, Toast.LENGTH_SHORT).show();
                    showAppActions(host, app, poster);
                });
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.QUICK_REMOVE,
                getString(R.string.quick_launch_delete), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> {
                    removeAppFromQuickLaunch(host.uuid, app.getAppId());
                    ConsoleUiFeedback.makeText(this, R.string.quick_launch_removed, Toast.LENGTH_SHORT).show();
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
                        ConsoleUiFeedback.makeText(this, R.string.unable_to_pin_shortcut, Toast.LENGTH_LONG).show();
                    }
                });
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.DETAILS,
                getString(R.string.applist_menu_details), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> consoleFeedback.showMessage(
                        getString(R.string.title_details), app.toString(),
                        getString(R.string.console_close), null));
        List<View> actions = new ArrayList<>();
        for (ConsoleAction action : resolved) actions.add(actionView(action));
        showSidePanel(getString(R.string.console_apps_eyebrow), app.getAppName(),
                getString(R.string.console_app_actions_details), actions.toArray(new View[0]));
    }

    private void confirmQuitAction(Runnable onConfirm) {
        consoleFeedback.showConfirm(getString(R.string.applist_menu_quit),
                getString(R.string.applist_quit_confirmation),
                getString(android.R.string.cancel), getString(R.string.applist_menu_quit),
                true, onConfirm);
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
                    view.setScaleType(CONSOLE_UI_V2
                            ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
                    view.clearColorFilter();
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private void updateArtworkReadability(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
        int columns = 12;
        int rows = 8;
        float total = 0f;
        int samples = 0;
        for (int row = 0; row < rows; row++) {
            int y = Math.min(bitmap.getHeight() - 1,
                    Math.round((row + .5f) * bitmap.getHeight() / rows));
            for (int column = 0; column < columns; column++) {
                int x = Math.min(bitmap.getWidth() - 1,
                        Math.round((column + .5f) * bitmap.getWidth() / columns));
                int color = bitmap.getPixel(x, y);
                total += ConsoleArtworkReadability.perceivedLuminance(
                        Color.red(color), Color.green(color), Color.blue(color));
                samples++;
            }
        }
        animateArtworkScrimTo(samples == 0 ? .42f : total / samples);
    }

    private void animateArtworkScrimTo(float luminance) {
        float target = Math.max(0f, Math.min(1f, luminance));
        if (artworkScrim == null) {
            artworkScrimLuminance = target;
            return;
        }
        if (artworkScrimAnimator != null) artworkScrimAnimator.cancel();
        if (reducedMotion || Math.abs(target - artworkScrimLuminance) < .015f) {
            artworkScrimLuminance = target;
            artworkScrim.setBackground(artworkScrimDrawable(target));
            return;
        }
        artworkScrimAnimator = ValueAnimator.ofFloat(artworkScrimLuminance, target);
        artworkScrimAnimator.setDuration(240L);
        artworkScrimAnimator.addUpdateListener(animation -> {
            artworkScrimLuminance = (Float) animation.getAnimatedValue();
            artworkScrim.setBackground(artworkScrimDrawable(artworkScrimLuminance));
        });
        artworkScrimAnimator.start();
    }

    private GradientDrawable artworkScrimDrawable(float luminance) {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                ConsoleArtworkReadability.gradient(luminance, CONSOLE_UI_V2));
    }

    private void showArtwork(File file, Drawable preview) {
        if (file == null || !file.exists()) return;
        String key = file.getAbsolutePath() + ':' + file.length() + ':' + file.lastModified();
        if (key.equals(pendingArtworkKey) || key.equals(loadingArtworkKey)
                || key.equals(displayedArtworkKey)) return;
        if (pendingArtworkCommit != null) mainHandler.removeCallbacks(pendingArtworkCommit);
        pendingArtworkFile = file;
        pendingArtworkPreview = preview;
        pendingArtworkKey = key;
        pendingArtworkCommit = () -> {
            File settledFile = pendingArtworkFile;
            Drawable settledPreview = pendingArtworkPreview;
            String settledKey = pendingArtworkKey;
            pendingArtworkCommit = null;
            pendingArtworkFile = null;
            pendingArtworkPreview = null;
            pendingArtworkKey = null;
            if (settledFile != null && settledKey != null) {
                showArtworkSettled(settledFile, settledPreview, settledKey);
            }
        };
        mainHandler.postDelayed(pendingArtworkCommit,
                reducedMotion ? 0L : ARTWORK_FOCUS_SETTLE_MS);
    }

    private void showArtworkSettled(File file, Drawable preview, String artworkKey) {
        int token = artworkGeneration.incrementAndGet();
        loadingArtworkKey = artworkKey;
        if (preview != null && !CONSOLE_UI_V2) {
            Drawable next = cloneDrawable(preview);
            Drawable current = artworkHero.getDrawable();
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
            artworkHero.animate().alpha(.72f)
                    .setDuration(reducedMotion ? 0 : 180).start();
            artworkScrim.animate().cancel();
            artworkScrim.animate().alpha(1f).setDuration(reducedMotion ? 0 : 180).start();
        }
        executor.execute(() -> {
            Bitmap bitmap = decodeArtwork(file, CONSOLE_UI_V2 ? 1920 : 1200);
            int accent = sampleAccent(bitmap);
            mainHandler.post(() -> {
                if (token != artworkGeneration.get() || bitmap == null) {
                    if (artworkKey.equals(loadingArtworkKey)) loadingArtworkKey = null;
                    return;
                }
                loadingArtworkKey = null;
                displayedArtworkKey = artworkKey;
                glassAccent = accent;
                updateArtworkReadability(bitmap);
                ImageView outgoingBackdrop = artworkBackdrop;
                ImageView incomingBackdrop = artworkBackdropNext;
                outgoingBackdrop.animate().cancel();
                incomingBackdrop.animate().cancel();
                incomingBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
                incomingBackdrop.setImageBitmap(bitmap);
                incomingBackdrop.setAlpha(reducedMotion
                        ? (CONSOLE_UI_V2 ? .72f : .16f) : 0f);
                if (!CONSOLE_UI_V2) {
                    // The preview and final hero have identical geometry. Replacing the
                    // preview avoids a soft double-image while retaining the tile-to-tile crossfade.
                    artworkHero.setImageBitmap(bitmap);
                    artworkHero.animate().alpha(.72f)
                            .setDuration(reducedMotion ? 0 : 220).start();
                }
                float targetAlpha = CONSOLE_UI_V2 ? .72f : .16f;
                if (reducedMotion) {
                    outgoingBackdrop.setAlpha(0f);
                    finishBackdropSwap(token, outgoingBackdrop, incomingBackdrop);
                } else {
                    incomingBackdrop.animate().alpha(targetAlpha)
                            .setDuration(ARTWORK_CROSSFADE_MS).start();
                    outgoingBackdrop.animate().alpha(0f)
                            .setDuration(ARTWORK_CROSSFADE_MS)
                            .withEndAction(() -> finishBackdropSwap(
                                    token, outgoingBackdrop, incomingBackdrop))
                            .start();
                }
                artworkScrim.animate().alpha(1f).setDuration(reducedMotion ? 0 : 220).start();
                View focused = getCurrentFocus();
                if (focused != null && focused.getTag() instanceof String
                        && ((String) focused.getTag()).startsWith("app:")) {
                    styleCard(focused, true);
                }
            });
        });
    }

    private void finishBackdropSwap(int token, ImageView outgoing, ImageView incoming) {
        if (token != artworkGeneration.get()) return;
        artworkBackdrop = incoming;
        artworkBackdropNext = outgoing;
        artworkBackdropNext.animate().cancel();
        artworkBackdropNext.setAlpha(0f);
        artworkBackdropNext.setImageDrawable(null);
    }

    private void clearArtwork() {
        if (pendingArtworkCommit != null) {
            mainHandler.removeCallbacks(pendingArtworkCommit);
            pendingArtworkCommit = null;
        }
        pendingArtworkFile = null;
        pendingArtworkPreview = null;
        pendingArtworkKey = null;
        loadingArtworkKey = null;
        displayedArtworkKey = null;
        int token = artworkGeneration.incrementAndGet();
        glassAccent = 0xFF73D7FF;
        animateArtworkScrimTo(.42f);
        if (artworkBackdrop != null) {
            artworkBackdrop.animate().cancel();
            artworkBackdrop.animate().alpha(0f).setDuration(reducedMotion ? 0 : 260).start();
        }
        if (artworkBackdropNext != null) {
            artworkBackdropNext.animate().cancel();
            artworkBackdropNext.animate().alpha(0f)
                    .setDuration(reducedMotion ? 0 : 260).start();
        }
        if (artworkHero != null) {
            artworkHero.animate().cancel();
            artworkHero.animate().alpha(0f).setDuration(reducedMotion ? 0 : 260)
                    .withEndAction(() -> {
                        if (token != artworkGeneration.get()) return;
                        artworkBackdrop.setImageDrawable(null);
                        artworkBackdropNext.setImageDrawable(null);
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
        beginLaunch(host, app, quickLaunchKey, transitionType,
                playniteGameId, playniteGameId);
    }

    private void beginLaunch(ComputerDetails host, NvApp app, String quickLaunchKey,
                             LaunchTransitionType transitionType,
                             String playniteGameId, String loadingArtworkGameId) {
        if (handleRetainedStreamLaunch(host, app, playniteGameId)) return;
        if (managerBinder == null) {
            ConsoleUiFeedback.makeText(this, R.string.console_initializing, Toast.LENGTH_SHORT).show();
            return;
        }
        int token = launchGeneration.incrementAndGet();
        LaunchTransitionSpec transition = LaunchTransitionSpec.create(
                host.uuid, transitionType, app.getAppId(), playniteGameId,
                System.currentTimeMillis());
        SuspendedSessionStore.Session suspendedLaunch =
                SuspendedSessionStore.load(this, host.uuid);
        boolean restoringSuspendedSession = suspendedLaunch != null
                && suspendedLaunch.resumedAt == 0L
                && suspendedLaunch.sunshineAppId == app.getAppId();
        String loadingArtworkPath = cachedLoadingArtworkPath(
                host.uuid, loadingArtworkGameId);
        showLoading(host.name, app.getAppName(), transitionType, loadingArtworkPath);
        Runnable startAfterOverlayFrame = () -> executor.execute(() -> {
            ComputerDetails ready = HostReadiness.await(
                    () -> managerBinder != null ? managerBinder.getComputer(host.uuid) : null,
                    host,
                    () -> token != launchGeneration.get() || !active,
                    message -> setLoadingStatus(token, message),
                    getString(R.string.console_wol_status, host.name),
                    getString(R.string.console_waiting_stream_ports));
            if (ready != null && restoringSuspendedSession) {
                PlayniteTransitionGateway gateway = PlayniteTransitionGateway.connect(
                        this, host.uuid, ready.activeAddress != null
                                ? ready.activeAddress.address : null);
                if (gateway != null) {
                    try {
                        gateway.focusGame();
                        Thread.sleep(350L);
                    } catch (IOException ignored) {
                        // Stream resume remains available when foreground restore fails.
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
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

    private boolean handleRetainedStreamLaunch(ComputerDetails host, NvApp app,
                                                String playniteGameId) {
        if (!retainedStreamHome) return false;
        boolean sameHost = host != null && retainedStreamHostId.equalsIgnoreCase(host.uuid);
        boolean sameApp = app != null && app.getAppId() == retainedStreamAppId;
        boolean sameGame = !retainedStreamPlayniteGameId.isEmpty()
                && retainedStreamPlayniteGameId.equalsIgnoreCase(normalizeId(playniteGameId));
        if (sameHost && (sameApp || sameGame)) {
            returnToRetainedStream();
        } else {
            ConsoleUiFeedback.makeText(this, R.string.console_stream_home_switch_blocked,
                    Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private void returnToRetainedStream() {
        if (isFinishing()) return;
        if (!RetainedStreamSessionCoordinator.canResumeInstantly()
                && SessionResumeManager.hasPendingSession(this)) {
            startActivity(SessionResumeManager.buildResumeIntent(this));
        }
        if (retainedStreamHome) {
            RetainedStreamSessionCoordinator.clear();
            SessionResumeManager.clear(this);
            stopService(new Intent(this, BackgroundStreamService.class));
            finish();
        }
        overridePendingTransition(0, android.R.anim.fade_out);
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
        if (consoleAudioEngine != null) consoleAudioEngine.setMenuVisible(false);
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
        if (consoleAudioEngine != null) consoleAudioEngine.setMenuVisible(true);
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
        StringBuilder devicesSignature = new StringBuilder();
        for (ControllerInfo controller : controllers) {
            signature.append(controller.deviceId).append(':').append(controller.name)
                    .append(':').append(controller.percentage).append(':')
                    .append(controller.batteryStatus).append(';');
            devicesSignature.append(controller.deviceId).append(':')
                    .append(controller.name).append(';');
        }
        boolean devicesChanged = !devicesSignature.toString().equals(
                renderedControllerDevicesSignature);
        if (devicesChanged) {
            rebuildExpandedNavigationLegend(controllers);
            rebuildHostSelectionLegend(controllers);
        }
        if (signature.toString().equals(renderedControllersSignature)) return;
        renderedControllersSignature = signature.toString();
        renderedControllerDevicesSignature = devicesSignature.toString();
        int scroll = controllerScroll.getScrollX();
        Object focusedTag = getCurrentFocus() != null ? getCurrentFocus().getTag() : null;
        controllerRow.removeAllViews();
        if (CONSOLE_UI_V2) {
            controllerScroll.setVisibility(
                    controllers.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        }
        controllersLabel.setText(controllers.isEmpty()
                ? getString(R.string.console_controllers_none)
                : getResources().getQuantityString(R.plurals.console_controller_count,
                        controllers.size(), controllers.size()));
        int player = 1;
        for (ControllerInfo controller : controllers) {
            controllerRow.addView(controllerCard(player++, controller),
                    CONSOLE_UI_V2 ? controllerPillSpacing() : cardSpacing());
        }
        if (CONSOLE_UI_V2 && devicesChanged && !controllers.isEmpty()) {
            controllerRow.setAlpha(0f);
            controllerRow.setTranslationX(-dp(24));
            controllerRow.animate().alpha(1f).translationX(0f)
                    .setDuration(reducedMotion ? 0 : 240).start();
        }
        controllerScroll.post(() -> controllerScroll.scrollTo(scroll != 0 ? scroll
                : preferences.getInt("controller_scroll", 0), 0));
        restoreTaggedFocus(controllerRow, focusedTag);
        wireHomeFocusNavigation();
    }

    private View controllerCard(int player, ControllerInfo controller) {
        if (CONSOLE_UI_V2) return controllerPill(player, controller);
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

    private View controllerPill(int player, ControllerInfo controller) {
        LinearLayout pill = cardBase(dp(62), dp(26));
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(7), dp(2), dp(8), dp(2));
        pill.setTag("controller:" + controller.deviceId);
        int batteryColor = controller.percentage < 0 ? 0xFFB3B8C8
                : controller.isCharging() ? 0xFF64B5F6
                : controller.percentage <= 10 ? 0xFFFF5252
                : controller.percentage <= 30 ? 0xFFFFB74D : 0xFF69F0AE;
        ImageView controllerIcon = new ImageView(this);
        controllerIcon.setImageDrawable(new ControllerGlyphDrawable(controller.name));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(30), dp(20));
        iconParams.rightMargin = dp(5);
        pill.addView(controllerIcon, iconParams);

        String battery;
        if (controller.percentage < 0) {
            battery = getString(R.string.console_controller_battery_unknown);
        } else if (controller.isFull()) {
            battery = getString(R.string.console_controller_battery_full, controller.percentage);
        } else if (controller.isCharging()) {
            battery = getString(R.string.console_controller_battery_charging,
                    controller.percentage);
        } else {
            battery = getString(R.string.console_controller_battery_level,
                    controller.percentage);
        }
        TextView level = text(controller.percentage < 0
                        ? "?" : controller.percentage + "%",
                9, batteryColor, true);
        pill.addView(level, wrapLinear());
        controllerIcon.setContentDescription(compactControllerName(controller.name));
        pill.setContentDescription(getString(R.string.console_controller_description,
                getString(R.string.console_controller_player_name,
                        player, compactControllerName(controller.name)), battery));
        pill.setOnClickListener(view -> showControllerMenu(player, controller));
        pill.setOnFocusChangeListener((view, focused) ->
                styleControllerPill(pill, focused));
        styleControllerPill(pill, false);
        return pill;
    }

    private void showControllerMenu(int player, ControllerInfo controller) {
        List<View> actions = new ArrayList<>();
        if (ControllerActions.canIdentify(controller.deviceId)) {
            TextView identify = panelAction(getString(R.string.console_controller_identify));
            identify.setOnClickListener(view -> ControllerActions.identify(
                    controller.deviceId, mainHandler, (success, message) -> mainHandler.post(() ->
                            ConsoleUiFeedback.makeText(this, message, Toast.LENGTH_LONG).show())));
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
        consoleFeedback.showConfirm(getString(unpair
                        ? R.string.console_controller_unpair_confirm_title
                        : R.string.console_controller_power_off_confirm_title),
                getString(unpair
                        ? R.string.console_controller_unpair_confirm_details
                        : R.string.console_controller_power_off_confirm_details),
                getString(android.R.string.cancel),
                getString(unpair ? R.string.console_controller_unpair
                        : R.string.console_controller_power_off), unpair,
                () -> runBluetoothAction(controller, action));
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
                    consoleFeedback.notify(ConsoleUiFeedback.Kind.SUCCESS, message);
                    refreshControllers();
                } else {
                    consoleFeedback.showConfirm(
                            getString(R.string.console_operation_unavailable), message,
                            getString(R.string.console_close),
                            getString(R.string.console_bluetooth_settings), false, () -> {
                                try {
                                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                                } catch (RuntimeException error) {
                                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                                }
                            });
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
            consoleFeedback.notify(ConsoleUiFeedback.Kind.ERROR,
                    getString(R.string.console_bluetooth_permission_required));
        }
    }

    private void showOptionsPanel() {
        LinearLayout sounds = settingsToggle(
                getString(R.string.console_ui_sounds_label), uiSoundsEnabled);
        LinearLayout ambient = settingsToggle(
                getString(R.string.console_ambient_sounds_label), ambientSoundsEnabled);
        LinearLayout hostMusic = settingsVolume(
                getString(R.string.console_host_music_volume_label), hostMusicVolume,
                value -> {
                    hostMusicVolume = value;
                    preferences.edit().putInt("host_music_volume", value).apply();
                    if (consoleAudioEngine != null) {
                        consoleAudioEngine.setHostSelectionVolume(value / 100f);
                    }
                });
        LinearLayout menuMusic = settingsVolume(
                getString(R.string.console_menu_music_volume_label), menuMusicVolume,
                value -> {
                    menuMusicVolume = value;
                    preferences.edit().putInt("menu_music_volume", value).apply();
                    if (consoleAudioEngine != null) {
                        consoleAudioEngine.setMenuVolume(value / 100f);
                    }
                });
        LinearLayout effects = settingsVolume(
                getString(R.string.console_effects_volume_label), effectsVolume,
                value -> {
                    effectsVolume = value;
                    preferences.edit().putInt("effects_volume", value).apply();
                    if (consoleAudioEngine != null) {
                        consoleAudioEngine.setEffectsVolume(value / 100f);
                    }
                });
        LinearLayout motion = settingsToggle(
                getString(R.string.console_reduced_motion_label), reducedMotion);
        LinearLayout carouselDescription = settingsToggle(getString(
                R.string.console_carousel_game_description_label),
                showCarouselGameDescription);
        LinearLayout hiddenApps = settingsToggle(
                getString(R.string.console_hidden_apps_label), showHiddenApps);
        TextView autoLogin = panelAction(getString(R.string.console_auto_login_host,
                autoLoginHostLabel()));
        autoLogin.setTag("options.auto_login_host");
        TextView backgroundStream = panelAction(getString(
                R.string.console_background_stream_retention,
                backgroundStreamRetentionLabel()));
        backgroundStream.setTag("options.background_stream_retention");
        TextView settings = panelAction(getString(R.string.console_streaming_settings));
        TextView overrides = panelAction(getString(R.string.console_options_overrides));
        TextView integrations = panelAction(getString(R.string.console_host_integrations));
        ComputerDetails selectedHost = hosts.get(selectedHostUuid);
        integrations.setEnabled(selectedHost != null);
        integrations.setAlpha(selectedHost != null ? 1f : .45f);
        TextView readme = panelAction(getString(R.string.console_options_readme));
        sounds.setOnClickListener(v -> {
            uiSoundsEnabled = !uiSoundsEnabled;
            preferences.edit().putBoolean("ui_sounds", uiSoundsEnabled).apply();
            if (consoleAudioEngine != null) {
                consoleAudioEngine.setEffectsEnabled(uiSoundsEnabled);
            }
            updateSettingsToggle(sounds, uiSoundsEnabled);
        });
        ambient.setOnClickListener(v -> {
            ambientSoundsEnabled = !ambientSoundsEnabled;
            preferences.edit().putBoolean("ambient_sounds", ambientSoundsEnabled).apply();
            if (consoleAudioEngine != null) {
                consoleAudioEngine.setAmbientEnabled(ambientSoundsEnabled);
            }
            updateSettingsToggle(ambient, ambientSoundsEnabled);
        });
        motion.setOnClickListener(v -> {
            reducedMotion = !reducedMotion;
            preferences.edit().putBoolean("reduced_motion", reducedMotion).apply();
            if (consoleFeedback != null) consoleFeedback.setReducedMotion(reducedMotion);
            if (libraryTransitionCoordinator != null) {
                libraryTransitionCoordinator.setReducedMotion(reducedMotion);
            }
            updateSettingsToggle(motion, reducedMotion);
        });
        carouselDescription.setOnClickListener(v -> {
            showCarouselGameDescription = !showCarouselGameDescription;
            preferences.edit().putBoolean("show_carousel_game_description",
                    showCarouselGameDescription).apply();
            applyCarouselDescriptionVisibility();
            updateSettingsToggle(carouselDescription, showCarouselGameDescription);
        });
        autoLogin.setOnClickListener(v -> showAutoLoginHostPanel());
        backgroundStream.setOnClickListener(v -> showBackgroundStreamRetentionPanel());
        hiddenApps.setOnClickListener(v -> {
            toggleHiddenApps();
            updateSettingsToggle(hiddenApps, showHiddenApps);
        });
        settings.setOnClickListener(v -> {
            hideSidePanel();
            startActivity(new Intent(this, StreamSettings.class));
        });
        overrides.setOnClickListener(v -> showOverridesPanel());
        integrations.setOnClickListener(v -> {
            ComputerDetails host = hosts.get(selectedHostUuid);
            if (host == null) return;
            String address = host.activeAddress != null ? host.activeAddress.address : null;
            discordPanelController.showHostIntegrations(host.uuid, address, host.name);
        });
        readme.setOnClickListener(v -> {
            hideSidePanel();
            HelpLauncher.launchSetupGuide(this);
        });
        showSidePanel(getString(R.string.console_title), getString(R.string.console_options_title),
                getString(R.string.console_options_details),
                settingsSectionHeader(R.string.console_options_section_interface),
                sounds, ambient, hostMusic, menuMusic, effects,
                motion, carouselDescription, hiddenApps,
                settingsSectionHeader(R.string.console_options_section_host),
                autoLogin, integrations,
                settingsSectionHeader(R.string.console_options_section_streaming),
                backgroundStream, settings, overrides,
                settingsSectionHeader(R.string.console_options_section_help), readme);
    }

    private String autoLoginHostLabel() {
        ComputerDetails host = autoLoginHostUuid == null || autoLoginHostUuid.isEmpty()
                ? null : hosts.get(autoLoginHostUuid);
        return host == null ? getString(R.string.console_auto_login_none) : host.name;
    }

    private String backgroundStreamRetentionLabel() {
        if (backgroundStreamRetentionMinutes == BackgroundStreamPreferences.NEVER) {
            return getString(R.string.console_background_stream_never);
        }
        if (backgroundStreamRetentionMinutes <= 0) {
            return getString(R.string.console_background_stream_off);
        }
        return getString(R.string.console_background_stream_minutes,
                backgroundStreamRetentionMinutes);
    }

    private void showBackgroundStreamRetentionPanel() {
        int[] values = { 0, 5, 10, 30, 60, BackgroundStreamPreferences.NEVER };
        List<View> actions = new ArrayList<>();
        for (int value : values) {
            String label = value == BackgroundStreamPreferences.NEVER
                    ? getString(R.string.console_background_stream_never)
                    : value == 0
                    ? getString(R.string.console_background_stream_off)
                    : getString(R.string.console_background_stream_minutes, value);
            if (value == backgroundStreamRetentionMinutes) {
                label += getString(R.string.console_selected_suffix);
            }
            TextView choice = panelAction(label);
            choice.setTag("background_stream_retention:" + value);
            choice.setOnClickListener(view -> setBackgroundStreamRetention(value));
            actions.add(choice);
        }
        showSidePanel(getString(R.string.console_title),
                getString(R.string.console_background_stream_retention_title),
                getString(R.string.console_background_stream_retention_details),
                actions.toArray(new View[0]));
    }

    private void setBackgroundStreamRetention(int minutes) {
        backgroundStreamRetentionMinutes = minutes;
        preferences.edit().putInt(BackgroundStreamPreferences.KEY_RETENTION_MINUTES,
                minutes).apply();
        showOptionsPanel();
    }

    private void showAutoLoginHostPanel() {
        List<View> actions = new ArrayList<>();
        TextView none = panelAction(getString(R.string.console_auto_login_none));
        none.setTag("auto_login:");
        none.setOnClickListener(view -> setAutoLoginHost(""));
        actions.add(none);
        List<ComputerDetails> sorted = new ArrayList<>(hosts.values());
        sorted.sort(Comparator.comparing(host -> host.name, String.CASE_INSENSITIVE_ORDER));
        for (ComputerDetails host : sorted) {
            String suffix = host.uuid.equals(autoLoginHostUuid)
                    ? getString(R.string.console_selected_suffix) : "";
            TextView choice = panelAction(host.name + suffix);
            choice.setTag("auto_login:" + host.uuid);
            choice.setOnClickListener(view -> setAutoLoginHost(host.uuid));
            actions.add(choice);
        }
        showSidePanel(getString(R.string.console_title),
                getString(R.string.console_auto_login_title),
                getString(R.string.console_auto_login_details),
                actions.toArray(new View[0]));
    }

    private void setAutoLoginHost(String uuid) {
        autoLoginHostUuid = uuid == null ? "" : uuid;
        SharedPreferences.Editor editor = preferences.edit();
        if (autoLoginHostUuid.isEmpty()) editor.remove("auto_login_host");
        else editor.putString("auto_login_host", autoLoginHostUuid);
        editor.apply();
        handlePanelBack();
        mainHandler.post(() -> {
            View action = sidePanel != null
                    ? sidePanel.findViewWithTag("options.auto_login_host") : null;
            if (action instanceof TextView) {
                ((TextView) action).setText(getString(
                        R.string.console_auto_login_host, autoLoginHostLabel()));
            }
        });
    }

    private void applyCarouselDescriptionVisibility() {
        if (selectedGameDescription != null) {
            selectedGameDescription.setVisibility(
                    showCarouselGameDescription ? View.VISIBLE : View.GONE);
        }
        if (selectedGameMetadata != null) {
            ViewGroup.LayoutParams params = selectedGameMetadata.getLayoutParams();
            if (params != null) {
                params.height = dp(showCarouselGameDescription ? 108 : 62);
                selectedGameMetadata.setLayoutParams(params);
            }
        }
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

    private void showDiscordPanel() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) {
            ConsoleUiFeedback.makeText(this, R.string.console_select_host_first, Toast.LENGTH_LONG).show();
            return;
        }
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        discordPanelController.openDiscord(host.uuid, address, host.name);
    }

    private void showSidePanel(String eyebrow, String title, String details, View... actions) {
        showSidePanelInternal(eyebrow, title, details, false, actions);
    }

    private void showRetainedStreamExitConfirmation() {
        TextView leave = panelAction(getString(R.string.console_leave_keep_stream));
        TextView returnToGame = panelAction(getString(R.string.console_return_to_game));
        TextView close = panelAction(getString(R.string.console_close_game_and_moonwaker));
        close.setTextColor(0xFFFF8A80);
        leave.setOnClickListener(view -> {
            hideSidePanel();
            leaveMoonWakerKeepingStream();
        });
        returnToGame.setOnClickListener(view -> {
            hideSidePanel();
            returnToRetainedStream();
        });
        close.setOnClickListener(view -> {
            hideSidePanel();
            terminateRetainedSessionAndExit();
        });
        showSidePanel(getString(R.string.console_title),
                getString(R.string.console_leave_with_stream_title),
                getString(R.string.console_leave_with_stream_details),
                leave, returnToGame, close);
    }

    private void leaveMoonWakerKeepingStream() {
        RetainedStreamSessionCoordinator.parkForBackground();
        moveTaskToBack(true);
    }

    private void terminateRetainedSessionAndExit() {
        Runnable complete = () -> mainHandler.post(() -> {
            RetainedStreamSessionCoordinator.clear();
            SessionResumeManager.clear(this);
            stopService(new Intent(this, BackgroundStreamService.class));
            finishAffinity();
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        });
        RetainedStreamSessionCoordinator.TerminationResult result =
                RetainedStreamSessionCoordinator.terminate(complete);
        if (result == RetainedStreamSessionCoordinator.TerminationResult.STARTED) {
            showSidePanelBusy(getString(R.string.console_title),
                    getString(R.string.console_close_game_and_moonwaker),
                    getString(R.string.console_closing_game_and_moonwaker));
            return;
        }
        if (result == RetainedStreamSessionCoordinator.TerminationResult.IN_PROGRESS) return;

        ComputerDetails host = hosts.get(!retainedStreamHostId.isEmpty()
                ? retainedStreamHostId : selectedHostUuid);
        if (host == null || managerBinder == null) {
            RetainedStreamSessionCoordinator.clear();
            complete.run();
            return;
        }

        showSidePanelBusy(getString(R.string.console_title),
                getString(R.string.console_close_game_and_moonwaker),
                getString(R.string.console_closing_game_and_moonwaker));
        executor.execute(() -> {
            try {
                NvHTTP connection = new NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(host), host.httpsPort,
                        managerBinder.getUniqueId(), host.serverCert,
                        PlatformBinding.getCryptoProvider(this));
                connection.quitApp();
            } catch (IOException | XmlPullParserException ignored) { }
            mainHandler.post(() -> {
                RetainedStreamSessionCoordinator.clear();
                complete.run();
            });
        });
    }

    private void showScrollableDetailsSidePanel(String eyebrow, String title, String details,
                                                View... actions) {
        showSidePanelInternal(eyebrow, title, details, true, actions);
    }

    private void showSidePanelInternal(String eyebrow, String title, String details,
                                       boolean scrollableDetails, View... actions) {
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
            if (hostSelectionLayer != null) {
                hostSelectionLayer.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                hostSelectionLayer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
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
        if (scrollableDetails) {
            detailView.setId(View.generateViewId());
            detailView.setTag("panel.scrollable.details");
            detailView.setFocusable(true);
            detailView.setPadding(dp(6), dp(4), dp(6), dp(4));
            detailView.setOnFocusChangeListener((view, focused) ->
                    detailView.setTextColor(focused ? Color.WHITE : 0xFFC1C5D6));
            detailView.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int direction;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) direction = 1;
                else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) direction = -1;
                else return false;
                if (!sidePanelScroll.canScrollVertically(direction)) return false;
                sidePanelScroll.smoothScrollBy(0, direction * dp(124));
                return true;
            });
        }
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
        if (!samePanel) {
            sidePanelScroll.post(() -> sidePanelScroll.scrollTo(0, 0));
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
            sidePanelScroll.setAlpha(1f);
            sidePanelScroll.setScaleX(1f);
            sidePanelScroll.setScaleY(1f);
            panelHistory.clear();
            currentPanelKey = null;
            sidePanelBusyBanner = null;
            sidePanelTransient = false;
            homeLayer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            homeLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (hostSelectionLayer != null) {
                hostSelectionLayer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                hostSelectionLayer.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
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
                focused != null ? focused.getTag() : null, sidePanelScroll.getScrollY());
    }

    private void handlePanelBack() {
        if (!panelHistory.isEmpty()) {
            PanelSnapshot snapshot = panelHistory.pop();
            sidePanel.removeAllViews();
            for (View child : snapshot.children) sidePanel.addView(child);
            currentPanelKey = snapshot.key;
            if (!reducedMotion) {
                sidePanelScroll.animate().cancel();
                sidePanelScroll.setAlpha(.55f);
                sidePanelScroll.setScaleX(.97f);
                sidePanelScroll.setScaleY(.97f);
                sidePanelScroll.setTranslationX(dp(18));
                sidePanelScroll.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .translationX(0f).setDuration(170L).start();
            }
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
            mainHandler.postDelayed(() -> {
                if (sideDialog != null && sideDialog.isShowing()) {
                    sidePanelScroll.scrollTo(0, snapshot.scrollY);
                }
            }, 48);
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
            ViewGroup activeLayer = hostSelectionVisible && hostSelectionLayer != null
                    ? hostSelectionLayer : homeLayer;
            View tagged = activeLayer.findViewWithTag(lastContentFocusTag);
            if (tagged != null && tagged.isShown() && tagged.isFocusable()) target = tagged;
        }
        if (target == null && hostSelectionVisible) {
            requestHostSelectionFocus();
            return;
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
        action.setOnFocusChangeListener((view, focused) -> {
            styleCompactButton(action, focused);
            if (focused) revealSidePanelFocus(action);
        });
        styleCompactButton(action, false);
        return action;
    }

    private TextView settingsSectionHeader(int label) {
        TextView header = text(getString(label).toUpperCase(Locale.ROOT),
                10, 0xFF83CAE9, true);
        header.setPadding(dp(5), dp(8), dp(5), dp(2));
        header.setFocusable(false);
        return header;
    }

    private LinearLayout settingsToggle(String label, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setId(View.generateViewId());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setSoundEffectsEnabled(false);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(16), dp(6), dp(12), dp(6));
        row.setTag("settings.toggle:" + label);
        TextView title = text(label, 14, 0xFFF0E9FF, true);
        title.setTag("settings.toggle.label");
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = text("", 9, Color.WHITE, true);
        state.setTag("settings.toggle.state");
        state.setGravity(Gravity.CENTER);
        row.addView(state, new LinearLayout.LayoutParams(dp(48), dp(26)));
        row.setOnFocusChangeListener((view, focused) -> {
            styleSettingsToggle(row, focused);
            if (focused) revealSidePanelFocus(row);
        });
        styleSettingsToggle(row, false);
        updateSettingsToggle(row, checked);
        return row;
    }

    private LinearLayout settingsVolume(String label, int initialValue,
            IntConsumer onChanged) {
        LinearLayout row = new LinearLayout(this);
        row.setId(View.generateViewId());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setSoundEffectsEnabled(false);
        row.setMinimumHeight(dp(62));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(label, 13, 0xFFF0E9FF, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text("", 11, 0xFF8DDCFF, true);
        value.setGravity(Gravity.END);
        heading.addView(value, new LinearLayout.LayoutParams(
                dp(54), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.console_accent)));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                0x504B5963));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        progressParams.topMargin = dp(6);
        row.addView(progress, progressParams);

        VolumeControl control = new VolumeControl(progress, value, onChanged);
        row.setTag(control);
        row.setOnKeyListener((view, keyCode, event) ->
                handleVolumeAdjustment(event, view));
        row.setOnGenericMotionListener((view, event) ->
                handleVolumeMotion(control, event));
        row.setOnFocusChangeListener((view, focused) -> {
            styleSettingsToggle(row, focused);
            if (focused) revealSidePanelFocus(row);
        });
        row.setOnClickListener(view -> updateVolumeControl(control,
                control.progress.getProgress() >= 100 ? 0
                        : control.progress.getProgress() + 10, true));
        styleSettingsToggle(row, false);
        updateVolumeControl(control, initialValue, false);
        return row;
    }

    private void updateVolumeControl(VolumeControl control, int value, boolean notify) {
        int safeValue = Math.max(0, Math.min(100, value));
        control.progress.setProgress(safeValue);
        control.value.setText(safeValue + "%");
        if (notify) control.onChanged.accept(safeValue);
    }

    private static final class VolumeControl {
        final ProgressBar progress;
        final TextView value;
        final IntConsumer onChanged;

        VolumeControl(ProgressBar progress, TextView value, IntConsumer onChanged) {
            this.progress = progress;
            this.value = value;
            this.onChanged = onChanged;
        }
    }

    private void updateSettingsToggle(LinearLayout row, boolean checked) {
        if (row == null) return;
        TextView title = (TextView) findTaggedChild(row, "settings.toggle.label");
        TextView state = (TextView) findTaggedChild(row, "settings.toggle.state");
        if (state == null) return;
        String value = getString(checked ? R.string.console_on : R.string.console_off);
        state.setText(value);
        GradientDrawable track = gradient(
                checked ? 0xFF2383A5 : 0xFF343B42,
                checked ? 0xFF176782 : 0xFF252B30, 13);
        track.setStroke(dp(1), checked ? 0xFF73D7FF : 0xFF58626B);
        state.setBackground(track);
        if (title != null) row.setContentDescription(title.getText() + ". " + value);
    }

    private void styleSettingsToggle(View row, boolean focused) {
        GradientDrawable background = gradient(
                focused ? 0xFF24343F : 0x26242B32,
                focused ? 0xFF18242C : 0x4813181D, 10);
        background.setStroke(dp(focused ? 2 : 1), focused
                ? getResources().getColor(R.color.console_accent) : 0x384C5962);
        row.setBackground(background);
        row.setElevation(dp(focused ? 4 : 0));
    }

    private void revealSidePanelFocus(View view) {
        if (sidePanelScroll == null || view == null) return;
        view.post(() -> {
            if (sideDialog == null || !sideDialog.isShowing()
                    || !view.isAttachedToWindow()) return;
            Rect bounds = new Rect();
            view.getDrawingRect(bounds);
            sidePanelScroll.offsetDescendantRectToMyCoords(view, bounds);
            int margin = dp(16);
            int viewportTop = sidePanelScroll.getScrollY() + margin;
            int viewportBottom = sidePanelScroll.getScrollY()
                    + sidePanelScroll.getHeight() - margin;
            int target = sidePanelScroll.getScrollY();
            if (bounds.bottom > viewportBottom) {
                target += bounds.bottom - viewportBottom;
            } else if (bounds.top < viewportTop) {
                target -= viewportTop - bounds.top;
            }
            target = Math.max(0, target);
            if (target != sidePanelScroll.getScrollY()) {
                if (reducedMotion) sidePanelScroll.scrollTo(0, target);
                else sidePanelScroll.smoothScrollTo(0, target);
            }
        });
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
        card.setSoundEffectsEnabled(false);
        card.setMinimumWidth(width);
        card.setMinimumHeight(height);
        styleCard(card, false);
        return card;
    }

    private void styleCard(View card, boolean focused) {
        Object tag = card.getTag();
        boolean carouselCard = CONSOLE_UI_V2 && tag instanceof String
                && (((String) tag).startsWith("app:")
                || ((String) tag).startsWith("playnite:"));
        if (carouselCard) {
            GradientDrawable background = gradient(
                    focused ? 0xF019222B : 0xC012171D,
                    focused ? 0xF00C1116 : 0xD00A0E12, 12);
            background.setStroke(dp(focused ? 2 : 1),
                    focused ? 0xFFEAF8FF : 0x305F6D76);
            card.setBackground(background);
            card.setElevation(dp(focused ? 12 : 2));
            card.setPivotX(card.getWidth() > 0 ? card.getWidth() / 2f : dp(42));
            card.setPivotY(card.getHeight() > 0 ? card.getHeight() / 2f : dp(75));
            float scale = focused ? 1.04f : 1f;
            float lift = focused ? -dp(2) : 0f;
            card.animate().cancel();
            if (reducedMotion || !card.isLaidOut()) {
                card.setScaleX(scale);
                card.setScaleY(scale);
                card.setTranslationY(lift);
            } else {
                card.animate().scaleX(scale).scaleY(scale).translationY(lift)
                        .setDuration(getResources().getInteger(
                                R.integer.console_motion_focus_ms)).start();
            }
            return;
        }
        int top = focused ? 0xFF202A32 : 0xE01B2026;
        int bottom = focused ? 0xFF151C22 : 0xF012161B;
        GradientDrawable background = gradient(top, bottom, 14);
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFF8DDCFF : 0x425F6D76);
        card.setBackground(background);
        card.setElevation(dp(focused ? 7 : 2));
        animateScale(card, focused ? 1.04f : 1f);
    }

    private void styleControllerPill(View pill, boolean focused) {
        GradientDrawable background = gradient(
                focused ? 0xF02A3A46 : 0xB51A222A,
                focused ? 0xF018242D : 0xC010151A, 18);
        background.setStroke(dp(focused ? 2 : 1),
                focused ? 0xFFBDEBFF : 0x465F7482);
        pill.setBackground(background);
        pill.setElevation(dp(focused ? 5 : 1));
        animateScale(pill, focused ? 1.04f : 1f);
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

    private void styleHostSelector(boolean focused) {
        if (hostSelector == null) return;
        GradientDrawable background = gradient(
                focused ? 0xD9233038 : 0x00000000,
                focused ? 0xD9182229 : 0x00000000, 10);
        if (focused) background.setStroke(dp(1), 0xB873D7FF);
        hostSelector.setBackground(background);
        hostSelector.setElevation(dp(focused ? 2 : 0));
        animateScale(hostSelector, focused ? 1.03f : 1f);
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
            libraryViewStateStore.saveExpandedMode(selectedHostUuid, expandedLibraryMode);
        }
        editor.apply();
    }

    private void restoreHostLibraryState(String hostUuid) {
        if (hostUuid == null) return;
        ConsoleLibraryViewStateStore.State state = libraryViewStateStore.load(
                hostUuid, preferences.getString("selected_playnite." + hostUuid, ""));
        lastCarouselGameId = state.carouselGameId;
        expandedSearchQuery = state.searchQuery;
        pendingExpandedLibraryRestore = CONSOLE_UI_V2 && state.expandedMode;
    }

    private void wireHomeFocusNavigation() {
        if (optionsButton == null) return;
        View resume = quickResumeButton != null
                && quickResumeButton.getVisibility() == View.VISIBLE
                && quickResumeButton.isEnabled() ? quickResumeButton : null;
        View playnite = launchPlayniteButton != null
                && launchPlayniteButton.getVisibility() == View.VISIBLE
                && launchPlayniteButton.isEnabled() ? launchPlayniteButton : null;
        View desktop = launchDesktopButton != null
                && launchDesktopButton.getVisibility() == View.VISIBLE
                && launchDesktopButton.isEnabled() ? launchDesktopButton : null;
        View filter = installedFilterButton != null
                && installedFilterButton.getVisibility() == View.VISIBLE
                && installedFilterButton.isEnabled() ? installedFilterButton : null;
        View quick = firstFocusableChild(quickActions);
        View controller = firstFocusableChild(controllerRow);
        View app = firstFocusableChild(appRow);

        if (CONSOLE_UI_V2) {
            wireDebugHomeFocusNavigation(resume, playnite, desktop, filter,
                    quick, controller, app);
            return;
        }

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
        view.setSoundEffectsEnabled(false);
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
        else params.rightMargin = dp(CONSOLE_UI_V2 ? 10 : 14);
        return params;
    }

    private LinearLayout.LayoutParams controllerPillSpacing() {
        LinearLayout.LayoutParams params = wrapLinear();
        params.rightMargin = dp(8);
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

    private static boolean isDirectionalNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
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

    private static final class ControllerGlyphDrawable extends Drawable {
        private enum Kind { DUALSENSE, XBOX, STEAM, GENERIC }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path body = new Path();
        private final Kind kind;

        ControllerGlyphDrawable(String name) {
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (normalized.contains("dualsense") || normalized.contains("dualshock")
                    || normalized.contains("wireless controller")) {
                kind = Kind.DUALSENSE;
            } else if (normalized.contains("steam")) {
                kind = Kind.STEAM;
            } else if (normalized.contains("xbox") || normalized.contains("xinput")) {
                kind = Kind.XBOX;
            } else {
                kind = Kind.GENERIC;
            }
        }

        @Override public void draw(Canvas canvas) {
            float scale = Math.min(getBounds().width() / 34f, getBounds().height() / 24f);
            float x = getBounds().left + (getBounds().width() - 34f * scale) / 2f;
            float y = getBounds().top + (getBounds().height() - 24f * scale) / 2f;
            canvas.save();
            canvas.translate(x, y);
            canvas.scale(scale, scale);
            body.reset();
            body.moveTo(3f, 10f);
            body.cubicTo(4f, 5f, 8f, 3f, 12f, 5f);
            body.cubicTo(15f, 6.5f, 19f, 6.5f, 22f, 5f);
            body.cubicTo(26f, 3f, 30f, 5f, 31f, 10f);
            body.lineTo(33f, 18f);
            body.cubicTo(34f, 22f, 29f, 24f, 26f, 21f);
            body.lineTo(22f, 17f);
            body.lineTo(12f, 17f);
            body.lineTo(8f, 21f);
            body.cubicTo(5f, 24f, 0f, 22f, 1f, 18f);
            body.close();

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(kind == Kind.DUALSENSE ? 0xFFE7EDF2 : 0xFF53606B);
            canvas.drawPath(body, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f);
            paint.setColor(0xFFBDEBFF);
            canvas.drawPath(body, paint);

            if (kind == Kind.DUALSENSE) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF202A33);
                canvas.drawRoundRect(new RectF(13f, 6f, 21f, 11f), 1.5f, 1.5f, paint);
                canvas.drawCircle(13f, 14f, 1.5f, paint);
                canvas.drawCircle(21f, 14f, 1.5f, paint);
            } else if (kind == Kind.STEAM) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.4f);
                paint.setColor(0xFFDDE8EF);
                canvas.drawCircle(10f, 11f, 3.2f, paint);
                canvas.drawCircle(24f, 11f, 3.2f, paint);
                canvas.drawCircle(17f, 15f, 1.4f, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.4f);
                paint.setColor(0xFFDDE8EF);
                canvas.drawLine(8f, 11f, 14f, 11f, paint);
                canvas.drawLine(11f, 8f, 11f, 14f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(24f, 9f, 1.3f, paint);
                canvas.drawCircle(27f, 12f, 1.3f, paint);
                canvas.drawCircle(kind == Kind.XBOX ? 14f : 15f, 15f, 1.4f, paint);
                canvas.drawCircle(kind == Kind.XBOX ? 20f : 19f, 14f, 1.4f, paint);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 34; }
        @Override public int getIntrinsicHeight() { return 24; }
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
        final int scrollY;

        PanelSnapshot(String key, List<View> children, View focused, Object focusedTag,
                      int scrollY) {
            this.key = key;
            this.children = children;
            this.focused = focused;
            this.focusedTag = focusedTag;
            this.scrollY = scrollY;
        }
    }

    private static final class HostSelectionTile {
        final View root;
        final View avatar;
        final View dot;
        final TextView status;
        final TextView options;

        HostSelectionTile(View root, View avatar, View dot,
                          TextView status, TextView options) {
            this.root = root;
            this.avatar = avatar;
            this.dot = dot;
            this.status = status;
            this.options = options;
        }
    }

    private static final class HostAvatarDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int seed;

        HostAvatarDrawable(String value) {
            seed = value == null ? 0 : value.hashCode();
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY();
            float radius = Math.min(bounds.width(), bounds.height()) / 2f;
            Path clip = new Path();
            clip.addCircle(cx, cy, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);

            float hue = Math.abs(seed % 360);
            paint.setColor(Color.HSVToColor(new float[]{hue, .58f, .62f}));
            canvas.drawCircle(cx, cy, radius, paint);
            for (int index = 0; index < 6; index++) {
                int shifted = seed >> (index * 3);
                float x = bounds.left + bounds.width() * (.12f + .17f * (index % 4));
                float y = bounds.top + bounds.height() * (.12f + .21f * ((shifted & 3)));
                float width = radius * (.75f + .12f * (index % 3));
                float height = radius * (.35f + .1f * ((index + 1) % 3));
                float blobHue = (hue + 48f + index * 43f) % 360f;
                paint.setColor(Color.HSVToColor(185,
                        new float[]{blobHue, .62f, .94f}));
                canvas.save();
                canvas.rotate((shifted & 63) - 31f, x, y);
                canvas.drawOval(new RectF(x - width, y - height,
                        x + width, y + height), paint);
                canvas.restore();
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, radius * .08f));
            paint.setColor(0x55FFFFFF);
            Path wave = new Path();
            wave.moveTo(bounds.left - radius * .2f, cy + radius * .35f);
            wave.cubicTo(cx - radius * .6f, cy - radius * .7f,
                    cx + radius * .25f, cy + radius * .8f,
                    bounds.right + radius * .2f, cy - radius * .2f);
            canvas.drawPath(wave, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class HostSelectionBackdropDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawColor(0xFF070A10);
            paint.setColor(0x342C4868);
            canvas.drawOval(new RectF(bounds.left - bounds.width() * .1f,
                    bounds.top - bounds.height() * .2f,
                    bounds.left + bounds.width() * .58f,
                    bounds.top + bounds.height() * 1.15f), paint);
            paint.setColor(0x263E284B);
            canvas.drawOval(new RectF(bounds.left + bounds.width() * .44f,
                    bounds.top - bounds.height() * .08f,
                    bounds.right + bounds.width() * .12f,
                    bounds.bottom + bounds.height() * .18f), paint);
            paint.setColor(0x1F9B6334);
            for (int index = 0; index < 18; index++) {
                float x = bounds.left + bounds.width() * ((index * 37 % 100) / 100f);
                float y = bounds.top + bounds.height() * (.28f
                        + ((index * 19 % 52) / 100f));
                float radius = Math.max(2f, bounds.width() * (.0015f + (index % 3) * .0008f));
                canvas.drawCircle(x, y, radius, paint);
            }
            paint.setColor(0x62000000);
            canvas.drawRect(bounds.left, bounds.top, bounds.right,
                    bounds.top + bounds.height() * .08f, paint);
            canvas.drawRect(bounds.left, bounds.bottom - bounds.height() * .08f,
                    bounds.right, bounds.bottom, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    private void wireDebugHomeFocusNavigation(View resume, View playnite, View desktop,
                                               View filter, View quick, View controller,
                                               View app) {
        View belowHeader = app != null ? app
                : playnite != null ? playnite : filter != null ? filter : controller;
        View lastQuick = null;
        if (quickActions != null) {
            for (int index = quickActions.getChildCount() - 1; index >= 0; index--) {
                View child = quickActions.getChildAt(index);
                if (child.isFocusable()) {
                    lastQuick = child;
                    break;
                }
            }
        }
        if (belowHeader != null) optionsButton.setNextFocusDownId(belowHeader.getId());
        optionsButton.setNextFocusLeftId(optionsButton.getId());
        optionsButton.setNextFocusRightId(quick != null ? quick.getId() : optionsButton.getId());
        optionsButton.setNextFocusUpId(optionsButton.getId());

        if (playnite != null) {
            if (app != null) playnite.setNextFocusUpId(app.getId());
            if (resume != null) playnite.setNextFocusLeftId(resume.getId());
            else if (filter != null) playnite.setNextFocusLeftId(filter.getId());
            if (desktop != null) playnite.setNextFocusRightId(desktop.getId());
            if (controller != null) playnite.setNextFocusDownId(controller.getId());
        }
        if (resume != null) {
            if (app != null) resume.setNextFocusUpId(app.getId());
            if (filter != null) resume.setNextFocusLeftId(filter.getId());
            if (playnite != null) resume.setNextFocusRightId(playnite.getId());
            else if (desktop != null) resume.setNextFocusRightId(desktop.getId());
            if (controller != null) resume.setNextFocusDownId(controller.getId());
        }
        if (desktop != null) {
            if (app != null) desktop.setNextFocusUpId(app.getId());
            if (playnite != null) desktop.setNextFocusLeftId(playnite.getId());
            else if (resume != null) desktop.setNextFocusLeftId(resume.getId());
            desktop.setNextFocusRightId(desktop.getId());
            if (controller != null) desktop.setNextFocusDownId(controller.getId());
        }
        if (filter != null) {
            if (app != null) filter.setNextFocusUpId(app.getId());
            if (resume != null) filter.setNextFocusRightId(resume.getId());
            else if (playnite != null) filter.setNextFocusRightId(playnite.getId());
            else if (desktop != null) filter.setNextFocusRightId(desktop.getId());
            if (controller != null) filter.setNextFocusDownId(controller.getId());
        }
        if (controller != null) {
            View aboveController = filter != null ? filter
                    : resume != null ? resume : playnite != null ? playnite
                    : desktop != null ? desktop : app;
            if (aboveController != null) controller.setNextFocusUpId(aboveController.getId());
        }
        if (quickActions != null) {
            for (int index = 0; index < quickActions.getChildCount(); index++) {
                View child = quickActions.getChildAt(index);
                if (!child.isFocusable()) continue;
                if (belowHeader != null) {
                    child.setNextFocusDownId(belowHeader.getId());
                }
                if (index == 0) {
                    child.setNextFocusLeftId(optionsButton.getId());
                }
            }
        }
        View belowApps = filter != null ? filter : resume != null ? resume
                : playnite != null ? playnite : desktop != null ? desktop : controller;
        if (appRow != null) {
            List<View> focusableApps = new ArrayList<>();
            for (int index = 0; index < appRow.getChildCount(); index++) {
                View child = appRow.getChildAt(index);
                if (!child.isFocusable()) continue;
                focusableApps.add(child);
                child.setNextFocusUpId(quick != null ? quick.getId() : optionsButton.getId());
                if (belowApps != null) child.setNextFocusDownId(belowApps.getId());
            }
            for (int index = 0; index < focusableApps.size(); index++) {
                View child = focusableApps.get(index);
                child.setNextFocusRightId(index + 1 < focusableApps.size()
                        ? focusableApps.get(index + 1).getId() : child.getId());
            }
        }
    }

    private enum BluetoothAction { POWER_OFF, UNPAIR }
}
