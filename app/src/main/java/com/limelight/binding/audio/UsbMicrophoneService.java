package com.limelight.binding.audio;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.limelight.R;
import com.limelight.console.ConsoleActivity;
import com.limelight.console.HostGatewayStore;
import com.limelight.gateway.GatewayConnection;

public final class UsbMicrophoneService extends Service implements
        SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String PREF_ENABLED = "checkbox_usb_microphone";
    public static final String CONSOLE_PREFS = "console_dashboard";
    public static final String SELECTED_HOST = "selected_host";
    public static final String GATEWAY_PREFS = "host_gateway_connections";
    public static final String STATE_PREFS = "usb_microphone_status";
    public static final String STATE_KEY = "state";
    public static final String STATE_OFF = "off";
    public static final String STATE_GATEWAY_REQUIRED = "gateway_required";
    public static final String STATE_PERMISSION_REQUIRED = "permission_required";
    public static final String STATE_UNSUPPORTED = "unsupported";
    public static final String STATE_CONNECTING = "connecting";
    public static final String STATE_ACTIVE = "active";
    public static final String STATE_GATEWAY_RECONNECTING = "gateway_reconnecting";
    public static final String STATE_WAITING_USB = "waiting_usb";
    public static final String STATE_USB_MULTIPLE = "usb_multiple";
    public static final String STATE_USB_UNAVAILABLE = "usb_unavailable";
    public static final String STATE_WORKER_MISSING = "worker_missing";
    public static final String STATE_HOST_UNAVAILABLE = "host_unavailable";
    public static final String STATE_STEAM_ENDPOINT_UNAVAILABLE =
            "steam_endpoint_missing_or_ambiguous_or_unsupported";

    private static final String ACTION_START =
            "com.limelight.action.START_USB_MICROPHONE";
    private static final String ACTION_STOP =
            "com.limelight.action.STOP_USB_MICROPHONE";
    private static final String ACTION_RETRY =
            "com.limelight.action.RETRY_USB_MICROPHONE";
    private static final String CHANNEL_ID = "moonwaker_usb_microphone";
    private static final int NOTIFICATION_ID = 7104;
    private static final long INITIAL_RETRY_DELAY_MS = 1_000;
    private static final long MAX_RETRY_DELAY_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable reloadCapture = this::reloadCapture;
    private final Runnable retryCapture = () -> {
        retryScheduled = false;
        startCapture();
    };
    private SharedPreferences defaultPreferences;
    private SharedPreferences consolePreferences;
    private SharedPreferences gatewayPreferences;
    private AudioManager audioManager;
    private AudioDeviceCallback audioDeviceCallback;
    private UsbMicrophoneCapture capture;
    private boolean retryScheduled;
    private boolean waitingForUsb;
    private long retryDelayMs = INITIAL_RETRY_DELAY_MS;

    public static boolean hasConfiguration(Context context) {
        String host = context.getSharedPreferences(CONSOLE_PREFS, MODE_PRIVATE)
                .getString(SELECTED_HOST, "");
        return host != null && !host.isEmpty()
                && new HostGatewayStore(context).loadForHost(host, null) != null;
    }

    public static String state(Context context) {
        return context.getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
                .getString(STATE_KEY, STATE_OFF);
    }

    public static void publishState(Context context, String state) {
        context.getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
                .putString(STATE_KEY, state).apply();
    }

    public static boolean isDisabledAttentionState(String state) {
        return STATE_GATEWAY_REQUIRED.equals(state) || STATE_PERMISSION_REQUIRED.equals(state)
                || STATE_UNSUPPORTED.equals(state);
    }

    public static void disableWithState(Context context, String state) {
        publishState(context, state);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PREF_ENABLED, false).apply();
        stop(context);
    }

    public static void startFromVisibleActivity(Context context) {
        startForegroundAction(context, ACTION_START);
    }

    public static void retryFromVisibleActivity(Context context) {
        startForegroundAction(context, ACTION_RETRY);
    }

    private static void startForegroundAction(Context context, String action) {
        Intent intent = new Intent(context, UsbMicrophoneService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, UsbMicrophoneService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.usb_microphone_notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        defaultPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        consolePreferences = getSharedPreferences(CONSOLE_PREFS, MODE_PRIVATE);
        gatewayPreferences = getSharedPreferences(GATEWAY_PREFS, MODE_PRIVATE);
        defaultPreferences.registerOnSharedPreferenceChangeListener(this);
        consolePreferences.registerOnSharedPreferenceChangeListener(this);
        gatewayPreferences.registerOnSharedPreferenceChangeListener(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback = new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    handler.post(UsbMicrophoneService.this::onUsbDevicesChanged);
                }

                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    handler.post(UsbMicrophoneService.this::onUsbDevicesChanged);
                }
            };
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            cancelRetry();
            waitingForUsb = false;
            publishState(this, STATE_OFF);
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean(PREF_ENABLED, false).apply();
            stopCapture();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_RETRY.equals(intent.getAction())) {
            waitingForUsb = false;
            startAsForeground(R.string.usb_microphone_notification_connecting);
            publishState(this, STATE_CONNECTING);
            reloadCapture();
            return START_NOT_STICKY;
        }

        if (shouldReloadOnStart(capture != null, retryScheduled, waitingForUsb)) {
            startAsForeground(R.string.usb_microphone_notification_connecting);
            publishState(this, STATE_CONNECTING);
            reloadCapture();
        }
        return START_NOT_STICKY;
    }

    static boolean shouldReloadOnStart(boolean hasCapture, boolean retryScheduled,
                                       boolean waitingForUsb) {
        return !hasCapture && !retryScheduled && !waitingForUsb;
    }

    static boolean isRetryableState(String state) {
        return "error".equals(state) || "unavailable".equals(state)
                || STATE_WORKER_MISSING.equals(state)
                || STATE_STEAM_ENDPOINT_UNAVAILABLE.equals(state);
    }

    static String usbWaitStateForCount(int count) {
        return count == 0 ? STATE_WAITING_USB : count == 1 ? null : STATE_USB_MULTIPLE;
    }

    static long nextRetryDelay(long currentDelayMs) {
        return Math.min(Math.max(INITIAL_RETRY_DELAY_MS, currentDelayMs * 2),
                MAX_RETRY_DELAY_MS);
    }

    public static boolean isGatewayKeyForSelectedHost(String selectedHost, String key) {
        if (selectedHost == null || selectedHost.isEmpty() || key == null) return false;
        String prefix = selectedHost + ".";
        if (!key.startsWith(prefix)) return false;
        String suffix = key.substring(prefix.length());
        return "paired".equals(suffix) || "endpoint".equals(suffix)
                || "token".equals(suffix) || "certificate".equals(suffix)
                || "integration_profile".equals(suffix);
    }

    private void startAsForeground(int textResource) {
        Notification notification = buildNotification(textResource);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(int textResource) {
        Intent open = new Intent(this, ConsoleActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, UsbMicrophoneService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_overlay_microphone)
                .setContentTitle(getString(R.string.usb_microphone_notification_title))
                .setContentText(getString(textResource))
                .setContentIntent(content)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(0, getString(R.string.usb_microphone_notification_stop), stopAction)
                .build();
    }

    private void reloadCapture() {
        cancelRetry();
        retryDelayMs = INITIAL_RETRY_DELAY_MS;
        startCapture();
    }

    private void startCapture() {
        stopCapture();
        if (!PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(PREF_ENABLED, false)) {
            stopForeground(true);
            stopSelf();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            disableWithState(this, STATE_UNSUPPORTED);
            stopForeground(true);
            stopSelf();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            disableWithState(this, STATE_PERMISSION_REQUIRED);
            stopForeground(true);
            stopSelf();
            return;
        }
        String host = getSharedPreferences(CONSOLE_PREFS, MODE_PRIVATE)
                .getString(SELECTED_HOST, "");
        GatewayConnection gateway = new HostGatewayStore(this).loadForHost(host, null);
        if (host == null || host.isEmpty() || gateway == null) {
            disableWithState(this, STATE_GATEWAY_REQUIRED);
            stopForeground(true);
            stopSelf();
            return;
        }
        int usbInputs = usbInputCount();
        if (usbInputs != 1) {
            enterUsbWait(usbInputs);
            return;
        }
        waitingForUsb = false;
        publishState(this, STATE_CONNECTING);
        UsbMicrophoneCapture next = new UsbMicrophoneCapture(this, gateway,
                (source, state) -> handler.post(() -> onCaptureState(source, state)));
        capture = next;
        next.start();
    }

    private void onCaptureState(UsbMicrophoneCapture source, String state) {
        if (capture != source) return;
        if ("active".equals(state)) {
            cancelRetry();
            retryDelayMs = INITIAL_RETRY_DELAY_MS;
            startAsForeground(R.string.usb_microphone_notification_active);
            publishState(this, STATE_ACTIVE);
            return;
        }
        capture = null;
        source.stop();
        if ("permission_revoked".equals(state)) {
            disableWithState(this, STATE_PERMISSION_REQUIRED);
            stopForeground(true);
            stopSelf();
            return;
        }
        if ("usb_missing".equals(state) || "usb_multiple".equals(state)
                || "usb_removed".equals(state)) {
            enterUsbWait(usbInputCount());
            return;
        }
        if (STATE_USB_UNAVAILABLE.equals(state)) {
            waitingForUsb = true;
            applyForegroundState(STATE_USB_UNAVAILABLE);
            return;
        }
        if (isRetryableState(state)) {
            applyForegroundState("error".equals(state)
                    ? STATE_GATEWAY_RECONNECTING
                    : STATE_WORKER_MISSING.equals(state) ? STATE_WORKER_MISSING
                    : STATE_STEAM_ENDPOINT_UNAVAILABLE.equals(state)
                    ? STATE_STEAM_ENDPOINT_UNAVAILABLE : STATE_HOST_UNAVAILABLE);
            scheduleRetry();
            return;
        }
        cancelRetry();
        stopForeground(true);
        stopSelf();
    }

    private int usbInputCount() {
        return UsbMicrophoneCapture.usbInputCount(
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS));
    }

    private void onUsbDevicesChanged() {
        int count = usbInputCount();
        if (count != 1) {
            enterUsbWait(count);
        } else if (waitingForUsb) {
            waitingForUsb = false;
            reloadCapture();
        }
    }

    private void enterUsbWait(int count) {
        cancelRetry();
        stopCapture();
        waitingForUsb = true;
        applyForegroundState(usbWaitStateForCount(count));
    }

    private void applyForegroundState(String state) {
        startAsForeground(notificationTextForState(state));
        publishState(this, state);
    }

    static int notificationTextForState(String state) {
        if (STATE_ACTIVE.equals(state)) return R.string.usb_microphone_notification_active;
        if (STATE_WAITING_USB.equals(state)) return R.string.usb_microphone_notification_waiting_usb;
        if (STATE_USB_MULTIPLE.equals(state)) return R.string.usb_microphone_notification_usb_multiple;
        if (STATE_USB_UNAVAILABLE.equals(state)) return R.string.usb_microphone_notification_usb_unavailable;
        if (STATE_WORKER_MISSING.equals(state)) return R.string.usb_microphone_notification_worker_missing;
        if (STATE_HOST_UNAVAILABLE.equals(state)) return R.string.usb_microphone_notification_host_unavailable;
        if (STATE_STEAM_ENDPOINT_UNAVAILABLE.equals(state)) return R.string.usb_microphone_notification_steam_endpoint;
        return R.string.usb_microphone_notification_reconnecting;
    }

    private void scheduleRetry() {
        if (retryScheduled) return;
        retryScheduled = true;
        handler.postDelayed(retryCapture, retryDelayMs);
        retryDelayMs = nextRetryDelay(retryDelayMs);
    }

    private void cancelRetry() {
        handler.removeCallbacks(retryCapture);
        retryScheduled = false;
    }

    private void stopCapture() {
        UsbMicrophoneCapture previous = capture;
        capture = null;
        if (previous != null) previous.stop();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if ((preferences == defaultPreferences && !PREF_ENABLED.equals(key))
                || (preferences == consolePreferences && !SELECTED_HOST.equals(key))
                || (preferences == gatewayPreferences && !isGatewayKeyForSelectedHost(
                consolePreferences.getString(SELECTED_HOST, ""), key))) return;
        cancelRetry();
        waitingForUsb = false;
        handler.removeCallbacks(reloadCapture);
        handler.post(reloadCapture);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        retryScheduled = false;
        stopCapture();
        defaultPreferences.unregisterOnSharedPreferenceChangeListener(this);
        consolePreferences.unregisterOnSharedPreferenceChangeListener(this);
        gatewayPreferences.unregisterOnSharedPreferenceChangeListener(this);
        if (audioDeviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
            audioDeviceCallback = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
