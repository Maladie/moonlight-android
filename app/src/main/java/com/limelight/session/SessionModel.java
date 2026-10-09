package com.limelight.session;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.limelight.console.LocalHostRecords;
import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.gateway.GatewayConnection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * The process-scoped owner of session state and the persistent session records. Every state
 * change runs as a {@link SessionEvent} on one model thread; readers use the last published
 * immutable {@link Snapshot}. Effects on the transport owner run on the main executor after the
 * transition committed, and their results return as events fenced by an operation id.
 */
public final class SessionModel {
    public enum State {
        NONE,
        PREPARING,
        HOME_LIVE,
        PARKED_LIVE,
        RECONNECT_REQUIRED,
        TERMINATING
    }

    /** The transport owner. Its effect methods run on the main executor, never on the model. */
    public interface Controller {
        /** Thread-safe probe; may be read from the model thread and from readers. */
        boolean isRetainedTransportLive();
        default String getExecutionProfileId() { return null; }
        /** Thread-safe probe: false while the transport runs without a presentation. */
        default boolean hasAttachedPresentation() { return true; }
        ParkOutcome parkRetainedTransport();
        default boolean parkPreparingTransport(Snapshot preparing) { return false; }
        default boolean cancelPreparingTransport(Snapshot preparing) { return false; }
        default boolean preparingHomeFrameSubmitted(Snapshot preparing) { return false; }
        void disconnectRetainedStream(TerminationCallback completion);
        /** {@code gameId}: the game the requester named to stop; empty for the session's own. */
        void terminateRetainedSession(String gameId, TerminationCallback completion);
        void switchGame(SwitchRequest request, SwitchCallback completion);
    }

    /** Receives every published snapshot on the main thread, never an older one after a newer. */
    public interface Listener {
        void onSnapshot(Snapshot snapshot);
    }

    /** An asynchronous event result, delivered on the main executor unless awaited. */
    public interface Reply<R> {
        void accept(R result);
    }

    public interface TerminationCallback {
        void complete(boolean success);
        /** The stream closed, but a game this close could not verify may still run on the host. */
        void gameLeftOnHost();
        default void transportUnsafe() { }
    }

    public interface SwitchCallback {
        void complete(SwitchOutcome outcome, String error);
    }

    public enum SwitchOutcome { REUSED, CANCELLED, FAILED }
    /** PARKED; REFUSED while a presentation shows the stream (state kept); STOPPED otherwise. */
    public enum ParkOutcome { PARKED, REFUSED, STOPPED }
    public enum SwitchResult { STARTED, NOT_ELIGIBLE }
    public enum TerminationResult { STARTED, IN_PROGRESS, NO_CONTROLLER }

    public static final class SwitchRequest {
        public final String streamSessionId;
        public final String hostId;
        public final String profileId;
        public final String executionProfileId;
        public final String targetProfileId;
        public final boolean targetIsChild;
        public final int appId;
        public final String oldGameId;
        public final String newGameId;
        public final String newGameName;
        public final String streamTargetName;
        public final String artworkPath;
        public final String transitionId;
        public final long attempt;
        public final boolean preparing;
        public final long orchestrationId;
        public final BooleanSupplier cancelled;

        public SwitchRequest(String streamSessionId, String hostId, String profileId,
                             String executionProfileId, String targetProfileId,
                             boolean targetIsChild,
                             int appId, String oldGameId, String newGameId, String newGameName,
                             String streamTargetName, String artworkPath, String transitionId,
                             long attempt, boolean preparing, long orchestrationId,
                             BooleanSupplier cancelled) {
            this.streamSessionId = normalize(streamSessionId);
            this.hostId = normalize(hostId);
            this.profileId = GatewayConnection.normalizeProfileId(profileId);
            this.executionProfileId = GatewayConnection.normalizeProfileId(executionProfileId);
            this.targetProfileId = GatewayConnection.normalizeProfileId(targetProfileId);
            this.targetIsChild = targetIsChild;
            this.appId = appId;
            this.oldGameId = normalize(oldGameId);
            this.newGameId = normalize(newGameId);
            this.newGameName = normalize(newGameName);
            this.streamTargetName = normalize(streamTargetName);
            this.artworkPath = normalize(artworkPath);
            this.transitionId = normalize(transitionId);
            this.attempt = attempt;
            this.preparing = preparing;
            this.orchestrationId = orchestrationId;
            this.cancelled = cancelled == null ? () -> false : cancelled;
        }
    }

    /** Proof of one direct termination operation; stale tokens are rejected by id. */
    public static final class DirectTermination {
        final long operationId;

        DirectTermination(long operationId) {
            this.operationId = operationId;
        }
    }

    public static final class Snapshot {
        public final State state;
        public final String streamSessionId;
        public final String hostId;
        public final String profileId;
        public final String executionProfileId;
        public final int appId;
        public final String playniteGameId;
        public final String transitionId;
        public final long attempt;
        public final long controlGeneration;
        public final long transportGeneration;
        /** Advances each time the session enters or leaves TERMINATING, seen or not. */
        public final long terminationEpoch;
        /**
         * Advances each time a presentation shows the stream again. A park request carries the
         * epoch its presenter saw; a newer one means another presenter took over.
         */
        public final long presentationEpoch;
        /** Persistent records, mirrored from the stores the model alone writes. */
        public final Records records;
        // Model-internal bindings: visible to the model's readers, not to listeners.
        final Controller controller;
        final Controller switchOwner;
        final String switchSessionId;
        final String switchTransitionId;
        final long switchAttempt;
        final String acceptedHomeTransitionId;
        final long acceptedHomeAttempt;
        final long sequence;

        Snapshot(SessionModel m, Records records, long sequence) {
            this.state = m.state;
            this.streamSessionId = m.streamSessionId;
            this.hostId = m.hostId;
            this.profileId = m.profileId;
            this.executionProfileId = m.executionProfileId;
            this.appId = m.appId;
            this.playniteGameId = m.playniteGameId;
            this.transitionId = m.transitionId;
            this.attempt = m.attempt;
            this.controlGeneration = m.controlGeneration;
            this.transportGeneration = m.transportGeneration;
            this.terminationEpoch = m.terminationEpoch;
            this.presentationEpoch = m.presentationEpoch;
            this.records = records;
            this.controller = m.controller;
            this.switchOwner = m.switchOwner;
            this.switchSessionId = m.switchSessionId;
            this.switchTransitionId = m.switchTransitionId;
            this.switchAttempt = m.switchAttempt;
            this.acceptedHomeTransitionId = m.acceptedHomeTransitionId;
            this.acceptedHomeAttempt = m.acceptedHomeAttempt;
            this.sequence = sequence;
        }

        boolean sameAs(Snapshot other) {
            return other != null && state == other.state
                    && streamSessionId.equals(other.streamSessionId)
                    && hostId.equals(other.hostId) && profileId.equals(other.profileId)
                    && executionProfileId.equals(other.executionProfileId)
                    && appId == other.appId && playniteGameId.equals(other.playniteGameId)
                    && transitionId.equals(other.transitionId) && attempt == other.attempt
                    && controlGeneration == other.controlGeneration
                    && transportGeneration == other.transportGeneration
                    && terminationEpoch == other.terminationEpoch
                    && records == other.records;
        }
    }

    /** Immutable mirror of the three persistent stores at one model transition. */
    public static final class Records {
        final Map<String, ?> resume;
        final Map<String, ?> suspended;
        final Map<String, ?> sleep;
        /** The reconnect record; resume material, never proof of a live connection. */
        public final SessionResumeManager.PendingSession pending;

        Records(Map<String, ?> resume, Map<String, ?> suspended, Map<String, ?> sleep) {
            this.resume = Collections.unmodifiableMap(new HashMap<>(resume));
            this.suspended = Collections.unmodifiableMap(new HashMap<>(suspended));
            this.sleep = Collections.unmodifiableMap(new HashMap<>(sleep));
            this.pending = SessionResumeManager.read(this.resume);
        }

        public SuspendedSessionStore.Session suspendedForExecution(String hostId,
                                                                   String executionProfileId) {
            return SuspendedSessionStore.loadForExecutionProfile(suspended, hostId,
                    executionProfileId);
        }

        public boolean recentlyEndedForExecution(String hostId, String executionProfileId) {
            return SuspendedSessionStore.recentlyEnded(suspended, hostId, executionProfileId);
        }

        public HostSleepStateStore.State hostSleep(String hostId) {
            return HostSleepStateStore.load(sleep, hostId);
        }

