package com.limelight.console;

import android.app.Activity;
import android.content.Context;
import android.view.SurfaceHolder;

import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.LimeLog;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns creation, start, and stop of the single NvConnection used by a session.
 * Game remains a listener/view adapter during migration.
 */
public final class MoonlightStreamSessionController implements StreamSessionController,
        StreamRenderTargetController {
    private static final AtomicLong NEXT_DIAGNOSTIC_SESSION_ID = new AtomicLong();

    interface Transport {
        void start();
        void stop();
    }

    private NvConnection connection;
    private StreamInputSender inputSender;
    private Transport transport;
    private MediaCodecDecoderRenderer videoRenderer;
    private final Executor stopExecutor;
    private final Executor callbackExecutor;
    private final Runnable quitHostApplication;
    private final long diagnosticSessionId = NEXT_DIAGNOSTIC_SESSION_ID.incrementAndGet();

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
        log("renderer_prepared");
        return videoRenderer;
    }

    public synchronized MediaCodecDecoderRenderer prepareRenderer(
            Activity activity,
            StreamRendererConfiguration configuration,
            CrashListener crashListener,
            PerfOverlayListener performanceListener,
            Runnable firstFrameRenderedCallback) {
        Objects.requireNonNull(configuration, "configuration");
        return prepareRenderer(activity,
                configuration.preferences,
                crashListener,
                configuration.consecutiveCrashCount,
                configuration.meteredData,
                configuration.requestedHdr,
                configuration.glRenderer,
                performanceListener,
                firstFrameRenderedCallback);
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
        inputSender = new NvConnectionInputSender(connection);
        AndroidAudioRenderer audioRenderer = new AndroidAudioRenderer(audioContext, enableAudioFx);
        transport = new Transport() {
            @Override public void start() {
                connection.start(audioRenderer, videoRenderer, listener);
            }

            @Override public void stop() {
                connection.stop();
            }
        };
        log("transport_initialized");
    }

    public synchronized void initializeTransport(
            Context appContext,
            Context audioContext,
            StreamTransportConfiguration configuration,
            NvConnectionListener listener) {
        Objects.requireNonNull(configuration, "configuration");
        initializeTransport(appContext,
                audioContext,
                configuration.host,
                configuration.httpsPort,
                configuration.uniqueId,
                configuration.streamConfiguration,
                configuration.cryptoProvider,
                configuration.serverCertificate,
                configuration.enableAudioFx,
                listener);
    }

    MoonlightStreamSessionController(
            Transport transport,
            Executor stopExecutor,
            Executor callbackExecutor,
            Runnable quitHostApplication) {
        this(transport, null, stopExecutor, callbackExecutor, quitHostApplication);
    }

    MoonlightStreamSessionController(
            Transport transport,
            StreamInputSender inputSender,
            Executor stopExecutor,
            Executor callbackExecutor,
            Runnable quitHostApplication) {
        this(stopExecutor, callbackExecutor, quitHostApplication);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.inputSender = inputSender;
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
            log("connect_requested");
        }
        transport.start();
    }

    public synchronized void onConnectionStarted() {
        if (state == SessionState.CONNECTING) {
            state = SessionState.CONNECTED;
            log("connected");
        }
    }

    public synchronized void onConnectionFailed() {
        if (state != SessionState.DISCONNECTING && state != SessionState.IDLE) {
            state = SessionState.RECOVERING;
            log("connection_failed");
        }
    }

    public synchronized void onConnectionTerminated() {
        if (state == SessionState.CONNECTING || state == SessionState.CONNECTED) {
            state = SessionState.RECOVERING;
            log("connection_terminated");
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
            log("disconnect_requested");
        }
        stopExecutor.execute(() -> {
            try {
                transport.stop();
            } finally {
                synchronized (MoonlightStreamSessionController.this) {
                    state = SessionState.IDLE;
                    log("transport_stopped");
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

    public synchronized StreamInputSender inputSender() {
        if (inputSender == null) {
            throw new IllegalStateException("Session input sender is not initialized");
        }
        return inputSender;
    }

    public long diagnosticSessionId() {
        return diagnosticSessionId;
    }

    @Override
    public synchronized boolean isRenderTargetSwitchReady() {
        return requireVideoRenderer().isRenderTargetSwitchReady();
    }

    @Override
    public synchronized void setInitialRenderTarget(SurfaceHolder renderTarget) {
        requireVideoRenderer().setRenderTarget(Objects.requireNonNull(renderTarget, "renderTarget"));
    }

    @Override
    public synchronized boolean switchToRenderTarget(SurfaceHolder renderTarget) {
        return requireVideoRenderer().switchToRenderTarget(
                Objects.requireNonNull(renderTarget, "renderTarget"));
    }

    @Override
    public synchronized boolean switchToBackgroundSurface() {
        return requireVideoRenderer().switchToBackgroundSurface();
    }

    @Override
    public synchronized void prepareRendererForStop() {
        requireVideoRenderer().prepareForStop();
    }

    private MediaCodecDecoderRenderer requireVideoRenderer() {
        if (videoRenderer == null) {
            throw new IllegalStateException("Session renderer is not prepared");
        }
        return videoRenderer;
    }

    private void log(String event) {
        LimeLog.info("MoonWakerSession event=" + event +
                " sessionId=" + diagnosticSessionId + " state=" + state);
    }

    private List<Runnable> drainStopCallbacks() {
        List<Runnable> callbacks = new ArrayList<>(stopCallbacks);
        stopCallbacks.clear();
        return callbacks;
    }
}
