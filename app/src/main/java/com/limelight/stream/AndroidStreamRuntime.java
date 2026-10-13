package com.limelight.stream;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.MoonWakerApplication;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.console.PlayniteTransitionGateway;
import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.av.video.SwitchableVideoDecoderRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.jni.BackgroundStreamBridge;
import com.limelight.session.SessionEvent;
import com.limelight.session.SessionModel;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Production seams of {@link StreamRuntime}. Holds only the application context. */
public final class AndroidStreamRuntime {
    private static final ExecutorService INPUT_EXECUTOR = Executors.newSingleThreadExecutor(
            action -> {
                Thread thread = new Thread(action, "MoonWaker-InputRelease");
                thread.setDaemon(true);
                return thread;
            });

    private AndroidStreamRuntime() { }

    /**
     * Creates and registers the runtime for a new transport generation, or null while another
     * generation is live and not stopping. The launch intent is copied, never the Activity.
     */
    public static StreamRuntime create(Context context, Intent launchIntent,
                                       String streamSessionId, String executionProfileId,
                                       NvConnection connection, AndroidAudioRenderer audio,
                                       SwitchableVideoDecoderRenderer video) {
        Context app = context.getApplicationContext();
        return StreamRuntime.create(streamSessionId, executionProfileId,
                new AndroidTransport(connection, audio, video),
                new AndroidPlatform(app, new Intent(launchIntent)));
    }

    /** Publishes the presentation's latest launch record for reconnect and host termination. */
    public static void publishLaunchRecord(StreamRuntime runtime, Intent launchIntent) {
        if (runtime != null && runtime.platform() instanceof AndroidPlatform) {
            ((AndroidPlatform) runtime.platform()).setLaunchIntent(new Intent(launchIntent));
        }
    }

    static final class AndroidTransport implements StreamRuntime.Transport {
        private final NvConnection connection;
        private final AndroidAudioRenderer audio;
        private final SwitchableVideoDecoderRenderer video;

        AndroidTransport(NvConnection connection, AndroidAudioRenderer audio,
                         SwitchableVideoDecoderRenderer video) {
            this.connection = connection;
            this.audio = audio;
            this.video = video;
        }

        @Override public void start(NvConnectionListener listener) {
            connection.start(audio, video, listener);
        }
        @Override public void stop() { connection.stop(); }
        @Override public long controlGeneration() { return connection.getControlGeneration(); }
        @Override public void abandonStart() { connection.abandonStart(); }
        @Override public boolean reauthorizeControlGeneration(long expected, long generation) {
            return connection.reauthorizeControlGeneration(expected, generation);
        }
        @Override public void requestIdrFrame() { BackgroundStreamBridge.requestIdrFrame(); }
        @Override public boolean attachDecoder(VideoDecoderRenderer decoder) {
            return video.attach(decoder);
        }
        @Override public void detachDecoder() {
            if (!video.isDetached()) video.detach();
        }
        @Override public void setAudioVolume(float volume) { audio.setVolume(volume); }
        @Override public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
            video.setHdrMode(enabled, hdrMetadata);
        }