        public String hostSelectionRequest() {
            return SuspendedSessionStore.hostSelectionRequest(suspended);
        }
    }

    /** Persistent session state, durable across process death. */
    static final class Stores {
        final SharedPreferences resume;
        final SharedPreferences suspended;
        final SharedPreferences sleep;

        Stores(SharedPreferences resume, SharedPreferences suspended, SharedPreferences sleep) {
            this.resume = resume;
            this.suspended = suspended;
            this.sleep = sleep;
        }

        Records read() {
            return new Records(resume.getAll(), suspended.getAll(), sleep.getAll());
        }
    }

    private static final class TerminationOperation {
        final long id;
        final String streamSessionId;
        final boolean direct;
        final State previousState;
        final Controller previousOwner;
        boolean transportUnsafe;
        final List<TerminationCallback> completions = new ArrayList<>();

        TerminationOperation(long id, String streamSessionId, boolean direct,
                             State previousState, Controller previousOwner,
                             TerminationCallback completion) {
            this.id = id;
            this.streamSessionId = streamSessionId;
            this.direct = direct;
            this.previousState = previousState;
            this.previousOwner = previousOwner;
            if (completion != null) completions.add(completion);
        }
    }

    // ---- model-thread-confined state ----------------------------------------------------------
    State state = State.NONE;
    long terminationEpoch;
    long presentationEpoch;
    Controller controller;
    String streamSessionId = "";
    String hostId = "";
    String profileId = GatewayConnection.DEFAULT_PROFILE_ID;
    String executionProfileId = GatewayConnection.DEFAULT_PROFILE_ID;
    int appId;
    String playniteGameId = "";
    String transitionId = "";
    long attempt;
    long controlGeneration;
    long transportGeneration;
    private long nextTransportGeneration;
    private long nextOperationId;
    private long homeFrameClaim;
    String acceptedHomeTransitionId = "";
    long acceptedHomeAttempt;
    Controller switchOwner;
    String switchSessionId = "";
    String switchTransitionId = "";
    long switchAttempt;
    private long switchOperation;
    private long parkOperation;
    private long reauthorizationOperation;
    private TerminationOperation terminationOperation;
    private Records records;
    private boolean recordsChanged;

    private final Executor modelThread;
    private final Executor mainThread;
    private final BooleanSupplier onMainThread;
    private final Stores stores;
    private final List<Runnable> afterCommit = new ArrayList<>();
    private volatile Thread runningThread;
    /** The thread the model runs on; await from it would never return. */
    private volatile Thread modelThreadOwner;
    private volatile Snapshot published;
    private Snapshot delivered;
    private long sequence;
    // Main-thread-confined subscriber registry: listener -> sequence last given.
    private final Map<Listener, Long> listeners = new LinkedHashMap<>();
    private final AtomicBoolean deliveryPosted = new AtomicBoolean();

    /**
     * @param modelThread single-threaded executor; every transition runs on it
     * @param mainThread executor for replies, completions, controller effects and delivery
     * @param onMainThread true while the caller runs on the main thread (await guard)
     */
    public SessionModel(Executor modelThread, Executor mainThread, BooleanSupplier onMainThread,
                        SharedPreferences resume, SharedPreferences suspended,
                        SharedPreferences sleep) {
        this(modelThread, mainThread, onMainThread, new Stores(resume, suspended, sleep));
    }

    private SessionModel(Executor modelThread, Executor mainThread, BooleanSupplier onMainThread,
                         Stores stores) {
        this.modelThread = modelThread;
        this.mainThread = mainThread;
        this.onMainThread = onMainThread;
        this.stores = stores;
        // Process start: restore persistent records; the transport is NONE and the host game
        // unknown until the first observation. A reconnect record is not a live connection.
        this.records = stores.read();
        this.published = new Snapshot(this, records, 0L);
        this.delivered = published;
    }

    /** Production model: one dedicated model thread; replies and effects on the main looper. */
    public static SessionModel create(Context application) {
        HandlerThread thread = new HandlerThread("MoonWaker-SessionModel");
        thread.start();
        Handler model = new Handler(thread.getLooper());
        Handler main = new Handler(Looper.getMainLooper());
        return new SessionModel(model::post, main::post,
                () -> Looper.getMainLooper().isCurrentThread(),
                new Stores(SessionResumeManager.prefs(application),
                        SuspendedSessionStore.prefs(application),
                        HostSleepStateStore.prefs(application)));
    }

    /** The model of this process (owned by the Application). */
    public static SessionModel of(Context context) {
        return ((Owner) context.getApplicationContext()).sessionModel();
    }

    /** Implemented by the Application that owns the process-scoped model. */
    public interface Owner {
        SessionModel sessionModel();
    }

    // ---- dispatch -----------------------------------------------------------------------------

    /** Posts an event; its result, if any, is ignored. */
    public void post(SessionEvent<?> event) {
        modelThread.execute(() -> run(event, null, null));
    }

    /** Posts an event; {@code reply} receives its result on the main thread. */
    public <R> void dispatch(SessionEvent<R> event, Reply<R> reply) {
        modelThread.execute(() -> run(event, reply, mainThread));
    }

