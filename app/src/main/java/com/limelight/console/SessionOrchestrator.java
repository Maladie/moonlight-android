package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.session.SessionModel;

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
    enum Rejection { INITIALIZING, UNPAIRED, TERMINATING }
    enum CloseResult { CLOSED, REUSED, CANCELLED, NEEDS_CONFIRMATION, PRESENTATION_REQUIRED }

    static final class PreparedWarmUp {
        final HostProfileKey profileKey;
        final NvApp target;
        final String activeGameId;
        final ComputerDetails host;
        final boolean ownsFreshSunshineSession;

        PreparedWarmUp(NvApp target, String activeGameId, ComputerDetails host,
                       boolean ownsFreshSunshineSession) {
            this(target, activeGameId, host,
                    host == null ? null : new HostProfileKey(host.uuid,
                            com.limelight.gateway.GatewayConnection.DEFAULT_PROFILE_ID),
                    ownsFreshSunshineSession);
        }

        PreparedWarmUp(NvApp target, String activeGameId, ComputerDetails host,
                       HostProfileKey profileKey,
                       boolean ownsFreshSunshineSession) {
            this.target = target;
            this.activeGameId = SessionSnapshot.normalize(activeGameId);
            this.host = host;
            this.profileKey = profileKey;
            this.ownsFreshSunshineSession = ownsFreshSunshineSession;
        }
    }

    private static final class Preparation {
        final long request;
        final String hostId;
        final HostProfileKey profileKey;
        PlayIntent pendingGame;
        boolean opaqueReady;
        PreparedWarmUp prepared;
        boolean launchClaimed;

        Preparation(long request, HostProfileKey profileKey) {
            this.request = request;
            this.profileKey = profileKey;
            this.hostId = SessionSnapshot.normalize(profileKey.hostId);
        }
    }

    interface Dispatcher { void post(Runnable action); }
    interface Effects {
        boolean isAvailable(); SessionSnapshot resolve(String hostId);
        /**
         * The host's last poll reported it paired, or no poll has reported its pairing yet and
         * its pairing certificate is saved. A host that answers always reports its pairing.
         */
        boolean isPaired(String hostId);
        default SessionSnapshot resolve(HostProfileKey key) { return resolve(key.hostId); }
        /** The host answers its polls; only then can its session be refreshed. */
        boolean isHostOnline(String hostId);
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
        boolean canAttemptRetainedSwitch(PlayIntent intent);
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
        CloseResult closePreviousSession(PlayIntent intent, NvApp target,
                                         boolean allowDestructiveClose,
                                         BooleanSupplier cancelled) throws Exception;
        void launch(PlayIntent intent, NvApp target, LaunchTransitionType type,
                    String sourceSuspendId, boolean ownsFreshSunshineSession);
        void preflightFailed(HostLaunchPreflight.Failure failure);
        /** Shows the failure screen for this exact path; the orchestrator recorded it. */
        void sessionFailed(PlayIntent intent, SessionFailure reason);
        PreparedWarmUp prepareHost(PlayIntent intent, long request,
                                   BooleanSupplier cancelled);
        /**
         * False while opening the stream screen would stall what is on screen (an entry
         * intro); {@link #openPreparedHost} follows once it no longer would.
         */
        boolean mayOpenPreparedHost();
        void launchPreparedHost(PreparedWarmUp prepared,
                                PlayIntent pendingGame, long request);
        void preparationFailed();
    }

    private final Effects effects;
    private final Executor executor;
    private final Dispatcher dispatcher;
    private final AtomicLong generation = new AtomicLong();
    private PlayIntent lastIntent;
    /** The request still being decided; 0 once it was handed off, refused or failed. */
    private long inFlight;
    /** The request and pass evaluating the refresh of an unresolved observation. */
    private long refreshedRequest;
    private int refreshedPass;
    private volatile Preparation preparation;
    private volatile boolean closed;

    SessionOrchestrator(Effects effects, Executor executor, Dispatcher dispatcher) {
        this.effects = effects;
        this.executor = executor;
        this.dispatcher = dispatcher;
    }

    static boolean shouldSendWarmUpWake(
            SessionModel.State localState,
            boolean bridgeResponded, ComputerDetails.State moonlightState) {
        return localState != SessionModel.State.HOME_LIVE
                && localState != SessionModel.State.PARKED_LIVE
                && localState != SessionModel.State.PREPARING
                && localState != SessionModel.State.TERMINATING
                && !bridgeResponded
                && moonlightState == ComputerDetails.State.OFFLINE;
    }

    void play(PlayIntent intent) {
        if (handoffPreparingGame(intent)) return;
        lastIntent = intent;
        long request = generation.incrementAndGet();
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
        evaluate(request, intent, false, false, false, null, 0);
    }

    long prepareHost(String hostId) {
        return prepareHost(hostId,
                com.limelight.gateway.GatewayConnection.DEFAULT_PROFILE_ID);
    }

    long prepareHost(String hostId, String profileId) {
        PlayIntent intent = PlayIntent.autoWarmUp(hostId, profileId);
        long request = generation.incrementAndGet();
        preparation = null;
        if (closed) return 0L;
        if (!effects.isAvailable()) {
            return 0L;
        }
        if (!effects.isPaired(intent.hostId)) {
            return 0L;
        }
        Preparation pending = new Preparation(request, intent.profileKey);
        preparation = pending;
        try {
            executor.execute(() -> {
                PreparedWarmUp prepared;
                try {
                    prepared = effects.prepareHost(
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

    boolean cancelPreparation(String hostId) {
        Preparation pending = preparation;
        if (!currentPreparation(pending) || !pending.hostId.equals(
                SessionSnapshot.normalize(hostId))) return false;
        preparation = null;
        generation.compareAndSet(pending.request, pending.request + 1L);
        return true;
    }

    boolean hasPreparationForHost(String hostId) {
        Preparation pending = preparation;
        return currentPreparation(pending) && pending.hostId.equals(
                SessionSnapshot.normalize(hostId));
    }

    void cancel() {
        long cancelled = generation.getAndIncrement();
        preparation = null;
        // Only a request still being decided is cancelled; one already launched is not.
        if (!closed && inFlight == cancelled && lastIntent != null) {
            diagnostic("INFO", "orchestration.cancelled", cancelled, lastIntent);
        }
        inFlight = 0L;
    }
    void retry() {
        PlayIntent intent = lastIntent;
        if (intent != null && !closed) play(intent);
    }
    @Override public void close() { closed = true; lastIntent = null; cancel(); }

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
                || prepared.profileKey != null
                && !pending.profileKey.equals(prepared.profileKey)) {
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
        effects.launchPreparedHost(
                pending.prepared, pending.pendingGame, pending.request);
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
                && generation.get() == pending.request;
    }

    /** What the user asked for. */
    public static final class UserIntent {
        public enum Kind { PLAY, BACK, CANCEL, RETRY, RETURN_FROM_HOME }

        /** The physical Back / B button on the stream screen. */
        public static final UserIntent BACK = new UserIntent(Kind.BACK, null);
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
        /** A live warm-up attempt carries the game into the resumed neutral stream. */
        boolean warmUpCarriesGame;
        boolean replacementAuthorized;
        boolean reuseOnly;
        boolean opaqueReady;
        boolean targetPrepared;
        /** This pass evaluates the snapshot a refresh of an unresolved observation produced. */
        boolean observationRefreshed;
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
        /** A session is closing: toast, or {@link Action#failure} under the loading layer. */
        REFUSE_CLOSING,
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
        /** Launch once the snapshot at effect time is still idle. */
        LAUNCH_IF_IDLE,
        RETURN_TO_STREAM,
        RECONNECT,
        CONFIRM_REPLACEMENT,
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
        TAKE_OVER_WARM_UP
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
        return Action.of(s.hiddenWarmUp && s.resumed && !s.homeVisible && !s.warmUpPreparing
                && !s.switchRequested && !s.retainedSwitch
                ? ActionKind.TAKE_OVER_WARM_UP : ActionKind.IGNORE);
    }

    private static Action play(State s, PlayIntent intent) {
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

    private static Action playOnHost(State s, PlayIntent intent) {
        SessionSnapshot snapshot = s.session;
        boolean game = intent.kind == PlayIntent.Kind.PLAYNITE_GAME;
        LaunchTransitionType fresh = freshType(intent);
        // A game stop beside a live stream keeps only the way back to that stream open.
        if (snapshot.state == SessionSnapshot.State.TERMINATING
                || (snapshot.endingGame && !intent.matches(snapshot))) {
            // Under the loading layer a toast would leave the loader up with no error.
            return Action.failing(ActionKind.REFUSE_CLOSING,
                    s.opaqueReady ? SessionFailure.UNCERTAIN_TERMINATING : null);
        }
        if (snapshot.observationState == SessionSnapshot.ObservationState.RECOVERY_REQUIRED
                && !intent.matches(snapshot)) {
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
            // Whether the launch owns a fresh host session is read at effect time.
            return Action.launch(ActionKind.LAUNCH_IF_IDLE, fresh, "", false);
        }
        boolean authorized = s.replacementAuthorized || orphaned;
        boolean reuseOnly = s.reuseOnly || !authorized && s.canSwitchRetained;
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
        // A revealed stream whose readiness timed out is live, not a launch to cancel.
        if (s.connected && !s.homeVisible && s.revealed && s.timedOut) {
            return Action.of(ActionKind.COVER_AND_SHOW_HOME);
        }
        if (s.covered) return Action.of(ActionKind.CANCEL_ON_COVER);
        if (retainedObservation(s)) return Action.of(ActionKind.COVER_AND_SHOW_HOME);
        return Action.of(s.connected && !s.homeVisible
                ? ActionKind.SHOW_HOME : ActionKind.LEAVE_SCREEN);
    }

    private static Action cancel(State s) {
        if (!s.transitionActive) return Action.of(ActionKind.IGNORE);
        if (s.authorizationRetryOffered) {
            return Action.of(ActionKind.DECLINE_AUTHORIZATION_RETRY);
        }
        // The observation kept after a failed start is that start: abandoning it closes.
        if (s.failedStartObservation) return Action.of(ActionKind.CLOSE_HOST_SESSION);
        if (retainedObservation(s)) return Action.of(ActionKind.COVER_AND_SHOW_HOME);
        if (s.retainedSwitch) return Action.of(ActionKind.CANCEL_RETAINED_SWITCH);
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

    /** A live retained stream observing its game in the foreground: nothing to cancel. */
    private static boolean retainedObservation(State s) {
        return s.connected && !s.homeVisible && !s.parked
                && s.type == LaunchTransitionType.GAME_CONNECTION && s.hasGame
                && s.retainedHomeLive;
    }

    private void evaluate(long request, PlayIntent intent, boolean replacementAuthorized,
                          boolean reuseOnly, boolean opaqueReady,
                          NvApp preparedTarget, int pass) {
        if (!current(request)) return;
        // The longest chain of a host that answers takes passes 0..8. A host found asleep adds
        // one WAKE_HOST pass; one that fell asleep unnoticed adds the failed refresh and then
        // the wake. A host that keeps flapping runs into this limit.
        if (pass > 10) {
            fail(request, intent, SessionFailure.ORCHESTRATION_PASS_LIMIT, "");
            return;
        }

        SessionSnapshot snapshot = effects.resolve(intent.profileKey);
        if (!current(request)) return;
        Action action = transition(State.forPlay(snapshot,
                effects.canAttemptRetainedSwitch(intent), effects.warmUpCarriesGame(),
                replacementAuthorized, reuseOnly, opaqueReady, preparedTarget != null,
                evaluatesRefresh(request, pass), effects.isHostOnline(intent.hostId),
                effects.isPaired(intent.hostId)), UserIntent.play(intent));
        switch (action.kind) {
            case REFUSE_UNPAIRED:
                diagnostic("WARN", "orchestration.rejected", request, intent,
                        "reason", Rejection.UNPAIRED.name());
                inFlight = 0L;
                effects.reject(Rejection.UNPAIRED);
                break;
            case REFUSE_CLOSING:
                diagnostic("WARN", "orchestration.rejected", request, intent,
                        "reason", Rejection.TERMINATING.name());
                inFlight = 0L;
                if (action.failure != null) fail(request, intent, action.failure, "");
                else effects.reject(Rejection.TERMINATING);
                break;
            case FAIL:
                fail(request, intent, action.failure, "");
                break;
            case SHOW_LOADING:
                awaitOpaque(request, intent, action.type, action.replacementAuthorized,
                        action.reuseOnly, preparedTarget, pass);
                break;
            case REFRESH_OBSERVATION:
                refreshThenEvaluate(request, intent, action.replacementAuthorized,
                        action.reuseOnly, preparedTarget, pass);
                break;
            case REFRESH_SESSION:
                awaitFreshSession(request, intent, action.replacementAuthorized,
                        action.reuseOnly, preparedTarget, pass);
                break;
            case AWAIT_PREPARED:
                effects.awaitPrepared(() -> !current(request), () -> dispatcher.post(() -> {
                    if (current(request)) evaluate(request, intent, replacementAuthorized,
                            reuseOnly, true, preparedTarget, pass + 1);
                }));
                break;
            case WAKE_HOST:
                awaitHost(request, intent, action.replacementAuthorized,
                        action.reuseOnly, preparedTarget, pass);
                break;
            case PREFLIGHT:
                awaitPreflight(request, intent, action.replacementAuthorized,
                        action.reuseOnly, pass, action.preflight);
                break;
            case CLOSE_COMPETING:
                closeCompetingSession(request, intent, preparedTarget,
                        action.replacementAuthorized, action.reuseOnly, pass);
                break;
            case LAUNCH:
                launch(request, intent, preparedTarget, action.type, action.sourceSuspendId,
                        action.ownsFreshSunshineSession);
                break;
            case LAUNCH_IF_IDLE:
                SessionSnapshot latest = effects.resolve(intent.profileKey);
                if (!current(request)) return;
                if (latest.state == SessionSnapshot.State.NONE) {
                    launch(request, intent, preparedTarget, action.type, "",
                            latest.hostGameAppId == 0);
                } else {
                    evaluate(request, intent, replacementAuthorized, reuseOnly,
                            true, preparedTarget, pass + 1);
                }
                break;
            case RETURN_TO_STREAM:
                returnToRetained(request, intent);
                break;
            case RECONNECT:
                reconnect(request, intent);
                break;
            case CONFIRM_REPLACEMENT:
                effects.confirmReplacement(() -> {
                    if (current(request)) evaluate(request, intent, true, false,
                            opaqueReady, preparedTarget, pass + 1);
                });
                break;
            default:
                throw new IllegalStateException("Not a Play action: " + action.kind);
        }
    }

    private void awaitOpaque(long request, PlayIntent intent, LaunchTransitionType type,
                             boolean replacementAuthorized, boolean reuseOnly,
                             NvApp preparedTarget, int pass) {
        effects.showLoading(intent, type, () -> {
            if (current(request)) evaluate(request, intent, replacementAuthorized,
                    reuseOnly, true, preparedTarget, pass + 1);
        });
    }

    private void awaitPreflight(long request, PlayIntent intent, boolean replacementAuthorized,
                                boolean reuseOnly, int pass,
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
                    evaluate(request, intent, replacementAuthorized, reuseOnly,
                            true, result.target, pass + 1);
            });
        });
    }

    private void awaitHost(long request, PlayIntent intent, boolean replacementAuthorized,
                           boolean reuseOnly, NvApp preparedTarget, int pass) {
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
                evaluate(request, intent, replacementAuthorized, reuseOnly,
                        true, preparedTarget, pass + 1);
            });
        });
    }

    /** Whether this pass evaluates the snapshot a refresh of an unresolved observation produced. */
    private boolean evaluatesRefresh(long request, int pass) {
        return refreshedRequest == request && refreshedPass == pass;
    }

    private void refreshThenEvaluate(long request, PlayIntent intent,
                                     boolean replacementAuthorized, boolean reuseOnly,
                                     NvApp preparedTarget, int pass) {
        refreshedRequest = request;
        refreshedPass = pass + 1;
        awaitFreshSession(request, intent, replacementAuthorized, reuseOnly, preparedTarget, pass);
    }

    private void awaitFreshSession(long request, PlayIntent intent,
                                   boolean replacementAuthorized, boolean reuseOnly,
                                   NvApp preparedTarget, int pass) {
        effects.refreshSession(intent, () -> !current(request), success ->
                dispatcher.post(() -> {
                    if (!current(request)) return;
                    if (!success) {
                        if (effects.isHostOnline(intent.hostId)) {
                            fail(request, intent, SessionFailure.UNCERTAIN_REFRESH_FAILED, "");
                            return;
                        }
                        // The host fell asleep before its polls noticed: nothing was
                        // refreshed, so the table decides again and wakes it.
                        refreshedRequest = 0L;
                        evaluate(request, intent, replacementAuthorized, reuseOnly,
                                true, preparedTarget, pass + 1);
                        return;
                    }
                    if (effects.resolve(intent.profileKey).state
                            == SessionSnapshot.State.UNCERTAIN) {
                        fail(request, intent, SessionFailure.UNCERTAIN_AFTER_REFRESH, "");
                        return;
                    }
                    evaluate(request, intent, replacementAuthorized, reuseOnly,
                            true, preparedTarget, pass + 1);
                }));
    }

    private void closeCompetingSession(long request, PlayIntent intent, NvApp preparedTarget,
                                       boolean replacementAuthorized, boolean reuseOnly,
                                       int pass) {
        SessionSnapshot beforeClose = effects.resolve(intent.profileKey);
        if (!current(request)) return;
        if ((beforeClose.state == SessionSnapshot.State.NONE || intent.matches(beforeClose))
                && !effects.canAttemptRetainedSwitch(intent)) {
            evaluate(request, intent, replacementAuthorized, reuseOnly,
                    true, preparedTarget, pass + 1);
            return;
        }
        execute(request, intent, () -> {
            CloseResult closeResult;
            SessionFailure.Raised closeFailure = null;
            diagnostic("INFO", "orchestration.close_started", request, intent);
            try { closeResult = effects.closePreviousSession(
                    intent, preparedTarget, replacementAuthorized,
                    () -> !current(request)); }
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
                if (result == null) { fail(request, intent, failure.reason, failure.detail); return; }
                if (result == CloseResult.CANCELLED) return;
                if (result == CloseResult.NEEDS_CONFIRMATION) {
                    effects.confirmReplacement(() -> {
                        if (current(request)) evaluate(request, intent, true,
                                false, true, preparedTarget, pass + 1);
                    });
                    return;
                }
                if (result == CloseResult.REUSED) {
                    returnToRetained(request, intent);
                    return;
                }
                if (result == CloseResult.PRESENTATION_REQUIRED) {
                    diagnostic("INFO", "orchestration.presentation_required", request, intent);
                    inFlight = 0L;
                    effects.attachPresentationForSwitch(request, intent, preparedTarget);
                    return;
                }
                SessionSnapshot afterClose = effects.resolve(intent.profileKey);
                if (!current(request)) return;
                // The verified close is authoritative. UNCERTAIN, or TERMINATING while the
                // closing confirmation window is open, only means that no host observation
                // newer than the close has been accepted yet; every local fact already
                // shows nothing left to close.
                if (afterClose.state == SessionSnapshot.State.NONE
                        || afterClose.state == SessionSnapshot.State.UNCERTAIN
                        || afterClose.state == SessionSnapshot.State.TERMINATING) {
                    launch(request, intent, preparedTarget, freshType(intent), "",
                            afterClose.hostGameAppId == 0);
                } else if (intent.matches(afterClose)) {
                    evaluate(request, intent, true, false,
                            true, preparedTarget, pass + 1);
                } else fail(request, intent, SessionFailure.CLOSE_SNAPSHOT_NOT_IDLE, "");
            });
        });
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

    private boolean current(long request) { return !closed && generation.get() == request; }

    private static LaunchTransitionType freshType(PlayIntent intent) {
        switch (intent.kind) {
            case PLAYNITE_GAME: return LaunchTransitionType.GAME;
            case PLAYNITE_FULLSCREEN: return LaunchTransitionType.PLAYNITE;
            default: return LaunchTransitionType.GENERIC;
        }
    }
}
