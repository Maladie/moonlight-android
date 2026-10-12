package com.limelight.stream;

import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamInput;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.session.SessionEvent;
import com.limelight.session.SessionModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Owns one physical stream transport generation independently of any Activity: the native
 * connection and its single stop completion, the MoonBridge listener, audio and switchable video
 * renderers, Wi-Fi locks and the foreground-service lifetime. A presentation (Game) attaches and
 * detaches; detaching never stops a transport that is allowed to survive.
 */
public final class StreamRuntime implements NvConnectionListener,
        SessionModel.Controller {

    /** The native transport plus the renderers bound to it at start. */
    public interface Transport extends StreamInput {
        void start(NvConnectionListener listener);
        void stop();
        long controlGeneration();
        /** The start was cancelled: a host permit not used yet is handed back, not used. */
        void abandonStart();
        boolean reauthorizeControlGeneration(long expected, long generation);
        void requestIdrFrame();
        boolean attachDecoder(VideoDecoderRenderer decoder);
        void detachDecoder();
        void setAudioVolume(float volume);
        /** Caches HDR state across decoder detach/attach. */
        void setHdrMode(boolean enabled, byte[] hdrMetadata);
    }

    /** Android effects, isolated so the owner runs in the JVM. */
    public interface Platform {
        void acquireWifiLocks();
        void releaseWifiLocks();
        /** Requests the mediaPlayback foreground service; false when Android refused it. */
        boolean requestForegroundService(String streamSessionId, long generation);
        void parkService(String streamSessionId, long generation, int retentionMinutes);
        void resumeService(String streamSessionId, long generation);
        void endService(String streamSessionId, long generation, boolean transportLost);
        boolean appForeground();
        int retentionMinutes();
        void saveReconnectRecord(String streamSessionId);
        void clearReconnectRecord(String streamSessionId);
        void controlGenerationBound(String streamSessionId, long controlGeneration);
        /** Host-side whole-session termination for a runtime without a presentation. */
        void terminateHost(StreamRuntime runtime, SessionModel.Snapshot session,
                           String gameId, SessionModel.TerminationCallback completion);
        /**
         * Closes the whole host stream a launch opened after this runtime was abandoned (the
         * start was cancelled while the launch was in flight); any failure is told to the user.
         */
        void closeAbandonedLaunch(String streamSessionId, String executionProfileId,
                                  long controlGeneration);
        /** The transport generation ended; release presentation-less host-side owners. */
        void runtimeEnded();
        Executor stopExecutor();
        Executor mainExecutor();
        Executor inputExecutor();
        void record(String level, String event, Object... fields);
        /** The process session model this transport reports to. */
        SessionModel sessionModel();
    }

    /** The Activity-side adapter. Callbacks arrive only while it is the current attachment. */
    public interface Presentation extends NvConnectionListener {
        boolean parkPreparingTransport(SessionModel.Snapshot preparing);
        boolean cancelPreparingTransport(SessionModel.Snapshot preparing);
        boolean preparingHomeFrameSubmitted(SessionModel.Snapshot preparing);
        void switchGame(SessionModel.SwitchRequest request,
                        SessionModel.SwitchCallback completion);
        void terminateRetainedSession(String gameId,
                                      SessionModel.TerminationCallback completion);
        /** The runtime parked the stream (decoder detached, audio muted); hide video and input. */
        void onTransportParked();
        /** The runtime finished an explicit disconnect; the presentation may finish itself. */
        void onRuntimeDisconnected();
        /** Presentation-owned effects that must run before the native stop. */
        void onTransportStopping();
        /** False while this presentation shows the stream (resumed with a valid surface). */
        boolean canPark();
    }

    /** Immutable identity of one presentation's attachment to one transport generation. */
    public static final class Attachment {
        public final long transportGeneration;
        public final long serial;
        public final StreamInput input;

        private Attachment(long transportGeneration, long serial, StreamInput input) {
            this.transportGeneration = transportGeneration;
            this.serial = serial;
            this.input = input;
        }
    }

    enum State { NEW, CONNECTING, CONNECTED, STOPPING, STOPPED }
    enum ForegroundService { NONE, REQUESTED, ESTABLISHED, ENDED }
    enum ReleaseBarrier { DONE, PENDING, ABANDONED }

    private static final Object REGISTRY_LOCK = new Object();
    private static StreamRuntime current;

    private final String streamSessionId;
    private final String executionProfileId;
    private final Transport transport;
    private final Platform platform;
    private final TransportStopCoordinator stopCoordinator;
    private final ReentrantReadWriteLock inputLock = new ReentrantReadWriteLock();
    private final List<TransportStopCoordinator.Completion> stopWaiters = new ArrayList<>();

    private State state = State.NEW;
    private ForegroundService foregroundService = ForegroundService.NONE;
    private ReleaseBarrier releaseBarrier = ReleaseBarrier.DONE;
    private boolean wifiLocksHeld;
    private boolean parked;
    private boolean stopRequested;
    private Presentation presentation;
    private long attachmentSerial;
    private long nextAttachmentSerial;
    private volatile long openInputSerial;
    // The presentation gave up its launch before the host granted it a control generation.
    private volatile boolean launchAbandoned;
    private volatile boolean launchKept;
    private final AtomicBoolean abandonedLaunchClosed = new AtomicBoolean();

    // Held input recorded by the gate; mutated only under the input lock.
    private final Map<Short, Short> heldControllers = new HashMap<>();
    private final Set<Short> heldKeys = new HashSet<>();
    private final Set<Byte> heldMouseButtons = new HashSet<>();
    private boolean touchActive;
    private boolean penActive;

    private StreamRuntime(String streamSessionId, String executionProfileId,
                          Transport transport, Platform platform) {
        this.streamSessionId = streamSessionId;
        this.executionProfileId = executionProfileId;
        this.transport = transport;
        this.platform = platform;
        this.stopCoordinator = new TransportStopCoordinator(this::stopNative,
                platform.stopExecutor());
    }

    /**
     * The native stop (on the stop executor, never the main thread) waits for every in-flight
     * native call of this generation and runs alone: the core holds one connection per process
     * and aborts on a call after its teardown. Callers never wait for it: they check the stop
     * flag first and only try the lock, so a slow LiStopConnection cannot stall the main thread.
     */
    private void stopNative() {
        inputLock.writeLock().lock();
        try {
            transport.stop();
        } finally {
            inputLock.writeLock().unlock();
        }
    }

    /** A native control call is legal only on this connected generation before its stop. */
    private synchronized boolean controlAllowed(Attachment attachment) {
        return isCurrent(attachment) && !stopRequested && state == State.CONNECTED;
    }

    /**
     * Registers a new transport generation. One native connection per process: refused while the
     * current runtime is live and no stop has been requested for it.
     */
    public static StreamRuntime create(String streamSessionId, String executionProfileId,
                                       Transport transport, Platform platform) {
        String session = streamSessionId == null ? "" : streamSessionId.trim();
        if (session.isEmpty() || transport == null || platform == null) {
            throw new IllegalArgumentException("Stream runtime identity is required");
        }
        synchronized (REGISTRY_LOCK) {
            if (current != null && current.isLiveAndUnstopped()) return null;
            StreamRuntime runtime = new StreamRuntime(
                    session, executionProfileId, transport, platform);
            runtime.stopCoordinator.whenStopped(runtime::onStopped);
            current = runtime;
            return runtime;
        }
    }

    /** The live runtime for this exact stream session, or null. */
    public static StreamRuntime forSession(String streamSessionId) {
        synchronized (REGISTRY_LOCK) {
            StreamRuntime runtime = current;
            return runtime != null && runtime.streamSessionId.equals(streamSessionId)
                    && runtime.isLiveAndUnstopped() ? runtime : null;
        }
    }

    /** Service acknowledgment; false tells a stale service to stop itself. */
    public static boolean serviceEstablished(String streamSessionId, long generation) {
        StreamRuntime runtime;
        synchronized (REGISTRY_LOCK) {
            runtime = current;
        }
        return runtime != null && runtime.acknowledgeService(streamSessionId, generation);
    }

    /** Service retention expiry or notification END for this exact generation. */
    public static void serviceEnded(String streamSessionId, long generation) {
        StreamRuntime runtime;
        synchronized (REGISTRY_LOCK) {
            runtime = current;
        }
        if (runtime == null || !runtime.streamSessionId.equals(streamSessionId)
                || runtime.generation() != generation) return;
        runtime.platform.sessionModel().post(new SessionEvent.Cleared(streamSessionId));
        runtime.platform.clearReconnectRecord(streamSessionId);
        runtime.stop(result -> {
            Presentation attached = runtime.attached();
            if (attached != null) attached.onRuntimeDisconnected();
        });
    }

    static void resetForTests() {
        synchronized (REGISTRY_LOCK) {
            current = null;
        }
    }

    public String streamSessionId() { return streamSessionId; }

    Platform platform() { return platform; }

    public long generation() { return stopCoordinator.generation(); }

    public synchronized boolean isConnecting() { return state == State.CONNECTING; }

    public synchronized boolean isConnected() { return state == State.CONNECTED; }

    public synchronized boolean isStopping() {
        return stopRequested || state == State.STOPPING || state == State.STOPPED;
    }

    private synchronized boolean isLiveAndUnstopped() {
        return !stopRequested && state != State.STOPPING && state != State.STOPPED;
    }

    public synchronized boolean hasPresentation() { return presentation != null; }

    public long controlGeneration() { return transport.controlGeneration(); }

    // ---- lifecycle --------------------------------------------------------------------------

    /** Acquires the locks and starts native only after the previous generation fully stopped. */
    public void start() {
        synchronized (this) {
            if (state != State.NEW || stopRequested) return;
            state = State.CONNECTING;
            wifiLocksHeld = true;
        }
        platform.acquireWifiLocks();
        stopCoordinator.start(platform.mainExecutor(), () -> transport.start(this));
    }

    /** Requests the one shared native stop; every caller observes the same fenced result. */
    public void stop(TransportStopCoordinator.Completion completion) {
        Presentation attached;
        synchronized (this) {
            if (state == State.STOPPED) {
                // Replay the actual terminal result; a failed native stop is never STOPPED.
                stopCoordinator.whenStopped(result -> TransportStopCoordinator.completeSafely(
                        completion, stopCoordinator.fence(result)));
                return;
            }
            if (completion != null) stopWaiters.add(completion);
            if (stopRequested) return;
            stopRequested = true;
            openInputSerial = 0L;
            attached = presentation;
        }
        platform.record("INFO", "runtime.stop_requested",
                "stream_session_id", streamSessionId, "generation", generation());
        stopCoordinator.stop(() -> {
            synchronized (StreamRuntime.this) {
                if (state != State.STOPPED) state = State.STOPPING;
            }
            if (attached != null) attached.onTransportStopping();
        }, result -> { });
    }

    private void onStopped(TransportStopCoordinator.Result result) {
        List<TransportStopCoordinator.Completion> waiting;
        boolean releaseLocks;
        boolean serviceRequested;
        synchronized (this) {
            state = State.STOPPED;
            stopRequested = true;
            if (releaseBarrier == ReleaseBarrier.PENDING) releaseBarrier = ReleaseBarrier.ABANDONED;
            openInputSerial = 0L;
            releaseLocks = wifiLocksHeld;
            wifiLocksHeld = false;
            serviceRequested = foregroundService == ForegroundService.REQUESTED
                    || foregroundService == ForegroundService.ESTABLISHED;
            if (foregroundService != ForegroundService.NONE) {
                foregroundService = ForegroundService.ENDED;
            }
            waiting = new ArrayList<>(stopWaiters);
            stopWaiters.clear();
        }
        if (releaseLocks) platform.releaseWifiLocks();
        if (attachedAtStop() == null) platform.runtimeEnded();
        if (serviceRequested) {
            platform.endService(streamSessionId, generation(), retainedByCoordinator());
        }
        synchronized (REGISTRY_LOCK) {
            if (current == this) current = null;
        }
        TransportStopCoordinator.Result fenced = stopCoordinator.fence(result);
        platform.record("INFO", "runtime.stopped", "stream_session_id", streamSessionId,
                "generation", generation(), "status", fenced.name());
        for (TransportStopCoordinator.Completion completion : waiting) {
            TransportStopCoordinator.completeSafely(completion, fenced);
        }
    }

    // ---- presentation attachment -------------------------------------------------------------

    /** Attaches a presentation; its input opens only after any previous release completed. */
    public Attachment attach(Presentation next) {
        if (next == null) throw new NullPointerException();
        long serial;
        synchronized (this) {
            if (stopRequested || state == State.STOPPED) return null;
            if (presentation != null) closeInputLocked();
            presentation = next;
            serial = ++nextAttachmentSerial;
            attachmentSerial = serial;
        }
        platform.record("INFO", "runtime.presentation_attached",
                "stream_session_id", streamSessionId, "generation", generation(),
                "attachment", serial);
        platform.inputExecutor().execute(() -> openInput(serial));
        return new Attachment(generation(), serial, new GatedInput(serial));
    }

    public synchronized boolean isCurrent(Attachment attachment) {
        return attachment != null && presentation != null
                && attachment.serial == attachmentSerial
                && attachment.transportGeneration == generation();
    }

    /** Non-blocking: closes the input gate and queues the held-input release. */
    public void detach(Attachment attachment) {
        synchronized (this) {
            if (!isCurrent(attachment)) return;
            presentation = null;
            attachmentSerial = 0L;
            closeInputLocked();
        }
        platform.record("INFO", "runtime.presentation_detached",
                "stream_session_id", streamSessionId, "generation", generation(),
                "attachment", attachment.serial);
    }

    private void closeInputLocked() {
        openInputSerial = 0L;
        if (releaseBarrier != ReleaseBarrier.ABANDONED) releaseBarrier = ReleaseBarrier.PENDING;
        platform.inputExecutor().execute(this::releaseHeldInput);
    }

    private void releaseHeldInput() {
        inputLock.writeLock().lock();
        try {
            synchronized (this) {
                if (releaseBarrier == ReleaseBarrier.ABANDONED || state == State.STOPPED) {
                    return;
                }
            }
            for (Map.Entry<Short, Short> held : heldControllers.entrySet()) {
                transport.sendControllerInput(held.getKey(), held.getValue(), 0,
                        (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0, (short) 0);
            }
            for (Short key : heldKeys) {
                transport.sendKeyboardInput(key, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
            }
            for (Byte button : heldMouseButtons) transport.sendMouseButtonUp(button);
            if (touchActive) {
                transport.sendTouchEvent(TOUCH_CANCEL_ALL, 0, 0f, 0f, 0f, 0f, 0f, ROT_UNKNOWN);
            }
            if (penActive) {
                transport.sendPenEvent(TOUCH_CANCEL_ALL, (byte) 0, (byte) 0, 0f, 0f, 0f, 0f, 0f,
                        ROT_UNKNOWN, TILT_UNKNOWN);
            }
            heldControllers.clear();
            heldKeys.clear();
            heldMouseButtons.clear();
            touchActive = false;
            penActive = false;
            synchronized (this) {
                if (releaseBarrier == ReleaseBarrier.PENDING) releaseBarrier = ReleaseBarrier.DONE;
            }
        } finally {
            inputLock.writeLock().unlock();
        }
    }

    private void openInput(long serial) {
        synchronized (this) {
            if (releaseBarrier != ReleaseBarrier.DONE || stopRequested
                    || state == State.STOPPED || attachmentSerial != serial) return;
            openInputSerial = serial;
        }
    }

    synchronized ReleaseBarrier releaseBarrier() { return releaseBarrier; }

    // ---- presentation-driven effects (identity fenced) ---------------------------------------

    /** A stopping transport never gets a decoder (and so never a frame) again. */
    public boolean attachDecoder(Attachment attachment, VideoDecoderRenderer decoder) {
        synchronized (this) {
            if (!isCurrent(attachment) || stopRequested) return false;
        }
        return transport.attachDecoder(decoder);
    }

    /** Muting always applies; a stopping transport is never unmuted. */
    public void setAudioVolume(Attachment attachment, float volume) {
        synchronized (this) {
            if (!isCurrent(attachment) || (volume > 0f && stopRequested)) return;
        }
        transport.setAudioVolume(volume);
    }

    public void requestIdrFrame(Attachment attachment) {
        if (!controlAllowed(attachment) || !inputLock.readLock().tryLock()) return;
        try {
            if (controlAllowed(attachment)) transport.requestIdrFrame();
        } finally {
            inputLock.readLock().unlock();
        }
    }

    /** False, without waiting, while the native stop runs or the generation is not live. */
    public boolean reauthorizeControlGeneration(Attachment attachment, long expected,
                                                long generation) {
        if (!controlAllowed(attachment) || !inputLock.readLock().tryLock()) return false;
        try {
            return controlAllowed(attachment)
                    && transport.reauthorizeControlGeneration(expected, generation);
        } finally {
            inputLock.readLock().unlock();
        }
    }

    /** Parks the transport for background retention; false means it was not parked. */
    public boolean park() {
        return parkTransport() == SessionModel.ParkOutcome.PARKED;
    }

    /**
     * The one park decision: a presentation that is showing the stream refuses it (the transport
     * stays live and unchanged); a transport that cannot be protected is stopped instead.
     */
    private SessionModel.ParkOutcome parkTransport() {
        Presentation shown = attached();
        if (shown != null && !shown.canPark()) {
            platform.record("INFO", "runtime.park_refused", "stream_session_id",
                    streamSessionId, "generation", generation(), "reason", "presented");
            return SessionModel.ParkOutcome.REFUSED;
        }
        Presentation attached;
        boolean protectedPark;
        int minutes = platform.retentionMinutes();
        synchronized (this) {
            if (state != State.CONNECTED || stopRequested) return SessionModel.ParkOutcome.STOPPED;
            if (parked) return SessionModel.ParkOutcome.PARKED;
            protectedPark = foregroundService == ForegroundService.ESTABLISHED && minutes != 0;
            attached = presentation;
            if (protectedPark) parked = true;
        }
        if (!protectedPark) {
            stopUnprotected(minutes == 0 ? "retention_disabled" : "park_without_foreground_service");
            return SessionModel.ParkOutcome.STOPPED;
        }
        transport.detachDecoder();
        transport.setAudioVolume(0f);
        platform.parkService(streamSessionId, generation(), minutes);
        platform.record("INFO", "runtime.parked", "stream_session_id", streamSessionId,
                "generation", generation());
        if (attached != null) attached.onTransportParked();
        return SessionModel.ParkOutcome.PARKED;
    }

    /** Presentation restored video: retention timer cancelled, service stays foreground. */
    public void resumed(Attachment attachment) {
        synchronized (this) {
            if (!isCurrent(attachment)) return;
            parked = false;
        }
        platform.resumeService(streamSessionId, generation());
    }

    private void stopUnprotected(String reason) {
        platform.record("WARN", "runtime.unprotected_stop", "stream_session_id",
                streamSessionId, "generation", generation(), "reason", reason);
        platform.saveReconnectRecord(streamSessionId);
        markReconnectRequired();
        stop(result -> {
            // A still-attached presentation must not keep showing a stopped transport.
            Presentation attached = attached();
            if (attached != null) attached.onRuntimeDisconnected();
        });
    }

    /** One owner-scoped report; the model decides from its own state what the loss means. */
    private void markReconnectRequired() {
        platform.sessionModel().post(new SessionEvent.TransportLost(this, streamSessionId));
    }

    /**
     * Detach on presentation loss. A retained, protected transport survives; anything else is
     * stopped honestly before the owner is dropped.
     */
    public void presentationLost(Attachment attachment, boolean finishing) {
        if (!isCurrent(attachment)) return;
        detach(attachment);
        if (!finishing && canSurviveWithoutPresentation()) {
            synchronized (this) {
                if (state == State.CONNECTED) {
                    transport.detachDecoder();
                    transport.setAudioVolume(0f);
                }
            }
            platform.record("INFO", "runtime.survived_presentation_loss",
                    "stream_session_id", streamSessionId, "generation", generation());
            return;
        }
        // An abandoned launch is settled; a model that has not applied its cancel yet must not
        // turn it back into a session to reconnect.
        if (!launchAbandoned && retainedByCoordinator()) {
            stopUnprotected("presentation_lost");
        } else {
            stop(result -> { });
        }
    }

    /**
     * The presentation cancelled its own unstarted preparing attempt and is going away: nothing
     * is retained, so the transport stops without saving reconnect material.
     */
    public void abandonPreparing(Attachment attachment) {
        if (!isCurrent(attachment)) return;
        detach(attachment);
        platform.record("INFO", "runtime.preparing_abandoned",
                "stream_session_id", streamSessionId, "generation", generation());
        stop(result -> { });
    }

    /**
     * The presentation's surface is gone: stop decoding into it and mute now, before any
     * asynchronous park decision. Changes no session state; park tolerates the detached decoder.
     * Returns whether the decoder was detached: the presentation must then reattach one.
     */
    public boolean surfaceLost(Attachment attachment) {
        synchronized (this) {
            if (!isCurrent(attachment) || state != State.CONNECTED) return false;
        }
        transport.detachDecoder();
        transport.setAudioVolume(0f);
        return true;
    }

    private boolean retainedByCoordinator() {
        SessionModel.Snapshot retained = platform.sessionModel().snapshot();
        return retained.streamSessionId.equals(streamSessionId)
                && retained.state != SessionModel.State.NONE
                && retained.state != SessionModel.State.TERMINATING;
    }

    boolean canSurviveWithoutPresentation() {
        SessionModel model = platform.sessionModel();
        SessionModel.Snapshot retained = model.snapshot();
        boolean retainedState = retained.streamSessionId.equals(streamSessionId)
                && (retained.state == SessionModel.State.HOME_LIVE
                || retained.state == SessionModel.State.PARKED_LIVE
                || (retained.state == SessionModel.State.PREPARING
                && model.isPreparingHomeFrameAccepted(
                retained.streamSessionId, retained.hostId, retained.appId,
                retained.playniteGameId, retained.transitionId, retained.attempt)));
        synchronized (this) {
            return retainedState && !stopRequested
                    && (state == State.CONNECTING || state == State.CONNECTED)
                    && (foregroundService == ForegroundService.ESTABLISHED
                    || platform.appForeground());
        }
    }

    // ---- foreground service -------------------------------------------------------------------

    private void requestForegroundService() {
        synchronized (this) {
            if (foregroundService != ForegroundService.NONE || stopRequested) return;
            if (!platform.appForeground()) {
                platform.record("WARN", "runtime.fgs_not_requested",
                        "stream_session_id", streamSessionId, "reason", "background");
                return;
            }
            foregroundService = ForegroundService.REQUESTED;
        }
        boolean accepted = platform.requestForegroundService(streamSessionId, generation());
        if (!accepted) {
            synchronized (this) {
                if (foregroundService == ForegroundService.REQUESTED) {
                    foregroundService = ForegroundService.NONE;
                }
            }
            platform.record("WARN", "runtime.fgs_refused", "stream_session_id", streamSessionId);
        }
    }

    private synchronized boolean acknowledgeService(String session, long serviceGeneration) {
        if (!streamSessionId.equals(session) || generation() != serviceGeneration
                || stopRequested || state == State.STOPPED
                || foregroundService != ForegroundService.REQUESTED) return false;
        foregroundService = ForegroundService.ESTABLISHED;
        return true;
    }

    synchronized ForegroundService foregroundService() { return foregroundService; }

    // ---- NvConnectionListener (the one MoonBridge listener) -----------------------------------

    private synchronized Presentation attached() { return presentation; }

    private synchronized Presentation attachedAtStop() { return presentation; }

    @Override public void stageStarting(String stage) {
        Presentation p = attached();
        if (p != null) p.stageStarting(stage);
    }

    @Override public void stageComplete(String stage) {
        long controlGeneration = transport.controlGeneration();
        if (controlGeneration > 0L && !isStopping()) {
            platform.sessionModel().post(new SessionEvent.ControlGenerationBound(
                    this, streamSessionId, controlGeneration));
            platform.controlGenerationBound(streamSessionId, controlGeneration);
        } else if (controlGeneration > 0L && launchKept) {
            // Granted after the stop: the kept session's reconnect record needs it.
            platform.controlGenerationBound(streamSessionId, controlGeneration);
        } else if (controlGeneration > 0L && (launchAbandoned || !retainedByCoordinator())) {
            // Deliberate: stops outside Cancel (stage error, STOP_TRANSPORT) declare nothing.
            closeAbandonedLaunch(controlGeneration);
        }
        Presentation p = attached();
        if (p != null) p.stageComplete(stage);
    }

    @Override public void startLeftOnHost(long controlGeneration) {
        closeAbandonedLaunch(controlGeneration);
    }

    /**
     * The presentation cancelled its launch, after stopping this runtime, and left the host
     * stream that launch opened to this runtime: the stream revealed nothing, whether or not the
     * host had granted it. A grant that already arrived (even through the live path, before the
     * stop) closes now; a permit whose start was not sent yet is handed back to the host; a
     * start already sent closes in {@link #stageComplete}, whatever the session model has
     * processed of the cancel by then.
     */
    public void abandonLaunch() {
        if (!isStopping()) return;
        launchAbandoned = true;
        long controlGeneration = transport.controlGeneration();
        if (controlGeneration > 0L) closeAbandonedLaunch(controlGeneration);
        else transport.abandonStart();
    }

    /**
     * The presentation cancelled a reconnect to a running game, or a used neutral stream
     * settling to the dashboard, and is stopping this runtime: the host stream stays to be
     * resumed, so a grant still in flight is recorded, never closed.
     */
    public void keepLaunch() {
        launchKept = true;
    }

    /**
     * Cancelled while the launch was in flight: the host stream it opened (and its virtual
     * display) has no other owner, and Sunshine only pauses it. Closed once.
     */
    private void closeAbandonedLaunch(long controlGeneration) {
        if (!abandonedLaunchClosed.compareAndSet(false, true)) return;
        platform.record("WARN", "runtime.abandoned_launch_close",
                "stream_session_id", streamSessionId, "control_generation", controlGeneration);
        platform.closeAbandonedLaunch(streamSessionId, executionProfileId, controlGeneration);
    }

    @Override public void stageFailed(String stage, int portFlags, int errorCode) {
        Presentation p = attached();
        if (p != null) {
            p.stageFailed(stage, portFlags, errorCode);
            return;
        }
        lostWithoutPresentation("stage_failed");
    }

    @Override public void controlRejected(String stage, String reason,
                                          boolean nothingStarted) {
        Presentation p = attached();
        if (p != null) {
            p.controlRejected(stage, reason, nothingStarted);
            return;
        }
        lostWithoutPresentation("stage_failed");
    }

    @Override public void connectionStarted() {
        synchronized (this) {
            if (state == State.CONNECTING && !stopRequested) state = State.CONNECTED;
        }
        requestForegroundService();
        Presentation p = attached();
        if (p != null) p.connectionStarted();
    }

    @Override public void connectionTerminated(int errorCode) {
        platform.record("WARN", "runtime.connection_terminated",
                "stream_session_id", streamSessionId, "generation", generation(),
                "reason", Integer.toString(errorCode));
        Presentation p = attached();
        if (p != null) {
            p.connectionTerminated(errorCode);
            return;
        }
        lostWithoutPresentation("connection_terminated");
    }

    private void lostWithoutPresentation(String reason) {
        if (isStopping()) return;
        stopUnprotected(reason);
    }

    @Override public void connectionStatusUpdate(int connectionStatus) {
        Presentation p = attached();
        if (p != null) p.connectionStatusUpdate(connectionStatus);
    }

    @Override public void displayMessage(String message) {
        Presentation p = attached();
        if (p != null) p.displayMessage(message);
    }

    @Override public void displayTransientMessage(String message) {
        Presentation p = attached();
        if (p != null) p.displayTransientMessage(message);
    }

    @Override public void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        Presentation p = attached();
        if (p != null) p.rumble(controllerNumber, lowFreqMotor, highFreqMotor);
    }

    @Override public void rumbleTriggers(short controllerNumber, short leftTrigger,
                                         short rightTrigger) {
        Presentation p = attached();
        if (p != null) p.rumbleTriggers(controllerNumber, leftTrigger, rightTrigger);
    }

    @Override public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        transport.setHdrMode(enabled, hdrMetadata);
        Presentation p = attached();
        if (p != null) p.setHdrMode(enabled, hdrMetadata);
    }

    @Override public void setMotionEventState(short controllerNumber, byte motionType,
                                              short reportRateHz) {
        Presentation p = attached();
        if (p != null) p.setMotionEventState(controllerNumber, motionType, reportRateHz);
    }

    @Override public void setControllerLED(short controllerNumber, byte r, byte g, byte b) {
        Presentation p = attached();
        if (p != null) p.setControllerLED(controllerNumber, r, g, b);
    }

    // ---- SessionModel.Controller ----------------------------------------------------------------

    @Override public boolean isRetainedTransportLive() {
        synchronized (this) {
            return state == State.CONNECTED && !stopRequested;
        }
    }

    @Override public String getExecutionProfileId() { return executionProfileId; }

    @Override public boolean hasAttachedPresentation() { return hasPresentation(); }

    @Override public SessionModel.ParkOutcome parkRetainedTransport() { return parkTransport(); }

    @Override public boolean parkPreparingTransport(
            SessionModel.Snapshot preparing) {
        Presentation p = attached();
        if (p != null) return p.parkPreparingTransport(preparing);
        if (!canSurviveWithoutPresentation()) stopUnprotected("preparing_park_headless");
        return true;
    }

    @Override public boolean cancelPreparingTransport(
            SessionModel.Snapshot preparing) {
        Presentation p = attached();
        if (p != null) return p.cancelPreparingTransport(preparing);
        stopUnprotected("preparing_cancelled_headless");
        return true;
    }

    @Override public boolean preparingHomeFrameSubmitted(
            SessionModel.Snapshot preparing) {
        Presentation p = attached();
        return p != null && p.preparingHomeFrameSubmitted(preparing);
    }

    @Override public void disconnectRetainedStream(
            SessionModel.TerminationCallback completion) {
        stop(result -> {
            boolean stopped = result == TransportStopCoordinator.Result.STOPPED;
            if (stopped) {
                platform.clearReconnectRecord(streamSessionId);
                Presentation p = attached();
                if (p != null) p.onRuntimeDisconnected();
            } else if (completion != null) {
                completion.transportUnsafe();
            }
            if (completion != null) completion.complete(stopped);
        });
    }

    @Override public void terminateRetainedSession(
            String gameId, SessionModel.TerminationCallback completion) {
        Presentation p = attached();
        if (p != null) {
            p.terminateRetainedSession(gameId, completion);
            return;
        }
        // Mandatory privacy effects before any asynchronous local or host stop.
        synchronized (this) {
            openInputSerial = 0L;
        }
        transport.setAudioVolume(0f);
        platform.clearReconnectRecord(streamSessionId);
        platform.terminateHost(this, platform.sessionModel().snapshot(), gameId, completion);
    }

    @Override public void switchGame(SessionModel.SwitchRequest request,
                                     SessionModel.SwitchCallback completion) {
        Presentation p = attached();
        if (p != null) {
            p.switchGame(request, completion);
            return;
        }
        completion.complete(SessionModel.SwitchOutcome.FAILED,
                "presentation_detached");
    }

    // ---- input gate ---------------------------------------------------------------------------

    private static final byte TOUCH_CANCEL_ALL = 0x07;
    private static final short ROT_UNKNOWN = (short) 0xFFFF;
    private static final byte TILT_UNKNOWN = (byte) 0xFF;
    private static final int LI_ERR_UNSUPPORTED = -5501;

    /**
     * Input runs on the main thread and never waits: while the native stop or the held-input
     * release holds the write lock, the event is dropped (the gate is closing anyway).
     */
    private boolean enter(long serial) {
        if (openInputSerial != serial || serial == 0L) return false;
        if (!inputLock.readLock().tryLock()) return false;
        if (openInputSerial == serial) return true;
        inputLock.readLock().unlock();
        return false;
    }

    private void exit() {
        inputLock.readLock().unlock();
    }

    /** Presentation input bound to one attachment; rejected once the gate closes. */
    private final class GatedInput implements StreamInput {
        private final long serial;

        GatedInput(long serial) {
            this.serial = serial;
        }

        @Override public void sendMouseMove(short deltaX, short deltaY) {
            if (!enter(serial)) return;
            try { transport.sendMouseMove(deltaX, deltaY); } finally { exit(); }
        }

        @Override public void sendMousePosition(short x, short y, short referenceWidth,
                                                short referenceHeight) {
            if (!enter(serial)) return;
            try { transport.sendMousePosition(x, y, referenceWidth, referenceHeight); }
            finally { exit(); }
        }

        @Override public void sendMouseMoveAsMousePosition(short deltaX, short deltaY,
                                                           short referenceWidth,
                                                           short referenceHeight) {
            if (!enter(serial)) return;
            try {
                transport.sendMouseMoveAsMousePosition(deltaX, deltaY, referenceWidth,
                        referenceHeight);
            } finally { exit(); }
        }

        @Override public void sendMouseButtonDown(byte mouseButton) {
            if (!enter(serial)) return;
            try {
                transport.sendMouseButtonDown(mouseButton);
                synchronized (heldMouseButtons) { heldMouseButtons.add(mouseButton); }
            } finally { exit(); }
        }

        @Override public void sendMouseButtonUp(byte mouseButton) {
            if (!enter(serial)) return;
            try {
                transport.sendMouseButtonUp(mouseButton);
                synchronized (heldMouseButtons) { heldMouseButtons.remove(mouseButton); }
            } finally { exit(); }
        }

        @Override public void sendControllerInput(short controllerNumber, short activeGamepadMask,
                                                  int buttonFlags, byte leftTrigger,
                                                  byte rightTrigger, short leftStickX,
                                                  short leftStickY, short rightStickX,
                                                  short rightStickY) {
            if (!enter(serial)) return;
            try {
                transport.sendControllerInput(controllerNumber, activeGamepadMask, buttonFlags,
                        leftTrigger, rightTrigger, leftStickX, leftStickY, rightStickX,
                        rightStickY);
                boolean neutral = buttonFlags == 0 && leftTrigger == 0 && rightTrigger == 0
                        && leftStickX == 0 && leftStickY == 0
                        && rightStickX == 0 && rightStickY == 0;
                synchronized (heldControllers) {
                    if (neutral) heldControllers.remove(controllerNumber);
                    else heldControllers.put(controllerNumber, activeGamepadMask);
                }
            } finally { exit(); }
        }

        @Override public void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier,
                                                byte flags) {
            if (!enter(serial)) return;
            try {
                transport.sendKeyboardInput(keyMap, keyDirection, modifier, flags);
                synchronized (heldKeys) {
                    if (keyDirection == KeyboardPacket.KEY_DOWN) heldKeys.add(keyMap);
                    else if (keyDirection == KeyboardPacket.KEY_UP) heldKeys.remove(keyMap);
                }
            } finally { exit(); }
        }

        @Override public void sendMouseScroll(byte scrollClicks) {
            if (!enter(serial)) return;
            try { transport.sendMouseScroll(scrollClicks); } finally { exit(); }
        }

        @Override public void sendMouseHScroll(byte scrollClicks) {
            if (!enter(serial)) return;
            try { transport.sendMouseHScroll(scrollClicks); } finally { exit(); }
        }

        @Override public void sendMouseHighResScroll(short scrollAmount) {
            if (!enter(serial)) return;
            try { transport.sendMouseHighResScroll(scrollAmount); } finally { exit(); }
        }

        @Override public void sendMouseHighResHScroll(short scrollAmount) {
            if (!enter(serial)) return;
            try { transport.sendMouseHighResHScroll(scrollAmount); } finally { exit(); }
        }

        @Override public int sendTouchEvent(byte eventType, int pointerId, float x, float y,
                                            float pressureOrDistance, float contactAreaMajor,
                                            float contactAreaMinor, short rotation) {
            if (!enter(serial)) return LI_ERR_UNSUPPORTED;
            try {
                int result = transport.sendTouchEvent(eventType, pointerId, x, y,
                        pressureOrDistance, contactAreaMajor, contactAreaMinor, rotation);
                synchronized (heldControllers) { touchActive = eventType != TOUCH_CANCEL_ALL; }
                return result;
            } finally { exit(); }
        }

        @Override public int sendPenEvent(byte eventType, byte toolType, byte penButtons, float x,
                                          float y, float pressureOrDistance,
                                          float contactAreaMajor, float contactAreaMinor,
                                          short rotation, byte tilt) {
            if (!enter(serial)) return LI_ERR_UNSUPPORTED;
            try {
                int result = transport.sendPenEvent(eventType, toolType, penButtons, x, y,
                        pressureOrDistance, contactAreaMajor, contactAreaMinor, rotation, tilt);
                synchronized (heldControllers) { penActive = eventType != TOUCH_CANCEL_ALL; }
                return result;
            } finally { exit(); }
        }

        @Override public int sendControllerArrivalEvent(byte controllerNumber,
                                                        short activeGamepadMask, byte type,
                                                        int supportedButtonFlags,
                                                        short capabilities) {
            if (!enter(serial)) return LI_ERR_UNSUPPORTED;
            try {
                return transport.sendControllerArrivalEvent(controllerNumber, activeGamepadMask,
                        type, supportedButtonFlags, capabilities);
            } finally { exit(); }
        }

        @Override public int sendControllerTouchEvent(byte controllerNumber, byte eventType,
                                                      int pointerId, float x, float y,
                                                      float pressure) {
            if (!enter(serial)) return LI_ERR_UNSUPPORTED;
            try {
                return transport.sendControllerTouchEvent(controllerNumber, eventType, pointerId,
                        x, y, pressure);
            } finally { exit(); }
        }

        @Override public int sendControllerMotionEvent(byte controllerNumber, byte motionType,
                                                       float x, float y, float z) {
            if (!enter(serial)) return LI_ERR_UNSUPPORTED;
            try {
                return transport.sendControllerMotionEvent(controllerNumber, motionType, x, y, z);
            } finally { exit(); }
        }

        @Override public void sendControllerBatteryEvent(byte controllerNumber, byte batteryState,
                                                         byte batteryPercentage) {
            if (!enter(serial)) return;
            try {
                transport.sendControllerBatteryEvent(controllerNumber, batteryState,
                        batteryPercentage);
            } finally { exit(); }
        }

        @Override public void sendUtf8Text(String text) {
            if (!enter(serial)) return;
            try { transport.sendUtf8Text(text); } finally { exit(); }
        }
    }
}