    /**
     * Posts an event and waits for its result. Worker threads only: the main thread never waits
     * for the model, and the model never waits for anything.
     */
    public <R> R await(SessionEvent<R> event) {
        if (onMainThread.getAsBoolean() || Thread.currentThread() == modelThreadOwner) {
            throw new IllegalStateException("SessionModel.await on the main or model thread");
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<R> result = new AtomicReference<>();
        modelThread.execute(() -> run(event, value -> {
            result.set(value);
            done.countDown();
        }, Runnable::run));
        try {
            done.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the session model");
        }
        return result.get();
    }

    /**
     * Applies one event. Replies and effects issued by the transition run only after the new
     * snapshot is published, so a continuation never observes the state before its own event.
     * A reply answered later (after an effect) is delivered directly on its executor. A
     * transition that throws fails its operation: the caller receives the event's failure
     * answer, so no continuation or waiting worker is left without a result.
     */
    private <R> void run(SessionEvent<R> event, Reply<R> reply, Executor replyOn) {
        Thread self = Thread.currentThread();
        runningThread = self;
        modelThreadOwner = self;
        boolean[] answered = new boolean[1];
        Reply<R> once = value -> {
            if (answered[0] || reply == null) return;
            answered[0] = true;
            Runnable answer = () -> reply.accept(value);
            if (Thread.currentThread() == runningThread) {
                afterCommit.add(() -> replyOn.execute(answer));
            } else {
                replyOn.execute(answer);
            }
        };
        Error fatal = null;
        try {
            event.apply(this, once);
        } catch (RuntimeException | Error error) {
            afterCommit.clear();
            MoonWakerDiagnostics.record("ERROR", "android.session-model", "event.failed",
                    "event", event.getClass().getSimpleName(),
                    "error_type", error.getClass().getName());
            once.accept(event.failure());
            if (error instanceof Error) fatal = (Error) error;
        } finally {
            publish();
            runningThread = null;
            List<Runnable> pending = new ArrayList<>(afterCommit);
            afterCommit.clear();
            for (Runnable action : pending) action.run();
        }
        if (fatal != null) throw fatal;
    }

    /** Runs {@code effect} on the main executor after the current transition commits. */
    void effect(Runnable effect) {
        afterCommit.add(() -> mainThread.execute(effect));
    }

    /** Delivers a caller completion on the main executor after the current transition. */
    private void complete(TerminationCallback callback, boolean success) {
        if (callback == null) return;
        effect(() -> {
            try {
                callback.complete(success);
            } catch (RuntimeException ignored) {
                // Every caller waiting on this operation receives its terminal result.
            }
        });
    }

    private void gameLeftOnHost(TerminationCallback callback) {
        effect(() -> {
            try {
                callback.gameLeftOnHost();
            } catch (RuntimeException ignored) {
                // Every caller waiting on this operation receives its terminal result.
            }
        });
    }

    // ---- readers ------------------------------------------------------------------------------

    /** The last published immutable state. */
    public Snapshot snapshot() {
        return published;
    }

    public State state() {
        return published.state;
    }

    public Records records() {
        return published.records;
    }

    /** True when the retained transport has an attached presentation to switch or resume into. */
    public boolean hasAttachedPresentation() {
        Controller owner = published.controller;
        return owner != null && owner.hasAttachedPresentation();
    }

    public boolean hasRetainedSession() {
        State current = published.state;
        return current != State.NONE && current != State.TERMINATING;
    }

    public boolean canResumeInstantly() {
        Snapshot s = published;
        return (s.state == State.HOME_LIVE || s.state == State.PARKED_LIVE)
                && liveController(s) != null;
    }

    public boolean canResumeInstantly(Snapshot expected) {
        Snapshot s = published;
        return expected != null && s.state == expected.state
                && (s.state == State.HOME_LIVE || s.state == State.PARKED_LIVE)
                && s.streamSessionId.equals(expected.streamSessionId)
                && s.hostId.equalsIgnoreCase(expected.hostId)
                && s.profileId.equals(expected.profileId)
                && s.appId == expected.appId
                && s.playniteGameId.equalsIgnoreCase(expected.playniteGameId)
                && liveController(s) != null;
    }

    public boolean canSwitchGame(Snapshot expected) {
        Snapshot s = published;
        return expected != null && s.state == expected.state
                && s.streamSessionId.equals(expected.streamSessionId)
                && s.transitionId.equals(expected.transitionId) && s.attempt == expected.attempt
                && s.executionProfileId.equals(expected.executionProfileId)
                && s.playniteGameId.equalsIgnoreCase(expected.playniteGameId)
                && canSwitchGame(s, expected.hostId, expected.profileId, expected.appId);
    }

    public boolean canSwitchOrigin(String host, String origin, int targetAppId) {
        Snapshot s = published;
        return canSwitchOrigin(s, host, origin, targetAppId);
    }

    private boolean canSwitchOrigin(Snapshot s, String host, String origin, int targetAppId) {
        return s.executionProfileId.equals(GatewayConnection.normalizeProfileId(origin))
                && canSwitchGame(s, host, s.profileId, targetAppId);
    }

    boolean canSwitchGame(String expectedHostId, String expectedProfileId, int expectedAppId) {
        return canSwitchGame(published, expectedHostId, expectedProfileId, expectedAppId);
    }

    private boolean canSwitchGame(Snapshot s, String expectedHostId, String expectedProfileId,
                                  int expectedAppId) {
        Controller owner = s.state == State.PREPARING ? s.controller
                : (s.state == State.HOME_LIVE || s.state == State.PARKED_LIVE)
                ? liveController(s) : null;
        return owner != null && s.switchOwner == null
                && s.appId == expectedAppId
                && normalize(expectedHostId).equalsIgnoreCase(s.hostId)
                && GatewayConnection.normalizeProfileId(expectedProfileId).equals(s.profileId);
    }

    public boolean isPreparing(Controller owner, String expectedStreamSessionId,
                               String expectedHostId, int expectedAppId,
                               String expectedTransitionId, long expectedAttempt) {
        return matchesPreparing(published, owner, expectedStreamSessionId, expectedHostId,
                expectedAppId, expectedTransitionId, expectedAttempt);
    }

    public boolean isPreparingHomeFrameAccepted(
            String expectedStreamSessionId, String expectedHostId, int expectedAppId,
            String expectedGameId, String expectedTransitionId, long expectedAttempt) {
        Snapshot s = published;
        return (s.state == State.PREPARING || s.state == State.HOME_LIVE
                || s.state == State.PARKED_LIVE)
                && s.streamSessionId.equals(normalize(expectedStreamSessionId))
                && s.hostId.equalsIgnoreCase(normalize(expectedHostId))
                && s.appId == expectedAppId
                && s.playniteGameId.equalsIgnoreCase(normalize(expectedGameId))
                && s.acceptedHomeTransitionId.equals(normalize(expectedTransitionId))
                && s.acceptedHomeAttempt == expectedAttempt;
    }

    public boolean isPreparingSwitchOwned(
            String expectedStreamSessionId, String expectedHostId, int expectedAppId,
            String expectedTransitionId, long expectedAttempt) {
        Snapshot s = published;
        return (s.state == State.PREPARING || s.state == State.HOME_LIVE)
                && s.switchOwner != null
                && s.switchSessionId.equals(normalize(expectedStreamSessionId))
                && s.hostId.equalsIgnoreCase(normalize(expectedHostId))
                && s.appId == expectedAppId
                && s.switchTransitionId.equals(normalize(expectedTransitionId))
                && s.switchAttempt == expectedAttempt;
    }

    public boolean ownsLiveOrParkedSession(Controller expectedOwner,
                                           String expectedStreamSessionId,
                                           String expectedHostId, int expectedAppId) {
        return ownsLiveOrParked(published, expectedOwner, expectedStreamSessionId,
                expectedHostId, expectedAppId);
    }

    public boolean ownsLiveOrParkedSession(Controller expectedOwner,
                                           String expectedStreamSessionId,
                                           String expectedHostId, int expectedAppId,
                                           String expectedGameId) {
        Snapshot s = published;
        return ownsLiveOrParked(s, expectedOwner, expectedStreamSessionId, expectedHostId,
                expectedAppId) && s.playniteGameId.equalsIgnoreCase(normalize(expectedGameId));
    }

    private static boolean ownsLiveOrParked(Snapshot s, Controller expectedOwner,
                                            String expectedStreamSessionId,
                                            String expectedHostId, int expectedAppId) {
        return (s.state == State.HOME_LIVE || s.state == State.PARKED_LIVE)
                && s.controller == expectedOwner
                && s.switchOwner == null && matches(s.streamSessionId, expectedStreamSessionId)
                && s.appId == expectedAppId
                && s.hostId.equalsIgnoreCase(normalize(expectedHostId));
    }

    /** Confirms every retained binding immediately before an external switch mutation. */
    public boolean ownsSwitchBeforeMutation(Controller expectedOwner, SwitchRequest request,
                                            long expectedControlGeneration) {
        Snapshot s = published;
        return request != null && !request.preparing
                && expectedControlGeneration > 0L
                && (s.state == State.HOME_LIVE || s.state == State.PARKED_LIVE)
                && s.controller == expectedOwner && s.switchOwner == expectedOwner
                && s.streamSessionId.equals(request.streamSessionId)
                && s.switchSessionId.equals(request.streamSessionId)
                && s.hostId.equalsIgnoreCase(request.hostId)
                && s.profileId.equals(request.profileId)
                && s.executionProfileId.equals(request.executionProfileId)
                && s.appId == request.appId
                && s.playniteGameId.equalsIgnoreCase(request.oldGameId)
                && s.transitionId.equals(request.transitionId)
                && s.attempt == request.attempt
                && s.switchTransitionId.equals(request.transitionId)
                && s.switchAttempt == request.attempt
                && s.controlGeneration == expectedControlGeneration;
    }

    /** Consumes provider proof only while every retained binding is still exact. */
    public boolean consumeProviderTransferAuthorization(
            ProviderTransferAuthorization authorization) {
        Snapshot s = published;
        if (authorization == null
                || s.controller != authorization.owner
                || !matches(s.streamSessionId, authorization.streamSessionId)
                || (s.state != State.PREPARING && s.state != State.HOME_LIVE
                && s.state != State.PARKED_LIVE)
                || !s.hostId.equalsIgnoreCase(authorization.hostId)
                || !s.profileId.equals(authorization.profileId)
                || !s.executionProfileId.equals(authorization.executionProfileId)
                || s.appId != authorization.appId
                || !s.playniteGameId.equalsIgnoreCase(authorization.playniteGameId)
                || s.controlGeneration != authorization.generation
                || authorization.previousGeneration <= 0L
                || authorization.generation <= authorization.previousGeneration
                || authorization.actorLeaseEpoch <= 0L
                || authorization.processId <= 0) return false;
        return authorization.consumed.compareAndSet(false, true);
    }

    /**
     * The live transport owner of {@code s}, or null. A retained state whose transport is gone
     * is corrected by a posted probe event; readers never change state themselves.
     */
    private Controller liveController(Snapshot s) {
        Controller owner = s.controller;
        if (owner != null && owner.isRetainedTransportLive()) return owner;
        if (s.state == State.HOME_LIVE || s.state == State.PARKED_LIVE) {
            post(new SessionEvent.ProbeTransport());
        }
        return null;
    }

    // ---- subscription (main thread) ------------------------------------------------------------

    /** Registers {@code listener} for main-thread delivery; returns the current snapshot. */
    public Snapshot subscribe(Listener listener) {
        Snapshot current = published;
        listeners.put(listener, current.sequence);
        return current;
    }

    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }

