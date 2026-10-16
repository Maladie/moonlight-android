package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionSpec;
import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.session.SessionModel;
import com.limelight.session.SessionResumeManager;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Routes a user target from one fresh canonical session snapshot, and owns the one decision
 * table "session state x user intent -> action" ({@link #transition}). The stream screen asks
 * it what Back, Cancel and Retry mean; it only executes the answer.
 */
public final class SessionOrchestrator implements AutoCloseable {
    enum Rejection { INITIALIZING, UNPAIRED }
    enum CloseResult { CLOSED, REUSED, CANCELLED, NEEDS_CONFIRMATION, PRESENTATION_REQUIRED }

    /** What a Play queued behind a close does once the session changed ({@link #queuedStep}). */
    enum QueueStep { WAIT, START, DROP_CLOSE_FAILED }

    /** Why a queued Play was dropped; the effects show it. */
    enum QueueDrop { CLOSE_UNCONFIRMED, CLOSE_FAILED }

    /** How the table answers a warm-up before anything contacts the host. */
    enum WarmUpStart {
        /** Nothing of this client's runs for the host: its warm-up starts. */
        START,
        /** Another host or execution profile holds the stream, or it is closing. */
        REFUSED,
        /** The stream already lives (Home or parked): nothing to warm up. */
        LIVE,
        /** Its start is still PREPARING on the host. */
        PREPARING
    }

    /** How a prepared warm-up opens its stream ({@link #warmUpLaunch}). */
    enum WarmUpLaunch {
        /** This client's saved neutral stream of the same target resumes. */
        RECONNECT,
        /** A new stream starts. */
        LAUNCH
    }

    /** How a competing session gives way to the request; decided at effect time. */
    enum ClosePath {
        /** The retained neutral stream takes the game itself. */
        SWITCH,
        /** That switch must run inside an attaching stream screen. */
        ATTACH,
        /** Replacing what runs needs the user's confirmation first. */
        CONFIRM,
        /** This client's exact retained stream is closed through its owner. */
        CLOSE_RETAINED,
        /** The host session is closed through its control plane. */
        CLOSE_DIRECT,
        /** The host is unknown: nothing can be switched or closed, so nothing is asked. */
        HOST_UNAVAILABLE
    }

    /** A warm-up whose host answered; the table qualifies it ({@link #warmUpQualifies}). */
    static final class PreparedWarmUp {
        final HostProfileKey profileKey;
        final NvApp target;
        final ComputerDetails host;
        /** The host's session once it answered and the warm-up observed it. */
        final SessionSnapshot readyProjection;

        PreparedWarmUp(NvApp target, ComputerDetails host, HostProfileKey profileKey,
                       SessionSnapshot readyProjection) {
            this.target = target;
            this.host = host;
            this.profileKey = profileKey;
            this.readyProjection = readyProjection;
        }
    }

    /**
     * This client's retained stream as the table reads it for one intent: plain facts read at
     * one moment by the effects, never a decision of theirs.
     */
    static final class Retained {
        /** The session model's stream (NONE when there is none). */
        final SessionModel.Snapshot stream;
        /** The intent's execution profile (a child plays on its parent's). */
        final String executionProfileId;
        /** The host's session for the intent's profile; null while the host is unknown. */
        final SessionSnapshot projection;
        /** The stream's app is the neutral MoonWaker stream. */
        final boolean neutralTarget;
        /** The session model lets this execution profile switch the stream's game. */
        final boolean originSwitchable;
        /** An Activity presents the stream. */
        final boolean presentationAttached;

        Retained(SessionModel.Snapshot stream, String executionProfileId,
                 SessionSnapshot projection, boolean neutralTarget, boolean originSwitchable,
                 boolean presentationAttached) {
            this.stream = stream;
            this.executionProfileId = executionProfileId;
            this.projection = projection;
            this.neutralTarget = neutralTarget;
            this.originSwitchable = originSwitchable;
            this.presentationAttached = presentationAttached;
        }
    }

    /** What the refresh this pass follows produced. */
    private enum Refresh { NONE, OBSERVATION, SESSION, FAILED }

    /** What one Play request carries from pass to pass: its grants and its last effect's result. */
    private static final class Progress {
        final boolean replacementAuthorized;
        final boolean reuseOnly;
        final boolean opaqueReady;
        final NvApp target;
        final int pass;
        final Refresh refresh;
        final CloseResult closed;

        Progress(boolean replacementAuthorized, boolean reuseOnly, boolean opaqueReady,
                 NvApp target, int pass, Refresh refresh, CloseResult closed) {
            this.replacementAuthorized = replacementAuthorized;
            this.reuseOnly = reuseOnly;
            this.opaqueReady = opaqueReady;
            this.target = target;
            this.pass = pass;
            this.refresh = refresh;
            this.closed = closed;
        }

        /** The next pass with these grants, under the loading layer. */
        Progress next(boolean authorized, boolean reuse) {
            return new Progress(authorized, reuse, true, target, pass + 1, Refresh.NONE, null);
        }

        Progress next(Action action) {
            return next(action.replacementAuthorized, action.reuseOnly);
        }

        /** The next pass once the user confirmed; a loading layer not yet raised still is. */
        Progress confirmed() {
            return new Progress(true, false, opaqueReady, target, pass + 1, Refresh.NONE, null);
        }

        // The three below complete a step already taken with next(): the pass it starts reads
        // what its effect produced.

        Progress prepared(NvApp prepared) {
            return new Progress(replacementAuthorized, reuseOnly, true, prepared, pass,
                    Refresh.NONE, null);
        }

        Progress refreshed(Refresh result) {
            return new Progress(replacementAuthorized, reuseOnly, true, target, pass,
                    result, null);
        }

        /** After a close only the replacement is left: no confirmation, no reuse. */
        Progress closed(CloseResult result) {
            return new Progress(true, false, true, target, pass, Refresh.NONE, result);
        }
    }

    private static final class Preparation {
        final long request;
        final String hostId;
        final PlayIntent intent;
        final HostProfileKey profileKey;
        PlayIntent pendingGame;
        boolean opaqueReady;
        PreparedWarmUp prepared;
        boolean launchClaimed;

        Preparation(long request, PlayIntent intent) {
            this.request = request;
            this.intent = intent;
            this.profileKey = intent.profileKey;
            this.hostId = SessionSnapshot.normalize(profileKey.hostId);
        }
    }

    interface Dispatcher { void post(Runnable action); }
    interface Effects {
        boolean isAvailable(); SessionSnapshot resolve(HostProfileKey key);
        /** Render-only projection of the delivered model snapshot: reads, refreshes nothing. */
        SessionSnapshot rendered(HostProfileKey key);
        /** Runs {@code action} on the main thread after {@code delayMs}. */
        void postDelayed(Runnable action, long delayMs);
        /**
         * This screen acts on the process's queued Play: it is the resumed console, and a
         * stream Home still has its stream (one being replaced by the console does not).
         */
        boolean drivesQueue();
        /** Render the queued Play ({@link #queued()}): its cover and the game tiles. */
        void queueChanged();
        /** The queued {@code intent} was dropped for {@code reason}: shown, tiles rendered. */
        void queueDropped(PlayIntent intent, QueueDrop reason);
        /**
         * The host's last poll reported it paired, or no poll has reported its pairing yet and
         * its pairing certificate is saved. A host that answers always reports its pairing.
         */
        boolean isPaired(String hostId);
        /** The host answers its polls; only then can its session be refreshed. */
        boolean isHostOnline(String hostId);
        /** This client's retained stream as the table reads it for this intent, read now. */
        Retained retained(PlayIntent intent);
        /** The session model's stream, read now (NONE when there is none); never reads disk. */
        SessionModel.Snapshot stream();
        /** The intent's execution profile (a child plays on its parent's). */
        String executionProfileId(PlayIntent intent);
        /**
         * Shows the waking status, wakes the host and waits, bounded, until it and its Gateway
         * answer. Null once both do; otherwise what never answered. Runs on the worker.
         */
        SessionFailure awaitHost(PlayIntent intent, BooleanSupplier cancelled);
        void returnToRetainedStream(); void reconnectSavedSession(PlayIntent intent);
        /** A live warm-up attempt carries the intent's game into the resumed neutral stream. */
        boolean warmUpCarriesGame();
        /** Starts the attaching stream presentation carrying the target; ends the request. */
        void attachPresentationForSwitch(long orchestrationId, PlayIntent intent, NvApp target);
        void reject(Rejection reason);
        void confirmReplacement(Runnable accepted);
        void showLoading(PlayIntent intent, LaunchTransitionType type, Runnable opaqueFrameReady);
        void refreshSession(PlayIntent intent, BooleanSupplier cancelled,
                            Consumer<Boolean> completion);
        /**
         * Runs {@code settled} once this client's retained stream is no longer PREPARING (at
         * once when it is not); never after {@code cancelled}. Runs on the main thread.
         */
        void awaitPrepared(BooleanSupplier cancelled, Runnable settled);
        HostLaunchPreflight.Result preflight(PlayIntent intent, HostLaunchPreflight.Action action,
                                             long orchestrationId,
                                             BooleanSupplier cancelled);
        /** Switches the retained stream's game: REUSED or CANCELLED, or raises its failure. */
        CloseResult switchRetained(PlayIntent intent, NvApp target,
                                   BooleanSupplier cancelled) throws Exception;
        /**
         * Closes the host session through {@code retained}, this client's exact retained
         * stream, or through its control plane when null: CLOSED or CANCELLED, or raises.
         */
        CloseResult closeSession(PlayIntent intent, SessionModel.Snapshot retained,
                                 BooleanSupplier cancelled) throws Exception;
        void launch(PlayIntent intent, NvApp target, LaunchTransitionType type,
                    String sourceSuspendId, boolean ownsFreshSunshineSession);
        void preflightFailed(HostLaunchPreflight.Failure failure);
        /** Shows the failure screen for this exact path; the orchestrator recorded it. */
        void sessionFailed(PlayIntent intent, SessionFailure reason);
        /**
         * Wakes, preflights and reads the host for a warm-up the table started; null, its
         * failure shown as the warm-up status, when it does not answer. Runs on the worker.
         */
        PreparedWarmUp prepareHost(PlayIntent intent, long request,
                                   BooleanSupplier cancelled);
        /** Shows how the table answered a warm-up (REFUSED: its error status). */
        void showWarmUpStatus(PlayIntent intent, long request, WarmUpStart status);
        /**
         * False while opening the stream screen would stall what is on screen (an entry
         * intro); {@link #openPreparedHost} follows once it no longer would.
         */
        boolean mayOpenPreparedHost();
        /** The saved reconnect record, read now; null when there is none. */
        SessionResumeManager.PendingSession pendingResume();
        /**
         * Opens the stream the table chose: RECONNECT resumes {@code record}, the saved record
         * the table decided on.
         */
        void launchPreparedHost(PreparedWarmUp prepared, WarmUpLaunch launch,
                                SessionResumeManager.PendingSession record,
                                boolean ownsFreshSunshineSession,
                                PlayIntent pendingGame, long request);
        void preparationFailed();
    }

    private final Effects effects;
    private final Executor executor;
    private final Dispatcher dispatcher;
    /**
     * The newest user request this orchestrator decides (Play, warm-up, cancel bumps it). Not
     * the model's operation id: a request exists before any model state does, and one request
     * may issue several model operations (prepare, switch, park), each fenced by its own id.
     */
    private final AtomicLong orchestrationId = new AtomicLong();
    private PlayIntent lastIntent;
    /** The request still being decided; 0 once it was handed off, refused or failed. */
    private long inFlight;
    private volatile Preparation preparation;
    private volatile boolean closed;
    /**
     * The one Play waiting for a close on its host to be confirmed (process-wide, outliving
     * this screen). A newer Play replaces it once the table queues or starts that Play; only
     * {@link #advanceQueue} starts it.
     */
    private final PlayQueue queue;
    /** The queue generation whose timeout this orchestrator armed. */
    private long armedGeneration;
    /** The request that restarted a queued Play: its re-queue keeps the first queued time. */
    private long restartedRequest;
    private long restartedSince;

    SessionOrchestrator(Effects effects, Executor executor, Dispatcher dispatcher,
                        PlayQueue queue) {
        this.effects = effects;
        this.executor = executor;
        this.dispatcher = dispatcher;
        this.queue = queue;
    }

    void play(PlayIntent intent) {
        PlayIntent waiting = queue.intent();
        // The same game again keeps its place in the queue.
        if (waiting != null && sameTarget(waiting, intent)) return;
        play(intent, 0L);
    }

    /** {@code queuedSince}: when the queued Play this restarts was first queued (0: none). */
    private void play(PlayIntent intent, long queuedSince) {
        if (handoffPreparingGame(intent)) return;
        lastIntent = intent;
        long request = orchestrationId.incrementAndGet();
        restartedRequest = queuedSince > 0L ? request : 0L;
        restartedSince = queuedSince;
        if (closed) {
            diagnostic("INFO", "orchestration.ignored", request, intent,
                    "reason", "CLOSED");
            return;
        }
        diagnostic("INFO", "orchestration.started", request, intent);
        if (!effects.isAvailable()) {
            diagnostic("WARN", "orchestration.rejected", request, intent,
                    "reason", Rejection.INITIALIZING.name());
            effects.reject(Rejection.INITIALIZING); return;
        }
        inFlight = request;
        evaluate(request, intent,
                new Progress(false, false, false, null, 0, Refresh.NONE, null));
    }

    long prepareHost(String hostId, String profileId) {
        PlayIntent intent = PlayIntent.autoWarmUp(hostId, profileId);
        long request = orchestrationId.incrementAndGet();
        preparation = null;
        if (closed) return 0L;
        if (!effects.isAvailable()) {
            return 0L;
        }
        if (!effects.isPaired(intent.hostId)) {
            return 0L;
        }
        Preparation pending = new Preparation(request, intent);
        preparation = pending;
        try {
            executor.execute(() -> {
                PreparedWarmUp prepared;
                try {
                    prepared = prepareWarmUp(
                            intent, request, () -> !currentPreparation(pending));
                } catch (RuntimeException error) {
                    dispatcher.post(() -> failPreparation(pending));
                    return;
                }
                dispatcher.post(() -> completePreparation(pending, prepared));
            });
        } catch (RuntimeException error) {
            failPreparation(pending);
            return 0L;
        }
        return request;
    }

    /** Runs on the worker: the table qualifies the warm-up, the effects only prepare its host. */
    private PreparedWarmUp prepareWarmUp(PlayIntent intent, long request,
                                         BooleanSupplier cancelled) {
        WarmUpStart start = warmUpStart(intent, effects.retained(intent));
        if (start != WarmUpStart.START) {
            if (start != WarmUpStart.REFUSED || !cancelled.getAsBoolean()) {
                effects.showWarmUpStatus(intent, request, start);
            }
            return null;
        }
        PreparedWarmUp prepared = effects.prepareHost(intent, request, cancelled);
        if (prepared == null || warmUpQualifies(prepared)) return prepared;
        effects.showWarmUpStatus(intent, request, WarmUpStart.REFUSED);
        return null;
    }

    boolean cancelPreparation(String hostId) {
        Preparation pending = preparation;
        if (!currentPreparation(pending) || !pending.hostId.equals(
                SessionSnapshot.normalize(hostId))) return false;
        preparation = null;
        orchestrationId.compareAndSet(pending.request, pending.request + 1L);
        return true;
    }

    boolean hasPreparationForHost(String hostId) {
        Preparation pending = preparation;
        return currentPreparation(pending) && pending.hostId.equals(
                SessionSnapshot.normalize(hostId));
    }

    void cancel() {
        long cancelled = orchestrationId.getAndIncrement();
        preparation = null;
        // Only a request still being decided is cancelled; one already launched is not.
        if (!closed && inFlight == cancelled && lastIntent != null) {
            diagnostic("INFO", "orchestration.cancelled", cancelled, lastIntent);
        }
        inFlight = 0L;
    }

    /**
     * This screen stops showing (paused): a Play it started from the queue and has not decided
     * yet goes back to the queue, with its first queued time, for the screen shown next (the
     * console replacing a stream Home whose stream closed). Its own cancel follows.
     */
    void screenLeft() {
        long current = orchestrationId.get();
        if (closed || inFlight != current || restartedRequest != current
                || lastIntent == null || queue.intent() != null) return;
        queue.put(lastIntent, restartedSince);
        diagnostic("INFO", "orchestration.requeued", current, lastIntent);
        effects.queueChanged();
    }

    void retry() {
        PlayIntent intent = lastIntent;
        if (intent != null && !closed) play(intent);
    }
    /** This screen is gone: it no longer acts on the queue, which stays the process's. */
    @Override public void close() {
        closed = true;
        lastIntent = null;
        cancel();
    }

    /** The Play queued behind a close, or null; the tiles render it. */
    PlayIntent queued() { return queue.intent(); }

    /** The user cancelled the queued Play (or left its host or profile); false when none. */
    boolean cancelQueued() {
        PlayIntent waiting = queue.clear();
        if (waiting == null) return false;
        diagnostic("INFO", "orchestration.queue_cancelled", orchestrationId.get(), waiting);
        effects.queueChanged();
        return true;
    }

    /**
     * The session model published a new snapshot, or this screen was resumed: the queued Play
     * takes its next step ({@link #advanceQueue}).
     */
    void sessionChanged() {
        advanceQueue();
    }

    /**
     * A Play queued behind a close starts as a normal Play once the close is confirmed, and is
     * dropped when it failed. Its bound (counted from its first queueing) passing while the
     * latest snapshot still reads closing drops it; one that ended by then still starts.
     */
    private void advanceQueue() {
        PlayIntent waiting = queue.intent();
        if (waiting == null || closed || !effects.drivesQueue()) return;
        switch (queuedStep(effects.rendered(waiting.profileKey))) {
            case WAIT:
                if (queueExpired(queue.since())) dropQueued(QueueDrop.CLOSE_UNCONFIRMED);
                else {
                    // The screen driving the queue may have just taken it over: it renders the
                    // queued cover on every step, not only when the queue itself changes.
                    effects.queueChanged();
                    armQueueTimeout();
                }
                return;
            case DROP_CLOSE_FAILED:
                dropQueued(QueueDrop.CLOSE_FAILED);
                return;
            default:
                long since = queue.since();
                queue.clear();
                diagnostic("INFO", "orchestration.queue_started", orchestrationId.get(), waiting);
                effects.queueChanged();
                play(waiting, since);
        }
    }

    private void queue(long request, PlayIntent intent) {
        // A restarted Play the live snapshot still sees closing keeps its first queued time.
        long since = request == restartedRequest ? restartedSince : queue.now();
        if (queueExpired(since)) {
            diagnostic("WARN", "orchestration.queue_dropped", request, intent,
                    "reason", QueueDrop.CLOSE_UNCONFIRMED.name());
            effects.queueDropped(intent, QueueDrop.CLOSE_UNCONFIRMED);
            return;
        }
        diagnostic("INFO", "orchestration.queued", request, intent);
        queue.put(intent, since);
        armQueueTimeout();
        effects.queueChanged();
    }

    /** The close is bounded like its own confirmation window: never a queue without end. */
    private boolean queueExpired(long since) {
        return queue.now() - since >= SessionStateResolver.CLOSING_CONFIRMATION_MS;
    }

    private void armQueueTimeout() {
        long generation = queue.generation();
        if (armedGeneration == generation) return;
        armedGeneration = generation;
        effects.postDelayed(() -> {
            if (queue.generation() != generation) return;
            armedGeneration = 0L;
            advanceQueue();
        }, Math.max(0L, queue.since() + SessionStateResolver.CLOSING_CONFIRMATION_MS
                - queue.now()));
    }

    /** A Play of another target that goes ahead replaces the queued one. */
    private void supersedeQueued(long request, PlayIntent intent) {
        PlayIntent waiting = queue.intent();
        if (waiting == null || sameTarget(waiting, intent)) return;
        queue.clear();
        diagnostic("INFO", "orchestration.queue_replaced", request, waiting);
        effects.queueChanged();
    }

    private void dropQueued(QueueDrop reason) {
        PlayIntent dropped = queue.clear();
        if (dropped == null) return;
        diagnostic("WARN", "orchestration.queue_dropped", orchestrationId.get(), dropped,
                "reason", reason.name());
        effects.queueDropped(dropped, reason);
    }

    private static boolean sameTarget(PlayIntent a, PlayIntent b) {
        return a.kind == b.kind && a.matches(b.hostId, b.profileId, b.sunshineAppId,
                b.playniteGameId);
    }

    private boolean handoffPreparingGame(PlayIntent intent) {
        Preparation pending = preparation;
        if (intent.kind != PlayIntent.Kind.PLAYNITE_GAME
                || !currentPreparation(pending) || pending.launchClaimed
                || !pending.profileKey.equals(intent.profileKey)) {
            return false;
        }
        if (pending.pendingGame != null) return true;
        pending.pendingGame = intent;
        lastIntent = intent;
        inFlight = pending.request;
        effects.showLoading(intent, LaunchTransitionType.GAME, () ->
                dispatcher.post(() -> {
                    if (!currentPreparation(pending) || pending.launchClaimed) return;
                    pending.opaqueReady = true;
                    maybeLaunch(pending);
                }));
        return true;
    }

    private void completePreparation(Preparation pending, PreparedWarmUp prepared) {
        if (!currentPreparation(pending)) return;
        if (prepared == null || prepared.target == null
                || !pending.profileKey.equals(prepared.profileKey)) {
            preparation = null;
            inFlight = 0L;
            if (pending.pendingGame != null) effects.preparationFailed();
            return;
        }
        pending.prepared = prepared;
        maybeLaunch(pending);
    }

    private void maybeLaunch(Preparation pending) {
        if (!currentPreparation(pending) || pending.launchClaimed
                || pending.prepared == null
                || pending.pendingGame != null && !pending.opaqueReady
                || !effects.mayOpenPreparedHost()) return;
        pending.launchClaimed = true;
        preparation = null;
        inFlight = 0L;
        // Read when the stream screen opens: the saved record and the stream at this moment.
        SessionResumeManager.PendingSession record = effects.pendingResume();
        effects.launchPreparedHost(pending.prepared,
                warmUpLaunch(pending.prepared, effects.stream(),
                        effects.executionProfileId(pending.intent), record),
                record, ownsFreshSession(pending.prepared.readyProjection),
                pending.pendingGame, pending.request);
    }

    /** The stream screen may open now: a prepared host that waited for this launches. */
    void openPreparedHost() {
        Preparation pending = preparation;
        if (pending != null) maybeLaunch(pending);
    }

    private void failPreparation(Preparation pending) {
        if (!currentPreparation(pending)) return;
        preparation = null;
        inFlight = 0L;
        effects.preparationFailed();
    }

    private boolean currentPreparation(Preparation pending) {
        return pending != null && !closed && preparation == pending
                && orchestrationId.get() == pending.request;
    }

    /** What the user asked for. */
    public static final class UserIntent {
        public enum Kind { PLAY, BACK, HOME, CANCEL, RETRY, RETURN_FROM_HOME }

        /** The physical Back / B button on the stream screen. */
        public static final UserIntent BACK = new UserIntent(Kind.BACK, null);
        /**
         * The pad's Home shortcut or the overlay's Home (one outcome for both): Back's rows,
         * except that Home never cancels a covered launch, it leaves it running.
         */
        public static final UserIntent HOME = new UserIntent(Kind.HOME, null);
        /** The Cancel control of the transition cover. */
        public static final UserIntent CANCEL = new UserIntent(Kind.CANCEL, null);
        /** The Retry control of the transition cover after a failure. */
        public static final UserIntent RETRY = new UserIntent(Kind.RETRY, null);
        /**
         * The user closed the Home over the stream screen and is back on its stream; asked again
         * when a fact that answer waited for settles.
         */
        public static final UserIntent RETURN_FROM_HOME =
                new UserIntent(Kind.RETURN_FROM_HOME, null);

        public final Kind kind;
        final PlayIntent target;

        private UserIntent(Kind kind, PlayIntent target) {
            this.kind = kind;
            this.target = target;
        }

        static UserIntent play(PlayIntent target) {
            return new UserIntent(Kind.PLAY, java.util.Objects.requireNonNull(target, "target"));
        }
    }

    /**
     * The facts one decision reads; plain data, filled by whoever asks. The console asks about
     * Play with the host's session projection and how far that request got; the stream screen
     * asks about Back, Cancel and Retry with the transition it presents. Cover, readiness and
     * input facts are copied from their owner, {@code LaunchTransitionController}.
     */
    public static final class State {
        // ---- console: the host's session and this Play request's progress ----
        SessionSnapshot session;
        /** The retained stream can take this game without being closed. */
        boolean canSwitchRetained;
        /** The live stream carries no game and the host names one without process proof. */
        boolean retainedGameUnproven;
        /** A live warm-up attempt carries the game into the resumed neutral stream. */
        boolean warmUpCarriesGame;
        boolean replacementAuthorized;
        boolean reuseOnly;
        boolean opaqueReady;
        boolean targetPrepared;
        /** This pass evaluates the snapshot a refresh of an unresolved observation produced. */
        boolean observationRefreshed;
        /** This pass follows a refresh (of the observation or the session) that succeeded. */
        boolean sessionRefreshed;
        /** This pass follows a refresh that failed. */
        boolean refreshFailed;
        /** What the close or switch this pass follows answered; null after any other effect. */
        CloseResult closeOutcome;
        /**
         * The host answers its polls and this client did not put it to sleep; a refresh of its
         * session needs that.
         */
        boolean hostOnline;
        /** Paired, or remembered as paired until its first poll answers. */
        boolean hostPaired;

        // ---- stream screen: the presented transition ----
        /** A transition is presented and no cancel of it is already running. */
        public boolean transitionActive;
        public LaunchTransitionType type;
        public boolean hasGame;
        public boolean connected;
        public boolean homeVisible;
        public boolean parked;
        /** The cover holds the input (the controller's inputBlocked). */
        public boolean covered;
        /** The cover is the Home return's, on its way to the Home (the controller's). */
        public boolean homeReturnCovered;
        /**
         * A user cover (Home return, switch) was released over the already revealed stream and
         * only waits for its fresh frame: nothing under it is a launch to cancel.
         */
        public boolean userCoverReleased;
        /** The presented game is stopping (the controller's GAME_STOPPING). */
        public boolean gameStopping;
        /** The stream was revealed in this transition. */
        public boolean revealed;
        public boolean timedOut;
        /**
         * The presented transition settles a kept, already used stream back to the dashboard
         * (the controller's RETURNING_TO_DASHBOARD): it is not a start.
         */
        public boolean returningToDashboard;
        public boolean gameReadyConfirmed;
        /** The user chose to see this transition's stream; nothing covers it again. */
        public boolean manuallyRevealed;
        /** The host reported this start failed: its cleanup was ordered. */
        public boolean failedStartCleanup;
        /** The host granted this transport a control generation. */
        public boolean controlGranted;
        public boolean authorizationRetryOffered;
        public boolean failedStartObservation;
        public boolean retainedSwitch;
        /** The retained switch already presents its new game's attempt. */
        public boolean retainedSwitchAttempted;
        /** A Retry of this very transition is already running. */
        public boolean retryStarted;
        /** The session model holds exactly this stream as HOME_LIVE. */
        public boolean retainedHomeLive;
        /** The stream screen is in the foreground. */
        public boolean resumed;
        /** The stream is this client's retained auto warm-up, still hidden behind the Home. */
        public boolean hiddenWarmUp;
        /** That warm-up's start is still PREPARING on the host. */
        public boolean warmUpPreparing;
        /** A Home selection was queued or submitted as a switch of this stream. */
        public boolean switchRequested;

        static State forPlay(SessionSnapshot session, boolean canSwitchRetained,
                             boolean warmUpCarriesGame, boolean replacementAuthorized,
                             boolean reuseOnly, boolean opaqueReady, boolean targetPrepared,
                             boolean observationRefreshed, boolean hostOnline,
                             boolean hostPaired) {
            State state = new State();
            state.session = session;
            state.canSwitchRetained = canSwitchRetained;
            state.warmUpCarriesGame = warmUpCarriesGame;
            state.replacementAuthorized = replacementAuthorized;
            state.reuseOnly = reuseOnly;
            state.opaqueReady = opaqueReady;
            state.targetPrepared = targetPrepared;
            state.observationRefreshed = observationRefreshed;
            // A sleep this client requested is known at once; the polls notice it only later.
            state.hostOnline = hostOnline && !session.hostSleepRequested;
            state.hostPaired = hostPaired;
            return state;
        }
    }

    public enum ActionKind {
        // ---- Play ----
        /** The host is not paired: toast, or the same message under the loading layer. */
        REFUSE_UNPAIRED,
        /**
         * A Play while a session is closing waits for its confirmation (one slot), on its own
         * launch cover from the press on (the loading layer already up included).
         */
        QUEUE_BEHIND_CLOSE,
        FAIL,
        SHOW_LOADING,
        /** Refresh an unresolved observation once; the pass it produces decides. */
        REFRESH_OBSERVATION,
        REFRESH_SESSION,
        /** Wait until this client's warm-up stream leaves PREPARING; the pass after decides. */
        AWAIT_PREPARED,
        /** Wake the host and wait for it; the pass after it answers decides. */
        WAKE_HOST,
        PREFLIGHT,
        CLOSE_COMPETING,
        LAUNCH,
        RETURN_TO_STREAM,
        RECONNECT,
        CONFIRM_REPLACEMENT,
        /** The retained switch runs inside an attaching stream screen; the request ends here. */
        ATTACH_PRESENTATION,
        // ---- Back / Cancel on the stream screen ----
        IGNORE,
        /** Back belongs to the cover: its Cancel control, when offered, raises CANCEL. */
        CANCEL_ON_COVER,
        LEAVE_SCREEN,
        SHOW_HOME,
        /** Cover the stream first, then open the home over the live stream. */
        COVER_AND_SHOW_HOME,
        /** Declines the offered authorization retry; the neutral stream stays. */
        DECLINE_AUTHORIZATION_RETRY,
        CANCEL_RETAINED_SWITCH,
        /** Closes the whole host session (stream and display), stopping only a verified game. */
        CLOSE_HOST_SESSION,
        /** This start's provider cleanup first, then the whole host session. */
        CLOSE_STARTED_SESSION,
        /** Only the local transport stops; the host stream (and any game) stays to be resumed. */
        STOP_TRANSPORT_KEEP_SESSION,
        /** The local transport stops and the host stream its launch opened is closed. */
        STOP_TRANSPORT_CLOSE_HOST_STREAM,
        /** Only the local transport stops and the session records are cleared. */
        STOP_TRANSPORT,
        // ---- Retry on the stream screen ----
        /** Accepts the offered authorization retry: the chosen game starts on this stream. */
        ACCEPT_AUTHORIZATION_RETRY,
        /** The retained switch cleans its failed attempt up and starts its game again. */
        RETRY_RETAINED_SWITCH,
        /** This start's cleanup first, then the same game again; the stream stays open. */
        RETRY_GAME_START,
        /** Connects the observation again; the stream stays open and no game is started. */
        RETRY_OBSERVATION,
        /** The transport is gone: a new start of this target with a new request time. */
        RESTART_STREAM,
        // ---- Return from the Home ----
        /** The hidden warm-up becomes the user's stream, as a Play on it would make it. */
        TAKE_OVER_WARM_UP,
        /** The kept neutral stream the user returned to begins a transition that shows it. */
        SHOW_NEUTRAL_STREAM
    }

    /** One decided step; the Play parameters are set only for the kinds that use them. */
    public static final class Action {
        public final ActionKind kind;
        final LaunchTransitionType type;
        final HostLaunchPreflight.Action preflight;
        final SessionFailure failure;
        final boolean replacementAuthorized;
        final boolean reuseOnly;
        final String sourceSuspendId;
        final boolean ownsFreshSunshineSession;

        private Action(ActionKind kind, LaunchTransitionType type,
                       HostLaunchPreflight.Action preflight, SessionFailure failure,
                       boolean replacementAuthorized, boolean reuseOnly,
                       String sourceSuspendId, boolean ownsFreshSunshineSession) {
            this.kind = kind;
            this.type = type;
            this.preflight = preflight;
            this.failure = failure;
            this.replacementAuthorized = replacementAuthorized;
            this.reuseOnly = reuseOnly;
            this.sourceSuspendId = sourceSuspendId;
            this.ownsFreshSunshineSession = ownsFreshSunshineSession;
        }

        private static Action of(ActionKind kind) {
            return new Action(kind, null, null, null, false, false, "", false);
        }

        private static Action failing(ActionKind kind, SessionFailure failure) {
            return new Action(kind, null, null, failure, false, false, "", false);
        }

        /** A step after which the request continues with these two grants. */
        private static Action step(ActionKind kind, LaunchTransitionType type,
                                   HostLaunchPreflight.Action preflight,
                                   boolean replacementAuthorized, boolean reuseOnly) {
            return new Action(kind, type, preflight, null, replacementAuthorized, reuseOnly,
                    "", false);
        }

        private static Action launch(ActionKind kind, LaunchTransitionType type,
                                     String sourceSuspendId, boolean ownsFreshSunshineSession) {
            return new Action(kind, type, null, null, false, false, sourceSuspendId,
                    ownsFreshSunshineSession);
        }
    }

    /**
     * The decision table: session state x user intent -> action. Pure and deterministic; the
     * caller executes the returned action and fences it at effect time.
     */
    public static Action transition(State state, UserIntent intent) {
        switch (intent.kind) {
            case PLAY: return play(state, intent.target);
            case BACK: return back(state);
            case HOME: return home(state);
            case RETRY: return retry(state);
            case RETURN_FROM_HOME: return returnFromHome(state);
            default: return cancel(state);
        }
    }

    /**
     * Only a settled hidden warm-up on screen becomes the user's. A start still PREPARING is
     * asked again when it completes; a requested switch retargets the stream itself and must
     * not have the old target revealed (or given the input) before it applies.
     */
    private static Action returnFromHome(State s) {
        if (!s.resumed || s.homeVisible || s.switchRequested || s.retainedSwitch) {
            return Action.of(ActionKind.IGNORE);
        }
        if (s.hiddenWarmUp) {
            return Action.of(s.warmUpPreparing ? ActionKind.IGNORE : ActionKind.TAKE_OVER_WARM_UP);
        }
        // A kept neutral stream settled for the Home has nothing left to close: the user's
        // return to it is a new transition that shows it, never its action-less closing cover.
        return Action.of(s.connected && s.returningToDashboard && !s.hasGame
                ? ActionKind.SHOW_NEUTRAL_STREAM : ActionKind.IGNORE);
    }

    private static Action play(State s, PlayIntent intent) {
        Action settled = afterEffect(s, intent);
        if (settled != null) return settled;
        // Decided on every pass: a host woken for this request reports its real pairing.
        if (!s.hostPaired) return Action.of(ActionKind.REFUSE_UNPAIRED);
        Action action = playOnHost(s, intent);
        // A session refresh needs a host that answers: one that does not is woken first, and
        // the pass after it answers decides from its fresh state. A host this client put to
        // sleep is woken before its preflight too: the polls still call it online, so the
        // preflight would not wake it.
        boolean refreshes = action.kind == ActionKind.REFRESH_SESSION
                || action.kind == ActionKind.REFRESH_OBSERVATION;
        boolean preflightsAsleep = s.session.hostSleepRequested
                && action.kind == ActionKind.PREFLIGHT
                && action.preflight != HostLaunchPreflight.Action.SWITCH_RETAINED;
        if (!s.hostOnline && (refreshes || preflightsAsleep)) {
            return Action.step(ActionKind.WAKE_HOST, null, null,
                    action.replacementAuthorized, action.reuseOnly);
        }
        return action;
    }

    /**
     * What the result of the effect this pass follows decides before anything else; null when
     * the regular rows decide.
     */
    private static Action afterEffect(State s, PlayIntent intent) {
        // A host that answers and still refused the refresh is a visible failure; one that fell
        // asleep before its polls noticed refreshed nothing: the rows below wake it.
        // Deliberate: hostOnline is false for a host this client put to sleep, so it is woken.
        if (s.refreshFailed && s.hostOnline) {
            return Action.failing(ActionKind.FAIL, SessionFailure.UNCERTAIN_REFRESH_FAILED);
        }
        if (s.sessionRefreshed && s.session.state == SessionSnapshot.State.UNCERTAIN) {
            return Action.failing(ActionKind.FAIL, SessionFailure.UNCERTAIN_AFTER_REFRESH);
        }
        if (s.closeOutcome == null) return null;
        switch (s.closeOutcome) {
            // The user's Cancel (of the close or of the switch on the stream) wins: nothing more.
            case CANCELLED: return Action.of(ActionKind.IGNORE);
            case NEEDS_CONFIRMATION: return Action.of(ActionKind.CONFIRM_REPLACEMENT);
            case REUSED: return Action.of(ActionKind.RETURN_TO_STREAM);
            case PRESENTATION_REQUIRED: return Action.of(ActionKind.ATTACH_PRESENTATION);
            default: break;
        }
        // The verified close is authoritative. UNCERTAIN, or TERMINATING while the closing
        // confirmation window is open, only means that no host observation newer than the close
        // has been accepted yet; every local fact already shows nothing left to close.
        SessionSnapshot.State after = s.session.state;
        if (after == SessionSnapshot.State.NONE || after == SessionSnapshot.State.UNCERTAIN
                || after == SessionSnapshot.State.TERMINATING) {
            return Action.launch(ActionKind.LAUNCH, freshType(intent), "",
                    s.session.hostGameAppId == 0);
        }
        return intent.matches(s.session) ? null
                : Action.failing(ActionKind.FAIL, SessionFailure.CLOSE_SNAPSHOT_NOT_IDLE);
    }

    private static Action playOnHost(State s, PlayIntent intent) {
        SessionSnapshot snapshot = s.session;
        boolean game = intent.kind == PlayIntent.Kind.PLAYNITE_GAME;
        LaunchTransitionType fresh = freshType(intent);
        // A game stop beside a live stream keeps only the way back to that stream open.
        if (snapshot.state == SessionSnapshot.State.TERMINATING
                || (snapshot.endingGame && !intent.matches(snapshot))) {
            // Every Play (game, app, Desktop) waits for the host to confirm the close.
            return Action.of(ActionKind.QUEUE_BEHIND_CLOSE);
        }
        // A game whose exact process the host proves is stopped by that proof alone: an episode
        // that expired only on its window (a windowed game, TV 2026-10-14) never blocks a switch.
        if (snapshot.observationState == SessionSnapshot.ObservationState.RECOVERY_REQUIRED
                && !intent.matches(snapshot)
                && snapshot.verifiedRunningGameId().isEmpty()) {
            // Only correlated evidence naming another game refuses, and only under the loading
            // layer. An uncorrelated observation (the hole between two host polls) decides
            // nothing: it is refreshed and the request is evaluated on the fresh one, which
            // refuses when it is the very pass that refresh produced.
            boolean namesOtherGame = snapshot.observationCorrelated
                    && !snapshot.playniteGameId.isEmpty();
            if (namesOtherGame || !snapshot.observationCorrelated) {
                if (!s.opaqueReady) {
                    return Action.step(ActionKind.SHOW_LOADING, fresh, null,
                            s.replacementAuthorized, s.reuseOnly);
                }
                if (namesOtherGame || s.observationRefreshed) {
                    return Action.failing(ActionKind.FAIL,
                            SessionFailure.UNCERTAIN_RECOVERY_REQUIRED);
                }
                return Action.step(ActionKind.REFRESH_OBSERVATION, null, null,
                        s.replacementAuthorized, s.reuseOnly);
            }
        }
        if (snapshot.state == SessionSnapshot.State.PREPARING && game) {
            if (!s.opaqueReady) {
                return Action.step(ActionKind.SHOW_LOADING, LaunchTransitionType.GAME, null,
                        s.replacementAuthorized, s.reuseOnly);
            }
            // The warm-up stream is still starting on the host: a switch now would run beside
            // that start and be refused. One operation at a time: the pass after it settles
            // decides (a live warm-up is switched, a failed one launched fresh).
            return Action.of(ActionKind.AWAIT_PREPARED);
        }
        if (snapshot.state == SessionSnapshot.State.UNCERTAIN) {
            if (game) {
                return Action.step(s.opaqueReady ? ActionKind.REFRESH_SESSION
                                : ActionKind.SHOW_LOADING, fresh, null,
                        s.replacementAuthorized, s.reuseOnly);
            }
            if (!s.opaqueReady) {
                return Action.step(ActionKind.SHOW_LOADING, fresh, null, false, false);
            }
            if (!s.targetPrepared) {
                return Action.step(ActionKind.PREFLIGHT, null,
                        HostLaunchPreflight.Action.LAUNCH, false, false);
            }
            return Action.launch(ActionKind.LAUNCH, fresh, "", true);
        }

        // The live stream carries no game and the host names one without its process proof: the
        // name may be stale, so it is neither switched to, returned to nor replaced (a close of
        // it would end whatever runs now). Refreshed once; still unproven, the request fails.
        if (game && s.retainedGameUnproven) {
            if (!s.opaqueReady) {
                return Action.step(ActionKind.SHOW_LOADING, fresh, null,
                        s.replacementAuthorized, s.reuseOnly);
            }
            if (!s.observationRefreshed) {
                return Action.step(ActionKind.REFRESH_OBSERVATION, null, null,
                        s.replacementAuthorized, s.reuseOnly);
            }
            return Action.failing(ActionKind.FAIL, SessionFailure.UNCERTAIN_AFTER_REFRESH);
        }
        boolean matches = intent.matches(snapshot) && !s.canSwitchRetained;
        if (snapshot.state == SessionSnapshot.State.ACTIVE && matches) {
            return snapshot.retainedTransport ? Action.of(ActionKind.RETURN_TO_STREAM)
                    : connect(s);
        }
        boolean reconnectingWarmUp = snapshot.state == SessionSnapshot.State.RECONNECT_REQUIRED
                && snapshot.neutralStreamTarget
                && snapshot.playniteGameId.isEmpty()
                && game;
        boolean carriesNoGame = reconnectingWarmUp && !matches && !s.warmUpCarriesGame;
        // Orphaned neutral stream: none to carry and the host proves no game runs (IDLE).
        // Decided on every pass from this snapshot and never carried forward; it is replaced
        // without asking. An empty game id from a stale or unresolved observation proves
        // nothing: it is refreshed once, then the replacement is confirmed.
        boolean orphaned = carriesNoGame
                && snapshot.observationState == SessionSnapshot.ObservationState.IDLE;
        if (carriesNoGame && !orphaned && !s.replacementAuthorized) {
            if (!s.opaqueReady) {
                return Action.step(ActionKind.SHOW_LOADING, fresh, null, false, s.reuseOnly);
            }
            if (!s.observationRefreshed) {
                return Action.step(ActionKind.REFRESH_OBSERVATION, null, null,
                        false, s.reuseOnly);
            }
        }
        if (!carriesNoGame && snapshot.state == SessionSnapshot.State.RECONNECT_REQUIRED
                && (matches || reconnectingWarmUp)) {
            if (!s.opaqueReady) {
                return Action.step(ActionKind.SHOW_LOADING, LaunchTransitionType.GENERIC, null,
                        s.replacementAuthorized, s.reuseOnly);
            }
            if (!s.targetPrepared) {
                return Action.step(ActionKind.PREFLIGHT, null,
                        HostLaunchPreflight.Action.RECONNECT,
                        s.replacementAuthorized, s.reuseOnly);
            }
            return Action.of(ActionKind.RECONNECT);
        }
        if (snapshot.state == SessionSnapshot.State.SUSPENDED && matches) return connect(s);
        if (snapshot.state == SessionSnapshot.State.NONE && !s.canSwitchRetained) {
            if (!s.opaqueReady) {
                return Action.step(ActionKind.SHOW_LOADING, fresh, null,
                        s.replacementAuthorized, s.reuseOnly);
            }
            if (!s.targetPrepared) {
                return Action.step(ActionKind.PREFLIGHT, null,
                        HostLaunchPreflight.Action.LAUNCH,
                        s.replacementAuthorized, s.reuseOnly);
            }
            // Read in this pass, right before the launch: an idle host's new session is ours.
            return Action.launch(ActionKind.LAUNCH, fresh, "", snapshot.hostGameAppId == 0);
        }
        boolean authorized = s.replacementAuthorized || orphaned;
        boolean reuseOnly = s.reuseOnly || !authorized && s.canSwitchRetained;
        // Any actor, a child included (owner 2026-10-14), replaces what the host runs: on the
        // live stream by a switch without asking, otherwise once the user confirmed. A child
        // starts only its own entitled game (host gates).
        if (!authorized && !reuseOnly) return Action.of(ActionKind.CONFIRM_REPLACEMENT);
        if (!s.opaqueReady) {
            return Action.step(ActionKind.SHOW_LOADING, fresh, null,
                    s.replacementAuthorized, reuseOnly);
        }
        if (!s.targetPrepared) {
            return Action.step(ActionKind.PREFLIGHT, null,
                    reuseOnly ? HostLaunchPreflight.Action.SWITCH_RETAINED
                            : HostLaunchPreflight.Action.LAUNCH,
                    s.replacementAuthorized, reuseOnly);
        }
        return Action.step(ActionKind.CLOSE_COMPETING, null, null, authorized, reuseOnly);
    }

    /** Connects a new transport to the session the host already runs. */
    private static Action connect(State s) {
        if (!s.opaqueReady) {
            return Action.step(ActionKind.SHOW_LOADING, LaunchTransitionType.GENERIC, null,
                    false, false);
        }
        if (!s.targetPrepared) {
            return Action.step(ActionKind.PREFLIGHT, null,
                    HostLaunchPreflight.Action.LAUNCH, false, false);
        }
        return Action.launch(ActionKind.LAUNCH, LaunchTransitionType.GENERIC,
                s.session.explicitSuspension ? s.session.suspendId : "", false);
    }

    private static Action back(State s) {
        // Back on the way to the Home, or under a user cover released over the revealed stream,
        // is a Home return, never a Cancel of that cover: the kept stream is never stopped.
        if (s.homeReturnCovered || s.userCoverReleased) {
            return Action.of(ActionKind.COVER_AND_SHOW_HOME);
        }
        // A stopping game's closing cover offers nothing to cancel: Back is the Home.
        if (s.gameStopping && s.connected && !s.homeVisible) {
            return Action.of(ActionKind.COVER_AND_SHOW_HOME);
        }
        // A revealed stream whose readiness timed out is live, not a launch to cancel.
        if (s.connected && !s.homeVisible && s.revealed && s.timedOut) {
            return Action.of(ActionKind.COVER_AND_SHOW_HOME);
        }
        if (s.covered) return Action.of(ActionKind.CANCEL_ON_COVER);
        if (retainedLiveStream(s)) return Action.of(ActionKind.COVER_AND_SHOW_HOME);
        return Action.of(s.connected && !s.homeVisible
                ? ActionKind.SHOW_HOME : ActionKind.LEAVE_SCREEN);
    }

    /**
     * The pad's Home and the overlay's Home: always the console Home with the launch or stream
     * kept running, never a Cancel. Where Back is the cover's Cancel, Home covers and leaves; a
     * switch's own cover keeps the screen (Back cancels that switch), and without a transport
     * there is no Home to show.
     */
    private static Action home(State s) {
        Action back = back(s);
        if (back.kind != ActionKind.CANCEL_ON_COVER) return back;
        if (!s.connected || s.homeVisible || s.retainedSwitch) return Action.of(ActionKind.IGNORE);
        return Action.of(ActionKind.COVER_AND_SHOW_HOME);
    }

    private static Action cancel(State s) {
        if (!s.transitionActive) return Action.of(ActionKind.IGNORE);
        // No Cancel under a Home return's or a released user cover: the stream stays.
        if (s.homeReturnCovered || s.userCoverReleased) return Action.of(ActionKind.IGNORE);
        if (s.authorizationRetryOffered) {
            return Action.of(ActionKind.DECLINE_AUTHORIZATION_RETRY);
        }
        // The observation kept after a failed start is that start: abandoning it closes.
        if (s.failedStartObservation) return Action.of(ActionKind.CLOSE_HOST_SESSION);
        // Cancel on a switch's cover cancels that switch, whatever stream it started from.
        if (s.retainedSwitch) return Action.of(ActionKind.CANCEL_RETAINED_SWITCH);
        if (retainedLiveStream(s)) return Action.of(ActionKind.COVER_AND_SHOW_HOME);
        boolean startsGame = s.type == LaunchTransitionType.GAME && s.hasGame;
        // A start the host never confirmed ready closes its session, even manually revealed.
        if (startsGame && (!s.revealed || !s.gameReadyConfirmed) && s.controlGranted) {
            return Action.of(ActionKind.CLOSE_STARTED_SESSION);
        }
        // A cancelled reconnect to a running game never ends that game or its host stream.
        if (s.type == LaunchTransitionType.GAME_CONNECTION && s.hasGame) {
            return Action.of(ActionKind.STOP_TRANSPORT_KEEP_SESSION);
        }
        // A kept stream settling back to the dashboard was used before: its host stream stays
        // paused under a granted generation, so its record stays for the next start to replace.
        if (s.returningToDashboard) return Action.of(ActionKind.STOP_TRANSPORT_KEEP_SESSION);
        // Nothing was shown: a host stream left paused would only refuse the next launch. A
        // generation granted after this cancel is closed by the stopped transport.
        if (!s.revealed) return Action.of(ActionKind.STOP_TRANSPORT_CLOSE_HOST_STREAM);
        return Action.of(ActionKind.STOP_TRANSPORT);
    }

    private static Action retry(State s) {
        if (!s.transitionActive) return Action.of(ActionKind.IGNORE);
        // A transition whose retry already runs has nothing more to retry.
        if (s.retryStarted) return Action.of(ActionKind.IGNORE);
        // Retry lives on the cover. A stream the user revealed is never covered again by it.
        if (!s.covered || s.manuallyRevealed) return Action.of(ActionKind.IGNORE);
        if (!s.connected) return Action.of(ActionKind.RESTART_STREAM);
        // From here the transport is alive: every retry stays on this stream.
        if (s.authorizationRetryOffered) {
            return Action.of(ActionKind.ACCEPT_AUTHORIZATION_RETRY);
        }
        if (s.retainedSwitch) {
            // Before its new attempt the switch is still settling and decides by itself.
            return Action.of(s.retainedSwitchAttempted
                    ? ActionKind.RETRY_RETAINED_SWITCH : ActionKind.IGNORE);
        }
        // Only a start the host reported failed is started again. A game that may run (ready
        // once, or a readiness that merely timed out) is never stopped: it is observed again.
        boolean failedStart = s.type == LaunchTransitionType.GAME && s.hasGame
                && !s.gameReadyConfirmed && s.failedStartCleanup;
        return Action.of(failedStart
                ? ActionKind.RETRY_GAME_START : ActionKind.RETRY_OBSERVATION);
    }

    /**
     * A live retained stream in the foreground whose transition starts nothing: observing its
     * game, or the kept neutral stream shown again (SHOW_NEUTRAL_STREAM). Nothing to cancel:
     * Back and Cancel return to the Home and keep it; only Close session ends it.
     */
    private static boolean retainedLiveStream(State s) {
        boolean startsNothing = s.type == LaunchTransitionType.GAME_CONNECTION && s.hasGame
                || s.type == LaunchTransitionType.GENERIC && !s.hasGame;
        return s.connected && !s.homeVisible && !s.parked && startsNothing
                && s.retainedHomeLive;
    }

    // ---------------------------------------------------------------- qualification rows
    // Plain functions of facts the effects read; the effects never qualify anything.

    /**
     * The retained stream can take this game without being closed: the same host and execution
     * profile, its neutral stream app, an origin the session model lets switch, and a game the
     * switch keeps ({@link #switchKeepsHostGame}). A start still PREPARING is switched only once
     * the host proves the game it observes.
     */
    static boolean canSwitchRetained(PlayIntent intent, Retained r) {
        if (intent.kind != PlayIntent.Kind.PLAYNITE_GAME) return false;
        SessionModel.Snapshot s = r.stream;
        if (!s.executionProfileId.equals(r.executionProfileId)) return false;
        boolean eligibleTarget = r.neutralTarget && r.originSwitchable;
        if (s.state == SessionModel.State.PREPARING) {
            if (r.projection == null || !s.hostId.equalsIgnoreCase(intent.hostId)) return false;
            if (s.playniteGameId.isEmpty()) return eligibleTarget;
            return "VERIFIED".equals(r.projection.processEvidence)
                    && r.projection.playniteGameId.equalsIgnoreCase(s.playniteGameId)
                    && eligibleTarget;
        }
        return (s.state == SessionModel.State.HOME_LIVE
                || s.state == SessionModel.State.PARKED_LIVE)
                && r.projection != null
                && r.projection.streamerAppId == s.appId
                && switchKeepsHostGame(s.playniteGameId, r.projection, intent.playniteGameId)
                && s.hostId.equalsIgnoreCase(intent.hostId)
                && (!s.profileId.equals(intent.profileId)
                        || !s.playniteGameId.equalsIgnoreCase(intent.playniteGameId))
                && eligibleTarget;
    }

    /**
     * A stream that carries no game holds no process of its own: it takes a game only while the
     * host names none or verifiably runs exactly that one (the proof the stream's own adoption
     * needs). Another running game is a replacement the user confirms, never something a switch
     * stops; a named game without that proof is refreshed first ({@link #namesUnprovenGame}).
     */
    static boolean switchKeepsHostGame(String retainedGameId, SessionSnapshot projection,
                                       String gameId) {
        return !retainedGameId.isEmpty() || projection.playniteGameId.isEmpty()
                || projection.verifiedRunningGameId().equals(SessionSnapshot.normalize(gameId));
    }

    /** The live stream of this intent carries no game and the host names one without proof. */
    static boolean retainedGameUnproven(PlayIntent intent, Retained r) {
        SessionModel.Snapshot s = r.stream;
        return r.projection != null && intent.kind == PlayIntent.Kind.PLAYNITE_GAME
                && (s.state == SessionModel.State.HOME_LIVE
                || s.state == SessionModel.State.PARKED_LIVE)
                && s.hostId.equalsIgnoreCase(intent.hostId)
                && s.executionProfileId.equals(r.executionProfileId)
                && namesUnprovenGame(s.playniteGameId, r.projection);
    }

    /** A stream carrying no game while the host names one without an exact process proof. */
    static boolean namesUnprovenGame(String retainedGameId, SessionSnapshot projection) {
        return retainedGameId.isEmpty() && !projection.playniteGameId.isEmpty()
                && projection.verifiedRunningGameId().isEmpty();
    }

    /**
     * How the competing session gives way, read at effect time. Any actor, a child over its
     * parent's game included, switches on the live stream without asking (owner 2026-10-14):
     * the host gates the child's start. Everything else needs the user's confirmation; this
     * client's exact retained stream (the app the host runs) closes through its owner.
     */
    static ClosePath closePath(PlayIntent intent, NvApp target, boolean authorized, Retained r) {
        // Fails before asking: a confirmation of an unknown host's close could not be acted on.
        if (r.projection == null) return ClosePath.HOST_UNAVAILABLE;
        SessionModel.Snapshot s = r.stream;
        if (intent.kind == PlayIntent.Kind.PLAYNITE_GAME
                && PlayniteTargetResolver.isNeutralStream(target)
                && s.appId == target.getAppId() && canSwitchRetained(intent, r)) {
            // No Activity presents the retained transport: the switch must run inside an
            // attaching stream screen, never against a headless runtime.
            return r.presentationAttached ? ClosePath.SWITCH : ClosePath.ATTACH;
        }
        if (!authorized) return ClosePath.CONFIRM;
        boolean exactRetained = !s.streamSessionId.isEmpty()
                && s.hostId.equalsIgnoreCase(intent.hostId)
                && s.executionProfileId.equals(r.executionProfileId)
                && r.projection != null && s.appId == r.projection.streamerAppId;
        return exactRetained ? ClosePath.CLOSE_RETAINED : ClosePath.CLOSE_DIRECT;
    }

    /** A switch the stream refused as ineligible asks before anything is closed. */
    static boolean switchRefusalAsks(SessionFailure reason, boolean authorized) {
        return !authorized && (reason == SessionFailure.SWITCH_NOT_ELIGIBLE
                || reason == SessionFailure.SWITCH_REFUSED);
    }

    /**
     * A Play queued behind a close waits while the session still reads as closing (until the
     * host confirms it free, or the bound passes), is dropped when that close failed, and
     * otherwise starts as a normal Play: the table decides it from the fresh state.
     */
    static QueueStep queuedStep(SessionSnapshot s) {
        if (s.state == SessionSnapshot.State.TERMINATING || s.endingGame) return QueueStep.WAIT;
        return s.closeFailed ? QueueStep.DROP_CLOSE_FAILED : QueueStep.START;
    }

    /** A warm-up starts only while no other host, profile or close holds this client's stream. */
    static WarmUpStart warmUpStart(PlayIntent intent, Retained r) {
        SessionModel.Snapshot s = r.stream;
        boolean sameScope = s.hostId.equalsIgnoreCase(intent.hostId)
                && s.executionProfileId.equals(r.executionProfileId);
        if (s.state != SessionModel.State.NONE && !sameScope
                || s.state == SessionModel.State.TERMINATING) {
            return WarmUpStart.REFUSED;
        }
        if (s.state == SessionModel.State.PREPARING) return WarmUpStart.PREPARING;
        if (s.state == SessionModel.State.HOME_LIVE
                || s.state == SessionModel.State.PARKED_LIVE) {
            return WarmUpStart.LIVE;
        }
        return WarmUpStart.START;
    }

    /** A warm-up never runs beside another app the host runs. */
    static boolean warmUpQualifies(PreparedWarmUp prepared) {
        return !anotherAppRuns(prepared.readyProjection, prepared.target.getAppId());
    }

    /**
     * A prepared warm-up resumes this client's saved neutral stream of the same host, execution
     * profile and app: the stream the session model still holds as RECONNECT_REQUIRED under the
     * record's stream id, or a record saved for auto-resume while the model holds none. Anything
     * else starts a new stream.
     */
    static WarmUpLaunch warmUpLaunch(PreparedWarmUp prepared, SessionModel.Snapshot s,
                                     String executionProfileId,
                                     SessionResumeManager.PendingSession pending) {
        boolean exactPending = pending != null && pending.neutralStreamTarget
                && pending.hostUuid.equalsIgnoreCase(prepared.profileKey.hostId)
                && pending.executionProfileId.equals(executionProfileId)
                && pending.appId == prepared.target.getAppId();
        if (!exactPending) return WarmUpLaunch.LAUNCH;
        boolean modelReconnect = s.state == SessionModel.State.RECONNECT_REQUIRED
                && s.hostId.equalsIgnoreCase(pending.hostUuid)
                && s.executionProfileId.equals(pending.executionProfileId)
                && s.appId == pending.appId
                && s.streamSessionId.equals(pending.streamSessionId);
        boolean recordOnly = pending.autoResume && s.state == SessionModel.State.NONE;
        return modelReconnect || recordOnly ? WarmUpLaunch.RECONNECT : WarmUpLaunch.LAUNCH;
    }

    /**
     * The transition a prepared warm-up opens with, from the projection read at launch: a game
     * the host verifiably runs is connected to; anything else is a generic stream.
     */
    static LaunchTransitionSpec warmUpTransition(PreparedWarmUp prepared,
                                                 SessionSnapshot projection, long nowMillis) {
        String runningGame = projection.verifiedRunningGameId();
        return LaunchTransitionSpec.create(prepared.host.uuid, prepared.profileKey.profileId,
                runningGame.isEmpty() ? LaunchTransitionType.GENERIC
                        : LaunchTransitionType.GAME_CONNECTION,
                prepared.target.getAppId(), runningGame, nowMillis);
    }

    /**
     * The projection a pre-launch decision reads: the streamer's app counts only from the
     * observation this decision just had accepted ({@code observedNow}); a failed or rejected
     * read leaves it unknown, never the projection's older held value nor the serverinfo poll.
     */
    static SessionSnapshot preLaunchProjection(SessionSnapshot projection, boolean observedNow) {
        return observedNow ? projection : projection.withStreamerUnknown();
    }

    /** The host's streamer is known to run an app other than {@code appId}; unknown is not. */
    static boolean anotherAppRuns(SessionSnapshot projection, int appId) {
        return projection.streamerAppId > 0 && projection.streamerAppId != appId;
    }

    /**
     * A stream started while the host is known to run no app owns the fresh host session; an
     * unknown streamer proves nothing, so that stream owns nothing it could later close.
     */
    static boolean ownsFreshSession(SessionSnapshot projection) {
        return projection.streamerAppId == 0;
    }

    // ---------------------------------------------------------------- stream-screen rows
    // What the stream screen (Game) does with its session at a decision point; it only executes.

    /** The host's answer when it has not yet confirmed the new game's actor authorization. */
    public static final String RETAINED_AUTHORIZATION_UNCONFIRMED =
            "retained_authorization_unconfirmed";

    /** Only a new game after a stopped one retries its authorization, and only once. */
    public static boolean retriesRetainedAuthorization(boolean reuseRunningGame, int attempt) {
        return !reuseRunningGame && attempt == 0;
    }

    /**
     * The previous game is stopped and the host still refused the new game's authorization:
     * the stream stays open and the user decides (Retry starts the chosen game again).
     */
    public static boolean offersRetainedAuthorizationRetry(boolean failClosed,
                                                           boolean reuseRunningGame,
                                                           String error) {
        return !failClosed && !reuseRunningGame
                && RETAINED_AUTHORIZATION_UNCONFIRMED.equals(error);
    }

    /** A launch that starts a game and was never revealed owes its cleanup when it fails. */
    public static boolean shouldCleanupUnrevealedProviderLaunch(boolean startsProviderGame,
                                                                boolean streamEverRevealed) {
        return startsProviderGame && !streamEverRevealed;
    }

    /**
     * A failed fresh neutral start closes the host session it began. A start the host refused
     * ({@code hostRefusedStart}) began none: closing would act without a control generation,
     * be rejected and report a session left on the host that this attempt never started.
     */
    public static boolean shouldTerminateFreshOwnedSunshineSession(
            boolean ownsFreshSession, boolean neutralTarget,
            LaunchTransitionType type, boolean startsProviderGame,
            boolean streamEverRevealed, boolean retainedSession, boolean hostRefusedStart) {
        return ownsFreshSession && neutralTarget && type == LaunchTransitionType.GAME
                && startsProviderGame && !streamEverRevealed && !retainedSession
                && !hostRefusedStart;
    }

    /** Only a parent may end the stale earlier launch's game; a child reveals or cancels. */
    public static boolean offersPreviousGameStop(boolean childSession) {
        return !childSession;
    }

    /**
     * A recreated stream screen takes the hidden warm-up over: its exact PREPARING start once
     * the session model accepted its Home frame, otherwise the warm-up stream it still owns.
     */
    public static boolean shouldResumeAcceptedWarmUp(boolean exactPreparing,
                                                     boolean frameAccepted,
                                                     boolean retainedWarmUp) {
        return exactPreparing ? frameAccepted : retainedWarmUp;
    }

    /**
     * A console that still waits shows its own Home on a Cancel and its own failure screen
     * otherwise; every other settled switch (the console no longer waits, the user cancelled on
     * this stream before REUSED, or the stream submitted it) shows the stream's Home instead of
     * leaving the neutral stream's closing cover.
     */
    public static boolean switchSettlesOnHome(boolean cancelled, boolean consoleSignalled,
                                              boolean streamSubmitted,
                                              boolean requesterCancelled) {
        return consoleSignalled || (cancelled || streamSubmitted) && !requesterCancelled;
    }

    /**
     * A retained switch is cancelled by the console's Cancel while the console still waits, or
     * by the stream's own Cancel unless a Retry replaced it.
     */
    public static boolean retainedSwitchCancelled(boolean consoleSignalled,
                                                  boolean consoleCancelled,
                                                  boolean cancelRequested,
                                                  boolean retryRequested) {
        return (!consoleSignalled && consoleCancelled) || (cancelRequested && !retryRequested);
    }

    // ---------------------------------------------------------------- sequencing
    // The order of host-facing steps an effect runs; the steps themselves stay the effect's.

    /** The host-facing steps of stopping the game a switch replaces. */
    public interface RetainedStop {
        /** Null when the child may play now; otherwise the host's refusal reason. */
        String childRefusal() throws IOException;
        boolean cancelled();
        void stop() throws IOException;
    }

    /**
     * A child replacing a game asks the host's read-only eligibility first: a child without
     * time or outside its schedule never stops that game for nothing. The host's child start
     * after the stop stays the real gate. False when a Cancel came before anything stopped.
     */
    public static boolean stopRetainedGame(boolean targetIsChild, RetainedStop steps)
            throws IOException {
        if (targetIsChild) {
            String refusal = steps.childRefusal();
            if (refusal != null) throw new IOException(refusal);
        }
        if (steps.cancelled()) return false;
        steps.stop();
        return true;
    }

    // ---------------------------------------------------------------- executing Play

    /**
     * One pass: the facts at this moment, the table's action, its effect; the effect's result
     * comes back as a fact of the next pass. The table has no cycle of its own, but facts can
     * keep flapping (a host that falls asleep again between its wake and its refresh feeds
     * wake -> refresh -> wake); the pass limit bounds that. Only steps taken with next() or
     * confirmed() count; the limit sits above the longest chain a host that answers needs
     * (loading, warm-up wait, refreshes, wake, preflight, a refused switch then confirmed,
     * close), so only flapping facts reach it.
     */
    private void evaluate(long request, PlayIntent intent, Progress p) {
        if (!current(request)) return;
        if (p.pass > 10) {
            fail(request, intent, SessionFailure.ORCHESTRATION_PASS_LIMIT, "");
            return;
        }
        SessionSnapshot snapshot = effects.resolve(intent.profileKey);
        if (!current(request)) return;
        Retained retained = effects.retained(intent);
        State state = State.forPlay(snapshot, canSwitchRetained(intent, retained),
                effects.warmUpCarriesGame(), p.replacementAuthorized, p.reuseOnly,
                p.opaqueReady, p.target != null, p.refresh == Refresh.OBSERVATION,
                effects.isHostOnline(intent.hostId), effects.isPaired(intent.hostId));
        state.retainedGameUnproven = retainedGameUnproven(intent, retained);
        state.sessionRefreshed = p.refresh == Refresh.OBSERVATION
                || p.refresh == Refresh.SESSION;
        state.refreshFailed = p.refresh == Refresh.FAILED;
        state.closeOutcome = p.closed;
        Action action = transition(state, UserIntent.play(intent));
        switch (action.kind) {
            // A refusal or a failure keeps the queued Play; a queued one replaces it in queue().
            case REFUSE_UNPAIRED: case QUEUE_BEHIND_CLOSE: case FAIL:
            case IGNORE:
                break;
            default:
                supersedeQueued(request, intent);
        }
        switch (action.kind) {
            case REFUSE_UNPAIRED:
                diagnostic("WARN", "orchestration.rejected", request, intent,
                        "reason", Rejection.UNPAIRED.name());
                inFlight = 0L;
                effects.reject(Rejection.UNPAIRED);
                break;
            case QUEUE_BEHIND_CLOSE:
                inFlight = 0L;
                queue(request, intent);
                break;
            case FAIL:
                fail(request, intent, action.failure, "");
                break;
            case SHOW_LOADING:
                effects.showLoading(intent, action.type, () ->
                        evaluate(request, intent, p.next(action)));
                break;
            case REFRESH_OBSERVATION:
                refresh(request, intent, p.next(action), Refresh.OBSERVATION);
                break;
            case REFRESH_SESSION:
                refresh(request, intent, p.next(action), Refresh.SESSION);
                break;
            case AWAIT_PREPARED:
                effects.awaitPrepared(() -> !current(request), () -> dispatcher.post(() ->
                        evaluate(request, intent, p.next(p.replacementAuthorized, p.reuseOnly))));
                break;
            case WAKE_HOST:
                awaitHost(request, intent, p.next(action));
                break;
            case PREFLIGHT:
                awaitPreflight(request, intent, p.next(action), action.preflight);
                break;
            case CLOSE_COMPETING:
                closeCompeting(request, intent, p.next(action));
                break;
            case LAUNCH:
                launch(request, intent, p.target, action.type, action.sourceSuspendId,
                        action.ownsFreshSunshineSession);
                break;
            case RETURN_TO_STREAM:
                returnToRetained(request, intent);
                break;
            case RECONNECT:
                reconnect(request, intent);
                break;
            case CONFIRM_REPLACEMENT:
                effects.confirmReplacement(() -> evaluate(request, intent, p.confirmed()));
                break;
            case ATTACH_PRESENTATION:
                diagnostic("INFO", "orchestration.presentation_required", request, intent);
                inFlight = 0L;
                effects.attachPresentationForSwitch(request, intent, p.target);
                break;
            case IGNORE:
                break;
            default:
                throw new IllegalStateException("Not a Play action: " + action.kind);
        }
    }

    private void awaitPreflight(long request, PlayIntent intent, Progress step,
                                HostLaunchPreflight.Action action) {
        execute(request, intent, () -> {
            HostLaunchPreflight.Result result = effects.preflight(
                    intent, action, request, () -> !current(request));
            diagnostic(result.status == HostLaunchPreflight.Status.FAILED ? "WARN" : "INFO",
                    "orchestration.preflight_completed", request, intent,
                    "status", result.status.name(), "operation", action.name(),
                    "stage", result.failure == null ? "" : result.failure.stage.name(),
                    "reason", result.failure == null ? result.status.name()
                            : result.failure.reason.name());
            dispatcher.post(() -> {
                if (!current(request)) return;
                if (result.status == HostLaunchPreflight.Status.FAILED) {
                    inFlight = 0L;
                    effects.preflightFailed(result.failure);
                }
                else if (result.status == HostLaunchPreflight.Status.READY)
                    evaluate(request, intent, step.prepared(result.target));
            });
        });
    }

    private void awaitHost(long request, PlayIntent intent, Progress step) {
        diagnostic("INFO", "orchestration.wake_started", request, intent);
        execute(request, intent, () -> {
            SessionFailure unanswered = effects.awaitHost(intent, () -> !current(request));
            dispatcher.post(() -> {
                // A cancelled wake closes nothing: it only stops waiting.
                if (!current(request)) return;
                if (unanswered != null) {
                    fail(request, intent, unanswered, "");
                    return;
                }
                evaluate(request, intent, step);
            });
        });
    }

    private void refresh(long request, PlayIntent intent, Progress step, Refresh kind) {
        effects.refreshSession(intent, () -> !current(request), success ->
                dispatcher.post(() -> evaluate(request, intent,
                        step.refreshed(success ? kind : Refresh.FAILED))));
    }

    private void closeCompeting(long request, PlayIntent intent, Progress step) {
        execute(request, intent, () -> {
            CloseResult closeResult;
            SessionFailure.Raised closeFailure = null;
            diagnostic("INFO", "orchestration.close_started", request, intent);
            try { closeResult = close(intent, step, () -> !current(request)); }
            catch (Exception error) {
                closeResult = null;
                closeFailure = error instanceof SessionFailure.Raised
                        ? (SessionFailure.Raised) error
                        : SessionFailure.CLOSE_UNEXPECTED_ERROR.raise();
                diagnostic("ERROR", "orchestration.close_failed", request, intent,
                        "error_type", error.getClass().getName());
            }
            if (closeResult != null) diagnostic("INFO",
                    "orchestration.close_completed", request, intent,
                    "status", closeResult.name());
            CloseResult result = closeResult;
            SessionFailure.Raised failure = closeFailure;
            dispatcher.post(() -> {
                if (!current(request)) return;
                if (result == null) fail(request, intent, failure.reason, failure.detail);
                else evaluate(request, intent, step.closed(result));
            });
        });
    }

    /** Runs on the worker: the table picks the path from facts read now, the effects run it. */
    private CloseResult close(PlayIntent intent, Progress step, BooleanSupplier cancelled)
            throws Exception {
        Retained retained = effects.retained(intent);
        switch (closePath(intent, step.target, step.replacementAuthorized, retained)) {
            case ATTACH: return CloseResult.PRESENTATION_REQUIRED;
            case CONFIRM: return CloseResult.NEEDS_CONFIRMATION;
            case HOST_UNAVAILABLE: throw SessionFailure.CLOSE_HOST_UNAVAILABLE.raise();
            case SWITCH:
                try {
                    return effects.switchRetained(intent, step.target, cancelled);
                } catch (SessionFailure.Raised refused) {
                    if (switchRefusalAsks(refused.reason, step.replacementAuthorized)) {
                        return CloseResult.NEEDS_CONFIRMATION;
                    }
                    throw refused;
                }
            case CLOSE_RETAINED: return effects.closeSession(intent, retained.stream, cancelled);
            default: return effects.closeSession(intent, null, cancelled);
        }
    }

    private void execute(long request, PlayIntent intent, Runnable action) {
        try { executor.execute(() -> { if (current(request)) action.run(); }); }
        catch (RuntimeException error) {
            diagnostic("ERROR", "orchestration.execution_failed", request, intent,
                    "error_type", error.getClass().getName());
            if (current(request)) {
                fail(request, intent, SessionFailure.ORCHESTRATION_EXECUTOR_REJECTED, "");
            }
        }
    }

    /** Records the failure with the snapshot at this moment, then shows its screen. */
    private void fail(long request, PlayIntent intent, SessionFailure reason, String detail) {
        inFlight = 0L;
        reason.record(request, intent.hostId, intent.profileId,
                effects.resolve(intent.profileKey), detail);
        effects.sessionFailed(intent, reason);
    }

    private void returnToRetained(long request, PlayIntent intent) {
        diagnostic("INFO", "orchestration.return_retained", request, intent);
        inFlight = 0L;
        effects.returnToRetainedStream();
    }

    private void reconnect(long request, PlayIntent intent) {
        diagnostic("INFO", "orchestration.reconnect", request, intent);
        inFlight = 0L;
        effects.reconnectSavedSession(intent);
    }

    private void launch(long request, PlayIntent intent, NvApp target,
                        LaunchTransitionType type, String sourceSuspendId,
                        boolean ownsFreshSunshineSession) {
        diagnostic("INFO", "orchestration.launch", request, intent,
                "operation", type.name(), "app_id", target.getAppId());
        inFlight = 0L;
        effects.launch(intent, target, type, sourceSuspendId, ownsFreshSunshineSession);
    }

    private static void diagnostic(String level, String event, long request,
                                   PlayIntent intent, Object... fields) {
        if (intent == null) return;
        Object[] values = new Object[fields.length + 12];
        Object[] base = {"orchestration_id", request, "host_id", intent.hostId,
                "profile_id", intent.profileId, "game_id", intent.playniteGameId,
                "app_id", intent.sunshineAppId,
                "kind", intent.kind.name()};
        System.arraycopy(base, 0, values, 0, base.length);
        System.arraycopy(fields, 0, values, base.length, fields.length);
        MoonWakerDiagnostics.record(level, "android.session-orchestrator", event, values);
    }

    private boolean current(long request) { return !closed && orchestrationId.get() == request; }

    private static LaunchTransitionType freshType(PlayIntent intent) {
        switch (intent.kind) {
            case PLAYNITE_GAME: return LaunchTransitionType.GAME;
            case PLAYNITE_FULLSCREEN: return LaunchTransitionType.PLAYNITE;
            default: return LaunchTransitionType.GENERIC;
        }
    }
}