        @Override public void sendMouseMove(short x, short y) { connection.sendMouseMove(x, y); }
        @Override public void sendMousePosition(short x, short y, short w, short h) {
            connection.sendMousePosition(x, y, w, h);
        }
        @Override public void sendMouseMoveAsMousePosition(short x, short y, short w, short h) {
            connection.sendMouseMoveAsMousePosition(x, y, w, h);
        }
        @Override public void sendMouseButtonDown(byte b) { connection.sendMouseButtonDown(b); }
        @Override public void sendMouseButtonUp(byte b) { connection.sendMouseButtonUp(b); }
        @Override public void sendControllerInput(short n, short mask, int buttons, byte lt,
                                                  byte rt, short lx, short ly, short rx,
                                                  short ry) {
            connection.sendControllerInput(n, mask, buttons, lt, rt, lx, ly, rx, ry);
        }
        @Override public void sendKeyboardInput(short key, byte direction, byte modifier,
                                                byte flags) {
            connection.sendKeyboardInput(key, direction, modifier, flags);
        }
        @Override public void sendMouseScroll(byte c) { connection.sendMouseScroll(c); }
        @Override public void sendMouseHScroll(byte c) { connection.sendMouseHScroll(c); }
        @Override public void sendMouseHighResScroll(short a) {
            connection.sendMouseHighResScroll(a);
        }
        @Override public void sendMouseHighResHScroll(short a) {
            connection.sendMouseHighResHScroll(a);
        }
        @Override public int sendTouchEvent(byte t, int p, float x, float y, float pr,
                                            float ma, float mi, short r) {
            return connection.sendTouchEvent(t, p, x, y, pr, ma, mi, r);
        }
        @Override public int sendPenEvent(byte t, byte tool, byte b, float x, float y, float pr,
                                          float ma, float mi, short r, byte tilt) {
            return connection.sendPenEvent(t, tool, b, x, y, pr, ma, mi, r, tilt);
        }
        @Override public int sendControllerArrivalEvent(byte n, short mask, byte type,
                                                        int flags, short caps) {
            return connection.sendControllerArrivalEvent(n, mask, type, flags, caps);
        }
        @Override public int sendControllerTouchEvent(byte n, byte t, int p, float x, float y,
                                                      float pr) {
            return connection.sendControllerTouchEvent(n, t, p, x, y, pr);
        }
        @Override public int sendControllerMotionEvent(byte n, byte t, float x, float y,
                                                       float z) {
            return connection.sendControllerMotionEvent(n, t, x, y, z);
        }
        @Override public void sendControllerBatteryEvent(byte n, byte s, byte p) {
            connection.sendControllerBatteryEvent(n, s, p);
        }
        @Override public void sendUtf8Text(String text) { connection.sendUtf8Text(text); }
    }

    static final class AndroidPlatform implements StreamRuntime.Platform {
        private final Context app;
        private final Handler main = new Handler(Looper.getMainLooper());
        private Intent launchIntent;
        private WifiManager.WifiLock highPerfLock;
        private WifiManager.WifiLock lowLatencyLock;

        AndroidPlatform(Context app, Intent launchIntent) {
            this.app = app;
            this.launchIntent = launchIntent;
        }

        synchronized void setLaunchIntent(Intent intent) { launchIntent = intent; }

        private synchronized Intent launchIntent() { return new Intent(launchIntent); }

        @Override public synchronized void acquireWifiLocks() {
            WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            try {
                highPerfLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "Moonlight High Perf Lock");
                highPerfLock.setReferenceCounted(false);
                highPerfLock.acquire();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    lowLatencyLock = wifi.createWifiLock(
                            WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Moonlight Low Latency Lock");
                    lowLatencyLock.setReferenceCounted(false);
                    lowLatencyLock.acquire();
                }
            } catch (SecurityException e) {
                // Some Samsung devices throw here despite WAKE_LOCK in the manifest.
                LimeLog.warning("Wi-Fi lock unavailable: " + e.getMessage());
            }
        }

        @Override public synchronized void releaseWifiLocks() {
            if (lowLatencyLock != null) lowLatencyLock.release();
            if (highPerfLock != null) highPerfLock.release();
            lowLatencyLock = null;
            highPerfLock = null;
        }

        @Override public boolean requestForegroundService(String id, long generation) {
            return BackgroundStreamService.establish(app, id, generation);
        }

        @Override public void parkService(String id, long generation, int minutes) {
            BackgroundStreamService.park(app, id, generation, minutes);
        }

        @Override public void resumeService(String id, long generation) {
            BackgroundStreamService.resume(app, id, generation);
        }

        @Override public void endService(String id, long generation, boolean transportLost) {
            BackgroundStreamService.end(app, id, generation, transportLost);
        }

        @Override public boolean appForeground() {
            return MoonWakerApplication.isAppForeground();
        }

        @Override public int retentionMinutes() {
            return BackgroundStreamPreferences.readMinutes(app);
        }

        @Override public SessionModel sessionModel() { return SessionModel.of(app); }

        @Override public void saveReconnectRecord(String id) {
            sessionModel().post(new SessionEvent.ReconnectSaved(launchIntent(), id, true));
        }

        @Override public void clearReconnectRecord(String id) {
            sessionModel().post(new SessionEvent.ReconnectCleared(id));
        }

        @Override public void controlGenerationBound(String id, long controlGeneration) {
            Intent record;
            synchronized (this) {
                launchIntent.putExtra(Game.EXTRA_CONTROL_GENERATION, controlGeneration);
                record = new Intent(launchIntent);
            }
            sessionModel().post(new SessionEvent.ReconnectSaved(record, id, false));
        }

        @Override public void terminateHost(StreamRuntime runtime,
                                            SessionModel.Snapshot session,
                                            String gameId,
                                            SessionModel.TerminationCallback
                                                    completion) {
            Intent record = launchIntent();
            boolean child = record.getBooleanExtra(Game.EXTRA_CHILD_PROFILE, false);
            PlayniteTransitionGateway.ActorContext actor = child
                    ? PlayniteTransitionGateway.ActorContext.child(
                    session.profileId, session.executionProfileId)
                    : PlayniteTransitionGateway.ActorContext.parent(session.profileId);
            java.util.concurrent.atomic.AtomicBoolean gameLeft =
                    new java.util.concurrent.atomic.AtomicBoolean();
            WholeSessionTermination.start(() -> { }, () -> { },
                    local -> runtime.stop(result -> local.complete(
                            result == TransportStopCoordinator.Result.STOPPED)),
                    host -> new Thread(() -> {
                        HostSessionTermination.Outcome outcome = HostSessionTermination.run(
                                app, session.hostId, record.getStringExtra(Game.EXTRA_HOST),
                                record.getIntExtra(Game.EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT),
                                record.getIntExtra(Game.EXTRA_HTTPS_PORT, 0),
                                record.getStringExtra(Game.EXTRA_UNIQUEID),
                                record.getByteArrayExtra(Game.EXTRA_SERVER_CERT),
                                session.streamSessionId, session.appId,
                                gameId.isEmpty() ? session.playniteGameId : gameId,
                                session.controlGeneration, actor);
                        // A closed stream ends the session; a game left behind it is reported.
                        gameLeft.set(outcome
                                == HostSessionTermination.Outcome.CLOSED_GAME_UNVERIFIED);
                        host.complete(outcome.streamClosed());
                    }, "MoonWaker-TerminateHeadless").start(),
                    success -> {
                        if (completion == null) return;
                        if (success && gameLeft.get()) {
                            completion.gameLeftOnHost();
                            return;
                        }
                        if (!success) completion.transportUnsafe();
                        completion.complete(success);
                    });
        }

        @Override public void closeAbandonedLaunch(String streamSessionId,
                                                   String executionProfileId,
                                                   long controlGeneration) {
            Intent record = launchIntent();
            String profileId = com.limelight.gateway.GatewayConnection.normalizeProfileId(
                    record.getStringExtra(Game.EXTRA_PROFILE_ID));
            PlayniteTransitionGateway.ActorContext actor =
                    record.getBooleanExtra(Game.EXTRA_CHILD_PROFILE, false)
                    ? PlayniteTransitionGateway.ActorContext.child(profileId, executionProfileId)
                    : PlayniteTransitionGateway.ActorContext.parent(profileId);
            // Any close that left something of this start is told to the user; a host a
            // newer generation or another owner took over is silent.
            new Thread(() -> Game.reportGameLeftOnHost(app,
                    HostSessionTermination.closeAbandonedStart(
                    app, record.getStringExtra(Game.EXTRA_PC_UUID),
                    record.getStringExtra(Game.EXTRA_HOST),
                    record.getIntExtra(Game.EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT),
                    record.getIntExtra(Game.EXTRA_HTTPS_PORT, 0),
                    record.getStringExtra(Game.EXTRA_UNIQUEID),
                    record.getByteArrayExtra(Game.EXTRA_SERVER_CERT),
                    // No game id: this attempt has no verified game identity, so the close stops
                    // no game; one still running reports CLOSED_GAME_UNVERIFIED, told to the user.
                    streamSessionId, record.getIntExtra(Game.EXTRA_APP_ID, 0), "",
                    controlGeneration, actor), true),
                    "MoonWaker-CloseAbandonedLaunch").start();
        }

        @Override public void runtimeEnded() {
            com.limelight.console.ConsoleStreamTransitionCoordinator.endDetachedOwner(
                    launchIntent().getStringExtra(Game.EXTRA_PC_UUID));
        }

        @Override public Executor stopExecutor() {
            return action -> new Thread(action, "Moonlight-StopConnection").start();
        }

        @Override public Executor mainExecutor() { return main::post; }

        @Override public Executor inputExecutor() { return INPUT_EXECUTOR; }

        @Override public void record(String level, String event, Object... fields) {
            MoonWakerDiagnostics.record(level, "android.stream-runtime", event, fields);
        }
    }
}