    private void publish() {
        if (recordsChanged) {
            records = stores.read();
            recordsChanged = false;
        }
        Snapshot next = new Snapshot(this, records, sequence);
        boolean changed = !next.sameAs(delivered);
        if (changed) {
            next = new Snapshot(this, records, ++sequence);
            delivered = next;
        }
        published = next;
        if (changed && deliveryPosted.compareAndSet(false, true)) {
            mainThread.execute(this::deliver);
        }
    }

    private void deliver() {
        deliveryPosted.set(false);
        Snapshot next = published;
        for (Listener listener : new ArrayList<>(listeners.keySet())) {
            Long seen = listeners.get(listener);
            if (seen == null || seen >= next.sequence) continue;
            listeners.put(listener, next.sequence);
            try {
                listener.onSnapshot(next);
            } catch (RuntimeException error) {
                MoonWakerDiagnostics.record("WARN", "android.session-model",
                        "subscriber.failed", "error_type", error.getClass().getName());
            }
        }
    }

    // ---- transitions (model thread only) --------------------------------------------------------

    void enterHome(Controller owner, String retainedStreamSessionId, String retainedHostId,
                   String retainedProfileId, int retainedAppId, String retainedPlayniteGameId) {
        if (state == State.PREPARING) return;
        String sessionId = normalize(retainedStreamSessionId);
        if (sessionId.isEmpty()) {
            throw new IllegalArgumentException("Stream session ID is required");
        }
        establishTransportGeneration(owner, sessionId);
        if (!sessionId.equals(streamSessionId)) controlGeneration = 0L;
        if (!sessionId.equals(streamSessionId) || controller != owner) releaseSwitch();
        controller = owner;
        streamSessionId = sessionId;
        hostId = retainedHostId == null ? "" : retainedHostId;
        profileId = GatewayConnection.normalizeProfileId(retainedProfileId);
        executionProfileId = origin(owner, profileId);
        appId = retainedAppId;
        playniteGameId = retainedPlayniteGameId == null ? "" : retainedPlayniteGameId;
        transitionId = "";
        attempt = 0L;
        clearHomeFrame();
        setState(State.HOME_LIVE);
    }

    boolean beginPreparing(Controller owner, String preparingStreamSessionId,
                           String preparingHostId, String preparingProfileId,
                           int preparingAppId, String preparingPlayniteGameId,
                           String preparingTransitionId, long preparingAttempt) {
        String sessionId = normalize(preparingStreamSessionId);
        String host = normalize(preparingHostId);
        String profile = GatewayConnection.normalizeProfileId(preparingProfileId);
        String transition = normalize(preparingTransitionId);
        if (owner == null || sessionId.isEmpty() || host.isEmpty()
                || preparingAppId <= 0 || transition.isEmpty() || preparingAttempt <= 0L) {
            return false;
        }
        if (profileId.equals(profile) && matchesPreparing(owner, sessionId, host,
                preparingAppId, transition, preparingAttempt)) return true;
        boolean exactReconnect = state == State.RECONNECT_REQUIRED
                && streamSessionId.equals(sessionId)
                && hostId.equalsIgnoreCase(host)
                && profileId.equals(profile)
                && appId == preparingAppId
                && playniteGameId.equalsIgnoreCase(normalize(preparingPlayniteGameId));
        if (state != State.NONE && !exactReconnect) return false;
        if (!exactReconnect) establishTransportGeneration(owner, sessionId);
        if (!sessionId.equals(streamSessionId)) controlGeneration = 0L;
        controller = owner;
        streamSessionId = sessionId;
        hostId = host;
        profileId = profile;
        executionProfileId = origin(owner, profileId);
        appId = preparingAppId;
        playniteGameId = normalize(preparingPlayniteGameId);
        transitionId = transition;
        attempt = preparingAttempt;
        clearHomeFrame();
        releaseSwitch();
        setState(State.PREPARING);
        MoonWakerDiagnostics.record("INFO", "android.retained-stream",
                "preparing.entered", "state", "PREPARING",
                "stream_session_id", streamSessionId, "host_id", hostId,
                "profile_id", profileId, "app_id", appId,
                "game_id", playniteGameId, "transition_id", transitionId,
                "attempt", attempt);
        return true;
    }

    boolean completePreparing(Controller owner, String expectedStreamSessionId,
                              String expectedHostId, int expectedAppId,
                              String expectedTransitionId, long expectedAttempt) {
        if (!matchesPreparing(owner, expectedStreamSessionId, expectedHostId,
                expectedAppId, expectedTransitionId, expectedAttempt)) return false;
        setState(State.HOME_LIVE);
        MoonWakerDiagnostics.record("INFO", "android.retained-stream",
                "preparing.completed", "from", "PREPARING", "to", "HOME_LIVE",
                "stream_session_id", streamSessionId, "host_id", hostId,
                "profile_id", profileId, "app_id", appId,
                "game_id", playniteGameId, "transition_id", transitionId,
                "attempt", attempt);
        transitionId = "";
        attempt = 0L;
        return true;
    }

    boolean cancelPreparing(Controller owner, String expectedStreamSessionId,
                            String expectedHostId, int expectedAppId,
                            String expectedTransitionId, long expectedAttempt) {
        if (!matchesPreparing(owner, expectedStreamSessionId, expectedHostId,
                expectedAppId, expectedTransitionId, expectedAttempt)) return false;
        Object[] fields = {"from", "PREPARING", "to", "NONE",
                "stream_session_id", streamSessionId, "host_id", hostId,
                "profile_id", profileId, "app_id", appId, "game_id", playniteGameId,
                "transition_id", transitionId, "attempt", attempt};
        clear();
        MoonWakerDiagnostics.record("INFO", "android.retained-stream",
                "preparing.cancelled", fields);
        return true;
    }

    /** Asks the transport owner to cancel exactly {@code expected}; replies its answer. */
    void cancelPreparingTransport(Snapshot expected, Reply<Boolean> reply) {
        Controller owner = matchesPreparingSnapshot(expected) ? controller : null;
        if (owner == null) {
            reply.accept(false);
            return;
        }
        effect(() -> reply.accept(ownerAnswer(() -> owner.cancelPreparingTransport(expected))));
    }

    /** Claims the preparing Home frame, asks the owner, and commits on the result event. */
    void preparingHomeFrameSubmitted(Snapshot expected, Reply<Boolean> reply) {
        if (!matchesPreparingSnapshot(expected) || homeFrameClaim != 0L
                || (acceptedHomeTransitionId.equals(expected.transitionId)
                && acceptedHomeAttempt == expected.attempt) || controller == null) {
            reply.accept(false);
            return;
        }
        Controller owner = controller;
        long claim = ++nextOperationId;
        homeFrameClaim = claim;
        effect(() -> {
            boolean accepted = ownerAnswer(() -> owner.preparingHomeFrameSubmitted(expected));
            dispatchFromEffect(new SessionEvent.HomeFrameResult(claim, owner, expected, accepted),
                    reply);
        });
    }

    void homeFrameResult(long claim, Controller owner, Snapshot expected, boolean accepted,
                         Reply<Boolean> reply) {
        boolean committed = false;
        if (homeFrameClaim == claim) {
            if (accepted && controller == owner && matchesClaimedHomeFrame(expected)) {
                acceptedHomeTransitionId = expected.transitionId;
                acceptedHomeAttempt = expected.attempt;
                committed = true;
                MoonWakerDiagnostics.record("INFO", "android.retained-stream",
                        "preparing.home_frame_accepted", "state", state.name(),
                        "stream_session_id", expected.streamSessionId,
                        "host_id", expected.hostId, "profile_id", expected.profileId,
                        "app_id", expected.appId, "game_id", expected.playniteGameId,
                        "transition_id", expected.transitionId, "attempt", expected.attempt);
            }
            homeFrameClaim = 0L;
        }
        reply.accept(committed);
    }

    boolean markPreparingReconnectRequired(Controller owner, String expectedStreamSessionId,
                                           String expectedHostId, int expectedAppId,
                                           String expectedTransitionId, long expectedAttempt) {
        if (!matchesPreparing(owner, expectedStreamSessionId, expectedHostId,
                expectedAppId, expectedTransitionId, expectedAttempt)) return false;
        controller = null;
        releaseSwitch();
        setState(State.RECONNECT_REQUIRED);
        transitionId = "";
        attempt = 0L;
        clearHomeFrame();
        return true;
    }

