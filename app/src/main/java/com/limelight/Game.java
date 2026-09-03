package com.limelight;


import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.capture.InputCaptureManager;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.input.touch.AbsoluteTouchContext;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.binding.input.evdev.EvdevListener;
import com.limelight.binding.input.touch.TouchContext;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.nvstream.av.video.SwitchableVideoDecoderRenderer;
import com.limelight.console.ConsoleStreamTransitionCoordinator;
import com.limelight.console.DiscordOverlayController;
import com.limelight.console.DiscordDmNotificationCoordinator;
import com.limelight.console.DiscordDmToastView;
import com.limelight.console.PlayniteTransitionGateway;
import com.limelight.console.PlayniteIdentityResolutionPolicy;
import com.limelight.discord.DiscordSocialClient;
import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.console.transition.LaunchTransitionController;
import com.limelight.console.transition.LaunchTransitionSnapshot;
import com.limelight.console.transition.LaunchTransitionSpec;
import com.limelight.console.transition.LaunchTransitionState;
import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.nvstream.jni.BackgroundStreamBridge;
import com.limelight.preferences.AppPreferences;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.BrightnessSliderView;
import com.limelight.ui.ConsoleStreamLoadingView;
import com.limelight.ui.GameGestures;
import com.limelight.ui.StreamView;
import com.limelight.ui.overlay.CustomCommand;
import com.limelight.ui.overlay.OverlayMenuView;
import com.limelight.console.SuspendedSessionStore;
import com.limelight.console.ConsoleConfirmDialog;
import com.limelight.console.ConsoleActivity;
import com.limelight.console.StreamHomeActivity;
import com.limelight.stream.BackgroundStreamService;
import com.limelight.stream.BackgroundStreamPreferences;
import com.limelight.stream.RetainedStreamSessionCoordinator;
import com.limelight.stream.StreamingAutopilotCalibration;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.SessionResumeManager;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.Service;
import android.app.UiModeManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Html;
import android.util.Rational;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnGenericMotionListener;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.View.OnTouchListener;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.view.inputmethod.InputMethodManager;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


