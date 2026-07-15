package com.limelight.console;

import android.app.Activity;
import android.content.Context;

import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.preferences.PreferenceConfiguration;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Owns creation, start, and stop of the single NvConnection used by a session.
 * Game remains a listener/view adapter during migration.
 */
public final class MoonlightStreamSessionController implements StreamSessionController {
    interface Transport {
        void start();
        void stop();
    }

    private NvConnection connection;
    private Transport transport;
    private MediaCodecDecoderRenderer videoRenderer;
    private final Executor stopExecutor;
    private final Executor callbackExecutor;
    private final Runnable quitHostApplication;

    private SessionState state = SessionState.IDLE;
    private boolean startRequested;
    private boolean stopRequested;
    private final List<Runnable> stopCallbacks = new ArrayList<>();

    public MoonlightStreamSessionController(
            Executor stopExecutor,
            Executor callbackExecutor,
            Runnable quitHostApplication) {
        this.stopExecutor = Objects.requireNonNull(stopExecutor, "stopExecutor");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.quitHostApplication = Objects.requireNonNull(quitHostApplication, "quitHostApplication");
    }

    public synchronized MediaCodecDecoderRenderer prepareRenderer(
            Activity activity,
            PreferenceConfiguration preferences,
            CrashListener crashListener,
            int consecutiveCrashCount,
            boolean meteredData,
            boolean requestedHdr,
            String glRenderer,
            PerfOverlayListener performanceListener,
            Runnable firstFrameRenderedCallback) {
        if (videoRenderer != null || transport != null || startRequested) {
            throw new IllegalStateException("Session renderer already prepared");
        }
        videoRenderer = new MediaCodecDecoderRenderer(activity, preferences, crashListener,
                consecutiveCrashCount, meteredData, requestedHdr, glRenderer,
                performanceListener, firstFrameRenderedCallback);
        return videoRenderer;
    }

    public synchronized void initializeTransport(
            Context appContext,
            Context audioContext,
            ComputerDetails.AddressTuple host,
            int httpsPort,
            String uniqueId,
            StreamConfiguration configuration,
            LimelightCryptoProvider cryptoProvider,
            X509Certificate serverCertificate,
            boolean enableAudioFx,
            NvConnectionListener listener) {
        if (videoRenderer == null) {
            throw new IllegalStateException("Renderer must be prepared before transport");
        }
        if (transport != null || connection != null || startRequested) {
            throw new IllegalStateException("Session transport already initialized");
        }
        connection = new NvConnection(appContext, host, httpsPort, uniqueId, configuration,
                cryptoProvider, serverCertificate);
        AndroidAudioRenderer audioRenderer = new AndroidAudioRenderer(audioContext, enableAudioFx);
        transport = new Transport() {
            @Override public void start() {
                connection.start(audioRenderer, videoRenderer, listener);
            }

            @Override public void stop() {
                connection.stop();
            }
        };
    }

    MoonlightStreamSessionController(
            Transport transport,
            Executor stopExecutor,
            Executor callbackExecutor,
            Runnable quitHostApplication) {
        this(stopExecutor, callbackExecutor, quitHostApplication);
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public synchronized SessionState state() {
        return state;
    }

    @Override
    public void connect() {
        synchronized (this) {
            if (transport == null) {
                throw new IllegalStateException("Session transport is not initialized");
            }
            if (startRequested) {
                throw new IllegalStateException("Session transport already started");
            }
            startRequested = true;
            state = SessionState.CONNECTING;
        }
        transport.start();
    }

    public synchronized void onConnectionStarted() {
        if (state == SessionState.CONNECTING) state = SessionState.CONNECTED;
    }

    public synchronized void onConnectionFailed() {
        if (state != SessionState.DISCONNECTING && state != SessionState.IDLE) {
            state = SessionState.RECOVERING;
        }
    }

    public synchronized void onConnectionTerminated() {
        if (state == SessionState.CONNECTING || state == SessionState.CONNECTED) {
            state = SessionState.RECOVERING;
        }
    }

    @Override
    public void disconnectTransport() {
        disconnectTransport(null);
    }

    public void disconnectTransport(Runnable afterStopped) {
        synchronized (this) {
            if (afterStopped != null) stopCallbacks.add(afterStopped);
            if (stopRequested) {
                return;
            }
            if (!startRequested) {
                List<Runnable> callbacks = drainStopCallbacks();
                callbacks.forEach(callbackExecutor::execute);
                return;
            }
            stopRequested = true;
            state = SessionState.DISCONNECTING;
        }
        stopExecutor.execute(() -> {
            try {
                transport.stop();
            } finally {
                synchronized (MoonlightStreamSessionController.this) {
                    state = SessionState.IDLE;
                }
                List<Runnable> callbacks;
                synchronized (MoonlightStreamSessionController.this) {
                    callbacks = drainStopCallbacks();
                }
                callbacks.forEach(callbackExecutor::execute);
            }
        });
    }

    @Override
    public void quitHostApplication() {
        quitHostApplication.run();
    }

    /** Temporary input-adapter escape hatch; it does not transfer ownership. */
    public NvConnection legacyConnection() {
        if (connection == null) throw new IllegalStateException("No Android connection in test controller");
        return connection;
    }

    private List<Runnable> drainStopCallbacks() {
        List<Runnable> callbacks = new ArrayList<>(stopCallbacks);
        stopCallbacks.clear();
        return callbacks;
    }
}