    boolean bindControlGeneration(Controller expectedOwner, String expectedStreamSessionId,
                                  long generation) {
        if (generation <= 0L || controller != expectedOwner
                || !matches(streamSessionId, expectedStreamSessionId)) return false;
        controlGeneration = generation;
        return true;
    }

    /**
     * Two-owner commit of a host-authorized actor/generation. The model checks every binding,
     * issues the connection commit as an effect under a fresh operation id, and applies the
     * actor and generation only when the matching result arrives with the bindings unchanged.
     */
    void reauthorize(Controller owner, String sessionId, String origin, String actor,
                     long expectedGeneration, long generation, BooleanSupplier commitConnection,
                     ProviderTransfer transfer, Reply<Object> reply) {
        String previousActor = profileId;
        if (!reauthorizable(owner, sessionId, origin, expectedGeneration, generation)
                || (transfer != null && !transfer.matches(this))) {
            reply.accept(transfer == null ? Boolean.FALSE : null);
            return;
        }
        long operation = ++nextOperationId;
        reauthorizationOperation = operation;
        effect(() -> {
            boolean committed;
            try {
                committed = commitConnection.getAsBoolean();
            } catch (RuntimeException error) {
                committed = false;
            }
            dispatchFromEffect(new SessionEvent.ReauthorizationResult(operation, owner,
                    sessionId, origin, actor, previousActor, expectedGeneration, generation,
                    committed, transfer), reply);
        });
    }

    void reauthorizationResult(long operation, Controller owner, String sessionId,
                               String origin, String actor, String previousActor,
                               long expectedGeneration, long generation, boolean committed,
                               ProviderTransfer transfer, Reply<Object> reply) {
        boolean current = reauthorizationOperation == operation;
        if (current) reauthorizationOperation = 0L;
        if (!current || !committed
                || !reauthorizable(owner, sessionId, origin, expectedGeneration, generation)
                || (transfer != null && !transfer.matches(this))) {
            reply.accept(transfer == null ? Boolean.FALSE : null);
            return;
        }
        profileId = GatewayConnection.normalizeProfileId(actor);
        controlGeneration = generation;
        if (transfer == null) {
            reply.accept(Boolean.TRUE);
            return;
        }
        reply.accept(new ProviderTransferAuthorization(owner, transfer.previousTransitionId,
                transfer.replacementTransitionId, streamSessionId, hostId, previousActor,
                profileId, executionProfileId, appId, playniteGameId, transfer.processToken,
                transfer.processId, expectedGeneration, generation, transfer.actorLeaseEpoch));
    }

    private boolean reauthorizable(Controller owner, String sessionId, String origin,
                                   long expectedGeneration, long generation) {
        return controller == owner && matches(streamSessionId, sessionId)
                && (state == State.PREPARING || state == State.HOME_LIVE
                || state == State.PARKED_LIVE)
                && executionProfileId.equals(GatewayConnection.normalizeProfileId(origin))
                && expectedGeneration > 0L && controlGeneration == expectedGeneration
                && generation > expectedGeneration;
    }

    /** A failed local handoff must not advertise the obsolete generation as reusable. */
    boolean discardReauthorization(Controller owner, String sessionId,
                                   long previousGeneration, long generation) {
        if (state == State.TERMINATING || controller != owner
                || !matches(streamSessionId, sessionId)
                || (controlGeneration != previousGeneration
                && controlGeneration != generation)) return false;
        clear();
        return true;
    }

    void parkForBackground(Controller presenterOwner, String expectedStreamSessionId,
                           long expectedPresentationEpoch, Reply<Boolean> reply) {
        boolean currentPresenter = presenterOwner != null ? presenterOwner == controller
                : expectedPresentationEpoch == presentationEpoch;
        if (!matches(streamSessionId, expectedStreamSessionId) || switchOwner != null
                || !currentPresenter) {
            reply.accept(false);
            return;
        }
        Controller owner = controller;
        if (state == State.PREPARING) {
            if (owner == null) {
                reply.accept(false);
                return;
            }
            Snapshot preparing = new Snapshot(this, records, sequence);
            effect(() -> reply.accept(ownerAnswer(() -> owner.parkPreparingTransport(preparing))));
            return;
        }
        if (state != State.HOME_LIVE && state != State.PARKED_LIVE) {
            reply.accept(false);
            return;
        }
        if (owner == null) {
            setState(State.RECONNECT_REQUIRED);
            reply.accept(false);
            return;
        }
        long operation = ++nextOperationId;
        parkOperation = operation;
        String capturedId = streamSessionId;
        effect(() -> dispatchFromEffect(new SessionEvent.ParkResult(
                operation, capturedId, ownerPark(owner)), reply));
    }

    /** An owner that throws while parking has not parked a usable transport. */
    private static ParkOutcome ownerPark(Controller owner) {
        try {
            return owner.parkRetainedTransport();
        } catch (RuntimeException error) {
            MoonWakerDiagnostics.record("ERROR", "android.session-model", "effect.failed",
                    "error_type", error.getClass().getName());
            return ParkOutcome.STOPPED;
        }
    }

    void parkResult(long operation, String capturedId, ParkOutcome outcome,
                    Reply<Boolean> reply) {
        if (parkOperation != operation || !matches(streamSessionId, capturedId)
                || (state != State.HOME_LIVE && state != State.PARKED_LIVE)) {
            if (parkOperation == operation) parkOperation = 0L;
            reply.accept(false);
            return;
        }
        parkOperation = 0L;
        if (outcome == ParkOutcome.REFUSED) {
            // The stream is on screen: it stays live and retained exactly as it was.
            reply.accept(false);
            return;
        }
        boolean parked = outcome == ParkOutcome.PARKED;
        setState(parked ? State.PARKED_LIVE : State.RECONNECT_REQUIRED);
        if (!parked) controller = null;
        reply.accept(parked);
    }

    void presentationRestored(Controller owner, String expectedStreamSessionId) {
        if (owner == null || controller != owner
                || !matches(streamSessionId, expectedStreamSessionId)
                || (state != State.HOME_LIVE && state != State.PARKED_LIVE)) return;
        presentationEpoch++;
        if (state == State.PARKED_LIVE) setState(State.HOME_LIVE);
    }

    void markParked(String expectedStreamSessionId) {
        if (state == State.PREPARING || !matches(streamSessionId, expectedStreamSessionId)) return;
        if (state == State.HOME_LIVE || state == State.PARKED_LIVE) setState(State.PARKED_LIVE);
    }

    void transportLost(Controller owner, String expectedStreamSessionId) {
        if (state == State.PREPARING) {
            if (owner != null && controller == owner) {
                markPreparingReconnectRequired(owner, streamSessionId, hostId, appId,
                        transitionId, attempt);
            }
            return;
        }
        markReconnectRequired(expectedStreamSessionId);
    }

    void markReconnectRequired(String expectedStreamSessionId) {
        if (state == State.PREPARING || !matches(streamSessionId, expectedStreamSessionId)) return;
        if (state != State.TERMINATING) setState(State.RECONNECT_REQUIRED);
        controller = null;
        releaseSwitch();
    }

    boolean markTerminating(String expectedStreamSessionId, String terminatingHostId,
                            int terminatingAppId, String terminatingGameId) {
        if (state == State.PREPARING) return false;
        String expected = normalize(expectedStreamSessionId);
        if (expected.isEmpty()
                || (!streamSessionId.isEmpty() && !expected.equals(streamSessionId))) {
            return false;
        }
        State previousState = state;
        Controller previousOwner = controller;
        controller = null;
        releaseSwitch();
        streamSessionId = expected;
        hostId = terminatingHostId == null ? "" : terminatingHostId;
        appId = terminatingAppId;
        playniteGameId = terminatingGameId == null ? "" : terminatingGameId;
        transitionId = "";
        attempt = 0L;
        setState(State.TERMINATING);
        if (terminationOperation == null) {
            terminationOperation = new TerminationOperation(++nextOperationId,
                    expected, true, previousState, previousOwner, null);
        }
        return true;
    }

    DirectTermination beginDirectTermination(String expectedStreamSessionId,
                                             String terminatingHostId, int terminatingAppId,
                                             String terminatingGameId) {
        TerminationOperation previous = terminationOperation;
        if (!markTerminating(expectedStreamSessionId, terminatingHostId,
                terminatingAppId, terminatingGameId)
                || terminationOperation == previous
                || terminationOperation == null || !terminationOperation.direct) {
            return null;
        }
        return new DirectTermination(terminationOperation.id);
    }