public class Game extends Activity implements SurfaceHolder.Callback,
        OnGenericMotionListener, OnTouchListener, NvConnectionListener, EvdevListener,
        OnSystemUiVisibilityChangeListener, GameGestures, StreamView.InputCallbacks,
        PerfOverlayListener, UsbDriverService.UsbDriverStateListener, View.OnKeyListener,
        RetainedStreamSessionCoordinator.Controller {
    private int lastButtonState = 0;
    // An overlay may hide synchronously from its first key-down. Keep that physical
    // device/key sequence out of the stream until its matching key-up arrives.
    private final OverlayKeySequenceLatch overlayOwnedKeySequences = new OverlayKeySequenceLatch();

    // Only 2 touches are supported
    private final TouchContext[] touchContextMap = new TouchContext[2];
    private long threeFingerDownTime = 0;

    private static final int REFERENCE_HORIZ_RES = 1280;
    private static final int REFERENCE_VERT_RES = 720;

    private static final int STYLUS_DOWN_DEAD_ZONE_DELAY = 100;
    private static final int STYLUS_DOWN_DEAD_ZONE_RADIUS = 20;

    private static final int STYLUS_UP_DEAD_ZONE_DELAY = 150;
    private static final int STYLUS_UP_DEAD_ZONE_RADIUS = 50;

    private static final int THREE_FINGER_TAP_THRESHOLD = 300;
    private static final long AUTOMATIC_REVEAL_DELAY_MS = 1200L;
    private static final long WHOLE_SESSION_QUIT_TIMEOUT_MS = 20_000L;
    private static final long AUTOPILOT_RESULT_ACTION_DELAY_MS = 2_000L;

    private ControllerHandler controllerHandler;
    private KeyboardTranslator keyboardTranslator;
    private VirtualController virtualController;

    private PreferenceConfiguration prefConfig;
    private SharedPreferences tombstonePrefs;

    private NvConnection conn;
    private AndroidAudioRenderer streamAudioRenderer;
    private SwitchableVideoDecoderRenderer switchableVideoRenderer;
    private SpinnerDialog spinner;
    private ConsoleStreamLoadingView consoleLoadingView;
    private LaunchTransitionController transitionController;
    private LaunchTransitionSpec transitionSpec;
    private long launchStartedAtMillis;
    private boolean streamEverRevealed;
    private String streamSessionId;
    private String sourceSuspendId;
    private String sourceSuspendPlayniteGameId;
    private ConsoleStreamTransitionCoordinator transitionCoordinator;
    private boolean lastTransitionOverlayVisible = true;
    private boolean lastTransitionRevealAuthorized;
    private final AtomicReference<String> armedVideoFrameTransitionId =
            new AtomicReference<>("");
    private final Handler transitionUiHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingAutomaticReveal;
    private boolean manualRevealRequested;
    private boolean transitionCancelInFlight;
    private boolean providerStopInFlight;
    private boolean providerStopConfirmed;
    private boolean providerEndGameInFlight;
    private boolean retainedDashboardReturnInFlight;
    private String providerStartRejectedTransitionId = "";
    private volatile boolean wholeSessionTerminationInFlight;
    private final AtomicBoolean freshOwnedFailureCleanupInFlight = new AtomicBoolean();
    private RetainedSwitch retainedSwitch;
    private boolean displayedFailureDialog = false;
    private boolean connecting = false;
    private boolean connected = false;
    private boolean userInitiatedDisconnect = false;
    private boolean streamHomeVisible = false;
    private boolean retainedRestoreAwaitingFrame;
    private Runnable retainedRestoreWatchdog;
    private boolean backgroundStreamParked = false;
    private boolean autoEnterPip = false;
    private boolean surfaceCreated = false;
    private boolean attemptedConnection = false;
    private long autoWarmUpAttempt;
    private boolean autoWarmUpConvertedToGame;
    private boolean autoWarmUpRetained;
    private boolean parkWarmUpWhenConnected;
    private final Object autoWarmUpGateLock = new Object();
    private final AtomicBoolean autoWarmUpHomeFrameAccepted = new AtomicBoolean();
    private final AtomicBoolean autoWarmUpTransportStopRequested = new AtomicBoolean();
    private boolean bitrateReconnectPending = false;
    private int runtimeBitrateKbps;
    private volatile StreamingAutopilotCalibration autopilotCalibration;
    private volatile boolean autopilotCalibrationArmed;
    private boolean autopilotGameReadySeen;
    private String autopilotCalibrationAppKey = "";
    private int autopilotWidth;
    private int autopilotHeight;
    private int autopilotFps;
    private int autopilotBitrateKbps;
    private boolean autopilotProgressOverlayActive;
    private int autopilotPreviousPerfVisibility;
    private CharSequence autopilotPreviousPerfText;
    private android.app.Dialog autopilotCalibrationDialog;
    private int suppressPipRefCount = 0;
    private String pcName;
    private String appName;
    private NvApp app;
    private float desiredRefreshRate;

    private InputCaptureProvider inputCaptureProvider;
    private int modifierFlags = 0;
    private boolean grabbedInput = true;
    private boolean cursorVisible = false;
    private boolean waitingForAllModifiersUp = false;
    private int specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private StreamView streamView;
    private long lastAbsTouchUpTime = 0;
    private long lastAbsTouchDownTime = 0;
    private float lastAbsTouchUpX, lastAbsTouchUpY;
    private float lastAbsTouchDownX, lastAbsTouchDownY;

    private boolean isHidingOverlays;
    private TextView notificationOverlayView;
    private int requestedNotificationOverlayVisibility = View.GONE;
    private TextView performanceOverlayView;
    private BrightnessSliderView brightnessSliderView;
    private OverlayMenuView overlayMenuView;
    private DiscordOverlayController discordOverlayController;
    private DiscordDmToastView discordDmToastView;
    private DiscordDmNotificationCoordinator discordDmNotifications;
    private DiscordDmNotificationCoordinator.HostToken discordDmHostToken;
    private boolean isImeVisible = false;
    private boolean isAndroidTV = false;

    private MediaCodecDecoderRenderer decoderRenderer;
    private boolean decoderMeteredNetwork;
    private boolean decoderHdrRequested;
    private String decoderGlRenderer;
    private boolean reportedCrash;

    private WifiManager.WifiLock highPerfWifiLock;
    private WifiManager.WifiLock lowLatencyWifiLock;

    private boolean connectedToUsbDriverService = false;
    private ServiceConnection usbDriverServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            UsbDriverService.UsbDriverBinder binder = (UsbDriverService.UsbDriverBinder) iBinder;
            binder.setListener(controllerHandler);
            binder.setStateListener(Game.this);
            binder.start();
            connectedToUsbDriverService = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            connectedToUsbDriverService = false;
        }
    };

    private final android.content.BroadcastReceiver quitAppReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_QUIT_APP.equals(intent.getAction())) {
                closeStreamWithPrivacy(true);
            } else if ((BackgroundStreamService.ACTION_EXPIRED.equals(intent.getAction())
                    || BackgroundStreamService.ACTION_END_REQUESTED.equals(intent.getAction()))
                    && streamSessionId.equals(intent.getStringExtra(
                    BackgroundStreamService.EXTRA_STREAM_SESSION_ID))) {
                endExpiredBackgroundStream();
            }
        }
    };

    public static final String EXTRA_HOST = "Host";
    public static final String EXTRA_PORT = "Port";
    public static final String EXTRA_HTTPS_PORT = "HttpsPort";
    public static final String EXTRA_APP_NAME = "AppName";
    public static final String EXTRA_APP_ID = "AppId";
    public static final String EXTRA_UNIQUEID = "UniqueId";
    public static final String EXTRA_PC_UUID = "UUID";
    public static final String EXTRA_PC_NAME = "PcName";
    public static final String EXTRA_APP_HDR = "HDR";
    public static final String EXTRA_SERVER_CERT = "ServerCert";
    public static final String EXTRA_QUICK_LAUNCH_APP_KEY = "QuickLaunchAppKey";
    public static final String EXTRA_APPLY_PREFERENCE_OVERRIDES = "ApplyPreferenceOverrides";
    public static final String EXTRA_AUTOPILOT_CALIBRATION_APP_KEY =
            "AutopilotCalibrationAppKey";
    public static final String EXTRA_RUNTIME_WIDTH = "RuntimeWidth";
    public static final String EXTRA_RUNTIME_HEIGHT = "RuntimeHeight";
    public static final String EXTRA_RUNTIME_FPS = "RuntimeFps";
    public static final String EXTRA_RUNTIME_BITRATE_KBPS = "RuntimeBitrateKbps";
    public static final String EXTRA_STREAM_SESSION_ID = "StreamSessionId";
    public static final String EXTRA_STREAM_TARGET_NAME = "StreamTargetName";
    public static final String EXTRA_NEUTRAL_STREAM_TARGET = "NeutralStreamTarget";
    public static final String EXTRA_FRESH_SUNSHINE_SESSION_OWNER =
            "FreshSunshineSessionOwner";
    public static final String EXTRA_SOURCE_SUSPEND_ID = "SourceSuspendId";
    public static final String EXTRA_SOURCE_SUSPEND_PLAYNITE_GAME_ID =
            "SourceSuspendPlayniteGameId";
    public static final String EXTRA_CONSOLE_LOADING = "ConsoleLoading";
    public static final String EXTRA_CONSOLE_LOADING_MESSAGE = "ConsoleLoadingMessage";
    public static final String EXTRA_CONSOLE_LOADING_EPOCH = "ConsoleLoadingEpoch";
    public static final String EXTRA_CONSOLE_LOADING_STEP = "ConsoleLoadingStep";
    public static final String EXTRA_CONSOLE_LOADING_STATUS = "ConsoleLoadingStatus";
    public static final String EXTRA_CONSOLE_LOADING_ARTWORK = "ConsoleLoadingArtwork";
    public static final String EXTRA_CONSOLE_REDUCED_MOTION = "ConsoleReducedMotion";
    public static final String EXTRA_TRANSITION_ID = "ConsoleTransitionId";
    public static final String EXTRA_TRANSITION_TYPE = "ConsoleTransitionType";
    public static final String EXTRA_TRANSITION_HOST_ID = "ConsoleTransitionHostId";
    public static final String EXTRA_TRANSITION_PLAYNITE_GAME_ID =
            "ConsoleTransitionPlayniteGameId";
    public static final String EXTRA_TRANSITION_CREATED_AT = "ConsoleTransitionCreatedAt";
    public static final String EXTRA_TRANSITION_START_BEFORE_STREAM =
            "ConsoleTransitionStartBeforeStream";
    public static final String EXTRA_AUTO_WARM_UP_ATTEMPT = "AutoWarmUpAttempt";
    private static final String STATE_TRANSITION_REVEALED = "ConsoleTransitionRevealed";
    public static final String ACTION_QUIT_APP = "com.limelight.QUIT_STREAMING_APP";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        streamSessionId = normalizeOpaqueId(
                getIntent().getStringExtra(EXTRA_STREAM_SESSION_ID));
        if (streamSessionId.isEmpty()) streamSessionId = UUID.randomUUID().toString();
        getIntent().putExtra(EXTRA_STREAM_SESSION_ID, streamSessionId);
        sourceSuspendId = normalizeOpaqueId(
                getIntent().getStringExtra(EXTRA_SOURCE_SUSPEND_ID));
        sourceSuspendPlayniteGameId = normalizeGameId(
                getIntent().getStringExtra(EXTRA_SOURCE_SUSPEND_PLAYNITE_GAME_ID));
        autoWarmUpAttempt = getIntent().getLongExtra(EXTRA_AUTO_WARM_UP_ATTEMPT, 0L);

        UiHelper.setLocale(this);

        // We don't want a title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Full-screen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // If we're going to use immersive mode, we want to have
        // the entire screen
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);

        // Listen for UI visibility events
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(this);

        // Detect keyboard visibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        isImeVisible = insets.isVisible(WindowInsets.Type.ime());
                    } else {
                        int bottomInset = insets.getSystemWindowInsetBottom();
                        float density = getResources().getDisplayMetrics().density;
                        isImeVisible = bottomInset > (100 * density);
                    }
                    return view.onApplyWindowInsets(insets);
                }
            });
        }

        // Detect Android TV
        UiModeManager uiModeManager = (UiModeManager) this.getBaseContext().getSystemService(Context.UI_MODE_SERVICE);
        isAndroidTV = uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        // Change volume button behavior
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Inflate the content
        setContentView(R.layout.activity_game);

        if (getIntent().getBooleanExtra(EXTRA_CONSOLE_LOADING, false)) {
            transitionSpec = readTransitionSpec();
            boolean restoredRevealed = savedInstanceState != null
                    && savedInstanceState.getBoolean(STATE_TRANSITION_REVEALED, false);
            if (transitionSpec != null) {
                if (hasAutoWarmUpTransportIdentity()) {
                    LaunchTransitionType warmUpType = transitionSpec.playniteGameId.isEmpty()
                            ? LaunchTransitionType.GENERIC
                            : LaunchTransitionType.GAME_CONNECTION;
                    transitionSpec = new LaunchTransitionSpec(
                            transitionSpec.id, transitionSpec.hostId, warmUpType,
                            transitionSpec.sunshineAppId, transitionSpec.playniteGameId,
                            transitionSpec.createdAtMillis,
                            transitionSpec.startProviderBeforeStream);
                    getIntent().putExtra(EXTRA_TRANSITION_TYPE, warmUpType.name());
                }
                LaunchTransitionType recoveredType = recoveredTransitionType(
                        transitionSpec.type, restoredRevealed);
                if (recoveredType != transitionSpec.type) {
                    transitionSpec = new LaunchTransitionSpec(
                            transitionSpec.id, transitionSpec.hostId, recoveredType,
                            transitionSpec.sunshineAppId, transitionSpec.playniteGameId,
                            transitionSpec.createdAtMillis,
                            transitionSpec.startProviderBeforeStream);
                    getIntent().putExtra(EXTRA_TRANSITION_TYPE, recoveredType.name());
                }
                streamEverRevealed = restoredRevealed;
            }
            launchStartedAtMillis = getIntent().getLongExtra(
                    EXTRA_CONSOLE_LOADING_EPOCH, SystemClock.uptimeMillis());
            consoleLoadingView = new ConsoleStreamLoadingView(
                    this,
                    getIntent().getStringExtra(EXTRA_APP_NAME),
                    getIntent().getStringExtra(EXTRA_CONSOLE_LOADING_MESSAGE),
                    launchStartedAtMillis,
                    getIntent().getBooleanExtra(EXTRA_CONSOLE_REDUCED_MOTION, false));
            if (hasAutoWarmUpTransportIdentity()) {
                consoleLoadingView.showNeutralWarmUpAppearance();
            } else {
                consoleLoadingView.setSplashArtwork(
                        getIntent().getStringExtra(EXTRA_CONSOLE_LOADING_ARTWORK));
            }
            ((FrameLayout) findViewById(android.R.id.content)).addView(consoleLoadingView,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            consoleLoadingView.bringToFront();
            if (transitionSpec != null) {
                consoleLoadingView.configureForPlaynite(
                        transitionSpec.type == LaunchTransitionType.PLAYNITE);
                transitionController = new LaunchTransitionController(
                        this::onTransitionChanged);
                transitionController.begin(transitionSpec);
                transitionCoordinator = createTransitionCoordinator();
                consoleLoadingView.setActions(new ConsoleStreamLoadingView.Actions() {
                    @Override public void onCancel() {
                        cancelTransition();
                    }

                    @Override public void onRetry() {
                        retryTransition();
                    }

                    @Override public void onShowStreamAnyway() {
                        if (isOwnedHiddenAutoWarmUp()) return;
                        manualRevealRequested = true;
                        LaunchTransitionSnapshot snapshot = transitionController.snapshot();
                        LimeLog.info("Manual stream reveal requested transition="
                                + transitionSpec.id + " game="
                                + transitionSpec.playniteGameId + " reason="
                                + snapshot.detail);
                        transitionController.showStreamAnyway(transitionSpec.id);
                    }
                });
            }
            consoleLoadingView.setStep(
                    getIntent().getIntExtra(EXTRA_CONSOLE_LOADING_STEP, 2),
                    getIntent().getStringExtra(EXTRA_CONSOLE_LOADING_STATUS));
        } else {
            // Preserve stock Moonlight presentation for classic launches.
            spinner = SpinnerDialog.displayDialog(this,
                    getResources().getString(R.string.conn_establishing_title),
                    getResources().getString(R.string.conn_establishing_msg), true);
        }

        // Get the app ID
        int appId = Game.this.getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);

        // Get the computer ID
        String computerId = Game.this.getIntent().getStringExtra(EXTRA_PC_UUID);

        // Read the stream preferences (per-app if configured, otherwise global)
        String appKey = computerId + ":" + appId;
        String quickLaunchAppKey = Game.this.getIntent().getStringExtra(EXTRA_QUICK_LAUNCH_APP_KEY);
        boolean applyPreferenceOverrides = Game.this.getIntent().getBooleanExtra(EXTRA_APPLY_PREFERENCE_OVERRIDES, true);
        prefConfig = AppPreferences.getEffectivePreferences(this, appKey, quickLaunchAppKey, applyPreferenceOverrides);
        int requestedRuntimeBitrate = Game.this.getIntent().getIntExtra(EXTRA_RUNTIME_BITRATE_KBPS, 0);
        if (!initializeStreamingAutopilotCalibration(requestedRuntimeBitrate)
                && requestedRuntimeBitrate > 0) {
            prefConfig.bitrate = Math.max(1000, Math.min(150000, requestedRuntimeBitrate));
        }
        runtimeBitrateKbps = prefConfig.bitrate;
        tombstonePrefs = Game.this.getSharedPreferences("DecoderTombstone", 0);

        // Enter landscape unless we're on a square screen
        setPreferredOrientationForCurrentDisplay();

        if (prefConfig.stretchVideo || shouldIgnoreInsetsForResolution(prefConfig.width, prefConfig.height)) {
            // Allow the activity to layout under notches if the fill-screen option
            // was turned on by the user or it's a full-screen native resolution
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }

        // Listen for non-touch events on the game surface
        streamView = findViewById(R.id.surfaceView);
        streamView.setZOrderOnTop(false);
        streamView.setZOrderMediaOverlay(false);
        streamView.setOnGenericMotionListener(this);
        streamView.setOnKeyListener(this);
        streamView.setInputCallbacks(this);

        // Listen for touch events on the background touch view to enable trackpad mode
        // to work on areas outside of the StreamView itself. We use a separate View
        // for this rather than just handling it at the Activity level, because that
        // allows proper touch splitting, which the OSC relies upon.
        View backgroundTouchView = findViewById(R.id.backgroundTouchView);
        backgroundTouchView.setOnTouchListener(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Request unbuffered input event dispatching for all input classes we handle here.
            // Without this, input events are buffered to be delivered in lock-step with VBlank,
            // artificially increasing input latency while streaming.
            streamView.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON | // Keyboards
                    InputDevice.SOURCE_CLASS_JOYSTICK | // Gamepads
                    InputDevice.SOURCE_CLASS_POINTER | // Touchscreens and mice (w/o pointer capture)
                    InputDevice.SOURCE_CLASS_POSITION | // Touchpads
                    InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            );
            backgroundTouchView.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON | // Keyboards
                    InputDevice.SOURCE_CLASS_JOYSTICK | // Gamepads
                    InputDevice.SOURCE_CLASS_POINTER | // Touchscreens and mice (w/o pointer capture)
                    InputDevice.SOURCE_CLASS_POSITION | // Touchpads
                    InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            );
        }

        notificationOverlayView = findViewById(R.id.notificationOverlay);

        performanceOverlayView = findViewById(R.id.performanceOverlay);

        // Initialize brightness slider
        brightnessSliderView = new BrightnessSliderView(this);

        // Initialize overlay menu view (setup will be done after controllerHandler is initialized)
        overlayMenuView = findViewById(R.id.overlayMenuView);
        overlayMenuView.setFlipFaceButtons(prefConfig.flipFaceButtons);
        overlayMenuView.setBitrateControlEnabled(prefConfig.runtimeBitrateControl);
        overlayMenuView.setInstallationConfirmationAvailable(
                transitionCoordinator != null
                        && transitionCoordinator.isInstallationConfirmationStream());
        discordDmToastView = findViewById(R.id.discordDmToastView);
        if (discordDmToastView == null) {
            discordDmToastView = new DiscordDmToastView(this);
            int margin = Math.round(18 * getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams toastParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            toastParams.setMargins(margin, margin, margin, 0);
            FrameLayout gameRoot = (FrameLayout) overlayMenuView.getParent();
            int overlayIndex = gameRoot.indexOfChild(overlayMenuView);
            gameRoot.addView(discordDmToastView,
                    overlayIndex < 0 ? gameRoot.getChildCount() : overlayIndex, toastParams);
        }
        discordDmToastView.setReducedMotion(
                getIntent().getBooleanExtra(EXTRA_CONSOLE_REDUCED_MOTION, false));
        discordDmToastView.setShortcutTrigger(prefConfig.overlayTriggerButton);
        discordDmNotifications = DiscordDmNotificationCoordinator.getInstance();
        discordDmNotifications.initialize(this);
        discordDmHostToken = discordDmNotifications.registerHost(discordDmToastView);
        try {
            DiscordSocialClient.attach(this);
        } catch (Exception | LinkageError ignored) {
            // Social is optional; a failed SDK attach must not affect the stream.
        }
        discordOverlayController = new DiscordOverlayController(this, overlayMenuView,
                (LinearLayout) findViewById(R.id.discordDockView), prefConfig,
                getIntent().getStringExtra(EXTRA_PC_UUID),
                getIntent().getStringExtra(EXTRA_HOST));
        if (discordDmNotifications != null) {
            discordOverlayController.setVisiblePeerListener(peerId ->
                    discordDmNotifications.setVisiblePeer(discordDmHostToken, peerId));
        }

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            streamView.setOnCapturedPointerListener(new View.OnCapturedPointerListener() {
                @Override
                public boolean onCapturedPointer(View view, MotionEvent motionEvent) {
                    return handleMotionEvent(view, motionEvent);
                }
            });
        }

        // Warn the user if they're on a metered connection
        ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr.isActiveNetworkMetered()) {
            displayTransientMessage(getResources().getString(R.string.conn_metered));
        }

        // Make sure Wi-Fi is fully powered up
        WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            highPerfWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Moonlight High Perf Lock");
            highPerfWifiLock.setReferenceCounted(false);
            highPerfWifiLock.acquire();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lowLatencyWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Moonlight Low Latency Lock");
                lowLatencyWifiLock.setReferenceCounted(false);
                lowLatencyWifiLock.acquire();
            }
        } catch (SecurityException e) {
            // Some Samsung Galaxy S10+/S10e devices throw a SecurityException from
            // WifiLock.acquire() even though we have android.permission.WAKE_LOCK in our manifest.
            e.printStackTrace();
        }

        appName = Game.this.getIntent().getStringExtra(EXTRA_APP_NAME);
        pcName = Game.this.getIntent().getStringExtra(EXTRA_PC_NAME);

        String host = Game.this.getIntent().getStringExtra(EXTRA_HOST);
        int port = Game.this.getIntent().getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT);
        int httpsPort = Game.this.getIntent().getIntExtra(EXTRA_HTTPS_PORT, 0); // 0 is treated as unknown
        String uniqueId = Game.this.getIntent().getStringExtra(EXTRA_UNIQUEID);
        boolean appSupportsHdr = Game.this.getIntent().getBooleanExtra(EXTRA_APP_HDR, false);
        byte[] derCertData = Game.this.getIntent().getByteArrayExtra(EXTRA_SERVER_CERT);

        app = new NvApp(appName != null ? appName : "app", appId, appSupportsHdr);

        X509Certificate serverCert = null;
        try {
            if (derCertData != null) {
                serverCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(derCertData));
            }
        } catch (CertificateException e) {
            e.printStackTrace();
        }

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish();
            return;
        }

        // Initialize the MediaCodec helper before creating the decoder
        GlPreferences glPrefs = GlPreferences.readPreferences(this);
        MediaCodecHelper.initialize(this, glPrefs.glRenderer);

        // Check if the user has enabled HDR
        boolean willStreamHdr = false;
        if (prefConfig.enableHdr) {
            // Start our HDR checklist
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Display display = getWindowManager().getDefaultDisplay();
                Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();

                // We must now ensure our display is compatible with HDR10
                if (hdrCaps != null) {
                    // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                    for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            willStreamHdr = true;
                            break;
                        }
                    }
                }

                if (!willStreamHdr) {
                    // Nope, no HDR for us :(
                    Toast.makeText(this, "Display does not support HDR10", Toast.LENGTH_LONG).show();
                }
            }
            else {
                Toast.makeText(this, "HDR requires Android 7.0 or later", Toast.LENGTH_LONG).show();
            }
        }

        // Check if the user has enabled performance stats overlay
        if (prefConfig.enablePerfOverlay) {
            performanceOverlayView.setVisibility(View.VISIBLE);
        }

        decoderMeteredNetwork = connMgr.isActiveNetworkMetered();
        decoderHdrRequested = willStreamHdr;
        decoderGlRenderer = glPrefs.glRenderer;
        decoderRenderer = createDecoderRenderer();
        switchableVideoRenderer = new SwitchableVideoDecoderRenderer(decoderRenderer);

        // Don't stream HDR if the decoder can't support it
        if (willStreamHdr && !decoderRenderer.isHevcMain10Hdr10Supported() && !decoderRenderer.isAv1Main10Supported()) {
            willStreamHdr = false;
            Toast.makeText(this, "Decoder does not support HDR10 profile", Toast.LENGTH_LONG).show();
        }

        // Display a message to the user if HEVC was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC && !decoderRenderer.isHevcSupported()) {
            Toast.makeText(this, "No HEVC decoder found", Toast.LENGTH_LONG).show();
        }

        // Display a message to the user if AV1 was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 && !decoderRenderer.isAv1Supported()) {
            Toast.makeText(this, "No AV1 decoder found", Toast.LENGTH_LONG).show();
        }

        // H.264 is always supported
        int supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264;
        if (decoderRenderer.isHevcSupported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265;
            if (willStreamHdr && decoderRenderer.isHevcMain10Hdr10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265_MAIN10;
            }
        }
        if (decoderRenderer.isAv1Supported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
            if (willStreamHdr && decoderRenderer.isAv1Main10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN10;
            }
        }

        int gamepadMask = ControllerHandler.getAttachedControllerMask(this);
        if (!prefConfig.multiController) {
            // Always set gamepad 1 present for when multi-controller is
            // disabled for games that don't properly support detection
            // of gamepads removed and replugged at runtime.
            gamepadMask = 1;
        }
        if (prefConfig.onscreenController) {
            // If we're using OSC, always set at least gamepad 1.
            gamepadMask |= 1;
        }

        // Set to the optimal mode for streaming
        float displayRefreshRate = prepareDisplayForRendering();
        LimeLog.info("Display refresh rate: "+displayRefreshRate);

        // If the user requested frame pacing using a capped FPS, we will need to change our
        // desired FPS setting here in accordance with the active display refresh rate.
        int roundedRefreshRate = Math.round(displayRefreshRate);
        int chosenFrameRate = prefConfig.fps;
        if (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (prefConfig.fps >= roundedRefreshRate) {
                if (prefConfig.fps > roundedRefreshRate + 3) {
                    // Use frame drops when rendering above the screen frame rate
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    LimeLog.info("Using drop mode for FPS > Hz");
                } else if (roundedRefreshRate <= 49) {
                    // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    LimeLog.info("Bogus refresh rate: " + roundedRefreshRate);
                }
                else {
                    chosenFrameRate = roundedRefreshRate - 1;
                    LimeLog.info("Adjusting FPS target for screen to " + chosenFrameRate);
                }
            }
        }

        // Use the "actual display refresh rate" preference for the X100 refresh rate
        //int refreshRateX100 = (int)(displayRefreshRate * 100);
        int refreshRateX100 = 0;
        if (prefConfig.actualDisplayRefreshRate != null && !prefConfig.actualDisplayRefreshRate.isBlank()) {
            float actualDisplayRefreshRateFloat = Float.parseFloat(prefConfig.actualDisplayRefreshRate);
            if (actualDisplayRefreshRateFloat > 0) {
                refreshRateX100 = (int)(actualDisplayRefreshRateFloat * 100);
            }
        }

        var configBuilder = new StreamConfiguration.Builder()
                .setResolution(prefConfig.width, prefConfig.height)
                .setLaunchRefreshRate(prefConfig.fps)
                .setRefreshRate(chosenFrameRate)
                .setApp(app)
                .setEnableUltraLowLatency(prefConfig.enableUltraLowLatency)
                .setBitrate(prefConfig.bitrate)
                .setEnableSops(prefConfig.enableSops)
                .enableLocalAudioPlayback(prefConfig.playHostAudio)
                .setMaxPacketSize(1392)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO) // NvConnection will perform LAN and VPN detection
                .setSupportedVideoFormats(supportedVideoFormats)
                .setAttachedGamepadMask(gamepadMask)
                .setAudioConfiguration(prefConfig.audioConfiguration)
                .setColorSpace(decoderRenderer.getPreferredColorSpace())
                .setColorRange(decoderRenderer.getPreferredColorRange())
                .setPersistGamepadsAfterDisconnect(!prefConfig.multiController);

        if (refreshRateX100 > 0) {
            configBuilder.setClientRefreshRateX100(refreshRateX100);
        }

        StreamConfiguration config = configBuilder.build();

        // Initialize the connection
        conn = new NvConnection(getApplicationContext(),
                new ComputerDetails.AddressTuple(host, port),
                httpsPort, uniqueId, config,
                PlatformBinding.getCryptoProvider(this), serverCert);
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);
        updateGuidePolicyTransition();
        if (transitionController != null) {
            controllerHandler.setInputSuppressed(true);
            transitionController.inputPipelineReady(transitionSpec.id);
        }
        keyboardTranslator = new KeyboardTranslator();

        // Setup overlay menu now that controllerHandler is initialized
        setupOverlayMenu();

        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(keyboardTranslator, null);

        // Initialize touch contexts
        for (int i = 0; i < touchContextMap.length; i++) {
            if (!prefConfig.touchscreenTrackpad) {
                touchContextMap[i] = new AbsoluteTouchContext(conn, i, streamView);
            }
            else {
                touchContextMap[i] = new RelativeTouchContext(conn, i,
                        REFERENCE_HORIZ_RES, REFERENCE_VERT_RES,
                        streamView, prefConfig);
            }
        }

        if (prefConfig.onscreenController) {
            // create virtual onscreen controller
            virtualController = new VirtualController(controllerHandler,
                    (FrameLayout)streamView.getParent(),
                    this);
            virtualController.refreshLayout();
            virtualController.show();

            // Register virtual controller to receive physical gamepad input events
            controllerHandler.setControllerInputListener(virtualController);
        }

        if (prefConfig.usbDriver) {
            // Start the USB driver
            bindService(new Intent(this, UsbDriverService.class),
                    usbDriverServiceConnection, Service.BIND_AUTO_CREATE);
        }

        if (!decoderRenderer.isAvcSupported()) {
            if (spinner != null) {
                spinner.dismiss();
                spinner = null;
            }

            // If we can't find an AVC decoder, we can't proceed
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
                    "This device or ROM doesn't support hardware accelerated H.264 playback.", true);
            return;
        }

        // Register broadcast receiver to allow external control
        android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_QUIT_APP);
        filter.addAction(BackgroundStreamService.ACTION_EXPIRED);
        filter.addAction(BackgroundStreamService.ACTION_END_REQUESTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(quitAppReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(quitAppReceiver, filter);
        }

        // The connection is armed only after Android has submitted an opaque
        // overlay frame. This preserves show -> frame -> operation ordering.
        if (transitionController != null && consoleLoadingView != null) {
            // Register immediately so we cannot miss an already-created Surface.
            // surfaceChanged() still gates conn.start() on operationAuthorized.
            streamView.getHolder().addCallback(this);
            if (hasAutoWarmUpTransportIdentity()) {
                if (!beginAutoWarmUpPreparing()) {
                    finish();
                    return;
                }
                openPreparingConsoleHome();
            } else {
                consoleLoadingView.doAfterNextFrame(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    transitionController.overlayRendered(transitionSpec.id);
                    logLaunchMilestone("opaque-overlay-rendered");
                    transitionCoordinator.start();
                    startConnectionIfReady(streamView.getHolder());
                });
            }
        } else {
            streamView.getHolder().addCallback(this);
        }
    }

    private MediaCodecDecoderRenderer createDecoderRenderer() {
        MediaCodecDecoderRenderer renderer = new MediaCodecDecoderRenderer(
                this,
                prefConfig,
                new CrashListener() {
                    @Override
                    public void notifyCrash(Exception e) {
                        tombstonePrefs.edit().putInt("CrashCount",
                                tombstonePrefs.getInt("CrashCount", 0) + 1).commit();
                        reportedCrash = true;
                    }
                },
                tombstonePrefs.getInt("CrashCount", 0),
                decoderMeteredNetwork,
                decoderHdrRequested,
                decoderGlRenderer,
                this,
                this::onFirstVideoFrameRendered);
        if (autopilotCalibration != null) {
            renderer.setVideoStatsListener(this::onStreamingAutopilotVideoStats);
        }
        return renderer;
    }

    private boolean initializeStreamingAutopilotCalibration(int runtimeBitrateKbps) {
        Intent intent = getIntent();
        String appKey = intent.getStringExtra(EXTRA_AUTOPILOT_CALIBRATION_APP_KEY);
        if (appKey == null || appKey.trim().isEmpty()) return false;

        int width = intent.getIntExtra(EXTRA_RUNTIME_WIDTH, 0);
        int height = intent.getIntExtra(EXTRA_RUNTIME_HEIGHT, 0);
        int fps = intent.getIntExtra(EXTRA_RUNTIME_FPS, 0);
        if (width <= 0 || height <= 0 || fps <= 0 || runtimeBitrateKbps < 500) {
            LimeLog.warning("Ignoring invalid Streaming Autopilot calibration settings");
            return false;
        }

        autopilotCalibrationAppKey = appKey.trim();
        autopilotWidth = width;
        autopilotHeight = height;
        autopilotFps = fps;
        autopilotBitrateKbps = runtimeBitrateKbps;
        prefConfig.width = width;
        prefConfig.height = height;
        prefConfig.fps = fps;
        prefConfig.bitrate = runtimeBitrateKbps;
        autopilotCalibration = new StreamingAutopilotCalibration(fps);
        return true;
    }

    private void onStreamingAutopilotVideoStats(
            StreamingAutopilotCalibration.Sample sample) {
        StreamingAutopilotCalibration calibration = autopilotCalibration;
        if (calibration == null || !autopilotCalibrationArmed) return;
        StreamingAutopilotCalibration.Progress progress = calibration.add(sample);
        runOnUiThread(() -> {
            if (autopilotCalibration != calibration || isFinishing() || isDestroyed()) return;
            if (progress.phase == StreamingAutopilotCalibration.Phase.COMPLETE) {
                showStreamingAutopilotCalibrationResult(calibration, progress.result);
            } else {
                updateStreamingAutopilotCalibrationProgress(progress);
            }
        });
    }

    private void maybeArmStreamingAutopilotCalibration() {
        StreamingAutopilotCalibration calibration = autopilotCalibration;
        if (calibration == null || autopilotCalibrationArmed || !streamEverRevealed) return;
        boolean playniteGame = transitionSpec != null
                && !transitionSpec.playniteGameId.isEmpty();
        if (playniteGame && !autopilotGameReadySeen) return;

        calibration.arm(SystemClock.uptimeMillis());
        autopilotCalibrationArmed = true;
        autopilotPreviousPerfVisibility = performanceOverlayView.getVisibility();
        autopilotPreviousPerfText = performanceOverlayView.getText();
        autopilotProgressOverlayActive = true;
        performanceOverlayView.setVisibility(View.VISIBLE);
        performanceOverlayView.setText(getString(
                R.string.console_autopilot_calibration_progress_overlay,
                getString(R.string.console_autopilot_calibration_warmup,
                        0, StreamingAutopilotCalibration.WARMUP_MS / 1_000L)));
    }

    private void updateStreamingAutopilotCalibrationProgress(
            StreamingAutopilotCalibration.Progress progress) {
        if (!autopilotProgressOverlayActive) return;
        long seconds = Math.min(progress.totalMs,
                progress.completedMs + 999L) / 1_000L;
        int message = progress.phase == StreamingAutopilotCalibration.Phase.WARMING_UP
                ? R.string.console_autopilot_calibration_warmup
                : R.string.console_autopilot_calibration_measuring;
        performanceOverlayView.setText(getString(
                R.string.console_autopilot_calibration_progress_overlay,
                getString(message, seconds, progress.totalMs / 1_000L)));
    }

    private void showStreamingAutopilotCalibrationResult(
            StreamingAutopilotCalibration completed,
            StreamingAutopilotCalibration.Result result) {
        if (autopilotCalibration != completed) return;
        autopilotCalibration = null;
        autopilotCalibrationArmed = false;
        if (decoderRenderer != null) decoderRenderer.setVideoStatsListener(null);
        restoreStreamingAutopilotProgressOverlay();

        StreamingAutopilotCalibration.Adjustment adjusted =
                StreamingAutopilotCalibration.adjustedSettings(
                        autopilotWidth, autopilotHeight, autopilotFps,
                        autopilotBitrateKbps, result);

        String metrics = getString(R.string.console_autopilot_calibration_metrics,
                result.receivedFps, result.renderedFps, result.frameLossPercent,
                result.rttMs, result.rttVarianceMs, result.decoderLatencyMs,
                result.hostProcessingLatencyMs,
                result.hostProcessingReportedRatio * 100d);
        String recommendation = getString(R.string.console_autopilot_stream_format,
                adjusted.width, adjusted.height, adjusted.fps,
                adjusted.bitrateKbps / 1_000d);
        String message = metrics + "\n\n" + getString(
                        result.passed
                                ? R.string.console_autopilot_calibration_apply_summary
                                : R.string.console_autopilot_calibration_conservative_summary,
                        recommendation) + (result.passed ? "" : "\n\n"
                        + getString(calibrationFailureMessage(result)));
        boolean restoreInputGrab = grabbedInput;
        boolean restoreInputSuppression = controllerHandler != null
                && controllerHandler.isInputSuppressed();
        setInputGrabState(false);
        if (controllerHandler != null) {
            controllerHandler.releaseAllControllerInputsAndSuppress();
        }
        autopilotCalibrationDialog = ConsoleConfirmDialog.show(this,
                getString(result.passed
                        ? R.string.console_autopilot_calibration_passed_title
                        : R.string.console_autopilot_calibration_failed_title),
                message, getString(R.string.console_autopilot_keep_current),
                getString(R.string.console_autopilot_apply_calibrated),
                () -> {
                    AppPreferences.applyStreamSettings(Game.this,
                            autopilotCalibrationAppKey, adjusted.width,
                            adjusted.height, adjusted.fps,
                            adjusted.bitrateKbps);
                    Toast.makeText(Game.this,
                            R.string.console_autopilot_calibration_applied,
                            Toast.LENGTH_LONG).show();
                }, AUTOPILOT_RESULT_ACTION_DELAY_MS, () -> {
                    autopilotCalibrationDialog = null;
                    if (controllerHandler != null) {
                        controllerHandler.setInputSuppressed(restoreInputSuppression);
                    }
                    setInputGrabState(restoreInputGrab);
                });
    }

    private static int calibrationFailureMessage(
            StreamingAutopilotCalibration.Result result) {
        if (result.lowerBitrateSuggested && result.lowerModeSuggested) {
            return R.string.console_autopilot_calibration_lower_both;
        }
        if (result.lowerBitrateSuggested) {
            return R.string.console_autopilot_calibration_lower_bitrate;
        }
        return R.string.console_autopilot_calibration_lower_mode;
    }

    private void cancelStreamingAutopilotCalibration() {
        autopilotCalibration = null;
        autopilotCalibrationArmed = false;
        if (decoderRenderer != null) decoderRenderer.setVideoStatsListener(null);
        runOnUiThread(() -> {
            restoreStreamingAutopilotProgressOverlay();
            if (autopilotCalibrationDialog != null) {
                autopilotCalibrationDialog.dismiss();
                autopilotCalibrationDialog = null;
            }
        });
    }

    private void restoreStreamingAutopilotProgressOverlay() {
        if (!autopilotProgressOverlayActive || performanceOverlayView == null) return;
        autopilotProgressOverlayActive = false;
        performanceOverlayView.setText(autopilotPreviousPerfText);
        performanceOverlayView.setVisibility(autopilotPreviousPerfVisibility);
        autopilotPreviousPerfText = null;
    }

    private LaunchTransitionSpec readTransitionSpec() {
        String id = getIntent().getStringExtra(EXTRA_TRANSITION_ID);
        String hostId = getIntent().getStringExtra(EXTRA_TRANSITION_HOST_ID);
        String rawType = getIntent().getStringExtra(EXTRA_TRANSITION_TYPE);
        if (id == null || id.isEmpty() || hostId == null || hostId.isEmpty()
                || rawType == null) return null;
        try {
            return new LaunchTransitionSpec(id, hostId,
                    LaunchTransitionType.valueOf(rawType),
                    getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID),
                    getIntent().getStringExtra(EXTRA_TRANSITION_PLAYNITE_GAME_ID),
                    getIntent().getLongExtra(EXTRA_TRANSITION_CREATED_AT,
                            System.currentTimeMillis()),
                    getIntent().getBooleanExtra(
                            EXTRA_TRANSITION_START_BEFORE_STREAM, false));
        } catch (IllegalArgumentException invalidType) {
            return null;
        }
    }

    private void setPreferredOrientationForCurrentDisplay() {
        Display display = getWindowManager().getDefaultDisplay();

        // For semi-square displays, we use more complex logic to determine which orientation to use (if any)
        if (PreferenceConfiguration.isSquarishScreen(display)) {
            int desiredOrientation = Configuration.ORIENTATION_UNDEFINED;

            // OSC doesn't properly support portrait displays, so don't use it in portrait mode by default
            if (prefConfig.onscreenController) {
                desiredOrientation = Configuration.ORIENTATION_LANDSCAPE;
            }

            // For native resolution, we will lock the orientation to the one that matches the specified resolution
            if (PreferenceConfiguration.isNativeResolution(prefConfig.width, prefConfig.height)) {
                if (prefConfig.width > prefConfig.height) {
                    desiredOrientation = Configuration.ORIENTATION_LANDSCAPE;
                }
                else {
                    desiredOrientation = Configuration.ORIENTATION_PORTRAIT;
                }
            }

            if (desiredOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
            }
            else if (desiredOrientation == Configuration.ORIENTATION_PORTRAIT) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            }
            else {
                // If we don't have a reason to lock to portrait or landscape, allow any orientation
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
            }
        }
        else {
            // For regular displays, we always request landscape
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
        }
    }


    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Set requested orientation for possible new screen size
        setPreferredOrientationForCurrentDisplay();

        if (virtualController != null) {
            // Refresh layout of OSC for possible new screen size
            virtualController.refreshLayout();
        }

        // Hide on-screen overlays in PiP mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isInPictureInPictureMode()) {
                isHidingOverlays = true;

                if (virtualController != null) {
                    virtualController.hide();
                }

                performanceOverlayView.setVisibility(View.GONE);
                notificationOverlayView.setVisibility(View.GONE);
                brightnessSliderView.hide();

                // Disable sensors while in PiP mode
                controllerHandler.disableSensors();

                // Update GameManager state to indicate we're in PiP (still gaming, but interruptible)
                UiHelper.notifyStreamEnteringPiP(this);
            }
            else {
                isHidingOverlays = false;

                // Restore overlays to previous state when leaving PiP

                if (virtualController != null) {
                    virtualController.show();
                }

                if (prefConfig.enablePerfOverlay) {
                    performanceOverlayView.setVisibility(View.VISIBLE);
                }

                notificationOverlayView.setVisibility(requestedNotificationOverlayVisibility);

                // Enable sensors again after exiting PiP
                controllerHandler.enableSensors();

                // Update GameManager state to indicate we're out of PiP (gaming, non-interruptible)
                UiHelper.notifyStreamExitingPiP(this);
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private PictureInPictureParams getPictureInPictureParams(boolean autoEnter) {
        PictureInPictureParams.Builder builder =
                new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(prefConfig.width, prefConfig.height))
                        .setSourceRectHint(new Rect(
                                streamView.getLeft(), streamView.getTop(),
                                streamView.getRight(), streamView.getBottom()));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter);
            builder.setSeamlessResizeEnabled(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (appName != null) {
                builder.setTitle(appName);
                if (pcName != null) {
                    builder.setSubtitle(pcName);
                }
            }
            else if (pcName != null) {
                builder.setTitle(pcName);
            }
        }

        return builder.build();
    }

    private void updatePipAutoEnter() {
        if (!prefConfig.enablePip) {
            return;
        }

        boolean autoEnter = connected && suppressPipRefCount == 0;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(getPictureInPictureParams(autoEnter));
        }
        else {
            autoEnterPip = autoEnter;
        }
    }

    public void setMetaKeyCaptureState(boolean enabled) {
        // This uses custom APIs present on some Samsung devices to allow capture of
        // meta key events while streaming.
        try {
            Class<?> semWindowManager = Class.forName("com.samsung.android.view.SemWindowManager");
            Method getInstanceMethod = semWindowManager.getMethod("getInstance");
            Object manager = getInstanceMethod.invoke(null);

            if (manager != null) {
                Class<?>[] parameterTypes = new Class<?>[2];
                parameterTypes[0] = ComponentName.class;
                parameterTypes[1] = boolean.class;
                Method requestMetaKeyEventMethod = semWindowManager.getDeclaredMethod("requestMetaKeyEvent", parameterTypes);
                requestMetaKeyEventMethod.invoke(manager, this.getComponentName(), enabled);
            }
            else {
                LimeLog.warning("SemWindowManager.getInstance() returned null");
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();

        // PiP is only supported on Oreo and later, and we don't need to manually enter PiP on
        // Android S and later. On Android R, we will use onPictureInPictureRequested() instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (autoEnterPip) {
                try {
                    // This has thrown all sorts of weird exceptions on Samsung devices
                    // running Oreo. Just eat them and close gracefully on leave, rather
                    // than crashing.
                    enterPictureInPictureMode(getPictureInPictureParams(false));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.R)
    public boolean onPictureInPictureRequested() {
        // Enter PiP when requested unless we're on Android 12 which supports auto-enter.
        if (autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(getPictureInPictureParams(false));
        }
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (discordDmNotifications != null) {
            discordDmNotifications.setWindowFocused(discordDmHostToken, hasFocus);
        }

        // We can't guarantee the state of modifiers keys which may have
        // lifted while focus was not on us. Clear the modifier state.
        this.modifierFlags = 0;

        // With Android native pointer capture, capture is lost when focus is lost,
        // so it must be requested again when focus is regained.
        inputCaptureProvider.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPictureInPictureMode,
                                              Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
        if (discordDmNotifications != null) {
            discordDmNotifications.setPictureInPicture(
                    discordDmHostToken, inPictureInPictureMode);
        }
    }

    private boolean isRefreshRateEqualMatch(float refreshRate) {
        return refreshRate >= prefConfig.fps &&
                refreshRate <= prefConfig.fps + 3;
    }

    private boolean isRefreshRateGoodMatch(float refreshRate) {
        return refreshRate >= prefConfig.fps &&
                Math.round(refreshRate) % prefConfig.fps <= 3;
    }

    private boolean shouldIgnoreInsetsForResolution(int width, int height) {
        // Never ignore insets for non-native resolutions
        if (!PreferenceConfiguration.isNativeResolution(width, height)) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display display = getWindowManager().getDefaultDisplay();
            for (Display.Mode candidate : display.getSupportedModes()) {
                // Ignore insets if this is an exact match for the display resolution
                if ((width == candidate.getPhysicalWidth() && height == candidate.getPhysicalHeight()) ||
                        (height == candidate.getPhysicalWidth() && width == candidate.getPhysicalHeight())) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean mayReduceRefreshRate() {
        return prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS ||
                prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED && prefConfig.reduceRefreshRate);
    }

    private float prepareDisplayForRendering() {
        Display display = getWindowManager().getDefaultDisplay();
        WindowManager.LayoutParams windowLayoutParams = getWindow().getAttributes();
        float displayRefreshRate;

        // On M, we can explicitly set the optimal display mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode bestMode = display.getMode();
            boolean isNativeResolutionStream = PreferenceConfiguration.isNativeResolution(prefConfig.width, prefConfig.height);
            boolean refreshRateIsGood = isRefreshRateGoodMatch(bestMode.getRefreshRate());
            boolean refreshRateIsEqual = isRefreshRateEqualMatch(bestMode.getRefreshRate());

            LimeLog.info("Current display mode: "+bestMode.getPhysicalWidth()+"x"+
                    bestMode.getPhysicalHeight()+"x"+bestMode.getRefreshRate());

            for (Display.Mode candidate : display.getSupportedModes()) {
                boolean refreshRateReduced = candidate.getRefreshRate() < bestMode.getRefreshRate();
                boolean resolutionReduced = candidate.getPhysicalWidth() < bestMode.getPhysicalWidth() ||
                        candidate.getPhysicalHeight() < bestMode.getPhysicalHeight();
                boolean resolutionFitsStream = candidate.getPhysicalWidth() >= prefConfig.width &&
                        candidate.getPhysicalHeight() >= prefConfig.height;

                LimeLog.info("Examining display mode: "+candidate.getPhysicalWidth()+"x"+
                        candidate.getPhysicalHeight()+"x"+candidate.getRefreshRate());

                if (candidate.getPhysicalWidth() > 4096 && prefConfig.width <= 4096) {
                    // Avoid resolutions options above 4K to be safe
                    continue;
                }

                // On non-4K streams, we force the resolution to never change unless it's above
                // 60 FPS, which may require a resolution reduction due to HDMI bandwidth limitations,
                // or it's a native resolution stream.
                if (prefConfig.width < 3840 && prefConfig.fps <= 60 && !isNativeResolutionStream) {
                    if (display.getMode().getPhysicalWidth() != candidate.getPhysicalWidth() ||
                            display.getMode().getPhysicalHeight() != candidate.getPhysicalHeight()) {
                        continue;
                    }
                }

                // Make sure the resolution doesn't regress unless if it's over 60 FPS
                // where we may need to reduce resolution to achieve the desired refresh rate.
                if (resolutionReduced && !(prefConfig.fps > 60 && resolutionFitsStream)) {
                    continue;
                }

                if (mayReduceRefreshRate() && refreshRateIsEqual && !isRefreshRateEqualMatch(candidate.getRefreshRate())) {
                    // If we had an equal refresh rate and this one is not, skip it. In min latency
                    // mode, we want to always prefer the highest frame rate even though it may cause
                    // microstuttering.
                    continue;
                }
                else if (refreshRateIsGood) {
                    // We've already got a good match, so if this one isn't also good, it's not
                    // worth considering at all.
                    if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
                        continue;
                    }

                    if (mayReduceRefreshRate()) {
                        // User asked for the lowest possible refresh rate, so don't raise it if we
                        // have a good match already
                        if (candidate.getRefreshRate() > bestMode.getRefreshRate()) {
                            continue;
                        }
                    }
                    else {
                        // User asked for the highest possible refresh rate, so don't reduce it if we
                        // have a good match already
                        if (refreshRateReduced) {
                            continue;
                        }
                    }
                }
                else if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
                    // We didn't have a good match and this match isn't good either, so just don't
                    // reduce the refresh rate.
                    if (refreshRateReduced) {
                        continue;
                    }
                } else {
                    // We didn't have a good match and this match is good. Prefer this refresh rate
                    // even if it reduces the refresh rate. Lowering the refresh rate can be beneficial
                    // when streaming a 60 FPS stream on a 90 Hz device. We want to select 60 Hz to
                    // match the frame rate even if the active display mode is 90 Hz.
                }

                bestMode = candidate;
                refreshRateIsGood = isRefreshRateGoodMatch(candidate.getRefreshRate());
                refreshRateIsEqual = isRefreshRateEqualMatch(candidate.getRefreshRate());
            }

            LimeLog.info("Best display mode: "+bestMode.getPhysicalWidth()+"x"+
                    bestMode.getPhysicalHeight()+"x"+bestMode.getRefreshRate());

            // Only apply new window layout parameters if we've actually changed the display mode
            if (display.getMode().getModeId() != bestMode.getModeId()) {
                // If we only changed refresh rate and we're on an OS that supports Surface.setFrameRate()
                // use that instead of using preferredDisplayModeId to avoid the possibility of triggering
                // bugs that can cause the system to switch from 4K60 to 4K24 on Chromecast 4K.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        display.getMode().getPhysicalWidth() != bestMode.getPhysicalWidth() ||
                        display.getMode().getPhysicalHeight() != bestMode.getPhysicalHeight()) {
                    // Apply the display mode change
                    windowLayoutParams.preferredDisplayModeId = bestMode.getModeId();
                    getWindow().setAttributes(windowLayoutParams);
                }
                else {
                    LimeLog.info("Using setFrameRate() instead of preferredDisplayModeId due to matching resolution");
                }
            }
            else {
                LimeLog.info("Current display mode is already the best display mode");
            }

            displayRefreshRate = bestMode.getRefreshRate();
        }
        // On L, we can at least tell the OS that we want a refresh rate
        else {
            float bestRefreshRate = display.getRefreshRate();
            for (float candidate : display.getSupportedRefreshRates()) {
                LimeLog.info("Examining refresh rate: "+candidate);

                if (candidate > bestRefreshRate) {
                    // Ensure the frame rate stays around 60 Hz for <= 60 FPS streams
                    if (prefConfig.fps <= 60) {
                        if (candidate >= 63) {
                            continue;
                        }
                    }

                    bestRefreshRate = candidate;
                }
            }

            LimeLog.info("Selected refresh rate: "+bestRefreshRate);
            windowLayoutParams.preferredRefreshRate = bestRefreshRate;
            displayRefreshRate = bestRefreshRate;

            // Apply the refresh rate change
            getWindow().setAttributes(windowLayoutParams);
        }

        // Until Marshmallow, we can't ask for a 4K display mode, so we'll
        // need to hint the OS to provide one.
        boolean aspectRatioMatch = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // We'll calculate whether we need to scale by aspect ratio. If not, we'll use
            // setFixedSize so we can handle 4K properly. The only known devices that have
            // >= 4K screens have exactly 4K screens, so we'll be able to hit this good path
            // on these devices. On Marshmallow, we can start changing to 4K manually but no
            // 4K devices run 6.0 at the moment.
            Point screenSize = new Point(0, 0);
            display.getSize(screenSize);

            double screenAspectRatio = ((double)screenSize.y) / screenSize.x;
            double streamAspectRatio = ((double)prefConfig.height) / prefConfig.width;
            if (Math.abs(screenAspectRatio - streamAspectRatio) < 0.001) {
                LimeLog.info("Stream has compatible aspect ratio with output display");
                aspectRatioMatch = true;
            }
        }

        if (prefConfig.stretchVideo || aspectRatioMatch) {
            // Set the surface to the size of the video
            streamView.getHolder().setFixedSize(prefConfig.width, prefConfig.height);
        }
        else {
            // Set the surface to scale based on the aspect ratio of the stream
            streamView.setDesiredAspectRatio((double)prefConfig.width / (double)prefConfig.height);
        }

        // Set the desired refresh rate that will get passed into setFrameRate() later
        desiredRefreshRate = displayRefreshRate;

        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // TVs may take a few moments to switch refresh rates, and we can probably assume
            // it will be eventually activated.
            // TODO: Improve this
            return displayRefreshRate;
        }
        else {
            // Use the lower of the current refresh rate and the selected refresh rate.
            // The preferred refresh rate may not actually be applied (ex: Battery Saver mode).
            return Math.min(getWindowManager().getDefaultDisplay().getRefreshRate(), displayRefreshRate);
        }
    }

    @SuppressLint("InlinedApi")
    private final Runnable hideSystemUi = new Runnable() {
            @Override
            public void run() {
                // TODO: Do we want to use WindowInsetsController here on R+ instead of
                // SYSTEM_UI_FLAG_IMMERSIVE_STICKY? They seem to do the same thing as of S...

                // In multi-window mode on N+, we need to drop our layout flags or we'll
                // be drawing underneath the system UI.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode()) {
                    Game.this.getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                }
                else {
                    // Use immersive mode
                    Game.this.getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
            }
    };

    private void hideSystemUi(int delay) {
        Handler h = getWindow().getDecorView().getHandler();
        if (h != null) {
            h.removeCallbacks(hideSystemUi);
            h.postDelayed(hideSystemUi, delay);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.N)
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);

        // In multi-window, we don't want to use the full-screen layout
        // flag. It will cause us to collide with the system UI.
        // This function will also be called for PiP so we can cover
        // that case here too.
        if (isInMultiWindowMode) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decoderRenderer.notifyVideoBackground();
        }
        else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decoderRenderer.notifyVideoForeground();
        }

        // Correct the system UI visibility flags
        hideSystemUi(50);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_TRANSITION_REVEALED, streamEverRevealed);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        cancelStreamingAutopilotCalibration();
        boolean acceptedWithoutTransport = false;
        synchronized (autoWarmUpGateLock) {
            if (!autoWarmUpHomeFrameAccepted.get()) {
                cancelUnstartedAutoWarmUp(false);
            } else if (!connecting && !connected
                    && isExactAutoWarmUpPreparing(
                    RetainedStreamSessionCoordinator.snapshot())) {
                acceptedWithoutTransport = markAutoWarmUpReconnectRequired(
                        "activity_destroyed_before_transport");
            }
        }
        if (acceptedWithoutTransport) {
            SessionResumeManager.save(this, getIntent(), streamSessionId);
            BackgroundStreamService.transportLost(this, streamSessionId);
        }
        if (hasOwnRetainedSession()
                && (connecting || connected)) {
            backgroundStreamParked = false;
            SessionResumeManager.save(this, getIntent(), streamSessionId);
            RetainedStreamSessionCoordinator.Snapshot retained =
                    RetainedStreamSessionCoordinator.snapshot();
            if (retained.state == RetainedStreamSessionCoordinator.State.PREPARING) {
                markAutoWarmUpReconnectRequired("activity_destroyed");
            } else {
                RetainedStreamSessionCoordinator.markReconnectRequired(streamSessionId);
            }
            BackgroundStreamService.transportLost(this, streamSessionId);
            stopConnection();
        }
        if (transitionCoordinator != null) {
            if (!isChangingConfigurations()) cleanupUnrevealedProviderLaunch("activity-destroyed");
            transitionCoordinator.close();
            transitionCoordinator = null;
        }
        cancelPendingAutomaticReveal();
        if (discordOverlayController != null) {
            discordOverlayController.destroy();
            discordOverlayController = null;
        }
        if (discordDmNotifications != null) {
            discordDmNotifications.unregisterHost(discordDmHostToken);
            discordDmHostToken = null;
        }
        if (consoleLoadingView != null) {
            consoleLoadingView.stop();
            consoleLoadingView = null;
        }
        super.onDestroy();

        // Unregister broadcast receiver
        try {
            unregisterReceiver(quitAppReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered, ignore
        }

        if (controllerHandler != null) {
            // Unregister virtual controller listener before destroying
            controllerHandler.setControllerInputListener(null);
            controllerHandler.destroy();
        }
        if (keyboardTranslator != null) {
            InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
            inputManager.unregisterInputDeviceListener(keyboardTranslator);
        }

        if (lowLatencyWifiLock != null) {
            lowLatencyWifiLock.release();
        }
        if (highPerfWifiLock != null) {
            highPerfWifiLock.release();
        }

        if (connectedToUsbDriverService) {
            // Unbind from the discovery service
            unbindService(usbDriverServiceConnection);
        }

        // Clean up brightness slider
        if (brightnessSliderView != null) {
            brightnessSliderView.cleanup();
        }

        // Destroy the capture provider
        inputCaptureProvider.destroy();
    }

    @Override
    protected void onPause() {
        if (discordDmNotifications != null) {
            discordDmNotifications.deactivateHost(discordDmHostToken);
        }
        if (discordOverlayController != null) {
            discordOverlayController.onActivityPaused();
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!pm.isInteractive() && connected && !userInitiatedDisconnect) {
            if (PreferenceConfiguration.readPreferences(this).autoResumeStream) {
                android.util.Log.d("SessionResume", "onPause: screen going off, saving session");
                SessionResumeManager.save(this, getIntent(), streamSessionId);
            } else {
                android.util.Log.d("SessionResume", "onPause: auto-resume disabled by preference");
            }
        } else {
            android.util.Log.d("SessionResume", "onPause: not saving — isInteractive=" + pm.isInteractive()
                    + " connected=" + connected + " userInitiatedDisconnect=" + userInitiatedDisconnect);
        }

        if (isFinishing()) {
            // Stop any further input device notifications before we lose focus (and pointer capture)
            if (controllerHandler != null) {
                controllerHandler.stop();
            }

            // Ungrab input to prevent further input device notifications
            setInputGrabState(false);
        }

        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (discordDmNotifications != null) {
            discordDmNotifications.activateHost(discordDmHostToken);
            discordDmNotifications.setWindowFocused(
                    discordDmHostToken, getWindow().getDecorView().hasWindowFocus());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                discordDmNotifications.setPictureInPicture(
                        discordDmHostToken, isInPictureInPictureMode());
            }
        }
        if (discordOverlayController != null) {
            discordOverlayController.onActivityResumed();
        }
        boolean returningFromStreamHome = streamHomeVisible;
        if (streamHomeVisible) {
            streamHomeVisible = false;
            if (streamAudioRenderer != null) {
                streamAudioRenderer.setVolume(isOwnedHiddenAutoWarmUp() ? 0f : 1f);
            }
            if (controllerHandler != null) {
                if (isOwnedHiddenAutoWarmUp()) {
                    controllerHandler.setInputSuppressed(true);
                }
                else controllerHandler.enableSensors();
            }
            if (connected) {
                hideSystemUi(50);
                streamView.post(streamView::requestFocus);
            }
        }
        if (backgroundStreamParked && surfaceCreated) {
            restoreParkedStream(streamView.getHolder());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        cancelStreamingAutopilotCalibration();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (virtualController != null) {
            virtualController.hide();
        }

        if (streamHomeVisible) return;
        if (canParkBackgroundStream()) {
            parkBackgroundStream();
            return;
        }
        if (backgroundStreamParked) return;

        if (conn != null) {
            int videoFormat = decoderRenderer.getActiveVideoFormat();

            displayedFailureDialog = true;
            stopConnection();

            if (prefConfig.enableLatencyToast) {
                int averageEndToEndLat = decoderRenderer.getAverageEndToEndLatency();
                int averageDecoderLat = decoderRenderer.getAverageDecoderLatency();
                String message = null;
                if (averageEndToEndLat > 0) {
                    message = getResources().getString(R.string.conn_client_latency)+" "+averageEndToEndLat+" ms";
                    if (averageDecoderLat > 0) {
                        message += " ("+getResources().getString(R.string.conn_client_latency_hw)+" "+averageDecoderLat+" ms)";
                    }
                }
                else if (averageDecoderLat > 0) {
                    message = getResources().getString(R.string.conn_hardware_latency)+" "+averageDecoderLat+" ms";
                }

                // Add the video codec to the post-stream toast
                if (message != null) {
                    message += " [";

                    if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                        message += "H.264";
                    }
                    else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                        message += "HEVC";
                    }
                    else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                        message += "AV1";
                    }
                    else {
                        message += "UNKNOWN";
                    }

                    if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
                        message += " HDR";
                    }

                    message += "]";
                }

                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
            }

            // Clear the tombstone count if we terminated normally
            if (!reportedCrash && tombstonePrefs.getInt("CrashCount", 0) != 0) {
                tombstonePrefs.edit()
                        .putInt("CrashCount", 0)
                        .putInt("LastNotifiedCrashCount", 0)
                        .apply();
            }
        }

        finish();
    }

    private void setInputGrabState(boolean grab) {
        // Grab/ungrab the mouse cursor
        if (grab) {
            inputCaptureProvider.enableCapture();

            // Enabling capture may hide the cursor again, so
            // we will need to show it again.
            if (cursorVisible) {
                inputCaptureProvider.showCursor();
            }
        }
        else {
            inputCaptureProvider.disableCapture();
        }

        // Grab/ungrab system keyboard shortcuts
        setMetaKeyCaptureState(grab);

        grabbedInput = grab;
    }

    private final Runnable toggleGrab = new Runnable() {
        @Override
        public void run() {
            setInputGrabState(!grabbedInput);
        }
    };

    // Returns true if the key stroke was consumed
    private boolean handleSpecialKeys(int androidKeyCode, boolean down) {
        int modifierMask = 0;
        int nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN;

        if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
            androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_CTRL;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
                 androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_SHIFT;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT ||
                 androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_ALT;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_META_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_META_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_META;
        }
        else {
            nonModifierKeyCode = androidKeyCode;
        }

        if (down) {
            this.modifierFlags |= modifierMask;
        }
        else {
            this.modifierFlags &= ~modifierMask;
        }

        // Handle the special combos on the key up
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode == androidKeyCode) {
                // If this is a key up for the special key itself, eat that because the host never saw the original key down
                return true;
            }
            else if (modifierFlags != 0) {
                // While we're waiting for modifiers to come up, eat all key downs and allow all key ups to pass
                return down;
            }
            else {
                // When all modifiers are up, perform the special action
                switch (specialKeyCode) {
                    // Toggle input grab
                    case KeyEvent.KEYCODE_Z:
                        Handler h = getWindow().getDecorView().getHandler();
                        if (h != null) {
                            h.postDelayed(toggleGrab, 250);
                        }
                        break;

                    // Quit
                    case KeyEvent.KEYCODE_Q:
                        finish();
                        break;

                    // Toggle cursor visibility
                    case KeyEvent.KEYCODE_C:
                        if (!grabbedInput) {
                            inputCaptureProvider.enableCapture();
                            grabbedInput = true;
                        }
                        cursorVisible = !cursorVisible;
                        if (cursorVisible) {
                            inputCaptureProvider.showCursor();
                        } else {
                            inputCaptureProvider.hideCursor();
                        }
                        break;

                    default:
                        break;
                }

                // Reset special key state
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
                waitingForAllModifiersUp = false;
            }
        }
        // Check if Ctrl+Alt+Shift is down when a non-modifier key is pressed
        else if ((modifierFlags & (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT)) ==
                (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT) &&
                (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)) {
            switch (androidKeyCode) {
                case KeyEvent.KEYCODE_Z:
                case KeyEvent.KEYCODE_Q:
                case KeyEvent.KEYCODE_C:
                    // Remember that a special key combo was activated, so we can consume all key
                    // events until the modifiers come up
                    specialKeyCode = androidKeyCode;
                    waitingForAllModifiersUp = true;
                    return true;

                default:
                    // This isn't a special combo that we consume on the client side
                    return false;
            }
        }

        // Not a special combo
        return false;
    }

    // We cannot simply use modifierFlags for all key event processing, because
    // some IMEs will not generate real key events for pressing Shift. Instead
    // they will simply send key events with isShiftPressed() returning true,
    // and we will need to send the modifier flag ourselves.
    private byte getModifierState(KeyEvent event) {
        // Start with the global modifier state to ensure we cover the case
        // detailed in https://github.com/moonlight-stream/moonlight-android/issues/840
        byte modifier = getModifierState();
        if (event.isShiftPressed()) {
            modifier |= KeyboardPacket.MODIFIER_SHIFT;
        }
        if (event.isCtrlPressed()) {
            modifier |= KeyboardPacket.MODIFIER_CTRL;
        }
        if (event.isAltPressed()) {
            modifier |= KeyboardPacket.MODIFIER_ALT;
        }
        if (event.isMetaPressed()) {
            modifier |= KeyboardPacket.MODIFIER_META;
        }
        return modifier;
    }

    private byte getModifierState() {
        return (byte) modifierFlags;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKeyDown(event) || super.onKeyDown(keyCode, event);
    }

    private boolean consumeOverlayOwnedKeySequence(KeyEvent event) {
        long sequence = overlayKeySequence(event);
        return overlayOwnedKeySequences.consume(sequence,
                event.getAction() == KeyEvent.ACTION_UP || event.isCanceled());
    }

    private boolean dispatchOverlayKeyEvent(KeyEvent event) {
        boolean handled = overlayMenuView.dispatchKeyEvent(event);
        if (!handled) return false;
        long sequence = overlayKeySequence(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            overlayOwnedKeySequences.retain(sequence);
        } else if (event.getAction() == KeyEvent.ACTION_UP || event.isCanceled()) {
            overlayOwnedKeySequences.consume(sequence, true);
        }
        return true;
    }

    private static long overlayKeySequence(KeyEvent event) {
        return overlayKeySequence(event.getDeviceId(), event.getKeyCode());
    }

    static long overlayKeySequence(int deviceId, int keyCode) {
        return ((long) deviceId << 32) ^ (keyCode & 0xffffffffL);
    }

    static final class OverlayKeySequenceLatch {
        private final Set<Long> sequences = new HashSet<>();

        void retain(long sequence) {
            sequences.add(sequence);
        }

        boolean consume(long sequence, boolean terminalUp) {
            if (!sequences.contains(sequence)) return false;
            if (terminalUp) sequences.remove(sequence);
            return true;
        }
    }

    @Override
    public void onBackPressed() {
        if (returnRetainedObservationToDashboard()) return;
        if (connected && !streamHomeVisible && !isTransitionInputBlocked()) {
            openConsoleHome();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean handleKeyDown(KeyEvent event) {
        if (consumeOverlayOwnedKeySequence(event)) return true;
        if (isTransitionInputBlocked()) {
            if (consoleLoadingView != null) {
                consoleLoadingView.handleControllerKey(event);
            }
            return true;
        }
        if (isImeVisible && isAndroidTV && event.getDeviceId() >= 0) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_BUTTON_B:
                    return false;
            }
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && (overlayMenuView == null
                || overlayMenuView.getVisibility() != View.VISIBLE)) {
            return true;
        }

        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        // If overlay menu is visible, route all key events to it
        if (overlayMenuView != null && overlayMenuView.getVisibility() == View.VISIBLE) {
            return dispatchOverlayKeyEvent(event);
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click. This event WILL repeat if
        // the right mouse button is held down, so we ignore those.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        boolean handled = false;

        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonDown(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            // Let this method take duplicate key down events
            if (handleSpecialKeys(event.getKeyCode(), true)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            // We'll send it as a raw key event if we have a key mapping, otherwise we'll send it
            // as UTF-8 text (if it's a printable character).
            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getDeviceId());
            if (translated == 0) {
                // Make sure it has a valid Unicode representation and it's not a dead character
                // (which we don't support). If those are true, we can send it as UTF-8 text.
                //
                // NB: We need to be sure this happens before the getRepeatCount() check because
                // UTF-8 events don't auto-repeat on the host side.
                int unicodeChar = event.getUnicodeChar();
                if ((unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0) {
                    conn.sendUtf8Text(""+(char)unicodeChar);
                    return true;
                }

                return false;
            }

            // Eat repeat down events
            if (event.getRepeatCount() > 0) {
                return true;
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), event.getDeviceId()) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleKeyUp(event) || super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean handleKeyUp(KeyEvent event) {
        if (consumeOverlayOwnedKeySequence(event)) return true;
        if (isTransitionInputBlocked()) {
            if (consoleLoadingView != null
                    && consoleLoadingView.handleControllerKey(event)) {
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
                cancelTransition();
            }
            return true;
        }
        if (isImeVisible && isAndroidTV && event.getDeviceId() >= 0) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_BUTTON_B:
                    return false;
            }
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && (overlayMenuView == null
                || overlayMenuView.getVisibility() != View.VISIBLE)) {
            openConsoleHome();
            return true;
        }

        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        // If overlay menu is visible, route all key events to it
        if (overlayMenuView != null && overlayMenuView.getVisibility() == View.VISIBLE) {
            return dispatchOverlayKeyEvent(event);
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        boolean handled = false;
        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonUp(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            if (handleSpecialKeys(event.getKeyCode(), false)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getDeviceId());
            if (translated == 0) {
                // If we sent this event as UTF-8 on key down, also report that it was handled
                // when we get the key up event for it.
                int unicodeChar = event.getUnicodeChar();
                return (unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0;
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), event.getDeviceId()) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        return handleKeyMultiple(event) || super.onKeyMultiple(keyCode, repeatCount, event);
    }

    private boolean handleKeyMultiple(KeyEvent event) {
        if (consumeOverlayOwnedKeySequence(event)) return true;
        // We can receive keys from a software keyboard that don't correspond to any existing
        // KEYCODE value. Android will give those to us as an ACTION_MULTIPLE KeyEvent.
        //
        // Despite the fact that the Android docs say this is unused since API level 29, these
        // events are still sent as of Android 13 for the above case.
        //
        // For other cases of ACTION_MULTIPLE, we will not report those as handled so hopefully
        // they will be passed to us again as regular singular key events.
        if (event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN || event.getCharacters() == null) {
            return false;
        }

        conn.sendUtf8Text(event.getCharacters());
        return true;
    }

    private TouchContext getTouchContext(int actionIndex)
    {
        if (actionIndex < touchContextMap.length) {
            return touchContextMap[actionIndex];
        }
        else {
            return null;
        }
    }

    @Override
    public void toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay");
        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputManager != null) {
            inputManager.toggleSoftInput(0, 0);
        }
    }

    @Override
    public void onTextCommitted(String text) {
        if (isTransitionInputBlocked()) return;
        if (conn != null && text != null) {
            conn.sendUtf8Text(text);
        }
    }

    @Override
    public void onKeyboardDismissRequest() {
        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputManager != null) {
            inputManager.hideSoftInputFromWindow(streamView.getWindowToken(), 0);
        }
    }

    @Override
    public void onImeKeyReceived(KeyEvent event) {
        if (isTransitionInputBlocked()) return;
        if (conn != null) {
            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getDeviceId());
            if (translated != 0) {
                byte action = (event.getAction() == KeyEvent.ACTION_DOWN) ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP;
                conn.sendKeyboardInput(translated, action, getModifierState(event),
                        keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), event.getDeviceId()) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
            }
        }
    }

    private byte getLiTouchTypeFromEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                return MoonBridge.LI_TOUCH_EVENT_DOWN;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if ((event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    return MoonBridge.LI_TOUCH_EVENT_CANCEL;
                }
                else {
                    return MoonBridge.LI_TOUCH_EVENT_UP;
                }

            case MotionEvent.ACTION_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_MOVE;

            case MotionEvent.ACTION_CANCEL:
                // ACTION_CANCEL applies to *all* pointers in the gesture, so it maps to CANCEL_ALL
                // rather than CANCEL. For a single pointer cancellation, that's indicated via
                // FLAG_CANCELED on a ACTION_POINTER_UP.
                // https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi
                return MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL;

            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_HOVER;

            case MotionEvent.ACTION_HOVER_EXIT:
                return MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY;

            default:
               return -1;
        }
    }

    private float[] getStreamViewRelativeNormalizedXY(View view, MotionEvent event, int pointerIndex) {
        float normalizedX = event.getX(pointerIndex);
        float normalizedY = event.getY(pointerIndex);

        // For the containing background view, we must subtract the origin
        // of the StreamView to get video-relative coordinates.
        if (view != streamView) {
            normalizedX -= streamView.getX();
            normalizedY -= streamView.getY();
        }

        normalizedX = Math.max(normalizedX, 0.0f);
        normalizedY = Math.max(normalizedY, 0.0f);

        normalizedX = Math.min(normalizedX, streamView.getWidth());
        normalizedY = Math.min(normalizedY, streamView.getHeight());

        normalizedX /= streamView.getWidth();
        normalizedY /= streamView.getHeight();

        return new float[] { normalizedX, normalizedY };
    }

    private static float normalizeValueInRange(float value, InputDevice.MotionRange range) {
        return (value - range.getMin()) / range.getRange();
    }

    private static float getPressureOrDistance(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                // Hover events report distance
                if (dev != null) {
                    InputDevice.MotionRange distanceRange = dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.getSource());
                    if (distanceRange != null) {
                        return normalizeValueInRange(event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex), distanceRange);
                    }
                }
                return 0.0f;

            default:
                // Other events report pressure
                return event.getPressure(pointerIndex);
        }
    }

    private static short getRotationDegrees(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) != null) {
                short rotationDegrees = (short) Math.toDegrees(event.getOrientation(pointerIndex));
                if (rotationDegrees < 0) {
                    rotationDegrees += 360;
                }
                return rotationDegrees;
            }
        }
        return MoonBridge.LI_ROT_UNKNOWN;
    }

    private static float[] polarToCartesian(float r, float theta) {
        return new float[] { (float)(r * Math.cos(theta)), (float)(r * Math.sin(theta)) };
    }

    private static float cartesianToR(float[] point) {
        return (float)Math.sqrt(Math.pow(point[0], 2) + Math.pow(point[1], 2));
    }

    private float[] getStreamViewNormalizedContactArea(MotionEvent event, int pointerIndex) {
        float orientation;

        // If the orientation is unknown, we'll just assume it's at a 45 degree angle and scale it by
        // X and Y scaling factors evenly.
        if (event.getDevice() == null || event.getDevice().getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) == null) {
            orientation = (float)(Math.PI / 4);
        }
        else {
            orientation = event.getOrientation(pointerIndex);
        }

        float contactAreaMajor, contactAreaMinor;
        switch (event.getActionMasked()) {
            // Hover events report the tool size
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                contactAreaMajor = event.getToolMajor(pointerIndex);
                contactAreaMinor = event.getToolMinor(pointerIndex);
                break;

            // Other events report contact area
            default:
                contactAreaMajor = event.getTouchMajor(pointerIndex);
                contactAreaMinor = event.getTouchMinor(pointerIndex);
                break;
        }

        // The contact area major axis is parallel to the orientation, so we simply convert
        // polar to cartesian coordinates using the orientation as theta.
        float[] contactAreaMajorCartesian = polarToCartesian(contactAreaMajor, orientation);

        // The contact area minor axis is perpendicular to the contact area major axis (and thus
        // the orientation), so rotate the orientation angle by 90 degrees.
        float[] contactAreaMinorCartesian = polarToCartesian(contactAreaMinor, (float)(orientation + (Math.PI / 2)));

        // Normalize the contact area to the stream view size
        contactAreaMajorCartesian[0] = Math.min(Math.abs(contactAreaMajorCartesian[0]), streamView.getWidth()) / streamView.getWidth();
        contactAreaMinorCartesian[0] = Math.min(Math.abs(contactAreaMinorCartesian[0]), streamView.getWidth()) / streamView.getWidth();
        contactAreaMajorCartesian[1] = Math.min(Math.abs(contactAreaMajorCartesian[1]), streamView.getHeight()) / streamView.getHeight();
        contactAreaMinorCartesian[1] = Math.min(Math.abs(contactAreaMinorCartesian[1]), streamView.getHeight()) / streamView.getHeight();

        // Convert the normalized values back into polar coordinates
        return new float[] { cartesianToR(contactAreaMajorCartesian), cartesianToR(contactAreaMinorCartesian) };
    }

    private boolean sendPenEventForPointer(View view, MotionEvent event, byte eventType, byte toolType, int pointerIndex) {
        byte penButtons = 0;
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_PRIMARY;
        }
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_SECONDARY;
        }

        byte tiltDegrees = MoonBridge.LI_TILT_UNKNOWN;
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_TILT, event.getSource()) != null) {
                tiltDegrees = (byte)Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex));
            }
        }

        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return conn.sendPenEvent(eventType, toolType, penButtons,
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex), tiltDegrees) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private static byte convertToolTypeToStylusToolType(MotionEvent event, int pointerIndex) {
        switch (event.getToolType(pointerIndex)) {
            case MotionEvent.TOOL_TYPE_ERASER:
                return MoonBridge.LI_TOOL_TYPE_ERASER;
            case MotionEvent.TOOL_TYPE_STYLUS:
                return MoonBridge.LI_TOOL_TYPE_PEN;
            default:
                return MoonBridge.LI_TOOL_TYPE_UNKNOWN;
        }
    }

    private boolean trySendPenEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            boolean handledStylusEvent = false;
            for (int i = 0; i < event.getPointerCount(); i++) {
                byte toolType = convertToolTypeToStylusToolType(event, i);
                if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                    // Not a stylus pointer, so skip it
                    continue;
                }
                else {
                    // This pointer is a stylus, so we'll report that we handled this event
                    handledStylusEvent = true;
                }

                if (!sendPenEventForPointer(view, event, eventType, toolType, i)) {
                    // Pen events aren't supported by the host
                    return false;
                }
            }
            return handledStylusEvent;
        }
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendPenEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, MoonBridge.LI_TOOL_TYPE_UNKNOWN, (byte)0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN, MoonBridge.LI_TILT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        }
        else {
            // Up, Down, and Hover events are specific to the action index
            byte toolType = convertToolTypeToStylusToolType(event, event.getActionIndex());
            if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                // Not a stylus event
                return false;
            }
            return sendPenEventForPointer(view, event, eventType, toolType, event.getActionIndex());
        }
    }

    private boolean sendTouchEventForPointer(View view, MotionEvent event, byte eventType, int pointerIndex) {
        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return conn.sendTouchEvent(eventType, event.getPointerId(pointerIndex),
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex)) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private boolean trySendTouchEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (!sendTouchEventForPointer(view, event, eventType, i)) {
                    return false;
                }
            }
            return true;
        }
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        }
        else {
            // Up, Down, and Hover events are specific to the action index
            return sendTouchEventForPointer(view, event, eventType, event.getActionIndex());
        }
    }

    // Returns true if the event was consumed
    // NB: View is only present if called from a view callback
    private boolean handleMotionEvent(View view, MotionEvent event) {
        if (isTransitionInputBlocked()) {
            if (consoleLoadingView != null) {
                consoleLoadingView.handleControllerMotion(event);
            }
            return true;
        }
        // Pass through mouse/touch/joystick input if we're not grabbing
        if (!grabbedInput) {
            return false;
        }

        int eventSource = event.getSource();
        int deviceSources = event.getDevice() != null ? event.getDevice().getSources() : 0;
        if ((eventSource & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            if (controllerHandler.handleMotionEvent(event)) {
                return true;
            }
        }
        else if ((deviceSources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && controllerHandler.tryHandleTouchpadEvent(event)) {
            return true;
        }
        else if ((eventSource & InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                 (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 ||
                 eventSource == InputDevice.SOURCE_MOUSE_RELATIVE)
        {
            // This case is for mice and non-finger touch devices
            if (eventSource == InputDevice.SOURCE_MOUSE ||
                    (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 || // SOURCE_TOUCHPAD
                    eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                    (event.getPointerCount() >= 1 &&
                            (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)) ||
                    eventSource == 12290) // 12290 = Samsung DeX mode desktop mouse
            {
                int buttonState = event.getButtonState();
                int changedButtons = buttonState ^ lastButtonState;

                // The DeX touchpad on the Fold 4 sends proper right click events using BUTTON_SECONDARY,
                // but doesn't send BUTTON_PRIMARY for a regular click. Instead it sends ACTION_DOWN/UP,
                // so we need to fix that up to look like a sane input event to process it correctly.
                if (eventSource == 12290) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        buttonState |= MotionEvent.BUTTON_PRIMARY;
                    }
                    else if (event.getAction() == MotionEvent.ACTION_UP) {
                        buttonState &= ~MotionEvent.BUTTON_PRIMARY;
                    }
                    else {
                        // We may be faking the primary button down from a previous event,
                        // so be sure to add that bit back into the button state.
                        buttonState |= (lastButtonState & MotionEvent.BUTTON_PRIMARY);
                    }

                    changedButtons = buttonState ^ lastButtonState;
                }

                // Ignore mouse input if we're not capturing from our input source
                if (!inputCaptureProvider.isCapturingActive()) {
                    // We return true here because otherwise the events may end up causing
                    // Android to synthesize d-pad events.
                    return true;
                }

                // Always update the position before sending any button events. If we're
                // dealing with a stylus without hover support, our position might be
                // significantly different than before.
                if (inputCaptureProvider.eventHasRelativeMouseAxes(event)) {
                    // Send the deltas straight from the motion event
                    short deltaX = (short)inputCaptureProvider.getRelativeAxisX(event);
                    short deltaY = (short)inputCaptureProvider.getRelativeAxisY(event);

                    if (deltaX != 0 || deltaY != 0) {
                        if (prefConfig.absoluteMouseMode) {
                            // NB: view may be null, but we can unconditionally use streamView because we don't need to adjust
                            // relative axis deltas for the position of the streamView within the parent's coordinate system.
                            conn.sendMouseMoveAsMousePosition(deltaX, deltaY, (short)streamView.getWidth(), (short)streamView.getHeight());
                        }
                        else {
                            conn.sendMouseMove(deltaX, deltaY);
                        }
                    }
                }
                else if ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0) {
                    // If this input device is not associated with the view itself (like a trackpad),
                    // we'll convert the device-specific coordinates to use to send the cursor position.
                    // This really isn't ideal but it's probably better than nothing.
                    //
                    // Trackpad on newer versions of Android (Oreo and later) should be caught by the
                    // relative axes case above. If we get here, we're on an older version that doesn't
                    // support pointer capture.
                    InputDevice device = event.getDevice();
                    if (device != null) {
                        InputDevice.MotionRange xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource);
                        InputDevice.MotionRange yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource);

                        // All touchpads coordinate planes should start at (0, 0)
                        if (xRange != null && yRange != null && xRange.getMin() == 0 && yRange.getMin() == 0) {
                            int xMax = (int)xRange.getMax();
                            int yMax = (int)yRange.getMax();

                            // Touchpads must be smaller than (65535, 65535)
                            if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE) {
                                conn.sendMousePosition((short)event.getX(), (short)event.getY(),
                                                       (short)xMax, (short)yMax);
                            }
                        }
                    }
                }
                else if (view != null && trySendPenEvent(view, event)) {
                    // If our host supports pen events, send it directly
                    return true;
                }
                else if (view != null) {
                    // Otherwise send absolute position based on the view for SOURCE_CLASS_POINTER
                    updateMousePosition(view, event);
                }

                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    // Send the vertical scroll packet
                    conn.sendMouseHighResScroll((short)(event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120));
                    conn.sendMouseHighResHScroll((short)(event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120));
                }

                if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                }

                // Mouse secondary or stylus primary is right click (stylus down is left click)
                if ((changedButtons & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                    }
                }

                // Mouse tertiary or stylus secondary is middle click
                if ((changedButtons & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                }

                if (prefConfig.mouseNavButtons) {
                    if ((changedButtons & MotionEvent.BUTTON_BACK) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_BACK) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1);
                        }
                        else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
                        }
                    }

                    if ((changedButtons & MotionEvent.BUTTON_FORWARD) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_FORWARD) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2);
                        }
                        else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2);
                        }
                    }
                }

                // Handle stylus presses
                if (event.getPointerCount() == 1 && event.getActionIndex() == 0) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);

                            // Stylus is left click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);

                            // Eraser is right click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    }
                    else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);

                            // Stylus is left click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);

                            // Eraser is right click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    }
                }

                lastButtonState = buttonState;
            }
            // This case is for fingers
            else
            {
                if (virtualController != null &&
                        (virtualController.getControllerMode() == VirtualController.ControllerMode.MoveButtons ||
                         virtualController.getControllerMode() == VirtualController.ControllerMode.ResizeButtons)) {
                    // Ignore presses when the virtual controller is being configured
                    return true;
                }

                // If this is the parent view, we'll offset our coordinates to appear as if they
                // are relative to the StreamView like our StreamView touch events are.
                float xOffset, yOffset;
                if (view != streamView && !prefConfig.touchscreenTrackpad) {
                    xOffset = -streamView.getX();
                    yOffset = -streamView.getY();
                }
                else {
                    xOffset = 0.f;
                    yOffset = 0.f;
                }

                int actionIndex = event.getActionIndex();

                int eventX = (int)(event.getX(actionIndex) + xOffset);
                int eventY = (int)(event.getY(actionIndex) + yOffset);

                // Special handling for 3 finger gesture
                if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN &&
                        event.getPointerCount() == 3) {
                    // Three fingers down
                    threeFingerDownTime = event.getEventTime();

                    // Cancel the first and second touches to avoid
                    // erroneous events
                    for (TouchContext aTouchContext : touchContextMap) {
                        aTouchContext.cancelTouch();
                    }

                    return true;
                }

                // TODO: Re-enable native touch when have a better solution for handling
                // cancelled touches from Android gestures and 3 finger taps to activate
                // the overlay menu.
                /*if (!prefConfig.touchscreenTrackpad && trySendTouchEvent(view, event)) {
                    // If this host supports touch events and absolute touch is enabled,
                    // send it directly as a touch event.
                    return true;
                }*/

                TouchContext context = getTouchContext(actionIndex);
                if (context == null) {
                    return false;
                }

                switch (event.getActionMasked())
                {
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_DOWN:
                    for (TouchContext touchContext : touchContextMap) {
                        touchContext.setPointerCount(event.getPointerCount());
                    }
                    context.touchDownEvent(eventX, eventY, event.getEventTime(), true);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                    if (event.getPointerCount() == 1 &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event.getFlags() & MotionEvent.FLAG_CANCELED) == 0)) {
                        // All fingers up
                        if (event.getEventTime() - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                            // This is a 3 finger tap to bring up the overlay menu
                            runOnUiThread(this::showOverlayMenuWithBattery);
                            return true;
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                        context.cancelTouch();
                    }
                    else {
                        context.touchUpEvent(eventX, eventY, event.getEventTime());
                    }

                    for (TouchContext touchContext : touchContextMap) {
                        touchContext.setPointerCount(event.getPointerCount() - 1);
                    }
                    if (actionIndex == 0 && event.getPointerCount() > 1 && !context.isCancelled()) {
                        // The original secondary touch now becomes primary
                        context.touchDownEvent(
                                (int)(event.getX(1) + xOffset),
                                (int)(event.getY(1) + yOffset),
                                event.getEventTime(), false);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    // ACTION_MOVE is special because it always has actionIndex == 0
                    // We'll call the move handlers for all indexes manually

                    // First process the historical events
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        for (TouchContext aTouchContextMap : touchContextMap) {
                            if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                            {
                                aTouchContextMap.touchMoveEvent(
                                        (int)(event.getHistoricalX(aTouchContextMap.getActionIndex(), i) + xOffset),
                                        (int)(event.getHistoricalY(aTouchContextMap.getActionIndex(), i) + yOffset),
                                        event.getHistoricalEventTime(i));
                            }
                        }
                    }

                    // Now process the current values
                    for (TouchContext aTouchContextMap : touchContextMap) {
                        if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                        {
                            aTouchContextMap.touchMoveEvent(
                                    (int)(event.getX(aTouchContextMap.getActionIndex()) + xOffset),
                                    (int)(event.getY(aTouchContextMap.getActionIndex()) + yOffset),
                                    event.getEventTime());
                        }
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    for (TouchContext aTouchContext : touchContextMap) {
                        aTouchContext.cancelTouch();
                        aTouchContext.setPointerCount(0);
                    }
                    break;
                default:
                    return false;
                }
            }

            // Handled a known source
            return true;
        }

        // Unknown class
        return false;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isTransitionInputBlocked()) return handleMotionEvent(null, event);
        // If overlay menu is visible, route all motion events to it
        if (overlayMenuView != null && overlayMenuView.getVisibility() == View.VISIBLE) {
            return overlayMenuView.onGenericMotionEvent(event);
        }

        if (isImeVisible && isAndroidTV) {
            return super.onGenericMotionEvent(event);
        }

        return handleMotionEvent(null, event) || super.onGenericMotionEvent(event);
    }

    private void updateMousePosition(View touchedView, MotionEvent event) {
        // X and Y are already relative to the provided view object
        float eventX, eventY;

        // For our StreamView itself, we can use the coordinates unmodified.
        if (touchedView == streamView) {
            eventX = event.getX(0);
            eventY = event.getY(0);
        }
        else {
            // For the containing background view, we must subtract the origin
            // of the StreamView to get video-relative coordinates.
            eventX = event.getX(0) - streamView.getX();
            eventY = event.getY(0) - streamView.getY();
        }

        if (event.getPointerCount() == 1 && event.getActionIndex() == 0 &&
                (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS))
        {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_EXIT:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (event.getEventTime() - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchUpX, 2) + Math.pow(eventY - lastAbsTouchUpY, 2)) <= STYLUS_UP_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch up and hover or touch down to allow more precise double-clicking
                        return;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (event.getEventTime() - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchDownX, 2) + Math.pow(eventY - lastAbsTouchDownY, 2)) <= STYLUS_DOWN_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch down and move or touch up to allow more precise double-clicking
                        return;
                    }
                    break;
            }
        }

        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = Math.min(Math.max(eventX, 0), streamView.getWidth());
        eventY = Math.min(Math.max(eventY, 0), streamView.getHeight());

        conn.sendMousePosition((short)eventX, (short)eventY, (short)streamView.getWidth(), (short)streamView.getHeight());
    }

    @Override
    public boolean onGenericMotion(View view, MotionEvent event) {
        if (isImeVisible && isAndroidTV) {
            return false;
        }
        return handleMotionEvent(view, event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (isTransitionInputBlocked()) return true;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // Tell the OS not to buffer input events for us
            //
            // NB: This is still needed even when we call the newer requestUnbufferedDispatch()!
            view.requestUnbufferedDispatch(event);

            // Check if touch is on the left edge to show brightness slider
            if (brightnessSliderView.isTouchInActivationZone(event.getX())) {
                brightnessSliderView.show();
                return true; // Consume the touch event to prevent game input
            }
        }

        return handleMotionEvent(view, event);
    }

    @Override
    public void stageStarting(final String stage) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (consoleLoadingView != null) {
                    consoleLoadingView.setStage(stage);
                }
                if (spinner != null) {
                    spinner.setMessage(getResources().getString(R.string.conn_starting) + " " + stage);
                }
            }
        });
    }

    @Override
    public void stageComplete(String stage) {
    }

    private void stopConnection() {
        stopConnection(null);
    }

    private boolean canParkBackgroundStream() {
        if (BackgroundStreamPreferences.readMinutes(this) == 0 || !connected
                || userInitiatedDisconnect || isFinishing()
                || streamHomeVisible || backgroundStreamParked) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureMode();
    }

    private void parkBackgroundStream() {
        if (backgroundStreamParked || switchableVideoRenderer == null) return;
        backgroundStreamParked = true;
        switchableVideoRenderer.detach();
        decoderRenderer = null;
        if (streamAudioRenderer != null) streamAudioRenderer.setVolume(0f);
        if (controllerHandler != null) controllerHandler.disableSensors();
        setInputGrabState(false);
        int retentionMinutes = BackgroundStreamPreferences.readMinutes(this);
        SessionResumeManager.save(this, getIntent(), streamSessionId);
        RetainedStreamSessionCoordinator.markParked(streamSessionId);
        BackgroundStreamService.park(this, streamSessionId, retentionMinutes);
        LimeLog.info("Background stream parked; retention minutes=" + retentionMinutes);
    }

    @Override
    public boolean isRetainedTransportLive() {
        return connected && !userInitiatedDisconnect;
    }

    @Override
    public boolean parkRetainedTransport() {
        if (backgroundStreamParked) return true;
        if (!connected || userInitiatedDisconnect
                || BackgroundStreamPreferences.readMinutes(this) == 0) {
            return false;
        }
        parkBackgroundStream();
        return backgroundStreamParked;
    }

    @Override
    public boolean parkPreparingTransport(
            RetainedStreamSessionCoordinator.Snapshot preparing) {
        if (!isExactAutoWarmUpPreparing(preparing)) return false;
        synchronized (autoWarmUpGateLock) {
            if (!autoWarmUpHomeFrameAccepted.get()) {
                return cancelUnstartedAutoWarmUp(true);
            }
        }
        if (BackgroundStreamPreferences.readMinutes(this) > 0) {
            parkWarmUpWhenConnected = true;
            return true;
        }
        if (!autoWarmUpTransportStopRequested.compareAndSet(false, true)) return true;
        boolean marked = markAutoWarmUpReconnectRequired("background_park_disabled");
        if (marked) {
            SessionResumeManager.save(this, getIntent(), streamSessionId);
            BackgroundStreamService.transportLost(this, streamSessionId);
        }
        stopConnection(() -> finish());
        return true;
    }

    @Override
    public boolean cancelPreparingTransport(
            RetainedStreamSessionCoordinator.Snapshot preparing) {
        if (!isExactAutoWarmUpPreparing(preparing)) return false;
        synchronized (autoWarmUpGateLock) {
            if (!autoWarmUpHomeFrameAccepted.get()) {
                return cancelUnstartedAutoWarmUp(true);
            }
        }
        if (!autoWarmUpTransportStopRequested.compareAndSet(false, true)) return true;
        boolean converted = autoWarmUpConvertedToGame;
        if (!markAutoWarmUpReconnectRequired("preparing_cancelled")) return false;
        SessionResumeManager.save(this, getIntent(), streamSessionId);
        BackgroundStreamService.transportLost(this, streamSessionId);
        runOnUiThread(() -> {
            if (converted && transitionCoordinator != null) transitionCoordinator.cancel();
            stopConnection(() -> finish());
        });
        return true;
    }

    @Override
    public boolean preparingHomeFrameSubmitted(
            RetainedStreamSessionCoordinator.Snapshot preparing) {
        synchronized (autoWarmUpGateLock) {
            if (!isExactAutoWarmUpPreparing(preparing)
                    || autoWarmUpTransportStopRequested.get()
                    || isFinishing() || isDestroyed()
                    || !autoWarmUpHomeFrameAccepted.compareAndSet(false, true)) {
                return false;
            }
            transitionController.overlayRendered(transitionSpec.id);
            logLaunchMilestone("opaque-home-frame-submitted");
            transitionCoordinator.start();
            startConnectionIfReady(streamView.getHolder());
            return true;
        }
    }

    @Override
    public void terminateRetainedSession(
            RetainedStreamSessionCoordinator.TerminationCallback completion) {
        terminateWholeSessionVerified(completion == null
                ? null : completion::complete);
    }

    @Override
    public void switchGame(RetainedStreamSessionCoordinator.SwitchRequest request,
                           RetainedStreamSessionCoordinator.SwitchCallback completion) {
        runOnUiThread(() -> beginRetainedGameSwitch(request, completion));
    }

    private void beginRetainedGameSwitch(
            RetainedStreamSessionCoordinator.SwitchRequest request,
            RetainedStreamSessionCoordinator.SwitchCallback completion) {
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        boolean liveExact = retainedSwitch == null && connected && !backgroundStreamParked
                && retained.state == RetainedStreamSessionCoordinator.State.HOME_LIVE
                && streamSessionId.equals(request.streamSessionId)
                && retained.streamSessionId.equals(request.streamSessionId)
                && request.hostId.equalsIgnoreCase(transitionSpec.hostId)
                && request.appId == transitionSpec.sunshineAppId
                && request.oldGameId.equalsIgnoreCase(transitionSpec.playniteGameId);
        boolean preparingExact = retainedSwitch == null
                && isExactPreparingSwitchRequest(request, retained);
        if ((!liveExact && !preparingExact) || transitionCoordinator == null) {
            LimeLog.warning("Retained switch rejected transition="
                    + (transitionSpec == null ? "" : transitionSpec.id)
                    + " reason=not_eligible live=" + liveExact
                    + " preparing=" + preparingExact
                    + " coordinator=" + (transitionCoordinator != null));
            completion.complete(RetainedStreamSessionCoordinator.SwitchOutcome.FAILED,
                    "retained_switch_not_eligible");
            return;
        }
        if (request.cancelled.getAsBoolean()) {
            completion.complete(RetainedStreamSessionCoordinator.SwitchOutcome.CANCELLED, "");
            return;
        }
        RetainedSwitch operation = new RetainedSwitch(
                request, completion, transitionSpec, transitionCoordinator);
        retainedSwitch = operation;
        cancelPendingAutomaticReveal();
        manualRevealRequested = false;
        consoleLoadingView.showOpaque();
        consoleLoadingView.doAfterNextFrame(() -> beginRetainedProviderSwitch(operation));
    }

    private void beginRetainedProviderSwitch(RetainedSwitch operation) {
        if (!isCurrentRetainedSwitch(operation)) return;
        if (operation.request.cancelled.getAsBoolean()) {
            completeRetainedSwitch(operation,
                    RetainedStreamSessionCoordinator.SwitchOutcome.CANCELLED, "");
            return;
        }
        if (operation.request.preparing
                && operation.oldSpec.type == LaunchTransitionType.GENERIC) {
            operation.oldCoordinator.close();
            beginNewRetainedGameAttempt(operation);
            return;
        }
        if (!operation.request.preparing
                && operation.oldSpec.type == LaunchTransitionType.GENERIC
                && operation.request.oldGameId.isEmpty()) {
            operation.oldCoordinator.close();
            new Thread(() -> performRetainedProviderSwitch(operation),
                    "MoonWaker-SwitchProviderGame").start();
            return;
        }
        if (operation.request.preparing
                && operation.oldSpec.type == LaunchTransitionType.GAME_CONNECTION
                && !operation.request.oldGameId.isEmpty()
                && operation.request.oldGameId.equalsIgnoreCase(
                operation.request.newGameId)) {
            adoptPreparingObservedGame(operation);
            return;
        }
        if (!operation.oldCoordinator.detachForSwitch()) {
            LimeLog.warning("Retained switch rejected transition="
                    + operation.oldSpec.id + " reason=provider_owner_changed");
            completeRetainedSwitch(operation,
                    RetainedStreamSessionCoordinator.SwitchOutcome.FAILED,
                    "retained_switch_owner_changed");
            return;
        }
        new Thread(() -> performRetainedProviderSwitch(operation),
                "MoonWaker-SwitchProviderGame").start();
    }

    private void performRetainedProviderSwitch(RetainedSwitch operation) {
        try {
            PlayniteTransitionGateway gateway = PlayniteTransitionGateway.connect(
                    this, operation.request.hostId,
                    getIntent().getStringExtra(EXTRA_HOST));
            if (gateway == null) throw new IOException("gateway_unavailable");
            PlayniteTransitionGateway.Snapshot current = gateway.snapshot();
            String exactGame = operation.request.oldGameId.isEmpty()
                    ? ("idle".equalsIgnoreCase(current.gameState) ? "" : null)
                    : PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                    operation.request.oldGameId, current.gameState, current.gameId);
            if (exactGame == null) {
                throw new IOException("retained_switch_current_mismatch");
            }
            if (!exactGame.isEmpty() && operation.request.cancelled.getAsBoolean()) {
                runOnUiThread(() -> recoverRetainedSwitch(operation,
                        RetainedStreamSessionCoordinator.SwitchOutcome.CANCELLED, ""));
                return;
            }
            if (!exactGame.isEmpty()) gateway.stopGame(exactGame);
            runOnUiThread(() -> afterRetainedGameStopped(operation));
        } catch (IOException | RuntimeException error) {
            String reason = error.getMessage() == null ? "retained_switch_failed"
                    : error.getMessage();
            runOnUiThread(() -> recoverRetainedSwitch(operation,
                    RetainedStreamSessionCoordinator.SwitchOutcome.FAILED, reason));
        }
    }

    private void recoverRetainedSwitch(RetainedSwitch operation,
                                       RetainedStreamSessionCoordinator.SwitchOutcome outcome,
                                       String error) {
        if (!isCurrentRetainedSwitch(operation)) return;
        LaunchTransitionSpec recovery = LaunchTransitionSpec.create(
                operation.request.hostId, LaunchTransitionType.GAME_CONNECTION,
                operation.request.appId, operation.request.oldGameId,
                System.currentTimeMillis());
        if (operation.request.preparing && !advancePreparingSwitch(
                operation, operation.request.oldGameId, operation.request.oldGameId,
                recovery.id)) {
            abortStalePreparingSwitch(operation);
            return;
        }
        transitionSpec = recovery;
        updateRetainedTransitionIntent(recovery, operation.request.oldGameId,
                operation.request.oldGameId.isEmpty()
                        ? operation.request.streamTargetName : appName,
                operation.request.oldGameId.isEmpty() ? ""
                        : getIntent().getStringExtra(EXTRA_CONSOLE_LOADING_ARTWORK));
        streamEverRevealed = false;
        cancelPendingAutomaticReveal();
        manualRevealRequested = false;
        transitionCancelInFlight = false;
        lastTransitionOverlayVisible = true;
        lastTransitionRevealAuthorized = false;
        consoleLoadingView.showFullTransitionAppearance();
        transitionController.begin(recovery);
        if (operation.request.preparing) {
            autoWarmUpConvertedToGame = false;
        }
        transitionController.overlayRendered(recovery.id);
        if (surfaceCreated && streamView.getHolder().getSurface() != null
                && streamView.getHolder().getSurface().isValid()) {
            transitionController.surfaceReady(recovery.id);
        }
        if (controllerHandler != null) transitionController.inputPipelineReady(recovery.id);
        ConsoleStreamTransitionCoordinator recoveryCoordinator = createTransitionCoordinator();
        if (!recoveryCoordinator.adoptDetachedProviderOwnership()) {
            recoveryCoordinator.close();
            completeRetainedSwitch(operation,
                    RetainedStreamSessionCoordinator.SwitchOutcome.FAILED,
                    "retained_switch_owner_changed");
            return;
        }
        operation.oldCoordinator.close();
        transitionCoordinator = recoveryCoordinator;
        transitionCoordinator.start();
        if (connected) {
            transitionController.streamConnected(recovery.id);
            transitionCoordinator.onStreamConnected();
        }
        completeRetainedSwitch(operation, outcome, error);
    }

    private void afterRetainedGameStopped(RetainedSwitch operation) {
        if (!isCurrentRetainedSwitch(operation)) return;
        operation.oldCoordinator.close();
        if (!updateRetainedGame(operation, operation.request.oldGameId, "")) {
            completeRetainedSwitch(operation,
                    RetainedStreamSessionCoordinator.SwitchOutcome.FAILED,
                    "retained_switch_session_changed");
            return;
        }
        settleNeutralRetainedStream(operation);
        if (operation.request.cancelled.getAsBoolean()) {
            completeRetainedSwitch(operation,
                    RetainedStreamSessionCoordinator.SwitchOutcome.CANCELLED, "");
            return;
        }
        beginNewRetainedGameAttempt(operation);
    }

    private void beginNewRetainedGameAttempt(RetainedSwitch operation) {
        if (operation.request.cancelled.getAsBoolean()) {
            completeRetainedSwitch(operation,
                    RetainedStreamSessionCoordinator.SwitchOutcome.CANCELLED, "");
            return;
        }
        if (transitionCoordinator != null) transitionCoordinator.close();
        providerStartRejectedTransitionId = "";
        LaunchTransitionSpec next = LaunchTransitionSpec.create(
                operation.request.hostId, LaunchTransitionType.GAME,
                operation.request.appId, operation.request.newGameId,
                System.currentTimeMillis());
        if (operation.request.preparing && !advancePreparingSwitch(
                operation, "", "", next.id)) {
            abortStalePreparingSwitch(operation);
            return;
        }
        ConsoleStreamTransitionCoordinator nextCoordinator =
                createTransitionCoordinator(next);
        consoleLoadingView.showOpaque();
        consoleLoadingView.doAfterNextFrame(() -> {
            if (!isCurrentRetainedSwitch(operation)
                    || operation.request.cancelled.getAsBoolean()) {
                nextCoordinator.close();
                if (isCurrentRetainedSwitch(operation)) {
                    recoverRetainedSwitch(operation,
                            RetainedStreamSessionCoordinator.SwitchOutcome.CANCELLED, "");
                }
                return;
            }
            applyRetainedTransition(operation, next, nextCoordinator);
            armNextVideoFrameForTransition(next.id, "retained-game-attempt");
            transitionCoordinator.start();
            if (connected) {
                transitionController.streamConnected(next.id);
                transitionCoordinator.onStreamConnected();
            }
            pollRetainedSwitchCancellation(operation);
        });
    }

    private void pollRetainedSwitchCancellation(RetainedSwitch operation) {
        transitionUiHandler.postDelayed(() -> {
            if (!isCurrentRetainedSwitch(operation) || operation.completed.get()) return;
            if (shouldHonorExternalSwitchCancellation(
                    operation.consoleSignalled.get(),
                    operation.request.cancelled.getAsBoolean())) {
                requestRetainedSwitchCancellation(operation);
            } else {
                pollRetainedSwitchCancellation(operation);
            }
        }, 100L);
    }

    private void adoptPreparingObservedGame(RetainedSwitch operation) {
        if (operation.request.cancelled.getAsBoolean()) {
            completeRetainedSwitch(operation,
                    RetainedStreamSessionCoordinator.SwitchOutcome.CANCELLED, "");
            return;
        }
        LaunchTransitionSpec next = LaunchTransitionSpec.create(
                operation.request.hostId, LaunchTransitionType.GAME_CONNECTION,
                operation.request.appId, operation.request.newGameId,
                System.currentTimeMillis());
        ConsoleStreamTransitionCoordinator replacement =
                createTransitionCoordinator(next);
        if (!advancePreparingSwitch(operation, operation.request.oldGameId,
                operation.request.oldGameId, next.id)) {
            replacement.close();
            abortStalePreparingSwitch(operation);
            return;
        }
        if (!operation.oldCoordinator.transferProviderOwnershipTo(replacement)) {
            boolean restored = RetainedStreamSessionCoordinator.updatePreparingSwitch(
                    this, operation.request, next.id, operation.request.oldGameId,
                    operation.request.oldGameId, operation.request.transitionId);
            if (!restored) {
                replacement.close();
                abortStalePreparingSwitch(operation);
                return;
            }
            replacement.close();
            completeRetainedSwitch(operation,
                    RetainedStreamSessionCoordinator.SwitchOutcome.FAILED,
                    "retained_switch_owner_changed");
            return;
        }
        operation.oldCoordinator.close();
        applyRetainedTransition(operation, next, replacement);
        replacement.start();
        signalRetainedSwitchReuse(operation);
        pollRetainedSwitchCancellation(operation);
    }

    private void applyRetainedTransition(
            RetainedSwitch operation, LaunchTransitionSpec next,
            ConsoleStreamTransitionCoordinator coordinator) {
        consoleLoadingView.showFullTransitionAppearance();
        operation.newSpec = next;
        transitionSpec = next;
        updateRetainedTransitionIntent(next, operation.request.newGameId,
                operation.request.newGameName, operation.request.artworkPath);
        streamEverRevealed = false;
        cancelPendingAutomaticReveal();
        manualRevealRequested = false;
        transitionCancelInFlight = false;
        providerStopInFlight = false;
        providerStopConfirmed = false;
        lastTransitionOverlayVisible = true;
        lastTransitionRevealAuthorized = false;
        transitionController.begin(next);
        if (operation.request.preparing) autoWarmUpConvertedToGame = true;
        else if (hasAutoWarmUpTransportIdentity()) clearAutoWarmUpIdentity();
        transitionController.overlayRendered(next.id);
        if (surfaceCreated && streamView.getHolder().getSurface() != null
                && streamView.getHolder().getSurface().isValid()) {
            transitionController.surfaceReady(next.id);
        }
        if (controllerHandler != null) transitionController.inputPipelineReady(next.id);
        transitionCoordinator = coordinator;
        operation.newCoordinator = coordinator;
    }

    private boolean advancePreparingSwitch(RetainedSwitch operation,
                                            String expectedGameId,
                                            String newGameId,
                                            String newTransitionId) {
        RetainedStreamSessionCoordinator.Snapshot preparing =
                RetainedStreamSessionCoordinator.snapshot();
        if (!isExactAutoWarmUpPreparing(preparing)) return false;
        if (!RetainedStreamSessionCoordinator.updatePreparingSwitch(
                this, operation.request, preparing.transitionId, expectedGameId,
                newGameId, newTransitionId)) return false;
        return true;
    }

    private void abortStalePreparingSwitch(RetainedSwitch operation) {
        completeRetainedSwitch(operation,
                RetainedStreamSessionCoordinator.SwitchOutcome.FAILED,
                "retained_switch_session_changed");
        if (autoWarmUpTransportStopRequested.compareAndSet(false, true)) {
            stopConnection(() -> finish());
        }
    }

    private void requestRetainedSwitchCancellation(RetainedSwitch operation) {
        if (!isCurrentRetainedSwitch(operation) || operation.cancelRequested) return;
        operation.cancelRequested = true;
        if (operation.newSpec != null) transitionController.cancel(operation.newSpec.id);
        if (operation.newCoordinator != null) operation.newCoordinator.cancel();
    }

    private void settleNeutralRetainedStream(RetainedSwitch operation) {
        settleNeutralRetainedStream(operation.request.hostId,
                operation.request.appId, operation.request.streamTargetName);
    }

    private void settleNeutralRetainedStream(String hostId, int appId,
                                             String streamTargetName) {
        boolean parked = backgroundStreamParked;
        if (transitionCoordinator != null) transitionCoordinator.close();
        LaunchTransitionSpec neutral = LaunchTransitionSpec.create(
                hostId, LaunchTransitionType.GAME_CONNECTION,
                appId, "", System.currentTimeMillis());
        transitionSpec = neutral;
        updateRetainedTransitionIntent(neutral, "",
                streamTargetName, "");
        streamEverRevealed = false;
        cancelPendingAutomaticReveal();
        manualRevealRequested = false;
        transitionCancelInFlight = false;
        lastTransitionOverlayVisible = true;
        lastTransitionRevealAuthorized = false;
        transitionController.begin(neutral);
        transitionController.overlayRendered(neutral.id);
        if (!parked && surfaceCreated && streamView.getHolder().getSurface() != null
                && streamView.getHolder().getSurface().isValid()) {
            transitionController.surfaceReady(neutral.id);
        }
        if (!parked && controllerHandler != null) {
            transitionController.inputPipelineReady(neutral.id);
        }
        if (connected) transitionController.streamConnected(neutral.id);
        transitionCoordinator = createTransitionCoordinator();
        transitionCoordinator.start();
        if (connected) transitionCoordinator.onStreamConnected();
        if (parked) {
            SessionResumeManager.save(this, getIntent(), streamSessionId);
        } else {
            SessionResumeManager.saveActive(this, getIntent(), streamSessionId);
        }
    }

    private void retainNeutralStreamAfterNaturalGameStop(
            String transitionId, String gameId) {
        String targetName = getIntent().getStringExtra(EXTRA_STREAM_TARGET_NAME);
        if (transitionSpec == null || !transitionSpec.id.equals(transitionId)
                || !transitionSpec.playniteGameId.equalsIgnoreCase(gameId)) {
            LimeLog.info("Neutral retain stale expectedTransition=" + transitionId
                    + " currentTransition="
                    + (transitionSpec == null ? "" : transitionSpec.id));
            return;
        }
        if (!backgroundStreamParked) consoleLoadingView.showOpaque();
        if (!connected || userInitiatedDisconnect
                || !getIntent().getBooleanExtra(EXTRA_NEUTRAL_STREAM_TARGET, false)
                || targetName == null || targetName.trim().isEmpty()) {
            LimeLog.warning("Neutral retain failed transition=" + transitionId
                    + " reason=transport_or_target");
            closeStreamWithPrivacy(false);
            return;
        }
        Runnable settle = () -> {
            if (transitionSpec == null || !transitionSpec.id.equals(transitionId)
                    || !transitionSpec.playniteGameId.equalsIgnoreCase(gameId)) {
                LimeLog.info("Neutral retain stale after frame expectedTransition="
                        + transitionId + " currentTransition="
                        + (transitionSpec == null ? "" : transitionSpec.id));
                return;
            }
            if (transitionCoordinator == null
                    || !transitionCoordinator.detachAfterConfirmedGameStop()) {
                LimeLog.warning("Neutral retain failed transition=" + transitionId
                        + " reason=provider_owner");
                closeStreamWithPrivacy(false);
                return;
            }
            if (!RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                    this, streamSessionId, transitionSpec.hostId,
                    transitionSpec.sunshineAppId, gameId)) {
                LimeLog.warning("Neutral retain failed transition=" + transitionId
                        + " reason=retained_session");
                closeStreamWithPrivacy(false);
                return;
            }
            settleNeutralRetainedStream(transitionSpec.hostId,
                    transitionSpec.sunshineAppId, targetName);
            LimeLog.info("Neutral retain success transition=" + transitionId
                    + " session=" + streamSessionId);
            if (!backgroundStreamParked && !streamHomeVisible) openConsoleHome();
        };
        if (backgroundStreamParked) settle.run();
        else consoleLoadingView.doAfterNextFrame(settle);
    }

    private boolean rearmFailedNewGameObservation(RetainedSwitch operation,
                                                   String gameId) {
        if (operation.newCoordinator == null) return false;
        operation.newCoordinator.detachForSwitch();
        LaunchTransitionSpec observation = LaunchTransitionSpec.create(
                operation.request.hostId, LaunchTransitionType.GAME_CONNECTION,
                operation.request.appId, gameId, System.currentTimeMillis());
        transitionSpec = observation;
        updateRetainedTransitionIntent(observation, gameId,
                operation.request.newGameName, operation.request.artworkPath);
        streamEverRevealed = false;
        cancelPendingAutomaticReveal();
        manualRevealRequested = false;
        transitionCancelInFlight = false;
        lastTransitionOverlayVisible = true;
        lastTransitionRevealAuthorized = false;
        consoleLoadingView.showFullTransitionAppearance();
        transitionController.begin(observation);
        transitionController.overlayRendered(observation.id);
        if (surfaceCreated && streamView.getHolder().getSurface() != null
                && streamView.getHolder().getSurface().isValid()) {
            transitionController.surfaceReady(observation.id);
        }
        if (controllerHandler != null) transitionController.inputPipelineReady(observation.id);
        ConsoleStreamTransitionCoordinator observer = createTransitionCoordinator();
        if (!observer.adoptDetachedProviderOwnership()) {
            observer.close();
            return false;
        }
        operation.newCoordinator.close();
        transitionCoordinator = observer;
        transitionCoordinator.start();
        if (connected) {
            transitionController.streamConnected(observation.id);
            transitionCoordinator.onStreamConnected();
        }
        transitionController.error(observation.id,
                getString(R.string.transition_readiness_unconfirmed));
        SessionResumeManager.saveActive(this, getIntent(), streamSessionId);
        return true;
    }

    private void updateRetainedTransitionIntent(LaunchTransitionSpec spec, String gameId,
                                                String title, String artworkPath) {
        getIntent().putExtra(EXTRA_TRANSITION_ID, spec.id);
        getIntent().putExtra(EXTRA_TRANSITION_TYPE, spec.type.name());
        getIntent().putExtra(EXTRA_TRANSITION_HOST_ID, spec.hostId);
        getIntent().putExtra(EXTRA_TRANSITION_PLAYNITE_GAME_ID, gameId);
        getIntent().putExtra(EXTRA_TRANSITION_CREATED_AT, spec.createdAtMillis);
        getIntent().putExtra(EXTRA_TRANSITION_START_BEFORE_STREAM,
                spec.startProviderBeforeStream);
        if (title != null && !title.trim().isEmpty()) {
            appName = title.trim();
            getIntent().putExtra(EXTRA_APP_NAME, appName);
            consoleLoadingView.setTitle(appName);
        }
        if (artworkPath != null && !artworkPath.trim().isEmpty()) {
            getIntent().putExtra(EXTRA_CONSOLE_LOADING_ARTWORK, artworkPath.trim());
            consoleLoadingView.setSplashArtwork(artworkPath);
        } else {
            getIntent().removeExtra(EXTRA_CONSOLE_LOADING_ARTWORK);
            consoleLoadingView.setSplashArtwork("");
        }
    }

    private boolean isCurrentRetainedSwitch(RetainedSwitch operation) {
        return retainedSwitch == operation && !operation.completed.get()
                && !isFinishing() && !isDestroyed();
    }

    private boolean isExactPreparingSwitchRequest(
            RetainedStreamSessionCoordinator.SwitchRequest request,
            RetainedStreamSessionCoordinator.Snapshot retained) {
        return request.preparing
                && retained.state == RetainedStreamSessionCoordinator.State.PREPARING
                && streamSessionId.equals(request.streamSessionId)
                && retained.streamSessionId.equals(request.streamSessionId)
                && request.hostId.equalsIgnoreCase(retained.hostId)
                && request.hostId.equalsIgnoreCase(transitionSpec.hostId)
                && request.appId == retained.appId
                && request.appId == transitionSpec.sunshineAppId
                && request.oldGameId.equalsIgnoreCase(retained.playniteGameId)
                && request.oldGameId.equalsIgnoreCase(transitionSpec.playniteGameId)
                && request.transitionId.equals(retained.transitionId)
                && request.attempt == retained.attempt
                && request.attempt == autoWarmUpAttempt
                && RetainedStreamSessionCoordinator.isPreparing(
                this, request.streamSessionId, request.hostId, request.appId,
                request.transitionId, request.attempt);
    }

    private boolean updateRetainedGame(RetainedSwitch operation,
                                       String expectedGameId, String newGameId) {
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        if (operation.request.preparing
                && retained.state == RetainedStreamSessionCoordinator.State.PREPARING) {
            return advancePreparingSwitch(operation, expectedGameId, newGameId,
                    retained.transitionId);
        }
        return RetainedStreamSessionCoordinator.updateOwnedSwitchGame(
                this, operation.request, expectedGameId, newGameId);
    }

    private void completeRetainedSwitch(RetainedSwitch operation,
                                        RetainedStreamSessionCoordinator.SwitchOutcome outcome,
                                        String error) {
        if (!operation.completed.compareAndSet(false, true)) return;
        if (retainedSwitch == operation) retainedSwitch = null;
        if (operation.consoleSignalled.compareAndSet(false, true)) {
            operation.completion.complete(outcome, error == null ? "" : error);
        } else {
            finishRetainedSwitchLock(operation);
        }
    }

    private void signalRetainedSwitchReuse(RetainedSwitch operation) {
        if (operation.consoleSignalled.compareAndSet(false, true)) {
            operation.completion.complete(
                    RetainedStreamSessionCoordinator.SwitchOutcome.REUSED, "");
        }
    }

    private void finishRetainedSwitch(RetainedSwitch operation) {
        if (!operation.completed.compareAndSet(false, true)) return;
        if (retainedSwitch == operation) retainedSwitch = null;
        finishRetainedSwitchLock(operation);
    }

    private void finishRetainedSwitchLock(RetainedSwitch operation) {
        if (operation.request.preparing) {
            RetainedStreamSessionCoordinator.finishSwitch(
                    operation.request.streamSessionId,
                    operation.request.transitionId,
                    operation.request.attempt, this);
        } else {
            RetainedStreamSessionCoordinator.finishSwitch(
                    operation.request.streamSessionId, this);
        }
    }

    private static final class RetainedSwitch {
        final RetainedStreamSessionCoordinator.SwitchRequest request;
        final RetainedStreamSessionCoordinator.SwitchCallback completion;
        final LaunchTransitionSpec oldSpec;
        final ConsoleStreamTransitionCoordinator oldCoordinator;
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean consoleSignalled = new AtomicBoolean();
        LaunchTransitionSpec newSpec;
        ConsoleStreamTransitionCoordinator newCoordinator;
        boolean cancelRequested;
        boolean startFailedCleanupPending;
        boolean providerStartRejected;
        boolean retryRequested;
        boolean identityConflict;

        RetainedSwitch(RetainedStreamSessionCoordinator.SwitchRequest request,
                       RetainedStreamSessionCoordinator.SwitchCallback completion,
                       LaunchTransitionSpec oldSpec,
                       ConsoleStreamTransitionCoordinator oldCoordinator) {
            this.request = request;
            this.completion = completion;
            this.oldSpec = oldSpec;
            this.oldCoordinator = oldCoordinator;
        }
    }

    private interface ProviderStopCallback {
        void complete(boolean success);
    }

    private boolean hasProviderGameTransition() {
        return transitionSpec != null && transitionSpec.playniteGameId != null
                && !transitionSpec.playniteGameId.isEmpty()
                && (transitionSpec.type == LaunchTransitionType.GAME
                || transitionSpec.type == LaunchTransitionType.GAME_CONNECTION);
    }

    private void stopProviderGame(ProviderStopCallback completion) {
        if (!hasProviderGameTransition()) {
            completion.complete(true);
            return;
        }
        String gameId = transitionSpec.playniteGameId;
        String hostId = transitionSpec.hostId;
        String activeHost = getIntent().getStringExtra(EXTRA_HOST);
        new Thread(() -> {
            boolean success = false;
            try {
                PlayniteTransitionGateway gateway = PlayniteTransitionGateway.connect(
                        Game.this, hostId, activeHost);
                if (gateway == null) {
                    LimeLog.warning("Unable to stop provider game: Gateway unavailable");
                } else {
                    gateway.stopGame(gameId);
                    success = true;
                }
            } catch (IOException | RuntimeException error) {
                LimeLog.warning("Unable to stop provider game: " + error.getMessage());
            }
            boolean stopped = success;
            runOnUiThread(() -> completion.complete(stopped));
        }, "MoonWaker-StopProviderGame").start();
    }

    private void terminateWholeSessionVerified(ProviderStopCallback completion) {
        terminateWholeSessionVerified(completion, false, true);
    }

    private void terminateWholeSessionVerified(
            ProviderStopCallback completion, boolean providerAlreadyStopped,
            boolean restoreRetainedOnFailure) {
        runOnUiThread(() -> {
            if (wholeSessionTerminationInFlight) {
                if (completion != null) completion.complete(false);
                return;
            }
            final String expectedSessionId = streamSessionId;
            final String expectedHostId = normalizeOpaqueId(
                    getIntent().getStringExtra(EXTRA_PC_UUID));
            final int expectedAppId = getIntent().getIntExtra(
                    EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
            final String expectedGameId = transitionSpec == null
                    ? "" : transitionSpec.playniteGameId;
            if (expectedSessionId.isEmpty() || expectedHostId.isEmpty()
                    || transitionSpec == null
                    || !expectedHostId.equalsIgnoreCase(transitionSpec.hostId)
                    || expectedAppId != transitionSpec.sunshineAppId) {
                LimeLog.warning("Whole-session termination rejected: missing correlation");
                if (completion != null) completion.complete(false);
                return;
            }
            wholeSessionTerminationInFlight = true;
            boolean marked = RetainedStreamSessionCoordinator.markTerminating(
                    expectedSessionId, expectedHostId, expectedAppId, expectedGameId);
            LimeLog.info("Whole-session termination requested host=" + expectedHostId
                    + " app=" + expectedAppId + " game=" + expectedGameId
                    + " marker=" + marked);
            ProviderStopCallback afterProvider = providerStopped -> {
                providerStopInFlight = false;
                providerStopConfirmed = providerStopped;
                if (!isCurrentWholeSessionTermination(
                        expectedSessionId, expectedHostId, expectedAppId)) return;
                if (!providerStopped) {
                    finishWholeSessionTermination(false, marked, completion,
                            "provider_stop_failed", expectedSessionId,
                            expectedHostId, expectedAppId, restoreRetainedOnFailure);
                    return;
                }
                if (!providerAlreadyStopped && !expectedGameId.isEmpty()) {
                    RetainedStreamSessionCoordinator.clearTerminatingGameIfMatches(
                            expectedSessionId, expectedHostId, expectedAppId,
                            expectedGameId);
                    String targetName = getIntent().getStringExtra(EXTRA_STREAM_TARGET_NAME);
                    settleNeutralRetainedStream(expectedHostId, expectedAppId,
                            targetName == null ? appName : targetName);
                }
                verifySunshineSessionStopped(expectedSessionId, expectedHostId,
                        expectedAppId, marked, completion, restoreRetainedOnFailure);
            };
            Runnable stopProvider = () -> {
                if (providerAlreadyStopped) afterProvider.complete(true);
                else stopVerifiedProviderGame(expectedHostId, expectedGameId, afterProvider);
            };
            providerStopInFlight = true;
            if (!backgroundStreamParked && !streamHomeVisible
                    && transitionController != null && consoleLoadingView != null) {
                transitionController.closingStream(transitionSpec.id);
                consoleLoadingView.showOpaque();
                consoleLoadingView.doAfterNextFrame(stopProvider);
            } else {
                stopProvider.run();
            }
        });
    }

    private void verifySunshineSessionStopped(
            String expectedSessionId, String expectedHostId, int expectedAppId,
            boolean marked, ProviderStopCallback completion,
            boolean restoreRetainedOnFailure) {
        final String host = getIntent().getStringExtra(EXTRA_HOST);
        final int port = getIntent().getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT);
        final int httpsPort = getIntent().getIntExtra(EXTRA_HTTPS_PORT, 0);
        final String uniqueId = getIntent().getStringExtra(EXTRA_UNIQUEID);
        final byte[] derCertData = getIntent().getByteArrayExtra(EXTRA_SERVER_CERT);
        new Thread(() -> {
            boolean stopped = false;
            String reason = "sunshine_state_unverified";
            try {
                X509Certificate serverCert = null;
                if (derCertData != null) {
                    serverCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                            .generateCertificate(new ByteArrayInputStream(derCertData));
                }
                NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple(host, port),
                        httpsPort, uniqueId, serverCert,
                        PlatformBinding.getCryptoProvider(this));
                ComputerDetails fresh = http.getComputerDetails(true);
                int freshAppId = fresh.runningGameId;
                LimeLog.info("Whole-session Sunshine check expectedApp=" + expectedAppId
                        + " freshApp=" + freshAppId);
                if (freshAppId == 0) {
                    stopped = true;
                    reason = "already_stopped";
                } else if (expectedAppId <= 0 || freshAppId != expectedAppId) {
                    reason = "sunshine_app_mismatch:" + freshAppId;
                } else if (!http.quitApp()) {
                    reason = "sunshine_quit_rejected";
                } else {
                    long deadline = SystemClock.uptimeMillis()
                            + WHOLE_SESSION_QUIT_TIMEOUT_MS;
                    while (SystemClock.uptimeMillis() < deadline) {
                        try {
                            freshAppId = http.getComputerDetails(true).runningGameId;
                            if (freshAppId == 0) {
                                stopped = true;
                                reason = "sunshine_stopped";
                                break;
                            }
                            if (freshAppId != expectedAppId) {
                                reason = "sunshine_app_changed:" + freshAppId;
                                break;
                            }
                        } catch (IOException readError) {
                            reason = "sunshine_poll_failed";
                        }
                        Thread.sleep(300L);
                    }
                }
            } catch (IOException | CertificateException | RuntimeException error) {
                reason = "sunshine_stop_failed:" + error.getClass().getSimpleName();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                reason = "sunshine_stop_interrupted";
            } catch (Exception error) {
                reason = "sunshine_stop_failed:" + error.getClass().getSimpleName();
            }
            boolean success = stopped;
            String terminalReason = reason;
            runOnUiThread(() -> finishWholeSessionTermination(
                    success, marked, completion, terminalReason,
                    expectedSessionId, expectedHostId, expectedAppId,
                    restoreRetainedOnFailure));
        }, "MoonWaker-TerminateSunshine").start();
    }

    private void stopVerifiedProviderGame(
            String expectedHostId, String expectedGameId,
            ProviderStopCallback completion) {
        final String activeHost = getIntent().getStringExtra(EXTRA_HOST);
        new Thread(() -> {
            boolean success = false;
            String reason = "provider_state_unverified";
            try {
                PlayniteTransitionGateway gateway = PlayniteTransitionGateway.connect(
                        Game.this, expectedHostId, activeHost);
                if (gateway == null) {
                    reason = "gateway_unavailable";
                } else {
                    PlayniteTransitionGateway.Snapshot current = gateway.snapshot();
                    String exactGame = expectedGameId.isEmpty()
                            ? ("idle".equalsIgnoreCase(current.gameState) ? "" : null)
                            : PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                            expectedGameId, current.gameState, current.gameId);
                    if (exactGame == null) {
                        reason = "provider_game_mismatch:" + current.gameState
                                + ':' + current.gameId;
                    } else {
                        if (!exactGame.isEmpty()) gateway.stopGame(exactGame);
                        success = true;
                        reason = exactGame.isEmpty()
                                ? "provider_idle" : "provider_stopped:" + exactGame;
                    }
                }
            } catch (IOException | RuntimeException error) {
                reason = "provider_stop_failed:" + error.getClass().getSimpleName();
            }
            boolean stopped = success;
            String terminalReason = reason;
            runOnUiThread(() -> {
                LimeLog.info("Whole-session provider decision host=" + expectedHostId
                        + " game=" + expectedGameId + " success=" + stopped
                        + " reason=" + terminalReason);
                if (completion != null) completion.complete(stopped);
            });
        }, "MoonWaker-VerifyProviderStop").start();
    }

    private boolean isCurrentWholeSessionTermination(
            String expectedSessionId, String expectedHostId, int expectedAppId) {
        return wholeSessionTerminationInFlight
                && expectedSessionId.equals(streamSessionId)
                && expectedHostId.equalsIgnoreCase(normalizeOpaqueId(
                getIntent().getStringExtra(EXTRA_PC_UUID)))
                && expectedAppId == getIntent().getIntExtra(
                EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
    }

    private void finishWholeSessionTermination(
            boolean success, boolean marked, ProviderStopCallback completion,
            String reason, String expectedSessionId, String expectedHostId,
            int expectedAppId, boolean restoreRetainedOnFailure) {
        if (!isCurrentWholeSessionTermination(
                expectedSessionId, expectedHostId, expectedAppId)) return;
        LimeLog.info("Whole-session termination result host=" + expectedHostId
                + " app=" + expectedAppId + " success=" + success
                + " reason=" + reason);
        if (success) {
            userInitiatedDisconnect = true;
            backgroundStreamParked = false;
            SessionResumeManager.clearIfMatches(this, expectedSessionId);
            BackgroundStreamService.resumed(this, expectedSessionId);
            if (controllerHandler != null) controllerHandler.pendingApplicationQuit = false;
            if (completion != null) completion.complete(true);
            RetainedStreamSessionCoordinator.clearIfMatches(expectedSessionId);
            finish();
            stopConnection(() -> wholeSessionTerminationInFlight = false);
            return;
        }

        wholeSessionTerminationInFlight = false;
        if (completion != null) completion.complete(false);
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        if (restoreRetainedOnFailure && marked
                && retained.state == RetainedStreamSessionCoordinator.State.TERMINATING
                && expectedSessionId.equals(retained.streamSessionId)) {
            RetainedStreamSessionCoordinator.enterHome(this, expectedSessionId,
                    expectedHostId, expectedAppId,
                    transitionSpec == null ? "" : transitionSpec.playniteGameId);
        }
        if (transitionController != null && transitionSpec != null) {
            transitionController.streamClosingFailed(transitionSpec.id,
                    getString(R.string.console_terminate_session_failed));
        }
        if (!restoreRetainedOnFailure) {
            if (marked) RetainedStreamSessionCoordinator.clearIfMatches(expectedSessionId);
            SessionResumeManager.clearIfMatches(this, expectedSessionId);
            stopConnection();
            return;
        }
        if (connected && !streamHomeVisible
                && expectedSessionId.equals(
                RetainedStreamSessionCoordinator.snapshot().streamSessionId)) {
            openConsoleHome();
        }
    }

    private void restoreParkedStream(SurfaceHolder holder) {
        if (!backgroundStreamParked || holder == null || holder.getSurface() == null
                || !holder.getSurface().isValid()) return;
        MediaCodecDecoderRenderer replacement = createDecoderRenderer();
        replacement.setRenderTarget(holder);
        if (!switchableVideoRenderer.attach(replacement)) {
            LimeLog.severe("Failed to attach a decoder to the parked stream");
            reconnectRetainedStream();
            return;
        }
        retainedRestoreAwaitingFrame = true;
        decoderRenderer = replacement;
        backgroundStreamParked = false;
        BackgroundStreamService.resumed(this, streamSessionId);
        if (streamAudioRenderer != null) streamAudioRenderer.setVolume(1f);
        if (controllerHandler != null) controllerHandler.enableSensors();
        setInputGrabState(true);
        BackgroundStreamBridge.requestIdrFrame();
        scheduleRetainedRestoreWatchdog();
        hideSystemUi(50);
        streamView.post(streamView::requestFocus);
        LimeLog.info("Background stream decoder restored");
    }

    private void scheduleRetainedRestoreWatchdog() {
        if (retainedRestoreWatchdog != null) {
            transitionUiHandler.removeCallbacks(retainedRestoreWatchdog);
        }
        transitionUiHandler.postDelayed(() -> {
            if (retainedRestoreAwaitingFrame && connected && !backgroundStreamParked) {
                LimeLog.warning("Retained stream has no frame yet; requesting another IDR");
                BackgroundStreamBridge.requestIdrFrame();
            }
        }, 1_500L);
        retainedRestoreWatchdog = () -> {
            if (retainedRestoreAwaitingFrame && connected && !backgroundStreamParked) {
                LimeLog.warning("Retained stream restore timed out; reconnecting transport");
                reconnectRetainedStream();
            }
        };
        transitionUiHandler.postDelayed(retainedRestoreWatchdog, 5_000L);
    }

    private void reconnectRetainedStream() {
        retainedRestoreAwaitingFrame = false;
        if (retainedRestoreWatchdog != null) {
            transitionUiHandler.removeCallbacks(retainedRestoreWatchdog);
            retainedRestoreWatchdog = null;
        }
        backgroundStreamParked = false;
        userInitiatedDisconnect = true;
        SessionResumeManager.save(this, getIntent(), streamSessionId);
        RetainedStreamSessionCoordinator.markReconnectRequired(streamSessionId);
        BackgroundStreamService.transportLost(this, streamSessionId);
        SessionResumeManager.PendingSession pending = SessionResumeManager.pendingSession(this);
        Intent resume = SessionResumeManager.buildResumeIntent(this, pending);
        stopConnection(() -> {
            finish();
            if (resume != null) {
                transitionUiHandler.postDelayed(() -> startActivity(resume), 100L);
            }
        });
    }

    private void endExpiredBackgroundStream() {
        if (!backgroundStreamParked) return;
        LimeLog.info("Background stream retention expired");
        backgroundStreamParked = false;
        userInitiatedDisconnect = true;
        RetainedStreamSessionCoordinator.clearIfMatches(streamSessionId);
        SessionResumeManager.clearIfMatches(this, streamSessionId);
        BackgroundStreamService.resumed(this, streamSessionId);
        stopConnection(() -> finish());
    }

    private void closeStreamWithPrivacy(boolean quitApplication) {
        if (quitApplication) {
            terminateWholeSessionVerified(null);
            return;
        }
        userInitiatedDisconnect = true;
        if (transitionController == null || consoleLoadingView == null) {
            stopConnection();
            finish();
            return;
        }
        transitionController.closingStream(transitionSpec.id);
        consoleLoadingView.showOpaque();
        consoleLoadingView.doAfterNextFrame(() -> {
            stopConnection(() -> {
                transitionController.returningToDashboard(transitionSpec.id);
                finish();
            });
        });
    }

    private void stopConnection(Runnable afterStopped) {
        if (connecting || connected) {
            connecting = connected = false;
            updatePipAutoEnter();

            controllerHandler.stop();

            // Update GameManager state to indicate we're no longer in game
            UiHelper.notifyStreamEnded(this);

            // Stop may take a few hundred ms to do some network I/O to tell
            // the server we're going away and clean up. Let it run in a separate
            // thread to keep things smooth for the UI. Inside moonlight-common,
            // we prevent another thread from starting a connection before and
            // during the process of stopping this one.
            new Thread() {
                public void run() {
                    conn.stop();
                    if (afterStopped != null) {
                        runOnUiThread(afterStopped);
                    }
                }
            }.start();

        }
        else if (afterStopped != null) {
            runOnUiThread(afterStopped);
        }
    }

    @Override
    public void stageFailed(final String stage, final int portFlags, final int errorCode) {
        if (cleanupUnrevealedProviderLaunch("stream-stage-failed:" + stage)
                && !hasAutoWarmUpTransportIdentity()) return;
        if (handleAutoWarmUpConnectionFailure()) return;
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        final int portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443, portFlags);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (consoleLoadingView != null) {
                    if (transitionController != null) {
                        String friendlyStage = consoleLoadingView.friendlyStage(stage);
                        transitionController.error(transitionSpec.id,
                                getString(R.string.conn_error_msg) + " " + friendlyStage);
                    } else {
                        consoleLoadingView.stopAndHide();
                    }
                }
                if (spinner != null) {
                    spinner.dismiss();
                    spinner = null;
                }

                if (!displayedFailureDialog) {
                    displayedFailureDialog = true;
                    LimeLog.severe(stage + " failed: " + errorCode);

                    // If video initialization failed and the surface is still valid, display extra information for the user
                    if (stage.contains("video") && streamView.getHolder().getSurface().isValid()) {
                        Toast.makeText(Game.this, getResources().getText(R.string.video_decoder_init_failed), Toast.LENGTH_LONG).show();
                    }

                    String dialogText = getResources().getString(R.string.conn_error_msg) + " " + stage +" (error "+errorCode+")";

                    if (portFlags != 0) {
                        dialogText += "\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
                                MoonBridge.stringifyPortFlags(portFlags, "\n");
                    }

                    if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0)  {
                        dialogText += "\n\n" + getResources().getString(R.string.nettest_text_blocked);
                    }

                    Dialog.displayDialog(Game.this, getResources().getString(R.string.conn_error_title), dialogText, true);
                }
            }
        });
    }

    @Override
    public void connectionTerminated(final int errorCode) {
        cancelStreamingAutopilotCalibration();
        // An intentional bitrate reconnect is completed by the callback passed to
        // stopConnection(). Do not let the normal termination path finish this Activity
        // or display a transient connection error while the old transport is stopping.
        if (bitrateReconnectPending) {
            return;
        }
        if (wholeSessionTerminationInFlight) {
            LimeLog.info("Ignoring transport termination while host stop is verified: "
                    + errorCode);
            runOnUiThread(() -> {
                if (wholeSessionTerminationInFlight) stopConnection();
            });
            return;
        }
        if (cleanupUnrevealedProviderLaunch(
                "stream-terminated-before-reveal:" + errorCode)
                && !hasAutoWarmUpTransportIdentity()) return;
        if (handleAutoWarmUpConnectionFailure()) return;

        if (hasOwnRetainedSession()) {
            runOnUiThread(() -> {
                LimeLog.warning("Parked stream transport was lost; preserving reconnect state");
                displayedFailureDialog = true;
                backgroundStreamParked = false;
                SessionResumeManager.save(Game.this, getIntent(), streamSessionId);
                RetainedStreamSessionCoordinator.markReconnectRequired(streamSessionId);
                BackgroundStreamService.transportLost(Game.this, streamSessionId);
                stopConnection(() -> finish());
            });
            return;
        }

        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        final int portFlags = MoonBridge.getPortFlagsFromTerminationErrorCode(errorCode);
        final int portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER,443, portFlags);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (transitionController != null) {
                    transitionController.closingStream(transitionSpec.id);
                }
                // Let the display go to sleep now
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                // Stop processing controller input
                controllerHandler.stop();

                // Ungrab input
                setInputGrabState(false);

                if (!displayedFailureDialog) {
                    displayedFailureDialog = true;
                    LimeLog.severe("Connection terminated: " + errorCode);
                    stopConnection();

                    // Display the error dialog if it was an unexpected termination.
                    // Otherwise, just finish the activity immediately.
                    if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
                        String message;

                        if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                            // If we got a blocked result, that supersedes any other error message
                            message = getResources().getString(R.string.nettest_text_blocked);
                        }
                        else {
                            switch (errorCode) {
                                case MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC:
                                    message = getResources().getString(R.string.no_video_received_error);
                                    break;

                                case MoonBridge.ML_ERROR_NO_VIDEO_FRAME:
                                    message = getResources().getString(R.string.no_frame_received_error);
                                    break;

                                case MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION:
                                case MoonBridge.ML_ERROR_PROTECTED_CONTENT:
                                    message = getResources().getString(R.string.early_termination_error);
                                    break;

                                case MoonBridge.ML_ERROR_FRAME_CONVERSION:
                                    message = getResources().getString(R.string.frame_conversion_error);
                                    break;

                                default:
                                    String errorCodeString;
                                    // We'll assume large errors are hex values
                                    if (Math.abs(errorCode) > 1000) {
                                        errorCodeString = Integer.toHexString(errorCode);
                                    }
                                    else {
                                        errorCodeString = Integer.toString(errorCode);
                                    }
                                    message = getResources().getString(R.string.conn_terminated_msg) + "\n\n" +
                                            getResources().getString(R.string.error_code_prefix) + " " + errorCodeString;
                                    break;
                            }
                        }

                        if (portFlags != 0) {
                            message += "\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
                                    MoonBridge.stringifyPortFlags(portFlags, "\n");
                        }

                        Dialog.displayDialog(Game.this, getResources().getString(R.string.conn_terminated_title),
                                message, true);
                    }
                    else {
                        if (transitionController != null && consoleLoadingView != null) {
                            consoleLoadingView.doAfterNextFrame(() -> {
                                transitionController.returningToDashboard(
                                        transitionSpec.id);
                                finish();
                            });
                        } else {
                            finish();
                        }
                    }
                }
            }
        });
    }

    @Override
    public void connectionStatusUpdate(final int connectionStatus) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (prefConfig.disableWarnings) {
                    return;
                }

                if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
                    if (prefConfig.bitrate > 5000) {
                        notificationOverlayView.setText(getResources().getString(R.string.slow_connection_msg));
                    }
                    else {
                        notificationOverlayView.setText(getResources().getString(R.string.poor_connection_msg));
                    }

                    requestedNotificationOverlayVisibility = View.VISIBLE;
                }
                else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY) {
                    requestedNotificationOverlayVisibility = View.GONE;
                }

                if (!isHidingOverlays) {
                    notificationOverlayView.setVisibility(requestedNotificationOverlayVisibility);
                }
            }
        });
    }

    @Override
    public void connectionStarted() {
        final boolean warmUpConnection = hasAutoWarmUpTransportIdentity();
        logLaunchMilestone("stream-connected");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (consoleLoadingView != null) {
                    if (transitionController != null) {
                        transitionController.streamConnected(transitionSpec.id);
                    } else {
                        consoleLoadingView.waitingForVideo();
                    }
                }
                if (spinner != null) {
                    spinner.dismiss();
                    spinner = null;
                }

                boolean convertedWarmUp = autoWarmUpConvertedToGame;
                if (warmUpConnection && !completeAutoWarmUpPreparing()) {
                    if (autoWarmUpTransportStopRequested.compareAndSet(false, true)) {
                        stopConnection(() -> finish());
                    }
                    return;
                }
                connected = true;
                connecting = false;
                // Register and prime pads before a cold provider launch without opening
                // the input gate. Arrival packets alone carry capabilities, not input state.
                controllerHandler.announceConnectedControllers();
                SessionResumeManager.saveActive(Game.this, getIntent(), streamSessionId);
                BackgroundStreamService.resumed(Game.this, streamSessionId);
                if (!sourceSuspendId.isEmpty()) {
                    SuspendedSessionStore.markResumedIfMatches(Game.this,
                            sourceSuspendId,
                            getIntent().getStringExtra(EXTRA_PC_UUID),
                            getIntent().getIntExtra(EXTRA_APP_ID, 0),
                            sourceSuspendPlayniteGameId, streamSessionId);
                }
                updatePipAutoEnter();

                // Hide the mouse cursor now after a short delay.
                // Doing it before dismissing the spinner seems to be undone
                // when the spinner gets displayed. On Android Q, even now
                // is too early to capture. We will delay a second to allow
                // the spinner to dismiss before capturing.
                Handler h = new Handler();
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isOwnedHiddenAutoWarmUp()) setInputGrabState(true);
                    }
                }, 500);

                // Keep the display on
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                // Update GameManager state to indicate we're in game
                UiHelper.notifyStreamConnected(Game.this);

                hideSystemUi(1000);

                // Non-console streams have no privacy transition to wait for.
                if (transitionController == null) showOverlayMenuHint();

                if (transitionCoordinator != null) transitionCoordinator.onStreamConnected();
                if (convertedWarmUp) reportSelectedGameLaunched();
                if (parkWarmUpWhenConnected) {
                    parkWarmUpWhenConnected = false;
                    parkBackgroundStream();
                }
            }
        });

        // Report this shortcut being used (off the main thread to prevent ANRs)
        ComputerDetails computer = new ComputerDetails();
        computer.name = pcName;
        computer.uuid = Game.this.getIntent().getStringExtra(EXTRA_PC_UUID);
        ShortcutHelper shortcutHelper = new ShortcutHelper(this);
        shortcutHelper.reportComputerShortcutUsed(computer);
        if (appName != null && !warmUpConnection) {
            // This may be null if launched from the "Resume Session" PC context menu item
            shortcutHelper.reportGameLaunched(computer, app);
        }
    }

    private void reportSelectedGameLaunched() {
        if (appName == null) return;
        new Thread(() -> {
            ComputerDetails computer = new ComputerDetails();
            computer.name = pcName;
            computer.uuid = getIntent().getStringExtra(EXTRA_PC_UUID);
            new ShortcutHelper(this).reportGameLaunched(computer, app);
        }, "MoonWaker-ReportGameLaunched").start();
    }

    private ConsoleStreamTransitionCoordinator createTransitionCoordinator() {
        return createTransitionCoordinator(transitionSpec);
    }

    private ConsoleStreamTransitionCoordinator createTransitionCoordinator(
            LaunchTransitionSpec spec) {
        PlayniteTransitionGateway gateway = PlayniteTransitionGateway.connect(
                this, spec.hostId, getIntent().getStringExtra(EXTRA_HOST));
        return new ConsoleStreamTransitionCoordinator(
                spec,
                transitionController,
                gateway,
                (action, delayMs) -> transitionUiHandler.postDelayed(action, delayMs),
                new ConsoleStreamTransitionCoordinator.Callbacks() {
                    @Override public void onHostGuidePolicy(
                            String transitionId, String gameId, boolean allowed) {
                        runOnUiThread(() -> {
                            if (transitionSpec == null || !transitionSpec.id.equals(transitionId)
                                    || controllerHandler == null) return;
                            updateGuidePolicyTransition();
                            controllerHandler.getGuidePolicy().observe(transitionId, gameId, allowed);
                        });
                    }

                    @Override public String gatewayUnavailableMessage() {
                        return getString(R.string.transition_gateway_unavailable);
                    }

                    @Override public String hostSessionLockedMessage() {
                        return getString(R.string.transition_host_session_locked);
                    }

                    @Override public String streamDisplayNotConfiguredMessage() {
                        return getString(R.string.transition_stream_display_not_configured);
                    }

                    @Override public String targetWrongDisplayMessage() {
                        return getString(R.string.transition_target_wrong_display);
                    }

                    @Override public String readinessUnconfirmedMessage() {
                        return getString(R.string.transition_readiness_unconfirmed);
                    }

                    @Override public String gameIdentityPendingMessage() {
                        return getString(R.string.transition_waiting_game_identity);
                    }

                    @Override public String gameWindowPendingMessage() {
                        return getString(R.string.transition_waiting_game_window);
                    }

                    @Override public String gameWindowNotFullscreenMessage() {
                        return getString(R.string.transition_game_window_not_fullscreen);
                    }

                    @Override public String gameWindowNotForegroundMessage() {
                        return getString(R.string.transition_game_window_not_foreground);
                    }

                    @Override public String launcherInteractionRequiredMessage() {
                        return getString(R.string.transition_launcher_interaction_required);
                    }

                    @Override public String windowStabilizingMessage() {
                        return getString(R.string.transition_window_stabilizing);
                    }

                    @Override public boolean isPendingInstallation(String hostId, String gameId) {
                        return Game.this.isPendingInstallation(hostId, gameId);
                    }

                    @Override public String pendingInstallationName(String hostId, String gameId) {
                        return Game.this.pendingInstallationName(hostId, gameId);
                    }

                    @Override public void onInstallationCompleted(
                            String hostId, String gameId, String gameName) {
                        runOnUiThread(() -> {
                            getSharedPreferences("console_dashboard", MODE_PRIVATE).edit()
                                    .putLong(playniteInstallNotificationKey(hostId, gameId),
                                            System.currentTimeMillis())
                                    .remove(playniteInstallPendingKey(hostId, gameId))
                                    .apply();
                            displayMessage(getString(
                                    R.string.playnite_install_complete, gameName));
                        });
                    }

                    @Override public void onInstallationCancelled(
                            String hostId, String gameId, String gameName) {
                        completeInstallationFailure(hostId, gameId, gameName,
                                R.string.playnite_install_cancelled);
                    }

                    @Override public void onInstallationFailed(
                            String hostId, String gameId, String gameName) {
                        completeInstallationFailure(hostId, gameId, gameName,
                                R.string.playnite_install_failed);
                    }

                    @Override public void onInstallationAttentionRequired(
                            String hostId, String gameId, String gameName) {
                        runOnUiThread(() -> displayMessage(getString(
                                R.string.playnite_install_attention_overlay, gameName)));
                    }

                    @Override public void onProviderGameStopped(
                            String transitionId, String gameId) {
                        runOnUiThread(() -> {
                            RetainedSwitch operation = retainedSwitch;
                            if (operation != null) {
                                if (operation.oldSpec.id.equals(transitionId)
                                        || (operation.cancelRequested
                                        && operation.newSpec != null
                                        && operation.newSpec.id.equals(transitionId))) return;
                                if (operation.newSpec != null
                                        && operation.newSpec.id.equals(transitionId)
                                        && operation.request.newGameId.equalsIgnoreCase(gameId)
                                        && isCurrentRetainedSwitch(operation)) {
                                    providerStopConfirmed = true;
                                    consoleLoadingView.showOpaque();
                                    consoleLoadingView.doAfterNextFrame(() -> {
                                        if (!isCurrentRetainedSwitch(operation)
                                                || operation.newSpec == null
                                                || !operation.newSpec.id.equals(transitionId)
                                                || !operation.request.newGameId
                                                .equalsIgnoreCase(gameId)) return;
                                        if (!updateRetainedGame(operation, gameId, "")) {
                                            completeRetainedSwitch(operation,
                                                    RetainedStreamSessionCoordinator
                                                            .SwitchOutcome.FAILED,
                                                    "retained_switch_session_changed");
                                            return;
                                        }
                                        settleNeutralRetainedStream(operation);
                                        boolean returnToDashboard =
                                                operation.consoleSignalled.get();
                                        completeRetainedSwitch(operation,
                                                RetainedStreamSessionCoordinator
                                                        .SwitchOutcome.FAILED,
                                                "provider_game_exited");
                                        if (returnToDashboard) openConsoleHome();
                                    });
                                    return;
                                }
                            }
                            if (transitionSpec == null
                                    || !transitionSpec.id.equals(transitionId)
                                    || !transitionSpec.playniteGameId.equalsIgnoreCase(gameId)) {
                                return;
                            }
                            providerStopConfirmed = true;
                            if (hasProviderGameTransition() && !providerStopInFlight
                                    && !providerEndGameInFlight
                                    && !transitionCancelInFlight
                                    && !userInitiatedDisconnect) {
                                retainNeutralStreamAfterNaturalGameStop(
                                        transitionId, gameId);
                            }
                        });
                    }

                    @Override public void onProviderGameStartAccepted(
                            String transitionId, String gameId) {
                        runOnUiThread(() -> {
                            if (transitionId.equals(providerStartRejectedTransitionId)) {
                                providerStartRejectedTransitionId = "";
                            }
                            RetainedSwitch operation = retainedSwitch;
                            if (operation == null || operation.newSpec == null
                                    || !operation.newSpec.id.equals(transitionId)
                                    || !operation.request.newGameId.equalsIgnoreCase(gameId)
                                    || !isCurrentRetainedSwitch(operation)) return;
                            if (!updateRetainedGame(operation, "", gameId)) {
                                operation.identityConflict = true;
                                requestRetainedSwitchCancellation(operation);
                                return;
                            }
                            SessionResumeManager.saveActive(
                                    Game.this, getIntent(), streamSessionId);
                            if (operation.cancelRequested
                                    || operation.request.cancelled.getAsBoolean()) {
                                requestRetainedSwitchCancellation(operation);
                                return;
                            }
                            signalRetainedSwitchReuse(operation);
                        });
                    }

                    @Override public void onProviderGameStartFailed(
                            String transitionId, String gameId,
                            ConsoleStreamTransitionCoordinator.ProviderStartFailure failure) {
                        runOnUiThread(() -> {
                            boolean interactionRequired = failure
                                    == ConsoleStreamTransitionCoordinator.ProviderStartFailure
                                    .INTERACTION_REQUIRED;
                            if (interactionRequired
                                    && isExactProviderTransition(transitionId, gameId)) {
                                providerStartRejectedTransitionId = transitionId;
                                persistRejectedProviderTransitionAsNeutral();
                            }
                            RetainedSwitch operation = retainedSwitch;
                            if (operation == null || operation.newSpec == null
                                    || !operation.newSpec.id.equals(transitionId)
                                    || !operation.request.newGameId.equalsIgnoreCase(gameId)
                                    || !isCurrentRetainedSwitch(operation)) return;
                            if (interactionRequired) {
                                operation.providerStartRejected = true;
                                signalRetainedSwitchReuse(operation);
                            } else {
                                operation.startFailedCleanupPending = true;
                            }
                        });
                    }

                    @Override public void onProviderGameCleanupComplete(
                            String transitionId, String gameId, boolean success) {
                        runOnUiThread(() -> {
                            if (isFreshOwnedFailureCleanup(transitionId, gameId)) {
                                LimeLog.info("Fresh neutral cleanup transition=" + transitionId
                                        + " providerStopped=" + success);
                                if (success) {
                                    terminateWholeSessionVerified(null, true, false);
                                } else if (transitionController != null) {
                                    transitionController.error(transitionId,
                                            getString(R.string.transition_readiness_unconfirmed));
                                }
                                return;
                            }
                            RetainedSwitch operation = retainedSwitch;
                            if (operation == null || operation.newSpec == null
                                    || !operation.newSpec.id.equals(transitionId)
                                    || !operation.request.newGameId.equalsIgnoreCase(gameId)
                                    || !isCurrentRetainedSwitch(operation)) return;
                            if (success) {
                                if (operation.identityConflict) {
                                    completeRetainedSwitch(operation,
                                            RetainedStreamSessionCoordinator.SwitchOutcome.FAILED,
                                            "retained_switch_session_changed");
                                    return;
                                }
                                if (!operation.providerStartRejected
                                        && !updateRetainedGame(operation, gameId, "")) {
                                    completeRetainedSwitch(operation,
                                            RetainedStreamSessionCoordinator.SwitchOutcome.FAILED,
                                            "retained_switch_session_changed");
                                    return;
                                }
                                settleNeutralRetainedStream(operation);
                                if (operation.retryRequested) {
                                    operation.cancelRequested = false;
                                    operation.retryRequested = false;
                                    operation.startFailedCleanupPending = false;
                                    operation.providerStartRejected = false;
                                    beginNewRetainedGameAttempt(operation);
                                    return;
                                }
                                boolean returnToDashboard = operation.consoleSignalled.get();
                                completeRetainedSwitch(operation,
                                        operation.cancelRequested
                                                ? RetainedStreamSessionCoordinator
                                                .SwitchOutcome.CANCELLED
                                                : RetainedStreamSessionCoordinator
                                                .SwitchOutcome.FAILED,
                                        operation.cancelRequested ? ""
                                                : "provider_start_failed");
                                if (returnToDashboard) openConsoleHome();
                            } else {
                                if (operation.identityConflict) {
                                    completeRetainedSwitch(operation,
                                            RetainedStreamSessionCoordinator.SwitchOutcome.FAILED,
                                            "retained_switch_session_changed");
                                    return;
                                }
                                if (!updateRetainedGame(operation, "", gameId)) {
                                    completeRetainedSwitch(operation,
                                            RetainedStreamSessionCoordinator.SwitchOutcome.FAILED,
                                            "retained_switch_session_changed");
                                    return;
                                }
                                if (!rearmFailedNewGameObservation(operation, gameId)) {
                                    SessionResumeManager.saveActive(
                                            Game.this, getIntent(), streamSessionId);
                                }
                                completeRetainedSwitch(operation,
                                        RetainedStreamSessionCoordinator.SwitchOutcome.FAILED,
                                        "provider_stop_failed");
                            }
                        });
                    }

                    @Override public void onInstallationVerified() {
                        // This Desktop target exists only for the launcher prompt.
                        runOnUiThread(() -> closeStreamWithPrivacy(true));
                    }

                    @Override public void onInstallationStillNeedsConfirmation() {
                        runOnUiThread(() -> displayMessage(getString(
                                R.string.playnite_install_still_needs_confirmation)));
                    }

                    @Override public void onInstallationVerificationFailed() {
                        runOnUiThread(() -> displayMessage(getString(
                                R.string.playnite_install_verify_failed)));
                    }
                }, launchStartedAtMillis);
    }

    private void completeInstallationFailure(
            String hostId, String gameId, String gameName, int messageId) {
        runOnUiThread(() -> {
            getSharedPreferences("console_dashboard", MODE_PRIVATE).edit()
                    .remove(playniteInstallPendingKey(hostId, gameId)).apply();
            displayMessage(getString(messageId, gameName));
        });
    }

    private void onFirstVideoFrameRendered() {
        logLaunchMilestone("first-video-frame-rendered");
        runOnUiThread(() -> {
            retainedRestoreAwaitingFrame = false;
            if (retainedRestoreWatchdog != null) {
                transitionUiHandler.removeCallbacks(retainedRestoreWatchdog);
                retainedRestoreWatchdog = null;
            }
            if (isOwnedHiddenAutoWarmUp()) {
                if (consoleLoadingView != null) consoleLoadingView.showOpaque();
            } else if (transitionController != null) {
                transitionController.videoFrameRendered(transitionSpec.id);
            } else {
                if (consoleLoadingView != null) consoleLoadingView.revealStream();
                streamEverRevealed = true;
                maybeArmStreamingAutopilotCalibration();
            }
        });
    }

    private void onTransitionChanged(LaunchTransitionSnapshot snapshot) {
        runOnUiThread(() -> applyTransitionSnapshot(snapshot));
    }

    private void updateGuidePolicyTransition() {
        if (controllerHandler == null) return;
        controllerHandler.getGuidePolicy().begin(
                transitionSpec == null ? "" : transitionSpec.id,
                transitionSpec == null ? "" : transitionSpec.playniteGameId,
                transitionSpec != null && transitionSpec.type != LaunchTransitionType.GENERIC);
    }

    private void applyTransitionSnapshot(LaunchTransitionSnapshot snapshot) {
        if (transitionSpec == null || snapshot.spec == null
                || !transitionSpec.id.equals(snapshot.spec.id)
                || consoleLoadingView == null) return;
        if (snapshot.state == LaunchTransitionState.GAME_READY) {
            autopilotGameReadySeen = true;
        }
        if (controllerHandler != null) {
            updateGuidePolicyTransition();
            controllerHandler.setInputSuppressed(
                    isOwnedHiddenAutoWarmUp() || snapshot.inputBlocked);
        }
        boolean shouldShowOpaque = shouldShowTransitionOverlay(
                lastTransitionOverlayVisible, lastTransitionRevealAuthorized,
                snapshot.overlayVisible, snapshot.revealAuthorized);
        if (shouldShowOpaque) consoleLoadingView.showOpaque();
        if (snapshot.overlayVisible) {
            if (usesClosingPresentation(snapshot)) {
                boolean gameEnded = snapshot.state == LaunchTransitionState.GAME_STOPPING
                        || snapshot.state == LaunchTransitionState.PLAYNITE_RETURNING
                        || snapshot.spec.type == LaunchTransitionType.GAME_CONNECTION
                        && snapshot.spec.playniteGameId.isEmpty();
                consoleLoadingView.showClosing(
                        getString(gameEnded ? R.string.transition_game_ended
                                : R.string.transition_closing_session),
                        getString(snapshot.state == LaunchTransitionState.PLAYNITE_RETURNING
                                ? R.string.transition_waiting_playnite_return
                                : R.string.transition_returning_library));
            } else {
                consoleLoadingView.setStep(snapshot.step, transitionStatus(snapshot));
                consoleLoadingView.setManualRevealAvailable(
                        !isOwnedHiddenAutoWarmUp() && snapshot.manualRevealAvailable);
            }
            boolean waitingForPostTargetFrame = !snapshot.revealAuthorized
                    && (snapshot.state == LaunchTransitionState.GAME_READY
                    || snapshot.state == LaunchTransitionState.PLAYNITE_FULLSCREEN_READY);
            if (!isOwnedHiddenAutoWarmUp()
                    && (shouldShowOpaque || waitingForPostTargetFrame) && decoderRenderer != null
                    && snapshot.state != LaunchTransitionState.CLOSING_STREAM
                    && snapshot.state != LaunchTransitionState.RETURNING_TO_DASHBOARD) {
                armNextVideoFrameForTransition(snapshot.spec.id, "transition-gate");
            }
        }
        if (snapshot.state == LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED) {
            consoleLoadingView.showLauncherInteraction(
                    getString(R.string.transition_game_failed),
                    snapshot.detail.isEmpty()
                            ? getString(R.string.transition_launcher_interaction_required)
                            : snapshot.detail,
                    snapshot.manualRevealAvailable);
        } else if (snapshot.state == LaunchTransitionState.ERROR
                || snapshot.state == LaunchTransitionState.TIMED_OUT) {
            int title = transitionSpec.type == LaunchTransitionType.PLAYNITE
                    ? (snapshot.step >= 4
                    ? R.string.transition_playnite_fullscreen_failed
                    : R.string.transition_playnite_failed)
                    : R.string.transition_game_failed;
            consoleLoadingView.showError(getString(title),
                    snapshot.detail.isEmpty()
                            ? getString(R.string.transition_readiness_unconfirmed)
                            : snapshot.detail,
                    snapshot.uncertain);
        } else if (snapshot.state == LaunchTransitionState.CANCELLED) {
            consoleLoadingView.showCancelling();
        }
        if (!snapshot.revealAuthorized || !snapshot.overlayVisible) {
            cancelPendingAutomaticReveal();
            if (!snapshot.overlayVisible) manualRevealRequested = false;
        }
        if (!isOwnedHiddenAutoWarmUp()
                && snapshot.revealAuthorized && snapshot.overlayVisible) {
            if (!manualRevealRequested) {
                scheduleAutomaticReveal();
            } else {
                revealTransitionNow();
            }
        }
        lastTransitionOverlayVisible = snapshot.overlayVisible;
        lastTransitionRevealAuthorized = snapshot.revealAuthorized;
        maybeArmStreamingAutopilotCalibration();
    }

    private static boolean usesClosingPresentation(LaunchTransitionSnapshot snapshot) {
        LaunchTransitionState state = snapshot.state;
        return state == LaunchTransitionState.GAME_STOPPING
                || state == LaunchTransitionState.PLAYNITE_RETURNING
                || state == LaunchTransitionState.PLAYNITE_STOPPING
                || state == LaunchTransitionState.CLOSING_STREAM
                || state == LaunchTransitionState.RETURNING_TO_DASHBOARD
                || snapshot.spec.type == LaunchTransitionType.GAME_CONNECTION
                && snapshot.spec.playniteGameId.isEmpty();
    }

    static boolean shouldShowTransitionOverlay(boolean wasOverlayVisible,
                                                boolean wasRevealAuthorized,
                                                boolean overlayVisible,
                                                boolean revealAuthorized) {
        return overlayVisible && (!wasOverlayVisible
                || (wasRevealAuthorized && !revealAuthorized));
    }

    private void scheduleAutomaticReveal() {
        if (pendingAutomaticReveal != null || transitionSpec == null) return;
        String transitionId = transitionSpec.id;
        pendingAutomaticReveal = () -> {
            pendingAutomaticReveal = null;
            if (transitionController == null || transitionSpec == null
                    || !transitionId.equals(transitionSpec.id)) return;
            LaunchTransitionSnapshot latest = transitionController.snapshot();
            if (latest.revealAuthorized && latest.overlayVisible) revealTransitionNow();
        };
        transitionUiHandler.postDelayed(
                pendingAutomaticReveal, AUTOMATIC_REVEAL_DELAY_MS);
    }

    private void revealTransitionNow() {
        if (isOwnedHiddenAutoWarmUp()) return;
        cancelPendingAutomaticReveal();
        if (consoleLoadingView == null || transitionController == null
                || transitionSpec == null) return;
        boolean manualReveal = manualRevealRequested;
        consoleLoadingView.revealStream(() -> {
            manualRevealRequested = false;
            if (streamAudioRenderer != null) streamAudioRenderer.setVolume(1f);
            streamEverRevealed = true;
            maybeArmStreamingAutopilotCalibration();
            if (manualReveal) {
                LimeLog.info("Manual stream reveal completed transition="
                        + transitionSpec.id + " game="
                        + transitionSpec.playniteGameId);
            }
            boolean rejectedProviderStart = transitionSpec.id.equals(
                    providerStartRejectedTransitionId);
            if (!rejectedProviderStart && transitionCoordinator != null) {
                transitionCoordinator.commitProviderLaunch();
            }
            if (rejectedProviderStart) persistRejectedProviderTransitionAsNeutral();
            else persistRevealedTransitionForRecovery();
            transitionController.revealCompleted(transitionSpec.id);
            RetainedSwitch operation = retainedSwitch;
            if (operation != null && operation.newSpec != null
                    && operation.newSpec.id.equals(transitionSpec.id)) {
                finishRetainedSwitch(operation);
            }
            showOverlayMenuHint();
        });
    }

    private void persistRevealedTransitionForRecovery() {
        if (transitionSpec == null || transitionSpec.type != LaunchTransitionType.GAME) return;
        // Keep the live observer as GAME, but never replay provider start after recreation.
        getIntent().putExtra(EXTRA_TRANSITION_TYPE,
                recoveredTransitionType(transitionSpec.type, true).name());
        SessionResumeManager.saveActive(this, getIntent(), streamSessionId);
    }

    private boolean isExactProviderTransition(String transitionId, String gameId) {
        return transitionSpec != null
                && transitionSpec.type == LaunchTransitionType.GAME
                && transitionSpec.id.equals(transitionId)
                && transitionSpec.playniteGameId.equalsIgnoreCase(gameId);
    }

    private void persistRejectedProviderTransitionAsNeutral() {
        if (transitionSpec == null
                || !transitionSpec.id.equals(providerStartRejectedTransitionId)) return;
        getIntent().putExtra(EXTRA_TRANSITION_TYPE,
                LaunchTransitionType.GAME_CONNECTION.name());
        getIntent().putExtra(EXTRA_TRANSITION_PLAYNITE_GAME_ID, "");
        if (connected) SessionResumeManager.saveActive(this, getIntent(), streamSessionId);
    }

    private String currentSessionGameId() {
        return transitionSpec == null
                || transitionSpec.id.equals(providerStartRejectedTransitionId)
                ? "" : transitionSpec.playniteGameId;
    }

    static LaunchTransitionType recoveredTransitionType(
            LaunchTransitionType runtimeType, boolean restoredRevealed) {
        return restoredRevealed && runtimeType == LaunchTransitionType.GAME
                ? LaunchTransitionType.GAME_CONNECTION : runtimeType;
    }

    static boolean shouldCleanupUnrevealedProviderLaunch(boolean startsProviderGame,
                                                          boolean streamEverRevealed) {
        return startsProviderGame && !streamEverRevealed;
    }

    static boolean shouldTerminateFreshOwnedSunshineSession(
            boolean ownsFreshSession, boolean neutralTarget,
            LaunchTransitionType type, boolean startsProviderGame,
            boolean streamEverRevealed, boolean retainedSession) {
        return ownsFreshSession && neutralTarget && type == LaunchTransitionType.GAME
                && startsProviderGame && !streamEverRevealed && !retainedSession;
    }

    static boolean shouldHonorExternalSwitchCancellation(
            boolean consoleSignalled, boolean cancelled) {
        return !consoleSignalled && cancelled;
    }

    private boolean cleanupUnrevealedProviderLaunch(String milestone) {
        if (freshOwnedFailureCleanupInFlight.get()) return true;
        if (transitionCoordinator == null
                || !shouldCleanupUnrevealedProviderLaunch(
                transitionCoordinator.startsProviderGame(), streamEverRevealed)) return false;
        boolean ownsFreshSession = shouldTerminateFreshOwnedSunshineSession(
                getIntent().getBooleanExtra(EXTRA_FRESH_SUNSHINE_SESSION_OWNER, false),
                getIntent().getBooleanExtra(EXTRA_NEUTRAL_STREAM_TARGET, false),
                transitionSpec == null ? null : transitionSpec.type,
                transitionCoordinator.startsProviderGame(), streamEverRevealed,
                retainedSwitch != null || hasOwnRetainedSession());
        logLaunchMilestone(milestone);
        if (ownsFreshSession) {
            if (freshOwnedFailureCleanupInFlight.compareAndSet(false, true)) {
                LimeLog.info("Fresh neutral cleanup claimed transition=" + transitionSpec.id
                        + " app=" + transitionSpec.sunshineAppId);
                transitionCoordinator.onStreamFailed();
                SessionResumeManager.clearIfMatches(this, streamSessionId);
            }
            return true;
        }
        transitionCoordinator.onStreamFailed();
        SessionResumeManager.clearIfMatches(this, streamSessionId);
        return false;
    }

    private boolean isFreshOwnedFailureCleanup(String transitionId, String gameId) {
        return freshOwnedFailureCleanupInFlight.get() && transitionSpec != null
                && transitionSpec.id.equals(transitionId)
                && transitionSpec.playniteGameId.equalsIgnoreCase(gameId);
    }

    private void cancelPendingAutomaticReveal() {
        if (pendingAutomaticReveal == null) return;
        transitionUiHandler.removeCallbacks(pendingAutomaticReveal);
        pendingAutomaticReveal = null;
    }

    private String transitionStatus(LaunchTransitionSnapshot snapshot) {
        switch (snapshot.state) {
            case PREPARING_SESSION:
                return getString(R.string.transition_preparing_session);
            case CONNECTING_STREAM:
            case WAITING_FOR_VIDEO_SURFACE:
                return transitionCoordinator != null && transitionCoordinator.startsProviderGame()
                        ? getString(R.string.transition_connecting_stream_and_starting_game)
                        : getString(R.string.transition_connecting_stream);
            case PLAYNITE_STARTING:
                return getString(R.string.transition_starting_playnite);
            case PLAYNITE_PROCESS_RUNNING:
                return getString(R.string.transition_waiting_fullscreen);
            case PLAYNITE_FULLSCREEN_STARTING:
                return snapshot.detail.isEmpty()
                        ? getString(R.string.transition_waiting_fullscreen) : snapshot.detail;
            case GAME_START_REQUESTED:
            case GAME_STARTING:
                return getString(R.string.transition_starting_game);
            case GAME_PROCESS_RUNNING:
                return snapshot.detail.isEmpty()
                        ? getString(R.string.transition_game_running_waiting_window)
                        : snapshot.detail;
            case GAME_WINDOW_STABILIZING:
                return snapshot.detail.isEmpty()
                        ? getString(R.string.transition_game_running_waiting_window)
                        : snapshot.detail;
            case LAUNCHER_INTERACTION_REQUIRED:
                return getString(R.string.transition_launcher_interaction_required);
            case GAME_STOPPING:
                return getString(R.string.transition_closing_session);
            case PLAYNITE_RETURNING:
                return getString(R.string.transition_waiting_playnite_return);
            case PLAYNITE_STOPPING:
            case CLOSING_STREAM:
            case RETURNING_TO_DASHBOARD:
                return getString(R.string.transition_closing_session);
            case GAME_READY:
            case PLAYNITE_FULLSCREEN_READY:
                return getString(R.string.transition_window_ready_waiting_frame);
            case GAME_RUNNING:
            case IDLE:
                return getString(R.string.transition_ready);
            default:
                return snapshot.detail.isEmpty()
                        ? getString(R.string.transition_waiting_gateway) : snapshot.detail;
        }
    }

    private static String playniteInstallNotificationKey(String hostId, String gameId) {
        return "playnite_install_notified." + hostId + ":" + gameId;
    }

    private static String playniteInstallPendingKey(String hostId, String gameId) {
        return "playnite_install_pending." + hostId + ":" + gameId;
    }

    private boolean isPendingInstallation(String hostId, String gameId) {
        return gameId != null && !gameId.isEmpty()
                && getSharedPreferences("console_dashboard", MODE_PRIVATE)
                .contains(playniteInstallPendingKey(hostId, gameId));
    }

    private String pendingInstallationName(String hostId, String gameId) {
        return getSharedPreferences("console_dashboard", MODE_PRIVATE).getString(
                playniteInstallPendingKey(hostId, gameId),
                getString(R.string.playnite_game_fallback_name));
    }

    private void cancelTransition() {
        if (transitionController == null || transitionCancelInFlight) return;
        if (returnRetainedObservationToDashboard()) return;
        if (retainedSwitch != null) {
            requestRetainedSwitchCancellation(retainedSwitch);
            return;
        }
        transitionCancelInFlight = true;
        transitionController.cancel(transitionSpec.id);
        if (transitionCoordinator != null) transitionCoordinator.cancel();
        cancelPendingAutomaticReveal();
        userInitiatedDisconnect = true;
        backgroundStreamParked = false;
        RetainedStreamSessionCoordinator.clearIfMatches(streamSessionId);
        SessionResumeManager.clearIfMatches(this, streamSessionId);
        BackgroundStreamService.resumed(this, streamSessionId);
        if (controllerHandler != null) controllerHandler.pendingApplicationQuit = false;
        if (transitionCoordinator == null || !transitionCoordinator.startsProviderGame()) {
            stopProviderGame(success -> {
                if (!success) {
                    LimeLog.warning("Provider game stop was not confirmed after transition cancel");
                }
            });
        }
        AtomicBoolean finished = new AtomicBoolean();
        Runnable finishOnce = () -> {
            if (finished.compareAndSet(false, true)) finish();
        };
        transitionUiHandler.postDelayed(finishOnce, 5_000L);
        stopConnection(finishOnce);
    }

    private boolean returnRetainedObservationToDashboard() {
        if (retainedDashboardReturnInFlight) return true;
        if (!isExactLiveRetainedObservation()) return false;
        String transitionId = transitionSpec.id;
        String gameId = transitionSpec.playniteGameId;
        retainedDashboardReturnInFlight = true;
        consoleLoadingView.showOpaque();
        consoleLoadingView.doAfterNextFrame(() -> {
            boolean exactForeground = isExactLiveRetainedObservation();
            boolean openHome = shouldOpenRetainedObservationDashboard(
                    transitionId, gameId,
                    transitionSpec == null ? "" : transitionSpec.id,
                    transitionSpec == null ? "" : transitionSpec.playniteGameId,
                    exactForeground);
            retainedDashboardReturnInFlight = false;
            if (openHome) openConsoleHome();
        });
        return true;
    }

    private boolean isExactLiveRetainedObservation() {
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        boolean exactOwner = transitionSpec != null
                && RetainedStreamSessionCoordinator.ownsLiveOrParkedSession(
                this, streamSessionId, transitionSpec.hostId,
                transitionSpec.sunshineAppId, transitionSpec.playniteGameId);
        return isForegroundRetainedObservation(
                connected, streamHomeVisible, backgroundStreamParked,
                transitionSpec == null ? null : transitionSpec.type,
                transitionSpec == null ? "" : transitionSpec.playniteGameId,
                retained.state, exactOwner);
    }

    static boolean isForegroundRetainedObservation(
            boolean connected, boolean streamHomeVisible, boolean backgroundStreamParked,
            LaunchTransitionType type, String gameId,
            RetainedStreamSessionCoordinator.State retainedState, boolean exactOwner) {
        return connected && !streamHomeVisible
                && !backgroundStreamParked
                && type == LaunchTransitionType.GAME_CONNECTION
                && gameId != null && !gameId.isEmpty()
                && retainedState == RetainedStreamSessionCoordinator.State.HOME_LIVE
                && exactOwner;
    }

    static boolean shouldOpenRetainedObservationDashboard(
            String scheduledTransitionId, String scheduledGameId,
            String currentTransitionId, String currentGameId,
            boolean exactForeground) {
        return exactForeground && scheduledTransitionId != null
                && scheduledTransitionId.equals(currentTransitionId)
                && scheduledGameId != null
                && scheduledGameId.equalsIgnoreCase(currentGameId);
    }

    private void armNextVideoFrameForTransition(String transitionId, String reason) {
        if (decoderRenderer == null || transitionId == null || transitionId.isEmpty()
                || transitionSpec == null || !transitionSpec.id.equals(transitionId)) return;
        while (true) {
            String armed = armedVideoFrameTransitionId.get();
            if (transitionId.equals(armed)) return;
            if (armedVideoFrameTransitionId.compareAndSet(armed, transitionId)) break;
        }
        MoonWakerDiagnostics.record("INFO", "android.video-frame",
                "video_frame.armed", "transition_id", transitionId,
                "reason", reason == null ? "" : reason);
        decoderRenderer.requestNextFrameRendered(() -> runOnUiThread(() -> {
            if (!armedVideoFrameTransitionId.compareAndSet(transitionId, "")) {
                MoonWakerDiagnostics.record("INFO", "android.video-frame",
                        "video_frame.stale", "transition_id", transitionId);
                return;
            }
            if (transitionSpec == null || !transitionSpec.id.equals(transitionId)
                    || transitionController == null) {
                MoonWakerDiagnostics.record("INFO", "android.video-frame",
                        "video_frame.stale", "transition_id", transitionId);
                return;
            }
            MoonWakerDiagnostics.record("INFO", "android.video-frame",
                    "video_frame.rendered", "transition_id", transitionId);
            transitionController.videoFrameRendered(transitionId);
        }));
    }

    private void retryTransition() {
        if (transitionSpec == null) return;
        if (retainedSwitch != null && retainedSwitch.newSpec != null) {
            retainedSwitch.retryRequested = true;
            requestRetainedSwitchCancellation(retainedSwitch);
            return;
        }
        if ((transitionSpec.type == LaunchTransitionType.GAME
                || transitionSpec.type == LaunchTransitionType.GAME_CONNECTION)
                && hasLiveRetainedTransport() && connected) {
            retryRetainedObservation();
            return;
        }
        if (transitionCoordinator != null) transitionCoordinator.onStreamFailed();
        Intent retry = new Intent(getIntent());
        LaunchTransitionSpec next = LaunchTransitionSpec.create(
                transitionSpec.hostId, transitionSpec.type, transitionSpec.sunshineAppId,
                transitionSpec.playniteGameId, System.currentTimeMillis(),
                transitionSpec.startProviderBeforeStream);
        providerStartRejectedTransitionId = "";
        retry.putExtra(EXTRA_TRANSITION_ID, next.id);
        retry.putExtra(EXTRA_TRANSITION_TYPE, next.type.name());
        retry.putExtra(EXTRA_TRANSITION_PLAYNITE_GAME_ID, next.playniteGameId);
        retry.putExtra(EXTRA_TRANSITION_CREATED_AT, next.createdAtMillis);
        retry.putExtra(EXTRA_TRANSITION_START_BEFORE_STREAM,
                next.startProviderBeforeStream);
        consoleLoadingView.showOpaque();
        consoleLoadingView.doAfterNextFrame(() -> {
            userInitiatedDisconnect = true;
            stopConnection(() -> {
                finish();
                startActivity(retry);
                overridePendingTransition(0, 0);
            });
        });
    }

    private void retryRetainedObservation() {
        ConsoleStreamTransitionCoordinator previousCoordinator = transitionCoordinator;
        LaunchTransitionSpec previousSpec = transitionSpec;
        cancelPendingAutomaticReveal();
        manualRevealRequested = false;
        transitionCancelInFlight = false;
        LaunchTransitionSpec next = LaunchTransitionSpec.create(
                transitionSpec.hostId, LaunchTransitionType.GAME_CONNECTION,
                transitionSpec.sunshineAppId, transitionSpec.playniteGameId,
                System.currentTimeMillis());
        transitionSpec = next;
        ConsoleStreamTransitionCoordinator replacement = createTransitionCoordinator();
        boolean ownershipReady = previousCoordinator != null
                ? previousCoordinator.transferProviderOwnershipTo(replacement)
                : next.playniteGameId.isEmpty();
        if (!ownershipReady) {
            replacement.close();
            transitionSpec = previousSpec;
            transitionController.error(previousSpec.id,
                    getString(R.string.transition_readiness_unconfirmed));
            return;
        }
        if (previousCoordinator != null) previousCoordinator.close();
        getIntent().putExtra(EXTRA_TRANSITION_ID, next.id);
        getIntent().putExtra(EXTRA_TRANSITION_TYPE, next.type.name());
        getIntent().putExtra(EXTRA_TRANSITION_CREATED_AT, next.createdAtMillis);
        SessionResumeManager.saveActive(this, getIntent(), streamSessionId);
        consoleLoadingView.showFullTransitionAppearance();
        transitionController.begin(next);
        transitionCoordinator = replacement;
        consoleLoadingView.showOpaque();
        consoleLoadingView.doAfterNextFrame(() -> {
            if (transitionSpec != next || isFinishing() || isDestroyed()) return;
            transitionController.overlayRendered(next.id);
            if (surfaceCreated && streamView.getHolder().getSurface() != null
                    && streamView.getHolder().getSurface().isValid()) {
                transitionController.surfaceReady(next.id);
            }
            if (controllerHandler != null) transitionController.inputPipelineReady(next.id);
            if (connected) {
                transitionController.streamConnected(next.id);
                transitionCoordinator.start();
                transitionCoordinator.onStreamConnected();
            }
        });
    }

    @Override
    public void displayMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void displayTransientMessage(final String message) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    public void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        LimeLog.info(String.format((Locale)null, "Rumble on gamepad %d: %04x %04x", controllerNumber, lowFreqMotor, highFreqMotor));

        controllerHandler.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor);
    }

    @Override
    public void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        LimeLog.info(String.format((Locale)null, "Rumble on gamepad triggers %d: %04x %04x", controllerNumber, leftTrigger, rightTrigger));

        controllerHandler.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger);
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        LimeLog.info("Display HDR mode: " + (enabled ? "enabled" : "disabled"));
        if (switchableVideoRenderer != null) {
            switchableVideoRenderer.setHdrMode(enabled, hdrMetadata);
        }
    }

    @Override
    public void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz) {
        controllerHandler.handleSetMotionEventState(controllerNumber, motionType, reportRateHz);
    }

    @Override
    public void setControllerLED(short controllerNumber, byte r, byte g, byte b) {
        controllerHandler.handleSetControllerLED(controllerNumber, r, g, b);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!surfaceCreated) {
            throw new IllegalStateException("Surface changed before creation!");
        }

        startConnectionIfReady(holder);
    }

    private void startConnectionIfReady(SurfaceHolder holder) {
        if (attemptedConnection || !surfaceCreated || holder == null
                || holder.getSurface() == null || !holder.getSurface().isValid()) {
            return;
        }
        if (transitionController != null
                && !transitionController.snapshot().operationAuthorized) {
            return;
        }
        if (hasAutoWarmUpTransportIdentity()
                && !isExactAutoWarmUpPreparing(
                RetainedStreamSessionCoordinator.snapshot())) return;
        attemptedConnection = true;

        // Update GameManager state to indicate we're "loading" while connecting
        UiHelper.notifyStreamConnecting(Game.this);

        // Show stream configuration to user
        String configMessage = String.format(Locale.getDefault(),
                "Streaming %dx%d @ %d FPS, %d Mbps%s",
                prefConfig.width,
                prefConfig.height,
                prefConfig.fps,
                prefConfig.bitrate / 1000,
                prefConfig.enableHdr ? ", HDR" : "");
        LimeLog.info(configMessage);
        if (consoleLoadingView == null) {
            Toast.makeText(Game.this, configMessage, Toast.LENGTH_LONG).show();
        }

        decoderRenderer.setRenderTarget(holder);
        streamAudioRenderer = new AndroidAudioRenderer(Game.this, prefConfig.enableAudioFx);
        if (isOwnedHiddenAutoWarmUp()) streamAudioRenderer.setVolume(0f);
        connecting = true;
        logLaunchMilestone("stream-start-requested");
        conn.start(streamAudioRenderer, switchableVideoRenderer, Game.this);
    }

    private void logLaunchMilestone(String milestone) {
        if (transitionSpec == null) return;
        long elapsed = launchStartedAtMillis <= 0L
                ? 0L : Math.max(0L, SystemClock.uptimeMillis() - launchStartedAtMillis);
        LimeLog.info("Launch timeline epoch=" + launchStartedAtMillis
                + " transition=" + transitionSpec.id
                + " host=" + transitionSpec.hostId
                + " game=" + transitionSpec.playniteGameId
                + " +" + elapsed + "ms " + milestone);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        float desiredFrameRate;

        surfaceCreated = true;
        if (transitionController != null) {
            transitionController.surfaceReady(transitionSpec.id);
        }

        // Android will pick the lowest matching refresh rate for a given frame rate value, so we want
        // to report the true FPS value if refresh rate reduction is enabled. We also report the true
        // FPS value if there's no suitable matching refresh rate. In that case, Android could try to
        // select a lower refresh rate that avoids uneven pull-down (ex: 30 Hz for a 60 FPS stream on
        // a display that maxes out at 50 Hz).
        if (mayReduceRefreshRate() || desiredRefreshRate < prefConfig.fps) {
            desiredFrameRate = prefConfig.fps;
        }
        else {
            // Otherwise, we will pretend that our frame rate matches the refresh rate we picked in
            // prepareDisplayForRendering(). This will usually be the highest refresh rate that our
            // frame rate evenly divides into, which ensures the lowest possible display latency.
            desiredFrameRate = desiredRefreshRate;
        }

        // Tell the OS about our frame rate to allow it to adapt the display refresh rate appropriately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // We want to change frame rate even if it's not seamless, since prepareDisplayForRendering()
            // will not set the display mode on S+ if it only differs by the refresh rate. It depends
            // on us to trigger the frame rate switch here.
            holder.getSurface().setFrameRate(desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.getSurface().setFrameRate(desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        }
        if (backgroundStreamParked) restoreParkedStream(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (!surfaceCreated) {
            throw new IllegalStateException("Surface destroyed before creation!");
        }

        surfaceCreated = false;
        if (attemptedConnection && connected) {
            if (hasOwnRetainedSession()) {
                RetainedStreamSessionCoordinator.parkForBackground(streamSessionId);
            } else if (canParkBackgroundStream()) {
                parkBackgroundStream();
            } else if (!backgroundStreamParked) {
                decoderRenderer.prepareForStop();
                stopConnection();
            }
        }
    }

    @Override
    public void mouseMove(int deltaX, int deltaY) {
        if (isTransitionInputBlocked()) return;
        conn.sendMouseMove((short) deltaX, (short) deltaY);
    }

    @Override
    public void mouseButtonEvent(int buttonId, boolean down) {
        if (isTransitionInputBlocked()) return;
        byte buttonIndex;

        switch (buttonId)
        {
        case EvdevListener.BUTTON_LEFT:
            buttonIndex = MouseButtonPacket.BUTTON_LEFT;
            break;
        case EvdevListener.BUTTON_MIDDLE:
            buttonIndex = MouseButtonPacket.BUTTON_MIDDLE;
            break;
        case EvdevListener.BUTTON_RIGHT:
            buttonIndex = MouseButtonPacket.BUTTON_RIGHT;
            break;
        case EvdevListener.BUTTON_X1:
            buttonIndex = MouseButtonPacket.BUTTON_X1;
            break;
        case EvdevListener.BUTTON_X2:
            buttonIndex = MouseButtonPacket.BUTTON_X2;
            break;
        default:
            LimeLog.warning("Unhandled button: "+buttonId);
            return;
        }

        if (down) {
            conn.sendMouseButtonDown(buttonIndex);
        }
        else {
            conn.sendMouseButtonUp(buttonIndex);
        }
    }

    @Override
    public void mouseVScroll(byte amount) {
        if (isTransitionInputBlocked()) return;
        conn.sendMouseScroll(amount);
    }

    @Override
    public void mouseHScroll(byte amount) {
        if (isTransitionInputBlocked()) return;
        conn.sendMouseHScroll(amount);
    }

    @Override
    public void keyboardEvent(boolean buttonDown, short keyCode) {
        if (isTransitionInputBlocked()) return;
        short keyMap = keyboardTranslator.translate(keyCode, -1);
        if (keyMap != 0) {
            // handleSpecialKeys() takes the Android keycode
            if (handleSpecialKeys(keyCode, buttonDown)) {
                return;
            }

            if (buttonDown) {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, getModifierState(), (byte)0);
            }
            else {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, getModifierState(), (byte)0);
            }
        }
    }

    @Override
    public void onSystemUiVisibilityChange(int visibility) {
        // Don't do anything if we're not connected
        if (!connected) {
            return;
        }

        // This flag is set for all devices
        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            hideSystemUi(2000);
        }
        else if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            hideSystemUi(2000);
        }
    }

    private boolean isTransitionInputBlocked() {
        return transitionController != null
                && transitionController.snapshot().inputBlocked;
    }

    @Override
    public void onPerfUpdate(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (autopilotProgressOverlayActive) return;
                performanceOverlayView.setText(text);
            }
        });
    }

    @Override
    public void onUsbPermissionPromptStarting() {
        // Disable PiP auto-enter while the USB permission prompt is on-screen. This prevents
        // us from entering PiP while the user is interacting with the OS permission dialog.
        suppressPipRefCount++;
        updatePipAutoEnter();
    }

    @Override
    public void onUsbPermissionPromptCompleted() {
        suppressPipRefCount--;
        updatePipAutoEnter();
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
        switch (keyEvent.getAction()) {
            case KeyEvent.ACTION_DOWN:
                return handleKeyDown(keyEvent);
            case KeyEvent.ACTION_UP:
                return handleKeyUp(keyEvent);
            case KeyEvent.ACTION_MULTIPLE:
                return handleKeyMultiple(keyEvent);
            default:
                return false;
        }
    }

    /**
     * Setup overlay menu and its listeners
     */
    private void setupOverlayMenu() {
        // Set up the overlay menu listener for select button hold detection
        controllerHandler.setOverlayMenuListener(new ControllerHandler.OverlayMenuListener() {
            @Override
            public void onOverlayMenuOpen() {
                runOnUiThread(Game.this::openDiscordDmShortcutOrOverlay);
            }

            @Override
            public void onHomeShortcut() {
                runOnUiThread(Game.this::openConsoleHome);
            }

            @Override
            public void onOverlayMenuCancel() {
                // Nothing to do - no progress indicator to hide
            }
        });

        // Set up menu action listener
        overlayMenuView.setMenuActionListener(new OverlayMenuView.MenuActionListener() {
            @Override
            public void onHome() {
                openConsoleHome();
            }

            @Override
            public void onQuitSession() {
                closeStreamWithPrivacy(true);
            }

            @Override
            public void onEndGame() {
                confirmEndManagedGame();
            }

            @Override
            public void onSuspendSession() {
                confirmSuspendSession();
            }

            @Override
            public void onToggleStats() {
                prefConfig.enablePerfOverlay = !prefConfig.enablePerfOverlay;

                // Toggle performance overlay visibility
                if (performanceOverlayView.getVisibility() == View.VISIBLE) {
                    performanceOverlayView.setVisibility(View.GONE);
                } else {
                    performanceOverlayView.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onToggleMouseEmulation() {
                // Toggle mouse emulation mode for the primary controller
                controllerHandler.toggleMouseEmulationForController0();
            }

            @Override
            public void onShowKeyboard() {
                // Toggle Android soft keyboard
                toggleKeyboard();
            }

            @Override
            public void onSendGuideButton() {
                // Send Guide button press to the host
                sendGuideButton();
            }

            @Override
            public void onApplyBitrate(int bitrateKbps) {
                applyBitrateAndReconnect(bitrateKbps);
            }

            @Override
            public void onCustomCommand(CustomCommand command) {
                if (command.getPostAction() == CustomCommand.POST_ACTION_CLOSE_MENU) {
                    overlayMenuView.closeMenu();
                    sendCustomKeyCommand(command, null);
                } else {
                    sendCustomKeyCommand(command, buildPostActionRunnable(command));
                }
            }

            @Override
            public void onDiscordMute() {
                discordOverlayController.toggleMute();
            }

            @Override
            public void onDiscordLeave() {
                discordOverlayController.leave();
            }

            @Override
            public void onDiscordRejoin() {
                discordOverlayController.rejoin();
            }

            @Override
            public void onDiscordDockToggle() {
                discordOverlayController.toggleDock();
            }

            @Override
            public void onDiscordSocialFriends() {
                discordOverlayController.toggleSocialFriends();
            }

            @Override public void onDiscordCommunityOpenFriendChat(String friendId) {
                discordOverlayController.onDiscordCommunityOpenFriendChat(friendId);
            }

            @Override public void onDiscordCommunityLoadGuild(String guildId) {
                discordOverlayController.onDiscordCommunityLoadGuild(guildId);
            }

            @Override public void onDiscordCommunityJoinChannel(String channelId) {
                discordOverlayController.onDiscordCommunityJoinChannel(channelId);
            }

            @Override public void onDiscordCommunityChatDraftChanged(String friendId, String draft) {
                discordOverlayController.onDiscordCommunityChatDraftChanged(friendId, draft);
            }

            @Override public void onDiscordCommunitySendChat(String friendId, String draft) {
                discordOverlayController.onDiscordCommunitySendChat(friendId, draft);
            }

            @Override public void onDiscordCommunityOpenMessageInDiscord(String messageId) {
                discordOverlayController.onDiscordCommunityOpenMessageInDiscord(messageId);
            }

            @Override public void onDiscordCommunityBackToFriends() {
                discordOverlayController.onDiscordCommunityBackToFriends();
            }

            @Override public void onDiscordCommunityBackToChannels() {
                discordOverlayController.onDiscordCommunityBackToChannels();
            }

            @Override public void onDiscordCommunityOpened() {
                discordOverlayController.onDiscordCommunityOpened();
            }

            @Override public void onDiscordCommunitySectionChanged(OverlayMenuView.CommunitySection section) {
                discordOverlayController.onDiscordCommunitySectionChanged(section);
            }

            @Override public void onDiscordCommunityAuthorizeDirectMessages() {
                // The controller releases its DM lease before closeMenu(), and begins OAuth
                // only after this listener receives onMenuClosed().
                discordOverlayController.authorizeDirectMessages();
            }

            @Override
            public void onInstallationConfirmed() {
                if (transitionCoordinator == null
                        || !transitionCoordinator.isInstallationConfirmationStream()) return;
                displayMessage(getString(R.string.playnite_install_verifying));
                transitionCoordinator.verifyInstallation();
            }

            @Override
            public void onMenuClosed() {
                discordOverlayController.onOverlayClosed();
                if (discordDmNotifications != null) {
                    discordDmNotifications.setPresentationBlocked(discordDmHostToken, false);
                }
            }
        });
    }

    private void openConsoleHome() {
        if (!connected || streamHomeVisible) return;
        overlayMenuView.closeMenu();
        String sessionGameId = currentSessionGameId();
        SessionResumeManager.save(this, getIntent(), streamSessionId);
        RetainedStreamSessionCoordinator.enterHome(this, streamSessionId,
                getIntent().getStringExtra(EXTRA_PC_UUID),
                getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID),
                sessionGameId);
        if (streamAudioRenderer != null) streamAudioRenderer.setVolume(0f);
        if (controllerHandler != null) controllerHandler.disableSensors();

        launchConsoleHome(sessionGameId, false);
    }

    private void openPreparingConsoleHome() {
        if (streamHomeVisible) return;
        overlayMenuView.closeMenu();
        if (streamAudioRenderer != null) streamAudioRenderer.setVolume(0f);
        if (controllerHandler != null) {
            controllerHandler.setInputSuppressed(true);
            controllerHandler.disableSensors();
        }
        launchConsoleHome(transitionSpec == null ? "" : transitionSpec.playniteGameId, true);
    }

    private void launchConsoleHome(String gameId, boolean preparing) {
        streamHomeVisible = true;
        Intent home = new Intent(this, StreamHomeActivity.class);
        home.putExtra(ConsoleActivity.EXTRA_RETAINED_STREAM_HOME, true);
        home.putExtra(ConsoleActivity.EXTRA_RETAINED_STREAM_SESSION_ID, streamSessionId);
        home.putExtra(ConsoleActivity.EXTRA_RETAINED_STREAM_HOST_ID,
                getIntent().getStringExtra(EXTRA_PC_UUID));
        home.putExtra(ConsoleActivity.EXTRA_RETAINED_STREAM_APP_ID,
                getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID));
        home.putExtra(ConsoleActivity.EXTRA_RETAINED_STREAM_PLAYNITE_GAME_ID,
                gameId);
        long warmUpAttempt = getIntent().getLongExtra(
                ConsoleActivity.EXTRA_WARM_UP_ATTEMPT, 0L);
        if (warmUpAttempt > 0L) {
            home.putExtra(ConsoleActivity.EXTRA_WARM_UP_ATTEMPT, warmUpAttempt);
            home.putExtra(ConsoleActivity.EXTRA_WARM_UP_TRANSITION_ID,
                    transitionSpec == null ? "" : transitionSpec.id);
            home.putExtra(ConsoleActivity.EXTRA_WARM_UP_PENDING_GAME_ID,
                    getIntent().getStringExtra(
                            ConsoleActivity.EXTRA_WARM_UP_PENDING_GAME_ID));
            home.putExtra(ConsoleActivity.EXTRA_WARM_UP_PENDING_GAME_NAME,
                    getIntent().getStringExtra(
                            ConsoleActivity.EXTRA_WARM_UP_PENDING_GAME_NAME));
            home.putExtra(ConsoleActivity.EXTRA_WARM_UP_PENDING_ARTWORK_ID,
                    getIntent().getStringExtra(
                            ConsoleActivity.EXTRA_WARM_UP_PENDING_ARTWORK_ID));
            home.putExtra(ConsoleActivity.EXTRA_WARM_UP_PENDING_QUICK_LAUNCH,
                    getIntent().getStringExtra(
                            ConsoleActivity.EXTRA_WARM_UP_PENDING_QUICK_LAUNCH));
        }
        startActivity(home);
        overridePendingTransition(preparing ? 0 : android.R.anim.fade_in, 0);
    }

    private void showOverlayMenuWithBattery() {
        if (discordDmNotifications != null) {
            discordDmNotifications.setPresentationBlocked(discordDmHostToken, true);
        }
        overlayMenuView.setControllerBatteryInfo(controllerHandler.getControllerBatteryInfo());
        overlayMenuView.setBitrateKbps(runtimeBitrateKbps);
        overlayMenuView.setEndGameAvailable(canEndManagedGame());
        overlayMenuView.show();
        discordOverlayController.onOverlayShown();
        controllerHandler.refreshControllerBatteryInfo(() -> {
            if (overlayMenuView.getVisibility() == View.VISIBLE) {
                overlayMenuView.setControllerBatteryInfo(controllerHandler.getControllerBatteryInfo());
            }
        });
    }

    private void openDiscordDmShortcutOrOverlay() {
        long peerId = discordDmNotifications == null
                ? 0L : discordDmNotifications.consumeQuickAction(discordDmHostToken);
        showOverlayMenuWithBattery();
        if (peerId > 0L) discordOverlayController.openDirectMessage(peerId);
    }

    private void confirmSuspendSession() {
        overlayMenuView.closeMenu();
        ConsoleConfirmDialog.show(this, getString(R.string.overlay_menu_suspend_session),
                getString(R.string.overlay_menu_suspend_confirmation),
                getString(android.R.string.cancel),
                getString(R.string.overlay_menu_suspend_confirm),
                this::suspendSessionAndSleep);
    }

    private boolean canEndManagedGame() {
        return connected && streamEverRevealed && transitionSpec != null
                && hasProviderGameTransition() && transitionCoordinator != null
                && getIntent().getBooleanExtra(EXTRA_NEUTRAL_STREAM_TARGET, false)
                && retainedSwitch == null && !providerEndGameInFlight
                && !providerStopInFlight && !transitionCancelInFlight
                && !userInitiatedDisconnect;
    }

    private void confirmEndManagedGame() {
        overlayMenuView.closeMenu();
        if (!canEndManagedGame()) return;
        ConsoleConfirmDialog.show(this, getString(R.string.overlay_menu_end_game),
                getString(R.string.overlay_menu_end_game_confirmation),
                getString(android.R.string.cancel),
                getString(R.string.overlay_menu_end_game_confirm),
                this::endManagedGameKeepingStream);
    }

    private void endManagedGameKeepingStream() {
        if (!canEndManagedGame()) return;
        LaunchTransitionSpec expected = transitionSpec;
        providerEndGameInFlight = true;
        consoleLoadingView.showOpaque();
        consoleLoadingView.doAfterNextFrame(() -> new Thread(() -> {
            try {
                PlayniteTransitionGateway gateway = PlayniteTransitionGateway.connect(
                        Game.this, expected.hostId, getIntent().getStringExtra(EXTRA_HOST));
                if (gateway == null) throw new IOException("gateway_unavailable");
                PlayniteTransitionGateway.Snapshot current = gateway.snapshot();
                String exactGame = PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                        expected.playniteGameId, current.gameState, current.gameId);
                if (exactGame == null || exactGame.isEmpty()) {
                    throw new IOException("provider_game_not_exact");
                }
                gateway.stopGame(exactGame);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()
                            || !providerEndGameInFlight || transitionSpec != expected) return;
                    providerEndGameInFlight = false;
                    providerStopConfirmed = true;
                    retainNeutralStreamAfterNaturalGameStop(expected.id, exactGame);
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()
                            || !providerEndGameInFlight || transitionSpec != expected) return;
                    providerEndGameInFlight = false;
                    retryRetainedObservation();
                    new android.app.AlertDialog.Builder(Game.this)
                            .setTitle(R.string.overlay_menu_end_game_failed_title)
                            .setMessage(R.string.overlay_menu_end_game_failed)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        }, "MoonWaker-EndManagedGame").start());
    }

    private void clearResumedSuspendedSession() {
        if (sourceSuspendId.isEmpty()) return;
        SuspendedSessionStore.markSessionEndedIfMatches(this,
                getIntent().getStringExtra(EXTRA_PC_UUID), sourceSuspendId);
    }
    private void suspendSessionAndSleep() {
        if (transitionSpec == null) {
            displayTransientMessage(getString(R.string.overlay_menu_suspend_unavailable));
            return;
        }
        transitionController.closingStream(transitionSpec.id);
        consoleLoadingView.showOpaque();
        final String host = getIntent().getStringExtra(EXTRA_HOST);
        final String artwork = getIntent().getStringExtra(EXTRA_CONSOLE_LOADING_ARTWORK);
        final String suspendId = UUID.randomUUID().toString();
        new Thread(() -> {
            PlayniteTransitionGateway gateway = PlayniteTransitionGateway.connect(
                    Game.this, transitionSpec.hostId, host);
            if (gateway == null) {
                runOnUiThread(() -> {
                    transitionController.cancel(transitionSpec.id);
                    consoleLoadingView.stopAndHide();
                    displayTransientMessage(getString(R.string.overlay_menu_suspend_unavailable));
                });
                return;
            }
            try {
                String playniteGameId = transitionSpec.playniteGameId;
                if (playniteGameId == null || playniteGameId.isEmpty()) {
                    playniteGameId = getSharedPreferences("console_dashboard", MODE_PRIVATE)
                            .getString("selected_playnite." + transitionSpec.hostId, "");
                }
                PlayniteTransitionGateway.SuspendAcceptance acceptance =
                        gateway.suspendSession(suspendId, transitionSpec.sunshineAppId,
                                playniteGameId, appName);
                if (!acceptance.matches(suspendId, transitionSpec.sunshineAppId,
                        playniteGameId)) {
                    throw new IOException("Host rejected the correlated suspend request.");
                }
                SuspendedSessionStore.save(Game.this,
                        new SuspendedSessionStore.Session(suspendId, transitionSpec.hostId,
                                transitionSpec.sunshineAppId, playniteGameId, appName,
                                artwork, System.currentTimeMillis()));
                SuspendedSessionStore.requestHostSelection(Game.this, transitionSpec.hostId);
                runOnUiThread(() -> closeStreamWithPrivacy(false));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    transitionController.cancel(transitionSpec.id);
                    consoleLoadingView.stopAndHide();
                    displayTransientMessage(getString(R.string.overlay_menu_suspend_failed));
                });
            }
        }, "MoonWaker-SuspendSession").start();
    }
    private void applyBitrateAndReconnect(int bitrateKbps) {
        if (!prefConfig.runtimeBitrateControl) {
            return;
        }

        int targetBitrate = Math.max(1000, Math.min(150000, bitrateKbps));
        if (targetBitrate == runtimeBitrateKbps) return;

        runtimeBitrateKbps = targetBitrate;
        getIntent().putExtra(EXTRA_RUNTIME_BITRATE_KBPS, targetBitrate);
        bitrateReconnectPending = true;
        Toast.makeText(this,
                getString(R.string.overlay_bitrate_reconnecting, Math.round(targetBitrate / 1000f)),
                Toast.LENGTH_LONG).show();
        overlayMenuView.closeMenu();
        stopConnection(() -> {
            if (!isFinishing() && !isDestroyed()) {
                Intent restartIntent = new Intent(getIntent());
                restartIntent.setClass(Game.this, Game.class);
                restartIntent.putExtra(EXTRA_RUNTIME_BITRATE_KBPS, targetBitrate);
                if (transitionSpec != null
                        && transitionSpec.type == LaunchTransitionType.GAME) {
                    restartIntent.putExtra(EXTRA_TRANSITION_TYPE,
                            LaunchTransitionType.GAME_CONNECTION.name());
                }
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);

                // Game is a no-history, singleTask activity. Activity.recreate() can
                // expose the previous AppView instead of creating a fresh streaming
                // instance on Android TV. Finish the stopped instance explicitly,
                // then launch the same session intent so NvConnection resumes the
                // already-running host application with the new bitrate.
                LimeLog.info("Restarting stream activity at " + targetBitrate + " Kbps");
                bitrateReconnectPending = false;
                userInitiatedDisconnect = true;
                finish();
                startActivity(restartIntent);
                overridePendingTransition(0, 0);
            }
        });
    }

    /**
     * Show a toast hint about how to open the overlay menu
     */
    private void showOverlayMenuHint() {
        // Get the trigger button name
        int buttonNameResId;
        switch (prefConfig.overlayTriggerButton) {
            case "select":
                buttonNameResId = R.string.overlay_trigger_select;
                break;
            case "start":
                buttonNameResId = R.string.overlay_trigger_start;
                break;
            case "guide":
                buttonNameResId = R.string.overlay_trigger_guide;
                break;
            case "lb_rb":
                buttonNameResId = R.string.overlay_trigger_lb_rb;
                break;
            default:
                buttonNameResId = R.string.overlay_trigger_select;
                break;
        }
        String buttonName = getString(buttonNameResId);

        // Show the toast
        String message = getString(R.string.overlay_menu_hint, buttonName);
        Toast.makeText(this, Html.fromHtml(message), Toast.LENGTH_LONG).show();
    }

    private Runnable buildPostActionRunnable(CustomCommand command) {
        switch (command.getPostAction()) {
            case CustomCommand.POST_ACTION_CLOSE_MENU:
                return () -> overlayMenuView.closeMenu();
            case CustomCommand.POST_ACTION_DISCONNECT:
                return () -> closeStreamWithPrivacy(false);
            case CustomCommand.POST_ACTION_QUIT:
                return () -> closeStreamWithPrivacy(true);
            case CustomCommand.POST_ACTION_SLEEP:
                return () -> {
                    android.app.admin.DevicePolicyManager dpm =
                        (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                    android.content.ComponentName adminComponent =
                        new android.content.ComponentName(this, SleepDeviceAdmin.class);
                    if (dpm.isAdminActive(adminComponent)) {
                        dpm.lockNow();
                    } else {
                        Intent intent = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                        intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                        intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            getString(R.string.sleep_admin_explanation));
                        startActivity(intent);
                    }
                };
            default:
                return null;
        }
    }

    /**
     * Send a custom key command to the server
     */
    private void sendCustomKeyCommand(CustomCommand command, Runnable onComplete) {
        CustomCommand.KeyCombination keyCombination = command.getKeyCombination();

        // Windows virtual key codes for modifier keys
        final short VK_CONTROL = 0x11;
        final short VK_MENU = 0x12;    // Alt key
        final short VK_SHIFT = 0x10;
        final short VK_LWIN = 0x5B;    // Windows/Meta key

        // Build modifier flags
        byte modifierFlags = 0;
        if (keyCombination.isCtrl()) {
            modifierFlags |= KeyboardPacket.MODIFIER_CTRL;
        }
        if (keyCombination.isAlt()) {
            modifierFlags |= KeyboardPacket.MODIFIER_ALT;
        }
        if (keyCombination.isShift()) {
            modifierFlags |= KeyboardPacket.MODIFIER_SHIFT;
        }
        if (keyCombination.isMeta()) {
            modifierFlags |= KeyboardPacket.MODIFIER_META;
        }

        // Translate Android key code to Windows VK code format
        final short translatedKeyCode = keyboardTranslator.translate(keyCombination.getKeyCode(), -1);
        final byte finalModifiers = modifierFlags;

        // Skip if translation failed
        if (translatedKeyCode == 0) {
            return;
        }

        Handler handler = new Handler();

        // Step 1: Send modifier keys DOWN first (to mimic human key press)
        if (keyCombination.isCtrl()) {
            conn.sendKeyboardInput(VK_CONTROL, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
        }
        if (keyCombination.isAlt()) {
            conn.sendKeyboardInput(VK_MENU, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
        }
        if (keyCombination.isShift()) {
            conn.sendKeyboardInput(VK_SHIFT, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
        }
        if (keyCombination.isMeta()) {
            conn.sendKeyboardInput(VK_LWIN, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
        }

        // Step 2: Wait 50ms, then send main key DOWN (with modifier flags set)
        handler.postDelayed(() -> {
            conn.sendKeyboardInput(translatedKeyCode, KeyboardPacket.KEY_DOWN, finalModifiers, (byte) 0);

            // Step 3: Wait 50ms, then send main key UP (with modifier flags set)
            handler.postDelayed(() -> {
                conn.sendKeyboardInput(translatedKeyCode, KeyboardPacket.KEY_UP, finalModifiers, (byte) 0);

                // Step 4: Wait 50ms, then send modifier keys UP (in reverse order)
                handler.postDelayed(() -> {
                    if (keyCombination.isMeta()) {
                        conn.sendKeyboardInput(VK_LWIN, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
                    }
                    if (keyCombination.isShift()) {
                        conn.sendKeyboardInput(VK_SHIFT, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
                    }
                    if (keyCombination.isAlt()) {
                        conn.sendKeyboardInput(VK_MENU, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
                    }
                    if (keyCombination.isCtrl()) {
                        conn.sendKeyboardInput(VK_CONTROL, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
                    }

                    if (onComplete != null) {
                        handler.postDelayed(onComplete, 200);
                    }
                }, 50);
            }, 50);
        }, 50);
    }

    /**
     * Send Guide button press to the server
     */
    private void sendGuideButton() {
        // Send Guide button down using ControllerHandler
        controllerHandler.reportOscState(
            ControllerPacket.SPECIAL_BUTTON_FLAG, // buttonFlags - Guide button
            (short) 0, // leftStickX
            (short) 0, // leftStickY
            (short) 0, // rightStickX
            (short) 0, // rightStickY
            (byte) 0,  // leftTrigger
            (byte) 0   // rightTrigger
        );

        // Send Guide button up after a brief delay
        new Handler().postDelayed(() -> {
            controllerHandler.reportOscState(
                0,         // buttonFlags - no buttons pressed
                (short) 0, // leftStickX
                (short) 0, // leftStickY
                (short) 0, // rightStickX
                (short) 0, // rightStickY
                (byte) 0,  // leftTrigger
                (byte) 0   // rightTrigger
            );
        }, 100);
    }
    private boolean hasOwnRetainedSession() {
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        if (retained.state == RetainedStreamSessionCoordinator.State.PREPARING) {
            return isExactAutoWarmUpPreparing(retained);
        }
        return streamSessionId.equals(retained.streamSessionId)
                && retained.state != RetainedStreamSessionCoordinator.State.NONE
                && retained.state != RetainedStreamSessionCoordinator.State.TERMINATING;
    }

    private boolean hasLiveRetainedTransport() {
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        return streamSessionId.equals(retained.streamSessionId)
                && (retained.state == RetainedStreamSessionCoordinator.State.HOME_LIVE
                || retained.state == RetainedStreamSessionCoordinator.State.PARKED_LIVE)
                && RetainedStreamSessionCoordinator.canResumeInstantly();
    }

    private boolean hasAutoWarmUpTransportIdentity() {
        return autoWarmUpAttempt > 0L && transitionSpec != null;
    }

    private void clearAutoWarmUpIdentity() {
        autoWarmUpAttempt = 0L;
        autoWarmUpConvertedToGame = false;
        autoWarmUpRetained = false;
        Intent intent = getIntent();
        intent.removeExtra(EXTRA_AUTO_WARM_UP_ATTEMPT);
        intent.removeExtra(ConsoleActivity.EXTRA_WARM_UP_ATTEMPT);
        intent.removeExtra(ConsoleActivity.EXTRA_WARM_UP_TRANSITION_ID);
        intent.removeExtra(ConsoleActivity.EXTRA_WARM_UP_PENDING_GAME_ID);
        intent.removeExtra(ConsoleActivity.EXTRA_WARM_UP_PENDING_GAME_NAME);
        intent.removeExtra(ConsoleActivity.EXTRA_WARM_UP_PENDING_ARTWORK_ID);
        intent.removeExtra(ConsoleActivity.EXTRA_WARM_UP_PENDING_QUICK_LAUNCH);
    }

    private boolean handleAutoWarmUpConnectionFailure() {
        if (!hasAutoWarmUpTransportIdentity()) return false;
        if (autoWarmUpTransportStopRequested.get()) return true;
        if (autoWarmUpRetained && isOwnedHiddenAutoWarmUp()) {
            return !autoWarmUpTransportStopRequested.compareAndSet(false, true);
        }
        RetainedStreamSessionCoordinator.Snapshot preparing =
                RetainedStreamSessionCoordinator.snapshot();
        boolean exact = isExactAutoWarmUpPreparing(preparing);
        if (!autoWarmUpTransportStopRequested.compareAndSet(false, true)) return true;
        if (exact && !markAutoWarmUpReconnectRequired("connection_failure")) exact = false;
        boolean claimed = exact;
        runOnUiThread(() -> {
            displayedFailureDialog = true;
            if (claimed) {
                SessionResumeManager.save(this, getIntent(), streamSessionId);
                BackgroundStreamService.transportLost(this, streamSessionId);
            }
            stopConnection(() -> finish());
        });
        return true;
    }

    private boolean beginAutoWarmUpPreparing() {
        if (!hasAutoWarmUpTransportIdentity()) return false;
        boolean begun = RetainedStreamSessionCoordinator.beginPreparing(
                this, streamSessionId, autoWarmUpHostId(), autoWarmUpAppId(),
                transitionSpec.playniteGameId, transitionSpec.id, autoWarmUpAttempt);
        return begun;
    }

    private boolean cancelUnstartedAutoWarmUp(boolean finishActivity) {
        synchronized (autoWarmUpGateLock) {
            if (autoWarmUpHomeFrameAccepted.get()) return false;
            autoWarmUpTransportStopRequested.set(true);
            RetainedStreamSessionCoordinator.Snapshot preparing =
                    RetainedStreamSessionCoordinator.snapshot();
            boolean cancelled = isExactAutoWarmUpPreparing(preparing)
                    && RetainedStreamSessionCoordinator.cancelPreparing(
                    this, preparing.streamSessionId, preparing.hostId, preparing.appId,
                    preparing.transitionId, preparing.attempt);
            if (cancelled && finishActivity) runOnUiThread(this::finish);
            return cancelled;
        }
    }

    private boolean completeAutoWarmUpPreparing() {
        RetainedStreamSessionCoordinator.Snapshot preparing =
                RetainedStreamSessionCoordinator.snapshot();
        boolean completed = isExactAutoWarmUpPreparing(preparing)
                && RetainedStreamSessionCoordinator.completePreparing(
                this, preparing.streamSessionId, preparing.hostId, preparing.appId,
                preparing.transitionId, preparing.attempt);
        if (completed && autoWarmUpConvertedToGame) {
            clearAutoWarmUpIdentity();
        } else if (completed) {
            autoWarmUpRetained = true;
        }
        return completed;
    }

    private boolean markAutoWarmUpReconnectRequired(String reason) {
        RetainedStreamSessionCoordinator.Snapshot preparing =
                RetainedStreamSessionCoordinator.snapshot();
        boolean marked = isExactAutoWarmUpPreparing(preparing)
                && RetainedStreamSessionCoordinator.markPreparingReconnectRequired(
                this, preparing.streamSessionId, preparing.hostId, preparing.appId,
                preparing.transitionId, preparing.attempt);
        if (marked) {
            autoWarmUpRetained = false;
            LimeLog.info("Auto warm-up terminal transition=" + preparing.transitionId
                    + " attempt=" + preparing.attempt + " reason=" + reason);
        }
        return marked;
    }

    private boolean isOwnedHiddenAutoWarmUp() {
        if (autoWarmUpConvertedToGame || !hasAutoWarmUpTransportIdentity()) return false;
        if (isExactAutoWarmUpPreparing(
                RetainedStreamSessionCoordinator.snapshot())) return true;
        return autoWarmUpRetained
                && RetainedStreamSessionCoordinator.ownsLiveOrParkedSession(
                this, streamSessionId, autoWarmUpHostId(), autoWarmUpAppId(),
                transitionSpec.playniteGameId);
    }

    private boolean isExactAutoWarmUpPreparing(
            RetainedStreamSessionCoordinator.Snapshot preparing) {
        return preparing != null && hasAutoWarmUpTransportIdentity()
                && preparing.state == RetainedStreamSessionCoordinator.State.PREPARING
                && streamSessionId.equals(preparing.streamSessionId)
                && autoWarmUpHostId().equalsIgnoreCase(preparing.hostId)
                && autoWarmUpAppId() == preparing.appId
                && autoWarmUpAttempt == preparing.attempt
                && RetainedStreamSessionCoordinator.isPreparing(
                this, preparing.streamSessionId, preparing.hostId, preparing.appId,
                preparing.transitionId, preparing.attempt);
    }

    private String autoWarmUpHostId() {
        return normalizeOpaqueId(getIntent().getStringExtra(EXTRA_PC_UUID));
    }

    private int autoWarmUpAppId() {
        return getIntent().getIntExtra(
                EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
    }

    private static String normalizeOpaqueId(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= 128 ? normalized : "";
    }

    private static String normalizeGameId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
