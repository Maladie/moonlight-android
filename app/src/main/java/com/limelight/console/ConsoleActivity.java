package com.limelight.console;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.Manifest;
import android.app.Activity;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ComponentCallbacks2;
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
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.hardware.BatteryState;
import android.hardware.input.InputManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.RelativeSizeSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.Display;
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
import com.limelight.LimeLog;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.UsbMicrophoneService;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.gateway.GatewayConnection;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.AppPreferences;
import com.limelight.preferences.AppStreamSettings;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
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
import com.limelight.ui.LoadingArtworkPolicy;
import com.limelight.ui.ArtworkImageView;
import com.limelight.ui.ControllerGlyphs;
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
import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** TV-first dashboard adapted from Wake & Play and backed by Moonlight's internal APIs. */
public class ConsoleActivity extends Activity implements InputManager.InputDeviceListener {
    private static final long ACTIVE_GAME_OBSERVATION_TTL_MS = 15_000L;
    private static final int WARM_UP_NONE = 0;
    private static final int WARM_UP_WAKING = 1;
    private static final int WARM_UP_PREPARING = 2;
    private static final int WARM_UP_READY = 3;
    private static final int WARM_UP_ERROR = 4;
    private static final String STATE_WARM_UP_RELAY_DISPATCHED =
            "warmUpRelayDispatched";
    public static final String EXTRA_RETAINED_STREAM_HOME =
            "com.limelight.console.RETAINED_STREAM_HOME";
    public static final String EXTRA_RETAINED_STREAM_SESSION_ID =
            "com.limelight.console.RETAINED_STREAM_SESSION_ID";
    public static final String EXTRA_RETAINED_STREAM_HOST_ID =
            "com.limelight.console.RETAINED_STREAM_HOST_ID";
    public static final String EXTRA_RETAINED_STREAM_PROFILE_ID =
            "com.limelight.console.RETAINED_STREAM_PROFILE_ID";
    public static final String EXTRA_RETAINED_STREAM_APP_ID =
            "com.limelight.console.RETAINED_STREAM_APP_ID";
    public static final String EXTRA_RETAINED_STREAM_PLAYNITE_GAME_ID =
            "com.limelight.console.RETAINED_STREAM_PLAYNITE_GAME_ID";
    public static final String EXTRA_WARM_UP_ATTEMPT =
            "com.limelight.console.WARM_UP_ATTEMPT";
    public static final String EXTRA_WARM_UP_TRANSITION_ID =
            "com.limelight.console.WARM_UP_TRANSITION_ID";
    public static final String EXTRA_WARM_UP_PENDING_GAME_ID =
            "com.limelight.console.WARM_UP_PENDING_GAME_ID";
    public static final String EXTRA_WARM_UP_PENDING_GAME_NAME =
            "com.limelight.console.WARM_UP_PENDING_GAME_NAME";
    public static final String EXTRA_WARM_UP_PENDING_ARTWORK_ID =
            "com.limelight.console.WARM_UP_PENDING_ARTWORK_ID";
    public static final String EXTRA_WARM_UP_PENDING_QUICK_LAUNCH =
            "com.limelight.console.WARM_UP_PENDING_QUICK_LAUNCH";
    public static final String EXTRA_WARM_UP_PENDING_REQUIRES_CONNECTOR =
            "com.limelight.console.WARM_UP_PENDING_REQUIRES_CONNECTOR";
    public static final String EXTRA_WARM_UP_PENDING_NEUTRAL_STREAM =
            "com.limelight.console.WARM_UP_PENDING_NEUTRAL_STREAM";
    public static final String EXTRA_WARM_UP_PENDING_START_BEFORE_STREAM =
            "com.limelight.console.WARM_UP_PENDING_START_BEFORE_STREAM";
    // The console UI is a product feature and must be identical in debug and release builds.
    private static final boolean CONSOLE_UI_V2 = true;
    private static final String PREFS = "console_dashboard";
    private static final int REQUEST_BLUETOOTH_CONNECT = 2201;
    private static final int REQUEST_COMMUNITY_DICTATION = 2202;
    private static final long CONTROLLER_REFRESH_MS = 30_000L;
    private static final long DUPLICATE_NAVIGATION_WINDOW_MS = 70L;
    private static final long PLAYNITE_REFRESH_MS = 60_000L;
    private static final long PLAYNITE_INSTALL_REFRESH_MS = 3_000L;
    private static final long PREVIOUS_SESSION_CLOSE_TIMEOUT_MS = 20_000L;
    private static final long LIBRARY_ENTER_TRANSITION_MS = 280L;
    private static final long LIBRARY_EXIT_TRANSITION_MS = 200L;
    private static final long LIBRARY_SHARED_ARTWORK_MS = 260L;
    private static final long HOST_WAKING_TIMEOUT_MS = 45_000L;
    private static final int EXPANDED_VISIBLE_ROWS = 3;
    private static final int EXPANDED_CACHE_ROWS_EACH_SIDE = 2;
    private static final int EXPANDED_PREFETCH_ROWS_EACH_SIDE = 1;
    private static final int PLAYNITE_ARTWORK_PREFETCH_WORKERS = 2;
    private static final int EXPANDED_WINDOW_ROWS = EXPANDED_VISIBLE_ROWS
            + EXPANDED_CACHE_ROWS_EACH_SIDE * 2;
    private static final int EXPANDED_PREFETCH_ROWS = EXPANDED_VISIBLE_ROWS
            + EXPANDED_PREFETCH_ROWS_EACH_SIDE * 2;
    private static final int EXPANDED_WINDOW_WARMUP_ROWS = 1;
    private static final long EXPANDED_WINDOW_WARMUP_DELAY_MS = 150L;
    private static final long EXPANDED_FOCUS_TRANSITION_TIMEOUT_MS = 300L;
    private static final long ARTWORK_FOCUS_SETTLE_MS = 420L;
    private static final long CAROUSEL_MARQUEE_SETTLE_MS = 320L;
    private static final long ARTWORK_CROSSFADE_MS = 560L;
    private static final int CAROUSEL_CARD_WIDTH_DP = 66;
    private static final int CAROUSEL_CARD_HEIGHT_DP = 88;
    private static final int CAROUSEL_FOCUSED_CARD_WIDTH_DP = 92;
    private static final int CAROUSEL_FOCUSED_CARD_HEIGHT_DP = 123;
    private static final int CAROUSEL_CARD_GAP_DP = 10;
    private static final int CAROUSEL_PREVIOUS_CARD_COUNT = 2;
    private static final long PLAYNITE_SELECTION_SAVE_DELAY_MS = 350L;
    private static final long LIBRARY_UPDATE_NAVIGATION_IDLE_MS = 180L;
    private static final long WINDOWS_PROFILE_SWITCH_TIMEOUT_MS = 2 * 60_000L;
    private static final long PIN_INVALID_FEEDBACK_MS = 1_200L;
    private static final String PREF_SCREEN_SAVER_SECONDS = "screen_saver_seconds";
    private static final String PREF_SCREEN_SAVER_MINUTES_LEGACY = "screen_saver_minutes";
    private static final String PREF_LAST_CONFIRMED_HOST_STATE = "last_confirmed_host_state.";
    private static final int SCREEN_SAVER_NEVER = 0;
    private static final int SCREEN_SAVER_DEFAULT_SECONDS = 5 * 60;
    private static final long SCREEN_SAVER_SLIDE_MS = 30_000L;
    private static final long EXPANDED_NAVIGATION_INTERVAL_MS = 70L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final ExecutorService playniteExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService playniteArtworkExecutor =
            Executors.newFixedThreadPool(PLAYNITE_ARTWORK_PREFETCH_WORKERS);
    private final AtomicInteger hostPreparationGeneration = new AtomicInteger();
    private final AtomicInteger artworkGeneration = new AtomicInteger();
    private final AtomicInteger discordStatusGeneration = new AtomicInteger();
    private final AtomicInteger playniteGeneration = new AtomicInteger();
    private final AtomicInteger appListRenderGeneration = new AtomicInteger();
    private final AtomicInteger playniteArtworkGeneration = new AtomicInteger();
    private final AtomicInteger profileGeneration = new AtomicInteger();
    private final AtomicInteger profileGateGeneration = new AtomicInteger();
    private final AtomicInteger hostProfileRefreshGeneration = new AtomicInteger();
    private final Map<String, Integer> hostProfileRefreshGenerations =
            new ConcurrentHashMap<>();
    private final Set<String> hostProfileRefreshInFlight = ConcurrentHashMap.newKeySet();
    private volatile WindowsProfileSwitchAttempt windowsProfileSwitchAttempt;
    private final SessionStateResolver sessionStateResolver = new SessionStateResolver();
    private final Map<String, ComputerDetails> hosts = new LinkedHashMap<>();
    private final Set<String> newlyDiscoveredHosts = new LinkedHashSet<>();

    private SharedPreferences preferences;
    private SharedPreferences microphoneStatePreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener microphoneStateListener =
            (preferences, key) -> {
                if (UsbMicrophoneService.STATE_KEY.equals(key)) {
                    mainHandler.post(this::refreshUsbMicrophoneIndicator);
                }
            };
    private ConsoleLibraryViewStateStore libraryViewStateStore;
    private ConsoleLibraryTransitionCoordinator libraryTransitionCoordinator;
    private DiskAssetLoader assetLoader;
    private ConsoleAudioEngine consoleAudioEngine;
    private ConsoleUiFeedback consoleFeedback;
    private InputManager inputManager;
    private DiscordPanelController discordPanelController;
    private DiscordSocialPanelController discordSocialPanelController;
    private HostGatewayClient hostGatewayClient;
    private GameOperationsController gameOperationsController;
    private HostLaunchPreflight hostLaunchPreflight;
    private SessionOrchestrator sessionOrchestrator;
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
    private String retainedStreamSessionId = "";
    private String retainedStreamHostId = "";
    private String retainedStreamProfileId = GatewayConnection.DEFAULT_PROFILE_ID;
    private int retainedStreamAppId = StreamConfiguration.INVALID_APP_ID;
    private String retainedStreamPlayniteGameId = "";
    private long warmUpClientAttempt;
    private String warmUpClientHostId = "";
    private int warmUpStatus;
    private PlayIntent pendingWarmUpRelay;
    private boolean warmUpRelayDispatched;
    private boolean warmUpRelaySubmitted;
    private boolean warmUpHomeFrameAccepted;
    private boolean inputListenerRegistered;
    private boolean reducedMotion;
    private boolean uiSoundsEnabled;
    private boolean ambientSoundsEnabled;
    private int hostMusicVolume;
    private int menuMusicVolume;
    private int effectsVolume;
    private int backgroundStreamRetentionMinutes;
    private int screenSaverSeconds;
    private boolean showCarouselGameDescription;
    private boolean refreshHostsOnResume;
    private boolean refreshSessionOnResume;
    private boolean initialHostsLoaded;
    private boolean showHiddenApps;
    private boolean hostSelectionVisible = true;
    private boolean initialHostSelectionResolved;
    private String pendingHostPreparation;
    private boolean hostSelectionLongPressConsumed;
    private String autoLoginHostUuid;
    private String profileGateHostUuid;
    private final ConsolePinEntry pinEntry = new ConsolePinEntry();
    private String pinProfileId;
    private boolean pinFocusApps;
    private boolean pinPrepareHost;
    private boolean pinCompleteHostEntry;
    private boolean pinSubmitting;
    private long pinCooldownUntil;
    private boolean pinSecureFlagAdded;
    private volatile OfflinePinAttempt offlinePinAttempt;
    private boolean hasLastControllerInput;
    private boolean lastControllerPlayStation;
    private boolean lastInputWasController;

    static final class WindowsProfileSwitchAttempt {
        final String hostId;
        final String profileId;
        final String requestId;
        final GatewayConnection connection;
        volatile String attemptId = "";
        volatile boolean cancelled;

        WindowsProfileSwitchAttempt(String hostId, String profileId, String requestId,
                                    GatewayConnection connection) {
            this.hostId = hostId;
            this.profileId = profileId;
            this.requestId = requestId;
            this.connection = connection;
        }

        boolean matches(String hostId, String profileId, String requestId) {
            return !cancelled && this.hostId.equals(hostId)
                    && this.profileId.equals(profileId) && this.requestId.equals(requestId);
        }
    }

    private static final class OfflinePinAttempt {
        final String hostId;
        final String profileId;
        final int gateToken;
        final GatewayConnection pairing;
        final ComputerDetails host;
        final String initialAddress;
        volatile boolean cancelled;

        OfflinePinAttempt(String hostId, String profileId, int gateToken,
                          GatewayConnection pairing, ComputerDetails host) {
            this.hostId = hostId;
            this.profileId = profileId;
            this.gateToken = gateToken;
            this.pairing = pairing;
            this.host = host;
            this.initialAddress = host == null || host.activeAddress == null
                    ? null : host.activeAddress.address;
        }
    }

    private FrameLayout root;
    private DiscordDmToastView discordDmToastView;
    private DiscordDmNotificationCoordinator discordDmNotifications;
    private DiscordDmNotificationCoordinator.HostToken discordDmHostToken;
    private DiscordDmShortcutHandler discordDmShortcut;
    private FrameLayout screenSaverLayer;
    private ImageView screenSaverArtwork;
    private ImageView screenSaverArtworkNext;
    private TextView screenSaverCaption;
    private boolean screenSaverVisible;
    private int screenSaverGeneration;
    private int screenSaverLoadGeneration;
    private int screenSaverDismissKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private long screenSaverMotionBlockUntil;
    private List<PlayniteDashboardItem> screenSaverItems = Collections.emptyList();
    private String screenSaverHostUuid = "";
    private int screenSaverItemIndex;
    private final Runnable screenSaverTimeout = this::showScreenSaver;
    private final Runnable screenSaverSlide = this::showNextScreenSaverArtwork;
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            if (hostSelectionClock != null) {
                hostSelectionClock.setText(DateFormat.getTimeInstance(
                        DateFormat.SHORT).format(now));
            }
            long delay = 60_000L - System.currentTimeMillis() % 60_000L;
            mainHandler.postDelayed(this, delay);
        }
    };
    private FrameLayout homeLayer;
    private FrameLayout hostSelectionLayer;
    private ConsoleProfileGateView profileGateView;
    private ConsolePinEntryView pinEntryView;
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
    private LockableScrollView sidePanelScroll;
    private FrameLayout communityPanelHost;
    private android.app.Dialog sideDialog;
    private final CommunityDictationSession communityDictationSession = new CommunityDictationSession();
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
    private TextView profileSelector;
    private TextView installedFilterButton;
    private TextView playniteLibraryStatus;
    private LinearLayout debugLibraryActions;
    private View debugLibrarySpacer;
    private FrameLayout carouselStage;
    private LinearLayout selectedGameTitleRow;
    private LinearLayout selectedGameMetadata;
    private ImageView selectedGameSource;
    private TextView selectedGameTitle;
    private TextView selectedGameFacts;
    private LinearLayout selectedGameFactsRow;
    private TextView selectedGameLastPlayedPill;
    private TextView selectedGamePlaytimePill;
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
    private long lastDirectionalNavigationAt;
    private ValueAnimator expandedGridScrollAnimator;
    private TextView quickResumeButton;
    private LinearLayout quickActions;
    private TextView quickActionHint;
    private ImageButton discordActionButton;
    private ImageButton usbMicrophoneActionButton;
    private int discordIndicatorColor = 0xFFFF6B6B;
    private int usbMicrophoneIndicatorColor = 0xFF697083;
    private String discordIndicatorDescription = "";
    private boolean discordNotificationPending;
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
    private Future<?> streamingAutopilotTask;
    private final AtomicInteger streamingAutopilotGeneration = new AtomicInteger();
    private String playniteArtworkPrefetchSignature;
    private final List<Future<?>> playniteArtworkPrefetchTasks =
            Collections.synchronizedList(new ArrayList<>());
    private static final LruCache<String, Bitmap> playniteBitmapCache =
            new LruCache<String, Bitmap>(32 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap == null ? 0 : bitmap.getAllocationByteCount();
                }
            };
    private static final ConcurrentHashMap<String, Object> playniteBitmapDecodeLocks =
            new ConcurrentHashMap<>();
    private static volatile InitialLibraryPresentation initialLibraryPresentation;

    private static final class InitialLibraryPresentation {
        final String hostId;
        final List<PlayniteLibraryGame> games;
        final List<NvApp> sunshineApps;
        final List<PlayniteDashboardItem> allItems;
        final List<PlayniteDashboardItem> unfilteredItems;
        final List<PlayniteDashboardItem> carouselItems;
        final PlayniteSessionPresentation.Projection sessionProjection;
        final String resumeGameId;
        final String suspendedGameId;
        final String sessionSignature;
        final long savedAt;

        InitialLibraryPresentation(String hostId, List<PlayniteLibraryGame> games,
                                   List<NvApp> sunshineApps,
                                   List<PlayniteDashboardItem> allItems,
                                   List<PlayniteDashboardItem> unfilteredItems,
                                   List<PlayniteDashboardItem> carouselItems,
                                   PlayniteSessionPresentation.Projection sessionProjection,
                                   String resumeGameId, String suspendedGameId,
                                   String sessionSignature, long savedAt) {
            this.hostId = hostId;
            this.games = games;
            this.sunshineApps = sunshineApps;
            this.allItems = allItems;
            this.unfilteredItems = unfilteredItems;
            this.carouselItems = carouselItems;
            this.sessionProjection = sessionProjection;
            this.resumeGameId = resumeGameId;
            this.suspendedGameId = suspendedGameId;
            this.sessionSignature = sessionSignature;
            this.savedAt = savedAt;
        }
    }
    private String expandedSearchQuery = "";
    private final PlayniteLibraryQuery.Cache expandedLibraryQuery =
            new PlayniteLibraryQuery.Cache();
    private Runnable pendingPlayniteSelectionSave;
    private String pendingPlayniteSelectionHostUuid;
    private String pendingPlayniteSelectionGameId;
    private boolean pendingPlayniteSelectionExpanded;
    private List<PlayniteLibraryGame> currentPlayniteGames = Collections.emptyList();
    private List<PlayniteDashboardItem> renderedPlayniteItems = Collections.emptyList();
    private List<PlayniteDashboardItem> allPlayniteItems = Collections.emptyList();
    private List<PlayniteDashboardItem> unfilteredPlayniteItems = Collections.emptyList();
    private String resumePlayniteGameId = "";
    private String suspendedPlayniteGameId = "";
    private PlayniteSessionPresentation.Projection playniteSessionProjection;
    private String renderedCarouselSessionSignature = "";
    private String renderedExpandedSessionSignature = "";
    private final Map<String, String> activePlayniteGameIds = new LinkedHashMap<>();
    private final Map<String, String> activePlayniteGameStates = new LinkedHashMap<>();
    private final Map<String, Integer> activePlayniteGameAppIds = new LinkedHashMap<>();
    private final Map<String, Long> activePlayniteGameResolvedAt = new LinkedHashMap<>();
    private final Map<String, Long> activePlayniteGameRequestedAt = new LinkedHashMap<>();
    private final Map<String, RunningGameObservation> runningGameObservations =
            new LinkedHashMap<>();

    static final class RunningGameObservation {
        final HostGatewayClient.RunningGames inventory;
        final GatewayConnection connection;
        final long observedAt;

        RunningGameObservation(HostGatewayClient.RunningGames inventory,
                               GatewayConnection connection, long observedAt) {
            this.inventory = inventory;
            this.connection = connection;
            this.observedAt = observedAt;
        }

        HostGatewayClient.RunningGame find(String gameId, long now) {
            return inventory != null && observedAt > 0L
                    && now >= observedAt && now - observedAt < 10_000L
                    ? inventory.find(gameId) : null;
        }

        String signature(long now) {
            if (inventory == null) return "";
            StringBuilder result = new StringBuilder("|running:");
            for (HostGatewayClient.RunningGame game : inventory.games) {
                if (find(game.gameId, now) != null) result.append(game.gameId)
                        .append(':').append(game.processToken).append(';');
            }
            return result.toString();
        }

        RunningGameObservation without(HostGatewayClient.RunningGame stopped) {
            if (inventory == null) return this;
            List<HostGatewayClient.RunningGame> remaining = new ArrayList<>();
            for (HostGatewayClient.RunningGame game : inventory.games) {
                if (!game.gameId.equals(stopped.gameId)
                        || !game.processToken.equals(stopped.processToken)) remaining.add(game);
            }
            return new RunningGameObservation(new HostGatewayClient.RunningGames(remaining,
                    inventory.status, inventory.revision), connection, observedAt);
        }
    }
    private final Map<String, Integer> activePlayniteGameRequestGenerations =
            new LinkedHashMap<>();
    private int activePlayniteGameRequestGeneration;
    private final Map<String, Integer> lastFreshRunningAppIds = new LinkedHashMap<>();
    private final Map<String, String> lastFreshStreamSessionIds = new LinkedHashMap<>();
    private final Set<String> activePlayniteGameResolutionInFlight =
            Collections.synchronizedSet(new HashSet<>());
    private String lastCarouselGameId = "";
    private String libraryTransitionGameId = "";
    private boolean pendingExpandedLibraryRestore;
    private final List<LibraryTransitionGhost> libraryTransitionGhosts = new ArrayList<>();
    private final Set<String> vibepolloEnsureInFlight =
            Collections.synchronizedSet(new HashSet<>());
    private final Set<String> completedPlayniteInstallAnimations = new HashSet<>();
    private List<NvApp> currentSunshineApps = Collections.emptyList();
    private boolean playniteLibraryCached;
    private long playniteLibraryCachedAt;
    private boolean playniteLibraryRefreshing;
    private boolean playniteInitialLoadPending;
    private String initialLocalAppsHostId = "";
    private String initialLocalLibraryHostId = "";
    private final Set<String> initialCarouselArtworkLoads = new HashSet<>();
    private int initialCarouselArtworkGeneration;
    private boolean suppressInitialCarouselMotion;
    private boolean deferInitialPlayniteRefresh;
    private PlayniteLibraryRepository.ErrorKind playniteLibraryError;
    private String currentPlayniteHostUuid;
    private int pendingConsoleUpdateChannels;
    private boolean consoleUpdatePosted;
    private long lastDirectionalAudioInputAt;
    private long lastVolumeAxisAdjustmentAt;
    private final ViewTreeObserver.OnGlobalFocusChangeListener consoleFocusSoundListener =
            (oldFocus, newFocus) -> {
                if (newFocus != null) newFocus.setSoundEffectsEnabled(false);
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
            if (!fresh && previous != null) {
                hostStateController.remember(previous.uuid, previous.state);
            }
            if (fresh) {
                reconcileFreshSessionFacts(copy);
                reconcileAuthoritativeSession(copy);
            }
            hostStateController.observe(copy, fresh);
            if (fresh) rememberConfirmedHostState(copy);
            if (previous == null && initialHostsLoaded) newlyDiscoveredHosts.add(copy.uuid);
            hosts.put(copy.uuid, copy);
            if (fresh && active && copy.uuid.equals(selectedHostUuid)) {
                resolveActivePlayniteGame(copy,
                        resolveSessionSnapshot(copy).state != SessionSnapshot.State.TERMINATING);
                refreshRunningGamePresentation(copy);
            }
            queueConsoleUpdates(ConsoleUpdateChannels.diff(
                    previous, copy, selectedHostUuid));
        });
    };

    private void reconcileFreshSessionFacts(ComputerDetails host) {
        SuspendedSessionStore.Session suspended =
                SuspendedSessionStore.load(this, host.uuid, selectedProfileId(host.uuid));
        if (suspended != null && suspended.resumedAt == 0L
                && host.state != ComputerDetails.State.ONLINE
                && suspended.sleepObservedAt == 0L) {
            SuspendedSessionStore.markSleepObservedIfMatches(this, suspended);
        }
        HostSleepStateStore.State sleep = HostSleepStateStore.load(this, host.uuid);
        if (sleep != null && host.state != ComputerDetails.State.ONLINE
                && sleep.sleepObservedAt == 0L) {
            HostSleepStateStore.markObserved(this, host.uuid, sleep);
        } else if (sleep != null && host.state == ComputerDetails.State.ONLINE
                && sleep.sleepObservedAt > 0L) {
            HostSleepStateStore.clearIfMatches(this, host.uuid, sleep.requestedAt);
        }

    }

    private void reconcileAuthoritativeSession(ComputerDetails host) {
        Integer previousRunningAppId = lastFreshRunningAppIds.put(
                host.uuid, host.runningGameId);
        SessionResumeManager.PendingSession pending =
                SessionResumeManager.pendingSession(this);
        String previousStreamSessionId = lastFreshStreamSessionIds.get(host.uuid);
        SuspendedSessionStore.Session explicitSuspended =
                SuspendedSessionStore.load(this, host.uuid, selectedProfileId(host.uuid));
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        boolean lostRetainedTransport = AuthoritativeSessionTransition.lostRetainedTransport(
                host.state == ComputerDetails.State.ONLINE, host.runningGameId,
                host.uuid.equalsIgnoreCase(retained.hostId), retained.state);
        if (lostRetainedTransport && !retained.streamSessionId.isEmpty()
                && RetainedStreamSessionCoordinator.clearIfMatches(
                retained.streamSessionId)) {
            SessionResumeManager.clearIfMatches(this, retained.streamSessionId);
            BackgroundStreamService.resumed(this, retained.streamSessionId);
            if (pending != null && pending.matches(retained.streamSessionId)) pending = null;
            boolean exactLocalRetained = retained.streamSessionId.equals(
                    retainedStreamSessionId)
                    && retained.hostId.equalsIgnoreCase(retainedStreamHostId);
            if (exactLocalRetained) {
                retainedStreamSessionId = "";
                retainedStreamHostId = "";
                retainedStreamAppId = StreamConfiguration.INVALID_APP_ID;
                retainedStreamPlayniteGameId = "";
                if (retainedStreamHome) finish();
            }
        }
        boolean retainedLive = host.uuid.equalsIgnoreCase(retained.hostId)
                && (retained.state == RetainedStreamSessionCoordinator.State.HOME_LIVE
                || retained.state == RetainedStreamSessionCoordinator.State.PARKED_LIVE);
        boolean retainedReplacedSuspended = retainedLive && explicitSuspended != null
                && (retained.appId != explicitSuspended.sunshineAppId
                || (!explicitSuspended.playniteGameId.isEmpty()
                && !retained.playniteGameId.isEmpty()
                && !explicitSuspended.playniteGameId.equals(retained.playniteGameId)));
        if (explicitSuspended != null && explicitSuspended.resumedAt == 0L
                && ((host.runningGameId != 0
                && explicitSuspended.sunshineAppId != host.runningGameId)
                || retainedReplacedSuspended)) {
            SuspendedSessionStore.markSessionEndedIfMatches(
                    this, host.uuid, explicitSuspended.profileId,
                    explicitSuspended.suspendId);
        }
        if (host.runningGameId != 0) {
            if (host.uuid.equalsIgnoreCase(retained.hostId)
                    && retained.appId == host.runningGameId
                    && !retained.streamSessionId.isEmpty()) {
                lastFreshStreamSessionIds.put(host.uuid, retained.streamSessionId);
            } else {
                if (pending != null && host.uuid.equalsIgnoreCase(pending.hostUuid)
                        && pending.appId == host.runningGameId) {
                    lastFreshStreamSessionIds.put(host.uuid, pending.streamSessionId);
                }
            }
        } else {
            lastFreshStreamSessionIds.remove(host.uuid);
        }
        if (!AuthoritativeSessionTransition.ended(
                previousRunningAppId, host.runningGameId)) return;
        String endedStreamSessionId = AuthoritativeSessionTransition.endedSessionId(
                previousRunningAppId, host.runningGameId, previousStreamSessionId);
        invalidateActivePlayniteGameRequest(host.uuid);
        if (host.uuid.equalsIgnoreCase(selectedHostUuid)) {
            refreshSelectedApplications();
            refreshSelectedPlayniteLibrary();
        }
        android.util.Log.i("MoonWakerSession",
                "Authoritative session ended host=" + host.uuid
                        + " previousApp=" + previousRunningAppId);
        if (!endedStreamSessionId.isEmpty()) {
            SuspendedSessionStore.Session resumed =
                    SuspendedSessionStore.load(this, host.uuid, selectedProfileId(host.uuid));
            if (resumed != null && resumed.resumedAt > 0L
                    && endedStreamSessionId.equals(resumed.resumedStreamSessionId)) {
                SuspendedSessionStore.markSessionEndedIfMatches(
                        this, host.uuid, resumed.profileId, resumed.suspendId);
            }
            RetainedStreamSessionCoordinator.clearIfMatches(endedStreamSessionId);
            SessionResumeManager.clearIfMatches(this, endedStreamSessionId);
            BackgroundStreamService.resumed(this, endedStreamSessionId);
        }
    }
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            ComputerManagerService.ComputerManagerBinder binder =
                    (ComputerManagerService.ComputerManagerBinder) service;
            executor.execute(() -> {
                binder.waitForReady();
                mainHandler.post(() -> {
                    managerBinder = binder;
                    dispatchPendingHostPreparation();
                    dispatchPendingWarmUpRelay();
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
        if (retainedStreamHome) setTheme(useTransparentWarmUpStartingWindow()
                ? R.style.ConsoleStreamHomeWarmUpTheme
                : R.style.ConsoleStreamHomeTheme);
        super.onCreate(state);
        if (!retainedStreamHome
                && RetainedStreamSessionCoordinator.canResumeInstantly()) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        retainedStreamSessionId = normalizeId(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_SESSION_ID));
        retainedStreamHostId = normalizeId(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_HOST_ID));
        retainedStreamProfileId = GatewayConnection.normalizeProfileId(
                getIntent().getStringExtra(EXTRA_RETAINED_STREAM_PROFILE_ID));
        retainedStreamAppId = getIntent().getIntExtra(EXTRA_RETAINED_STREAM_APP_ID,
                StreamConfiguration.INVALID_APP_ID);
        retainedStreamPlayniteGameId = normalizeId(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_PLAYNITE_GAME_ID));
        warmUpClientAttempt = getIntent().getLongExtra(EXTRA_WARM_UP_ATTEMPT, 0L);
        warmUpClientHostId = warmUpClientAttempt > 0L ? retainedStreamHostId : "";
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        microphoneStatePreferences = getSharedPreferences(
                UsbMicrophoneService.STATE_PREFS, MODE_PRIVATE);
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
        screenSaverSeconds = preferences.contains(PREF_SCREEN_SAVER_SECONDS)
                ? preferences.getInt(PREF_SCREEN_SAVER_SECONDS, SCREEN_SAVER_DEFAULT_SECONDS)
                : preferences.contains(PREF_SCREEN_SAVER_MINUTES_LEGACY)
                ? preferences.getInt(PREF_SCREEN_SAVER_MINUTES_LEGACY, 0) * 60
                : SCREEN_SAVER_DEFAULT_SECONDS;
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
        gameOperationsController = new GameOperationsController(
                hostGatewayClient, executor, mainHandler::post);
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
        PreferenceConfiguration shortcutPreferences = PreferenceConfiguration.readPreferences(this);
        discordDmToastView.setReducedMotion(reducedMotion);
        discordDmToastView.setShortcutLabel(getString(R.string.discord_dm_toast_shortcut,
                getString(DiscordDmShortcutHandler.triggerLabelResource(
                        shortcutPreferences.overlayTriggerButton))));
        discordDmNotifications = DiscordDmNotificationCoordinator.getInstance();
        discordDmNotifications.initialize(this);
        discordDmHostToken = discordDmNotifications.registerHost(
                new DiscordDmNotificationCoordinator.Host() {
                    @Override public void showDiscordDmToast(
                            DiscordDmNotificationCoordinator.ToastModel model, boolean announce) {
                        discordDmToastView.showDiscordDmToast(model, announce);
                        setDiscordNotificationPending(true);
                    }

                    @Override public void hideDiscordDmToast() {
                        discordDmToastView.hideDiscordDmToast();
                    }

                    @Override public void hideDiscordDmToastImmediately() {
                        discordDmToastView.hideDiscordDmToastImmediately();
                    }
                });
        discordDmShortcut = new DiscordDmShortcutHandler(
                shortcutPreferences.overlayTriggerButton,
                shortcutPreferences.overlayHoldDurationMs,
                new DiscordDmShortcutHandler.Scheduler() {
                    @Override public void postDelayed(Runnable task, long delayMs) {
                        mainHandler.postDelayed(task, delayMs);
                    }

                    @Override public void remove(Runnable task) {
                        mainHandler.removeCallbacks(task);
                    }
                },
                () -> discordDmNotifications.hasQuickAction(discordDmHostToken),
                this::openDiscordDmShortcut);
        consoleFeedback = new ConsoleUiFeedback(this, root, consoleAudioEngine, reducedMotion);
        hostLaunchPreflight = createHostLaunchPreflight();
        sessionOrchestrator = createSessionOrchestrator();
        warmUpRelayDispatched = state != null
                && state.getBoolean(STATE_WARM_UP_RELAY_DISPATCHED, false);
        armPendingWarmUpRelay();
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
        discordSocialPanelController = new DiscordSocialPanelController(this, mainHandler,
                new DiscordSocialPanelController.Ui() {
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

                    @Override public void showCommunity(View shell) {
                        showCommunityPanel(shell);
                    }

                    @Override public void toast(String message) {
                        ConsoleUiFeedback.makeText(ConsoleActivity.this, message,
                                Toast.LENGTH_LONG).show();
                    }

                    @Override public void backPanel() {
                        handlePanelBack();
                    }

                    @Override public void requestCommunityDictation(long recipientId,
                                                                     long directMessageGeneration) {
                        startCommunityDictation(recipientId, directMessageGeneration);
                    }

                    @Override public void dismissCommunityForAuthorization() {
                        hideSidePanelImmediately();
                    }

                    @Override public void voiceChanged(HostGatewayClient.DiscordVoice voice) {
                        applyDiscordVoiceIndicator(voice);
                    }

                    @Override public void visiblePeerChanged(long peerId) {
                        if (discordDmNotifications != null) {
                            discordDmNotifications.setVisiblePeer(discordDmHostToken, peerId);
                        }
                    }
                }, new DiscordSocialPanelController.CommunitySource() {
                    @Override public void requestHome(DiscordPanelController.CommunityHomeCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) {
                            callback.onUnavailable();
                            return;
                        }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.loadCommunityHome(host.uuid, address, host.name, callback);
                    }

                    @Override public void requestGuildChannels(HostGatewayClient.DiscordGuild guild,
                            DiscordPanelController.CommunityChannelsCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) {
                            callback.onUnavailable();
                            return;
                        }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.loadCommunityGuildChannels(host.uuid, address, host.name,
                                guild, callback);
                    }

                    @Override public void joinChannel(HostGatewayClient.DiscordChannel channel,
                            DiscordPanelController.CommunityActionCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) {
                            callback.onError(getString(R.string.discord_join_not_confirmed));
                            return;
                        }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.joinCommunityChannel(host.uuid, address, host.name,
                                channel, callback);
                    }

                    @Override public void requestVoice(
                            DiscordPanelController.CommunityVoiceCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) { callback.onError(getString(R.string.discord_join_not_confirmed)); return; }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.loadCommunityVoice(host.uuid, address, host.name, callback);
                    }

                    @Override public void voiceAction(DiscordPanelController.CommunityVoiceAction action,
                                                      DiscordPanelController.CommunityVoiceCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) { callback.onError(getString(R.string.discord_join_not_confirmed)); return; }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.performCommunityVoiceAction(host.uuid, address, host.name,
                                action, callback);
                    }

                    @Override public void requestOptions(boolean refresh,
                            DiscordPanelController.CommunityOptionsCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) { callback.onError(getString(R.string.discord_join_not_confirmed)); return; }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.loadCommunityOptions(host.uuid, address, host.name, refresh, callback);
                    }

                    @Override public void setOption(DiscordPanelController.CommunitySetting setting,
                                                    boolean enabled,
                                                    DiscordPanelController.CommunityOptionsCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) { callback.onError(getString(R.string.discord_join_not_confirmed)); return; }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.setCommunitySetting(host.uuid, address, host.name, setting,
                                enabled, callback);
                    }

                    @Override public void hostAction(DiscordPanelController.CommunityHostAction action,
                                                     DiscordPanelController.CommunityOptionsCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) { callback.onError(getString(R.string.discord_join_not_confirmed)); return; }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.performCommunityHostAction(host.uuid, address, host.name,
                                action, callback);
                    }

                    @Override public void requestAudio(
                            DiscordPanelController.CommunityAudioCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) { callback.onError(getString(R.string.discord_join_not_confirmed)); return; }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.loadCommunityAudio(host.uuid, address, host.name, callback);
                    }

                    @Override public void selectAudioDevice(HostGatewayClient.AudioDevice device,
                                                            DiscordPanelController.CommunityAudioCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) { callback.onError(getString(R.string.discord_join_not_confirmed)); return; }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.selectCommunityAudioDevice(host.uuid, address, host.name,
                                device, callback);
                    }

                    @Override public void changeSystemVolume(int delta,
                                                             DiscordPanelController.CommunityAudioCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) { callback.onError(getString(R.string.discord_join_not_confirmed)); return; }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.changeCommunitySystemVolume(host.uuid, address, host.name,
                                delta, callback);
                    }

                    @Override public void toggleSystemMute(
                            DiscordPanelController.CommunityAudioCallback callback) {
                        ComputerDetails host = hosts.get(selectedHostUuid);
                        if (host == null) { callback.onError(getString(R.string.discord_join_not_confirmed)); return; }
                        String address = host.activeAddress == null ? null : host.activeAddress.address;
                        discordPanelController.toggleCommunitySystemMute(host.uuid, address, host.name, callback);
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
        microphoneStatePreferences.registerOnSharedPreferenceChangeListener(
                microphoneStateListener);
        if (discordDmNotifications != null) {
            discordDmNotifications.activateHost(discordDmHostToken);
            discordDmNotifications.setWindowFocused(
                    discordDmHostToken, getWindow().getDecorView().hasWindowFocus());
        }
        if (discordSocialPanelController != null) discordSocialPanelController.onActivityResumed();
        active = true;
        if (initialHostsLoaded) resolveInitialHostSelection();
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
        if (selected != null && !hostSelectionVisible && profileGateHostUuid == null) {
            if (appListPoller == null) startAppListPoller(selected);
            if (!renderedPlayniteItems.isEmpty()) {
                schedulePlayniteArtworkPrefetch(selected, renderedPlayniteItems);
            }
            loadPlayniteForHost(selected);
        }
        if (refreshSessionOnResume) {
            refreshSessionOnResume = false;
            refreshSessionState(selectedHostUuid);
        }
        refreshControllers();
        refreshDiscordIndicator();
        refreshUsbMicrophoneIndicator();
        mainHandler.postDelayed(controllerRefresh, CONTROLLER_REFRESH_MS);
        if (loadingLayer != null && loadingLayer.getVisibility() == View.VISIBLE) showHome();
        if (homeLayer != null) {
            homeLayer.setVisibility(hostSelectionVisible ? View.GONE : View.VISIBLE);
        }
        if (hostSelectionLayer != null) {
            hostSelectionLayer.setVisibility(hostSelectionVisible
                    && profileGateHostUuid == null ? View.VISIBLE : View.GONE);
        }
        if (profileGateView != null && profileGateHostUuid != null) {
            profileGateView.setVisibility(pinProfileId == null ? View.VISIBLE : View.GONE);
            if (pinProfileId == null) profileGateView.bringToFront();
        }
        if (pinEntryView != null && pinProfileId != null) {
            pinEntryView.setVisibility(View.VISIBLE);
            pinEntryView.bringToFront();
        }
        hideSystemUi();
        resetScreenSaverTimer();
        updateScreenSaverWakePolicy();
        mainHandler.removeCallbacks(clockTick);
        clockTick.run();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null) {
            InputDevice inputDevice = event.getDevice();
            if (inputDevice != null && isGamepad(inputDevice)) {
                hasLastControllerInput = true;
                lastInputWasController = true;
                lastControllerPlayStation = ControllerGlyphs.isPlayStation(inputDevice);
                if (pinEntryView != null && pinProfileId != null) {
                    pinEntryView.setPlayStationButtons(lastControllerPlayStation);
                    pinEntryView.setControllerInputMode(true);
                }
            } else if (inputDevice != null) {
                lastInputWasController = false;
                if (pinEntryView != null && pinProfileId != null) {
                    pinEntryView.setControllerInputMode(false);
                }
            }
            if (screenSaverVisible) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    screenSaverDismissKeyCode = event.getKeyCode();
                    hideScreenSaver();
                }
                return true;
            }
            if (screenSaverDismissKeyCode == event.getKeyCode()) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    screenSaverDismissKeyCode = KeyEvent.KEYCODE_UNKNOWN;
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) resetScreenSaverTimer();
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && isDirectionalNavigationKey(event.getKeyCode())) {
                lastDirectionalNavigationAt = event.getEventTime();
            }
        }
        if (pinProfileId != null && event != null) {
            int sources = event.getSource();
            InputDevice device = event.getDevice();
            if (device != null) sources |= device.getSources();
            boolean blocked = pinSubmitting
                    || SystemClock.elapsedRealtime() < pinCooldownUntil;
            ConsolePinEntry.Action action = blocked
                    ? pinEntry.handleBlocked(event.getAction(), event.getKeyCode(), sources)
                    : pinEntry.handle(event.getAction(), event.getKeyCode(), sources);
            if (action != ConsolePinEntry.Action.IGNORED) {
                if (action == ConsolePinEntry.Action.CHANGED) renderPinEntry("");
                else if (action == ConsolePinEntry.Action.SUBMIT) submitProfilePin();
                else if (action == ConsolePinEntry.Action.BACK) {
                    ComputerDetails host = currentHost(profileGateHostUuid);
                    if (host != null) showProfileGate(host, pinFocusApps, pinPrepareHost);
                }
                return true;
            }
        }
        if (profileGateHostUuid != null && event != null
                && event.getAction() == KeyEvent.ACTION_UP
                && (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            closeProfileGate();
            return true;
        }
        if (discordDmShortcut != null && discordDmShortcut.handle(event)) return true;
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

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event != null && screenSaverVisible) {
            hideScreenSaver();
            return true;
        }
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            resetScreenSaverTimer();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (isMeaningfulMotion(event)) {
            if (screenSaverVisible) {
                screenSaverMotionBlockUntil = SystemClock.uptimeMillis() + 350L;
                hideScreenSaver();
                return true;
            }
            if (SystemClock.uptimeMillis() < screenSaverMotionBlockUntil) return true;
            resetScreenSaverTimer();
        }
        return super.dispatchGenericMotionEvent(event);
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

    protected boolean useTransparentWarmUpStartingWindow() {
        return false;
    }

    protected final boolean hasResolvedInitialHostSelection() {
        return initialHostSelectionResolved;
    }

    protected boolean requiresPreparedInitialCarouselFrame() {
        return false;
    }

    protected final boolean prepareInitialCarouselFrame() {
        if (!initialLocalPresentationReady(selectedHostUuid,
                initialLocalAppsHostId, initialLocalLibraryHostId)) return false;
        if (!initialCarouselArtworkLoads.isEmpty()) return false;
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) return false;
        if (pendingExpandedLibraryRestore && CONSOLE_UI_V2
                && !unfilteredPlayniteItems.isEmpty()) {
            pendingExpandedLibraryRestore = false;
            enterExpandedLibrary(true);
            return false;
        }
        if (expandedLibraryMode) {
            View target = libraryTransitionGameId.isEmpty() ? firstFocusableChild(expandedGrid)
                    : directChildWithTag(expandedGrid,
                    "playnite:" + libraryTransitionGameId);
            if (target == null) target = firstFocusableChild(expandedGrid);
            if (target != null) {
                target.requestFocus();
                int desired = Math.max(0, target.getTop() - dp(12));
                expandedGridScroll.scrollTo(0, desired);
            }
        } else {
            View target = firstFocusableChild(appRow);
            if (target != null) target.requestFocus();
            if (portraitLayout) appVerticalScroll.scrollTo(0, 0);
            else appScroll.scrollTo(0, 0);
        }
        return true;
    }

    protected final void completeInitialCarouselFrame() {
        suppressInitialCarouselMotion = false;
        resetInitialCarouselArtworkWarmup();
        libraryTransitionCoordinator.setReducedMotion(reducedMotion);
        if (deferInitialPlayniteRefresh) {
            deferInitialPlayniteRefresh = false;
            scheduleNextPlayniteRefresh();
        }
    }

    static boolean initialLocalPresentationReady(String selectedHostId,
                                                 String appsHostId,
                                                 String libraryHostId) {
        return selectedHostId != null && !selectedHostId.isEmpty()
                && selectedHostId.equals(appsHostId)
                && selectedHostId.equals(libraryHostId);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (discordDmNotifications != null) {
            discordDmNotifications.setWindowFocused(discordDmHostToken, hasFocus);
        }
    }

    private static boolean isExpandedLibraryShortcutKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR
                || keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL
                || keyCode == KeyEvent.KEYCODE_BUTTON_Y;
    }

    @Override
    protected void onPause() {
        pendingHostPreparation = null;
        cancelOfflinePinAttempt();
        if (pinProfileId != null) {
            profileGateGeneration.incrementAndGet();
            pinEntry.clear();
            pinSubmitting = false;
            pinCooldownUntil = 0L;
            renderPinEntry("");
        }
        cancelWindowsProfileSwitch(false);
        microphoneStatePreferences.unregisterOnSharedPreferenceChangeListener(
                microphoneStateListener);
        flushPendingPlayniteSelection();
        if (discordDmShortcut != null) discordDmShortcut.cancel();
        if (discordDmNotifications != null) {
            discordDmNotifications.deactivateHost(discordDmHostToken);
        }
        setDiscordNotificationPending(false);
        if (discordSocialPanelController != null) discordSocialPanelController.onActivityPaused();
        active = false;
        screenSaverDismissKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        screenSaverMotionBlockUntil = 0L;
        hideScreenSaver();
        mainHandler.removeCallbacks(screenSaverTimeout);
        mainHandler.removeCallbacks(clockTick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (consoleAudioEngine != null) consoleAudioEngine.pause();
        cancelCurrentPreparation();
        hostPreparationGeneration.incrementAndGet();
        artworkGeneration.incrementAndGet();
        mainHandler.removeCallbacks(controllerRefresh);
        mainHandler.removeCallbacks(playniteRefreshCycle);
        cancelPlayniteRequest();
        cancelPlayniteArtworkPrefetch();
        stopExpandedDescriptionAutoScroll();
        if (libraryTransitionCoordinator != null) libraryTransitionCoordinator.cancel();
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
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            playniteBitmapCache.evictAll();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            playniteBitmapCache.trimToSize(16 * 1024 * 1024);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_WARM_UP_RELAY_DISPATCHED,
                warmUpRelayDispatched);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        active = false;
        pendingHostPreparation = null;
        cancelOfflinePinAttempt();
        pinEntry.clear();
        clearPinEntry();
        cancelStreamingAutopilot(false);
        if (root != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalFocusChangeListener(
                    consoleFocusSoundListener);
        }
        if (consoleAudioEngine != null) consoleAudioEngine.release();
        if (consoleFeedback != null) consoleFeedback.release();
        if (artworkScrimAnimator != null) artworkScrimAnimator.cancel();
        if (discordPanelController != null) discordPanelController.destroy();
        if (discordSocialPanelController != null) discordSocialPanelController.closePanel();
        if (discordDmNotifications != null) {
            discordDmNotifications.unregisterHost(discordDmHostToken);
            discordDmHostToken = null;
        }
        if (playniteFilterPopup != null) playniteFilterPopup.dismiss();
        if (sideDialog != null) sideDialog.dismiss();
        if (serviceBound) unbindService(serviceConnection);
        cancelPlayniteRequest();
        cancelPlayniteArtworkPrefetch();
        if (sessionOrchestrator != null) sessionOrchestrator.close();
        if (gameOperationsController != null) gameOperationsController.close();
        playniteExecutor.shutdownNow();
        playniteArtworkExecutor.shutdownNow();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (pinProfileId != null) {
            ComputerDetails host = currentHost(profileGateHostUuid);
            if (host != null) showProfileGate(host, pinFocusApps, pinPrepareHost);
            else closeProfileGate();
        } else if (profileGateHostUuid != null) {
            closeProfileGate();
        } else if (sideDialog != null && sideDialog.isShowing()) {
            handlePanelBack();
        } else if (loadingLayer != null && loadingLayer.getVisibility() == View.VISIBLE) {
            cancelCurrentPreparation();
            hostPreparationGeneration.incrementAndGet();
            showHome();
        } else if (expandedLibraryMode) {
            exitExpandedLibrary();
        } else if (RetainedStreamSessionCoordinator.hasRetainedSession()
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
                for (ComputerDetails host : known) {
                    hostStateController.remember(host.uuid,
                            cachedConfirmedHostState(host.uuid));
                    hostStateController.observe(host, false);
                    hosts.put(host.uuid, host);
                }
                initialHostsLoaded = true;
                renderHosts();
                resolveInitialHostSelection();
            });
        });
    }

    private ComputerDetails.State cachedConfirmedHostState(String hostUuid) {
        String value = preferences.getString(PREF_LAST_CONFIRMED_HOST_STATE + hostUuid, "");
        try {
            return ComputerDetails.State.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return ComputerDetails.State.UNKNOWN;
        }
    }

    private void rememberConfirmedHostState(ComputerDetails host) {
        if (host == null || host.uuid == null || host.state == ComputerDetails.State.UNKNOWN) return;
        preferences.edit().putString(PREF_LAST_CONFIRMED_HOST_STATE + host.uuid,
                host.state.name()).apply();
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
        container.setBackgroundResource(R.color.console_background);
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
        homeContent.setPadding(dp(54), dp(14), dp(54), dp(24));
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
        hostSelector.setOnClickListener(v -> {
            ComputerDetails host = hosts.get(selectedHostUuid);
            if (host != null) showHostSelectionOptions(host);
        });
        optionsButton = hostSelector;
        hostSelector.setMaxLines(2);
        hostSelector.setEllipsize(TextUtils.TruncateAt.END);
        hostSelector.setMaxWidth(dp(CONSOLE_UI_V2 ? 260 : 430));
        if (CONSOLE_UI_V2) {
            hostSelector.setTextSize(11);
            hostSelector.setMinHeight(dp(36));
            hostSelector.setMinWidth(0);
            hostSelector.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            hostSelector.setPadding(dp(10), dp(3), dp(10), dp(3));
            hostSelector.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_console_host_power, 0, 0, 0);
            hostSelector.setCompoundDrawablePadding(dp(8));
            hostSelector.setOnFocusChangeListener((view, focused) -> {
                styleHostSelector(focused);
                updateHostPowerLabel();
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

            profileSelector = compactButton(getString(R.string.gateway_profile_title));
            profileSelector.setTextSize(11);
            profileSelector.setMinHeight(dp(36));
            profileSelector.setOnClickListener(view -> showProfileSelection(
                    hosts.get(selectedHostUuid)));
            LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            profileParams.leftMargin = dp(8);
            header.addView(profileSelector, profileParams);

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
            profileSelector = compactButton(getString(R.string.gateway_profile_title));
            profileSelector.setOnClickListener(view -> showProfileSelection(
                    hosts.get(selectedHostUuid)));
            LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
            profileParams.leftMargin = dp(8);
            header.addView(profileSelector, profileParams);
        }
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (CONSOLE_UI_V2) headerParams.bottomMargin = 0;
        homeContent.addView(header, headerParams);
        if (CONSOLE_UI_V2) {
            quickActionHint = text("", 12, Color.WHITE, false);
            quickActionHint.setGravity(Gravity.CENTER);
            quickActionHint.setSingleLine(true);
            quickActionHint.setShadowLayer(dp(2), 0, dp(1), 0xE0000000);
            quickActionHint.setVisibility(View.INVISIBLE);
            FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(28),
                    Gravity.TOP | Gravity.START);
            hintParams.topMargin = dp(66);
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
        if (!CONSOLE_UI_V2) {
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
        if (CONSOLE_UI_V2 && !portraitLayout) {
            appRow.setGravity(Gravity.TOP);
            LayoutTransition carouselTransition = new LayoutTransition();
            carouselTransition.disableTransitionType(LayoutTransition.APPEARING);
            carouselTransition.disableTransitionType(LayoutTransition.DISAPPEARING);
            carouselTransition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
            carouselTransition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            carouselTransition.enableTransitionType(LayoutTransition.CHANGING);
            carouselTransition.setDuration(LayoutTransition.CHANGING,
                    getResources().getInteger(R.integer.console_motion_focus_ms));
            appRow.setLayoutTransition(carouselTransition);
        }
        carouselStage = CONSOLE_UI_V2 && !portraitLayout
                ? new FrameLayout(this) : null;
        if (carouselStage != null) {
            carouselStage.setClipChildren(false);
            carouselStage.setClipToPadding(false);
        }
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
            if (carouselStage != null) {
                appScroll.setClipChildren(true);
                appScroll.setClipToPadding(true);
            }
            appScroll.addView(appRow, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (carouselStage != null) {
                appScroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                      oldLeft, oldTop, oldRight, oldBottom) -> {
                    view.setClipBounds(new Rect(0, 0,
                            Math.max(0, right - left), Math.max(0, bottom - top)));
                    int endPadding = Math.max(0, right - left - dp(92));
                    if (appRow.getPaddingRight() != endPadding) {
                        appRow.setPadding(0, 0, endPadding, 0);
                    }
                });
                appScroll.setOnScrollChangeListener((view, scrollX, scrollY,
                                                     oldScrollX, oldScrollY) -> {
                    View focus = getCurrentFocus();
                    if (focus != null && focus.getParent() == appRow) {
                        positionCarouselMetadata(focus);
                    }
                });
                FrameLayout.LayoutParams appScrollParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(149), Gravity.TOP);
                appScrollParams.topMargin = dp(8);
                carouselStage.addView(appScroll, appScrollParams);
                homeContent.addView(carouselStage, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));
            } else {
                homeContent.addView(appScroll, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(235)));
            }
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
            debugLibraryActions.addView(new View(this), new LinearLayout.LayoutParams(
                    0, 1, 1f));
            libraryHeader.removeView(playniteLibraryStatus);
            playniteLibraryStatus.setTextSize(11);
            playniteLibraryStatus.setMaxLines(2);
            playniteLibraryStatus.setMinHeight(dp(18));
            playniteLibraryStatus.setShadowLayer(dp(2), 0, dp(1), 0xE0000000);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            statusParams.topMargin = dp(16);
            statusParams.bottomMargin = dp(12);
            homeContent.addView(playniteLibraryStatus, 1, statusParams);
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
            actionParams.topMargin = dp(5);
            homeContent.addView(debugLibraryActions, actionParams);
            debugLibraryActions.setVisibility(View.GONE);

            selectedGameMetadata = new LinearLayout(this);
            selectedGameMetadata.setOrientation(LinearLayout.VERTICAL);
            selectedGameMetadata.setPadding(dp(portraitLayout ? 8 : 62),
                    dp(8), dp(12), dp(8));
            selectedGameMetadata.setBackground(new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x0004070B, 0x6004070B, 0x6004070B,
                            0x3404070B, 0x0004070B}));
            selectedGameTitleRow = horizontalRow();
            selectedGameTitleRow.setGravity(Gravity.CENTER_VERTICAL);
            selectedGameSource = new ImageView(this);
            selectedGameSource.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            selectedGameTitleRow.addView(selectedGameSource,
                    new LinearLayout.LayoutParams(dp(22), dp(22)));
            selectedGameTitle = text("", 19, Color.WHITE, true);
            selectedGameTitle.setSingleLine(true);
            selectedGameTitle.setEllipsize(TextUtils.TruncateAt.END);
            selectedGameTitle.setShadowLayer(dp(2), 0, dp(1), 0xE0000000);
            LinearLayout.LayoutParams selectedTitleParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            selectedTitleParams.leftMargin = dp(CAROUSEL_CARD_GAP_DP);
            selectedGameTitleRow.addView(selectedGameTitle, selectedTitleParams);
            selectedGameTitleRow.setVisibility(View.INVISIBLE);
            selectedGameFacts = text("", 11, 0xFFB8C9DC, false);
            selectedGameFacts.setVisibility(View.GONE);
            selectedGameFactsRow = horizontalRow();
            selectedGameFactsRow.setGravity(Gravity.CENTER_VERTICAL);
            selectedGameLastPlayedPill = metadataPill();
            selectedGamePlaytimePill = metadataPill();
            selectedGamePlaytimePill.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_console_clock, 0, 0, 0);
            selectedGamePlaytimePill.setCompoundDrawablePadding(dp(5));
            selectedGameFactsRow.addView(selectedGameLastPlayedPill,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)));
            LinearLayout.LayoutParams playtimePillParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(24));
            playtimePillParams.leftMargin = dp(12);
            selectedGameFactsRow.addView(selectedGamePlaytimePill, playtimePillParams);
            selectedGameDescription = text("", 11, 0xFFD2D9E2, false);
            selectedGameDescription.setShadowLayer(dp(2), 0, dp(1), 0xE0000000);
            selectedGameDescription.setLineSpacing(0, 1.25f);
            selectedGameDescription.setMaxLines(6);
            selectedGameDescription.setEllipsize(TextUtils.TruncateAt.END);
            selectedGameDescription.setVisibility(
                    showCarouselGameDescription ? View.VISIBLE : View.GONE);
            selectedGameMetadata.addView(selectedGameFactsRow, matchLinearWidth());
            selectedGameMetadata.addView(selectedGameFacts, matchLinearWidth());
            LinearLayout.LayoutParams descriptionParams = matchLinearWidth();
            descriptionParams.topMargin = dp(8);
            selectedGameMetadata.addView(selectedGameDescription, descriptionParams);
            selectedGameMetadata.setVisibility(View.INVISIBLE);
            if (carouselStage != null) {
                FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                        dp(430), dp(28),
                        Gravity.TOP | Gravity.START);
                titleParams.leftMargin = dp(112);
                titleParams.topMargin = dp(103);
                carouselStage.addView(selectedGameTitleRow, titleParams);
            } else {
                selectedGameMetadata.addView(selectedGameTitleRow, 0, matchLinearWidth());
            }
            LinearLayout.LayoutParams metadataParams = new LinearLayout.LayoutParams(
                    portraitLayout ? ViewGroup.LayoutParams.MATCH_PARENT : dp(484),
                    dp(showCarouselGameDescription ? 140 : 36));
            if (!portraitLayout) metadataParams.leftMargin = -dp(54);
            metadataParams.topMargin = dp(4);
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
        discordDmToastView = new DiscordDmToastView(this);
        FrameLayout.LayoutParams toastParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        toastParams.setMargins(dp(18), dp(18), dp(18), 0);
        container.addView(discordDmToastView, toastParams);
        if (retainedStreamHome) deferSecondaryLayersAfterFirstLayout(container);
        else buildSecondaryLayers(container);
        return container;
    }

    private void deferSecondaryLayersAfterFirstLayout(FrameLayout container) {
        ViewTreeObserver.OnGlobalLayoutListener listener =
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        if (container.getViewTreeObserver().isAlive()) {
                            container.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                        mainHandler.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            buildSecondaryLayers(container);
                            homeLayer.setVisibility(View.VISIBLE);
                            hostSelectionLayer.setVisibility(View.GONE);
                        });
                    }
                };
        container.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    private void buildSecondaryLayers(FrameLayout container) {
        buildHostSelectionLayer(container);
        profileGateView = new ConsoleProfileGateView(this);
        container.addView(profileGateView, match());
        pinEntryView = new ConsolePinEntryView(this);
        container.addView(pinEntryView, match());
        buildSidePanel();
        buildLoadingLayer(container);
        buildScreenSaverLayer(container);
        discordDmToastView.bringToFront();
    }

    private void buildScreenSaverLayer(FrameLayout container) {
        screenSaverLayer = new FrameLayout(this);
        screenSaverLayer.setVisibility(View.GONE);
        screenSaverLayer.setBackgroundColor(Color.BLACK);
        screenSaverLayer.setFocusable(true);
        screenSaverLayer.setClickable(true);

        screenSaverArtwork = screenSaverImage();
        screenSaverArtworkNext = screenSaverImage();
        screenSaverLayer.addView(screenSaverArtwork, match());
        screenSaverLayer.addView(screenSaverArtworkNext, match());

        View captionScrim = new View(this);
        captionScrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xC0000000}));
        FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190), Gravity.BOTTOM);
        screenSaverLayer.addView(captionScrim, scrimParams);

        screenSaverCaption = text("", 13, Color.WHITE, false);
        screenSaverCaption.setGravity(Gravity.END);
        screenSaverCaption.setShadowLayer(dp(3), 0, dp(1), 0xFF000000);
        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        captionParams.rightMargin = dp(42);
        captionParams.bottomMargin = dp(32);
        screenSaverLayer.addView(screenSaverCaption, captionParams);

        container.addView(screenSaverLayer, match());
    }

    private ImageView screenSaverImage() {
        ImageView image = new ArtworkImageView(this);
        image.setAlpha(0f);
        return image;
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
                15, 0xFFB8C0CD, false);
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(236));
        rowParams.topMargin = dp(18);
        content.addView(hostSelectionScroll, rowParams);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(330), Gravity.CENTER);
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

        TextView version = text("v" + BuildConfig.VERSION_NAME, 8, 0x809DA8B6, false);
        FrameLayout.LayoutParams versionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        versionParams.leftMargin = dp(20);
        versionParams.bottomMargin = dp(14);
        hostSelectionLayer.addView(version, versionParams);

        homeLayer.setVisibility(View.GONE);
        container.addView(hostSelectionLayer, match());
        renderHostSelection();
    }

    private void resolveInitialHostSelection() {
        if (!active || initialHostSelectionResolved) return;
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
            resolveProfileGate(automatic, false, true);
        } else {
            showHostSelection(selectedHostUuid);
        }
    }

    private void showHostSelection(String focusUuid) {
        pendingHostPreparation = null;
        cancelOfflinePinAttempt();
        profileGateGeneration.incrementAndGet();
        clearPinEntry();
        profileGateHostUuid = null;
        if (profileGateView != null) profileGateView.setVisibility(View.GONE);
        hostSelectionVisible = true;
        if (consoleAudioEngine != null) {
            consoleAudioEngine.setHostSelectionVisible(true);
        }
        hostSelectionFocusUuid = focusUuid;
        stopAppListPoller();
        cancelPlayniteArtworkPrefetch();
        if (loadingLayer != null) loadingLayer.setVisibility(View.GONE);
        if (discordDmNotifications != null) {
            discordDmNotifications.setPresentationBlocked(discordDmHostToken, false);
        }
        if (homeLayer != null) homeLayer.setVisibility(View.GONE);
        if (hostSelectionLayer != null) hostSelectionLayer.setVisibility(View.VISIBLE);
        renderHostSelection();
        refreshVisibleHostProfiles();
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(156), dp(220));
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
        tile.addView(avatar, new LinearLayout.LayoutParams(dp(92), dp(92)));
        TextView label = text(getString(R.string.console_add_host_short),
                14, Color.WHITE, false);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        labelParams.topMargin = dp(10);
        tile.addView(label, labelParams);
        TextView hint = text("", 12, 0xFFC8D0DB, false);
        hint.setGravity(Gravity.CENTER);
        tile.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
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
                dp(84), dp(84), Gravity.CENTER);
        avatar.addView(artwork, artworkParams);
        TextView initials = text(hostInitials(host.name), 14, Color.WHITE, true);
        initials.setGravity(Gravity.CENTER);
        initials.setShadowLayer(dp(3), 0f, dp(1), 0xE0000000);
        avatar.addView(initials, match());
        styleHostSelectionAvatar(avatar, false, false);
        tile.addView(avatar, new LinearLayout.LayoutParams(dp(92), dp(92)));

        TextView name = text(host.name, 14, Color.WHITE, false);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        nameParams.topMargin = dp(10);
        tile.addView(name, nameParams);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER);
        statusRow.setTranslationY(-dp(4));
        View dot = new View(this);
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        TextView status = text("", 12, 0xFFC7CED8, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        statusParams.leftMargin = dp(4);
        statusRow.addView(status, statusParams);
        tile.addView(statusRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        TextView options = text(getString(R.string.console_host_options_hint),
                12, 0xFFDCE3ED, false);
        options.setGravity(Gravity.CENTER);
        options.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_overlay_window_menu, 0, 0, 0);
        options.setCompoundDrawablePadding(dp(2));
        options.setTranslationY(-dp(4));
        options.setVisibility(View.INVISIBLE);
        tile.addView(options, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));

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
        SessionSnapshot snapshot = resolveSessionSnapshot(host);
        ConsoleHostPresentation.State state = consoleHostState(host, snapshot);
        String status = hostStatus(host, snapshot);
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
        if (host.pairState != PairingManager.PairState.PAIRED) {
            pairHost(host);
            return;
        }
        resolveProfileGate(host, true, true);
    }

    private void resolveProfileGate(ComputerDetails host, boolean focusApps,
                                    boolean prepareHost) {
        if (host == null) return;
        int token = profileGateGeneration.incrementAndGet();
        profileGateHostUuid = host.uuid;
        stopAppListPoller();
        cancelPlayniteRequest();
        mainHandler.removeCallbacks(playniteRefreshCycle);
        String address = host.activeAddress == null ? null : host.activeAddress.address;
        GatewayConnection connection = hostGatewayStore.loadForHost(host.uuid, address);
        if (connection == null) {
            resolveProfileGate(host, focusApps, prepareHost, token);
            return;
        }
        executor.execute(() -> {
            HostGatewayClient.IntegrationProfiles profiles = null;
            try {
                profiles = hostGatewayClient.getIntegrationProfiles(connection);
            } catch (IOException | RuntimeException unavailable) {
                // The last authorized profile cache remains usable while Gateway is unavailable.
            }
            HostGatewayClient.IntegrationProfiles result = profiles;
            mainHandler.post(() -> {
                if (token != profileGateGeneration.get()) return;
                ComputerDetails current = currentHost(host.uuid);
                if (current == null) return;
                if (result != null) hostGatewayStore.saveProfiles(host.uuid, result);
                resolveProfileGate(current, focusApps, prepareHost, token);
            });
        });
    }

    private void resolveProfileGate(ComputerDetails host, boolean focusApps,
                                    boolean prepareHost, int token) {
        if (token != profileGateGeneration.get()) return;
        HostGatewayStore.ProfileGate gate = hostGatewayStore.profileGate(host.uuid);
        if (gate.clearAutomatic) {
            hostGatewayStore.setAutomaticIntegrationProfileId(host.uuid, "");
        }
        if (gate.showGate) {
            showProfileGate(host, focusApps, prepareHost);
            return;
        }
        if (gate.profile != null) {
            if (gate.profile.pinRequired) {
                showPinEntry(host, gate.profile, focusApps, prepareHost, true);
                return;
            }
            changeSelectedProfile(host, gate.profile.id);
        }
        enterHostAfterProfileGate(host, focusApps, prepareHost);
    }

    private void showProfileGate(ComputerDetails host, boolean focusApps,
                                 boolean prepareHost) {
        cancelOfflinePinAttempt();
        clearPinEntry();
        HostGatewayStore.ProfileSelection selection =
                hostGatewayStore.profileSelection(host.uuid);
        profileGateHostUuid = host.uuid;
        hostSelectionVisible = false;
        hostSelectionFocusUuid = host.uuid;
        if (hostSelectionLayer != null) hostSelectionLayer.setVisibility(View.GONE);
        if (homeLayer != null) homeLayer.setVisibility(View.GONE);
        profileGateView.show(host.name, selection.profiles,
                hostGatewayStore.automaticIntegrationProfileId(host.uuid),
                new ConsoleProfileGateView.Listener() {
                    @Override public void onProfileSelected(String profileId) {
                        if (!host.uuid.equals(profileGateHostUuid)) return;
                        HostGatewayClient.IntegrationProfile profile =
                                profileForGate(host.uuid, profileId);
                        if (profile == null) return;
                        if (profile.pinRequired) {
                            showPinEntry(host, profile, focusApps, prepareHost, true);
                        } else {
                            changeSelectedProfile(host, profileId);
                            enterHostAfterProfileGate(
                                    currentHost(host.uuid), focusApps, prepareHost);
                        }
                    }

                    @Override public void onAutomaticProfileToggled(String profileId) {
                        if (!host.uuid.equals(profileGateHostUuid)) return;
                        HostGatewayClient.IntegrationProfile profile =
                                profileForGate(host.uuid, profileId);
                        if (profile == null || profile.pinRequired) return;
                        String automatic = hostGatewayStore.automaticIntegrationProfileId(
                                host.uuid);
                        hostGatewayStore.setAutomaticIntegrationProfileId(host.uuid,
                                profileId.equals(automatic) ? "" : profileId);
                        showProfileGate(host, focusApps, prepareHost);
                    }
                });
        profileGateView.bringToFront();
    }

    private HostGatewayClient.IntegrationProfile profileForGate(
            String hostId, String profileId) {
        for (HostGatewayClient.IntegrationProfile profile
                : hostGatewayStore.profileSelection(hostId).profiles) {
            if (profile.id.equals(profileId)) return profile;
        }
        return null;
    }

    private void showPinEntry(ComputerDetails host,
                              HostGatewayClient.IntegrationProfile profile,
                              boolean focusApps, boolean prepareHost,
                              boolean completeHostEntry) {
        cancelOfflinePinAttempt();
        profileGateGeneration.incrementAndGet();
        clearPinEntry();
        profileGateHostUuid = host.uuid;
        pinProfileId = profile.id;
        pinFocusApps = focusApps;
        pinPrepareHost = prepareHost;
        pinCompleteHostEntry = completeHostEntry;
        if ((getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            pinSecureFlagAdded = true;
        }
        if (profileGateView != null) profileGateView.setVisibility(View.GONE);
        boolean playStationButtons = hasLastControllerInput
                ? lastControllerPlayStation : ControllerGlyphs.hasPlayStationController();
        pinEntryView.show(host.name, profile, playStationButtons,
                lastInputWasController && hasLastControllerInput, digit -> {
            if (pinProfileId == null || pinSubmitting
                    || SystemClock.elapsedRealtime() < pinCooldownUntil) return;
            ConsolePinEntry.Action action = pinEntry.appendDigit(digit);
            renderPinEntry("");
            if (action == ConsolePinEntry.Action.SUBMIT) submitProfilePin();
        });
        renderPinEntry("");
        pinEntryView.bringToFront();
    }

    private void submitProfilePin() {
        if (pinProfileId == null || pinSubmitting || pinEntry.length() != 4) return;
        long now = SystemClock.elapsedRealtime();
        if (now < pinCooldownUntil) {
            renderPinCooldown();
            return;
        }
        String hostId = profileGateHostUuid;
        String profileId = pinProfileId;
        int token = profileGateGeneration.get();
        String pin = pinEntry.takeAndClear();
        pinSubmitting = true;
        renderPinEntry("");
        ComputerDetails host = currentHost(hostId);
        String address = host == null || host.activeAddress == null
                ? null : host.activeAddress.address;
        GatewayConnection connection = hostGatewayStore.loadForHost(
                hostId, address, profileId);
        OfflinePinAttempt attempt = new OfflinePinAttempt(
                hostId, profileId, token, connection, host);
        offlinePinAttempt = attempt;
        renderPinEntry(getString(R.string.console_pin_checking));
        executor.execute(() -> {
            OfflineProfilePinFlow.Result result = OfflineProfilePinFlow.run(
                    hostId, connection, pin,
                    OfflineProfilePinFlow.cacheOf(hostGatewayStore.offlineProfilePinStore()),
                    new OfflineProfilePinFlow.Gateway() {
                        @Override public void verifyProfilePin(
                                GatewayConnection current, String enteredPin)
                                throws IOException {
                            mainHandler.post(() -> renderOfflinePinMessage(attempt,
                                    R.string.console_pin_checking));
                            hostGatewayClient.verifyProfilePin(current, enteredPin);
                        }

                        @Override public HostGatewayClient.IntegrationProfiles
                        getIntegrationProfiles(GatewayConnection current) throws IOException {
                            mainHandler.post(() -> renderOfflinePinMessage(attempt,
                                    R.string.console_pin_offline_waiting));
                            return hostGatewayClient.getIntegrationProfiles(current);
                        }
                    },
                    () -> {
                        if (!isOfflinePinAttemptCurrent(attempt)
                                || currentOfflinePinConnection(attempt) == null
                                || attempt.host == null) {
                            throw new IOException("Host unavailable");
                        }
                        mainHandler.post(() -> renderOfflinePinMessage(attempt,
                                R.string.console_pin_offline_waking));
                        WakeOnLanSender.sendWolPacket(attempt.host);
                    },
                    () -> currentOfflinePinConnection(attempt),
                    SystemClock::elapsedRealtime,
                    millis -> {
                        try {
                            Thread.sleep(millis);
                            return true;
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    },
                    () -> !isOfflinePinAttemptCurrent(attempt));
            mainHandler.post(() -> finishProfilePinFlow(attempt, result));
        });
    }

    private boolean isOfflinePinAttemptCurrent(OfflinePinAttempt attempt) {
        return attempt != null && offlinePinAttempt == attempt && !attempt.cancelled
                && attempt.gateToken == profileGateGeneration.get();
    }

    private GatewayConnection currentOfflinePinConnection(OfflinePinAttempt attempt) {
        if (attempt == null) return null;
        ComputerDetails current = managerBinder == null
                ? null : managerBinder.getComputer(attempt.hostId);
        if (managerBinder != null && current == null) return null;
        String address = current == null || current.activeAddress == null
                ? attempt.initialAddress : current.activeAddress.address;
        GatewayConnection connection = hostGatewayStore.loadForHost(
                attempt.hostId, address, attempt.profileId);
        return OfflineProfilePinFlow.samePairing(attempt.pairing, connection)
                ? connection : null;
    }

    private void cancelOfflinePinAttempt() {
        OfflinePinAttempt attempt = offlinePinAttempt;
        if (attempt != null) attempt.cancelled = true;
        offlinePinAttempt = null;
    }

    private void renderOfflinePinMessage(OfflinePinAttempt attempt, int message) {
        if (!isOfflinePinAttemptCurrent(attempt)) return;
        renderPinEntry(getString(message));
    }

    private void finishProfilePinFlow(OfflinePinAttempt attempt,
                                      OfflineProfilePinFlow.Result result) {
        if (!isOfflinePinAttemptCurrent(attempt)
                || !ConsolePinEntry.matchesResult(attempt.gateToken, attempt.hostId,
                attempt.profileId, profileGateGeneration.get(), profileGateHostUuid,
                pinProfileId)) return;
        if (currentOfflinePinConnection(attempt) == null) {
            attempt.cancelled = true;
            offlinePinAttempt = null;
            pinSubmitting = false;
            pinEntry.clear();
            renderPinEntry(getString(R.string.console_pin_failed));
            return;
        }
        offlinePinAttempt = null;
        pinSubmitting = false;
        pinEntry.clear();
        if (result == null) {
            renderPinEntry(getString(R.string.console_pin_failed));
            return;
        }
        if (result.profiles != null) {
            hostGatewayStore.saveProfiles(attempt.hostId, result.profiles);
        }
        switch (result.status) {
            case VERIFIED:
                finishProfilePinVerification(attempt.hostId, attempt.profileId,
                        attempt.gateToken, null, null);
                return;
            case INVALID:
                finishOfflinePinFailure(attempt.gateToken, attempt.profileId,
                        result.retryAfterSeconds, getString(R.string.console_pin_invalid));
                return;
            case COOLDOWN:
                finishOfflinePinFailure(attempt.gateToken, attempt.profileId,
                        result.retryAfterSeconds, null);
                return;
            case MISSING:
                renderPinEntry(getString(OfflineProfilePinStore.supportsOfflineValidation(
                        Build.VERSION.SDK_INT)
                        ? R.string.console_pin_offline_connect_once
                        : R.string.console_pin_offline_unsupported));
                return;
            case PROFILE_DENIED:
                ComputerDetails current = currentHost(attempt.hostId);
                if (current != null) {
                    showProfileGate(current, pinFocusApps, pinPrepareHost);
                }
                return;
            case CANCELLED:
                renderPinEntry("");
                return;
            case TIMEOUT:
                renderPinEntry(getString(R.string.console_pin_offline_unavailable));
                return;
            case UNAVAILABLE:
            default:
                renderPinEntry(getString(R.string.console_pin_failed));
        }
    }

    private void finishOfflinePinFailure(int token, String profileId, int retryAfterSeconds,
                                         String message) {
        int seconds = Math.max(1, retryAfterSeconds);
        pinCooldownUntil = SystemClock.elapsedRealtime() + seconds * 1_000L;
        if (message != null) {
            renderPinEntry(message);
            mainHandler.postDelayed(() -> {
                if (token == profileGateGeneration.get()
                        && profileId.equals(pinProfileId)) renderPinCooldown();
            }, PIN_INVALID_FEEDBACK_MS);
        } else {
            renderPinCooldown();
        }
    }

    private void finishProfilePinVerification(String hostId, String profileId, int token,
                                              HostGatewayClient.GatewayException gatewayError,
                                              IOException transportError) {
        if (!ConsolePinEntry.matchesResult(token, hostId, profileId,
                profileGateGeneration.get(), profileGateHostUuid, pinProfileId)) return;
        pinSubmitting = false;
        pinEntry.clear();
        if (gatewayError == null && transportError == null) {
            ComputerDetails host = currentHost(hostId);
            HostGatewayClient.IntegrationProfile profile =
                    profileForGate(hostId, profileId);
            if (host == null || profile == null || !profile.pinRequired) return;
            if (pinCompleteHostEntry) {
                changeSelectedProfile(host, profileId);
                enterHostAfterProfileGate(host, pinFocusApps, pinPrepareHost);
            } else {
                profileGateGeneration.incrementAndGet();
                clearPinEntry();
                profileGateHostUuid = null;
                applySelectedProfile(host, profileId);
            }
            return;
        }
        String reason = gatewayError == null ? "" : gatewayError.getMessage();
        int seconds = ConsolePinEntry.retryDelaySeconds(reason,
                gatewayError == null ? 0 : gatewayError.retryAfterSeconds);
        if (seconds > 0) {
            pinCooldownUntil = SystemClock.elapsedRealtime() + seconds * 1_000L;
            if ("invalid_pin".equals(reason)) {
                renderPinEntry(getString(R.string.console_pin_invalid));
                mainHandler.postDelayed(() -> {
                    if (token == profileGateGeneration.get()
                            && profileId.equals(pinProfileId)) renderPinCooldown();
                }, PIN_INVALID_FEEDBACK_MS);
            } else {
                renderPinCooldown();
            }
        } else {
            renderPinEntry(getString(R.string.console_pin_failed));
        }
    }

    private void renderPinCooldown() {
        long remaining = Math.max(0L, pinCooldownUntil - SystemClock.elapsedRealtime());
        if (remaining == 0L) {
            pinCooldownUntil = 0L;
            renderPinEntry("");
            return;
        }
        int seconds = (int) ((remaining + 999L) / 1_000L);
        renderPinEntry(getString(R.string.console_pin_cooldown, seconds));
        int token = profileGateGeneration.get();
        String profileId = pinProfileId;
        mainHandler.postDelayed(() -> {
            if (token == profileGateGeneration.get()
                    && profileId != null && profileId.equals(pinProfileId)) {
                renderPinCooldown();
            }
        }, Math.min(1_000L, remaining));
    }

    private void renderPinEntry(String message) {
        if (pinEntryView != null && pinProfileId != null) {
            pinEntryView.render(pinEntry.length(), message, !pinSubmitting
                    && SystemClock.elapsedRealtime() >= pinCooldownUntil);
        }
    }

    private void clearPinEntry() {
        pinEntry.clear();
        pinProfileId = null;
        pinSubmitting = false;
        pinCooldownUntil = 0L;
        pinCompleteHostEntry = false;
        if (pinEntryView != null) pinEntryView.setVisibility(View.GONE);
        if (pinSecureFlagAdded) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            pinSecureFlagAdded = false;
        }
    }

    private void enterHostAfterProfileGate(ComputerDetails host, boolean focusApps,
                                           boolean prepareHost) {
        if (host == null) return;
        cancelOfflinePinAttempt();
        profileGateGeneration.incrementAndGet();
        clearPinEntry();
        profileGateHostUuid = null;
        if (profileGateView != null) profileGateView.setVisibility(View.GONE);
        newlyDiscoveredHosts.remove(host.uuid);
        selectHost(host, focusApps);
        if (prepareHost) prepareSelectedHost(host);
    }

    private void closeProfileGate() {
        String hostId = profileGateHostUuid;
        clearPinEntry();
        showHostSelection(hostId);
    }

    private void prepareSelectedHost(ComputerDetails host) {
        if (!active) return;
        if (sessionOrchestrator != null
                && ConsoleActionCatalog.canPrepareHost(host)
                && HostAutoWarmUpPreferences.isEnabled(preferences, host.uuid)) {
            if (managerBinder == null) {
                pendingHostPreparation = host.uuid;
                return;
            }
            pendingHostPreparation = null;
            long attempt = sessionOrchestrator.prepareHost(host.uuid,
                    selectedProfileId(host.uuid));
            warmUpClientHostId = normalizeId(host.uuid);
            if (attempt > 0L) {
                warmUpClientAttempt = attempt;
                warmUpStatus = WARM_UP_PREPARING;
            } else {
                warmUpClientAttempt = 0L;
                warmUpStatus = WARM_UP_ERROR;
            }
            renderHosts();
            updateHostSelector();
        }
    }

    private void dispatchPendingHostPreparation() {
        String hostId = pendingHostPreparation;
        pendingHostPreparation = null;
        if (active && hostId != null && !hostSelectionVisible && hostId.equals(selectedHostUuid)) {
            prepareSelectedHost(currentHost(hostId));
        }
    }

    private void armPendingWarmUpRelay() {
        if (!retainedStreamHome || warmUpRelayDispatched || warmUpClientAttempt <= 0L) return;
        String gameId = normalizeId(getIntent().getStringExtra(
                EXTRA_WARM_UP_PENDING_GAME_ID));
        String gameName = getIntent().getStringExtra(EXTRA_WARM_UP_PENDING_GAME_NAME);
        if (gameId.isEmpty() || gameName == null || gameName.trim().isEmpty()) return;
        String artworkId = getIntent().getStringExtra(EXTRA_WARM_UP_PENDING_ARTWORK_ID);
        String quickLaunchId = getIntent().getStringExtra(EXTRA_WARM_UP_PENDING_QUICK_LAUNCH);
        if (getIntent().hasExtra(EXTRA_WARM_UP_PENDING_REQUIRES_CONNECTOR)
                && getIntent().hasExtra(EXTRA_WARM_UP_PENDING_NEUTRAL_STREAM)
                && getIntent().hasExtra(EXTRA_WARM_UP_PENDING_START_BEFORE_STREAM)) {
            pendingWarmUpRelay = PlayIntent.playniteGame(
                    retainedStreamHostId, retainedStreamProfileId,
                    retainedStreamAppId, gameName, false, gameId,
                    artworkId, quickLaunchId,
                    getIntent().getBooleanExtra(
                            EXTRA_WARM_UP_PENDING_REQUIRES_CONNECTOR, true),
                    getIntent().getBooleanExtra(EXTRA_WARM_UP_PENDING_NEUTRAL_STREAM, false),
                    getIntent().getBooleanExtra(
                            EXTRA_WARM_UP_PENDING_START_BEFORE_STREAM, false));
        } else {
            pendingWarmUpRelay = providerGameIntent(
                    retainedStreamHostId, retainedStreamProfileId,
                    retainedStreamAppId, gameName, false, gameId,
                    artworkId, quickLaunchId);
        }
    }

    protected final void acceptPreparingHomeFrame() {
        warmUpHomeFrameAccepted = true;
        if (pendingWarmUpRelay == null) return;
        ComputerDetails host = currentHost(retainedStreamHostId);
        showLoading(host == null ? retainedStreamHostId : host.name,
                pendingWarmUpRelay.appName,
                LaunchTransitionType.GAME,
                cachedLoadingArtworkPath(retainedStreamHostId,
                        pendingWarmUpRelay.profileId,
                        pendingWarmUpRelay.loadingArtworkGameId));
        dispatchPendingWarmUpRelay();
    }

    private void dispatchPendingWarmUpRelay() {
        if (!warmUpHomeFrameAccepted || managerBinder == null || sessionOrchestrator == null
                || pendingWarmUpRelay == null || warmUpRelayDispatched) return;
        if (isPendingWarmUpRelayOwnedOrCompleted(pendingWarmUpRelay.playniteGameId)) {
            clearPendingWarmUpRelay();
            return;
        }
        if (warmUpRelaySubmitted) return;
        RetainedStreamSessionCoordinator.Snapshot preparing =
                RetainedStreamSessionCoordinator.snapshot();
        boolean exact = preparing.state == RetainedStreamSessionCoordinator.State.PREPARING
                && preparing.attempt == warmUpClientAttempt
                && !preparing.transitionId.isEmpty()
                && preparing.transitionId.equals(normalizeId(getIntent().getStringExtra(
                EXTRA_WARM_UP_TRANSITION_ID)))
                && preparing.streamSessionId.equals(retainedStreamSessionId)
                && preparing.hostId.equalsIgnoreCase(retainedStreamHostId)
                && preparing.appId == retainedStreamAppId;
        if (!exact) {
            clearPendingWarmUpRelay();
            warmUpStatus = WARM_UP_ERROR;
            renderHosts();
            updateHostSelector();
            showHome();
            return;
        }
        PlayIntent relay = pendingWarmUpRelay;
        warmUpRelaySubmitted = true;
        sessionOrchestrator.play(relay);
    }

    private boolean isPendingWarmUpRelayOwnedOrCompleted(String gameId) {
        if (RetainedStreamSessionCoordinator.isPreparingSwitchOwned(
                retainedStreamSessionId, retainedStreamHostId, retainedStreamAppId,
                normalizeId(getIntent().getStringExtra(EXTRA_WARM_UP_TRANSITION_ID)),
                warmUpClientAttempt)) {
            return true;
        }
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        return (retained.state == RetainedStreamSessionCoordinator.State.PREPARING
                || retained.state == RetainedStreamSessionCoordinator.State.HOME_LIVE
                || retained.state == RetainedStreamSessionCoordinator.State.PARKED_LIVE)
                && retained.streamSessionId.equals(retainedStreamSessionId)
                && retained.hostId.equalsIgnoreCase(retainedStreamHostId)
                && retained.appId == retainedStreamAppId
                && retained.playniteGameId.equalsIgnoreCase(normalizeId(gameId));
    }

    private void clearPendingWarmUpRelay() {
        pendingWarmUpRelay = null;
        warmUpRelayDispatched = true;
        warmUpRelaySubmitted = false;
        getIntent().removeExtra(EXTRA_WARM_UP_PENDING_GAME_ID);
        getIntent().removeExtra(EXTRA_WARM_UP_PENDING_GAME_NAME);
        getIntent().removeExtra(EXTRA_WARM_UP_PENDING_ARTWORK_ID);
        getIntent().removeExtra(EXTRA_WARM_UP_PENDING_QUICK_LAUNCH);
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
        expandedGameFacts = text("", 12, 0xFFB8C9DC, false);
        expandedGameFacts.setMaxLines(3);
        expandedGameDescription = text("", 12, 0xFFE0E5EA, false);
        expandedGameDescription.setLineSpacing(0, 1.25f);
        expandedGameDescription.setPadding(0, 0, 0, dp(20));
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
        expandedFilterButton.setPadding(dp(16), dp(6), dp(16), dp(6));
        expandedFilterButton.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_console_filter, 0, 0, 0);
        expandedFilterButton.setCompoundDrawablePadding(dp(9));
        expandedFilterButton.setOnClickListener(view ->
                showPlayniteFilterSelector(expandedFilterButton));
        expandedFilterButton.setOnFocusChangeListener((view, focused) ->
        {
            styleFilterButton(expandedFilterButton, focused);
            if (focused) stopExpandedDescriptionAutoScroll();
        });
        LinearLayout.LayoutParams expandedFilterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        expandedFilterParams.rightMargin = dp(10);
        expandedLibraryHeader.addView(expandedFilterButton, expandedFilterParams);

        expandedSourceFilterButton = text(getString(R.string.playnite_sources_all),
                11, 0xFFD7E4EA, true);
        expandedSourceFilterButton.setId(View.generateViewId());
        expandedSourceFilterButton.setFocusable(true);
        expandedSourceFilterButton.setClickable(true);
        expandedSourceFilterButton.setGravity(Gravity.CENTER);
        expandedSourceFilterButton.setMinHeight(dp(42));
        expandedSourceFilterButton.setPadding(dp(16), dp(6), dp(16), dp(6));
        expandedSourceFilterButton.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_console_platforms, 0, 0, 0);
        expandedSourceFilterButton.setCompoundDrawablePadding(dp(9));
        expandedSourceFilterButton.setOnClickListener(view ->
                showPlayniteSourceSelector(expandedSourceFilterButton));
        expandedSourceFilterButton.setOnFocusChangeListener((view, focused) -> {
            styleSourceFilterButton(focused);
            if (focused) stopExpandedDescriptionAutoScroll();
        });
        LinearLayout.LayoutParams expandedSourceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        expandedSourceParams.rightMargin = dp(10);
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
        sortParams.rightMargin = dp(10);
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
        addNavigationLegendItem(ControllerGlyphs.text(
                        playStation, ControllerGlyphs.Button.RIGHT_STICK),
                getString(R.string.playnite_legend_filters));
        addNavigationLegendItem(ControllerGlyphs.text(
                        playStation, ControllerGlyphs.Button.LEFT_STICK),
                getString(R.string.playnite_legend_reset));
        addNavigationLegendItem(ControllerGlyphs.text(
                        playStation, ControllerGlyphs.Button.NORTH),
                getString(R.string.playnite_legend_search));
        addNavigationLegendItem(ControllerGlyphs.text(
                        playStation, ControllerGlyphs.Button.CANCEL),
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
        hostSelectionLegend.removeAllViews();
        if (controllers.isEmpty()) {
            addHostSelectionLegendTextItem("OK", getString(R.string.console_select_hint));
        } else {
            addHostSelectionLegendItem(ControllerGlyphs.text(
                    playStation, ControllerGlyphs.Button.CONFIRM),
                    getString(R.string.console_select_hint));
        }
        addHostSelectionLegendItem(ControllerGlyphs.text(
                        playStation, ControllerGlyphs.Button.MENU),
                getString(R.string.console_host_options_label));
    }

    private void addHostSelectionLegendItem(String glyph, String label) {
        addLegendSeparator(hostSelectionLegend, 18, 9);
        TextView button = controllerGlyph(glyph, 23);
        hostSelectionLegend.addView(button, new LinearLayout.LayoutParams(dp(23), dp(23)));
        addLegendDescription(hostSelectionLegend, label, 13, 5);
    }

    private void addHostSelectionLegendTextItem(String glyph, String label) {
        addLegendSeparator(hostSelectionLegend, 18, 9);
        hostSelectionLegend.addView(legendTextButton(glyph),
                new LinearLayout.LayoutParams(dp(23), dp(23)));
        addLegendDescription(hostSelectionLegend, label, 13, 5);
    }

    private void addNavigationLegendItem(String glyph, String label) {
        addLegendSeparator(expandedNavigationLegend, 20, 10);
        TextView button = controllerGlyph(glyph, 25);
        expandedNavigationLegend.addView(button, new LinearLayout.LayoutParams(dp(25), dp(25)));
        addLegendDescription(expandedNavigationLegend, label, 10, 6);
    }

    private void addLegendSeparator(LinearLayout container, int height, int margin) {
        if (container.getChildCount() > 0) {
            View separator = new View(this);
            separator.setBackgroundColor(0x336E8291);
            LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(
                    dp(1), dp(height));
            separatorParams.setMargins(dp(margin), 0, dp(margin), 0);
            container.addView(separator, separatorParams);
        }
    }

    private TextView legendTextButton(String glyph) {
        TextView button = text(glyph, 12, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0xFF26343E);
        background.setStroke(dp(1), 0xBFE8F1F5);
        button.setBackground(background);
        return button;
    }

    private TextView controllerGlyph(String glyph, int size) {
        TextView button = text(glyph, size - 3, Color.WHITE, false);
        button.setTypeface(ControllerGlyphs.typeface(this));
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private void addLegendDescription(LinearLayout container, String label,
                                      int textSize, int leftMargin) {
        TextView description = text(label, textSize, 0xFFD7E4EA, true);
        LinearLayout.LayoutParams descriptionParams = wrapLinear();
        descriptionParams.leftMargin = dp(leftMargin);
        container.addView(description, descriptionParams);
    }

    private TextView expandedHeaderButton(String label, int icon) {
        TextView button = text(label, 11, 0xFFD7E4EA, true);
        button.setId(View.generateViewId());
        button.setFocusable(true);
        button.setClickable(true);
        button.setSoundEffectsEnabled(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(42));
        button.setPadding(dp(16), dp(6), dp(16), dp(6));
        button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(9));
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
        sidePanelScroll = new LockableScrollView(this);
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
        // Community is a full-screen, bounded composition with its own feed/detail scrollers.
        // Keeping it out of the generic ScrollView prevents the modal root from stealing focus
        // and prevents the fixed header/footer from being measured as scrollable content.
        communityPanelHost = new FrameLayout(this);
        communityPanelHost.setVisibility(View.GONE);
        communityPanelHost.setFocusable(false);
        modalLayer.addView(communityPanelHost, match());
        sideDialog = new android.app.Dialog(this);
        sideDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sideDialog.setContentView(modalLayer);
        sideDialog.setCanceledOnTouchOutside(false);
        sideDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (discordDmShortcut != null && discordDmShortcut.handle(event)) return true;
            if ("discord.community".equals(currentPanelKey)
                    && discordSocialPanelController != null
                    && discordSocialPanelController.handleCommunityBack(event)) {
                return true;
            }
            if ("discord.community".equals(currentPanelKey)
                    && discordSocialPanelController != null
                    && discordSocialPanelController.handleCommunityKey(event)) {
                return true;
            }
            if (handleVolumeAdjustment(event, sideDialog.getCurrentFocus())) return true;
            if ((keyCode == KeyEvent.KEYCODE_BACK || ("discord.community".equals(currentPanelKey)
                    && keyCode == KeyEvent.KEYCODE_BUTTON_B)) && event.getAction() == KeyEvent.ACTION_UP) {
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
        if (!hostSelectionVisible && profileGateHostUuid == null && selected != null
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
            updateHostPowerLabel();
            hostSelector.setContentDescription(getString(
                    R.string.console_host_status_description, host.name,
                    compactHostStatus(host)));
            hostSelector.setEnabled(true);
            hostSelector.setAlpha(1f);
        }
        updateProfileSelector(host);
        updateQuickResumeButton(host);
    }

    private String selectedProfileId(String hostId) {
        return hostGatewayStore == null ? GatewayConnection.DEFAULT_PROFILE_ID
                : hostGatewayStore.selectedIntegrationProfileId(hostId);
    }

    private HostProfileKey selectedProfileKey(String hostId) {
        return new HostProfileKey(hostId, selectedProfileId(hostId));
    }

    private String profileStateKey(String hostId) {
        return selectedProfileKey(hostId).cacheKey();
    }

    private void updateProfileSelector(ComputerDetails host) {
        if (profileSelector == null) return;
        HostGatewayStore.ProfileSelection selection = host == null ? null
                : hostGatewayStore.profileSelection(host.uuid);
        if (selection == null || selection.selected == null
                || !selection.showSelector) {
            profileSelector.setVisibility(View.GONE);
            if (selection != null && selection.selected != null
                    && !selection.selected.pinRequired) {
                hostGatewayStore.setSelectedIntegrationProfileId(
                        host.uuid, selection.selected.id);
            }
            return;
        }
        profileSelector.setText(getString(R.string.console_profile_selector,
                selection.selected.name));
        profileSelector.setContentDescription(getString(
                R.string.console_profile_selector_description,
                selection.selected.name));
        profileSelector.setVisibility(View.VISIBLE);
        profileSelector.setEnabled(true);
    }

    private void showProfileSelection(ComputerDetails host) {
        if (host == null) return;
        HostGatewayStore.ProfileSelection selection =
                hostGatewayStore.profileSelection(host.uuid);
        if (!selection.showSelector) return;
        List<View> actions = new ArrayList<>();
        for (HostGatewayClient.IntegrationProfile profile : selection.profiles) {
            TextView action = panelAction(profile.name);
            action.setContentDescription(getString(
                    R.string.console_profile_selector_description, profile.name));
            action.setOnClickListener(view -> {
                selectProfile(host, profile.id);
                hideSidePanel();
            });
            actions.add(action);
        }
        showSidePanel(getString(R.string.console_profile_eyebrow),
                getString(R.string.gateway_profiles_title),
                getString(R.string.gateway_profile_details),
                actions.toArray(new View[0]));
    }

    private void selectProfile(ComputerDetails host, String profileId) {
        HostGatewayClient.IntegrationProfile profile =
                profileForGate(host == null ? null : host.uuid, profileId);
        if (profile == null) return;
        if (profile.pinRequired) {
            profileGateHostUuid = host.uuid;
            showPinEntry(host, profile, true, false, false);
            return;
        }
        applySelectedProfile(host, profileId);
    }

    private void applySelectedProfile(ComputerDetails host, String profileId) {
        if (!changeSelectedProfile(host, profileId)) return;
        currentSunshineApps = loadApps(host);
        updateHostSelector();
        refreshHostProfiles(host);
        refreshDiscordIndicator();
        loadPlayniteForHost(host);
        resolveActivePlayniteGame(host, host.runningGameId != 0);
    }

    private boolean changeSelectedProfile(ComputerDetails host, String profileId) {
        if (host == null) return false;
        String selected = GatewayConnection.normalizeProfileId(profileId);
        if (selected.equals(selectedProfileId(host.uuid))) return false;
        cancelWindowsProfileSwitch(false);
        if (sessionOrchestrator != null) sessionOrchestrator.cancel();
        hostGatewayStore.setSelectedIntegrationProfileId(host.uuid, selected);
        profileGeneration.incrementAndGet();
        cancelPlayniteRequest();
        cancelPlayniteArtworkPrefetch();
        gameOperationsController.close();
        gameOperationsController = new GameOperationsController(
                hostGatewayClient, executor, mainHandler::post);
        currentPlayniteGames = Collections.emptyList();
        currentSunshineApps = Collections.emptyList();
        currentPlayniteHostUuid = null;
        renderedAppsSignature = null;
        return true;
    }

    private void refreshHostProfiles(ComputerDetails host) {
        if (host == null) return;
        String address = host.activeAddress == null ? null : host.activeAddress.address;
        GatewayConnection connection = hostGatewayStore.loadForHost(host.uuid, address);
        if (connection == null) return;
        int token = profileGeneration.incrementAndGet();
        String hostId = host.uuid;
        executor.execute(() -> {
            HostGatewayClient.IntegrationProfiles profiles;
            try {
                profiles = hostGatewayClient.getIntegrationProfiles(connection);
            } catch (IOException | RuntimeException unavailable) {
                return;
            }
            mainHandler.post(() -> {
                if (token != profileGeneration.get() || !hostId.equals(selectedHostUuid)) return;
                String previous = selectedProfileId(hostId);
                hostGatewayStore.saveProfiles(hostId, profiles);
                HostGatewayStore.ProfileSelection selection =
                        hostGatewayStore.profileSelection(hostId);
                if (selection.selected != null
                        && !previous.equals(selection.selected.id)) {
                    selectProfile(currentHost(hostId), selection.selected.id);
                } else {
                    updateProfileSelector(currentHost(hostId));
                    updateHostPowerLabel();
                    updateHostSelectionTile(currentHost(hostId));
                }
            });
        });
    }

    private void refreshVisibleHostProfiles() {
        int generation = hostProfileRefreshGeneration.incrementAndGet();
        for (ComputerDetails host : hosts.values()) {
            if (!newlyDiscoveredHosts.contains(host.uuid)) {
                hostProfileRefreshGenerations.put(host.uuid, generation);
                refreshVisibleHostProfile(host, generation);
            }
        }
    }

    private void refreshVisibleHostProfile(ComputerDetails host, int generation) {
        if (host == null || host.state != ComputerDetails.State.ONLINE
                || host.pairState != PairingManager.PairState.PAIRED) return;
        String address = host.activeAddress == null ? null : host.activeAddress.address;
        GatewayConnection connection = hostGatewayStore.loadForHost(host.uuid, address);
        if (connection == null || !hostProfileRefreshInFlight.add(host.uuid)) return;
        String hostId = host.uuid;
        executor.execute(() -> {
            HostGatewayClient.IntegrationProfiles profiles = null;
            try {
                profiles = hostGatewayClient.getIntegrationProfiles(connection);
            } catch (IOException | RuntimeException unavailable) {
                // Keep the last cached presentation when this read-only refresh is unavailable.
            }
            HostGatewayClient.IntegrationProfiles result = profiles;
            mainHandler.post(() -> {
                hostProfileRefreshInFlight.remove(hostId);
                Integer latest = hostProfileRefreshGenerations.get(hostId);
                if (latest == null || latest != generation || !hostSelectionVisible) {
                    if (latest != null && hostSelectionVisible) {
                        refreshVisibleHostProfile(hosts.get(hostId), latest);
                    }
                    return;
                }
                ComputerDetails current = hosts.get(hostId);
                if (result != null && current != null) {
                    hostGatewayStore.saveProfiles(hostId, result);
                    updateHostSelectionTile(current);
                }
            });
        });
    }

    private void updateHostPowerLabel() {
        if (hostSelector == null) return;
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) return;
        SessionSnapshot snapshot = resolveSessionSnapshot(host);
        ConsoleHostPresentation.State state = consoleHostState(host, snapshot);
        int stateColor = ConsoleHostPresentation.color(state);
        Drawable hostIcon = getResources().getDrawable(
                R.drawable.ic_console_host_power).mutate();
        hostIcon.setTint(stateColor);
        hostIcon.setBounds(0, 0, dp(24), dp(24));
        hostSelector.setCompoundDrawables(hostIcon, null, null, null);
        String value = getString(R.string.console_host_status,
                host.name, compactHostStatus(host));
        hostSelector.setText(value);
    }

    private String compactHostStatus(ComputerDetails host) {
        SessionSnapshot snapshot = resolveSessionSnapshot(host);
        if (snapshot.isSuspended()) {
            SuspendedSessionStore.Session suspended =
                    SuspendedSessionStore.load(this, host.uuid, selectedProfileId(host.uuid));
            String title = suspended == null || suspended.title.isEmpty()
                    ? getString(R.string.console_game) : suspended.title;
            if (host.state != ComputerDetails.State.ONLINE) {
                return getString(R.string.console_status_suspended_short, title);
            }
            return !snapshot.suspensionSleepObserved
                    && !hostStateController.isWaking(host.uuid)
                    ? getString(R.string.console_status_suspending_short)
                    : getString(R.string.console_status_suspended_short, title);
        }
        return hostStatus(host, snapshot);
    }

    private void updateQuickResumeButton(ComputerDetails host) {
        if (quickResumeButton == null) return;
        SessionSnapshot snapshot = resolveSessionSnapshot(host);
        boolean activeSession = host != null && ConsoleActionCatalog.isOnline(host)
                && ConsoleActionCatalog.isPaired(host) && snapshot.hasActiveSession();
        boolean visible = activeSession && !CONSOLE_UI_V2;
        if (CONSOLE_UI_V2 && host != null) {
            PlayniteIdentityResolutionPolicy.Action resolutionAction =
                    PlayniteIdentityResolutionPolicy.decide(
                            ConsoleActionCatalog.isOnline(host),
                            ConsoleActionCatalog.isPaired(host),
                            snapshot.state);
            if (resolutionAction == PlayniteIdentityResolutionPolicy.Action.REQUEST) {
                resolveActivePlayniteGame(host, true);
            } else if (resolutionAction == PlayniteIdentityResolutionPolicy.Action.CLEAR) {
                resolveActivePlayniteGame(host, false);
            } else if (ConsoleActionCatalog.isOnline(host)
                    && ConsoleActionCatalog.isPaired(host)) {
                resolveActivePlayniteGame(host, activeSession);
            }
        }
        boolean restoreFocus = quickResumeButton.hasFocus() && !visible;
        quickResumeButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        quickResumeButton.setEnabled(visible);
        if (visible) {
            String appName = findAppName(host, snapshot.hostGameAppId);
            quickResumeButton.setText(appName == null
                    ? getString(R.string.console_quick_resume)
                    : getString(R.string.console_quick_resume_app, appName));
            quickResumeButton.setContentDescription(getString(
                    R.string.console_quick_resume_description,
                    appName == null ? getString(R.string.console_status_active_session) : appName));
        } else if (restoreFocus) {
            View fallback = firstFocusableChild(quickActions);
            View target = fallback != null ? fallback : hostSelector;
            target.post(target::requestFocus);
        }
        updateLibraryActionVisibility();
        wireHomeFocusNavigation();
    }

    private void resolveActivePlayniteGame(ComputerDetails host, boolean activeSession) {
        if (host == null) return;
        resolveActivePlayniteGame(host, selectedProfileKey(host.uuid), activeSession, null);
    }

    private void resolveActivePlayniteGame(ComputerDetails host, boolean activeSession,
                                           Consumer<Boolean> completion) {
        if (host == null) return;
        resolveActivePlayniteGame(host, selectedProfileKey(host.uuid), activeSession,
                completion);
    }

    private void resolveActivePlayniteGame(ComputerDetails host, HostProfileKey requestKey,
                                           boolean activeSession,
                                           Consumer<Boolean> completion) {
        if (host == null) return;
        String stateKey = requestKey.cacheKey();
        if (!activeSession) {
            boolean changed = activePlayniteGameIds.remove(stateKey) != null;
            changed |= activePlayniteGameStates.remove(stateKey) != null;
            activePlayniteGameAppIds.remove(stateKey);
            activePlayniteGameResolvedAt.remove(stateKey);
            boolean visibleResumeMarker = host.uuid.equals(selectedHostUuid)
                    && !resumePlayniteGameId.isEmpty();
            if ((changed || visibleResumeMarker) && host.uuid.equals(selectedHostUuid)) {
                android.util.Log.i("MoonWakerSession",
                        "Clearing resume marker because Sunshine reports no active session"
                                + " host=" + host.uuid + " game=" + resumePlayniteGameId);
                renderPlayniteLibrary(host, currentSunshineApps);
            }
        }
        if (!ConsoleActionCatalog.isOnline(host) || !ConsoleActionCatalog.isPaired(host)) {
            RunningGameObservation observation = runningGameObservations.get(stateKey);
            if (observation != null) runningGameObservations.put(stateKey,
                    new RunningGameObservation(observation.inventory, observation.connection, 0L));
            invalidateActivePlayniteGameRequest(host.uuid);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (completion != null) invalidateActivePlayniteGameRequest(host.uuid);
        if ((completion == null && !activeGameRefreshDue(now,
                activePlayniteGameRequestedAt.getOrDefault(stateKey, 0L)))
                || !activePlayniteGameResolutionInFlight.add(stateKey)) return;
        activePlayniteGameRequestedAt.put(stateKey, now);
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        GatewayConnection connection =
                hostGatewayStore.loadForHost(host.uuid, address, requestKey.profileId);
        if (connection == null) {
            activePlayniteGameResolutionInFlight.remove(stateKey);
            if (completion != null) completion.accept(false);
            return;
        }
        int expectedRunningGameId = host.runningGameId;
        int requestGeneration = ++activePlayniteGameRequestGeneration;
        activePlayniteGameRequestGenerations.put(stateKey, requestGeneration);
        String expectedHostId = host.uuid;
        executor.execute(() -> {
            String gameId = "";
            String gameState = "";
            boolean succeeded = false;
            HostGatewayClient.RunningGames inventory = null;
            try {
                HostGatewayClient.PlayniteCurrentGame current =
                        hostGatewayClient.getPlayniteCurrentGame(connection);
                gameState = SessionSnapshot.normalize(current.state);
                inventory = current.runningGames;
                if ("running".equalsIgnoreCase(current.state)
                        && HostGatewayClient.isPlayniteId(current.id)) {
                    gameId = current.id;
                }
                succeeded = true;
            } catch (IOException ignored) {
                // Keep the last verified Bridge fact; a transport failure is not idle.
            }
            String resolvedGameId = gameId;
            String resolvedGameState = gameState;
            boolean requestSucceeded = succeeded;
            HostGatewayClient.RunningGames resolvedInventory = inventory;
            mainHandler.post(() -> {
                Integer currentGeneration = activePlayniteGameRequestGenerations.get(
                        stateKey);
                if (currentGeneration != null && currentGeneration == requestGeneration) {
                    activePlayniteGameResolutionInFlight.remove(stateKey);
                }
                ComputerDetails latestHost = currentHost(expectedHostId);
                GatewayConnection latestConnection = latestHost == null ? null
                        : hostGatewayStore.loadForHost(expectedHostId,
                        latestHost.activeAddress == null ? null : latestHost.activeAddress.address,
                        requestKey.profileId);
                boolean acceptsInventory = currentGeneration != null
                        && currentGeneration == requestGeneration
                        && latestHost != null && expectedHostId.equals(latestHost.uuid)
                        && requestKey.equals(selectedProfileKey(expectedHostId))
                        && sameGatewayProfile(connection, latestConnection);
                if (requestSucceeded && acceptsInventory) {
                    runningGameObservations.put(stateKey, new RunningGameObservation(
                            resolvedInventory, connection, SystemClock.uptimeMillis()));
                }
                if (acceptsInventory) refreshRunningGamePresentation(latestHost);
                if (!activeSession) {
                    if (completion != null) completion.accept(requestSucceeded && acceptsInventory);
                    return;
                }
                int latestAppId = latestHost == null ? -1 : latestHost.runningGameId;
                if (!PlayniteIdentityResolutionPolicy.acceptsResponse(
                        expectedHostId, requestGeneration, expectedRunningGameId,
                        latestHost == null ? "" : latestHost.uuid,
                        currentGeneration == null ? -1 : currentGeneration,
                        latestAppId, ConsoleActionCatalog.isOnline(latestHost),
                        ConsoleActionCatalog.isPaired(latestHost))) {
                    android.util.Log.i("MoonWakerSession",
                            "Discarding stale Playnite session response host=" + expectedHostId
                                    + " expectedApp=" + expectedRunningGameId
                                    + " resolvedGame=" + resolvedGameId);
                    if (completion != null) completion.accept(false);
                    return;
                }
                if (!requestSucceeded) {
                    if (completion != null) completion.accept(false);
                    return;
                }
                long acceptedAt = SystemClock.uptimeMillis();
                boolean freshnessRecovered = !PlayniteIdentityResolutionPolicy
                        .isFreshObservation(expectedRunningGameId,
                                activePlayniteGameAppIds.getOrDefault(
                                        stateKey, Integer.MIN_VALUE),
                                activePlayniteGameResolvedAt.getOrDefault(
                                        stateKey, 0L),
                                acceptedAt, ACTIVE_GAME_OBSERVATION_TTL_MS);
                activePlayniteGameResolvedAt.put(stateKey, acceptedAt);
                activePlayniteGameAppIds.put(stateKey, expectedRunningGameId);
                String previous = activePlayniteGameIds.get(stateKey);
                String previousState = activePlayniteGameStates.get(stateKey);
                String acceptedState = "running".equals(resolvedGameState)
                        && resolvedGameId.isEmpty() ? "unknown" : resolvedGameState;
                if (acceptedState.isEmpty()) acceptedState = "unknown";
                activePlayniteGameStates.put(stateKey, acceptedState);
                android.util.Log.i("MoonWakerSession",
                        "Resolved active Playnite session host=" + expectedHostId
                                + " app=" + expectedRunningGameId
                                + " state=" + acceptedState + " game=" + resolvedGameId);
                if ("idle".equals(acceptedState)) activePlayniteGameIds.remove(stateKey);
                else if (!resolvedGameId.isEmpty()) {
                    activePlayniteGameIds.put(stateKey, resolvedGameId);
                }
                String acceptedGameId = activePlayniteGameIds.get(stateKey);
                if (active && expectedHostId.equals(selectedHostUuid)
                        && runningGamePresentationChanged(previous, acceptedGameId,
                        previousState, acceptedState, freshnessRecovered)) {
                    renderPlayniteLibrary(currentHost(expectedHostId), currentSunshineApps);
                }
                if (completion != null) completion.accept(true);
            });
        });
    }

    static boolean runningGamePresentationChanged(String previousGameId,
                                                  String currentGameId,
                                                  String previousState,
                                                  String currentState,
                                                  boolean freshnessRecovered) {
        if (normalizeId(previousGameId).isEmpty()
                && normalizeId(currentGameId).isEmpty()) return false;
        return freshnessRecovered
                || !Objects.equals(previousGameId, currentGameId)
                || !Objects.equals(previousState, currentState);
    }

    private void invalidateActivePlayniteGameRequest(String hostId) {
        String key = selectedProfileKey(hostId).cacheKey();
        activePlayniteGameRequestGenerations.put(key,
                ++activePlayniteGameRequestGeneration);
        activePlayniteGameResolutionInFlight.remove(key);
    }

    static boolean activeGameRefreshDue(long now, long requestedAt) {
        return requestedAt == 0L || now < requestedAt || now - requestedAt >= 5_000L;
    }

    private void refreshRunningGamePresentation(ComputerDetails host) {
        if (!active || host == null || !host.uuid.equals(selectedHostUuid)
                || currentPlayniteGames.isEmpty() || playniteSessionProjection == null) return;
        String signature = playniteSessionProjection.signature() + runningGameSignature(host);
        if (!signature.equals(renderedCarouselSessionSignature)
                || (expandedLibraryMode && !signature.equals(renderedExpandedSessionSignature))) {
            renderPlayniteLibrary(host, currentSunshineApps);
        }
    }

    static boolean sameGatewayProfile(GatewayConnection first, GatewayConnection second) {
        return first != null && second != null
                && first.profileId().equals(second.profileId())
                && first.endpoint().equals(second.endpoint())
                && first.certificateSha256().equals(second.certificateSha256());
    }

    private void resumeSelectedSession() {
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) {
            updateHostSelector();
            return;
        }
        resumeSession(host);
    }

    private SessionSnapshot resolveSessionSnapshot(ComputerDetails host) {
        return resolveSessionSnapshot(host, host == null
                ? GatewayConnection.DEFAULT_PROFILE_ID : selectedProfileId(host.uuid));
    }

    private SessionSnapshot resolveSessionSnapshot(ComputerDetails host, String profileId) {
        if (host == null) {
            return new SessionSnapshot("", SessionSnapshot.State.NONE, 0, "",
                    false, false, false, false, false);
        }
        HostProfileKey profileKey = new HostProfileKey(host.uuid, profileId);
        String stateKey = profileKey.cacheKey();
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        SuspendedSessionStore.Session suspended =
                SuspendedSessionStore.load(this, host.uuid, profileKey.profileId);
        HostSleepStateStore.State sleep = HostSleepStateStore.load(this, host.uuid);
        SessionResumeManager.PendingSession pending =
                SessionResumeManager.pendingSession(this);
        boolean pendingMatchesRunningSession = pending != null
                && host.uuid.equalsIgnoreCase(pending.hostUuid)
                && profileKey.profileId.equals(pending.profileId)
                && host.runningGameId != 0
                && host.runningGameId == pending.appId;
        boolean freshBridgeApp = PlayniteIdentityResolutionPolicy.isFreshObservation(
                host.runningGameId, activePlayniteGameAppIds.getOrDefault(
                        stateKey, Integer.MIN_VALUE),
                activePlayniteGameResolvedAt.getOrDefault(stateKey, 0L),
                SystemClock.uptimeMillis(), ACTIVE_GAME_OBSERVATION_TTL_MS);
        String bridgeGameState = freshBridgeApp
                ? activePlayniteGameStates.getOrDefault(stateKey, "unknown")
                : "unknown";
        String resolvedPlayniteGameId = activePlayniteGameIds.getOrDefault(stateKey, "");
        if (resolvedPlayniteGameId.isEmpty() && pendingMatchesRunningSession
                && !activePlayniteGameResolvedAt.containsKey(stateKey)) {
            resolvedPlayniteGameId = pending.playniteGameId;
        }
        boolean pendingReconnect = pending != null
                && (pending.autoResume || pendingMatchesRunningSession)
                && PlayniteIdentityResolutionPolicy.allowsPendingReconnect(
                        bridgeGameState, pending.playniteGameId);
        SessionStateResolver.Observations observations =
                new SessionStateResolver.Observations(
                        host.uuid, profileKey.profileId, host.runningGameId,
                        resolvedPlayniteGameId, retained,
                        suspended, SuspendedSessionStore.recentlyEnded(
                        this, host.uuid, profileKey.profileId),
                        pendingReconnect, pending == null ? "" : pending.hostUuid,
                        pending == null ? GatewayConnection.DEFAULT_PROFILE_ID
                                : pending.profileId,
                        pending == null ? 0 : pending.appId, host.uuid, sleep,
                        host.state == ComputerDetails.State.ONLINE);
        observations.bridgeGameState = bridgeGameState;
        observations.retainedOwnerLive =
                RetainedStreamSessionCoordinator.canResumeInstantly(retained);
        int sessionAppId = retained.hostId.equalsIgnoreCase(host.uuid)
                && retained.profileId.equals(profileKey.profileId)
                && retained.appId != 0 ? retained.appId
                : host.runningGameId != 0 ? host.runningGameId
                : pending != null && pending.hostUuid.equalsIgnoreCase(host.uuid)
                && pending.profileId.equals(profileKey.profileId)
                ? pending.appId : 0;
        observations.neutralStreamTarget = pending != null
                && pending.neutralStreamTarget
                && pending.hostUuid.equalsIgnoreCase(host.uuid)
                && pending.profileId.equals(profileKey.profileId)
                && pending.appId == sessionAppId;
        if (!observations.neutralStreamTarget && sessionAppId != 0) {
            observations.neutralStreamTarget = PlayniteTargetResolver.isNeutralStream(
                    PlayniteTargetResolver.findById(loadApps(host, true), sessionAppId));
        }
        return sessionStateResolver.resolve(observations);
    }
    private String hostStatus(ComputerDetails host) {
        return hostStatus(host, resolveSessionSnapshot(host));
    }

    private String hostStatus(ComputerDetails host, SessionSnapshot snapshot) {
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        if (retained.hostId.equalsIgnoreCase(host.uuid)) {
            if (retained.state == RetainedStreamSessionCoordinator.State.PREPARING) {
                return getString(R.string.console_warm_up_preparing);
            }
            if ((retained.state == RetainedStreamSessionCoordinator.State.HOME_LIVE
                    || retained.state == RetainedStreamSessionCoordinator.State.PARKED_LIVE)
                    && retained.playniteGameId.isEmpty()
                    && snapshot.neutralStreamTarget) {
                return getString(R.string.console_warm_up_ready);
            }
        }
        if (snapshot.state == SessionSnapshot.State.TERMINATING) {
            return getString(R.string.console_status_closing_stream);
        }
        if (snapshot.isSuspended()) {
            SuspendedSessionStore.Session suspended =
                    SuspendedSessionStore.load(this, host.uuid, selectedProfileId(host.uuid));
            String title = suspended == null || suspended.title.isEmpty()
                    ? getString(R.string.console_game) : suspended.title;
            if (host.state != ComputerDetails.State.ONLINE) {
                return getString(R.string.console_status_sleeping_suspended_game, title);
            }
            if (!snapshot.suspensionSleepObserved
                    && !hostStateController.isWaking(host.uuid)) {
                return getString(R.string.console_status_suspending_game, title);
            }
            return getString(R.string.console_status_suspended_game, title);
        }
        if (snapshot.hostSleepRequested) {
            if (host.state != ComputerDetails.State.ONLINE) {
                return getString(R.string.console_status_asleep);
            }
            if (!snapshot.hostSleepObserved) {
                return getString(R.string.console_status_suspending_short);
            }
        }
        ConsoleHostPresentation.State state = consoleHostState(host, snapshot);
        if (state == ConsoleHostPresentation.State.ACTIVE_SESSION) {
            String appName = activeSessionName(host, snapshot);
            return appName == null ? getString(R.string.console_status_active_session)
                    : getString(R.string.console_status_active_app, appName);
        }
        boolean localWarmUp = host.uuid.equalsIgnoreCase(warmUpClientHostId);
        boolean preparingHost = sessionOrchestrator != null
                && sessionOrchestrator.hasPreparationForHost(host.uuid);
        if (localWarmUp && preparingHost) {
            if (warmUpStatus == WARM_UP_WAKING) {
                return getString(R.string.console_warm_up_waking);
            }
            if (warmUpStatus == WARM_UP_PREPARING) {
                return getString(R.string.console_warm_up_preparing);
            }
        }
        if (localWarmUp
                && warmUpStatus == WARM_UP_PREPARING
                && retained.state == RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED
                && retained.hostId.equalsIgnoreCase(host.uuid)
                && !preparingHost) {
            return getString(R.string.console_warm_up_error);
        }
        if (localWarmUp && warmUpStatus == WARM_UP_ERROR) {
            return getString(R.string.console_warm_up_error);
        }
        ConsoleHostPresentation.Profile profile = consoleProfile(host);
        if (state == ConsoleHostPresentation.State.ONLINE
                || state == ConsoleHostPresentation.State.PROFILE_ATTENTION) {
            switch (profile.state) {
                case ACTIVE:
                    return getString(R.string.console_profile_status_active,
                            profile.selectedName);
                case OTHER_AUTHORIZED_ACTIVE:
                    return getString(R.string.console_profile_status_other_named,
                            profile.selectedName, profile.activeName);
                case OTHER_ACTIVE:
                    return getString(R.string.console_profile_status_other,
                            profile.selectedName);
                case SIGN_IN_REQUIRED:
                    return getString(R.string.console_profile_status_sign_in,
                            profile.selectedName);
                case UNKNOWN:
                    return getString(R.string.console_profile_status_unknown,
                            profile.selectedName);
                default:
                    break;
            }
        }
        switch (state) {
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
        return consoleHostState(host, resolveSessionSnapshot(host));
    }

    private ConsoleHostPresentation.State consoleHostState(
            ComputerDetails host, SessionSnapshot snapshot) {
        if (snapshot.state == SessionSnapshot.State.TERMINATING) {
            return ConsoleHostPresentation.State.CONNECTING;
        }
        if (snapshot.hasActiveSession()) {
            return ConsoleHostPresentation.State.ACTIVE_SESSION;
        }
        if (snapshot.isSuspended()) {
            if (host.state != ComputerDetails.State.ONLINE) {
                return ConsoleHostPresentation.State.ASLEEP;
            }
            return !snapshot.suspensionSleepObserved
                    ? ConsoleHostPresentation.State.CONNECTING
                    : ConsoleHostPresentation.State.WAKING;
        }
        if (snapshot.hostSleepRequested) {
            return host.state == ComputerDetails.State.ONLINE
                    ? ConsoleHostPresentation.State.CONNECTING
                    : ConsoleHostPresentation.State.ASLEEP;
        }
        ConsoleHostPresentation.State state = hostStateController.state(host);
        if (state == ConsoleHostPresentation.State.ACTIVE_SESSION) {
            state = ConsoleHostPresentation.State.ONLINE;
        }
        return ConsoleHostPresentation.withProfile(state, consoleProfile(host));
    }

    private ConsoleHostPresentation.Profile consoleProfile(ComputerDetails host) {
        return ConsoleHostPresentation.profile(host == null || hostGatewayStore == null
                ? null : hostGatewayStore.profileSelection(host.uuid));
    }

    private String findAppName(ComputerDetails host, int appId) {
        for (NvApp app : loadApps(host, true)) {
            if (app.getAppId() == appId) return app.getAppName();
        }
        return null;
    }

    private String activeSessionName(ComputerDetails host, SessionSnapshot snapshot) {
        for (PlayniteDashboardItem item : unfilteredPlayniteItems) {
            if (snapshot.playniteGameId.equals(
                    SessionSnapshot.normalize(item.stableId()))) return item.game.name;
        }
        return findAppName(host, snapshot.hostGameAppId);
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
        boolean terminating = resolveSessionSnapshot(host).state
                == SessionSnapshot.State.TERMINATING;
        boolean online = host.state == ComputerDetails.State.ONLINE && !terminating;
        boolean paired = host.pairState == PairingManager.PairState.PAIRED;
        boolean autoWarmUp = HostAutoWarmUpPreferences.isEnabled(preferences, host.uuid);
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        boolean gatewayAvailable = hostGatewayStore.loadForHost(host.uuid, address) != null;
        boolean canSleep = online && gatewayAvailable;
        HostGatewayStore.ProfileSelection profileSelection =
                hostGatewayStore.profileSelection(host.uuid);
        HostGatewayClient.IntegrationProfile selectedProfile = profileSelection.selected;
        HostGatewayClient.IntegrationProfile activeProfile =
                activeAuthorizedProfile(profileSelection);

        TextView wake = hostSelectionMenuAction(getString(R.string.console_wake_host),
                !terminating && ConsoleHostPresentation.canWake(host)
                        && !hostStateController.isWaking(host.uuid));
        wake.setOnClickListener(view -> {
            hideSidePanel();
            wakeHost(host);
        });
        TextView desktop = hostSelectionMenuAction(
                getString(R.string.console_launch_desktop), online && paired);
        desktop.setContentDescription(getString(R.string.console_launch_desktop_description));
        desktop.setOnClickListener(view -> {
            hideSidePanel();
            launchDesktopSession(host);
        });
        TextView profiles = hostSelectionMenuAction(
                getString(R.string.console_choose_moonwaker_profile), true);
        profiles.setOnClickListener(view -> showProfileSelection(host));
        TextView alignProfile = activeProfile == null || selectedProfile == null
                || activeProfile.id.equals(selectedProfile.id) ? null
                : hostSelectionMenuAction(getString(
                R.string.console_use_profile_in_moonwaker, activeProfile.name), true);
        if (alignProfile != null) {
            alignProfile.setOnClickListener(view -> {
                selectProfile(host, activeProfile.id);
                hideSidePanel();
            });
        }
        boolean switchVisible = selectedProfile != null
                && !"active".equals(selectedProfile.sessionState);
        TextView switchProfile = switchVisible ? hostSelectionMenuAction(getString(
                R.string.console_switch_windows_profile, selectedProfile.name),
                online && gatewayAvailable && selectedProfile.remoteSignIn
                        && windowsProfileSwitchAttempt == null) : null;
        if (switchProfile != null) {
            switchProfile.setOnClickListener(view ->
                    confirmWindowsProfileSwitch(host, selectedProfile));
        }
        LinearLayout warmUp = settingsToggle(
                getString(R.string.console_auto_stream_warm_up), autoWarmUp);
        warmUp.setOnClickListener(view -> {
            boolean enabled = !HostAutoWarmUpPreferences.isEnabled(preferences, host.uuid);
            HostAutoWarmUpPreferences.setEnabled(preferences, host.uuid, enabled);
            updateSettingsToggle(warmUp, enabled);
            if (!enabled) cancelOwnedWarmUp(host.uuid, false);
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
                getString(R.string.console_close_stream),
                online && paired && host.runningGameId != 0);
        terminate.setTextColor(terminate.isEnabled() ? 0xFFFF9B92 : 0x88FF9B92);
        terminate.setOnClickListener(view -> confirmCloseHostStream(host));
        TextView hardTerminate = hostSelectionMenuAction(
                getString(R.string.console_hard_terminate_session),
                online && paired && gatewayAvailable);
        hardTerminate.setTextColor(hardTerminate.isEnabled() ? 0xFFFF6F61 : 0x88FF6F61);
        hardTerminate.setOnClickListener(view -> confirmHardTerminateSession(host));
        List<View> actions = new ArrayList<>();
        actions.add(wake);
        actions.add(desktop);
        if (alignProfile != null) actions.add(alignProfile);
        if (switchProfile != null) actions.add(switchProfile);
        if (profileSelection.showSelector) actions.add(profiles);
        actions.add(warmUp);
        actions.add(terminate);
        actions.add(hardTerminate);
        actions.add(unpair);
        actions.add(test);
        actions.add(sleep);
        showSidePanel(getString(R.string.console_host_eyebrow), host.name,
                getString(online ? R.string.console_host_online_details
                        : R.string.console_host_offline_details) + "\n\n"
                        + getString(R.string.console_auto_stream_warm_up_description),
                actions.toArray(new View[0]));
    }

    static HostGatewayClient.IntegrationProfile activeAuthorizedProfile(
            HostGatewayStore.ProfileSelection selection) {
        if (selection == null) return null;
        for (HostGatewayClient.IntegrationProfile profile : selection.profiles) {
            if ("active".equals(profile.sessionState)) return profile;
        }
        return null;
    }

    private void confirmWindowsProfileSwitch(ComputerDetails host,
                                             HostGatewayClient.IntegrationProfile profile) {
        TextView cancel = panelAction(getString(R.string.console_cancel));
        TextView switchProfile = panelAction(getString(
                R.string.console_switch_windows_profile_confirm, profile.name));
        switchProfile.setTextColor(0xFFFFB74D);
        cancel.setOnClickListener(view -> handlePanelBack());
        switchProfile.setOnClickListener(view -> {
            hideSidePanel();
            requestWindowsProfileSwitch(host, profile);
        });
        showSidePanel(getString(R.string.console_profile_eyebrow),
                getString(R.string.console_switch_windows_profile_title, profile.name),
                getString(R.string.console_switch_windows_profile_details),
                cancel, switchProfile);
    }

    private void requestWindowsProfileSwitch(ComputerDetails host,
                                             HostGatewayClient.IntegrationProfile profile) {
        if (host == null || profile == null || !profile.id.equals(selectedProfileId(host.uuid))) {
            return;
        }
        String address = host.activeAddress == null ? null : host.activeAddress.address;
        GatewayConnection connection = hostGatewayStore.loadForHost(
                host.uuid, address, profile.id);
        if (connection == null) return;
        cancelWindowsProfileSwitch(false);
        WindowsProfileSwitchAttempt attempt = new WindowsProfileSwitchAttempt(
                host.uuid, profile.id, "android-switch-" + UUID.randomUUID(), connection);
        windowsProfileSwitchAttempt = attempt;

        TextView cancel = panelAction(getString(R.string.console_cancel));
        cancel.setOnClickListener(view -> {
            hideSidePanel();
            cancelWindowsProfileSwitch(true);
        });
        showSidePanel(getString(R.string.console_profile_eyebrow),
                getString(R.string.console_switch_windows_profile_progress, profile.name),
                getString(R.string.console_switch_windows_profile_progress_details), cancel);
        executor.execute(() -> runWindowsProfileSwitch(attempt));
    }

    private void runWindowsProfileSwitch(WindowsProfileSwitchAttempt attempt) {
        HostGatewayClient.WindowsSession session;
        try {
            session = hostGatewayClient.switchWindowsSession(
                    attempt.connection, attempt.requestId);
        } catch (IOException | RuntimeException unavailable) {
            finishWindowsProfileSwitch(attempt,
                    new HostGatewayClient.WindowsSession("failed", "gateway_unavailable", "", 1_000));
            return;
        }
        attempt.attemptId = session.attemptId;
        if (!isCurrentWindowsProfileSwitch(attempt)) {
            cancelWindowsProfileSwitchAttempt(attempt);
            return;
        }
        long deadline = SystemClock.elapsedRealtime() + WINDOWS_PROFILE_SWITCH_TIMEOUT_MS;
        while (canPollWindowsProfileSwitch(session)
                && isCurrentWindowsProfileSwitch(attempt)
                && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(Math.max(250L, Math.min(3_000L, session.retryAfterMs)));
                if (!isCurrentWindowsProfileSwitch(attempt)) break;
                session = hostGatewayClient.getWindowsSessionStatus(
                        attempt.connection, attempt.requestId, attempt.attemptId);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException | RuntimeException unavailable) {
                // Retry the same bounded, request-correlated status poll.
            }
        }
        if (!isCurrentWindowsProfileSwitch(attempt)) {
            cancelWindowsProfileSwitchAttempt(attempt);
            return;
        }
        if (canPollWindowsProfileSwitch(session)) {
            cancelWindowsProfileSwitchAttempt(attempt);
            session = new HostGatewayClient.WindowsSession(
                    "failed", "switch_timeout", attempt.attemptId, 1_000);
        }
        finishWindowsProfileSwitch(attempt, session);
    }

    static boolean canPollWindowsProfileSwitch(HostGatewayClient.WindowsSession session) {
        if (session == null || session.attemptId.isEmpty()) return false;
        switch (session.state) {
            case "pending":
            case "credential_available":
            case "credential_issued":
            case "credential_acquired":
            case "credential_submitted":
            case "sign_in_requested":
            case "session_starting":
                return true;
            default:
                return false;
        }
    }

    static boolean windowsProfileSwitchNeedsAttention(
            HostGatewayClient.WindowsSession session) {
        return session != null && ("attention_required".equals(session.state)
                || "credential_missing".equals(session.reason));
    }

    private boolean isCurrentWindowsProfileSwitch(WindowsProfileSwitchAttempt attempt) {
        return windowsProfileSwitchAttempt == attempt && attempt.matches(
                attempt.hostId, selectedProfileId(attempt.hostId), attempt.requestId);
    }

    private void finishWindowsProfileSwitch(WindowsProfileSwitchAttempt attempt,
                                            HostGatewayClient.WindowsSession session) {
        mainHandler.post(() -> {
            if (!isCurrentWindowsProfileSwitch(attempt)) return;
            windowsProfileSwitchAttempt = null;
            refreshWindowsProfileStatus(attempt.hostId);
            hideSidePanel();
            int message = "ready".equals(session.state)
                    ? R.string.console_switch_windows_profile_success
                    : windowsProfileSwitchNeedsAttention(session)
                    ? R.string.console_switch_windows_profile_attention
                    : "cancelled".equals(session.state)
                    ? R.string.console_switch_windows_profile_cancelled
                    : R.string.console_switch_windows_profile_failed;
            ConsoleUiFeedback.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void cancelWindowsProfileSwitch(boolean userVisible) {
        WindowsProfileSwitchAttempt attempt = windowsProfileSwitchAttempt;
        if (attempt == null) return;
        windowsProfileSwitchAttempt = null;
        attempt.cancelled = true;
        if (!attempt.attemptId.isEmpty()) {
            executor.execute(() -> cancelWindowsProfileSwitchAttempt(attempt));
        }
        refreshWindowsProfileStatus(attempt.hostId);
        if (userVisible) {
            ConsoleUiFeedback.makeText(this,
                    R.string.console_switch_windows_profile_cancelled,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void cancelWindowsProfileSwitchAttempt(WindowsProfileSwitchAttempt attempt) {
        if (attempt.attemptId.isEmpty()) return;
        try {
            hostGatewayClient.cancelWindowsSession(attempt.connection,
                    attempt.requestId, attempt.attemptId);
        } catch (IOException | RuntimeException ignored) {
            // Best effort: the Login Broker expires the exact bound attempt.
        }
    }

    private void refreshWindowsProfileStatus(String hostId) {
        ComputerDetails host = hosts.get(hostId);
        if (host == null) return;
        String address = host.activeAddress == null ? null : host.activeAddress.address;
        GatewayConnection connection = hostGatewayStore.loadForHost(hostId, address);
        if (connection == null) return;
        executor.execute(() -> {
            HostGatewayClient.IntegrationProfiles profiles;
            try {
                profiles = hostGatewayClient.getIntegrationProfiles(connection);
            } catch (IOException | RuntimeException unavailable) {
                return;
            }
            mainHandler.post(() -> {
                ComputerDetails current = hosts.get(hostId);
                if (current == null) return;
                hostGatewayStore.saveProfiles(hostId, profiles);
                updateHostSelectionTile(current);
                if (hostId.equals(selectedHostUuid)) {
                    updateProfileSelector(current);
                    updateHostPowerLabel();
                }
            });
        });
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
        for (ComputerDetails host : hosts.values()) invalidateHostStateAsync(host.uuid);
    }

    private void refreshSelectedApplications() {
        if (appListPoller != null) appListPoller.pollNow();
    }

    private void refreshSelectedPlayniteLibrary() {
        ComputerDetails selected = hosts.get(selectedHostUuid);
        if (selected != null) requestPlayniteRefresh(selected, true);
    }

    private void refreshSessionState(String hostUuid) {
        if (managerBinder == null || hostUuid == null || hostUuid.isEmpty()
                || executor.isShutdown()) return;
        boolean refreshSelected = hostUuid.equalsIgnoreCase(selectedHostUuid);
        try {
            executor.execute(() -> {
                ComputerManagerService.ComputerManagerBinder binder = managerBinder;
                if (binder == null) return;
                binder.invalidateStateForComputer(hostUuid);
                if (refreshSelected) {
                    mainHandler.post(() -> {
                        if (!active || !hostUuid.equalsIgnoreCase(selectedHostUuid)) return;
                        refreshSelectedApplications();
                        refreshSelectedPlayniteLibrary();
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A termination callback can arrive after Activity teardown has shut down the pool.
        }
    }

    private void invalidateHostStateAsync(String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty()) return;
        executor.execute(() -> {
            ComputerManagerService.ComputerManagerBinder binder = managerBinder;
            if (binder != null) binder.invalidateStateForComputer(hostUuid);
        });
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
        if (!ConsoleActionCatalog.isOnline(host)) {
            hideSidePanel();
            playSunshineApp(host,
                    new NvApp(item.originalAppName, item.appId, false), item.key);
            return;
        }
        ConsoleUiFeedback.makeText(this, R.string.console_quick_launch_app_missing, Toast.LENGTH_LONG).show();
    }

    private void showQuickLaunchItemActions(QuickLaunchManager.QuickLaunchItem item) {
        ComputerDetails host = hosts.get(item.computerUuid);
        if (host != null && resolveSessionSnapshot(host).state
                == SessionSnapshot.State.TERMINATING) {
            showSidePanelBusy(getString(R.string.console_quick_launch_eyebrow),
                    item.getDisplayNameLong(),
                    getString(R.string.console_status_closing_stream));
            return;
        }
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
        if (host != null && host.runningGameId == item.appId) {
            TextView quit = panelAction(getString(R.string.applist_menu_quit));
            quit.setTextColor(0xFFFF9B92);
            quit.setOnClickListener(view -> confirmQuitAction(() -> {
                hideSidePanel();
                requestTerminateSession(host);
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
        playSunshineApp(host, app, quickKey);
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
        addQuickAction(globalAction("global.settings", R.string.console_action_stream_settings,
                R.drawable.ic_console_settings, this::showOptionsPanel));
        discordActionButton = addQuickAction(globalAction("global.discord",
                R.string.console_action_discord, R.drawable.ic_console_discord,
                this::showDiscordPanel));
        usbMicrophoneActionButton = addQuickAction(globalAction("global.usb_microphone",
                R.string.console_action_usb_microphone, R.drawable.ic_overlay_microphone,
                this::showUsbMicrophonePanel));
        addQuickAction(globalAction("global.leave_host", R.string.console_action_leave_host,
                R.drawable.ic_console_leave_host,
                () -> showHostSelection(selectedHostUuid)));
    }

    private ImageButton addQuickAction(ConsoleAction resolved) {
        boolean discord = "global.discord".equals(resolved.id);
        ImageButton button = discord
                ? new ImageButton(this) {
                    private final Paint notificationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    {
                        notificationPaint.setColor(0xFF3B82F6);
                    }

                    @Override protected void onDraw(Canvas canvas) {
                        super.onDraw(canvas);
                        statusPaint.setColor(discordIndicatorColor);
                        canvas.drawCircle(getWidth() / 2f + dp(10),
                                getHeight() / 2f + dp(10),
                                dp(3), statusPaint);
                        if (discordNotificationPending) {
                            canvas.drawCircle(getWidth() - dp(10), dp(10), dp(4),
                                    notificationPaint);
                        }
                    }
                }
                : new ImageButton(this);
        button.setId(View.generateViewId());
        button.setImageResource(resolved.icon);
        button.setTag(resolved.id);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int target = getResources().getDimensionPixelSize(R.dimen.console_action_target);
        int icon = getResources().getDimensionPixelSize(R.dimen.console_icon_size)
                + (discord ? dp(14) : 0);
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
        params.leftMargin = getResources().getDimensionPixelSize(R.dimen.console_space_s);
        quickActions.addView(button, params);
        return button;
    }

    private void positionQuickActionHint(View anchor) {
        quickActionHint.post(() -> {
            int[] anchorPosition = new int[2];
            int[] layerPosition = new int[2];
            anchor.getLocationInWindow(anchorPosition);
            homeLayer.getLocationInWindow(layerPosition);
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) quickActionHint.getLayoutParams();
            int centered = anchorPosition[0] - layerPosition[0]
                    + anchor.getWidth() / 2 - quickActionHint.getWidth() / 2;
            params.leftMargin = Math.max(dp(12), Math.min(centered,
                    homeLayer.getWidth() - quickActionHint.getWidth() - dp(12)));
            quickActionHint.setLayoutParams(params);
        });
    }

    private void refreshDiscordIndicator() {
        ImageButton button = discordActionButton;
        if (button == null) return;
        int request = discordStatusGeneration.incrementAndGet();
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host == null) {
            applyDiscordIndicator(button, 0xFFFF6B6B,
                    getString(R.string.console_discord_no_host));
            return;
        }
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        GatewayConnection connection =
                hostGatewayStore.loadForHost(host.uuid, address);
        if (connection == null) {
            applyDiscordIndicator(button, 0xFFFF6B6B,
                    getString(R.string.console_discord_unconfigured));
            return;
        }
        if (!hostGatewayStore.isDiscordEnabled(host.uuid, connection.profileId())) {
            applyDiscordIndicator(button, 0xFFFF6B6B,
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
                    color = DiscordPanelController.discordIndicatorColor(
                            false, status.authenticated, status.rpcConnected, false);
                } else if (!status.authenticated) {
                    state = getString(R.string.console_discord_auth_required);
                    color = DiscordPanelController.discordIndicatorColor(
                            true, false, status.rpcConnected, false);
                } else if (DiscordPanelController.discordNeedsReconnect(status)) {
                    state = getString(R.string.console_discord_reconnecting);
                    color = DiscordPanelController.discordIndicatorColor(
                            true, true, false, false);
                } else {
                    HostGatewayClient.DiscordVoice voice =
                            hostGatewayClient.getDiscordVoice(connection, true);
                    state = voice.connected
                            ? getString(R.string.console_discord_voice_active, voice.channelName)
                            : getString(R.string.console_discord_connected);
                    color = DiscordPanelController.discordIndicatorColor(
                            true, true, true, voice.connected);
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
        discordIndicatorDescription = getString(R.string.console_discord_description, state);
        updateDiscordActionDescription();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) button.setTooltipText(state);
        styleQuickAction(button, button.hasFocus());
        button.setAlpha(1f);
    }

    private void applyDiscordVoiceIndicator(HostGatewayClient.DiscordVoice voice) {
        ImageButton button = discordActionButton;
        if (button == null || voice == null) return;
        discordStatusGeneration.incrementAndGet();
        String state = voice.connected
                ? getString(R.string.console_discord_voice_active, voice.channelName)
                : getString(R.string.console_discord_connected);
        applyDiscordIndicator(button, DiscordPanelController.discordIndicatorColor(
                true, true, true, voice.connected), state);
    }

    private void refreshUsbMicrophoneIndicator() {
        ImageButton button = usbMicrophoneActionButton;
        if (button == null) return;
        String state = UsbMicrophoneService.state(this);
        String description = getString(microphoneStateTextResource(state));
        usbMicrophoneIndicatorColor = microphoneStateColor(state);
        button.setContentDescription(getString(
                R.string.console_usb_microphone_description, description));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setTooltipText(description);
        }
        styleQuickAction(button, button.hasFocus());
    }

    static int microphoneStateTextResource(String state) {
        if (UsbMicrophoneService.STATE_ACTIVE.equals(state)) {
            return R.string.console_usb_microphone_active;
        }
        if (UsbMicrophoneService.STATE_CONNECTING.equals(state)) {
            return R.string.console_usb_microphone_connecting;
        }
        if (UsbMicrophoneService.STATE_GATEWAY_RECONNECTING.equals(state)) {
            return R.string.console_usb_microphone_reconnecting;
        }
        if (UsbMicrophoneService.STATE_WAITING_USB.equals(state)) {
            return R.string.console_usb_microphone_waiting_usb;
        }
        if (UsbMicrophoneService.STATE_USB_MULTIPLE.equals(state)) {
            return R.string.console_usb_microphone_multiple;
        }
        if (UsbMicrophoneService.STATE_USB_UNAVAILABLE.equals(state)) {
            return R.string.console_usb_microphone_usb_unavailable;
        }
        if (UsbMicrophoneService.STATE_GATEWAY_REQUIRED.equals(state)) {
            return R.string.console_usb_microphone_gateway_required;
        }
        if (UsbMicrophoneService.STATE_PERMISSION_REQUIRED.equals(state)) {
            return R.string.console_usb_microphone_permission_required;
        }
        if (UsbMicrophoneService.STATE_UNSUPPORTED.equals(state)) {
            return R.string.console_usb_microphone_unsupported;
        }
        if (UsbMicrophoneService.STATE_WORKER_MISSING.equals(state)) {
            return R.string.console_usb_microphone_worker_missing;
        }
        if (UsbMicrophoneService.STATE_HOST_UNAVAILABLE.equals(state)) {
            return R.string.console_usb_microphone_host_unavailable;
        }
        if (UsbMicrophoneService.STATE_STEAM_ENDPOINT_UNAVAILABLE.equals(state)) {
            return R.string.console_usb_microphone_steam_endpoint;
        }
        return R.string.console_usb_microphone_off;
    }

    private static int microphoneStateColor(String state) {
        if (UsbMicrophoneService.STATE_ACTIVE.equals(state)) return 0xFF69F0AE;
        if (UsbMicrophoneService.STATE_CONNECTING.equals(state)
                || UsbMicrophoneService.STATE_GATEWAY_RECONNECTING.equals(state)) {
            return 0xFF73D7FF;
        }
        if (UsbMicrophoneService.STATE_OFF.equals(state)) return 0xFF697083;
        return 0xFFFFB74D;
    }

    static boolean shouldShowMicrophoneRetry(String state) {
        return UsbMicrophoneService.STATE_GATEWAY_RECONNECTING.equals(state)
                || UsbMicrophoneService.STATE_HOST_UNAVAILABLE.equals(state)
                || UsbMicrophoneService.STATE_WORKER_MISSING.equals(state)
                || UsbMicrophoneService.STATE_STEAM_ENDPOINT_UNAVAILABLE.equals(state)
                || UsbMicrophoneService.STATE_USB_UNAVAILABLE.equals(state);
    }

    private void showUsbMicrophonePanel() {
        String state = UsbMicrophoneService.state(this);
        String status = getString(microphoneStateTextResource(state));
        List<View> actions = new ArrayList<>();
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(UsbMicrophoneService.PREF_ENABLED, false);
        if (enabled) {
            TextView stop = panelAction(getString(R.string.console_usb_microphone_stop));
            stop.setOnClickListener(view -> {
                UsbMicrophoneService.disableWithState(
                        this, UsbMicrophoneService.STATE_OFF);
                showUsbMicrophonePanel();
            });
            actions.add(stop);
            if (shouldShowMicrophoneRetry(state)) {
                TextView retry = panelAction(getString(R.string.console_usb_microphone_retry));
                retry.setOnClickListener(view -> {
                    UsbMicrophoneService.retryFromVisibleActivity(this);
                    hideSidePanel();
                });
                actions.add(retry);
            }
        }
        TextView settings = panelAction(getString(R.string.console_usb_microphone_settings));
        settings.setOnClickListener(view ->
                startActivity(new Intent(this, StreamSettings.class)));
        actions.add(settings);
        showSidePanel(getString(R.string.console_action_usb_microphone),
                getString(R.string.console_usb_microphone_panel_title),
                status + "\n\n" + getString(R.string.console_usb_microphone_panel_details),
                actions.toArray(new View[0]));
    }

    private void setDiscordNotificationPending(boolean pending) {
        if (discordNotificationPending == pending) return;
        discordNotificationPending = pending;
        if (discordActionButton != null) {
            updateDiscordActionDescription();
            discordActionButton.invalidate();
        }
    }

    private void setWarmUpStatus(String hostId, long attempt, int status) {
        mainHandler.post(() -> {
            if (attempt <= 0L || attempt != warmUpClientAttempt
                    || !normalizeId(hostId).equals(warmUpClientHostId)) return;
            warmUpStatus = status;
            renderHosts();
            updateHostSelector();
        });
    }

    private void cancelCurrentPreparation() {
        boolean cancelledPreparation = sessionOrchestrator != null
                && sessionOrchestrator.cancelPreparation(warmUpClientHostId);
        if (cancelledPreparation) {
            warmUpClientAttempt = 0L;
            warmUpClientHostId = "";
            warmUpStatus = WARM_UP_NONE;
            renderHosts();
            updateHostSelector();
        } else if (sessionOrchestrator != null) {
            sessionOrchestrator.cancel();
        }
    }

    private void updateDiscordActionDescription() {
        if (discordActionButton == null || discordIndicatorDescription.isEmpty()) return;
        String description = discordIndicatorDescription;
        if (discordNotificationPending) {
            description += ". " + getString(R.string.overlay_discord_unread);
        }
        discordActionButton.setContentDescription(description);
    }

    private void resumeSession(ComputerDetails host) {
        if (host == null) return;
        SessionSnapshot snapshot = resolveSessionSnapshot(currentHost(host.uuid));
        if (!snapshot.isResumeAvailable() || snapshot.hostGameAppId == 0) return;
        NvApp running = null;
        for (NvApp app : loadApps(host, true)) {
            if (app.getAppId() == snapshot.hostGameAppId) {
                running = app;
                break;
            }
        }
        if (running == null) {
            String name = findAppName(host, snapshot.hostGameAppId);
            running = new NvApp(name == null ? "Moonlight" : name,
                    snapshot.hostGameAppId, false);
        }
        String loadingArtworkGameId = snapshot.playniteGameId.isEmpty()
                ? uniquePlayniteGameIdForRunningApp(host, running)
                : snapshot.playniteGameId;
        PlayIntent intent = snapshot.playniteGameId.isEmpty()
                ? PlayIntent.sunshineApp(host.uuid, snapshot.profileId,
                running.getAppId(), running.getAppName(),
                running.isHdrSupported(), "", loadingArtworkGameId)
                : providerGameIntent(host.uuid, snapshot.profileId,
                running.getAppId(), running.getAppName(),
                running.isHdrSupported(), snapshot.playniteGameId,
                loadingArtworkGameId, "");
        sessionOrchestrator.play(intent);
    }
    private String uniquePlayniteGameIdForRunningApp(ComputerDetails host, NvApp app) {
        if (host == null || !host.uuid.equals(selectedHostUuid)) return "";
        String synchronizedId = PlayniteTargetResolver.playniteGameId(app);
        if (!synchronizedId.isEmpty()) return synchronizedId;
        int appId = app == null ? 0 : app.getAppId();
        String match = "";
        for (PlayniteDashboardItem item : allPlayniteItems) {
            if (item.sunshineAppId == null || item.sunshineAppId != appId) continue;
            if (!match.isEmpty()) return "";
            match = item.game.playniteGameId;
        }
        return HostGatewayClient.isPlayniteId(match) ? match : "";
    }

    private String uniquePlayniteGameIdForRunningApp(ComputerDetails host, int appId) {
        return uniquePlayniteGameIdForRunningApp(host,
                PlayniteTargetResolver.findById(loadApps(host, true), appId));
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
        GatewayConnection connection =
                hostGatewayStore.loadForHost(host.uuid, address);
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
                hostGatewayStore.saveProfiles(host.uuid, pairing.profiles);
                if (pairing.profiles.profiles.size() > 1) {
                    mainHandler.post(() -> showPairingProfileSelection(host, pairing));
                    return;
                }
                beginAutomaticHostPairing(host, pairing.connection,
                        pairing.streamPairTicket);
            } catch (IOException | RuntimeException error) {
                showAutomaticPairingFailure(host, error);
            }
        });
    }

    private void showPairingProfileSelection(ComputerDetails host,
                                             HostGatewayClient.Pairing pairing) {
        List<View> actions = new ArrayList<>();
        for (HostGatewayClient.IntegrationProfile profile : pairing.profiles.profiles) {
            TextView action = panelAction(profile.name);
            action.setOnClickListener(view -> {
                GatewayConnection connection = pairing.connection.forProfile(profile.id);
                hostGatewayStore.save(host.uuid, connection);
                hideSidePanel();
                beginAutomaticHostPairing(host, connection, pairing.streamPairTicket);
            });
            actions.add(action);
        }
        showSidePanel(getString(R.string.console_profile_eyebrow),
                getString(R.string.gateway_profiles_title),
                getString(R.string.gateway_profile_details),
                actions.toArray(new View[0]));
    }

    private void beginAutomaticHostPairing(ComputerDetails host,
                                           GatewayConnection connection,
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
            invalidateHostStateAsync(host.uuid);
            resolveProfileGate(host, true, false);
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
                    invalidateHostStateAsync(host.uuid);
                    resolveProfileGate(host, true, false);
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
        GatewayConnection connection =
                hostGatewayStore.loadForHost(host.uuid, activeAddress);
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
                                  GatewayConnection connection) {
        final SuspendedSessionStore.Session suspendedRequest;
        final HostSleepStateStore.State sleepRequest;
        if (host.runningGameId != 0) {
            String gameId = uniquePlayniteGameIdForRunningApp(host, host.runningGameId);
            if (gameId.isEmpty()) {
                gameId = preferences.getString("selected_playnite." + host.uuid, "");
            }
            String title = findAppName(host, host.runningGameId);
            suspendedRequest = new SuspendedSessionStore.Session(
                    UUID.randomUUID().toString(), host.uuid, host.runningGameId, gameId,
                    title == null ? getString(R.string.console_game) : title,
                    "", System.currentTimeMillis());
            sleepRequest = null;
        } else {
            suspendedRequest = null;
            sleepRequest = HostSleepStateStore.request(this, host.uuid);
        }
        renderHostSelection();
        if (host.uuid.equals(selectedHostUuid)) updateHostSelector();
        ConsoleUiFeedback.makeText(this, getString(R.string.console_sleep_request, host.name),
                Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                JSONObject response = hostGatewayClient.sleepHost(connection);
                if (!response.optBoolean("accepted", false)) {
                    throw new IOException("Host rejected sleep request.");
                }
                mainHandler.post(() -> {
                    if (suspendedRequest != null) {
                        SuspendedSessionStore.save(this, suspendedRequest);
                        renderHostSelection();
                        if (host.uuid.equals(selectedHostUuid)) updateHostSelector();
                    }
                    ConsoleUiFeedback.makeText(this,
                            getString(R.string.console_sleep_accepted, host.name),
                            Toast.LENGTH_LONG).show();
                });
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> {
                    if (sleepRequest != null) {
                        HostSleepStateStore.clearIfMatches(
                                this, host.uuid, sleepRequest.requestedAt);
                    }
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
                    invalidateHostStateAsync(host.uuid);
                    mainHandler.postDelayed(() -> finishHostWaking(host.uuid, wakeToken),
                            HOST_WAKING_TIMEOUT_MS);
                }
            });
        });
    }

    private void resumeSuspendedSession(ComputerDetails host) {
        if (host == null) return;
        SuspendedSessionStore.Session suspended = SuspendedSessionStore.load(
                this, host.uuid, selectedProfileId(host.uuid));
        if (suspended == null) {
            requestPlayniteRefresh(host, false);
            return;
        }
        suspendedPlayniteGameId = suspended.playniteGameId;
        resumePlayniteGameId = suspended.playniteGameId;
        NvApp target = new NvApp(suspended.title.isEmpty()
                ? getString(R.string.console_game) : suspended.title,
                suspended.sunshineAppId, false);
        PlayIntent intent = suspended.playniteGameId.isEmpty()
                ? PlayIntent.sunshineApp(host.uuid, suspended.profileId,
                target.getAppId(), target.getAppName(),
                false, "", uniquePlayniteGameIdForRunningApp(host, target))
                : providerGameIntent(host.uuid, suspended.profileId,
                target.getAppId(), target.getAppName(),
                false, suspended.playniteGameId, suspended.playniteGameId, "");
        sessionOrchestrator.play(intent);
    }
    private void confirmTerminateSession(ComputerDetails host) {
        confirmTerminateSession(host, "");
    }

    private void confirmTerminateSession(ComputerDetails host, String providerGameId) {
        if (host == null || host.state != ComputerDetails.State.ONLINE
                || (resolveSessionSnapshot(host).state != SessionSnapshot.State.ACTIVE
                && host.runningGameId == 0)
                || managerBinder == null) return;
        TextView cancel = panelAction(getString(R.string.console_cancel));
        TextView terminate = panelAction(getString(R.string.overlay_menu_quit_session));
        terminate.setTextColor(0xFFFF8A80);
        cancel.setOnClickListener(view -> handlePanelBack());
        terminate.setOnClickListener(view -> {
            hideSidePanel();
            requestTerminateSession(host, providerGameId);
        });
        showSidePanel(getString(R.string.console_status_active_session),
                getString(R.string.console_terminate_session_title),
                getString(R.string.console_terminate_session_details),
                cancel, terminate);
    }

    private void requestTerminateSession(ComputerDetails host) {
        requestTerminateSession(host, "");
    }

    private void requestTerminateSession(ComputerDetails host, String expectedProviderGameId) {
        HostProfileKey profileKey = selectedProfileKey(host.uuid);
        SuspendedSessionStore.Session suspended =
                SuspendedSessionStore.load(this, host.uuid, profileKey.profileId);
        String expectedSuspendId = suspended == null ? "" : suspended.suspendId;
        String requestedProviderGameId = normalizeId(expectedProviderGameId);
        final String providerGameId = requestedProviderGameId.isEmpty()
                ? knownProviderGameId(host, suspended) : requestedProviderGameId;
        NvApp runningTarget = PlayniteTargetResolver.findById(
                currentSunshineApps, host.runningGameId);
        boolean requireProviderVerification = !providerGameId.isEmpty()
                || PlayniteTargetResolver.isNeutralStream(runningTarget);
        ConsoleUiFeedback.makeText(this, R.string.console_terminate_session_request,
                Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean stopped = false;
            try {
                stopActiveProviderGame(host, providerGameId,
                        requireProviderVerification, profileKey.profileId);
                stopped = quitSunshineIfRunning(host);
            } catch (IOException | XmlPullParserException ignored) { }
            boolean success = stopped;
            mainHandler.post(() -> {
                if (success) {
                    if (!expectedSuspendId.isEmpty()) {
                        SuspendedSessionStore.markSessionEndedIfMatches(
                                this, host.uuid, profileKey.profileId, expectedSuspendId);
                    }
                    String stateKey = profileKey.cacheKey();
                    activePlayniteGameIds.remove(stateKey);
                    activePlayniteGameStates.remove(stateKey);
                    activePlayniteGameAppIds.remove(stateKey);
                    activePlayniteGameResolvedAt.remove(stateKey);
                    if (profileKey.equals(selectedProfileKey(host.uuid))) {
                        invalidateActivePlayniteGameRequest(host.uuid);
                    }
                }
                refreshSessionState(host.uuid);
                ConsoleUiFeedback.makeText(this, getString(success
                                ? R.string.console_terminate_session_success
                                : R.string.console_terminate_session_failed),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void confirmCloseHostStream(ComputerDetails host) {
        if (host == null || host.state != ComputerDetails.State.ONLINE
                || host.runningGameId == 0 || managerBinder == null) return;
        TextView cancel = panelAction(getString(R.string.console_cancel));
        TextView close = panelAction(getString(R.string.console_close_stream));
        close.setTextColor(0xFFFF8A80);
        cancel.setOnClickListener(view -> handlePanelBack());
        close.setOnClickListener(view -> {
            hideSidePanel();
            requestCloseHostStream(host);
        });
        showSidePanel(getString(R.string.console_status_active_session),
                getString(R.string.console_close_stream_title),
                getString(R.string.console_close_stream_details), cancel, close);
    }

    private void requestCloseHostStream(ComputerDetails host) {
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        boolean exactRetained = retained.hostId.equalsIgnoreCase(host.uuid)
                && retained.profileId.equals(selectedProfileId(host.uuid));
        if (exactRetained && !retained.streamSessionId.isEmpty()) {
            RetainedStreamSessionCoordinator.TerminationResult result =
                    RetainedStreamSessionCoordinator.disconnect(
                            retained.streamSessionId,
                            success -> mainHandler.post(() -> finishCloseHostStream(
                                    host, retained.streamSessionId, success)));
            if (result != RetainedStreamSessionCoordinator.TerminationResult.NO_CONTROLLER) {
                ConsoleUiFeedback.makeText(this, R.string.console_close_stream_request,
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        ConsoleUiFeedback.makeText(this, R.string.console_close_stream_request,
                Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean stopped = false;
            try {
                stopped = quitSunshineIfRunning(host);
            } catch (IOException | XmlPullParserException ignored) { }
            boolean success = stopped;
            mainHandler.post(() -> finishCloseHostStream(
                    host, exactRetained ? retained.streamSessionId : "", success));
        });
    }

    private void finishCloseHostStream(ComputerDetails host, String streamSessionId,
                                       boolean success) {
        if (success) {
            if (!streamSessionId.isEmpty()) {
                RetainedStreamSessionCoordinator.clearIfMatches(streamSessionId);
                SessionResumeManager.clearIfMatches(this, streamSessionId);
                BackgroundStreamService.resumed(this, streamSessionId);
            }
        }
        refreshSessionState(host.uuid);
        ConsoleUiFeedback.makeText(this, success
                        ? R.string.console_close_stream_success
                        : R.string.console_close_stream_failed,
                Toast.LENGTH_LONG).show();
    }

    private void confirmHardTerminateSession(ComputerDetails host) {
        if (host == null || host.state != ComputerDetails.State.ONLINE
                || host.pairState != PairingManager.PairState.PAIRED
                || managerBinder == null) return;
        HostProfileKey profileKey = selectedProfileKey(host.uuid);
        String address = host.activeAddress == null ? null : host.activeAddress.address;
        if (hostGatewayStore.loadForHost(host.uuid, address, profileKey.profileId) == null) return;
        TextView cancel = panelAction(getString(R.string.console_cancel));
        TextView terminate = panelAction(getString(R.string.console_hard_terminate_session));
        terminate.setTextColor(0xFFFF6F61);
        cancel.setOnClickListener(view -> handlePanelBack());
        terminate.setOnClickListener(view -> {
            hideSidePanel();
            requestHardTerminateSession(host, profileKey);
        });
        showSidePanel(getString(R.string.console_host_eyebrow),
                getString(R.string.console_hard_terminate_session_title),
                getString(R.string.console_hard_terminate_session_details),
                cancel, terminate);
    }

    private void requestHardTerminateSession(ComputerDetails host, HostProfileKey profileKey) {
        if (host == null || profileKey == null
                || !profileKey.equals(selectedProfileKey(host.uuid))) return;
        String address = host.activeAddress == null ? null : host.activeAddress.address;
        GatewayConnection gateway = hostGatewayStore.loadForHost(host.uuid, address,
                profileKey.profileId);
        if (gateway == null || managerBinder == null) return;
        if (sessionOrchestrator != null) sessionOrchestrator.cancel();
        cancelOwnedWarmUp(host.uuid, false);
        ConsoleUiFeedback.makeText(this, R.string.console_hard_terminate_session_request,
                Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean stopped = false;
            try {
                hostGatewayClient.hardResetSession(gateway);
                stopped = hardQuitSunshineSession(host);
            } catch (IOException | XmlPullParserException ignored) { }
            boolean success = stopped;
            mainHandler.post(() -> {
                if (success) clearHardResetSessionState(host, profileKey);
                ConsoleUiFeedback.makeText(this, getString(success
                                ? R.string.console_hard_terminate_session_success
                                : R.string.console_hard_terminate_session_failed),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private boolean hardQuitSunshineSession(ComputerDetails host)
            throws IOException, XmlPullParserException {
        NvHTTP connection = new NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(host), host.httpsPort,
                managerBinder.getUniqueId(), host.serverCert,
                PlatformBinding.getCryptoProvider(this));
        if (connection.getComputerDetails(true).runningGameId == 0) return true;
        try {
            connection.quitApp();
        } catch (IOException | XmlPullParserException ignored) { }
        long deadline = SystemClock.uptimeMillis() + PREVIOUS_SESSION_CLOSE_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (connection.getComputerDetails(true).runningGameId == 0) return true;
            try {
                Thread.sleep(300L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void clearHardResetSessionState(ComputerDetails host, HostProfileKey profileKey) {
        if (host == null || profileKey == null
                || !profileKey.hostId.equalsIgnoreCase(host.uuid)
                || !profileKey.equals(selectedProfileKey(host.uuid))) return;
        String stateKey = profileKey.cacheKey();
        SuspendedSessionStore.Session suspended =
                SuspendedSessionStore.load(this, host.uuid, profileKey.profileId);
        if (suspended != null) {
            SuspendedSessionStore.markSessionEndedIfMatches(
                    this, host.uuid, suspended.profileId, suspended.suspendId);
        }
        HostSleepStateStore.clear(this, host.uuid);
        Set<String> streamSessionIds = new LinkedHashSet<>();
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        if (host.uuid.equalsIgnoreCase(retained.hostId)) {
            if (!retained.streamSessionId.isEmpty()) {
                streamSessionIds.add(retained.streamSessionId);
            }
            RetainedStreamSessionCoordinator.hardResetIfHostMatches(host.uuid);
        }
        SessionResumeManager.PendingSession pending =
                SessionResumeManager.pendingSession(this);
        if (pending != null && host.uuid.equalsIgnoreCase(pending.hostUuid)) {
            streamSessionIds.add(pending.streamSessionId);
        }
        for (String streamSessionId : streamSessionIds) {
            SessionResumeManager.clearIfMatches(this, streamSessionId);
            BackgroundStreamService.resumed(this, streamSessionId);
        }
        if (host.uuid.equalsIgnoreCase(retainedStreamHostId)) {
            retainedStreamSessionId = "";
            retainedStreamHostId = "";
            retainedStreamAppId = StreamConfiguration.INVALID_APP_ID;
            retainedStreamPlayniteGameId = "";
        }
        activePlayniteGameIds.remove(stateKey);
        activePlayniteGameStates.remove(stateKey);
        activePlayniteGameAppIds.remove(stateKey);
        activePlayniteGameResolvedAt.remove(stateKey);
        lastFreshRunningAppIds.remove(host.uuid);
        lastFreshStreamSessionIds.remove(host.uuid);
        if (profileKey.equals(selectedProfileKey(host.uuid))) {
            invalidateActivePlayniteGameRequest(host.uuid);
        }
        if (host.uuid.equalsIgnoreCase(selectedHostUuid)) {
            resumePlayniteGameId = "";
            suspendedPlayniteGameId = "";
        }
        refreshSessionState(host.uuid);
    }

    private boolean quitSunshineIfRunning(ComputerDetails host)
            throws IOException, XmlPullParserException {
        int expectedRunningAppId = host.runningGameId;
        NvHTTP connection = new NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(host), host.httpsPort,
                managerBinder.getUniqueId(), host.serverCert,
                PlatformBinding.getCryptoProvider(this));
        ComputerDetails fresh = connection.getComputerDetails(true);
        AuthoritativeSessionTransition.SunshineStopAction action =
                AuthoritativeSessionTransition.sunshineStopAction(
                        expectedRunningAppId, fresh.runningGameId);
        if (action == AuthoritativeSessionTransition.SunshineStopAction.COMPLETE) return true;
        return action == AuthoritativeSessionTransition.SunshineStopAction.QUIT
                && connection.quitApp();
    }

    private String knownProviderGameId(ComputerDetails host,
                                       SuspendedSessionStore.Session suspended) {
        if (suspended != null && HostGatewayClient.isPlayniteId(suspended.playniteGameId)) {
            return suspended.playniteGameId;
        }
        String active = host == null ? ""
                : activePlayniteGameIds.get(profileStateKey(host.uuid));
        if (HostGatewayClient.isPlayniteId(active)) return active;
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        SessionResumeManager.PendingSession pending =
                SessionResumeManager.pendingSession(this);
        if (host != null && host.uuid.equalsIgnoreCase(retained.hostId)
                && retained.profileId.equals(selectedProfileId(host.uuid))
                && HostGatewayClient.isPlayniteId(retained.playniteGameId)) {
            return retained.playniteGameId;
        }
        if (host != null && pending != null
                && host.uuid.equalsIgnoreCase(pending.hostUuid)
                && pending.profileId.equals(selectedProfileId(host.uuid))
                && host.runningGameId == pending.appId
                && HostGatewayClient.isPlayniteId(pending.playniteGameId)) {
            return pending.playniteGameId;
        }
        if (host != null && host.uuid.equalsIgnoreCase(retainedStreamHostId)
                && retainedStreamProfileId.equals(selectedProfileId(host.uuid))
                && HostGatewayClient.isPlayniteId(retainedStreamPlayniteGameId)) {
            return retainedStreamPlayniteGameId;
        }
        return "";
    }

    private void stopActiveProviderGame(ComputerDetails host, String knownGameId,
                                        boolean requireProviderVerification,
                                        String profileId)
            throws IOException {
        String address = host.activeAddress == null ? null : host.activeAddress.address;
        GatewayConnection gateway = hostGatewayStore.loadForHost(host.uuid, address, profileId);
        String expectedGameId = normalizeId(knownGameId);
        if (gateway == null) {
            if (requireProviderVerification || !expectedGameId.isEmpty()) {
                throw new IOException("Gateway unavailable for provider stop");
            }
            return;
        }
        HostGatewayClient.PlayniteCurrentGame current;
        try {
            current = hostGatewayClient.getPlayniteCurrentGame(gateway);
        } catch (IOException unavailable) {
            if (!requireProviderVerification && expectedGameId.isEmpty()) return;
            throw unavailable;
        }
        String gameId = PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                expectedGameId, current.state, current.id);
        if (gameId == null) {
            if (!requireProviderVerification && expectedGameId.isEmpty()) return;
            throw new IOException("Provider game state is uncertain or does not match");
        }
        if (!gameId.isEmpty()) hostGatewayClient.stopGame(gateway, gameId);
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
        flushPendingPlayniteSelection();
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
        pendingHostPreparation = null;
        boolean enteringHost = hostSelectionVisible;
        boolean changed = !host.uuid.equals(selectedHostUuid);
        if (changed) {
            cancelWindowsProfileSwitch(false);
            cancelStreamingAutopilot(false);
            cancelPlayniteArtworkPrefetch();
            cancelOwnedWarmUp(selectedHostUuid, true);
        }
        hostSelectionVisible = false;
        if (consoleAudioEngine != null) {
            consoleAudioEngine.setHostSelectionVisible(false);
        }
        if (enteringHost || focusApps) pendingInitialGameFocus = true;
        hostSelectionFocusUuid = host.uuid;
        if (hostSelectionLayer != null) hostSelectionLayer.setVisibility(View.GONE);
        if (homeLayer != null) homeLayer.setVisibility(View.VISIBLE);
        selectedHostUuid = host.uuid;
        if (requiresPreparedInitialCarouselFrame()) {
            initialLocalAppsHostId = "";
            initialLocalLibraryHostId = "";
            resetInitialCarouselArtworkWarmup();
            suppressInitialCarouselMotion = true;
            libraryTransitionCoordinator.setReducedMotion(true);
        }
        if (changed) {
            currentSunshineApps = Collections.emptyList();
        }
        restoreHostLibraryState(host.uuid);
        boolean restoredInitialPresentation = requiresPreparedInitialCarouselFrame()
                && restoreInitialLocalPresentationData(host);
        preferences.edit().putString("selected_host", host.uuid).apply();
        newlyDiscoveredHosts.remove(host.uuid);
        clearArtwork();
        appsLabel.setText(getString(R.string.console_apps_host,
                host.name.toUpperCase(Locale.ROOT)));
        updateHostSelector();
        refreshHostProfiles(host);
        refreshDiscordIndicator();
        startAppListPoller(host);
        if (restoredInitialPresentation) settleInitialLocalPresentation(host);
        renderAppsAsync(host, focusApps);
        if (!restoredInitialPresentation && (changed || currentPlayniteGames.isEmpty())) {
            loadPlayniteForHost(host);
        }
    }

    private void cancelOwnedWarmUp(String hostId, boolean relinquishForHostChange) {
        String normalizedHost = normalizeId(hostId);
        if (sessionOrchestrator != null) {
            sessionOrchestrator.cancelPreparation(normalizedHost);
        }
        RetainedStreamSessionCoordinator.Snapshot preparing =
                RetainedStreamSessionCoordinator.snapshot();
        boolean exactPreparing = warmUpClientAttempt != 0L
                && preparing.state == RetainedStreamSessionCoordinator.State.PREPARING
                && preparing.attempt == warmUpClientAttempt
                && preparing.hostId.equalsIgnoreCase(normalizedHost);
        if (exactPreparing) {
            boolean cancelled = RetainedStreamSessionCoordinator.cancelPreparing(preparing);
            if (cancelled && relinquishForHostChange) {
                RetainedStreamSessionCoordinator.Snapshot reconnect =
                        RetainedStreamSessionCoordinator.snapshot();
                if (reconnect.state == RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED
                        && reconnect.streamSessionId.equals(preparing.streamSessionId)
                        && reconnect.hostId.equalsIgnoreCase(preparing.hostId)
                        && reconnect.appId == preparing.appId
                        && reconnect.playniteGameId.equalsIgnoreCase(
                        preparing.playniteGameId)) {
                    RetainedStreamSessionCoordinator.clearIfMatches(
                            preparing.streamSessionId);
                }
            }
        }
        if (warmUpClientHostId.equals(normalizedHost)) {
            warmUpClientAttempt = 0L;
            warmUpClientHostId = "";
            warmUpStatus = WARM_UP_NONE;
        }
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
        int generation = appListRenderGeneration.incrementAndGet();
        executor.execute(() -> {
            List<NvApp> apps = loadApps(host);
            mainHandler.post(() -> runLibraryUpdateWhenNavigationIdle(() -> {
                if (generation != appListRenderGeneration.get()) return;
                if (!uuid.equals(selectedHostUuid)) return;
                ComputerDetails latestHost = currentHost(uuid);
                if (latestHost == null) return;
                currentSunshineApps = Collections.unmodifiableList(new ArrayList<>(apps));
                initialLocalAppsHostId = uuid;
                if (requiresPreparedInitialCarouselFrame()) {
                    settleInitialLocalPresentation(latestHost);
                    return;
                }
                boolean playniteAvailable = !currentPlayniteGames.isEmpty() ||
                        hostGatewayStore.loadForHost(latestHost.uuid,
                                latestHost.activeAddress != null ? latestHost.activeAddress.address : null) != null;
                if (playniteAvailable) {
                    renderPlayniteLibrary(latestHost, apps);
                } else {
                    renderApps(latestHost, apps);
                }
                if (!playniteAvailable) requestPendingInitialGameFocus(latestHost);
            }));
        });
    }

    private void runLibraryUpdateWhenNavigationIdle(Runnable update) {
        long delay = LIBRARY_UPDATE_NAVIGATION_IDLE_MS
                - (SystemClock.uptimeMillis() - lastDirectionalNavigationAt);
        if (delay > 0L) mainHandler.postDelayed(
                () -> runLibraryUpdateWhenNavigationIdle(update), delay);
        else update.run();
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

    private boolean restoreInitialLocalPresentationData(ComputerDetails host) {
        InitialLibraryPresentation cached = initialLibraryPresentation;
        if (cached == null || host == null || !host.uuid.equals(cached.hostId)) return false;
        currentPlayniteHostUuid = host.uuid;
        currentPlayniteGames = cached.games;
        currentSunshineApps = cached.sunshineApps;
        playniteLibraryCached = true;
        playniteLibraryCachedAt = cached.savedAt;
        playniteLibraryError = null;
        playniteInitialLoadPending = false;
        initialLocalAppsHostId = host.uuid;
        initialLocalLibraryHostId = host.uuid;
        deferInitialPlayniteRefresh = true;
        return true;
    }

    private void loadPlayniteForHost(ComputerDetails host) {
        if (host == null || profileGateHostUuid != null) return;
        HostProfileKey requestKey = selectedProfileKey(host.uuid);
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
        if (!requiresPreparedInitialCarouselFrame()) showCarouselLoadingGhosts();
        updatePlayniteLibraryStatus(host);
        playniteRequest = playniteExecutor.submit(() -> {
            PlayniteLibraryCache.Entry cached = playniteLibraryRepository.cached(requestKey);
            mainHandler.post(() -> {
                if (token != playniteGeneration.get() ||
                        !host.uuid.equals(selectedHostUuid)
                        || !requestKey.equals(selectedProfileKey(host.uuid))) return;
                if (cached != null) {
                    currentPlayniteGames = cached.games;
                    playniteLibraryCached = true;
                    playniteLibraryCachedAt = cached.savedAt;
                }
                initialLocalLibraryHostId = host.uuid;
                ComputerDetails latestHost = currentHost(host.uuid);
                if (requiresPreparedInitialCarouselFrame()) {
                    settleInitialLocalPresentation(latestHost);
                    deferInitialPlayniteRefresh = true;
                } else if (cached != null) {
                    renderPlayniteLibrary(latestHost, currentSunshineApps);
                }
                if (!deferInitialPlayniteRefresh) {
                    requestPlayniteRefresh(latestHost, false);
                }
            });
        });
    }

    private void settleInitialLocalPresentation(ComputerDetails host) {
        if (!requiresPreparedInitialCarouselFrame() || host == null
                || !initialLocalPresentationReady(host.uuid,
                initialLocalAppsHostId, initialLocalLibraryHostId)) return;
        if (currentPlayniteGames.isEmpty()) {
            renderApps(host, currentSunshineApps);
            requestPendingInitialGameFocus(host);
        } else if (!restoreInitialLibraryPresentation(host)) {
            renderPlayniteLibrary(host, currentSunshineApps);
        }
    }

    private boolean restoreInitialLibraryPresentation(ComputerDetails host) {
        InitialLibraryPresentation cached = initialLibraryPresentation;
        if (cached == null || !host.uuid.equals(cached.hostId)
                || cached.games != currentPlayniteGames) return false;
        allPlayniteItems = cached.allItems;
        unfilteredPlayniteItems = cached.unfilteredItems;
        playniteSessionProjection = cached.sessionProjection;
        resumePlayniteGameId = cached.resumeGameId;
        suspendedPlayniteGameId = cached.suspendedGameId;
        applyPlayniteDiff(host, currentSunshineApps, cached.carouselItems,
                "", "", false);
        renderedCarouselSessionSignature = cached.sessionSignature;
        if (CONSOLE_UI_V2 && !portraitLayout && !unfilteredPlayniteItems.isEmpty()) {
            addFullLibraryCard();
        }
        renderedAppsSignature = null;
        appsLabel.setText(getString(R.string.playnite_library,
                host.name.toUpperCase(Locale.ROOT)));
        updateHostSelector();
        updatePlayniteLibraryStatus(host);
        requestPendingInitialGameFocus(host);
        return true;
    }

    private void requestPlayniteRefresh(ComputerDetails host, boolean manual) {
        if (!active || profileGateHostUuid != null || host == null
                || !host.uuid.equals(selectedHostUuid)) return;
        HostProfileKey requestKey = selectedProfileKey(host.uuid);
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        GatewayConnection connection =
                hostGatewayStore.loadForHost(host.uuid, address, requestKey.profileId);
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
                    requestKey, connection, () -> token != playniteGeneration.get() ||
                            Thread.currentThread().isInterrupted(), manual);
            mainHandler.post(() -> {
                if (token != playniteGeneration.get() || !active ||
                        !host.uuid.equals(selectedHostUuid)
                        || !requestKey.equals(selectedProfileKey(host.uuid))) return;
                playniteLibraryRefreshing = false;
                playniteInitialLoadPending = false;
                ComputerDetails latestHost = currentHost(host.uuid);
                if (latestHost == null) return;
                if (result.entry != null) {
                    boolean libraryChanged = !result.entry.games.equals(currentPlayniteGames);
                    currentPlayniteGames = result.entry.games;
                    playniteLibraryCachedAt = result.entry.savedAt;
                    playniteLibraryCached = false;
                    playniteLibraryError = null;
                    reconcilePlayniteInstallations(latestHost, currentPlayniteGames);
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
        return gameOperationsController.hasActiveOperation(
                selectedHostUuid, currentPlayniteGames);
    }

    private boolean hasPlayniteOperationAwaitingConfirmation() {
        return gameOperationsController.hasOperationAwaitingConfirmation(
                selectedHostUuid, currentPlayniteGames);
    }

    private void reconcilePlayniteInstallations(ComputerDetails host,
                                                List<PlayniteLibraryGame> current) {
        if (host == null) return;
        Set<String> completed = new HashSet<>();
        for (GameOperationsController.Observation observation
                : gameOperationsController.reconcile(host.uuid, current)) {
            if (observation.type == GameOperationsController.ObservationType
                    .INSTALL_CONFIRMED_BY_SNAPSHOT) {
                completePlayniteInstallation(host, observation.game, observation.gameId,
                        observation.gameName);
                completed.add(observation.gameId);
            } else if (observation.type == GameOperationsController.ObservationType
                    .INSTALL_NO_LONGER_ACTIVE_AFTER_OBSERVED_ACTIVITY) {
                preferences.edit()
                        .remove(playniteInstallNotificationKey(host.uuid, observation.gameId))
                        .remove(playniteInstallPendingKey(host.uuid, observation.gameId)).apply();
                if (consoleAudioEngine != null) {
                    consoleAudioEngine.play(ConsoleAudioSynthesis.Cue.ERROR);
                }
                ConsoleUiFeedback.makeText(this, getString(R.string.playnite_install_cancelled,
                        observation.gameName), Toast.LENGTH_LONG).show();
            } else if (observation.type == GameOperationsController.ObservationType
                    .UNINSTALL_CONFIRMED_BY_SNAPSHOT) {
                ConsoleUiFeedback.makeText(this, getString(
                        R.string.playnite_uninstall_complete, observation.gameName),
                        Toast.LENGTH_LONG).show();
                preferences.edit().remove(playniteCarouselInstallActivityKey(
                        host.uuid, observation.gameId)).apply();
            }
        }
        for (PlayniteLibraryGame game : current) {
            String gameId = game.playniteGameId;
            String pendingKey = playniteInstallPendingKey(host.uuid, gameId);
            if (!preferences.contains(pendingKey)) continue;
            if (game.installed && !completed.contains(gameId)) {
                completePlayniteInstallation(host, game, gameId, game.name);
            } else if (!game.installing && !game.installRequiresAttention
                    && game.operationState.trim().isEmpty()) {
                preferences.edit().remove(playniteInstallNotificationKey(host.uuid, gameId))
                        .remove(pendingKey).apply();
            }
        }
    }

    private void completePlayniteInstallation(ComputerDetails host, PlayniteLibraryGame game,
                                               String gameId, String gameName) {
        ensureVibepolloAfterInstall(host, game);
        boolean alreadyShownInStream = preferences.getLong(
                playniteInstallNotificationKey(host.uuid, gameId), 0L) > 0L;
        if (!alreadyShownInStream) {
            if (consoleAudioEngine != null) {
                consoleAudioEngine.play(ConsoleAudioSynthesis.Cue.SUCCESS);
            }
            ConsoleUiFeedback.makeText(this, getString(R.string.playnite_install_complete,
                    gameName), Toast.LENGTH_LONG).show();
        }
        preferences.edit()
                .putLong(playniteCarouselInstallActivityKey(
                        host.uuid, gameId), System.currentTimeMillis())
                .remove(playniteInstallNotificationKey(host.uuid, gameId))
                .remove(playniteInstallPendingKey(host.uuid, gameId)).apply();
        completedPlayniteInstallAnimations.add(playniteInstallKey(host.uuid, gameId));
    }

    private void ensureVibepolloAfterInstall(ComputerDetails host,
                                             PlayniteLibraryGame game) {
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        GatewayConnection connection =
                hostGatewayStore.loadForHost(host.uuid, address);
        if (connection == null) return;
        String key = host.uuid + ":" + game.playniteGameId;
        if (!vibepolloEnsureInFlight.add(key)) return;
        executor.execute(() -> {
            JSONObject result = null;
            try {
                if ("playnite".equals(game.provider)) {
                    result = hostGatewayClient.ensureVibepolloPlayniteApp(
                            connection, game.playniteGameId, game.name);
                }
            } catch (IOException | RuntimeException ignored) { }
            PlayniteArtworkSpec artworkSpec = PlayniteArtworkSpec.forBackdrop(game);
            if (artworkSpec.available()) {
                try {
                    playniteArtworkCache.fetch(hostGatewayClient, connection, host.uuid,
                            game.playniteGameId, artworkSpec.kind, artworkSpec.version);
                } catch (IOException ignored) { }
            }
            JSONObject ensured = result;
            mainHandler.post(() -> {
                vibepolloEnsureInFlight.remove(key);
                if (ensured != null) {
                    if (appListPoller != null) appListPoller.pollNow();
                    return;
                }
                ComputerDetails current = currentHost(host.uuid);
                if (current != null && host.uuid.equals(selectedHostUuid)) {
                    renderPlayniteLibrary(current, currentSunshineApps);
                }
            });
        });
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
                && hostGatewayStore.loadForHost(host.uuid, address) != null;
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
                || libraryStatus == ConsoleLibraryStatus.State.REFRESHING;
        playniteLibraryStatus.setText(hiddenBackgroundStatus ? "" : status);
        playniteLibraryStatus.setVisibility(
                hiddenBackgroundStatus ? View.INVISIBLE : View.VISIBLE);
        updateLibraryActionVisibility();
        int color = ConsoleLibraryStatus.isError(libraryStatus)
                ? 0xFFFFB74D : libraryStatus == ConsoleLibraryStatus.State.CACHED
                ? 0xFFB8C9DC : 0xFFC8D2DC;
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
        int top = focused ? 0xD024343F : activeFilter ? 0x55314755 : 0x26242B32;
        int bottom = focused ? 0xD018242C : activeFilter ? 0x55314755 : 0x4813181D;
        GradientDrawable background = gradient(top, bottom, 10);
        background.setStroke(dp(1), focused ? 0xB073D7FF
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
        int top = focused ? 0xD024343F : activeFilter ? 0x55314755 : 0x26242B32;
        int bottom = focused ? 0xD018242C : activeFilter ? 0x55314755 : 0x4813181D;
        GradientDrawable background = gradient(top, bottom, 10);
        background.setStroke(dp(1), focused ? 0xB073D7FF
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
        int top = focused ? 0xD024343F : activeControl ? 0x55314755 : 0x26242B32;
        int bottom = focused ? 0xD018242C : activeControl ? 0x55314755 : 0x4813181D;
        GradientDrawable background = gradient(top, bottom, 10);
        background.setStroke(dp(1), focused ? 0xB073D7FF
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
        final boolean[] applied = {false};
        TextView done = sourceFilterOption(getString(R.string.playnite_sources_done));
        done.setOnClickListener(view -> {
            applied[0] = true;
            playniteFilterPopup.dismiss();
        });
        done.setOnFocusChangeListener((view, focused) ->
                stylePlayniteFilterOption(done, false, focused));
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
            if (!applied[0]) {
                anchor.post(anchor::requestFocus);
                return;
            }
            hostGatewayStore.setPlayniteLibrarySources(host.uuid, selected);
            renderFilteredPlayniteLibraryFromStart(host);
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
                dismissPlaynitePopup();
                renderExpandedLibraryFromStart(host);
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
        final boolean[] applied = {false};
        consoleFeedback.showInput(getString(R.string.playnite_search_title), null,
                expandedSearchQuery, getString(R.string.playnite_search_hint),
                InputType.TYPE_CLASS_TEXT, 120,
                getString(android.R.string.cancel),
                getString(R.string.playnite_search_clear), value -> {
                    applied[0] = true;
                    applyExpandedSearch("");
                    return null;
                }, getString(android.R.string.ok), value -> {
                    applied[0] = true;
                    applyExpandedSearch(value);
                    return null;
                }, () -> {
                    if (!applied[0] && expandedSearchButton != null) {
                        expandedSearchButton.post(expandedSearchButton::requestFocus);
                    }
                });
    }

    private void applyExpandedSearch(String query) {
        expandedSearchQuery = query == null ? "" : query.trim();
        if (selectedHostUuid != null) {
            libraryViewStateStore.saveSearch(selectedHostUuid, expandedSearchQuery);
        }
        ComputerDetails host = hosts.get(selectedHostUuid);
        if (host != null) renderExpandedLibraryFromStart(host);
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
        background.setStroke(dp(1),
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
        background.setStroke(dp(1),
                focused ? 0xFF8DDCFF : selected ? 0x8873D7FF : 0x223F4C54);
        item.setBackground(background);
        item.setTextColor(focused || selected ? Color.WHITE : 0xFFCFD8DE);
        item.setCompoundDrawablesWithIntrinsicBounds(
                selected ? android.R.drawable.checkbox_on_background : 0, 0, 0, 0);
    }

    private void applyPlayniteFilter(ComputerDetails host, PlayniteLibraryFilter filter,
                                     TextView anchor) {
        hostGatewayStore.setPlayniteLibraryFilter(host.uuid, filter);
        if (playniteFilterPopup != null) playniteFilterPopup.dismiss();
        if (expandedLibraryMode) {
            renderFilteredPlayniteLibraryFromStart(host);
        } else {
            renderPlayniteLibrary(host, currentSunshineApps);
            TextView target = installedFilterButton != null ? installedFilterButton : anchor;
            if (target != null) target.post(target::requestFocus);
        }
    }

    private void updateLibraryActionVisibility() {
        if (debugLibraryActions == null) return;
        boolean resumeVisible = quickResumeButton != null
                && quickResumeButton.getVisibility() == View.VISIBLE;
        boolean statusVisible = playniteLibraryStatus != null
                && playniteLibraryStatus.getParent() == debugLibraryActions
                && playniteLibraryStatus.getVisibility() == View.VISIBLE
                && !TextUtils.isEmpty(playniteLibraryStatus.getText());
        debugLibraryActions.setVisibility(
                resumeVisible || statusVisible ? View.VISIBLE : View.GONE);
    }

    private void renderExpandedLibraryFromStart(ComputerDetails host) {
        resetExpandedLibraryWindow();
        renderExpandedLibrary(host);
        requestExpandedFocusOnce(firstFocusableChild(expandedGrid));
    }

    private void renderFilteredPlayniteLibraryFromStart(ComputerDetails host) {
        resetExpandedLibraryWindow();
        renderPlayniteLibrary(host, currentSunshineApps);
        requestExpandedFocusOnce(firstFocusableChild(expandedGrid));
    }

    private void resetExpandedLibraryWindow() {
        if (expandedWindowWarmupRunnable != null) {
            mainHandler.removeCallbacks(expandedWindowWarmupRunnable);
            expandedWindowWarmupRunnable = null;
        }
        expandedWindowWarmupPosted = false;
        pendingExpandedWindowWarmupIndex = -1;
        if (expandedFocusRestoreRunnable != null) {
            mainHandler.removeCallbacks(expandedFocusRestoreRunnable);
            if (expandedGrid != null) expandedGrid.removeCallbacks(expandedFocusRestoreRunnable);
            expandedFocusRestoreRunnable = null;
        }
        expandedFocusTransitionInProgress = false;
        expandedGridWindowStartRow = 0;
        pendingExpandedFocusIndex = 0;
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
        Set<String> storedSources = hostGatewayStore.playniteLibrarySources(host.uuid);
        Map<String, String> availableSources = PlayniteLibrarySources.available(
                visibleLibraryGames,
                getResources().getConfiguration().getLocales().get(0));
        Set<String> selectedSources = PlayniteLibrarySources.migrateSelection(
                storedSources, availableSources);
        if (storedSources != null && selectedSources == null) {
            hostGatewayStore.clearPlayniteLibrarySources(host.uuid);
        } else if (selectedSources != null && !selectedSources.equals(storedSources)) {
            hostGatewayStore.setPlayniteLibrarySources(host.uuid, selectedSources);
        }
        List<PlayniteLibraryGame> sourceFiltered = PlayniteLibrarySources.filter(
                visibleLibraryGames, selectedSources);
        boolean appListAuthoritative = ConsoleActionCatalog.isOnline(host);
        Map<String, PlayniteDashboardItem> resolvedById = new LinkedHashMap<>();
        Map<String, Long> localActivity = new LinkedHashMap<>();
        List<PlayniteDashboardItem> unfilteredItems = new ArrayList<>();
        for (PlayniteLibraryGame game : visibleLibraryGames) {
            PlayniteDashboardItem item = PlayniteTargetResolver.resolve(host.uuid, game, apps,
                    playniteLaunchTargetStore, appListAuthoritative);
            unfilteredItems.add(item);
            resolvedById.put(item.stableId(), item);
            localActivity.put(item.stableId(), playActivityEpoch(host.uuid, item));
        }
        SessionSnapshot sessionSnapshot = resolveSessionSnapshot(host);
        playniteSessionProjection = PlayniteSessionPresentation.project(
                sessionSnapshot, unfilteredItems, preferences.getString(
                        "selected_playnite." + host.uuid, ""));
        resumePlayniteGameId = playniteSessionProjection.resumeGameId;
        suspendedPlayniteGameId = playniteSessionProjection.suspendedGameId;
        List<PlayniteLibraryGame> ordered = PlayniteLibraryOrdering.order(
                sourceFiltered, hostGatewayStore.playniteLibraryFilter(host.uuid),
                getResources().getConfiguration().getLocales().get(0), localActivity);
        ordered = promoteInstallingGames(host, ordered, sourceFiltered);
        List<PlayniteDashboardItem> items = new ArrayList<>();
        for (PlayniteLibraryGame game : ordered) {
            PlayniteDashboardItem item = resolvedById.get(game.playniteGameId);
            if (item != null) items.add(item);
        }
        if (CONSOLE_UI_V2) {
            items = putInstallingItemsFirst(host, items);
            items = putSelectedSessionFirst(items);
        } else {
            resumePlayniteGameId = "";
            suspendedPlayniteGameId = "";
        }
        allPlayniteItems = Collections.unmodifiableList(new ArrayList<>(items));
        unfilteredPlayniteItems = Collections.unmodifiableList(
                new ArrayList<>(unfilteredItems));
        List<PlayniteDashboardItem> carouselSource = CONSOLE_UI_V2
                ? putSelectedSessionFirst(unfilteredItems) : unfilteredItems;
        List<PlayniteDashboardItem> dashboardItems = carouselPlayniteItems(
                host, carouselSource, maxCarouselGameCount());
        String sessionSignature = playniteSessionProjection.signature()
                + runningGameSignature(host);
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
        updateHostSelector();
        updatePlayniteLibraryStatus(host);
        initialLibraryPresentation = new InitialLibraryPresentation(
                host.uuid, currentPlayniteGames, currentSunshineApps, allPlayniteItems,
                unfilteredPlayniteItems, renderedPlayniteItems,
                playniteSessionProjection, resumePlayniteGameId,
                suspendedPlayniteGameId, renderedCarouselSessionSignature,
                playniteLibraryCachedAt);
        if (pendingExpandedLibraryRestore && CONSOLE_UI_V2
                && !unfilteredItems.isEmpty()) {
            if (!requiresPreparedInitialCarouselFrame()) {
                pendingExpandedLibraryRestore = false;
                appRow.post(() -> enterExpandedLibrary(true));
            }
        } else {
            requestPendingInitialGameFocus(host);
        }
    }

    private void requestPendingInitialGameFocus(ComputerDetails host) {
        if (!pendingInitialGameFocus || hostSelectionVisible || host == null || appRow == null
                || !host.uuid.equals(selectedHostUuid)) return;
        View target = firstFocusableChild(appRow);
        if (target == null) return;
        pendingInitialGameFocus = false;
        View resolved = target;
        resolved.post(resolved::requestFocus);
    }

    private int maxCarouselGameCount() {
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        int available = Math.max(1, (int) widthDp - 108);
        int pitch = CAROUSEL_CARD_WIDTH_DP + CAROUSEL_CARD_GAP_DP;
        int focusedGrowth = CAROUSEL_FOCUSED_CARD_WIDTH_DP - CAROUSEL_CARD_WIDTH_DP;
        int slots = Math.max(2, (available - focusedGrowth) / pitch);
        return Math.max(1, slots + 3);
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
            else if (item.game.installed
                    && carouselActivityEpoch(host.uuid, item) > 0L) recent.add(item);
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
        for (PlayniteDashboardItem item : items) {
            if (result.size() >= limit) break;
            if (item.game.installed && included.add(item.stableId())) result.add(item);
        }
        return result;
    }

    private long carouselActivityEpoch(String hostUuid, PlayniteDashboardItem item) {
        long playActivity = playActivityEpoch(hostUuid, item);
        long installActivity = preferences.getLong(
                playniteCarouselInstallActivityKey(hostUuid, item.stableId()), 0L);
        return Math.max(playActivity, installActivity);
    }

    private long playActivityEpoch(String hostUuid, PlayniteDashboardItem item) {
        long playActivity = PlayniteLibraryOrdering.activityEpoch(item.game.lastActivity);
        long localLaunchActivity = preferences.getLong(
                playniteGameHistoryKey(hostUuid, item.stableId()), 0L);
        return Math.max(playActivity, localLaunchActivity);
    }

    private static String playniteCarouselInstallActivityKey(String hostUuid, String gameId) {
        return "playnite_carousel_install_activity." + hostUuid + ':' + gameId;
    }

    private void addFullLibraryCard() {
        View existing = directChildWithTag(appRow, "playnite:__library__");
        if (existing != null) appRow.removeView(existing);
        LinearLayout card = cardBase(dp(CAROUSEL_CARD_WIDTH_DP),
                dp(CAROUSEL_CARD_HEIGHT_DP));
        card.setTag("playnite:__library__");
        card.setForeground(carouselCardForeground());
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
            if (focused) {
                libraryTransitionCoordinator.beginTransition("__library__");
                positionCarouselMetadata(card);
                showLibraryMetadataPlaceholder();
            } else {
                hideCarouselMetadataOutsideCarousel();
            }
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
        if (host != null) expandedLibraryItems(host);
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
        int transitionIndex = host == null ? -1
                : expandedLibraryQuery.indexOf(libraryTransitionGameId);
        pendingExpandedFocusIndex = transitionIndex >= 0 ? transitionIndex : 0;
        int columns = Math.max(1, expandedGridColumns());
        expandedGridWindowStartRow = Math.max(0,
                pendingExpandedFocusIndex / columns - EXPANDED_WINDOW_ROWS / 2);
        if (host != null) renderExpandedLibrary(host);
        if (reducedMotion || suppressInitialCarouselMotion) {
            setNormalLibraryVisibility(View.GONE);
            expandedLibrary.setAlpha(1f);
            expandedLibrary.setTranslationX(0f);
            expandedLibrary.setVisibility(View.VISIBLE);
            focusExpandedLibrary();
            return;
        }
        libraryTransitionRunning = true;
        libraryTransitionCoordinator.beginTransition(libraryTransitionGameId);
        expandedLibrary.setAlpha(0f);
        expandedLibrary.setTranslationX(dp(18));
        expandedLibrary.setVisibility(View.VISIBLE);
        expandedLibrary.animate().alpha(1f).translationX(0f)
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
        libraryTransitionCoordinator.beginTransition(libraryTransitionGameId);
        resetNormalLibraryTransforms();
        expandedLibrary.animate().alpha(0f).translationX(dp(28))
                .setDuration(LIBRARY_EXIT_TRANSITION_MS)
                .withEndAction(() -> {
                    expandedLibrary.setVisibility(View.GONE);
                    expandedLibrary.setAlpha(1f);
                    expandedLibrary.setTranslationX(0f);
                    expandedLibrary.setScaleX(1f);
                    expandedLibrary.setScaleY(1f);
                    releaseExpandedGrid();
                    libraryTransitionRunning = false;
                    revealNormalLibraryAfterTransition();
                }).start();
    }

    private void setNormalLibraryVisibility(int visibility) {
        dashboardHeader.setVisibility(visibility);
        View carousel = carouselStage != null ? carouselStage
                : appScroll != null ? appScroll : appVerticalScroll;
        if (carousel != null) carousel.setVisibility(visibility);
        if (visibility == View.VISIBLE) updateLibraryActionVisibility();
        else debugLibraryActions.setVisibility(visibility);
        selectedGameMetadata.setVisibility(visibility);
        debugLibrarySpacer.setVisibility(visibility);
        controllerScroll.setVisibility(visibility);
    }

    private List<View> normalLibraryViews() {
        List<View> views = new ArrayList<>();
        views.add(dashboardHeader);
        View carousel = carouselStage != null ? carouselStage
                : appScroll != null ? appScroll : appVerticalScroll;
        if (carousel != null) views.add(carousel);
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
        expandedLibraryItems(host);
        return expandedLibraryQuery.indexOf(gameId);
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
            if (suppressInitialCarouselMotion) {
                focusTarget.requestFocus();
                return;
            }
            focusTarget.post(() -> {
                focusTarget.requestFocus();
                smoothRevealExpandedCard(focusTarget);
            });
        }
    }

    private void renderExpandedLibrary(ComputerDetails host) {
        if (!expandedLibraryMode || expandedGrid == null || host == null) return;
        String sessionSignature = playniteSessionProjection == null
                ? "" : playniteSessionProjection.signature();
        sessionSignature += runningGameSignature(host);
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
            focusedItem = expandedLibraryQuery.indexOf(stableId);
            if (focusedItem < 0 && !expandedItems.isEmpty()) {
                int previousLocal = PlayniteLibraryQuery.indexOf(
                        renderedExpandedItems, stableId);
                if (previousLocal >= 0) {
                    focusedItem = Math.min(expandedItems.size() - 1,
                            renderedExpandedWindowStartRow * columns + previousLocal);
                    pendingExpandedFocusIndex = focusedItem;
                }
            }
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
        boolean retainingFocusedCard = preservingGridFocus
                && PlayniteLibraryQuery.indexOf(windowItems,
                ((String) focusedTag).substring("playnite:".length())) >= 0;
        if (preservingGridFocus && !retainingFocusedCard) {
            expandedFocusTransitionInProgress = true;
            expandedGrid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            expandedGrid.setFocusable(true);
            expandedGrid.requestFocus();
        }
        for (int childIndex = expandedGrid.getChildCount() - 1;
             childIndex >= 0; childIndex--) {
            Object childTag = expandedGrid.getChildAt(childIndex).getTag();
            if (!(childTag instanceof String)
                    || !((String) childTag).startsWith("playnite:")) {
                expandedGrid.removeViewAt(childIndex);
            }
        }
        expandedGrid.setColumnCount(columns);
        boolean hasPreviousRows = expandedGridWindowStartRow > 0;
        boolean hasMoreRows = windowEndRow < totalRows;
        int cardRowOffset = hasPreviousRows ? 2 : 1;
        int rowCount = Math.max(2,
                windowEndRow - expandedGridWindowStartRow + 5);
        if (rowCount > expandedGrid.getRowCount()) expandedGrid.setRowCount(rowCount);
        View topSpacer = new View(this);
        GridLayout.LayoutParams topParams = new GridLayout.LayoutParams(
                GridLayout.spec(0), GridLayout.spec(0));
        topParams.width = 1;
        topParams.height = dp(Math.max(0, expandedGridWindowStartRow
                - (hasPreviousRows ? 1 : 0)) * 131);
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
            } else if (!item.equals(previousItem)
                    || sessionPresentationChanged
                    || isVibepolloEnsureInFlight(host.uuid, item)
                    || isPlayniteInstalling(host.uuid, item)) {
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
            params.width = dp(92);
            params.height = dp(123);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            if (card.getParent() == expandedGrid) card.setLayoutParams(params);
            else expandedGrid.addView(card, params);
            gridCards.add(card);
            ImageView poster = (ImageView) findTaggedChild((ViewGroup) card, "playnite.poster");
            if (poster != null && needsArtwork) loadPlaynitePoster(host, item, poster, false);
        }
        for (View discarded : reusableCards.values()) {
            releaseExpandedCardArtwork(discarded);
            expandedGrid.removeView(discarded);
        }
        if (hasMoreRows) {
            addExpandedLoadingGhostRow(cardRowOffset
                    + windowEndRow - expandedGridWindowStartRow, columns);
        }
        View bottomSpacer = new View(this);
        GridLayout.LayoutParams bottomParams = new GridLayout.LayoutParams(
                GridLayout.spec(cardRowOffset + windowEndRow
                        - expandedGridWindowStartRow + (hasMoreRows ? 1 : 0)),
                GridLayout.spec(0));
        bottomParams.width = 1;
        bottomParams.height = dp(Math.max(0, totalRows - windowEndRow
                - (hasMoreRows ? 1 : 0)) * 131);
        expandedGrid.addView(bottomSpacer, bottomParams);
        if (rowCount < expandedGrid.getRowCount()) expandedGrid.setRowCount(rowCount);
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
        return expandedLibraryQuery.apply(allPlayniteItems, expandedSearchQuery,
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
        int index = expandedLibraryQuery.indexOf(stableId);
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
            params.width = dp(92);
            params.height = dp(123);
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
        mainHandler.post(expandedWindowWarmupRunnable);
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
                int actual = expandedLibraryQuery.indexOf(
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
                    expandedGrid.setFocusable(false);
                    Runnable focusTransitionTimeout = () ->
                            expandedFocusTransitionInProgress = false;
                    mainHandler.postDelayed(focusTransitionTimeout,
                            EXPANDED_FOCUS_TRANSITION_TIMEOUT_MS);
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
        return Math.max(3, available / 100);
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

    private List<PlayniteDashboardItem> putSelectedSessionFirst(
            List<PlayniteDashboardItem> items) {
        String selectedGameId = !resumePlayniteGameId.isEmpty()
                ? resumePlayniteGameId : suspendedPlayniteGameId;
        if (selectedGameId.isEmpty() || items.isEmpty()) return items;
        for (PlayniteDashboardItem item : items) {
            if (!selectedGameId.equalsIgnoreCase(item.stableId())) continue;
            if (items.get(0) == item) return items;
            List<PlayniteDashboardItem> reordered = new ArrayList<>(items);
            reordered.remove(item);
            reordered.add(0, item);
            return reordered;
        }
        return items;
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
        View currentFocus = getCurrentFocus();
        boolean carouselFocused = currentFocus != null && appRow != null
                && isDescendant(appRow, currentFocus);
        Object focusTag = carouselFocused ? currentFocus.getTag() : null;
        String focusedId = focusTag instanceof String &&
                ((String) focusTag).startsWith("playnite:")
                ? ((String) focusTag).substring("playnite:".length()) : null;
        int previousIndex = carouselFocused
                ? Math.max(0, appRow.indexOfChild(currentFocus)) : 0;

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
            if (suppressInitialCarouselMotion) {
                ImageView poster = (ImageView) findTaggedChild(
                        (ViewGroup) card, "playnite.poster");
                restoreCachedPlaynitePoster(host, item, poster);
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
                old.game.operationProgress != item.game.operationProgress ||
                old.game.uninstalling != item.game.uninstalling ||
                !old.game.operationState.equals(item.game.operationState) ||
                !old.game.installAttentionReason.equals(item.game.installAttentionReason) ||
                !old.game.installWindowTitle.equals(item.game.installWindowTitle) ||
                !old.game.installLauncher.equals(item.game.installLauncher) ||
                old.game.playtimeSeconds != item.game.playtimeSeconds ||
                old.game.playCount != item.game.playCount ||
                !old.game.description.equals(item.game.description) ||
                !old.game.source.equals(item.game.source) ||
                !old.game.libraryKey.equals(item.game.libraryKey) ||
                !old.game.libraryName.equals(item.game.libraryName)) {
            payload |= PlayniteLibraryDiff.TEXT;
        }
        if (!old.game.coverKey.equals(item.game.coverKey) ||
                !old.game.backgroundKey.equals(item.game.backgroundKey) ||
                !old.game.heroKey.equals(item.game.heroKey)) {
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
                dp(portraitLayout ? 220 : expandedCard ? CAROUSEL_FOCUSED_CARD_WIDTH_DP
                        : debugCarousel ? CAROUSEL_CARD_WIDTH_DP : 205),
                dp(portraitLayout ? 225 : expandedCard ? CAROUSEL_FOCUSED_CARD_HEIGHT_DP
                        : debugCarousel ? CAROUSEL_CARD_HEIGHT_DP : 190));
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.TOP);
        card.setPadding(0, 0, 0, 0);
        card.setClipToOutline(true);
        if (debugCarousel) card.setForeground(carouselCardForeground());
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
        poster.setPadding(dp(expandedCard ? 20 : debugCarousel ? 24 : 72),
                dp(expandedCard ? 29 : debugCarousel ? 36 : 54),
                dp(expandedCard ? 20 : debugCarousel ? 24 : 72),
                dp(expandedCard ? 29 : debugCarousel ? 36 : 54));
        artworkFrame.addView(poster, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (debugCarousel && !expandedCard) {
            View shine = new View(this);
            shine.setTag("playnite.shine");
            shine.setAlpha(0f);
            shine.setRotation(14f);
            shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x00FFFFFF, 0x5CFFFFFF, 0x00FFFFFF}));
            shine.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams shineParams = new FrameLayout.LayoutParams(
                    dp(24), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
            shineParams.leftMargin = -dp(32);
            artworkFrame.addView(shine, shineParams);
        }
        ImageView source = new ImageView(this);
        source.setTag("playnite.source");
        source.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        source.setPadding(dp(2), dp(2), dp(2), dp(2));
        source.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        TextView running = text(getString(R.string.playnite_running_badge), 8,
                Color.WHITE, true);
        running.setTag("playnite.running");
        running.setSingleLine(true);
        if (debugCarousel && !expandedCard) running.setTextSize(6);
        running.setPadding(dp(5), dp(2), dp(5), dp(2));
        running.setBackground(gradient(0xEE147D75, 0xEE0E625D, 5));
        running.setVisibility(View.GONE);
        running.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams runningParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        runningParams.leftMargin = dp(5);
        runningParams.topMargin = dp(5);
        artworkFrame.addView(running, runningParams);
        ProgressBar installProgress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        installProgress.setTag("playnite.install.progress");
        installProgress.setMax(100);
        installProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                0xFFE4B34B));
        installProgress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x70404950));
        installProgress.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(0xFFE4B34B));
        installProgress.setVisibility(View.GONE);
        installProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM);
        artworkFrame.addView(installProgress, progressParams);
        TextView state = text("", debugCarousel ? 8 : 10, 0xFF929BAD, false);
        state.setTag("playnite.state");
        state.setSingleLine(true);
        state.setMaxWidth(dp(debugCarousel ? 70 : expandedCard ? 118 : 170));
        state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (debugCarousel) {
            FrameLayout.LayoutParams stateParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            stateParams.rightMargin = dp(8);
            stateParams.bottomMargin = dp(8);
            artworkFrame.addView(state, stateParams);
        }
        View copyScrim = new View(this);
        copyScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xE6000000}));
        copyScrim.setVisibility(debugCarousel && expandedCard ? View.VISIBLE : View.GONE);
        artworkFrame.addView(copyScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38), Gravity.BOTTOM));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.HORIZONTAL);
        copy.setGravity(Gravity.BOTTOM | Gravity.CENTER_VERTICAL);
        copy.setPadding(dp(CAROUSEL_CARD_GAP_DP), dp(2),
                dp(CAROUSEL_CARD_GAP_DP), dp(5));
        copy.addView(source, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView name = text("", debugCarousel ? 12 : 19, Color.WHITE, true);
        name.setTag("playnite.name");
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setShadowLayer(dp(2), 0, dp(1), 0xF0000000);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dp(CAROUSEL_CARD_GAP_DP);
        copy.addView(name, nameParams);
        TextView playtime = text("", debugCarousel ? 8 : 10, 0xFFB8C9DC, false);
        playtime.setTag("playnite.playtime");
        playtime.setVisibility(View.GONE);
        copy.addView(playtime, matchLinearWidth());
        if (!debugCarousel) {
            LinearLayout.LayoutParams stateParams = wrapLinear();
            stateParams.topMargin = dp(5);
            copy.addView(state, stateParams);
        }
        if (debugCarousel) {
            copy.setVisibility(expandedCard ? View.VISIBLE : View.GONE);
            artworkFrame.addView(copy, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(34), Gravity.BOTTOM));
            card.addView(artworkFrame, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            card.addView(artworkFrame, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(portraitLayout ? 140 : 115)));
            card.addView(copy, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
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
                        dp(CONSOLE_UI_V2 ? CAROUSEL_CARD_WIDTH_DP : 205),
                        dp(CONSOLE_UI_V2 ? CAROUSEL_CARD_HEIGHT_DP : 190));
        if (portraitLayout) params.bottomMargin = dp(14);
        else params.rightMargin = dp(CONSOLE_UI_V2 ? CAROUSEL_CARD_GAP_DP : 14);
        return params;
    }

    private int playniteSourceIcon(String source) {
        switch (PlayniteLibrarySources.key(source)) {
            case "steam":
                return R.drawable.ic_source_steam;
            case "epic":
            case "epic games":
                return R.drawable.ic_source_epic_games;
            case PlayniteLibrarySources.PLAYNITE_KEY:
                return R.drawable.ic_source_playnite;
            default:
                return 0;
        }
    }

    private PlayniteSessionPresentation.State playniteSessionState(
            PlayniteDashboardItem item) {
        return playniteSessionProjection == null
                ? PlayniteSessionPresentation.State.READY
                : playniteSessionProjection.stateFor(item.stableId());
    }

    private boolean isFreshExactRunningManagedGame(ComputerDetails host,
                                                    PlayniteDashboardItem item) {
        if (host == null || item == null) return false;
        String stateKey = profileStateKey(host.uuid);
        RunningGameObservation observation = runningGameObservations.get(stateKey);
        if (observation != null && observation.inventory != null) {
            return freshRunningGame(host, item.stableId()) != null;
        }
        return isFreshExactBridgeRunning(selectedHostUuid, host.uuid,
                host.runningGameId, item.stableId(),
                activePlayniteGameStates.get(stateKey),
                activePlayniteGameIds.get(stateKey),
                activePlayniteGameAppIds.getOrDefault(stateKey, Integer.MIN_VALUE),
                activePlayniteGameResolvedAt.getOrDefault(stateKey, 0L),
                SystemClock.uptimeMillis(), ACTIVE_GAME_OBSERVATION_TTL_MS);
    }

    private HostGatewayClient.RunningGame freshRunningGame(ComputerDetails host, String gameId) {
        if (host == null || !host.uuid.equalsIgnoreCase(selectedHostUuid)) return null;
        RunningGameObservation observation = runningGameObservations.get(
                profileStateKey(host.uuid));
        GatewayConnection connection = hostGatewayStore.loadForHost(host.uuid,
                host.activeAddress == null ? null : host.activeAddress.address,
                selectedProfileId(host.uuid));
        return observation != null && sameGatewayProfile(observation.connection, connection)
                ? observation.find(gameId, SystemClock.uptimeMillis()) : null;
    }

    private String runningGameSignature(ComputerDetails host) {
        RunningGameObservation observation = host == null ? null
                : runningGameObservations.get(profileStateKey(host.uuid));
        if (observation == null || observation.inventory == null) return "";
        GatewayConnection connection = hostGatewayStore.loadForHost(host.uuid,
                host.activeAddress == null ? null : host.activeAddress.address,
                selectedProfileId(host.uuid));
        if (!host.uuid.equalsIgnoreCase(selectedHostUuid)
                || !sameGatewayProfile(observation.connection, connection)) return "|running:";
        return observation.signature(SystemClock.uptimeMillis());
    }

    static boolean isFreshExactBridgeRunning(
            String selectedHostId, String hostId, int expectedAppId, String expectedGameId,
            String cachedState, String cachedGameId, int cachedAppId,
            long resolvedAt, long now, long ttlMs) {
        return hostId != null && hostId.equalsIgnoreCase(selectedHostId)
                && "running".equalsIgnoreCase(cachedState)
                && SessionSnapshot.normalize(expectedGameId).equals(
                SessionSnapshot.normalize(cachedGameId))
                && !SessionSnapshot.normalize(expectedGameId).isEmpty()
                && PlayniteIdentityResolutionPolicy.isFreshObservation(
                expectedAppId, cachedAppId, resolvedAt, now, ttlMs);
    }

    private void bindPlayniteCard(View card, ComputerDetails host,
                                  PlayniteDashboardItem item, List<NvApp> apps, int payload) {
        card.setTag("playnite:" + item.stableId());
        styleCard(card, card.hasFocus());
        TextView name = (TextView) findTaggedChild((ViewGroup) card, "playnite.name");
        TextView playtime = (TextView) findTaggedChild((ViewGroup) card, "playnite.playtime");
        TextView state = (TextView) findTaggedChild((ViewGroup) card, "playnite.state");
        ImageView source = (ImageView) findTaggedChild((ViewGroup) card, "playnite.source");
        TextView running = (TextView) findTaggedChild((ViewGroup) card, "playnite.running");
        ProgressBar installProgress = (ProgressBar) findTaggedChild(
                (ViewGroup) card, "playnite.install.progress");
        ImageView poster = (ImageView) findTaggedChild((ViewGroup) card, "playnite.poster");
        ImageView backdrop = (ImageView) findTaggedChild(
                (ViewGroup) card, "playnite.poster.backdrop");
        String playtimeText = formatPlayniteTime(item.game.playtimeSeconds);
        PlayniteSessionPresentation.State sessionState = playniteSessionState(item);
        boolean resumeSession = sessionState
                == PlayniteSessionPresentation.State.RESUME_ACTIVE;
        boolean runningSession = isFreshExactRunningManagedGame(host, item);
        boolean suspendedSession = sessionState
                == PlayniteSessionPresentation.State.RESUME_SUSPENDED;
        stylePlayniteSessionCard(card, card.hasFocus(), resumeSession || suspendedSession);
        running.setVisibility(runningSession ? View.VISIBLE : View.GONE);
        running.setContentDescription(getString(
                R.string.playnite_running_badge_description, item.game.name));
        GameOperationsController.Presentation operation =
                gameOperationsController.presentation(host.uuid, item.game);
        boolean installing = operation.installActive || operation.uninstallActive;
        card.setAlpha(playniteCardAlpha(item, operation,
                resumeSession || suspendedSession, card.hasFocus()));
        if (!item.game.installed && !installing) {
            android.graphics.ColorMatrix colors = new android.graphics.ColorMatrix();
            colors.setSaturation(.25f);
            android.graphics.ColorMatrixColorFilter muted =
                    new android.graphics.ColorMatrixColorFilter(colors);
            poster.setColorFilter(muted);
            if (backdrop != null) backdrop.setColorFilter(muted);
        } else {
            poster.clearColorFilter();
            if (backdrop != null) backdrop.clearColorFilter();
        }
        installProgress.setVisibility(installing ? View.VISIBLE : View.GONE);
        installProgress.setIndeterminate(installing && operation.progress < 0);
        if (operation.progress >= 0) installProgress.setProgress(operation.progress);
        String stateText = suspendedSession ? getString(R.string.console_resume_suspended)
                : resumeSession ? getString(R.string.console_resume_session)
                : playniteState(host.uuid, item);
        if ((payload & PlayniteLibraryDiff.TEXT) != 0) {
            name.setText(item.game.name);
            playtime.setText(playtimeText);
            String sourceKey = item.game.libraryKey.isEmpty()
                    ? item.game.source : item.game.libraryKey;
            int sourceIcon = playniteSourceIcon(sourceKey);
            source.setImageResource(sourceIcon);
            source.setVisibility(sourceIcon == 0 ? View.GONE : View.VISIBLE);
            GradientDrawable sourceBackground = gradient(0xD9182027, 0xE00C1116, 4);
            sourceBackground.setStroke(dp(1), 0xB3FFFFFF);
            source.setBackground(sourceBackground);
        }
        if ((payload & (PlayniteLibraryDiff.TEXT | PlayniteLibraryDiff.LAUNCH)) != 0) {
            String glyph = suspendedSession ? "◷"
                    : playniteStateGlyph(host.uuid, item, resumeSession);
            String label = suspendedSession ? getString(R.string.console_resume)
                    : playniteStateChipLabel(host.uuid, item, resumeSession);
            state.setText(playniteStateChipText(glyph, label, resumeSession));
            stylePlayniteStateChip(state, host.uuid, item, resumeSession || suspendedSession);
        }
        stylePlayniteStateEmphasis(state, item, operation,
                resumeSession || suspendedSession, card.hasFocus());
        state.post(() -> stylePlayniteStateEmphasis(state, item,
                gameOperationsController.presentation(host.uuid, item.game),
                playniteSessionState(item) != PlayniteSessionPresentation.State.READY,
                card.hasFocus()));
        if ((payload & PlayniteLibraryDiff.ARTWORK) != 0) resetPlaynitePoster(poster, item);
        String installKey = playniteInstallKey(host.uuid, item);
        if (completedPlayniteInstallAnimations.remove(installKey)) {
            card.animate().cancel();
            float restingAlpha = playniteCardAlpha(item, operation,
                    resumeSession || suspendedSession, card.hasFocus());
            if (reducedMotion) {
                card.setAlpha(restingAlpha);
            } else {
                card.setAlpha(.55f);
                card.setTranslationY(dp(5));
                card.animate().alpha(restingAlpha).translationY(0f)
                        .setDuration(420L).start();
            }
        }
        card.setOnClickListener(view -> activatePlayniteItem(host.uuid, item));
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
        String sourceName = PlayniteLibrarySources.label(item.game.libraryName.isEmpty()
                ? item.game.source : item.game.libraryName);
        card.setContentDescription(getString(R.string.playnite_card_description,
                item.game.name, playtimeText, stateText) + ". "
                + getString(R.string.playnite_source, sourceName)
                + (runningSession ? ". " + getString(
                R.string.playnite_running_badge_description, item.game.name) : ""));
        card.setOnFocusChangeListener((view, focused) -> {
            stylePlayniteSessionCard(card, focused, resumeSession || suspendedSession);
            GameOperationsController.Presentation focusedOperation =
                    gameOperationsController.presentation(host.uuid, item.game);
            card.setAlpha(playniteCardAlpha(item, focusedOperation,
                    resumeSession || suspendedSession, focused));
            stylePlayniteStateEmphasis(state, item, focusedOperation,
                    resumeSession || suspendedSession, focused);
            updateCarouselMarquee(name, focused);
            if (focused) {
                if (!expandedLibraryMode && card.getParent() == appRow) {
                    if (selectedGameTitleRow != null) {
                        selectedGameTitleRow.setVisibility(View.INVISIBLE);
                    }
                    positionCarouselMetadata(card);
                }
                libraryTransitionCoordinator.beginSelection(item.stableId(),
                        () -> {
                            prepareGameMetadataTransition();
                            loadPlaynitePoster(host, item, poster, true);
                        },
                        () -> showGameMetadataHeader(item),
                        () -> showGameMetadataDescription(item));
                if (!expandedLibraryMode && card.getParent() == appRow) {
                    lastCarouselGameId = item.stableId();
                    schedulePlayniteSelectionSave(host.uuid, lastCarouselGameId, false);
                } else if (expandedLibraryMode && card.getParent() == expandedGrid) {
                    schedulePlayniteSelectionSave(host.uuid, item.stableId(), true);
                }
                if (expandedLibraryMode && card.getParent() == expandedGrid) {
                    if (!expandedFocusTransitionInProgress) {
                        smoothRevealExpandedCard(card);
                    }
                } else if (portraitLayout) {
                    smoothCenterOn(appVerticalScroll, card);
                } else {
                    smoothCenterOn(appScroll, card);
                    mainHandler.postDelayed(() -> {
                        if (card.hasFocus()) positionCarouselMetadata(card);
                    }, 280L);
                }
            } else {
                hideCarouselMetadataOutsideCarousel();
            }
        });
    }

    private float playniteCardAlpha(PlayniteDashboardItem item,
                                    GameOperationsController.Presentation operation,
                                    boolean resumeSession, boolean focused) {
        if (operation.attentionRequired) return .94f;
        if (operation.installActive || operation.uninstallActive || !item.game.installed) {
            return .88f;
        }
        return focused || resumeSession ? 1f : .93f;
    }

    private void stylePlayniteStateEmphasis(TextView state, PlayniteDashboardItem item,
                                            GameOperationsController.Presentation operation,
                                            boolean resumeSession, boolean focused) {
        boolean ordinaryReady = !resumeSession && !operation.attentionRequired
                && !operation.installActive && !operation.uninstallActive
                && item.game.installed
                && item.mappingState == PlayniteDashboardItem.MappingState.MAPPED;
        boolean homeCarousel = appRow != null && isDescendant(appRow, state)
                && !expandedLibraryMode;
        state.setVisibility(homeCarousel && ordinaryReady ? View.GONE : View.VISIBLE);
        state.setAlpha(ordinaryReady && !focused ? .48f : 1f);
    }

    private void schedulePlayniteSelectionSave(String hostUuid, String gameId,
                                                boolean expanded) {
        if (hostUuid == null || hostUuid.isEmpty() || gameId == null || gameId.isEmpty()) return;
        if (pendingPlayniteSelectionSave != null
                && (!hostUuid.equals(pendingPlayniteSelectionHostUuid)
                || expanded != pendingPlayniteSelectionExpanded)) {
            flushPendingPlayniteSelection();
        } else if (pendingPlayniteSelectionSave != null) {
            mainHandler.removeCallbacks(pendingPlayniteSelectionSave);
        }
        pendingPlayniteSelectionHostUuid = hostUuid;
        pendingPlayniteSelectionGameId = gameId;
        pendingPlayniteSelectionExpanded = expanded;
        pendingPlayniteSelectionSave = this::flushPendingPlayniteSelection;
        mainHandler.postDelayed(pendingPlayniteSelectionSave,
                PLAYNITE_SELECTION_SAVE_DELAY_MS);
    }

    private void flushPendingPlayniteSelection() {
        if (pendingPlayniteSelectionSave == null) return;
        mainHandler.removeCallbacks(pendingPlayniteSelectionSave);
        pendingPlayniteSelectionSave = null;
        String hostUuid = pendingPlayniteSelectionHostUuid;
        String gameId = pendingPlayniteSelectionGameId;
        boolean expanded = pendingPlayniteSelectionExpanded;
        pendingPlayniteSelectionHostUuid = null;
        pendingPlayniteSelectionGameId = null;
        if (expanded) libraryViewStateStore.saveExpandedGame(hostUuid, gameId);
        else libraryViewStateStore.saveCarouselGame(hostUuid, gameId);
        String preferenceKey = "selected_playnite." + hostUuid;
        if (!gameId.equals(preferences.getString(preferenceKey, ""))) {
            preferences.edit().putString(preferenceKey, gameId).apply();
        }
    }

    private void stylePlayniteSessionCard(View card, boolean focused, boolean resumeSession) {
        styleCard(card, focused);
        if (!resumeSession) return;
        GradientDrawable background = gradient(
                focused ? 0xF0203C43 : 0xE0173037,
                focused ? 0xF011252B : 0xE00D2026, 12);
        background.setStroke(dp(1), focused ? 0xFFEAFBFF : 0xFF54D4DE);
        card.setBackground(background);
        card.setElevation(dp(focused ? 14 : 8));
        boolean homeCarousel = card.getParent() == appRow && !expandedLibraryMode;
        card.setPivotX(card.getWidth() > 0 ? card.getWidth() / 2f : dp(42));
        float scale = homeCarousel ? 1f : focused ? 1.04f : 1.02f;
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
        if (selectedGameTitleRow != null) {
            selectedGameTitleRow.setVisibility(View.VISIBLE);
        }
        if (selectedGameSource != null) {
            String sourceKey = item.game.libraryKey.isEmpty()
                    ? item.game.source : item.game.libraryKey;
            String sourceLabel = PlayniteLibrarySources.label(item.game.libraryName.isEmpty()
                    ? item.game.source : item.game.libraryName);
            int sourceIcon = playniteSourceIcon(sourceKey);
            selectedGameSource.setImageResource(sourceIcon);
            selectedGameSource.setContentDescription(getString(
                    R.string.playnite_source, sourceLabel));
            selectedGameSource.setVisibility(sourceIcon == 0 ? View.GONE : View.VISIBLE);
        }
        String played = formatLastActivity(item.game.lastActivity);
        String playtime = formatPlayniteTime(item.game.playtimeSeconds);
        String facts = getString(R.string.playnite_selected_game_facts, played, playtime);
        if (selectedGameFactsRow != null) {
            selectedGameFacts.setVisibility(View.GONE);
            selectedGameFactsRow.setVisibility(View.VISIBLE);
            selectedGameLastPlayedPill.setText(getString(
                    R.string.playnite_last_played_pill, played));
            selectedGamePlaytimePill.setText(getString(
                    R.string.playnite_playtime_pill, playtime));
            selectedGamePlaytimePill.setContentDescription(
                    getString(R.string.playnite_playtime) + ": " + playtime);
        }
        bindGameMetadataHeader(selectedGameMetadata, selectedGameTitle,
                selectedGameFacts, item.game.name, facts);
        bindGameMetadataHeader(expandedLibrary, expandedGameTitle,
                expandedGameFacts, item.game.name, facts);
    }

    private void prepareGameMetadataTransition() {
        if (reducedMotion) return;
        prepareMetadataViewsForTransition(selectedGameTitle, selectedGameFactsRow,
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
        View factsView = panel == selectedGameMetadata
                && selectedGameFactsRow != null
                && selectedGameFactsRow.getVisibility() == View.VISIBLE
                ? selectedGameFactsRow : facts;
        if (panel == selectedGameMetadata && !expandedLibraryMode) {
            panel.setVisibility(View.VISIBLE);
            if (selectedGameTitleRow != null) {
                selectedGameTitleRow.setVisibility(View.VISIBLE);
            }
        }
        title.animate().cancel();
        factsView.animate().cancel();
        if (!reducedMotion) {
            title.setAlpha(0f);
            title.setTranslationY(dp(4));
            factsView.setAlpha(0f);
            factsView.setTranslationY(dp(3));
        }
        title.animate().alpha(1f).translationY(0f)
                .setDuration(reducedMotion ? 0L : 150L).start();
        factsView.animate().alpha(1f).translationY(0f)
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
            if (selectedGameTitleRow != null) {
                selectedGameTitleRow.setVisibility(View.VISIBLE);
            }
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
        if (selectedGameSource != null) selectedGameSource.setVisibility(View.GONE);
        if (selectedGameFactsRow != null) selectedGameFactsRow.setVisibility(View.GONE);
        if (selectedGameFacts != null) selectedGameFacts.setVisibility(View.GONE);
        bindGameMetadata(selectedGameMetadata, selectedGameTitle,
                selectedGameFacts, selectedGameDescription,
                getString(R.string.playnite_full_library), "", "");
    }

    private void positionCarouselMetadata(View card) {
        if (selectedGameTitleRow == null || card == null
                || !(selectedGameTitleRow.getParent() instanceof FrameLayout)) return;
        FrameLayout stage = (FrameLayout) selectedGameTitleRow.getParent();
        stage.post(() -> {
            if (card.getParent() != appRow || !card.hasFocus()
                    || stage.getWidth() == 0) return;
            int[] cardLocation = new int[2];
            int[] stageLocation = new int[2];
            card.getLocationInWindow(cardLocation);
            stage.getLocationInWindow(stageLocation);
            int left = cardLocation[0] - stageLocation[0]
                    + dp(CAROUSEL_FOCUSED_CARD_WIDTH_DP)
                    + dp(CAROUSEL_CARD_GAP_DP);
            int width = Math.min(dp(430), Math.max(dp(240),
                    stage.getWidth() - left - dp(12)));
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) selectedGameTitleRow.getLayoutParams();
            params.leftMargin = Math.max(dp(104),
                    Math.min(left, stage.getWidth() - width - dp(12)));
            params.width = width;
            selectedGameTitleRow.setLayoutParams(params);
        });
    }

    private void hideCarouselMetadataOutsideCarousel() {
        if (selectedGameTitleRow == null) return;
        selectedGameTitleRow.post(() -> {
            View focus = getCurrentFocus();
            if (focus == null || appRow == null || !isDescendant(appRow, focus)) {
                selectedGameTitleRow.setVisibility(View.INVISIBLE);
                if (focus == null || expandedGrid == null
                        || !isDescendant(expandedGrid, focus)) {
                    libraryTransitionCoordinator.beginTransition("");
                }
            }
        });
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
        GameOperationsController.Presentation operation =
                gameOperationsController.presentation(hostUuid, item.game);
        if (operation.attentionRequired) {
            return getString(R.string.playnite_install_attention);
        }
        if (operation.uninstallActive) {
            return getString(R.string.playnite_uninstalling);
        }
        if (operation.installActive) {
            String label = getString(R.string.playnite_installing);
            return operation.progress >= 0
                    ? label + " " + operation.progress + "%" : label;
        }
        if (!item.game.installed) return getString(R.string.playnite_not_installed);
        if (isVibepolloEnsureInFlight(hostUuid, item)
                && item.mappingState != PlayniteDashboardItem.MappingState.MAPPED) {
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
        if (resumeSession) return "▶";
        GameOperationsController.Presentation operation =
                gameOperationsController.presentation(hostUuid, item.game);
        if (operation.attentionRequired) return "!";
        if (operation.uninstallActive) return "↓";
        if (operation.installActive) return "↓";
        if (!item.game.installed) return "↓";
        if (isVibepolloEnsureInFlight(hostUuid, item)
                && item.mappingState != PlayniteDashboardItem.MappingState.MAPPED) return "…";
        if (item.mappingState == PlayniteDashboardItem.MappingState.MAPPED) return "✓";
        if (item.mappingState == PlayniteDashboardItem.MappingState.FALLBACK_PLAYNITE) {
            return "↗";
        }
        return "!";
    }

    private String playniteStateChipLabel(String hostUuid, PlayniteDashboardItem item,
                                          boolean resumeSession) {
        if (resumeSession) return getString(R.string.console_resume);
        GameOperationsController.Presentation operation =
                gameOperationsController.presentation(hostUuid, item.game);
        if (operation.attentionRequired) {
            return getString(R.string.playnite_install_attention_short);
        }
        if (operation.uninstallActive) {
            return getString(R.string.playnite_uninstalling);
        }
        if (operation.installActive) {
            String label = getString(R.string.playnite_installing);
            return operation.progress >= 0
                    ? CONSOLE_UI_V2 ? operation.progress + "%"
                    : label + " " + operation.progress + "%" : label;
        }
        if (!item.game.installed) return CONSOLE_UI_V2 ? ""
                : getString(R.string.playnite_install_action_short);
        if (isVibepolloEnsureInFlight(hostUuid, item)
                && item.mappingState != PlayniteDashboardItem.MappingState.MAPPED) {
            return getString(R.string.playnite_preparing_short);
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.MAPPED) {
            return CONSOLE_UI_V2 ? "" : getString(R.string.playnite_ready_short);
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.FALLBACK_PLAYNITE) {
            return CONSOLE_UI_V2 ? "" : getString(R.string.playnite_via_playnite_short);
        }
        return getString(R.string.playnite_configuration_short);
    }

    private CharSequence playniteStateChipText(String glyph, String label,
                                               boolean resumeSession) {
        SpannableString text = new SpannableString(
                label.isEmpty() ? glyph : glyph + "  " + label);
        if (resumeSession && !glyph.isEmpty()) {
            text.setSpan(new RelativeSizeSpan(1.5f), 0, glyph.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private void stylePlayniteStateChip(TextView state, String hostUuid,
                                        PlayniteDashboardItem item,
                                        boolean resumeSession) {
        GameOperationsController.Presentation operation =
                gameOperationsController.presentation(hostUuid, item.game);
        boolean installing = operation.installActive || operation.uninstallActive;
        boolean unavailable = item.game.installed
                && item.mappingState != PlayniteDashboardItem.MappingState.MAPPED
                && item.mappingState != PlayniteDashboardItem.MappingState.FALLBACK_PLAYNITE
                && !isVibepolloEnsureInFlight(hostUuid, item);
        int textColor;
        int fill;
        int stroke;
        if (resumeSession) {
            textColor = 0xFFE1F7FF; fill = 0xE0142933; stroke = 0xFF73D7FF;
        } else if (operation.attentionRequired) {
            textColor = 0xFFFFE1A6; fill = 0xE03D2B0A; stroke = 0xFFE3A93F;
        } else if (installing) {
            textColor = 0xFFFFE2A8; fill = 0xE03D2B0A; stroke = 0xFFE4B34B;
        } else if (!item.game.installed) {
            textColor = 0xFFE6EBEF; fill = 0xE0181E23; stroke = 0xFF9AA5AD;
        } else if (unavailable) {
            textColor = 0xFFFFC4BE; fill = 0xE0371B1A; stroke = 0xFFE16D63;
        } else if (isVibepolloEnsureInFlight(hostUuid, item)) {
            textColor = 0xFFFFE2A8; fill = 0xE03D2B0A; stroke = 0xFFE4B34B;
        } else {
            textColor = 0xFF8EF1AE; fill = 0xE014241B; stroke = 0xFF62C985;
        }
        state.setTextColor(textColor);
        state.setShadowLayer(dp(1), 0, dp(1), 0xE0000000);
        state.setElevation(dp(3));
        state.setPadding(dp(4), dp(1), dp(4), dp(1));
        GradientDrawable background = gradient(fill, fill, 6);
        background.setStroke(dp(1), stroke);
        state.setBackground(background);
    }

    private boolean isVibepolloEnsureInFlight(String hostUuid, PlayniteDashboardItem item) {
        // The Set above prevents duplicate requests only. The visible state comes
        // from the Gateway-enriched library so it survives an Android process restart.
        return "preparing".equals(item.game.vibepolloState);
    }

    private String playniteInstallKey(String hostUuid, PlayniteDashboardItem item) {
        return playniteInstallKey(hostUuid, item.game.playniteGameId);
    }

    private String playniteInstallKey(String hostUuid, String gameId) {
        return hostUuid + ":" + gameId;
    }

    private boolean isPlayniteInstalling(String hostUuid, PlayniteDashboardItem item) {
        return gameOperationsController.isInstalling(hostUuid, item.game);
    }

    private boolean isPlayniteInstalling(String hostUuid, PlayniteLibraryGame game) {
        return gameOperationsController.isInstalling(hostUuid, game);
    }

    private boolean isPlayniteUninstalling(String hostUuid, PlayniteDashboardItem item) {
        return gameOperationsController.isUninstalling(hostUuid, item.game);
    }

    private void resetPlaynitePoster(ImageView poster, PlayniteDashboardItem item) {
        ImageView backdrop = playnitePosterBackdrop(poster);
        if (backdrop != null) {
            backdrop.setImageDrawable(null);
            backdrop.setVisibility(View.GONE);
        }
        poster.setImageResource(R.drawable.ic_computer);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
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
        GatewayConnection connection =
                hostGatewayStore.loadForHost(latestHost.uuid, address);
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
        StringBuilder signature = new StringBuilder(
                selectedProfileKey(host.uuid).cacheKey());
        for (PlayniteDashboardItem item : items) {
            signature.append('|').append(item.stableId()).append(':')
                    .append(playniteCardArtworkSpec(item.game).cacheIdentity());
            if (CONSOLE_UI_V2 && backdropIds.contains(item.stableId())) {
                signature.append(':')
                        .append(PlayniteArtworkSpec.forBackdrop(
                                item.game).cacheIdentity()).append(':')
                        .append(PlayniteArtworkSpec.forLoadingCurtain(
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
        GatewayConnection connection =
                hostGatewayStore.loadForHost(latestHost.uuid, address,
                        selectedProfileId(latestHost.uuid));
        if (connection == null) return;
        int token = playniteArtworkGeneration.incrementAndGet();
        List<PlayniteDashboardItem> snapshot = new ArrayList<>(items);
        int workers = Math.min(PLAYNITE_ARTWORK_PREFETCH_WORKERS, snapshot.size());
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

    private void prefetchPlayniteArtwork(GatewayConnection connection,
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
            if (backdropSpec.available()
                    && !backdropSpec.cacheIdentity().equals(spec.cacheIdentity())) {
                ArtworkResult backdrop = cachedPlayniteArtwork(host.uuid, item, backdropSpec);
                if (backdrop == null || !backdropSpec.kind.equals(backdrop.kind)) {
                    try {
                        backdrop = fetchPlayniteArtwork(
                                connection, host.uuid, item, backdropSpec);
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
            }

            PlayniteArtworkSpec curtainSpec =
                    PlayniteArtworkSpec.forLoadingCurtain(item.game);
            if (curtainSpec.available()
                    && !curtainSpec.cacheIdentity().equals(spec.cacheIdentity())
                    && !curtainSpec.cacheIdentity().equals(backdropSpec.cacheIdentity())) {
                ArtworkResult curtain = cachedPlayniteArtwork(host.uuid, item, curtainSpec);
                if (curtain == null || !curtainSpec.kind.equals(curtain.kind)) {
                    try {
                        fetchPlayniteArtwork(connection, host.uuid, item, curtainSpec);
                    } catch (IOException primaryFailure) {
                        if (curtain == null) throw primaryFailure;
                    }
                }
            }
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
        HostProfileKey key = selectedProfileKey(hostUuid);
        File primary = playniteArtworkCache.get(key, item.stableId(),
                spec.kind, spec.version);
        if (primary != null && primary.isFile() && primary.length() > 0) {
            return new ArtworkResult(primary, spec.kind);
        }
        if (!spec.hasFallback()) return null;
        File fallback = playniteArtworkCache.get(key, item.stableId(),
                spec.fallbackKind, spec.fallbackVersion);
        return fallback != null && fallback.isFile() && fallback.length() > 0
                ? new ArtworkResult(fallback, spec.fallbackKind) : null;
    }

    private String cachedLoadingArtworkPath(String hostUuid, String gameId) {
        return cachedLoadingArtworkPath(hostUuid, selectedProfileId(hostUuid), gameId);
    }

    private String cachedLoadingArtworkPath(String hostUuid, String profileId,
                                            String gameId) {
        if (hostUuid == null || gameId == null || gameId.isEmpty()) return null;
        HostProfileKey key = new HostProfileKey(hostUuid, profileId);
        for (PlayniteDashboardItem item : allPlayniteItems) {
            if (!gameId.equalsIgnoreCase(item.stableId())) continue;
            PlayniteArtworkSpec spec = PlayniteArtworkSpec.forLoadingCurtain(item.game);
            File primary = playniteArtworkCache.get(key, item.stableId(),
                    spec.kind, spec.version);
            if (usablePanoramicLoadingArtwork(primary)) {
                return primary.getAbsolutePath();
            }
            if (spec.hasFallback()) {
                File fallback = playniteArtworkCache.get(key, item.stableId(),
                        spec.fallbackKind, spec.fallbackVersion);
                if (usablePanoramicLoadingArtwork(fallback)) {
                    return fallback.getAbsolutePath();
                }
            }
            return null;
        }
        return null;
    }

    private boolean usablePanoramicLoadingArtwork(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return bounds.outWidth > bounds.outHeight
                && LoadingArtworkPolicy.canUseAsSplash(bounds.outWidth, bounds.outHeight);
    }

    private ArtworkResult fetchPlayniteArtwork(GatewayConnection connection,
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
        return selectedProfileId(selectedHostUuid) + ":" + item.stableId() + ":"
                + playniteCardArtworkSpec(
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
        GatewayConnection connection =
                hostGatewayStore.loadForHost(host.uuid, address);
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
        Object decodeLock = playniteBitmapDecodeLocks.computeIfAbsent(key,
                ignored -> new Object());
        try {
            synchronized (decodeLock) {
                cached = playniteBitmapCache.get(key);
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
        } finally {
            playniteBitmapDecodeLocks.remove(key, decodeLock);
        }
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
        int targetWidth = poster.getWidth() > 0 ? poster.getWidth() : dp(92);
        int targetHeight = poster.getHeight() > 0 ? poster.getHeight() : dp(123);
        boolean modestTileCrop = CONSOLE_UI_V2
                && LoadingArtworkPolicy.canFillWithModestCrop(
                bitmap.getWidth(), bitmap.getHeight(), targetWidth, targetHeight);
        boolean lowResolution = bitmap.getWidth() < targetWidth
                || bitmap.getHeight() < targetHeight;
        poster.setScaleType(lowResolution ? ImageView.ScaleType.CENTER_INSIDE
                : modestTileCrop || landscape && !CONSOLE_UI_V2
                ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        BitmapDrawable drawable = filteredBitmapDrawable(bitmap);
        ImageView backdrop = playnitePosterBackdrop(poster);
        if (backdrop != null) {
            if (lowResolution || modestTileCrop || landscape && !CONSOLE_UI_V2) {
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
        SessionSnapshot session = resolveSessionSnapshot(host);
        if (session.state == SessionSnapshot.State.TERMINATING) {
            showSidePanelBusy(getString(R.string.console_apps_eyebrow), item.game.name,
                    getString(R.string.console_status_closing_stream));
            return;
        }
        List<View> actions = new ArrayList<>();
        GameOperationsController.Presentation operation =
                gameOperationsController.presentation(host.uuid, item.game);
        boolean installing = operation.installActive;
        boolean exactActiveGame = isFreshExactRunningManagedGame(host, item);

        if (item.game.installed) {
            TextView launch = panelAction(getString(R.string.console_play));
            launch.setEnabled(item.game.canLaunch && !operation.launchBlocked);
            launch.setAlpha(!item.game.canLaunch || operation.launchBlocked ? .42f : 1f);
            launch.setOnClickListener(view -> {
                hideSidePanel();
                activatePlayniteItem(host.uuid, item);
            });
            actions.add(launch);
            if (item.game.canUninstall) {
                TextView uninstall = panelAction(getString(R.string.playnite_uninstall));
                uninstall.setTextColor(0xFFFF9B92);
                uninstall.setOnClickListener(view -> confirmPlayniteUninstall(host, item));
                actions.add(uninstall);
            }
            if (exactActiveGame) {
                TextView endGame = panelAction(getString(R.string.overlay_menu_end_game));
                endGame.setTextColor(0xFFFFB08A);
                endGame.setOnClickListener(view -> confirmEndPlayniteGame(host, item));
                actions.add(endGame);
            }
            if (session.state == SessionSnapshot.State.ACTIVE
                    && SessionSnapshot.normalize(item.stableId()).equals(
                    session.playniteGameId)) {
                TextView terminate = panelAction(
                        getString(R.string.overlay_menu_quit_session));
                terminate.setTextColor(0xFFFF9B92);
                terminate.setOnClickListener(view ->
                        confirmTerminateSession(host, item.stableId()));
                actions.add(terminate);
            }
        } else if (installing) {
            if (operation.attentionRequired) {
                boolean localOnly = "secure_desktop".equals(operation.attentionReason);
                TextView finish = panelAction(getString(localOnly
                        ? R.string.playnite_install_confirm_on_pc
                        : R.string.playnite_install_continue));
                finish.setOnClickListener(view -> {
                    hideSidePanel();
                    continuePlayniteInstallation(host, item);
                });
                actions.add(finish);
            } else {
                TextView status = panelAction(getString(R.string.playnite_installing));
                status.setEnabled(false);
                status.setAlpha(.52f);
                actions.add(status);
            }
        } else if (item.game.canInstall) {
            TextView install = panelAction(getString(R.string.playnite_install_action_short));
            install.setOnClickListener(view -> {
                hideSidePanel();
                startPlayniteInstallation(host, item);
            });
            actions.add(install);
        }

        TextView streamSettings = panelAction(getString(R.string.playnite_stream_settings));
        streamSettings.setOnClickListener(view -> {
            hideSidePanel();
            Intent intent = new Intent(this, AppStreamSettings.class);
            intent.putExtra(AppStreamSettings.EXTRA_APP_KEY,
                    playniteStreamSettingsKey(host.uuid, item.stableId()));
            intent.putExtra(AppStreamSettings.EXTRA_APP_NAME, item.game.name);
            intent.putExtra(AppStreamSettings.EXTRA_INHERIT_APP_SETTINGS, true);
            startActivity(intent);
        });
        actions.add(streamSettings);

        TextView autopilot = panelAction(getString(R.string.console_streaming_autopilot_game));
        boolean autopilotAvailable = ConsoleActionCatalog.isOnline(host)
                && ConsoleActionCatalog.isPaired(host);
        autopilot.setEnabled(autopilotAvailable);
        autopilot.setAlpha(autopilotAvailable ? 1f : .42f);
        autopilot.setOnClickListener(view -> {
            NvApp target = PlayniteTargetResolver.findById(
                    currentSunshineApps, item.sunshineAppId);
            requestStreamingAutopilot(host,
                    playniteStreamSettingsKey(host.uuid, item.stableId()),
                    item.sunshineAppId == null ? null
                            : host.uuid + ":" + item.sunshineAppId,
                    item.game.name, PlayIntent.providerGame(host.uuid,
                            selectedProfileId(host.uuid),
                            item.sunshineAppId == null ? 0 : item.sunshineAppId,
                            item.game.name, target != null && target.isHdrSupported(),
                            item.game, playniteStreamSettingsKey(
                                    host.uuid, item.stableId())));
        });
        actions.add(autopilot);

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
        if (operation.attentionRequired) {
            String prompt = !operation.attentionWindowTitle.isEmpty()
                    ? operation.attentionWindowTitle : operation.attentionLauncher;
            panelDetails = getString("secure_desktop".equals(
                            operation.attentionReason)
                            ? R.string.playnite_install_attention_details_pc
                            : R.string.playnite_install_attention_details,
                    prompt.isEmpty() ? item.game.name : prompt);
        }
        showSidePanel(getString(R.string.playnite_game_options), item.game.name,
                panelDetails,
                actions.toArray(new View[0]));
    }

    private void confirmEndPlayniteGame(ComputerDetails host,
                                        PlayniteDashboardItem item) {
        HostProfileKey confirmedKey = selectedProfileKey(host.uuid);
        if (!isFreshExactRunningManagedGame(host, item)) return;
        HostGatewayClient.RunningGame confirmedGame = freshRunningGame(host, item.stableId());
        RunningGameObservation confirmedObservation = runningGameObservations.get(
                confirmedKey.cacheKey());
        TextView cancel = panelAction(getString(R.string.console_cancel));
        TextView endGame = panelAction(getString(R.string.overlay_menu_end_game_confirm));
        endGame.setTextColor(0xFFFF9B92);
        cancel.setOnClickListener(view -> handlePanelBack());
        endGame.setOnClickListener(view -> {
            hideSidePanel();
            requestEndPlayniteGame(host, item, confirmedGame, confirmedObservation,
                    confirmedKey);
        });
        showSidePanel(getString(R.string.playnite_game_options), item.game.name,
                getString(R.string.playnite_end_game_confirmation), cancel, endGame);
    }

    private void requestEndPlayniteGame(ComputerDetails host,
                                        PlayniteDashboardItem item,
                                        HostGatewayClient.RunningGame confirmedGame,
                                        RunningGameObservation confirmedObservation,
                                        HostProfileKey confirmedKey) {
        if (host == null || confirmedKey == null
                || !confirmedKey.equals(selectedProfileKey(host.uuid))
                || !host.uuid.equalsIgnoreCase(selectedHostUuid)) {
            ConsoleUiFeedback.makeText(this, R.string.overlay_menu_end_game_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        String stateKey = confirmedKey.cacheKey();
        String expectedGameId = SessionSnapshot.normalize(item.stableId());
        HostGatewayClient.RunningGame latestGame = freshRunningGame(host, expectedGameId);
        boolean verified = confirmedGame != null;
        if (expectedGameId.isEmpty() || !isFreshExactRunningManagedGame(host, item)
                || (verified && (latestGame == null
                || !confirmedGame.processToken.equals(latestGame.processToken)))) {
            ConsoleUiFeedback.makeText(this, R.string.overlay_menu_end_game_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        RunningGameObservation latestObservation = runningGameObservations.get(stateKey);
        // Never downgrade an inventory confirmation to the older current-only operation.
        if (!verified && latestObservation != null && latestObservation.inventory != null) return;
        int expectedAppId = host.runningGameId;
        long expectedResolvedAt = activePlayniteGameResolvedAt.getOrDefault(stateKey, 0L);
        executor.execute(() -> {
            boolean success = false;
            boolean stoppedCurrent = false;
            try {
                if (!confirmedKey.equals(selectedProfileKey(host.uuid))) {
                    throw new IOException("game_stop_profile_changed");
                }
                if (verified) {
                    GatewayConnection connection = hostGatewayStore.loadForHost(host.uuid,
                            host.activeAddress == null ? null : host.activeAddress.address,
                            confirmedKey.profileId);
                    if (confirmedObservation == null
                            || !sameGatewayProfile(confirmedObservation.connection, connection)) {
                        throw new IOException("game_stop_profile_changed");
                    }
                    HostGatewayClient.PlayniteCurrentGame fresh =
                            hostGatewayClient.getPlayniteCurrentGame(connection);
                    HostGatewayClient.RunningGame exact = fresh.runningGames == null ? null
                            : fresh.runningGames.find(confirmedGame.gameId);
                    if (exact == null || !confirmedGame.processToken.equals(exact.processToken)) {
                        throw new IOException("game_stop_process_changed");
                    }
                    GatewayConnection beforeStop = hostGatewayStore.loadForHost(host.uuid,
                            host.activeAddress == null ? null : host.activeAddress.address,
                            confirmedKey.profileId);
                    if (!confirmedKey.equals(selectedProfileKey(host.uuid))
                            || !host.uuid.equalsIgnoreCase(selectedHostUuid)
                            || !sameGatewayProfile(connection, beforeStop)) {
                        throw new IOException("game_stop_host_changed");
                    }
                    stoppedCurrent = hostGatewayClient.stopGame(connection,
                            exact.gameId, exact.processToken);
                } else {
                    if (!confirmedKey.equals(selectedProfileKey(host.uuid))) {
                        throw new IOException("game_stop_profile_changed");
                    }
                    stopActiveProviderGame(host, item.stableId(), true, confirmedKey.profileId);
                    stoppedCurrent = true;
                }
                success = true;
            } catch (IOException | RuntimeException ignored) { }
            boolean stopped = success;
            boolean stoppedSessionGame = stoppedCurrent;
            mainHandler.post(() -> {
                if (!confirmedKey.equals(selectedProfileKey(host.uuid))
                        || !host.uuid.equalsIgnoreCase(selectedHostUuid)) return;
                RunningGameObservation local = runningGameObservations.get(stateKey);
                GatewayConnection currentConnection = hostGatewayStore.loadForHost(host.uuid,
                        host.activeAddress == null ? null : host.activeAddress.address,
                        confirmedKey.profileId);
                HostGatewayClient.RunningGame localGame = local == null || local.inventory == null
                        ? null : local.inventory.find(expectedGameId);
                boolean sameStopScope = !verified || (confirmedObservation != null
                        && sameGatewayProfile(confirmedObservation.connection, currentConnection)
                        && local != null
                        && sameGatewayProfile(confirmedObservation.connection, local.connection)
                        && localGame != null
                        && confirmedGame.processToken.equals(localGame.processToken));
                if (stopped && sameStopScope) {
                    if (stoppedSessionGame) markExactPlayniteGameIdle(host.uuid, expectedAppId,
                            expectedGameId, expectedResolvedAt);
                    // Preserve other verified games and their original freshness deadline.
                    RunningGameObservation observation = runningGameObservations.get(stateKey);
                    if (observation != null && confirmedGame != null) {
                        runningGameObservations.put(stateKey, observation.without(confirmedGame));
                    }
                    invalidateActivePlayniteGameRequest(host.uuid);
                    renderPlayniteLibrary(currentHost(host.uuid), currentSunshineApps);
                    if (stoppedSessionGame) refreshSessionState(host.uuid);
                }
                if (sameStopScope) {
                    resolveActivePlayniteGame(host, host.runningGameId != 0, ignored -> { });
                }
                ConsoleUiFeedback.makeText(this, stopped
                                ? R.string.playnite_end_game_success
                                : R.string.overlay_menu_end_game_failed,
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void restoreCachedPlaynitePoster(ComputerDetails host,
                                             PlayniteDashboardItem item,
                                             ImageView poster) {
        if (host == null || poster == null) return;
        PlayniteArtworkSpec spec = playniteCardArtworkSpec(item.game);
        ArtworkResult cached = cachedPlayniteArtwork(host.uuid, item, spec);
        if (cached == null) return;
        String bitmapKey = playniteBitmapCacheKey(cached.file);
        Bitmap bitmap = playniteBitmapCache.get(bitmapKey);
        if (bitmap != null) {
            applyPlayniteBitmap(poster, playniteArtworkTag(item), cached.kind, bitmap);
        } else if (suppressInitialCarouselMotion
                && initialCarouselArtworkLoads.add(bitmapKey)) {
            int generation = initialCarouselArtworkGeneration;
            String expectedTag = playniteArtworkTag(item);
            executor.execute(() -> {
                Bitmap decoded = cachePlayniteBitmap(cached.file);
                mainHandler.post(() -> {
                    if (generation != initialCarouselArtworkGeneration
                            || !initialCarouselArtworkLoads.remove(bitmapKey)) return;
                    applyPlayniteBitmap(poster, expectedTag, cached.kind, decoded);
                    if (appRow != null) appRow.postInvalidateOnAnimation();
                });
            });
        }
    }

    private void resetInitialCarouselArtworkWarmup() {
        initialCarouselArtworkGeneration++;
        initialCarouselArtworkLoads.clear();
    }

    private void markExactPlayniteGameIdle(String hostId, int expectedAppId,
                                           String expectedGameId, long expectedResolvedAt) {
        ComputerDetails current = currentHost(hostId);
        String stateKey = profileStateKey(hostId);
        if (current == null || current.runningGameId != expectedAppId
                || !shouldApplyExactStopResult(true, expectedAppId, expectedGameId,
                expectedResolvedAt,
                activePlayniteGameAppIds.getOrDefault(stateKey, Integer.MIN_VALUE),
                activePlayniteGameStates.get(stateKey), activePlayniteGameIds.get(stateKey),
                activePlayniteGameResolvedAt.getOrDefault(stateKey, 0L))) return;
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        boolean retainedScope = (retained.state
                == RetainedStreamSessionCoordinator.State.HOME_LIVE
                || retained.state == RetainedStreamSessionCoordinator.State.PARKED_LIVE)
                && retained.hostId.equalsIgnoreCase(hostId)
                && retained.profileId.equals(selectedProfileId(hostId))
                && retained.appId == expectedAppId;
        if (retainedScope && !RetainedStreamSessionCoordinator.updateGameIfMatches(
                retained.streamSessionId, hostId, expectedAppId, expectedGameId, "")) return;
        activePlayniteGameIds.remove(stateKey);
        activePlayniteGameStates.put(stateKey, "idle");
        activePlayniteGameAppIds.put(stateKey, expectedAppId);
        activePlayniteGameResolvedAt.put(stateKey, SystemClock.uptimeMillis());
        invalidateActivePlayniteGameRequest(hostId);
        if (hostId.equalsIgnoreCase(selectedHostUuid)) {
            renderPlayniteLibrary(current, currentSunshineApps);
        }
    }

    static boolean shouldApplyExactStopResult(
            boolean success, int expectedAppId, String expectedGameId,
            long expectedResolvedAt, int cachedAppId, String cachedState,
            String cachedGameId, long cachedResolvedAt) {
        return success && expectedAppId == cachedAppId
                && expectedResolvedAt == cachedResolvedAt
                && "running".equalsIgnoreCase(cachedState)
                && SessionSnapshot.normalize(expectedGameId).equals(
                SessionSnapshot.normalize(cachedGameId));
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
        GameOperationsController.Presentation operation =
                gameOperationsController.presentation(hostUuid, item.game);
        if (operation.attentionRequired) {
            continuePlayniteInstallation(host, item);
            return;
        }
        if (operation.launchBlocked) {
            ConsoleUiFeedback.makeText(this, getString(R.string.playnite_install_in_progress,
                    item.game.name), Toast.LENGTH_SHORT).show();
            return;
        }
        List<NvApp> apps = currentSunshineApps;
        if (!item.game.installed) {
            if (!item.game.canInstall) {
                ConsoleUiFeedback.makeText(this, R.string.playnite_launch_unavailable,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            startPlayniteInstallation(host, item);
            return;
        }
        if (!item.game.canLaunch) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_launch_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (isVibepolloEnsureInFlight(hostUuid, item)
                && item.mappingState != PlayniteDashboardItem.MappingState.MAPPED) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_creating_vibepollo_app,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.MAPPED) {
            NvApp target = PlayniteTargetResolver.launchTarget(item, apps,
                    ConsoleActionCatalog.isOnline(host));
            if (target != null) {
                playPlayniteGame(host, target, item.game);
                return;
            }
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.FALLBACK_PLAYNITE) {
            NvApp fallback = PlayniteTargetResolver.launchTarget(item, apps,
                    ConsoleActionCatalog.isOnline(host));
            if (fallback != null) {
                playPlayniteGame(host, fallback, item.game);
                return;
            }
        }
        if (item.mappingState == PlayniteDashboardItem.MappingState.MISSING) {
            sessionOrchestrator.play(PlayIntent.providerGame(
                    host.uuid, selectedProfileId(host.uuid), 0,
                    item.game.name, false, item.game,
                    playniteStreamSettingsKey(host.uuid, item.game.playniteGameId)));
            return;
        }
        showUncertainSessionMessage();
    }

    private void startPlayniteInstallation(ComputerDetails host,
                                           PlayniteDashboardItem item) {
        if (host == null || item == null || !item.game.canInstall) return;
        if (hasPlayniteOperationAwaitingConfirmation()) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_operation_waiting,
                    Toast.LENGTH_LONG).show();
            return;
        }
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        GatewayConnection connection =
                hostGatewayStore.loadForHost(host.uuid, address);
        if (connection == null) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_gateway_not_configured,
                    Toast.LENGTH_LONG).show();
            return;
        }
        preferences.edit().putLong(playniteCarouselInstallActivityKey(
                        host.uuid, item.game.playniteGameId), System.currentTimeMillis())
                .remove(playniteInstallNotificationKey(
                host.uuid, item.game.playniteGameId))
                .putString(playniteInstallPendingKey(host.uuid,
                        item.game.playniteGameId), item.game.name).apply();
        gameOperationsController.requestInstall(host.uuid, item.game, connection, success -> {
            if (success) {
                    if (!active || !host.uuid.equals(selectedHostUuid)) return;
                    requestPlayniteRefresh(currentHost(host.uuid), false);
            } else {
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
            }
        });
        renderPlayniteLibrary(host, currentSunshineApps);
        ConsoleUiFeedback.makeText(this, getString(R.string.playnite_install_starting,
                item.game.name), Toast.LENGTH_LONG).show();
    }

    private void confirmPlayniteUninstall(ComputerDetails host,
                                          PlayniteDashboardItem item) {
        TextView cancel = panelAction(getString(R.string.console_cancel));
        TextView uninstall = panelAction(getString(R.string.playnite_uninstall));
        uninstall.setTextColor(0xFFFF8A80);
        cancel.setOnClickListener(view -> handlePanelBack());
        uninstall.setOnClickListener(view -> {
            hideSidePanel();
            uninstallPlayniteGame(host, item);
        });
        showSidePanel(getString(R.string.playnite_game_options), item.game.name,
                getString(R.string.playnite_uninstall_confirmation, item.game.name),
                cancel, uninstall);
    }

    private void uninstallPlayniteGame(ComputerDetails host,
                                       PlayniteDashboardItem item) {
        if (item == null || !item.game.canUninstall) return;
        if (hasPlayniteOperationAwaitingConfirmation()) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_operation_waiting,
                    Toast.LENGTH_LONG).show();
            return;
        }
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        GatewayConnection connection =
                hostGatewayStore.loadForHost(host.uuid, address);
        if (connection == null) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_gateway_not_configured,
                    Toast.LENGTH_LONG).show();
            return;
        }
        gameOperationsController.requestUninstall(host.uuid, item.game, connection, success -> {
            if (success) {
                requestPlayniteRefresh(currentHost(host.uuid), false);
            } else {
                renderPlayniteLibrary(currentHost(host.uuid), currentSunshineApps);
                ConsoleUiFeedback.makeText(this,
                        getString(R.string.playnite_uninstall_failed, item.game.name),
                        Toast.LENGTH_LONG).show();
            }
        });
        renderPlayniteLibrary(currentHost(host.uuid), currentSunshineApps);
        ConsoleUiFeedback.makeText(this, getString(R.string.playnite_uninstall_starting,
                item.game.name), Toast.LENGTH_LONG).show();
    }

    private void continuePlayniteInstallation(ComputerDetails host,
                                              PlayniteDashboardItem item) {
        if (host == null || item == null) return;
        String address = host.activeAddress != null ? host.activeAddress.address : null;
        GatewayConnection connection =
                hostGatewayStore.loadForHost(host.uuid, address);
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
        GameOperationsController.Presentation operation =
                gameOperationsController.presentation(host.uuid, item.game);
        if (gameOperationsController.continuation(operation)
                == GameOperationsController.Continuation.OPEN_INSTALLATION_DESKTOP) {
            ConsoleUiFeedback.makeText(this, R.string.playnite_install_confirm_on_pc_details,
                    Toast.LENGTH_LONG).show();
            openInstallationDesktop(host, item, streamTarget);
            return;
        }
        gameOperationsController.requestInstallationFocus(
                connection, item.game.playniteGameId, success -> {
            if (success) {
                    ConsoleUiFeedback.makeText(this, getString(
                            R.string.playnite_install_opening_confirmation,
                            item.game.name), Toast.LENGTH_LONG).show();
                    beginInstallationSupportStream(
                            currentHost(host.uuid), streamTarget, item.game.playniteGameId);
            } else {
                    ConsoleUiFeedback.makeText(this,
                            R.string.playnite_install_window_unavailable,
                            Toast.LENGTH_LONG).show();
                    openInstallationDesktop(host, item, streamTarget);
            }
        });
    }

    private void openInstallationDesktop(ComputerDetails host,
                                         PlayniteDashboardItem item,
                                         NvApp streamTarget) {
        beginInstallationSupportStream(
                currentHost(host.uuid), streamTarget, item.game.playniteGameId);
    }

    private void launchDesktopSession(ComputerDetails host) {
        if (host == null) return;
        String hostUuid = host.uuid;
        List<NvApp> selectedApps = hostUuid.equalsIgnoreCase(selectedHostUuid)
                ? currentSunshineApps : Collections.emptyList();
        executor.execute(() -> {
            List<NvApp> apps = selectedApps.isEmpty() ? loadApps(host, true) : selectedApps;
            NvApp desktop = PlayniteTargetResolver.findPlayableExactName(apps, "Desktop");
            mainHandler.post(() -> {
                ComputerDetails current = currentHost(hostUuid);
                if (current == null || desktop == null
                        || !ConsoleActionCatalog.isPaired(current)) {
                    ConsoleUiFeedback.makeText(this,
                            R.string.console_launch_desktop_unavailable,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                playSunshineApp(current, desktop, "");
            });
        });
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
            if (ConsoleActionCatalog.isPaired(host)) playSunshineApp(host, app, "");
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
        Object pending = title.getTag(R.id.carousel_marquee_start);
        if (pending instanceof Runnable) title.removeCallbacks((Runnable) pending);
        title.setSingleLine(true);
        title.setHorizontallyScrolling(false);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMarqueeRepeatLimit(0);
        title.setSelected(false);
        title.setTag(R.id.carousel_marquee_start, null);
        if (!focused) return;
        Runnable start = () -> {
            title.setTag(R.id.carousel_marquee_start, null);
            if (!title.isAttachedToWindow() || !cardParentHasFocus(title)) return;
            title.setHorizontallyScrolling(true);
            title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            title.setMarqueeRepeatLimit(1);
            title.setSelected(true);
        };
        title.setTag(R.id.carousel_marquee_start, start);
        title.postDelayed(start, reducedMotion ? 0L : CAROUSEL_MARQUEE_SETTLE_MS);
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

    private void playSunshineApp(ComputerDetails host, NvApp app, String quickLaunchId) {
        if (host == null || app == null) return;
        sessionOrchestrator.play(PlayIntent.sunshineApp(host.uuid,
                selectedProfileId(host.uuid), app.getAppId(),
                app.getAppName(), app.isHdrSupported(), quickLaunchId,
                uniquePlayniteGameIdForRunningApp(host, app)));
    }

    private void playPlayniteGame(ComputerDetails host, NvApp app,
                                  PlayniteLibraryGame game) {
        if (host == null || app == null || game == null) return;
        sessionOrchestrator.play(PlayIntent.providerGame(host.uuid,
                selectedProfileId(host.uuid), app.getAppId(),
                game.name, app.isHdrSupported(), game,
                playniteStreamSettingsKey(host.uuid, game.playniteGameId)));
    }

    private PlayIntent providerGameIntent(String hostUuid, String profileId,
                                          int appId, String appName,
                                          boolean hdrSupported, String gameId,
                                          String loadingArtworkGameId,
                                          String streamSettingsKey) {
        PlayniteLibraryGame game = providerGameMetadata(hostUuid, profileId, gameId);
        if (game == null) {
            return PlayIntent.playniteGame(hostUuid, profileId,
                    appId, appName, hdrSupported,
                    gameId, loadingArtworkGameId, streamSettingsKey);
        }
        return PlayIntent.playniteGame(hostUuid, profileId,
                appId, appName, hdrSupported,
                game.playniteGameId, loadingArtworkGameId, streamSettingsKey,
                game.requiresConnector, game.usesNeutralStream(), game.startBeforeStream);
    }

    private PlayniteLibraryGame providerGameMetadata(String hostUuid, String profileId,
                                                      String gameId) {
        HostProfileKey key = new HostProfileKey(hostUuid, profileId);
        if (normalizeId(hostUuid).equalsIgnoreCase(normalizeId(currentPlayniteHostUuid))
                && key.equals(selectedProfileKey(hostUuid))) {
            PlayniteLibraryGame current = findProviderGame(currentPlayniteGames, gameId);
            if (current != null) return current;
        }
        PlayniteLibraryCache.Entry cached = playniteLibraryRepository == null
                ? null : playniteLibraryRepository.cached(key);
        return cached == null ? null : findProviderGame(cached.games, gameId);
    }

    static PlayniteLibraryGame findProviderGame(List<PlayniteLibraryGame> games,
                                                 String gameId) {
        String expected = normalizeId(gameId);
        if (games == null || expected.isEmpty()) return null;
        for (PlayniteLibraryGame game : games) {
            if (game != null && game.playniteGameId.equalsIgnoreCase(expected)) return game;
        }
        return null;
    }

    private static String playniteStreamSettingsKey(String hostUuid, String gameId) {
        return "playnite:" + normalizeId(hostUuid) + ":" + normalizeId(gameId);
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
        if (resolveSessionSnapshot(host).state == SessionSnapshot.State.TERMINATING) {
            showSidePanelBusy(getString(R.string.console_apps_eyebrow), app.getAppName(),
                    getString(R.string.console_status_closing_stream));
            return;
        }
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
                false, () -> { hideSidePanel(); playSunshineApp(host, app, ""); });
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.RESUME,
                getString(R.string.applist_menu_resume), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> { hideSidePanel(); playSunshineApp(host, app, ""); });
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.QUIT_AND_LAUNCH,
                getString(R.string.applist_menu_quit_and_start), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                false, () -> { hideSidePanel(); playSunshineApp(host, app, ""); });
        addAppAction(resolved, ConsoleActionCatalog.AppCapability.QUIT,
                getString(R.string.applist_menu_quit), online, paired, thisAppRunning,
                anotherAppRunning, quickLaunch, isHidden, hasArtwork, shortcutsSupported,
                true, () -> confirmQuitAction(() -> {
                    hideSidePanel();
                    requestTerminateSession(host);
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
        if (online && paired) {
            resolved.add(ConsoleAction.enabled("app.streaming_autopilot",
                    getString(R.string.console_streaming_autopilot_game), 0,
                    ConsoleAction.Context.APPLICATION, false,
                    () -> requestStreamingAutopilot(host,
                            host.uuid + ":" + app.getAppId(), null, app.getAppName(),
                            PlayIntent.sunshineApp(host.uuid,
                                    selectedProfileId(host.uuid), app.getAppId(),
                                    app.getAppName(), app.isHdrSupported(), ""))));
        }
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
                reducedMotion || suppressInitialCarouselMotion
                        ? 0L : ARTWORK_FOCUS_SETTLE_MS);
    }

    private void showArtworkSettled(File file, Drawable preview, String artworkKey) {
        int token = artworkGeneration.incrementAndGet();
        boolean immediate = reducedMotion || suppressInitialCarouselMotion;
        loadingArtworkKey = artworkKey;
        if (preview != null && !CONSOLE_UI_V2) {
            Drawable next = cloneDrawable(preview);
            Drawable current = artworkHero.getDrawable();
            artworkHero.animate().cancel();
            if (current != null && !immediate) {
                TransitionDrawable transition = new TransitionDrawable(
                        new Drawable[]{current, next});
                transition.setCrossFadeEnabled(true);
                artworkHero.setImageDrawable(transition);
                transition.startTransition(180);
            } else {
                artworkHero.setImageDrawable(next);
            }
            artworkHero.animate().alpha(.72f)
                    .setDuration(immediate ? 0 : 180).start();
            artworkScrim.animate().cancel();
            artworkScrim.animate().alpha(1f).setDuration(immediate ? 0 : 180).start();
        }
        executor.execute(() -> {
            if (token != artworkGeneration.get()) return;
            String bitmapKey = "backdrop:" + artworkKey;
            Bitmap bitmap = playniteBitmapCache.get(bitmapKey);
            if (bitmap == null) {
                bitmap = decodeArtwork(file, CONSOLE_UI_V2 ? 1920 : 1200);
                if (bitmap != null) playniteBitmapCache.put(bitmapKey, bitmap);
            }
            if (token != artworkGeneration.get()) {
                return;
            }
            Bitmap readyBitmap = bitmap;
            int accent = sampleAccent(readyBitmap);
            mainHandler.post(() -> {
                if (token != artworkGeneration.get() || readyBitmap == null) {
                    if (artworkKey.equals(loadingArtworkKey)) loadingArtworkKey = null;
                    return;
                }
                loadingArtworkKey = null;
                displayedArtworkKey = artworkKey;
                glassAccent = accent;
                updateArtworkReadability(readyBitmap);
                ImageView outgoingBackdrop = artworkBackdrop;
                ImageView incomingBackdrop = artworkBackdropNext;
                outgoingBackdrop.animate().cancel();
                incomingBackdrop.animate().cancel();
                incomingBackdrop.setImageBitmap(readyBitmap);
                incomingBackdrop.setAlpha(immediate
                        ? (CONSOLE_UI_V2 ? .72f : .16f) : 0f);
                if (!CONSOLE_UI_V2) {
                    // The preview and final hero have identical geometry. Replacing the
                    // preview avoids a soft double-image while retaining the tile-to-tile crossfade.
                    artworkHero.setImageBitmap(readyBitmap);
                    artworkHero.animate().alpha(.72f)
                            .setDuration(immediate ? 0 : 220).start();
                }
                float targetAlpha = CONSOLE_UI_V2 ? .72f : .16f;
                if (immediate) {
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
                artworkScrim.animate().alpha(1f).setDuration(immediate ? 0 : 220).start();
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
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = LoadingArtworkPolicy.sampleSize(
                bounds.outWidth, bounds.outHeight, maxDimension);
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

    private HostLaunchPreflight createHostLaunchPreflight() {
        HostLaunchPreflight.Network network = (hostId, action, cancelled) -> {
            if (action == HostLaunchPreflight.Action.SWITCH_RETAINED) {
                RetainedStreamSessionCoordinator.Snapshot retained =
                        RetainedStreamSessionCoordinator.snapshot();
                return (retained.state == RetainedStreamSessionCoordinator.State.HOME_LIVE
                        || retained.state == RetainedStreamSessionCoordinator.State.PREPARING)
                        && retained.hostId.equalsIgnoreCase(hostId)
                        && RetainedStreamSessionCoordinator.canSwitchGame(retained);
            }
            ComputerDetails target = currentHost(hostId);
            if (target == null) return false;
            if (action == HostLaunchPreflight.Action.WARM_UP) {
                return HostReadiness.awaitAfterWakeDecision(
                        () -> managerBinder == null ? null : managerBinder.getComputer(hostId),
                        target, false, cancelled, ignored -> { }, "", "") != null;
            }
            return HostReadiness.await(
                    () -> managerBinder == null ? null : managerBinder.getComputer(hostId),
                    target, cancelled, ignored -> { }, "", "") != null;
        };
        HostLaunchPreflight.Gateway gateway = new HostLaunchPreflight.Gateway() {
            @Override public HostLaunchPreflight.Profile profile(String hostId, String profileId)
                    throws IOException {
                GatewayConnection connection = connection(hostId, profileId);
                if (connection == null) return null;
                HostGatewayClient.IntegrationProfiles profiles = hostGatewayClient
                        .getIntegrationProfiles(connection);
                hostGatewayStore.saveProfiles(hostId, profiles);
                HostGatewayClient.IntegrationProfile profile = profiles.find(profileId);
                if (profile == null) {
                    return new HostLaunchPreflight.Profile(false, false,
                            false, false);
                }
                return new HostLaunchPreflight.Profile(profile.name, profile.useProfile,
                        profile.playniteBridgeOnline,
                        profile.playniteConnectorConnected,
                        profile.vibepolloBridgeOnline);
            }

            @Override public HostLaunchPreflight.Session ensureSession(
                    String hostId, String profileId, String requestId) throws IOException {
                GatewayConnection connection = connection(hostId, profileId);
                if (connection == null) throw new IOException("Gateway unavailable");
                return session(hostGatewayClient.ensureWindowsSession(connection, requestId));
            }

            @Override public HostLaunchPreflight.Session sessionStatus(
                    String hostId, String profileId, String requestId, String attemptId)
                    throws IOException {
                GatewayConnection connection = connection(hostId, profileId);
                if (connection == null) throw new IOException("Gateway unavailable");
                return session(hostGatewayClient.getWindowsSessionStatus(
                        connection, requestId, attemptId));
            }

            @Override public void cancelSession(String hostId, String profileId,
                                                String requestId, String attemptId)
                    throws IOException {
                GatewayConnection connection = connection(hostId, profileId);
                if (connection != null) {
                    hostGatewayClient.cancelWindowsSession(
                            connection, requestId, attemptId);
                }
            }

            @Override public HostLaunchPreflight.EnsuredTarget ensureTarget(
                    String hostId, String profileId, String gameId, String name)
                    throws IOException {
                ComputerDetails host = currentHost(hostId);
                String address = host != null && host.activeAddress != null
                        ? host.activeAddress.address : null;
                GatewayConnection connection = hostGatewayStore.loadForHost(
                        hostId, address, profileId);
                if (connection == null) throw new IOException("Gateway unavailable");
                JSONObject ensured = hostGatewayClient.ensureVibepolloPlayniteApp(
                        connection, gameId, name);
                return new HostLaunchPreflight.EnsuredTarget(
                        HostGatewayClient.parseVibepolloAppId(ensured),
                        HostGatewayClient.parseVibepolloAppUuid(ensured));
            }

            private GatewayConnection connection(String hostId, String profileId) {
                ComputerDetails host = currentHost(hostId);
                String address = host != null && host.activeAddress != null
                        ? host.activeAddress.address : null;
                return hostGatewayStore.loadForHost(hostId, address, profileId);
            }

            private HostLaunchPreflight.Session session(
                    HostGatewayClient.WindowsSession value) {
                return new HostLaunchPreflight.Session(value.state, value.reason,
                        value.attemptId, value.retryAfterMs);
            }
        };
        HostLaunchPreflight.Sunshine sunshine = new HostLaunchPreflight.Sunshine() {
            @Override public NvApp verifiedRetainedTarget(HostLaunchPreflight.Request request) {
                RetainedStreamSessionCoordinator.Snapshot retained =
                        RetainedStreamSessionCoordinator.snapshot();
                if (request.action != HostLaunchPreflight.Action.SWITCH_RETAINED
                        || !request.requiresPlaynite()
                        || !retained.hostId.equalsIgnoreCase(request.hostId)
                        || !retained.profileId.equals(request.profileId)
                        || retained.appId != request.appId
                        || !RetainedStreamSessionCoordinator.canSwitchGame(retained)) return null;
                ComputerDetails host = currentHost(request.hostId);
                if (host == null || !host.uuid.equalsIgnoreCase(retained.hostId)) return null;
                NvApp target = PlayniteTargetResolver.findById(loadApps(host, true), retained.appId);
                return PlayniteTargetResolver.isNeutralStream(target)
                        && RetainedStreamSessionCoordinator.canSwitchGame(retained) ? target : null;
            }

            @Override public List<NvApp> refreshApps(String hostId) throws IOException {
                ComputerDetails host = currentHost(hostId);
                if (host == null || managerBinder == null) {
                    throw new IOException("Sunshine host unavailable");
                }
                NvHTTP connection = new NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(host), host.httpsPort,
                        managerBinder.getUniqueId(), host.serverCert,
                        PlatformBinding.getCryptoProvider(ConsoleActivity.this));
                try {
                    return connection.getAppList();
                } catch (XmlPullParserException invalidResponse) {
                    throw new IOException("Invalid Sunshine app list", invalidResponse);
                }
            }
        };
        HostLaunchPreflight.Waiter waiter = (millis, cancelled) -> {
            if (cancelled.getAsBoolean()) return false;
            try {
                Thread.sleep(millis);
                return !cancelled.getAsBoolean();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
        return new HostLaunchPreflight(network, gateway, sunshine,
                SystemClock::uptimeMillis, waiter);
    }

    private String preflightStageMessage(HostLaunchPreflight.Stage stage,
                                         PlayIntent intent) {
        switch (stage) {
            case NETWORK_READY:
                return getString(R.string.console_checking_host);
            case GATEWAY_READY:
                return getString(R.string.transition_waiting_gateway);
            case PROFILE_AUTHORIZED:
                return getString(R.string.preflight_authorizing_profile);
            case INTERACTIVE_SESSION_READY:
                return getString(R.string.preflight_starting_windows_session);
            case PROFILE_READY:
                return getString(R.string.preflight_starting_profile_services);
            case PLAYNITE_READY:
                return getString(R.string.transition_checking_game_launch_service);
            case VIBEPOLLO_READY:
                return getString(R.string.playnite_creating_vibepollo_app);
            default:
                return getString(R.string.transition_verifying_readiness);
        }
    }

    private String preflightFailureMessage(HostLaunchPreflight.Failure failure) {
        switch (failure.reason) {
            case NETWORK_UNAVAILABLE:
                return getString(R.string.console_host_timeout);
            case GATEWAY_UNAVAILABLE:
                return getString(R.string.transition_gateway_unavailable);
            case SELECTED_PROFILE_UNAVAILABLE:
                return getString(R.string.preflight_profile_unavailable);
            case REMOTE_SIGN_IN_NOT_GRANTED:
                return getString(R.string.preflight_remote_sign_in_not_granted,
                        failure.profileName);
            case MANUAL_SIGN_IN_REQUIRED:
                return getString(R.string.preflight_manual_sign_in_required,
                        failure.profileName);
            case CREDENTIAL_ACTION_REQUIRED:
                return getString(R.string.preflight_credential_action_required,
                        failure.profileName);
            case OTHER_PROFILE_ACTIVE:
                return getString(R.string.preflight_other_profile_active);
            case LOGIN_BROKER_UNAVAILABLE:
                return getString(R.string.preflight_login_broker_unavailable);
            case WINDOWS_SIGN_IN_EXPIRED:
                return getString(R.string.preflight_windows_sign_in_expired,
                        failure.profileName);
            case WINDOWS_SIGN_IN_CANCELLED:
                return getString(R.string.preflight_windows_sign_in_cancelled,
                        failure.profileName);
            case WINDOWS_SIGN_IN_TIMEOUT:
                return getString(R.string.preflight_windows_sign_in_timeout,
                        failure.profileName);
            case WINDOWS_SIGN_IN_FAILED:
                return getString(R.string.preflight_windows_sign_in_failed,
                        failure.profileName);
            case PLAYNITE_BRIDGE_OFFLINE:
                return getString(R.string.preflight_playnite_offline);
            case PLAYNITE_CONNECTOR_DISCONNECTED:
                return getString(R.string.preflight_playnite_connector_disconnected);
            case VIBEPOLLO_UNAVAILABLE:
                return getString(R.string.preflight_vibepollo_unavailable);
            case TARGET_PROPAGATION_TIMEOUT:
                return getString(R.string.preflight_target_timeout);
            default:
                return getString(R.string.playnite_launch_unavailable);
        }
    }

    private static final class WarmUpBridgeProbe {
        final boolean responded;
        final String gameId;

        WarmUpBridgeProbe(boolean responded, String gameId) {
            this.responded = responded;
            this.gameId = normalizeId(gameId);
        }
    }

    private WarmUpBridgeProbe probeWarmUpBridge(ComputerDetails host) {
        return probeWarmUpBridge(host, selectedProfileId(host == null ? null : host.uuid));
    }

    private WarmUpBridgeProbe probeWarmUpBridge(ComputerDetails host, String profileId) {
        if (host == null) return new WarmUpBridgeProbe(false, "");
        String address = host.activeAddress == null ? null : host.activeAddress.address;
        GatewayConnection gateway = hostGatewayStore.loadForHost(
                host.uuid, address, profileId);
        if (gateway == null) return new WarmUpBridgeProbe(false, "");
        try {
            HostGatewayClient.PlayniteCurrentGame current =
                    hostGatewayClient.getPlayniteCurrentGame(gateway);
            String state = normalizeId(current.state);
            String id = normalizeId(current.id);
            return new WarmUpBridgeProbe(true,
                    "running".equals(state) && HostGatewayClient.isPlayniteId(id)
                            ? id : "");
        } catch (IOException | RuntimeException unavailable) {
            return new WarmUpBridgeProbe(false, "");
        }
    }

    private ComputerDetails refreshWarmUpHost(ComputerDetails host) throws IOException {
        if (host == null || managerBinder == null) throw new IOException("Host unavailable");
        NvHTTP connection = new NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(host), host.httpsPort,
                managerBinder.getUniqueId(), host.serverCert,
                PlatformBinding.getCryptoProvider(this));
        try {
            return HostReadiness.mergeFreshDetails(
                    host, connection.getComputerDetails(true));
        } catch (XmlPullParserException invalid) {
            throw new IOException("Invalid Sunshine host response", invalid);
        }
    }

    private SessionOrchestrator createSessionOrchestrator() {
        return new SessionOrchestrator(new SessionOrchestrator.Effects() {
            @Override public boolean isAvailable() {
                return managerBinder != null;
            }

            @Override public boolean isPaired(String hostId) {
                return ConsoleActionCatalog.isPaired(currentHost(hostId));
            }

            @Override public boolean canPrepareHost(String hostId) {
                return ConsoleActionCatalog.canPrepareHost(currentHost(hostId));
            }

            @Override public SessionSnapshot resolve(String hostId) {
                return resolve(selectedProfileKey(hostId));
            }

            @Override public SessionSnapshot resolve(HostProfileKey profileKey) {
                if (profileKey == null) {
                    return new SessionSnapshot("", SessionSnapshot.State.NONE, 0, "",
                            false, false, false, false, false);
                }
                String hostId = profileKey.hostId;
                ComputerDetails host = currentHost(hostId);
                if (ConsoleActionCatalog.isOnline(host)
                        && ConsoleActionCatalog.isPaired(host)
                        && profileKey.equals(selectedProfileKey(hostId))) {
                    resolveActivePlayniteGame(host, profileKey, true, null);
                }
                return resolveSessionSnapshot(host, profileKey.profileId);
            }

            @Override public void returnToRetainedStream() {
                ConsoleActivity.this.returnToRetainedStream();
            }

            @Override public void reconnectSavedSession() {
                SessionResumeManager.PendingSession pending =
                        SessionResumeManager.pendingSession(ConsoleActivity.this);
                Intent resume = SessionResumeManager.buildResumeIntent(
                        ConsoleActivity.this, pending);
                if (resume == null) return;
                refreshSessionOnResume = true;
                startActivity(resume);
                overridePendingTransition(0, 0);
            }

            @Override public void reconnectSavedSession(PlayIntent intent) {
                if (intent == null) return;
                SessionResumeManager.PendingSession pending =
                        SessionResumeManager.pendingSession(ConsoleActivity.this);
                if (pending == null
                        || !intent.hostId.equalsIgnoreCase(pending.hostUuid)
                        || !intent.profileId.equals(pending.profileId)) return;
                Intent resume = SessionResumeManager.buildResumeIntent(
                        ConsoleActivity.this, pending);
                if (resume == null) return;
                refreshSessionOnResume = true;
                startActivity(resume);
                overridePendingTransition(0, 0);
            }
            @Override public void reject(SessionOrchestrator.Rejection reason) {
                int message;
                switch (reason) {
                    case INITIALIZING:
                        message = R.string.console_initializing;
                        break;

                    case TERMINATING:
                        message = R.string.console_terminate_session_request;
                        break;
                    default:
                        message = R.string.scut_not_paired;
                        break;
                }
                ConsoleUiFeedback.makeText(ConsoleActivity.this, message,
                        Toast.LENGTH_SHORT).show();
            }

            @Override public void confirmReplacement(Runnable accepted) {
                confirmQuitAction(accepted);
            }

            @Override public boolean canAttemptRetainedSwitch(PlayIntent intent) {
                if (intent.kind != PlayIntent.Kind.PLAYNITE_GAME) return false;
                ComputerDetails host = currentHost(intent.hostId);
                RetainedStreamSessionCoordinator.Snapshot retained =
                        RetainedStreamSessionCoordinator.snapshot();
                if (!retained.profileId.equals(intent.profileId)) return false;
                boolean preparing = retained.state
                        == RetainedStreamSessionCoordinator.State.PREPARING;
                NvApp retainedTarget = PlayniteTargetResolver.findById(
                        preparing && host != null ? loadApps(host, true)
                                : currentSunshineApps,
                        retained.appId);
                if (preparing) {
                    if (host == null || !retained.hostId.equalsIgnoreCase(host.uuid)) {
                        return false;
                    }
                    if (retained.playniteGameId.isEmpty()) {
                        return PlayniteTargetResolver.isNeutralStream(retainedTarget)
                                && RetainedStreamSessionCoordinator.canSwitchGame(
                                intent.hostId, intent.profileId, retained.appId);
                    }
                    String stateKey = intent.profileKey.cacheKey();
                    long observedAt = activePlayniteGameResolvedAt.getOrDefault(
                            stateKey, 0L);
                    boolean fresh = PlayniteIdentityResolutionPolicy.isFreshObservation(
                            retained.appId, activePlayniteGameAppIds.getOrDefault(
                                    stateKey, Integer.MIN_VALUE), observedAt,
                            SystemClock.uptimeMillis(), ACTIVE_GAME_OBSERVATION_TTL_MS);
                    String state = activePlayniteGameStates.getOrDefault(
                            stateKey, "unknown");
                    String gameId = normalizeId(activePlayniteGameIds.get(stateKey));
                    boolean identityMatches = retained.playniteGameId.isEmpty()
                            ? "idle".equals(state)
                            : "running".equals(state)
                            && HostGatewayClient.isPlayniteId(gameId)
                            && gameId.equalsIgnoreCase(retained.playniteGameId);
                    return fresh && identityMatches
                            && PlayniteTargetResolver.isNeutralStream(retainedTarget)
                            && RetainedStreamSessionCoordinator.canSwitchGame(
                            intent.hostId, intent.profileId, retained.appId);
                }
                return (preparing || retained.state
                        == RetainedStreamSessionCoordinator.State.HOME_LIVE)
                        && host != null
                        && (preparing || host.runningGameId == retained.appId)
                        && retained.hostId.equalsIgnoreCase(intent.hostId)
                        && retained.profileId.equals(intent.profileId)
                        && (preparing || !retained.playniteGameId.equalsIgnoreCase(
                                intent.playniteGameId))
                        && PlayniteTargetResolver.isNeutralStream(retainedTarget)
                        && RetainedStreamSessionCoordinator.canSwitchGame(
                                intent.hostId, intent.profileId, retained.appId);
            }

            @Override public void showLoading(PlayIntent intent,
                                              LaunchTransitionType type,
                                              Runnable opaqueFrameReady) {
                if (intent.kind == PlayIntent.Kind.PLAYNITE_GAME
                        && warmUpStatus == WARM_UP_ERROR
                        && warmUpClientHostId.equals(normalizeId(intent.hostId))
                        && !sessionOrchestrator.hasPreparationForHost(intent.hostId)) {
                    warmUpClientAttempt = 0L;
                    warmUpClientHostId = "";
                    warmUpStatus = WARM_UP_NONE;
                }
                ComputerDetails host = currentHost(intent.hostId);
                ConsoleActivity.this.showLoading(
                        host == null ? intent.hostId : host.name, intent.appName,
                        type, cachedLoadingArtworkPath(
                                intent.hostId, intent.profileId,
                                intent.loadingArtworkGameId));
                if (streamLoadingView != null) {
                    streamLoadingView.doAfterNextFrame(opaqueFrameReady);
                } else {
                    opaqueFrameReady.run();
                }
            }

            @Override public void refreshSession(
                    PlayIntent intent, BooleanSupplier cancelled,
                    Consumer<Boolean> completion) {
                if (streamLoadingView != null) {
                    streamLoadingView.setStep(1,
                            getString(R.string.console_session_uncertain_title));
                }
                ComputerDetails host = currentHost(intent.hostId);
                if (host == null) {
                    completion.accept(false);
                    return;
                }
                resolveActivePlayniteGame(host, intent.profileKey, true, accepted -> {
                    if (!cancelled.getAsBoolean()) completion.accept(accepted);
                });
            }

            @Override public HostLaunchPreflight.Result preflight(
                    PlayIntent intent, HostLaunchPreflight.Action action,
                    long orchestrationId,
                    BooleanSupplier cancelled) {
                long timelineEpoch = streamLoadingEpoch;
                LimeLog.info("Launch timeline epoch=" + timelineEpoch
                        + " host=" + intent.hostId
                        + " game=" + intent.transitionGameId()
                        + " +" + Math.max(0L, SystemClock.uptimeMillis() - timelineEpoch)
                        + "ms preflight-start action=" + action);
                HostLaunchPreflight.Result result = hostLaunchPreflight.run(
                        HostLaunchPreflight.Request.from(
                                intent, action, orchestrationId), cancelled,
                        stage -> mainHandler.post(() -> {
                            if (!cancelled.getAsBoolean() && streamLoadingView != null) {
                                streamLoadingView.setStep(1,
                                        preflightStageMessage(stage, intent));
                            }
                        }));
                LimeLog.info("Launch timeline epoch=" + timelineEpoch
                        + " host=" + intent.hostId
                        + " game=" + intent.transitionGameId()
                        + " +" + Math.max(0L, SystemClock.uptimeMillis() - timelineEpoch)
                        + "ms preflight-" + result.status.name().toLowerCase(Locale.ROOT));
                return result;
            }

            @Override public SessionOrchestrator.CloseResult closePreviousSession(
                    PlayIntent intent, NvApp target, boolean allowDestructiveClose,
                    BooleanSupplier cancelled)
                    throws IOException, XmlPullParserException {
                ComputerDetails host = currentHost(intent.hostId);
                if (host == null) throw new IOException("Host unavailable");
                if (intent.kind == PlayIntent.Kind.PLAYNITE_GAME
                        && PlayniteTargetResolver.isNeutralStream(target)) {
                    RetainedStreamSessionCoordinator.Snapshot retained =
                            RetainedStreamSessionCoordinator.snapshot();
                    if (retained.appId == target.getAppId()
                            && canAttemptRetainedSwitch(intent)) {
                        try {
                            return switchRetainedProviderGame(intent, target, cancelled);
                        } catch (IOException error) {
                            if (!allowDestructiveClose
                                    && "retained_switch_not_eligible".equals(
                                    error.getMessage())) {
                                return SessionOrchestrator.CloseResult.NEEDS_CONFIRMATION;
                            }
                            throw error;
                        }
                    }
                    if (!allowDestructiveClose) {
                        return SessionOrchestrator.CloseResult.NEEDS_CONFIRMATION;
                    }
                } else if (!allowDestructiveClose) {
                    return SessionOrchestrator.CloseResult.NEEDS_CONFIRMATION;
                }
                RetainedStreamSessionCoordinator.Snapshot retained =
                        RetainedStreamSessionCoordinator.snapshot();
                boolean exactRetained = allowDestructiveClose
                        && !retained.streamSessionId.isEmpty()
                        && retained.hostId.equalsIgnoreCase(intent.hostId)
                        && retained.profileId.equals(intent.profileId)
                        && retained.appId == host.runningGameId;
                boolean markedReconnect = false;
                if (exactRetained) {
                    Boolean terminated = terminateLiveRetainedForReplacement(
                            retained, cancelled);
                    if (terminated != null) {
                        LimeLog.info("Destructive close path=retained-live session="
                                + retained.streamSessionId + " success=" + terminated);
                        if (terminated) return SessionOrchestrator.CloseResult.CLOSED;
                        if (cancelled.getAsBoolean()) {
                            return SessionOrchestrator.CloseResult.CANCELLED;
                        }
                        throw new IOException("Retained session was not closed");
                    }
                    RetainedStreamSessionCoordinator.Snapshot current =
                            RetainedStreamSessionCoordinator.snapshot();
                    markedReconnect = current.state
                            == RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED
                            && current.streamSessionId.equals(retained.streamSessionId)
                            && RetainedStreamSessionCoordinator.markTerminating(
                            retained.streamSessionId, retained.hostId,
                            retained.appId, retained.playniteGameId);
                    LimeLog.info("Destructive close path=reconnect-fallback session="
                            + retained.streamSessionId + " marked=" + markedReconnect);
                    if (!markedReconnect) {
                        throw new IOException("Retained session changed before close");
                    }
                } else {
                    LimeLog.info("Destructive close path=direct-nonretained host="
                            + intent.hostId + " app=" + host.runningGameId);
                }
                boolean closed = false;
                try {
                    closed = ConsoleActivity.this.closePreviousSession(
                            host, target.getAppId(), true,
                            intent.kind == PlayIntent.Kind.PLAYNITE_GAME, intent.profileId,
                            cancelled,
                            message -> mainHandler.post(() -> {
                                if (!cancelled.getAsBoolean() && streamLoadingView != null) {
                                    streamLoadingView.setStep(1, message);
                                }
                            }));
                } finally {
                    if (markedReconnect) {
                        if (closed) {
                            RetainedStreamSessionCoordinator.clearIfMatches(
                                    retained.streamSessionId);
                        } else {
                            RetainedStreamSessionCoordinator
                                    .restoreReconnectIfTerminating(
                                            retained.streamSessionId,
                                            retained.hostId, retained.appId,
                                            retained.playniteGameId);
                        }
                    }
                }
                if (closed) return SessionOrchestrator.CloseResult.CLOSED;
                if (cancelled.getAsBoolean()) {
                    return SessionOrchestrator.CloseResult.CANCELLED;
                }
                throw new IOException("Previous session was not closed");
            }

            @Override public void launch(PlayIntent intent, NvApp app,
                                         LaunchTransitionType type, String sourceSuspendId,
                                         boolean ownsFreshSunshineSession) {
                ComputerDetails host = currentHost(intent.hostId);
                if (host == null) {
                    preflightFailed(new HostLaunchPreflight.Failure(
                            HostLaunchPreflight.Stage.NETWORK_READY,
                            HostLaunchPreflight.FailureReason.NETWORK_UNAVAILABLE));
                    return;
                }
                if (intent.kind == PlayIntent.Kind.PLAYNITE_GAME) {
                    playniteLaunchTargetStore.setGameTarget(intent.hostId,
                            intent.playniteGameId, app.getAppId());
                }
                boolean legacySunshineLaunch = intent.kind == PlayIntent.Kind.PLAYNITE_GAME
                        && !intent.neutralStream
                        && !PlayniteTargetResolver.isNeutralStream(app);
                LaunchTransitionType transitionType = legacySunshineLaunch
                        ? LaunchTransitionType.GAME_CONNECTION : intent.transitionType(type);
                LaunchTransitionSpec transition = LaunchTransitionSpec.create(
                        intent.hostId, intent.profileId, transitionType, app.getAppId(),
                        intent.transitionGameId(), System.currentTimeMillis(),
                        intent.startBeforeStream);
                launchPreparedStream(host, app,
                        intent.quickLaunchId.isEmpty() ? null : intent.quickLaunchId,
                        transition, cachedLoadingArtworkPath(
                                intent.hostId, intent.profileId,
                                intent.loadingArtworkGameId),
                        sourceSuspendId, intent.playniteGameId,
                        ownsFreshSunshineSession
                                && transitionType == LaunchTransitionType.GAME
                                && PlayniteTargetResolver.isNeutralStream(app), intent);
            }

            @Override public SessionOrchestrator.PreparedWarmUp prepareHost(
                    PlayIntent intent, long request, BooleanSupplier cancelled) {
                RetainedStreamSessionCoordinator.Snapshot retained =
                        RetainedStreamSessionCoordinator.snapshot();
                boolean localLifecycle = retained.state !=
                        RetainedStreamSessionCoordinator.State.NONE;
                if (localLifecycle && (!retained.hostId.equalsIgnoreCase(intent.hostId)
                        || !retained.profileId.equals(intent.profileId))) {
                    if (!cancelled.getAsBoolean()) {
                        setWarmUpStatus(intent.hostId, request, WARM_UP_ERROR);
                    }
                    return null;
                }
                if (retained.state == RetainedStreamSessionCoordinator.State.TERMINATING) {
                    if (!cancelled.getAsBoolean()) {
                        setWarmUpStatus(intent.hostId, request, WARM_UP_ERROR);
                    }
                    return null;
                }
                if (retained.hostId.equalsIgnoreCase(intent.hostId)
                        && retained.profileId.equals(intent.profileId)
                        && (retained.state == RetainedStreamSessionCoordinator.State.HOME_LIVE
                        || retained.state == RetainedStreamSessionCoordinator.State.PARKED_LIVE
                        || retained.state == RetainedStreamSessionCoordinator.State.PREPARING)) {
                    setWarmUpStatus(intent.hostId, request,
                            retained.state == RetainedStreamSessionCoordinator.State.PREPARING
                                    ? WARM_UP_PREPARING : WARM_UP_READY);
                    return null;
                }
                ComputerDetails host = currentHost(intent.hostId);
                if (host == null) {
                    if (!cancelled.getAsBoolean()) {
                        setWarmUpStatus(intent.hostId, request, WARM_UP_ERROR);
                    }
                    return null;
                }
                WarmUpBridgeProbe initialBridge = probeWarmUpBridge(host, intent.profileId);
                boolean sendWake = SessionOrchestrator.shouldSendWarmUpWake(
                        retained.state, initialBridge.responded, host.state);
                setWarmUpStatus(intent.hostId, request,
                        sendWake ? WARM_UP_WAKING : WARM_UP_PREPARING);
                ComputerDetails ready = HostReadiness.awaitAfterWakeDecision(
                        () -> managerBinder == null ? null
                                : managerBinder.getComputer(intent.hostId),
                        host, sendWake, cancelled, ignored -> { },
                        getString(R.string.console_wol_status, host.name),
                        getString(R.string.console_waiting_stream_ports));
                if (ready == null || cancelled.getAsBoolean()) {
                    if (!cancelled.getAsBoolean()) {
                        setWarmUpStatus(intent.hostId, request, WARM_UP_ERROR);
                    }
                    return null;
                }
                setWarmUpStatus(intent.hostId, request, WARM_UP_PREPARING);
                HostLaunchPreflight.Result result = hostLaunchPreflight.run(
                        HostLaunchPreflight.Request.from(intent,
                                HostLaunchPreflight.Action.WARM_UP, request),
                        cancelled, ignored -> { });
                if (result.status != HostLaunchPreflight.Status.READY
                        || result.target == null || cancelled.getAsBoolean()) {
                    if (!cancelled.getAsBoolean()) {
                        setWarmUpStatus(intent.hostId, request, WARM_UP_ERROR);
                    }
                    return null;
                }
                try {
                    ready = refreshWarmUpHost(ready);
                } catch (IOException unavailable) {
                    if (!cancelled.getAsBoolean()) {
                        setWarmUpStatus(intent.hostId, request, WARM_UP_ERROR);
                    }
                    return null;
                }
                if (!ConsoleActionCatalog.isPaired(ready)) {
                    setWarmUpStatus(intent.hostId, request, WARM_UP_ERROR);
                    return null;
                }
                if (ready.runningGameId != 0
                        && ready.runningGameId != result.target.getAppId()) {
                    setWarmUpStatus(intent.hostId, request, WARM_UP_ERROR);
                    return null;
                }
                WarmUpBridgeProbe freshBridge = probeWarmUpBridge(ready, intent.profileId);
                return new SessionOrchestrator.PreparedWarmUp(
                        result.target, freshBridge.gameId, ready,
                        intent.profileKey,
                        ready.runningGameId == 0);
            }

            @Override public void launchPreparedHost(
                    SessionOrchestrator.PreparedWarmUp prepared,
                    PlayIntent pendingGame, long request) {
                if (prepared.host == null || request != warmUpClientAttempt
                        || !prepared.host.uuid.equalsIgnoreCase(warmUpClientHostId)) return;
                launchPreparedWarmUp(prepared, pendingGame, request);
            }

            @Override public void preparationFailed() {
                setWarmUpStatus(warmUpClientHostId, warmUpClientAttempt, WARM_UP_ERROR);
                if (streamLoadingView != null
                        && loadingLayer.getVisibility() == View.VISIBLE) {
                    streamLoadingView.showError(
                            getString(R.string.console_warm_up_error),
                            getString(R.string.console_warm_up_fallback_details));
                }
            }


            @Override public void preflightFailed(HostLaunchPreflight.Failure failure) {
                if (streamLoadingView != null) {
                    streamLoadingView.showError(
                            getString(failure.stage == HostLaunchPreflight.Stage.NETWORK_READY
                                    ? R.string.console_host_not_ready
                                    : R.string.playnite_launch_unavailable),
                            preflightFailureMessage(failure));
                }
            }

            @Override public void orchestrationFailed() {
                if (streamLoadingView != null) {
                    streamLoadingView.showError(getString(R.string.console_host_not_ready),
                            getString(R.string.console_host_timeout));
                }
            }

            @Override public void previousSessionCloseFailed(String reason) {
                if (streamLoadingView != null) {
                    int detail = reason != null && reason.contains("game_stop_timeout")
                            ? R.string.console_provider_stop_waiting_for_process
                            : R.string.console_previous_session_close_failed;
                    streamLoadingView.showError(getString(R.string.applist_quit_fail),
                            getString(detail));
                }
            }

            @Override public void uncertainSessionFailed() {
                if (streamLoadingView != null) {
                    streamLoadingView.showError(
                            getString(R.string.console_session_uncertain_title),
                            getString(R.string.console_session_uncertain_details));
                }
            }
        }, executor, mainHandler::post);
    }

    private Boolean terminateLiveRetainedForReplacement(
            RetainedStreamSessionCoordinator.Snapshot retained,
            BooleanSupplier cancelled) throws IOException {
        AtomicReference<Boolean> result = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        RetainedStreamSessionCoordinator.TerminationResult started =
                RetainedStreamSessionCoordinator.terminate(
                        retained.streamSessionId, success -> {
                            result.set(success);
                            completed.countDown();
                        });
        if (started == RetainedStreamSessionCoordinator.TerminationResult.NO_CONTROLLER) {
            return null;
        }
        if (started != RetainedStreamSessionCoordinator.TerminationResult.STARTED) {
            throw new IOException("Retained session termination is already in progress");
        }
        long deadline = SystemClock.uptimeMillis() + 60_000L;
        try {
            while (result.get() == null && !cancelled.getAsBoolean()
                    && SystemClock.uptimeMillis() < deadline) {
                completed.await(100L, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while closing retained session", interrupted);
        }
        if (cancelled.getAsBoolean()) return false;
        if (result.get() == null) throw new IOException("Retained session close timed out");
        return result.get();
    }

    private void beginInstallationSupportStream(ComputerDetails host, NvApp app,
                                                String playniteGameId) {
        if (host == null || app == null) return;
        LaunchTransitionType transitionType = LaunchTransitionType.GENERIC;
        String loadingArtworkGameId = playniteGameId;
        if (handleRetainedStreamLaunch(host, app, playniteGameId)) return;
        if (managerBinder == null) {
            ConsoleUiFeedback.makeText(this, R.string.console_initializing, Toast.LENGTH_SHORT).show();
            return;
        }
        String transitionProfileId = selectedProfileId(host.uuid);
        int token = hostPreparationGeneration.incrementAndGet();
        LaunchTransitionSpec transition = LaunchTransitionSpec.create(
                host.uuid, transitionProfileId, transitionType,
                app.getAppId(), playniteGameId,
                System.currentTimeMillis());
        SuspendedSessionStore.Session suspendedLaunch =
                SuspendedSessionStore.load(this, host.uuid, transitionProfileId);
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
                    () -> token != hostPreparationGeneration.get() || !active,
                    message -> setLoadingStatus(token, message),
                    getString(R.string.console_wol_status, host.name),
                    getString(R.string.console_waiting_stream_ports));
            if (ready != null && restoringSuspendedSession) {
                PlayniteTransitionGateway gateway = PlayniteTransitionGateway.connect(
                        this, host.uuid, ready.activeAddress != null
                                ? ready.activeAddress.address : null, transitionProfileId);
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
                    closePreviousSession(ready, app.getAppId(), false, false,
                            transitionProfileId,
                            () -> token != hostPreparationGeneration.get() || !active,
                            message -> setLoadingStatus(token, message));
                } catch (IOException | XmlPullParserException error) {
                    mainHandler.post(() -> {
                        if (token != hostPreparationGeneration.get() || !active) return;
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
                if (token != hostPreparationGeneration.get() || !active) return;
                if (ready == null) {
                    if (streamLoadingView != null) {
                        streamLoadingView.showError(
                                getString(R.string.console_host_not_ready),
                                getString(R.string.console_host_timeout));
                    }
                    return;
                }
                launchPreparedStream(ready, app, "", transition, loadingArtworkPath,
                        restoringSuspendedSession ? suspendedLaunch.suspendId : "",
                        restoringSuspendedSession ? suspendedLaunch.playniteGameId : "", false);
            });
        });
        if (streamLoadingView != null) {
            streamLoadingView.doAfterNextFrame(startAfterOverlayFrame);
        } else {
            startAfterOverlayFrame.run();
        }
    }

    private void launchPreparedStream(ComputerDetails host, NvApp app,
                                      String quickLaunchKey,
                                      LaunchTransitionSpec transition,
                                      String loadingArtworkPath,
                                      String sourceSuspendId,
                                      String sourceSuspendPlayniteGameId,
                                      boolean ownsFreshSunshineSession) {
        launchPreparedStream(host, app, quickLaunchKey, transition, loadingArtworkPath,
                sourceSuspendId, sourceSuspendPlayniteGameId,
                ownsFreshSunshineSession, 0L, null, true, null);
    }

    private void launchPreparedStream(ComputerDetails host, NvApp app,
                                      String quickLaunchKey,
                                      LaunchTransitionSpec transition,
                                      String loadingArtworkPath,
                                      String sourceSuspendId,
                                      String sourceSuspendPlayniteGameId,
                                      boolean ownsFreshSunshineSession,
                                      PlayIntent playIntent) {
        launchPreparedStream(host, app, quickLaunchKey, transition, loadingArtworkPath,
                sourceSuspendId, sourceSuspendPlayniteGameId,
                ownsFreshSunshineSession, 0L, null, true, playIntent);
    }

    private void launchPreparedWarmUp(SessionOrchestrator.PreparedWarmUp prepared,
                                      PlayIntent pendingGame, long request) {
        LaunchTransitionType type = prepared.activeGameId.isEmpty()
                ? LaunchTransitionType.GENERIC : LaunchTransitionType.GAME_CONNECTION;
        LaunchTransitionSpec transition = LaunchTransitionSpec.create(
                prepared.host.uuid, prepared.profileKey.profileId, type,
                prepared.target.getAppId(),
                prepared.activeGameId, System.currentTimeMillis());
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        SessionResumeManager.PendingSession pending =
                SessionResumeManager.pendingSession(this);
        boolean exactPending = pending != null && pending.neutralStreamTarget
                && pending.hostUuid.equalsIgnoreCase(prepared.host.uuid)
                && pending.profileId.equals(prepared.profileKey.profileId)
                && pending.appId == prepared.target.getAppId();
        boolean coordinatorReconnect = exactPending
                && retained.state == RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED
                && retained.hostId.equalsIgnoreCase(pending.hostUuid)
                && retained.profileId.equals(pending.profileId)
                && retained.appId == pending.appId
                && retained.streamSessionId.equals(pending.streamSessionId);
        boolean pendingOnlyReconnect = exactPending && pending.autoResume
                && retained.state == RetainedStreamSessionCoordinator.State.NONE;
        if (coordinatorReconnect || pendingOnlyReconnect) {
            Intent resume = SessionResumeManager.buildResumeIntent(this, pending);
            if (resume == null) {
                setWarmUpStatus(prepared.host.uuid, request, WARM_UP_ERROR);
                return;
            }
            applyWarmUpIntent(resume, transition, pendingGame, request);
            refreshSessionOnResume = true;
            startActivity(resume);
            overridePendingTransition(0, 0);
            return;
        }
        SuspendedSessionStore.Session suspended =
                SuspendedSessionStore.load(this, prepared.host.uuid,
                        prepared.profileKey.profileId);
        String sourceSuspendId = suspended != null && suspended.resumedAt == 0L
                && suspended.sunshineAppId == prepared.target.getAppId()
                ? suspended.suspendId : "";
        launchPreparedStream(prepared.host, prepared.target, null, transition, null,
                sourceSuspendId, sourceSuspendId.isEmpty() ? "" : suspended.playniteGameId,
                prepared.ownsFreshSunshineSession, request, pendingGame, false, null);
    }

    private void applyWarmUpIntent(Intent intent, LaunchTransitionSpec transition,
                                   PlayIntent pendingGame, long request) {
        intent.putExtra(Game.EXTRA_AUTO_WARM_UP_ATTEMPT, request);
        intent.putExtra(EXTRA_WARM_UP_ATTEMPT, request);
        intent.putExtra(Game.EXTRA_NEUTRAL_STREAM_TARGET, true);
        intent.putExtra(Game.EXTRA_STREAM_TARGET_NAME,
                PlayniteTargetResolver.MOONWAKER_STREAM_NAME);
        intent.putExtra(Game.EXTRA_TRANSITION_ID, transition.id);
        intent.putExtra(Game.EXTRA_PROFILE_ID, transition.profileId);
        intent.putExtra(Game.EXTRA_TRANSITION_TYPE, transition.type.name());
        intent.putExtra(Game.EXTRA_TRANSITION_HOST_ID, transition.hostId);
        intent.putExtra(Game.EXTRA_TRANSITION_PLAYNITE_GAME_ID,
                transition.playniteGameId);
        intent.putExtra(Game.EXTRA_TRANSITION_CREATED_AT, transition.createdAtMillis);
        intent.putExtra(Game.EXTRA_TRANSITION_START_BEFORE_STREAM,
                transition.startProviderBeforeStream);
        if (transition.playniteGameId.isEmpty()) {
            intent.putExtra(Game.EXTRA_APP_NAME,
                    PlayniteTargetResolver.MOONWAKER_STREAM_NAME);
            intent.removeExtra(Game.EXTRA_QUICK_LAUNCH_APP_KEY);
            intent.removeExtra(Game.EXTRA_CONSOLE_LOADING_ARTWORK);
        }
        if (pendingGame != null) {
            intent.putExtra(EXTRA_WARM_UP_PENDING_GAME_ID, pendingGame.playniteGameId);
            intent.putExtra(EXTRA_WARM_UP_PENDING_GAME_NAME, pendingGame.appName);
            intent.putExtra(EXTRA_WARM_UP_PENDING_ARTWORK_ID,
                    pendingGame.loadingArtworkGameId);
            intent.putExtra(EXTRA_WARM_UP_PENDING_QUICK_LAUNCH,
                    pendingGame.quickLaunchId);
            intent.putExtra(EXTRA_WARM_UP_PENDING_REQUIRES_CONNECTOR,
                    pendingGame.requiresConnector);
            intent.putExtra(EXTRA_WARM_UP_PENDING_NEUTRAL_STREAM,
                    pendingGame.neutralStream);
            intent.putExtra(EXTRA_WARM_UP_PENDING_START_BEFORE_STREAM,
                    pendingGame.startBeforeStream);
        }
    }

    private void launchPreparedStream(ComputerDetails host, NvApp app,
                                      String quickLaunchKey,
                                      LaunchTransitionSpec transition,
                                      String loadingArtworkPath,
                                      String sourceSuspendId,
                                      String sourceSuspendPlayniteGameId,
                                      boolean ownsFreshSunshineSession,
                                      long warmUpAttempt,
                                      PlayIntent pendingWarmUpGame,
                                      boolean recordHistory,
                                      PlayIntent playIntent) {
        long playedAt = System.currentTimeMillis();
        if (recordHistory) {
            SharedPreferences.Editor history = preferences.edit()
                    .putLong(appHistoryKey(host.uuid, app.getAppId()), playedAt)
                    .putLong("host_played." + host.uuid, playedAt);
            if (transition.type == LaunchTransitionType.GAME
                    || transition.type == LaunchTransitionType.GAME_CONNECTION) {
                history.putLong(playniteGameHistoryKey(
                        host.uuid, transition.playniteGameId), playedAt);
            }
            history.apply();
        }
        String openingStatus = getString(R.string.console_opening_stream);
        if (streamLoadingView != null) streamLoadingView.setStep(2, openingStatus);
        Bundle presentation = new Bundle();
        presentation.putBoolean(Game.EXTRA_CONSOLE_LOADING, true);
        presentation.putString(Game.EXTRA_CONSOLE_LOADING_MESSAGE,
                streamLoadingView == null ? null : streamLoadingView.getCurrentMessage());
        presentation.putLong(Game.EXTRA_CONSOLE_LOADING_EPOCH, streamLoadingEpoch);
        presentation.putInt(Game.EXTRA_CONSOLE_LOADING_STEP, 2);
        presentation.putString(Game.EXTRA_CONSOLE_LOADING_STATUS, openingStatus);
        presentation.putBoolean(Game.EXTRA_CONSOLE_REDUCED_MOTION, reducedMotion);
        presentation.putString(Game.EXTRA_CONSOLE_LOADING_ARTWORK, loadingArtworkPath);
        presentation.putString(Game.EXTRA_TRANSITION_ID, transition.id);
        presentation.putString(Game.EXTRA_PROFILE_ID, transition.profileId);
        presentation.putString(Game.EXTRA_TRANSITION_TYPE, transition.type.name());
        presentation.putString(Game.EXTRA_TRANSITION_HOST_ID, transition.hostId);
        presentation.putString(Game.EXTRA_TRANSITION_PLAYNITE_GAME_ID,
                transition.playniteGameId);
        presentation.putLong(Game.EXTRA_TRANSITION_CREATED_AT, transition.createdAtMillis);
        presentation.putBoolean(Game.EXTRA_TRANSITION_START_BEFORE_STREAM,
                transition.startProviderBeforeStream);
        presentation.putString(Game.EXTRA_STREAM_TARGET_NAME, app.getAppName());
        presentation.putBoolean(Game.EXTRA_NEUTRAL_STREAM_TARGET,
                PlayniteTargetResolver.isNeutralStream(app));
        presentation.putBoolean(Game.EXTRA_FRESH_SUNSHINE_SESSION_OWNER,
                ownsFreshSunshineSession);
        if (playIntent != null && playIntent.isCalibration()) {
            presentation.putString(Game.EXTRA_AUTOPILOT_CALIBRATION_APP_KEY,
                    playIntent.calibrationAppKey);
            presentation.putInt(Game.EXTRA_RUNTIME_WIDTH, playIntent.runtimeWidth);
            presentation.putInt(Game.EXTRA_RUNTIME_HEIGHT, playIntent.runtimeHeight);
            presentation.putInt(Game.EXTRA_RUNTIME_FPS, playIntent.runtimeFps);
            presentation.putInt(Game.EXTRA_RUNTIME_BITRATE_KBPS,
                    playIntent.runtimeBitrateKbps);
        }
        if (warmUpAttempt > 0L) {
            presentation.putLong(Game.EXTRA_AUTO_WARM_UP_ATTEMPT, warmUpAttempt);
            presentation.putLong(EXTRA_WARM_UP_ATTEMPT, warmUpAttempt);
        }
        if (pendingWarmUpGame != null) {
            presentation.putString(EXTRA_WARM_UP_PENDING_GAME_ID,
                    pendingWarmUpGame.playniteGameId);
            presentation.putString(EXTRA_WARM_UP_PENDING_GAME_NAME,
                    pendingWarmUpGame.appName);
            presentation.putString(EXTRA_WARM_UP_PENDING_ARTWORK_ID,
                    pendingWarmUpGame.loadingArtworkGameId);
            presentation.putString(EXTRA_WARM_UP_PENDING_QUICK_LAUNCH,
                    pendingWarmUpGame.quickLaunchId);
        }
        if (sourceSuspendId != null && !sourceSuspendId.isEmpty()) {
            presentation.putString(Game.EXTRA_SOURCE_SUSPEND_ID, sourceSuspendId);
            presentation.putString(Game.EXTRA_SOURCE_SUSPEND_PLAYNITE_GAME_ID,
                    sourceSuspendPlayniteGameId);
        }
        refreshSessionOnResume = true;
        ServerHelper.doStart(this, app, host, managerBinder, quickLaunchKey, presentation);
        overridePendingTransition(0, 0);
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
        if (!RetainedStreamSessionCoordinator.canResumeInstantly()) {
            SessionResumeManager.PendingSession pending =
                    SessionResumeManager.pendingSession(this);
            Intent resume = SessionResumeManager.buildResumeIntent(this, pending);
            if (resume != null) startActivity(resume);
        }
        if (retainedStreamHome) finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    private SessionOrchestrator.CloseResult switchRetainedProviderGame(
            PlayIntent intent, NvApp target, BooleanSupplier cancelled)
            throws IOException {
        AtomicReference<RetainedStreamSessionCoordinator.SwitchOutcome> outcome =
                new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>("");
        CountDownLatch completed = new CountDownLatch(1);
        RetainedStreamSessionCoordinator.SwitchResult started =
                RetainedStreamSessionCoordinator.switchGame(
                        intent.hostId, intent.profileId, target.getAppId(),
                        intent.playniteGameId,
                        intent.appName, target.getAppName(), cachedLoadingArtworkPath(
                                intent.hostId, intent.profileId,
                                intent.loadingArtworkGameId), cancelled,
                        (result, reason) -> {
                            outcome.set(result);
                            error.set(reason == null ? "" : reason);
                            completed.countDown();
                        });
        if (started != RetainedStreamSessionCoordinator.SwitchResult.STARTED) {
            throw new IOException("retained_switch_not_eligible");
        }
        long deadline = SystemClock.uptimeMillis() + 60_000L;
        try {
            while (outcome.get() == null && SystemClock.uptimeMillis() < deadline) {
                completed.await(100L, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while switching provider game", interrupted);
        }
        RetainedStreamSessionCoordinator.SwitchOutcome result = outcome.get();
        if (result == RetainedStreamSessionCoordinator.SwitchOutcome.REUSED) {
            long playedAt = System.currentTimeMillis();
            preferences.edit()
                    .putLong(appHistoryKey(intent.hostId, target.getAppId()), playedAt)
                    .putLong(playniteGameHistoryKey(
                            intent.hostId, intent.playniteGameId), playedAt)
                    .putLong("host_played." + intent.hostId, playedAt)
                    .apply();
            return SessionOrchestrator.CloseResult.REUSED;
        }
        if (result == RetainedStreamSessionCoordinator.SwitchOutcome.CANCELLED) {
            return SessionOrchestrator.CloseResult.CANCELLED;
        }
        if (result == null) throw new IOException("retained_switch_timeout");
        throw new IOException(error.get().isEmpty()
                ? "retained_switch_failed" : error.get());
    }

    private boolean closePreviousSession(ComputerDetails host, int targetAppId,
                                         boolean closeMatchingApp,
                                         boolean requireProviderVerification,
                                         String profileId,
                                         BooleanSupplier cancelled,
                                         Consumer<String> status)
            throws IOException, XmlPullParserException {
        int expectedRunningAppId = host.runningGameId;
        if (!closeMatchingApp && expectedRunningAppId != 0
                && expectedRunningAppId == targetAppId) return true;
        if (cancelled.getAsBoolean()) return false;
        status.accept(getString(R.string.transition_closing_session));
        stopActiveProviderGame(host, knownProviderGameId(
                        host, SuspendedSessionStore.load(this, host.uuid,
                                profileId)),
                requireProviderVerification, profileId);
        NvHTTP connection = new NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(host), host.httpsPort,
                managerBinder.getUniqueId(), host.serverCert,
                PlatformBinding.getCryptoProvider(this));
        if (cancelled.getAsBoolean()) return false;
        ComputerDetails fresh = connection.getComputerDetails(true);
        AuthoritativeSessionTransition.SunshineStopAction action =
                AuthoritativeSessionTransition.sunshineStopAction(
                        expectedRunningAppId, fresh.runningGameId);
        if (action == AuthoritativeSessionTransition.SunshineStopAction.COMPLETE) return true;
        if (action != AuthoritativeSessionTransition.SunshineStopAction.QUIT) {
            throw new IOException("Previous host session changed before close.");
        }
        if (!connection.quitApp()) {
            throw new IOException("Host rejected closing the previous session.");
        }
        long deadline = SystemClock.uptimeMillis() + PREVIOUS_SESSION_CLOSE_TIMEOUT_MS;
        IOException lastError = null;
        while (!cancelled.getAsBoolean() && SystemClock.uptimeMillis() < deadline) {
            try {
                ComputerDetails current = connection.getComputerDetails(true);
                if (current.runningGameId == 0
                        || (!closeMatchingApp && current.runningGameId == targetAppId)) {
                    return true;
                }
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
        if (cancelled.getAsBoolean()) return false;
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
        if (discordDmNotifications != null) {
            discordDmNotifications.setPresentationBlocked(discordDmHostToken, true);
        }
        if (consoleAudioEngine != null) consoleAudioEngine.setMenuVisible(false);
        lastContentFocus = getCurrentFocus();
        lastContentFocusTag = lastContentFocus == null ? null : lastContentFocus.getTag();
        homeLayer.setVisibility(View.GONE);
        loadingLayer.setVisibility(View.VISIBLE);
        loadingLayer.bringToFront();
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
                cancelCurrentPreparation();
                hostPreparationGeneration.incrementAndGet();
                showHome();
            }

            @Override public void onRetry() {
                if (sessionOrchestrator != null) sessionOrchestrator.retry();
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
            if (token == hostPreparationGeneration.get()
                    && loadingLayer.getVisibility() == View.VISIBLE) {
                if (streamLoadingView != null) streamLoadingView.setStep(1, message);
            }
        });
    }

    private void showHome() {
        if (discordDmNotifications != null) {
            discordDmNotifications.setPresentationBlocked(discordDmHostToken, false);
        }
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
            refreshDiscordIndicator();
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
                11, batteryColor, true);
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

    private void requestStreamingAutopilot(ComputerDetails requestedHost, String appKey,
                                           String inheritedAppKey, String targetName,
                                           PlayIntent calibrationIntent) {
        ComputerDetails host = requestedHost == null ? null : currentHost(requestedHost.uuid);
        if (!ConsoleActionCatalog.isOnline(host) || !ConsoleActionCatalog.isPaired(host)
                || host.activeAddress == null || managerBinder == null) {
            consoleFeedback.notify(ConsoleUiFeedback.Kind.ERROR,
                    getString(R.string.console_autopilot_unavailable));
            return;
        }
        if (calibrationIntent != null && hasStreamingAutopilotCalibrationSession(host)) {
            consoleFeedback.notify(ConsoleUiFeedback.Kind.ERROR,
                    getString(R.string.console_autopilot_calibration_session_active));
            return;
        }
        if (preferences.getBoolean(OverridesView.PREF_OVERRIDES_ENABLED, false)
                && preferences.getInt(OverridesView.PREF_BITRATE_OVERRIDE, 0) > 0) {
            consoleFeedback.notify(ConsoleUiFeedback.Kind.ERROR,
                    getString(R.string.console_autopilot_bitrate_override_active));
            return;
        }
        GatewayConnection gateway = hostGatewayStore.loadForHost(
                host.uuid, host.activeAddress.address);
        if (gateway != null && resolveSessionSnapshot(host).hasActiveSession()) {
            consoleFeedback.notify(ConsoleUiFeedback.Kind.INFO,
                    getString(R.string.console_autopilot_active_stream_fallback));
            beginStreamingAutopilot(host, appKey, inheritedAppKey, targetName,
                    calibrationIntent, null);
            return;
        }
        if (gateway != null && isAutopilotNetworkMetered()) {
            TextView fallback = panelAction(
                    getString(R.string.console_autopilot_skip_network_test));
            fallback.setOnClickListener(view -> beginStreamingAutopilot(
                    host, appKey, inheritedAppKey, targetName, calibrationIntent, null));
            TextView measure = panelAction(getString(R.string.console_autopilot_measure));
            measure.setOnClickListener(view -> beginStreamingAutopilot(
                    host, appKey, inheritedAppKey, targetName, calibrationIntent, gateway));
            showSidePanel(getString(R.string.console_options_section_streaming),
                    getString(R.string.console_autopilot_metered_title),
                    getString(R.string.console_autopilot_metered_details),
                    fallback, measure);
            return;
        }
        beginStreamingAutopilot(host, appKey, inheritedAppKey, targetName,
                calibrationIntent, gateway);
    }

    private boolean hasStreamingAutopilotCalibrationSession(ComputerDetails host) {
        return resolveSessionSnapshot(host).state != SessionSnapshot.State.NONE
                || RetainedStreamSessionCoordinator.state()
                != RetainedStreamSessionCoordinator.State.NONE;
    }

    private boolean isAutopilotNetworkMetered() {
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE);
        return connectivity != null && connectivity.isActiveNetworkMetered();
    }

    private void beginStreamingAutopilot(ComputerDetails requestedHost, String appKey,
                                         String inheritedAppKey, String targetName,
                                         PlayIntent calibrationIntent,
                                         GatewayConnection gateway) {
        ComputerDetails current = currentHost(requestedHost.uuid);
        if (current == null || current.activeAddress == null || managerBinder == null
                || selectedHostUuid == null
                || !current.uuid.equalsIgnoreCase(selectedHostUuid)) {
            consoleFeedback.notify(ConsoleUiFeedback.Kind.ERROR,
                    getString(R.string.console_autopilot_unavailable));
            return;
        }

        cancelStreamingAutopilot(false);
        int generation = streamingAutopilotGeneration.incrementAndGet();
        String hostUuid = current.uuid;
        ComputerDetails host = new ComputerDetails(current);
        String uniqueId = managerBinder.getUniqueId();
        Display display = getWindowManager().getDefaultDisplay();
        String glRenderer = GlPreferences.readPreferences(this).glRenderer;

        TextView cancel = panelAction(getString(R.string.console_cancel));
        cancel.setOnClickListener(view -> {
            cancelStreamingAutopilot(true);
            hideSidePanel();
        });
        showSidePanel(getString(R.string.console_options_section_streaming),
                getString(R.string.console_autopilot_analyzing_title),
                getString(R.string.console_autopilot_analyzing_details), cancel);

        streamingAutopilotTask = executor.submit(() -> {
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(host),
                        host.httpsPort, uniqueId, host.serverCert,
                        PlatformBinding.getCryptoProvider(this));
                String serverInfo = http.getServerInfo(true);
                StreamingAutopilotController.Analysis analysis =
                        StreamingAutopilotController.analyze(this, display, glRenderer,
                                http, serverInfo, gateway);
                if (Thread.currentThread().isInterrupted()) return;
                mainHandler.post(() -> {
                    if (generation != streamingAutopilotGeneration.get()) return;
                    streamingAutopilotTask = null;
                    if (!isStreamingAutopilotRequestCurrent(generation, hostUuid)) return;
                    showStreamingAutopilotPreview(generation, hostUuid, appKey,
                            inheritedAppKey, targetName, calibrationIntent, analysis);
                });
            } catch (IOException | XmlPullParserException | RuntimeException error) {
                if (Thread.currentThread().isInterrupted()) return;
                mainHandler.post(() -> {
                    if (generation != streamingAutopilotGeneration.get()) return;
                    streamingAutopilotTask = null;
                    if (!isStreamingAutopilotRequestCurrent(generation, hostUuid)) return;
                    showStreamingAutopilotError(error);
                });
            }
        });
    }

    private boolean isStreamingAutopilotRequestCurrent(int generation, String hostUuid) {
        return generation == streamingAutopilotGeneration.get() && active
                && !isFinishing() && !isDestroyed() && selectedHostUuid != null
                && hostUuid.equalsIgnoreCase(selectedHostUuid);
    }

    private void showStreamingAutopilotPreview(int generation, String hostUuid,
                                               String appKey, String inheritedAppKey,
                                               String targetName,
                                               PlayIntent calibrationIntent,
                                               StreamingAutopilotController.Analysis analysis) {
        if (!analysis.recommendation.isPresent()) {
            TextView close = panelAction(getString(R.string.console_close));
            close.setOnClickListener(view -> hideSidePanel());
            showSidePanel(getString(R.string.console_options_section_streaming),
                    getString(R.string.console_autopilot_no_recommendation_title),
                    getString(R.string.console_autopilot_no_recommendation_details,
                            streamingAutopilotNetworkSummary(analysis)), close);
            return;
        }

        PreferenceConfiguration current;
        if (appKey == null) {
            current = PreferenceConfiguration.readPreferences(this);
        } else if (inheritedAppKey != null) {
            current = AppPreferences.getEffectivePreferences(
                    this, inheritedAppKey, appKey, false);
        } else {
            current = AppPreferences.getEffectivePreferences(this, appKey, null, false);
        }
        com.limelight.stream.StreamingAutopilot.Recommendation recommendation =
                analysis.recommendation.get();
        String currentSummary = getString(R.string.console_autopilot_stream_format,
                current.width, current.height, current.fps, current.bitrate / 1000d);
        String recommendedSummary = getString(R.string.console_autopilot_stream_format,
                recommendation.width, recommendation.height, recommendation.fps,
                recommendation.bitrateKbps / 1000d);
        String confidence = getString(recommendation.confidence
                == com.limelight.stream.StreamingAutopilot.Confidence.HIGH
                ? R.string.console_autopilot_confidence_high
                : R.string.console_autopilot_confidence_low);

        TextView cancel = panelAction(getString(R.string.console_cancel));
        cancel.setOnClickListener(view -> hideSidePanel());
        TextView apply = panelAction(getString(calibrationIntent != null
                ? R.string.console_autopilot_start_calibration : appKey == null
                ? R.string.console_autopilot_apply_global
                : R.string.console_autopilot_apply_game));
        apply.setOnClickListener(view -> {
            if (!isStreamingAutopilotRequestCurrent(generation, hostUuid)) {
                showStreamingAutopilotError(
                        new IllegalStateException(getString(R.string.console_autopilot_stale)));
                return;
            }
            try {
                if (calibrationIntent != null) {
                    ComputerDetails host = currentHost(hostUuid);
                    if (host == null || hasStreamingAutopilotCalibrationSession(host)) {
                        throw new IllegalStateException(getString(
                                R.string.console_autopilot_calibration_session_active));
                    }
                    hideSidePanel();
                    sessionOrchestrator.play(calibrationIntent.withCalibration(
                            appKey, recommendation.width, recommendation.height,
                            recommendation.fps, recommendation.bitrateKbps));
                    return;
                } else if (appKey == null) {
                    StreamingAutopilotController.applyGlobal(this, analysis);
                } else {
                    StreamingAutopilotController.applyForApp(this, appKey, analysis);
                }
                hideSidePanel();
                consoleFeedback.notify(ConsoleUiFeedback.Kind.SUCCESS,
                        getString(R.string.console_autopilot_applied));
            } catch (RuntimeException error) {
                showStreamingAutopilotError(error);
            }
        });
        showScrollableDetailsSidePanel(getString(R.string.console_options_section_streaming),
                getString(R.string.console_autopilot_preview_title),
                getString(calibrationIntent == null
                                ? R.string.console_autopilot_preview_details
                                : R.string.console_autopilot_calibration_preview_details,
                        targetName,
                        currentSummary, recommendedSummary, confidence,
                        streamingAutopilotNetworkSummary(analysis)), cancel, apply);
    }

    private String streamingAutopilotNetworkSummary(
            StreamingAutopilotController.Analysis analysis) {
        switch (analysis.networkStatus) {
            case MEASURED:
                return getString(R.string.console_autopilot_network_measured,
                        analysis.goodputKbps.getAsLong() / 1000d,
                        analysis.safeBitrateKbps.getAsInt() / 1000d);
            case FAILED:
                return getString(R.string.console_autopilot_network_failed);
            case TOO_SLOW:
                return getString(R.string.console_autopilot_network_too_slow,
                        analysis.goodputKbps.getAsLong() / 1000d);
            case UNAVAILABLE:
            default:
                return getString(R.string.console_autopilot_network_unavailable);
        }
    }

    private void showStreamingAutopilotError(Throwable error) {
        String detail = error == null || TextUtils.isEmpty(error.getMessage())
                ? getString(R.string.console_autopilot_error_unknown) : error.getMessage();
        TextView close = panelAction(getString(R.string.console_close));
        close.setOnClickListener(view -> hideSidePanel());
        showSidePanel(getString(R.string.console_options_section_streaming),
                getString(R.string.console_autopilot_error_title),
                getString(R.string.console_autopilot_error_details, detail), close);
    }

    private void cancelStreamingAutopilot(boolean notify) {
        streamingAutopilotGeneration.incrementAndGet();
        Future<?> task = streamingAutopilotTask;
        streamingAutopilotTask = null;
        if (task != null) task.cancel(true);
        if (notify && consoleFeedback != null && !isFinishing() && !isDestroyed()) {
            consoleFeedback.notify(ConsoleUiFeedback.Kind.INFO,
                    getString(R.string.console_autopilot_cancelled));
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
        TextView screenSaver = panelAction(getString(
                R.string.console_screen_saver_timeout, screenSaverTimeoutLabel()));
        screenSaver.setTag("options.screen_saver_timeout");
        TextView settings = panelAction(getString(R.string.console_streaming_settings));
        TextView autopilot = panelAction(getString(R.string.console_streaming_autopilot_global));
        TextView overrides = panelAction(getString(R.string.console_options_overrides));
        TextView integrations = panelAction(getString(R.string.console_host_integrations));
        ComputerDetails selectedHost = hosts.get(selectedHostUuid);
        integrations.setEnabled(selectedHost != null);
        integrations.setAlpha(selectedHost != null ? 1f : .45f);
        boolean autopilotAvailable = ConsoleActionCatalog.isOnline(selectedHost)
                && ConsoleActionCatalog.isPaired(selectedHost);
        autopilot.setEnabled(autopilotAvailable);
        autopilot.setAlpha(autopilotAvailable ? 1f : .45f);
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
            if (discordDmToastView != null) discordDmToastView.setReducedMotion(reducedMotion);
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
        screenSaver.setOnClickListener(v -> showScreenSaverTimeoutPanel());
        hiddenApps.setOnClickListener(v -> {
            toggleHiddenApps();
            updateSettingsToggle(hiddenApps, showHiddenApps);
        });
        settings.setOnClickListener(v -> {
            hideSidePanel();
            startActivity(new Intent(this, StreamSettings.class));
        });
        autopilot.setOnClickListener(v -> requestStreamingAutopilot(
                currentHost(selectedHostUuid), null, null,
                getString(R.string.console_autopilot_global_target), null));
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
                backgroundStream, autopilot, settings, overrides,
                settingsSectionHeader(R.string.console_options_section_tv), screenSaver,
                settingsSectionHeader(R.string.console_options_section_help), readme);
    }

    private void resetScreenSaverTimer() {
        mainHandler.removeCallbacks(screenSaverTimeout);
        screenSaverLoadGeneration++;
        if (!active || screenSaverSeconds == SCREEN_SAVER_NEVER || screenSaverVisible) return;
        mainHandler.postDelayed(screenSaverTimeout, screenSaverSeconds * 1_000L);
    }

    private void updateScreenSaverWakePolicy() {
        if (active && screenSaverSeconds != SCREEN_SAVER_NEVER) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void showScreenSaver() {
        if (!active || screenSaverSeconds == SCREEN_SAVER_NEVER
                || loadingLayer != null && loadingLayer.getVisibility() == View.VISIBLE
                || sideDialog != null && sideDialog.isShowing()
                || libraryTransitionRunning) {
            resetScreenSaverTimer();
            return;
        }
        String hostUuid = selectedHostUuid;
        if ((hostUuid == null || hostUuid.isEmpty()) && !hosts.isEmpty()) {
            hostUuid = hosts.keySet().iterator().next();
        }
        if (hostUuid == null || hostUuid.isEmpty()) {
            resetScreenSaverTimer();
            return;
        }
        List<PlayniteDashboardItem> eligible = screenSaverItems(allPlayniteItems);
        if (eligible.isEmpty() && hostSelectionVisible) {
            String cachedHostUuid = hostUuid;
            int loadGeneration = screenSaverLoadGeneration;
            executor.execute(() -> {
                PlayniteLibraryCache.Entry cached = playniteLibraryRepository.cached(cachedHostUuid);
                List<PlayniteDashboardItem> cachedItems = new ArrayList<>();
                if (cached != null) {
                    for (PlayniteLibraryGame game : cached.games) {
                        cachedItems.add(new PlayniteDashboardItem(game, null, "",
                                PlayniteDashboardItem.MappingState.NOT_INSTALLED));
                    }
                }
                mainHandler.post(() -> {
                    if (loadGeneration == screenSaverLoadGeneration) {
                        startScreenSaver(cachedHostUuid, screenSaverItems(cachedItems));
                    }
                });
            });
            return;
        }
        startScreenSaver(hostUuid, eligible);
    }

    private List<PlayniteDashboardItem> screenSaverItems(
            List<PlayniteDashboardItem> source) {
        List<PlayniteDashboardItem> eligible = new ArrayList<>();
        for (PlayniteDashboardItem item : source) {
            if (item != null && item.game != null
                    && (!item.game.heroKey.isEmpty() || !item.game.backgroundKey.isEmpty())) {
                eligible.add(item);
            }
        }
        return eligible;
    }

    private void startScreenSaver(String hostUuid, List<PlayniteDashboardItem> eligible) {
        if (!active || screenSaverVisible) return;
        if (eligible.isEmpty()) {
            resetScreenSaverTimer();
            return;
        }
        Collections.shuffle(eligible);
        screenSaverHostUuid = hostUuid;
        screenSaverItems = eligible;
        screenSaverItemIndex = 0;
        screenSaverGeneration++;
        showNextScreenSaverArtwork();
    }

    private void showNextScreenSaverArtwork() {
        mainHandler.removeCallbacks(screenSaverSlide);
        if (!active || screenSaverItems.isEmpty()) return;
        int generation = screenSaverGeneration;
        PlayniteDashboardItem item = screenSaverItems.get(
                screenSaverItemIndex++ % screenSaverItems.size());
        String hostUuid = screenSaverHostUuid;
        ComputerDetails host = hosts.get(hostUuid);
        executor.execute(() -> {
            PlayniteArtworkSpec spec = PlayniteArtworkSpec.forScreenSaver(item.game);
            ArtworkResult result = cachedPlayniteArtwork(hostUuid, item, spec);
            if (result == null && host != null) {
                String address = host.activeAddress != null ? host.activeAddress.address : null;
                GatewayConnection connection =
                        hostGatewayStore.loadForHost(hostUuid, address);
                if (connection != null) {
                    try {
                        result = fetchPlayniteArtwork(connection, hostUuid, item, spec);
                    } catch (IOException ignored) { }
                }
            }
            Bitmap bitmap = result == null ? null : decodeArtwork(result.file, 1920);
            if (bitmap != null && !LoadingArtworkPolicy.canUseAsSplash(
                    bitmap.getWidth(), bitmap.getHeight())) {
                bitmap = null;
            }
            Bitmap selectedBitmap = bitmap;
            mainHandler.post(() -> applyScreenSaverArtwork(
                    generation, item.game.name, selectedBitmap));
        });
    }

    private void applyScreenSaverArtwork(int generation, String title, Bitmap bitmap) {
        if (!active || generation != screenSaverGeneration) return;
        if (bitmap == null) {
            if (screenSaverItemIndex < screenSaverItems.size()) {
                showNextScreenSaverArtwork();
            } else {
                resetScreenSaverTimer();
            }
            return;
        }
        screenSaverVisible = true;
        if (discordDmNotifications != null) {
            discordDmNotifications.setScreenSaverActive(discordDmHostToken, true);
        }
        screenSaverLayer.setVisibility(View.VISIBLE);
        screenSaverLayer.bringToFront();
        screenSaverCaption.setText(getString(R.string.console_screen_saver_caption, title));
        ImageView incoming = screenSaverArtworkNext;
        ImageView outgoing = screenSaverArtwork;
        incoming.animate().cancel();
        outgoing.animate().cancel();
        incoming.setImageBitmap(bitmap);
        incoming.setAlpha(reducedMotion ? 1f : 0f);
        if (reducedMotion) {
            outgoing.setImageDrawable(null);
        } else {
            incoming.animate().alpha(1f).setDuration(850L).start();
            outgoing.animate().alpha(0f).setDuration(850L).withEndAction(
                    () -> outgoing.setImageDrawable(null)).start();
        }
        screenSaverArtwork = incoming;
        screenSaverArtworkNext = outgoing;
        mainHandler.postDelayed(screenSaverSlide, SCREEN_SAVER_SLIDE_MS);
    }

    private void hideScreenSaver() {
        mainHandler.removeCallbacks(screenSaverSlide);
        screenSaverGeneration++;
        screenSaverItems = Collections.emptyList();
        if (screenSaverLayer != null) screenSaverLayer.setVisibility(View.GONE);
        if (screenSaverArtwork != null) screenSaverArtwork.setImageDrawable(null);
        if (screenSaverArtworkNext != null) screenSaverArtworkNext.setImageDrawable(null);
        screenSaverVisible = false;
        if (discordDmNotifications != null) {
            discordDmNotifications.setScreenSaverActive(discordDmHostToken, false);
        }
        resetScreenSaverTimer();
    }

    private static boolean isMeaningfulMotion(MotionEvent event) {
        if (event == null) return false;
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0) {
            return Math.abs(event.getAxisValue(MotionEvent.AXIS_X)) > .25f
                    || Math.abs(event.getAxisValue(MotionEvent.AXIS_Y)) > .25f
                    || Math.abs(event.getAxisValue(MotionEvent.AXIS_HAT_X)) > .25f
                    || Math.abs(event.getAxisValue(MotionEvent.AXIS_HAT_Y)) > .25f
                    || Math.abs(event.getAxisValue(MotionEvent.AXIS_Z)) > .25f
                    || Math.abs(event.getAxisValue(MotionEvent.AXIS_RZ)) > .25f;
        }
        return (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0;
    }

    private String screenSaverTimeoutLabel() {
        if (screenSaverSeconds == SCREEN_SAVER_NEVER) {
            return getString(R.string.console_screen_saver_never);
        }
        return screenSaverSeconds < 60
                ? getString(R.string.console_screen_saver_seconds, screenSaverSeconds)
                : getString(R.string.console_screen_saver_minutes, screenSaverSeconds / 60);
    }

    private void showScreenSaverTimeoutPanel() {
        int[] values = {SCREEN_SAVER_NEVER, 30, 5 * 60, 10 * 60, 30 * 60, 60 * 60};
        List<View> actions = new ArrayList<>();
        for (int value : values) {
            String label = value == SCREEN_SAVER_NEVER
                    ? getString(R.string.console_screen_saver_never)
                    : value < 60
                    ? getString(R.string.console_screen_saver_seconds, value)
                    : getString(R.string.console_screen_saver_minutes, value / 60);
            if (value == screenSaverSeconds) label += getString(R.string.console_selected_suffix);
            TextView choice = panelAction(label);
            choice.setTag("screen_saver_timeout:" + value);
            choice.setOnClickListener(view -> setScreenSaverMinutes(value));
            actions.add(choice);
        }
        showSidePanel(getString(R.string.console_title),
                getString(R.string.console_screen_saver_timeout_title),
                getString(R.string.console_screen_saver_timeout_details),
                actions.toArray(new View[0]));
    }

    private void setScreenSaverMinutes(int seconds) {
        screenSaverSeconds = seconds;
        preferences.edit().putInt(PREF_SCREEN_SAVER_SECONDS, seconds)
                .remove(PREF_SCREEN_SAVER_MINUTES_LEGACY).apply();
        updateScreenSaverWakePolicy();
        resetScreenSaverTimer();
        showOptionsPanel();
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
                params.height = dp(showCarouselGameDescription ? 140 : 36);
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
        setDiscordNotificationPending(false);
        discordSocialPanelController.showHub();
    }

    private void openDiscordDmShortcut() {
        setDiscordNotificationPending(false);
        long peerId = discordDmNotifications.consumeQuickAction(discordDmHostToken);
        if (sideDialog != null && sideDialog.isShowing()) hideSidePanelImmediately();
        if (peerId > 0L) discordSocialPanelController.showDirectMessage(peerId);
        else discordSocialPanelController.showHub();
    }

    private void startCommunityDictation(long recipientId, long directMessageGeneration) {
        if (discordSocialPanelController == null
                || !communityDictationSession.begin(recipientId, directMessageGeneration)) return;
        if (!discordSocialPanelController.collapseCommunityKeyboardForDictation(recipientId,
                directMessageGeneration)) {
            communityDictationSession.finish();
            return;
        }
        Intent recognition = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        if (getPackageManager().resolveActivity(recognition, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            communityDictationSession.finish();
            discordSocialPanelController.restoreCommunityKeyboardAfterDictation(recipientId,
                    directMessageGeneration);
            ConsoleUiFeedback.makeText(this, R.string.discord_dm_dictation_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivityForResult(recognition, REQUEST_COMMUNITY_DICTATION);
        } catch (ActivityNotFoundException | SecurityException error) {
            communityDictationSession.finish();
            discordSocialPanelController.restoreCommunityKeyboardAfterDictation(recipientId,
                    directMessageGeneration);
            ConsoleUiFeedback.makeText(this, R.string.discord_dm_dictation_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_COMMUNITY_DICTATION || !communityDictationSession.isActive()) return;
        long recipientId = communityDictationSession.recipientId();
        long directMessageGeneration = communityDictationSession.directMessageGeneration();
        communityDictationSession.finish();
        if (discordSocialPanelController == null) return;
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String transcript = results == null || results.isEmpty() ? "" : results.get(0);
            if (!TextUtils.isEmpty(transcript)) {
                discordSocialPanelController.insertCommunityDictationResult(recipientId,
                        directMessageGeneration, transcript);
            }
        }
        discordSocialPanelController.restoreCommunityKeyboardAfterDictation(recipientId,
                directMessageGeneration);
    }

    private void showUncertainSessionMessage() {
        TextView close = panelAction(getString(R.string.console_cancel));
        close.setOnClickListener(view -> hideSidePanel());
        showSidePanel(getString(R.string.console_apps_eyebrow),
                getString(R.string.console_session_uncertain_title),
                getString(R.string.console_session_uncertain_details), close);
    }

    private void showSidePanel(String eyebrow, String title, String details, View... actions) {
        showSidePanelInternal(eyebrow, title, details, false, false, actions);
    }

    private void showCommunityPanel(View shell) {
        if (communityPanelHost == null) return;
        boolean alreadyShowing = sideDialog != null && sideDialog.isShowing();
        boolean sameCommunity = alreadyShowing && "discord.community".equals(currentPanelKey)
                && communityPanelHost.getChildCount() == 1
                && communityPanelHost.getChildAt(0) == shell;
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
        }
        if (!sameCommunity) {
            communityPanelHost.removeAllViews();
            ViewParent parent = shell.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(shell);
            communityPanelHost.addView(shell, match());
        }
        currentPanelKey = "discord.community";
        sidePanelScroll.animate().cancel();
        sidePanelScroll.setVisibility(View.GONE);
        sidePanelScroll.setScrollLocked(false);
        communityPanelHost.setVisibility(View.VISIBLE);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0C111A, 0xFE0D121D, 0xFE090C13});
        communityPanelHost.setBackground(background);
        if (!alreadyShowing) {
            sideDialog.show();
            Window window = sideDialog.getWindow();
            if (window != null) window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        communityPanelHost.setAlpha(1f);
        communityPanelHost.setTranslationX(0f);
        if (!sameCommunity) {
            shell.post(() -> {
                if ("discord.community".equals(currentPanelKey)) {
                    discordSocialPanelController.focusCommunityEntry();
                }
            });
        }
    }

    private void showRetainedStreamExitConfirmation() {
        TextView leave = panelAction(getString(R.string.console_leave_keep_stream));
        TextView returnToGame = panelAction(getString(R.string.console_return_to_game));
        TextView close = panelAction(getString(R.string.console_close_game_and_moonwaker));
        close.setTextColor(0xFFFF8A80);
        leave.setOnClickListener(view -> {
            hideSidePanelImmediately();
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
        RetainedStreamSessionCoordinator.parkForBackground(
                currentRetainedStreamSessionId());
        moveTaskToBack(true);
    }

    private void terminateRetainedSessionAndExit() {
        final String expectedStreamSessionId = currentRetainedStreamSessionId();
        if (expectedStreamSessionId.isEmpty()) return;
        AtomicInteger failureMessage = new AtomicInteger(
                R.string.console_terminate_session_failed);
        RetainedStreamSessionCoordinator.TerminationCallback complete = success ->
                mainHandler.post(() -> {
            if (!mayCompleteTermination(expectedStreamSessionId)) return;
            if (!success) {
                hideSidePanel();
                ConsoleUiFeedback.makeText(this, failureMessage.get(),
                        Toast.LENGTH_LONG).show();
                return;
            }
            RetainedStreamSessionCoordinator.clearIfMatches(expectedStreamSessionId);
            SessionResumeManager.clearIfMatches(this, expectedStreamSessionId);
            BackgroundStreamService.resumed(this, expectedStreamSessionId);
            finishAffinity();
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        });
        RetainedStreamSessionCoordinator.TerminationResult result =
                RetainedStreamSessionCoordinator.terminate(
                        expectedStreamSessionId, complete);
        if (result == RetainedStreamSessionCoordinator.TerminationResult.STARTED) {
            showSidePanelBusy(getString(R.string.console_title),
                    getString(R.string.console_close_game_and_moonwaker),
                    getString(R.string.console_closing_game_and_moonwaker));
            return;
        }
        if (result == RetainedStreamSessionCoordinator.TerminationResult.IN_PROGRESS) return;

        ComputerDetails host = hosts.get(!retainedStreamHostId.isEmpty()
                ? retainedStreamHostId : selectedHostUuid);
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        String providerGameId = HostGatewayClient.isPlayniteId(retained.playniteGameId)
                ? retained.playniteGameId : retainedStreamPlayniteGameId;
        if (host == null || managerBinder == null) {
            complete.complete(providerGameId.isEmpty());
            return;
        }
        String terminationProfileId = !retained.profileId.isEmpty()
                ? retained.profileId
                : !retainedStreamProfileId.isEmpty()
                ? retainedStreamProfileId : selectedProfileId(host.uuid);

        showSidePanelBusy(getString(R.string.console_title),
                getString(R.string.console_close_game_and_moonwaker),
                getString(R.string.console_closing_game_and_moonwaker));
        executor.execute(() -> {
            if (!mayCompleteTermination(expectedStreamSessionId)) return;
            boolean stopped = false;
            try {
                stopActiveProviderGame(host, providerGameId,
                        !normalizeId(providerGameId).isEmpty(), terminationProfileId);
                if (!mayCompleteTermination(expectedStreamSessionId)) return;
                stopped = quitSunshineIfRunning(host);
            } catch (IOException | XmlPullParserException error) {
                if (error.getMessage() != null
                        && error.getMessage().contains("game_stop_timeout")) {
                    failureMessage.set(R.string.console_provider_stop_waiting_for_process);
                }
            }
            boolean success = stopped;
            mainHandler.post(() -> {
                if (success) {
                    RetainedStreamSessionCoordinator.clearIfMatches(
                            expectedStreamSessionId);
                }
                refreshSessionState(host.uuid);
                complete.complete(success);
            });
        });
    }

    private String currentRetainedStreamSessionId() {
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        if (!retained.streamSessionId.isEmpty()) return retained.streamSessionId;
        SessionResumeManager.PendingSession pending =
                SessionResumeManager.pendingSession(this);
        return pending == null ? retainedStreamSessionId : pending.streamSessionId;
    }

    private boolean mayCompleteTermination(String expectedStreamSessionId) {
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        if (!retained.streamSessionId.isEmpty()
                && !expectedStreamSessionId.equals(retained.streamSessionId)) return false;
        SessionResumeManager.PendingSession pending =
                SessionResumeManager.pendingSession(this);
        return pending == null || pending.matches(expectedStreamSessionId);
    }
    private void showScrollableDetailsSidePanel(String eyebrow, String title, String details,
                                                View... actions) {
        showSidePanelInternal(eyebrow, title, details, true, false, actions);
    }

    private void showSidePanelInternal(String eyebrow, String title, String details,
                                       boolean scrollableDetails, boolean communityShell,
                                       View... actions) {
        if (communityPanelHost != null && communityPanelHost.getVisibility() == View.VISIBLE) {
            communityPanelHost.removeAllViews();
            communityPanelHost.setVisibility(View.GONE);
            sidePanelScroll.setVisibility(View.VISIBLE);
        }
        if (sidePanelBusyBanner != null) {
            ViewParent parent = sidePanelBusyBanner.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(sidePanelBusyBanner);
            sidePanelBusyBanner = null;
        }
        applySidePanelFrame(communityShell);
        String nextKey = communityShell ? "discord.community" : eyebrow + "\n" + title;
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
        if (communityShell) {
            sidePanel.addView(actions[0], new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
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
        }
        currentPanelKey = nextKey;
        if (!alreadyShowing) {
            sideDialog.show();
            Window window = sideDialog.getWindow();
            if (window != null) window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        if (!alreadyShowing) {
            sidePanelScroll.setTranslationX(communityShell || reducedMotion ? 0 : dp(510));
            if (communityShell && !reducedMotion) {
                sidePanelScroll.setAlpha(0f);
                sidePanelScroll.animate().alpha(1f).setDuration(180).start();
            } else if (!reducedMotion) {
                sidePanelScroll.animate().translationX(0).setDuration(180).start();
            }
        } else {
            sidePanelScroll.animate().cancel();
            sidePanelScroll.setAlpha(1f);
            sidePanelScroll.setScaleX(1f);
            sidePanelScroll.setScaleY(1f);
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

    private void applySidePanelFrame(boolean communityShell) {
        if (sidePanelScroll == null || sidePanel == null) return;
        sidePanelScroll.setScrollLocked(communityShell);
        FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) sidePanelScroll.getLayoutParams();
        if (communityShell) {
            frame.width = ViewGroup.LayoutParams.MATCH_PARENT;
            frame.height = ViewGroup.LayoutParams.MATCH_PARENT;
            frame.gravity = Gravity.CENTER;
            frame.leftMargin = 0;
            frame.topMargin = 0;
            frame.rightMargin = 0;
            frame.bottomMargin = 0;
            GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF0C111A, 0xFE0D121D, 0xFE090C13});
            sidePanelScroll.setBackground(background);
            sidePanel.setPadding(0, 0, 0, 0);
            ViewGroup.LayoutParams content = sidePanel.getLayoutParams();
            content.height = ViewGroup.LayoutParams.MATCH_PARENT;
            sidePanel.setLayoutParams(content);
            return;
        }
        frame.width = dp(510);
        frame.height = ViewGroup.LayoutParams.MATCH_PARENT;
        frame.gravity = Gravity.END;
        frame.leftMargin = 0;
        frame.topMargin = 0;
        frame.rightMargin = 0;
        frame.bottomMargin = 0;
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFC1A2028, 0xFC12171E, 0xFF090C10});
        background.setCornerRadii(new float[]{dp(24), dp(24), 0, 0, 0, 0, dp(24), dp(24)});
        background.setStroke(dp(1), 0x704A6677);
        sidePanelScroll.setBackground(background);
        sidePanel.setPadding(dp(34), dp(26), dp(34), dp(20));
        ViewGroup.LayoutParams content = sidePanel.getLayoutParams();
        content.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        sidePanel.setLayoutParams(content);
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
        Runnable finish = this::completeSidePanelDismissal;
        if (reducedMotion || "discord.community".equals(currentPanelKey)) finish.run();
        else sidePanelScroll.animate().translationX(dp(510)).setDuration(150).withEndAction(finish).start();
    }

    private void hideSidePanelImmediately() {
        sidePanelScroll.animate().cancel();
        completeSidePanelDismissal();
    }

    private void completeSidePanelDismissal() {
        if (discordSocialPanelController != null) discordSocialPanelController.closePanel();
        if (discordPanelController != null) discordPanelController.closePanel();
        if (sideDialog != null) sideDialog.dismiss();
        if (communityPanelHost != null) {
            communityPanelHost.removeAllViews();
            communityPanelHost.setVisibility(View.GONE);
        }
        sidePanelScroll.setVisibility(View.VISIBLE);
        sidePanelScroll.setTranslationX(0);
        sidePanelScroll.setAlpha(1f);
        sidePanelScroll.setScaleX(1f);
        sidePanelScroll.setScaleY(1f);
        applySidePanelFrame(false);
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
        if (streamingAutopilotTask != null) cancelStreamingAutopilot(false);
        if (discordSocialPanelController != null && discordSocialPanelController.prepareForPanelBack()) return;
        if (!panelHistory.isEmpty()) {
            PanelSnapshot snapshot = panelHistory.pop();
            applySidePanelFrame("discord.community".equals(snapshot.key));
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
            if (!isSpatialDiscordTile(item)) {
                if (item.getNextFocusUpId() == View.NO_ID) item.setNextFocusUpId(up.getId());
                if (item.getNextFocusDownId() == View.NO_ID) item.setNextFocusDownId(down.getId());
            }
        }
        if (requestFirst) focusable.get(0).post(focusable.get(0)::requestFocus);
    }

    private void requestFirstModalFocus() {
        List<View> focusable = new ArrayList<>();
        collectFocusable(sidePanel, focusable);
        if (!focusable.isEmpty()) focusable.get(0).post(focusable.get(0)::requestFocus);
    }

    private boolean isSpatialDiscordTile(View view) {
        Object tag = view.getTag();
        return tag instanceof String && ((String) tag).startsWith("discord.");
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
            if (DiscordPanelViews.isTile(action)) DiscordPanelViews.styleTile(action, focused);
            else styleCompactButton(action, focused);
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
        background.setStroke(dp(1), focused
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

    private TextView metadataPill() {
        TextView pill = text("", 10, 0xFFF0F5F8, true);
        pill.setSingleLine(true);
        pill.setGravity(Gravity.CENTER);
        pill.setMinHeight(dp(24));
        pill.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable background = gradient(0xD91A2026, 0xE00E1318, 10);
        background.setStroke(dp(1), 0x667C8B96);
        pill.setBackground(background);
        return pill;
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
            if (!(card.getBackground() instanceof StateListDrawable)) {
                card.setBackground(carouselCardBackground());
            }
            card.setElevation(dp(focused ? 12 : 2));
            boolean homeCarousel = card.getParent() == appRow && !expandedLibraryMode;
            boolean playniteHomeCarousel = homeCarousel
                    && ((String) tag).startsWith("playnite:");
            if (playniteHomeCarousel) resizeHomeCarouselCard(card, focused);
            card.setPivotX(card.getWidth() > 0 ? card.getWidth() / 2f : dp(42));
            card.setPivotY(homeCarousel ? 0f
                    : card.getHeight() > 0 ? card.getHeight() / 2f : dp(75));
            float scale = focused ? playniteHomeCarousel ? 1f
                    : homeCarousel ? 1.10f : 1.035f : 1f;
            float lift = focused && !homeCarousel ? -dp(2) : 0f;
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
            updateCarouselShine(card, focused && playniteHomeCarousel);
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

    private void resizeHomeCarouselCard(View card, boolean focused) {
        ViewGroup.LayoutParams params = card.getLayoutParams();
        if (params == null) return;
        int width = dp(focused
                ? CAROUSEL_FOCUSED_CARD_WIDTH_DP : CAROUSEL_CARD_WIDTH_DP);
        int height = dp(focused
                ? CAROUSEL_FOCUSED_CARD_HEIGHT_DP : CAROUSEL_CARD_HEIGHT_DP);
        if (params.width == width && params.height == height) return;
        params.width = width;
        params.height = height;
        card.setLayoutParams(params);
    }

    private void updateCarouselShine(View card, boolean focused) {
        if (!(card instanceof ViewGroup)) return;
        View shine = findTaggedChild((ViewGroup) card, "playnite.shine");
        if (shine == null) return;
        Object scheduled = card.getTag(R.id.carousel_shine_runnable);
        if (scheduled instanceof Runnable) shine.removeCallbacks((Runnable) scheduled);
        shine.animate().cancel();
        shine.setAlpha(0f);
        card.setTag(R.id.carousel_shine_runnable, null);
        if (!focused || reducedMotion) return;
        Runnable animation = new Runnable() {
            @Override public void run() {
                if (!card.hasFocus() || card.getParent() != appRow
                        || card.getTag(R.id.carousel_shine_runnable) != this) return;
                shine.setAlpha(0f);
                shine.setTranslationX(-dp(32));
                shine.animate().alpha(.36f)
                        .translationX(card.getWidth() + dp(32))
                        .setDuration(850L)
                        .withEndAction(() -> shine.animate().alpha(0f)
                                .setDuration(180L)
                                .withEndAction(() -> {
                                    if (card.hasFocus()
                                            && card.getTag(R.id.carousel_shine_runnable) == this) {
                                        shine.postDelayed(this, 7_000L);
                                    }
                                }).start())
                        .start();
            }
        };
        card.setTag(R.id.carousel_shine_runnable, animation);
        shine.postDelayed(animation, 2_200L);
    }

    private StateListDrawable carouselCardBackground() {
        GradientDrawable focused = gradient(0xF019222B, 0xF00C1116, 12);
        focused.setStroke(dp(1), 0xFFEAF8FF);
        GradientDrawable normal = gradient(0xC012171D, 0xD00A0E12, 12);
        normal.setStroke(dp(1), 0x305F6D76);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        background.addState(new int[0], normal);
        return background;
    }

    private StateListDrawable carouselCardForeground() {
        GradientDrawable focused = gradient(0x00000000, 0x00000000, 12);
        focused.setStroke(dp(1), 0xFFF5FAFF);
        GradientDrawable normal = gradient(0x00000000, 0x00000000, 12);
        StateListDrawable foreground = new StateListDrawable();
        foreground.addState(new int[]{android.R.attr.state_focused}, focused);
        foreground.addState(new int[0], normal);
        return foreground;
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
        background.setStroke(dp(1), focused
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
        boolean discord = button == discordActionButton
                || "global.discord".equals(button.getTag());
        int tint = button == usbMicrophoneActionButton
                ? usbMicrophoneIndicatorColor : 0xFFE8EDF1;
        button.setColorFilter(focused ? 0xFF24313A
                : discord ? 0xFFF4F6F7 : tint);
        if (focused) {
            GradientDrawable halo = new GradientDrawable();
            halo.setShape(GradientDrawable.OVAL);
            halo.setColor(0x12000000);
            halo.setStroke(dp(1), 0xA8D9E9F4);
            GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setColor(0xFFF4F6F7);
            disc.setStroke(dp(1), 0xFF94A8B5);
            android.graphics.drawable.LayerDrawable background =
                    new android.graphics.drawable.LayerDrawable(
                            new Drawable[]{halo, disc});
            background.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));
            button.setBackground(background);
        } else {
            button.setBackgroundColor(Color.TRANSPARENT);
        }
        button.setElevation(dp(focused ? 8 : 0));
        animateScale(button, focused ? 1.10f : 1f);
        if (discord) button.invalidate();
    }

    private void animateScale(View view, float scale) {
        view.animate().cancel();
        if (reducedMotion || suppressInitialCarouselMotion || !view.isLaidOut()) {
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
        View filter = installedFilterButton != null
                && installedFilterButton.getVisibility() == View.VISIBLE
                && installedFilterButton.isEnabled() ? installedFilterButton : null;
        View quick = firstFocusableChild(quickActions);
        View controller = firstFocusableChild(controllerRow);
        View app = firstFocusableChild(appRow);

        if (CONSOLE_UI_V2) {
            wireDebugHomeFocusNavigation(resume, filter,
                    quick, controller, app);
            return;
        }

        View belowOptions = resume != null ? resume
                : quick != null ? quick : controller != null ? controller : app;
        if (belowOptions != null) optionsButton.setNextFocusDownId(belowOptions.getId());

        int down = controller != null ? controller.getId()
                : filter != null ? filter.getId() : app != null ? app.getId() : View.NO_ID;
        if (resume != null) {
            resume.setNextFocusUpId(optionsButton.getId());
            if (quick != null) resume.setNextFocusRightId(quick.getId());
            if (down != View.NO_ID) resume.setNextFocusDownId(down);
        }

        if (quickActions != null) {
            for (int index = 0; index < quickActions.getChildCount(); index++) {
                View child = quickActions.getChildAt(index);
                if (!child.isFocusable()) continue;
                child.setNextFocusUpId(optionsButton.getId());
                if (down != View.NO_ID) child.setNextFocusDownId(down);
                if (index == 0 && resume != null) child.setNextFocusLeftId(resume.getId());
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
        if (suppressInitialCarouselMotion) return;
        tile.post(() -> {
            boolean homeCarousel = CONSOLE_UI_V2 && scroll == appScroll
                    && tile.getParent() == appRow;
            int previousCards = dp(CAROUSEL_PREVIOUS_CARD_COUNT
                    * (CAROUSEL_CARD_WIDTH_DP + CAROUSEL_CARD_GAP_DP));
            int target = Math.max(0, tile.getLeft() - (homeCarousel ? previousCards
                    : (scroll.getWidth() - tile.getWidth()) / 2));
            if (reducedMotion) scroll.scrollTo(target, 0);
            else scroll.smoothScrollTo(target, 0);
        });
    }

    private void smoothCenterOn(ScrollView scroll, View tile) {
        if (scroll == null || tile == null) return;
        if (suppressInitialCarouselMotion) return;
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
        params.rightMargin = dp(16);
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

    private static String playniteGameHistoryKey(String hostUuid, String gameId) {
        return "played_at.game." + hostUuid + "." + gameId;
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

    private void wireDebugHomeFocusNavigation(View resume, View filter,
                                               View quick, View controller,
                                               View app) {
        View belowHeader = app != null ? app
                : filter != null ? filter : controller;
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

        if (resume != null) {
            if (app != null) resume.setNextFocusUpId(app.getId());
            if (filter != null) resume.setNextFocusLeftId(filter.getId());
            resume.setNextFocusRightId(resume.getId());
            if (controller != null) resume.setNextFocusDownId(controller.getId());
        }
        if (filter != null) {
            if (app != null) filter.setNextFocusUpId(app.getId());
            if (resume != null) filter.setNextFocusRightId(resume.getId());
            if (controller != null) filter.setNextFocusDownId(controller.getId());
        }
        if (controller != null) {
            View aboveController = filter != null ? filter
                    : resume != null ? resume : app;
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
                : controller;
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