    /** Begins recovery only if every captured local ownership fence is still current. */
    DirectTermination beginDirectTermination(Snapshot expected) {
        if (expected == null || state != expected.state
                || (state != State.HOME_LIVE && state != State.PARKED_LIVE
                && state != State.RECONNECT_REQUIRED)
                || !streamSessionId.equals(expected.streamSessionId)
                || !hostId.equalsIgnoreCase(expected.hostId)
                || !profileId.equals(expected.profileId)
                || !executionProfileId.equals(expected.executionProfileId)
                || appId != expected.appId
                || !playniteGameId.equalsIgnoreCase(expected.playniteGameId)
                || !transitionId.equals(expected.transitionId)
                || attempt != expected.attempt
                || controlGeneration != expected.controlGeneration
                || transportGeneration != expected.transportGeneration) return null;
        return beginDirectTermination(expected.streamSessionId, expected.hostId,
                expected.appId, expected.playniteGameId);
    }

    DirectTermination beginDirectTermination(Controller expectedOwner,
                                             String expectedStreamSessionId,
                                             String terminatingHostId, int terminatingAppId,
                                             String terminatingGameId) {
        if (state != State.PREPARING) {
            return beginDirectTermination(expectedStreamSessionId, terminatingHostId,
                    terminatingAppId, terminatingGameId);
        }
        String expected = normalize(expectedStreamSessionId);
        if (expectedOwner == null || controller != expectedOwner
                || !streamSessionId.equals(expected)
                || !hostId.equalsIgnoreCase(normalize(terminatingHostId))
                || appId != terminatingAppId
                || !playniteGameId.equalsIgnoreCase(normalize(terminatingGameId))) {
            return null;
        }
        Controller previousOwner = controller;
        controller = null;
        releaseSwitch();
        transitionId = "";
        attempt = 0L;
        setState(State.TERMINATING);
        terminationOperation = new TerminationOperation(++nextOperationId,
                expected, true, State.PREPARING, previousOwner, null);
        return new DirectTermination(terminationOperation.id);
    }

    /** Lazy correction: a retained live state whose transport is gone needs a reconnect. */
    void probeTransport() {
        liveControllerLocked();
    }

    boolean bindActor(Controller owner, String sessionId, String actor, String origin) {
        if (controller != owner || !streamSessionId.equals(sessionId)
                || !executionProfileId.equals(GatewayConnection.normalizeProfileId(origin))) {
            return false;
        }
        profileId = GatewayConnection.normalizeProfileId(actor);
        return true;
    }

    void switchGame(String expectedHostId, String expectedExecutionProfileId,
                    String targetProfileId, boolean targetIsChild, int expectedAppId,
                    String newGameId, String newGameName, String streamTargetName,
                    String artworkPath, long orchestrationId, BooleanSupplier cancelled,
                    SwitchCallback completion, Reply<SwitchResult> reply) {
        Controller owner = state == State.PREPARING ? controller
                : (state == State.HOME_LIVE || state == State.PARKED_LIVE)
                ? liveControllerLocked() : null;
        if (owner == null || switchOwner != null || appId != expectedAppId
                || !normalize(expectedHostId).equalsIgnoreCase(hostId)
                || !executionProfileId.equals(
                GatewayConnection.normalizeProfileId(expectedExecutionProfileId))) {
            reply.accept(SwitchResult.NOT_ELIGIBLE);
            return;
        }
        SwitchRequest request = new SwitchRequest(streamSessionId, hostId, profileId,
                executionProfileId, targetProfileId, targetIsChild, appId,
                playniteGameId, newGameId, newGameName, streamTargetName,
                artworkPath, transitionId, attempt, state == State.PREPARING,
                orchestrationId, cancelled);
        long operation = ++nextOperationId;
        switchOwner = owner;
        switchSessionId = streamSessionId;
        switchTransitionId = transitionId;
        switchAttempt = attempt;
        switchOperation = operation;
        reply.accept(SwitchResult.STARTED);
        // A synchronous owner completion is posted back as an event, never run inline.
        effect(() -> owner.switchGame(request, (outcome, error) -> post(
                new SessionEvent.SwitchCompleted(operation, request, outcome, error,
                        completion))));
    }

    void switchCompleted(long operation, SwitchRequest request, SwitchOutcome outcome,
                         String error, SwitchCallback completion) {
        boolean current = switchOperation == operation && switchOwner != null
                && switchSessionId.equals(request.streamSessionId)
                && switchTransitionId.equals(request.transitionId)
                && switchAttempt == request.attempt;
        if (current && outcome != SwitchOutcome.REUSED) releaseSwitch();
        if (completion != null && (current || !request.preparing)) {
            effect(() -> completion.complete(outcome, error));
        }
    }

    boolean updatePreparingSwitch(Controller expectedOwner, SwitchRequest request,
                                  String expectedCurrentTransitionId, String expectedGameId,
                                  String newGameId, String newTransitionId) {
        String currentTransition = normalize(expectedCurrentTransitionId);
        String nextTransition = normalize(newTransitionId);
        if (request == null || !request.preparing || nextTransition.isEmpty()
                || switchOwner != expectedOwner
                || !switchSessionId.equals(request.streamSessionId)
                || !switchTransitionId.equals(request.transitionId)
                || switchAttempt != request.attempt
                || !matchesPreparing(expectedOwner, request.streamSessionId,
                request.hostId, request.appId, currentTransition, request.attempt)
                || !playniteGameId.equalsIgnoreCase(normalize(expectedGameId))) {
            return false;
        }
        playniteGameId = normalize(newGameId);
        // The accepted Home frame belongs to this exact attempt; carry its proof forward with
        // the replacement transition so a recreated presentation still recognizes it.
        if (acceptedHomeAttempt == request.attempt
                && acceptedHomeTransitionId.equals(currentTransition)) {
            acceptedHomeTransitionId = nextTransition;
        }
        transitionId = nextTransition;
        Object[] fields = {"state", "PREPARING", "stream_session_id", streamSessionId,
                "host_id", hostId, "profile_id", profileId, "app_id", appId,
                "game_id", playniteGameId, "from", currentTransition,
                "transition_id", transitionId, "attempt", attempt,
                "orchestration_id", request.orchestrationId};
        MoonWakerDiagnostics.record("INFO", "android.retained-stream",
                "preparing.target_committed", request.orchestrationId > 0L ? fields
                        : java.util.Arrays.copyOf(fields, fields.length - 2));
        return true;
    }

    boolean finishSwitch(String expectedStreamSessionId, Controller expectedOwner) {
        if (switchOwner != expectedOwner
                || !switchSessionId.equals(normalize(expectedStreamSessionId))) {
            return false;
        }
        if ((!switchTransitionId.isEmpty() || switchAttempt != 0L)
                && (state != State.HOME_LIVE || !transitionId.isEmpty() || attempt != 0L)) {
            return false;
        }
        releaseSwitch();
        return true;
    }

    boolean finishSwitch(String expectedStreamSessionId, String expectedTransitionId,
                         long expectedAttempt, Controller expectedOwner) {
        if (switchOwner != expectedOwner
                || !switchSessionId.equals(normalize(expectedStreamSessionId))
                || !switchTransitionId.equals(normalize(expectedTransitionId))
                || switchAttempt != expectedAttempt) {
            return false;
        }
        releaseSwitch();
        return true;
    }

    boolean updateOwnedSwitchGame(Controller expectedOwner, SwitchRequest request,
                                  String expectedOldGameId, String newGameId) {
        if (request == null || (state != State.HOME_LIVE && state != State.PARKED_LIVE)
                || controller != expectedOwner || switchOwner != expectedOwner
                || !switchSessionId.equals(request.streamSessionId)
                || !switchTransitionId.equals(request.transitionId)
                || switchAttempt != request.attempt
                || !matches(streamSessionId, request.streamSessionId)
                || appId != request.appId
                || !hostId.equalsIgnoreCase(request.hostId)
                || (!playniteGameId.equalsIgnoreCase(normalize(expectedOldGameId))
                && !playniteGameId.equalsIgnoreCase(normalize(newGameId)))) return false;
        playniteGameId = normalize(newGameId);
        if (request.orchestrationId > 0L && acceptedHomeAttempt > 0L
                && !acceptedHomeTransitionId.isEmpty()) {
            MoonWakerDiagnostics.record("INFO", "android.retained-stream",
                    "home.target_committed", "state", state.name(),
                    "stream_session_id", streamSessionId, "host_id", hostId,
                    "profile_id", profileId, "app_id", appId,
                    "game_id", playniteGameId,
                    "source_transition_id", acceptedHomeTransitionId,
                    "source_attempt", acceptedHomeAttempt,
                    "orchestration_id", request.orchestrationId);
        }
        return true;
    }

    boolean markTransportUnsafe(Controller expectedOwner, String expectedStreamSessionId,
                                String expectedHostId, int expectedAppId) {
        if (expectedOwner == null || controller != expectedOwner
                || !matches(streamSessionId, expectedStreamSessionId)
                || appId != expectedAppId
                || !hostId.equalsIgnoreCase(normalize(expectedHostId))) return false;
        setState(State.RECONNECT_REQUIRED);
        controller = null;
        releaseSwitch();
        return true;
    }

    boolean retainNeutralAfterGameStopped(Controller expectedOwner,
                                          String expectedStreamSessionId,
                                          String expectedHostId, int expectedAppId,
                                          String expectedGameId) {
        String sessionId = normalize(expectedStreamSessionId);
        String expectedHost = normalize(expectedHostId);
        if (state == State.NONE) {
            if (expectedOwner == null || sessionId.isEmpty() || expectedHost.isEmpty()) {
                return false;
            }
            controller = expectedOwner;
            establishTransportGeneration(expectedOwner, sessionId);
            streamSessionId = sessionId;
            hostId = expectedHost;
            executionProfileId = origin(expectedOwner, profileId);
            appId = expectedAppId;
            playniteGameId = "";
            setState(State.HOME_LIVE);
            return true;
        }
        Snapshot current = new Snapshot(this, records, sequence);
        if ((state == State.HOME_LIVE || state == State.PARKED_LIVE)
                && controller == expectedOwner && switchOwner == null
                && matches(streamSessionId, sessionId) && appId == expectedAppId
                && hostId.equalsIgnoreCase(expectedHost) && playniteGameId.isEmpty()) {
            return true;
        }
        if (!ownsLiveOrParked(current, expectedOwner, sessionId, expectedHost, expectedAppId)
                || !playniteGameId.equalsIgnoreCase(normalize(expectedGameId))) return false;
        playniteGameId = "";
        return true;
    }

    void terminate(String expectedStreamSessionId, String gameId,
                   TerminationCallback completion, Reply<TerminationResult> reply) {
        if (!matches(streamSessionId, expectedStreamSessionId) || state == State.PREPARING) {
            reply.accept(TerminationResult.NO_CONTROLLER);
            return;
        }
        if (state == State.TERMINATING) {
            if (terminationOperation != null
                    && terminationOperation.streamSessionId.equals(streamSessionId)
                    && completion != null) {
                terminationOperation.completions.add(completion);
            }
            reply.accept(TerminationResult.IN_PROGRESS);
            return;
        }
        State previousState = state;
        Controller owner = liveControllerLocked();
        controller = null;
        if (owner == null) {
            reply.accept(TerminationResult.NO_CONTROLLER);
            return;
        }
        setState(State.TERMINATING);
        TerminationOperation operation = new TerminationOperation(++nextOperationId,
                streamSessionId, false, previousState, owner, completion);
        terminationOperation = operation;
        reply.accept(TerminationResult.STARTED);
        TerminationCallback finished = new TerminationCallback() {
            @Override public void transportUnsafe() {
                post(new SessionEvent.TerminationUnsafe(operation));
            }

            @Override public void complete(boolean success) {
                post(new SessionEvent.TerminationFinished(operation, success, false));
            }

            @Override public void gameLeftOnHost() {
                post(new SessionEvent.TerminationFinished(operation, true, true));
            }
        };
        effect(() -> {
            try {
                owner.terminateRetainedSession(gameId, finished);
            } catch (RuntimeException error) {
                finished.complete(false);
            }
        });
    }

    void terminationUnsafe(Object operation) {
        if (terminationOperation == operation) terminationOperation.transportUnsafe = true;
    }

    /**
     * A stale finish completes its own waiters with false and changes no state. A closed stream
     * ({@code success}) retains nothing, even when {@code gameLeft} reports an unverified game.
     */
    void terminationFinished(Object finished, boolean success, boolean gameLeft) {
        TerminationOperation operation = (TerminationOperation) finished;
        if (operation.completions.isEmpty() && terminationOperation != operation) return;
        boolean current = terminationOperation == operation
                && matches(streamSessionId, operation.streamSessionId)
                && state == State.TERMINATING;
        if (current) {
            if (success) {
                clear();
            } else if (operation.transportUnsafe) {
                setState(State.RECONNECT_REQUIRED);
                controller = null;
            } else {
                setState(operation.previousState);
                controller = operation.previousOwner;
            }
        }
        if (terminationOperation == operation) terminationOperation = null;
        for (TerminationCallback waiting : operation.completions) {
            if (current && success && gameLeft) gameLeftOnHost(waiting);
            else complete(waiting, current && success);
        }
        operation.completions.clear();
    }

    boolean completeDirectTermination(DirectTermination token, boolean success,
                                      boolean gameLeft, boolean transportUnsafe) {
        TerminationOperation operation = terminationOperation;
        if (token == null || operation == null || operation.id != token.operationId
                || !operation.direct || state != State.TERMINATING
                || !operation.streamSessionId.equals(streamSessionId)) {
            return false;
        }
        terminationOperation = null;
        if (success || operation.previousState == State.NONE) {
            clear();
        } else if (transportUnsafe) {
            setState(State.RECONNECT_REQUIRED);
            controller = null;
        } else {
            setState(operation.previousState);
            controller = operation.previousOwner;
        }
        for (TerminationCallback completion : operation.completions) {
            if (success && gameLeft) gameLeftOnHost(completion);
            else complete(completion, success);
        }
        return true;
    }

    boolean clearIfMatches(String expectedStreamSessionId) {
        if (state == State.PREPARING || !matches(streamSessionId, expectedStreamSessionId)) {
            return false;
        }
        clear();
        return true;
    }

    boolean clearAfterAuthoritativeEndIfMatches(String expectedStreamSessionId) {
        if (state == State.PREPARING || state == State.TERMINATING
                || !matches(streamSessionId, expectedStreamSessionId)) return false;
        clear();
        return true;
    }

    boolean relinquishReconnect(Snapshot preparing) {
        if (state != State.RECONNECT_REQUIRED
                || !streamSessionId.equals(preparing.streamSessionId)
                || !hostId.equalsIgnoreCase(preparing.hostId)
                || appId != preparing.appId
                || !playniteGameId.equalsIgnoreCase(preparing.playniteGameId)) return false;
        clear();
        return true;
    }

    boolean hardResetIfHostMatches(String expectedHostId) {
        String expected = normalize(expectedHostId);
        if (expected.isEmpty() || !expected.equalsIgnoreCase(hostId)) return false;
        Snapshot preparing = state == State.PREPARING
                ? new Snapshot(this, records, sequence) : null;
        Controller owner = preparing != null ? controller : liveControllerLocked();
        clear();
        if (owner != null) {
            effect(() -> {
                try {
                    if (preparing != null) owner.cancelPreparingTransport(preparing);
                    else owner.disconnectRetainedStream(null);
                } catch (RuntimeException ignored) {
                    // The authoritative local reset is already complete.
                }
            });
        }
        return true;
    }

    /**
     * The device forgets a host: its live, retained or preparing session is reset locally and
     * every record of it (reconnect, suspended sessions, sleep, host-selection request) is gone.
     */
    boolean forgetHost(String hostId) {
        String host = normalize(hostId);
        if (host.isEmpty()) return false;
        hardResetIfHostMatches(host);
        if (LocalHostRecords.mentions(stores.resume, host)) stores.resume.edit().clear().apply();
        LocalHostRecords.sweep(stores.suspended, host);
        LocalHostRecords.sweep(stores.sleep, host);
        recordsChanged = true;
        return true;
    }

    // ---- persistent records (model thread only) ------------------------------------------------

    SessionResumeManager.PendingSession saveReconnect(Intent record, String streamSessionId,
                                                      boolean autoResume) {
        SessionResumeManager.save(stores.resume, record, streamSessionId, autoResume);
        recordsChanged = true;
        return SessionResumeManager.read(stores.resume.getAll());
    }

    boolean clearReconnect(String streamSessionId) {
        boolean cleared = SessionResumeManager.clearIfMatches(stores.resume, streamSessionId);
        recordsChanged |= cleared;
        return cleared;
    }

    SharedPreferences suspendedStore() {
        recordsChanged = true;
        return stores.suspended;
    }

    SharedPreferences sleepStore() {
        recordsChanged = true;
        return stores.sleep;
    }

    // ---- helpers -------------------------------------------------------------------------------


    /** An owner effect's answer; an owner that throws answers false (logged). */
    private static boolean ownerAnswer(BooleanSupplier owner) {
        try {
            return owner.getAsBoolean();
        } catch (RuntimeException error) {
            MoonWakerDiagnostics.record("ERROR", "android.session-model", "effect.failed",
                    "error_type", error.getClass().getName());
            return false;
        }
    }

    /** Posts an effect's result event; the original caller's reply is answered by it. */
    private <R> void dispatchFromEffect(SessionEvent<R> event, Reply<R> reply) {
        modelThread.execute(() -> run(event, reply, Runnable::run));
    }

    private void releaseSwitch() {
        switchOwner = null;
        switchSessionId = "";
        switchTransitionId = "";
        switchAttempt = 0L;
        switchOperation = 0L;
    }

    private void clearHomeFrame() {
        homeFrameClaim = 0L;
        acceptedHomeTransitionId = "";
        acceptedHomeAttempt = 0L;
    }

    private void clear() {
        setState(State.NONE);
        terminationOperation = null;
        controller = null;
        streamSessionId = "";
        hostId = "";
        profileId = GatewayConnection.DEFAULT_PROFILE_ID;
        executionProfileId = GatewayConnection.DEFAULT_PROFILE_ID;
        appId = 0;
        playniteGameId = "";
        transitionId = "";
        attempt = 0L;
        controlGeneration = 0L;
        transportGeneration = 0L;
        parkOperation = 0L;
        reauthorizationOperation = 0L;
        clearHomeFrame();
        releaseSwitch();
    }

    private void establishTransportGeneration(Controller owner, String sessionId) {
        if (transportGeneration > 0L && streamSessionId.equals(sessionId)
                && controller == owner) return;
        if (nextTransportGeneration == Long.MAX_VALUE) nextTransportGeneration = 0L;
        transportGeneration = ++nextTransportGeneration;
    }

    private Controller liveControllerLocked() {
        Controller owner = controller;
        if (owner != null && owner.isRetainedTransportLive()) return owner;
        if (state == State.HOME_LIVE || state == State.PARKED_LIVE) {
            setState(State.RECONNECT_REQUIRED);
            controller = null;
            releaseSwitch();
        }
        return null;
    }

    private boolean matchesPreparing(Controller expectedOwner, String expectedStreamSessionId,
                                     String expectedHostId, int expectedAppId,
                                     String expectedTransitionId, long expectedAttempt) {
        return state == State.PREPARING
                && controller == expectedOwner
                && matches(streamSessionId, expectedStreamSessionId)
                && hostId.equalsIgnoreCase(normalize(expectedHostId))
                && appId == expectedAppId
                && transitionId.equals(normalize(expectedTransitionId))
                && attempt == expectedAttempt;
    }

    private static boolean matchesPreparing(Snapshot s, Controller expectedOwner,
                                            String expectedStreamSessionId,
                                            String expectedHostId, int expectedAppId,
                                            String expectedTransitionId, long expectedAttempt) {
        return s.state == State.PREPARING
                && s.controller == expectedOwner
                && matches(s.streamSessionId, expectedStreamSessionId)
                && s.hostId.equalsIgnoreCase(normalize(expectedHostId))
                && s.appId == expectedAppId
                && s.transitionId.equals(normalize(expectedTransitionId))
                && s.attempt == expectedAttempt;
    }

    private boolean matchesPreparingSnapshot(Snapshot expected) {
        return expected != null && state == State.PREPARING
                && streamSessionId.equals(expected.streamSessionId)
                && hostId.equalsIgnoreCase(expected.hostId)
                && profileId.equals(expected.profileId)
                && appId == expected.appId
                && playniteGameId.equalsIgnoreCase(expected.playniteGameId)
                && transitionId.equals(expected.transitionId)
                && attempt == expected.attempt;
    }

    private boolean matchesClaimedHomeFrame(Snapshot expected) {
        if (matchesPreparingSnapshot(expected)) return true;
        return (state == State.HOME_LIVE || state == State.PARKED_LIVE)
                && streamSessionId.equals(expected.streamSessionId)
                && hostId.equalsIgnoreCase(expected.hostId)
                && profileId.equals(expected.profileId)
                && appId == expected.appId
                && playniteGameId.equalsIgnoreCase(expected.playniteGameId)
                && transitionId.isEmpty() && attempt == 0L;
    }

    private static String origin(Controller owner, String actor) {
        String value = owner == null ? null : owner.getExecutionProfileId();
        return GatewayConnection.normalizeProfileId(value == null ? actor : value);
    }

    private static boolean matches(String current, String expectedStreamSessionId) {
        String expected = normalize(expectedStreamSessionId);
        return !expected.isEmpty() && expected.equals(current);
    }

    private void setState(State next) {
        if (state == next) return;
        State previous = state;
        state = next;
        if (previous == State.TERMINATING || next == State.TERMINATING) terminationEpoch++;
        MoonWakerDiagnostics.record("INFO", "android.retained-stream",
                "retained_stream.state_changed",
                "from", previous.name(), "to", next.name(), "state", next.name(),
                "stream_session_id", streamSessionId, "host_id", hostId,
                "profile_id", profileId, "app_id", appId, "game_id", playniteGameId,
                "transition_id", transitionId, "attempt", attempt);
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /** Process identity a provider handoff must still match when its result arrives. */
    static final class ProviderTransfer {
        final String previousTransitionId;
        final String replacementTransitionId;
        final String hostId;
        final int appId;
        final String gameId;
        final String processToken;
        final int processId;
        final long actorLeaseEpoch;

        ProviderTransfer(String previousTransitionId, String replacementTransitionId,
                         String hostId, int appId, String gameId, String processToken,
                         int processId, long actorLeaseEpoch) {
            this.previousTransitionId = normalize(previousTransitionId);
            this.replacementTransitionId = normalize(replacementTransitionId);
            this.hostId = normalize(hostId);
            this.appId = appId;
            this.gameId = normalize(gameId);
            this.processToken = normalize(processToken);
            this.processId = processId;
            this.actorLeaseEpoch = actorLeaseEpoch;
        }

        boolean matches(SessionModel m) {
            return !processToken.isEmpty() && processId > 0 && actorLeaseEpoch > 0L
                    && !previousTransitionId.isEmpty() && !replacementTransitionId.isEmpty()
                    && m.hostId.equalsIgnoreCase(hostId) && m.appId == appId
                    && m.playniteGameId.equalsIgnoreCase(gameId);
        }
    }

    /** One-shot proof that the host and retained transport accepted an exact actor handoff. */
    public static final class ProviderTransferAuthorization {
        public final String previousTransitionId;
        public final String replacementTransitionId;
        public final String streamSessionId;
        public final String hostId;
        public final String previousProfileId;
        public final String profileId;
        public final String executionProfileId;
        public final int appId;
        public final String playniteGameId;
        public final String processToken;
        public final int processId;
        public final long previousGeneration;
        public final long generation;
        public final long actorLeaseEpoch;
        private final Controller owner;
        private final AtomicBoolean consumed = new AtomicBoolean();

        ProviderTransferAuthorization(
                Controller owner, String previousTransitionId, String replacementTransitionId,
                String streamSessionId, String hostId, String previousProfileId,
                String profileId, String executionProfileId, int appId,
                String playniteGameId, String processToken, int processId,
                long previousGeneration, long generation, long actorLeaseEpoch) {
            this.owner = owner;
            this.previousTransitionId = normalize(previousTransitionId);
            this.replacementTransitionId = normalize(replacementTransitionId);
            this.streamSessionId = normalize(streamSessionId);
            this.hostId = normalize(hostId);
            this.previousProfileId = GatewayConnection.normalizeProfileId(previousProfileId);
            this.profileId = GatewayConnection.normalizeProfileId(profileId);
            this.executionProfileId = GatewayConnection.normalizeProfileId(executionProfileId);
            this.appId = appId;
            this.playniteGameId = normalize(playniteGameId);
            this.processToken = normalize(processToken);
            this.processId = processId;
            this.previousGeneration = previousGeneration;
            this.generation = generation;
            this.actorLeaseEpoch = actorLeaseEpoch;
        }
    }
}
