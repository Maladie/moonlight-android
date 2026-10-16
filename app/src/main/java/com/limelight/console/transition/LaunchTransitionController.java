package com.limelight.console.transition;

import com.limelight.diagnostics.MoonWakerDiagnostics;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Thread-safe transition state machine. Every asynchronous signal is correlated
 * with the active transition and host before it can change cover or input state. The cover is
 * cosmetic: whenever readiness fails or stays uncertain over a live stream, the user can remove it.
 */
public final class LaunchTransitionController {
    public interface Listener {
        void onTransitionChanged(LaunchTransitionSnapshot snapshot);
    }

    /**
     * A cover a user-started operation holds over this transition's own presentation: the
     * Home return ({@code HOME_RETURN}) or a game switch ({@code SWITCH}). It covers and blocks
     * input whatever the transition shows and withholds any reveal; with a new transition and a
     * closing it is the only way a revealed stream is covered again. Released, a revealed stream
     * returns on its transport's next fresh frame (or at that frame's deadline).
     */
    public enum UserCover { NONE, HOME_RETURN, SWITCH }

    /** A readiness edge without a fresh frame by then offers only "Reveal now", never auto-reveal. */
    public static final long FRESH_FRAME_DEADLINE_MS = 3000L;
    /** An observed launcher prompt must persist this long before it shows as attention. */
    public static final long LAUNCHER_ATTENTION_DEBOUNCE_MS = 2500L;

    private final Listener listener;
    /** Same time base as MediaCodec rendered-frame timestamps (System.nanoTime). */
    private final LongSupplier nanoClock;
    private LaunchTransitionSpec spec;
    private LaunchTransitionType currentTarget = LaunchTransitionType.GENERIC;
    private String currentGameId = "";
    private LaunchTransitionState state = LaunchTransitionState.IDLE;
    private int activeStep = 5;
    private boolean overlayVisible;
    private boolean inputBlocked;
    private boolean operationAuthorized;
    private boolean revealAuthorized;
    private boolean manualRevealAvailable;
    private boolean manualRevealOverride;
    private boolean revealCompleted;
    private boolean surfaceReady;
    private boolean streamConnected;
    /** Any frame rendered on this transition's transport: gates the manual reveal. */
    private boolean transportHasFrames;
    /** A frame rendered after the latest readiness edge: gates the automatic reveal. */
    private boolean freshFrameAfterEdge;
    /** The current edge's fresh-frame deadline passed without one: "Reveal now" is offered. */
    private boolean freshFrameOverdue;
    /** Readiness-edge generation; a frame proves freshness only for its own edge. */
    private long frameEdge;
    private long edgeNanos;
    private int rejectedPreEdgeFrames;
    private boolean inputReady;
    private boolean gatewayReady;
    private boolean targetProcessRunning;
    private boolean targetWindowReady;
    private boolean streamWasRevealedBeforeClosing;
    private boolean uncertain;
    private boolean startTakingLonger;
    private int updateProgress = -1;
    /** The host reported this attempt's game window ready at least once: its start finished. */
    private boolean gameReadyConfirmed;
    private boolean revealPreferred;
    private UserCover userCover = UserCover.NONE;
    /** The user cover was released over a revealed stream and waits for its fresh frame. */
    private boolean uncoverAwaitsFrame;
    /** A readiness failure or window loss after a reveal: reported, never covering again. */
    private String notice = "";
    /** Since when (nanos) the observed launcher signal is pending; null: not pending. */
    private Long launcherPendingSince;
    /** Generation of the pending launcher signal; a deadline applies only to its own. */
    private long launcherGeneration;
    private String launcherPendingReason = "";
    /** The state an observed launcher attention returns to when its signal clears. */
    private LaunchTransitionState launcherReturnState;
    private LaunchTransitionState terminalFailureState;
    private String detail = "";
    /** Orders published snapshots: one posted from a worker may arrive after a newer one. */
    private long publishSequence;
    private String diagnosticSignature = "";
    private String diagnosticTransitionId = "";
    private LaunchTransitionState diagnosticState = LaunchTransitionState.IDLE;

    public LaunchTransitionController(Listener listener) {
        this(listener, System::nanoTime);
    }

    public LaunchTransitionController(Listener listener, LongSupplier nanoClock) {
        this.listener = listener;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public synchronized LaunchTransitionSnapshot begin(LaunchTransitionSpec next) {
        reset(next, 0L);
        return publish();
    }

    /**
     * {@code next} replaces the presented transition over the same live transport: the frames
     * that transport already rendered still count for "Reveal now", and its start is a readiness
     * edge (an IDR and a fresh-frame deadline), so no kind of transition keeps the cover with
     * only Cancel when the transport sends no new frame.
     */
    public synchronized LaunchTransitionSnapshot beginOnKeptTransport(LaunchTransitionSpec next) {
        boolean frames = transportHasFrames;
        reset(next, 1L);
        transportHasFrames = frames;
        return publish();
    }

    private void reset(LaunchTransitionSpec next, long firstEdge) {
        Objects.requireNonNull(next, "next");
        spec = next;
        currentTarget = next.type;
        currentGameId = next.type == LaunchTransitionType.GAME
                || next.type == LaunchTransitionType.GAME_CONNECTION
                ? normalizedGameId(next.playniteGameId) : "";
        state = LaunchTransitionState.PREPARING_SESSION;
        activeStep = 1;
        overlayVisible = true;
        inputBlocked = true;
        operationAuthorized = false;
        revealAuthorized = false;
        manualRevealAvailable = false;
        manualRevealOverride = false;
        revealCompleted = false;
        surfaceReady = false;
        streamConnected = false;
        transportHasFrames = false;
        rejectedPreEdgeFrames = 0;
        startFrameEdge(firstEdge);
        inputReady = false;
        boolean targetAlreadyRunning = next.type == LaunchTransitionType.GENERIC;
        gatewayReady = targetAlreadyRunning;
        targetProcessRunning = targetAlreadyRunning;
        targetWindowReady = targetAlreadyRunning;
        streamWasRevealedBeforeClosing = false;
        uncertain = false;
        startTakingLonger = false;
        gameReadyConfirmed = false;
        revealPreferred = false;
        endUserCover();
        notice = "";
        launcherPendingSince = null;
        launcherReturnState = null;
        terminalFailureState = null;
        detail = "";
    }

    /**
     * The exact game start is still not ready past the local soft threshold. Only adds a notice:
     * readiness, reveal and failure stay with the host observation and this controller. A game
     * whose window was once ready is running; a later window loss is not a slow start.
     */
    public synchronized void startTakingLonger(String transitionId) {
        if (!accept(transitionId, null) || startTakingLonger || gameReadyConfirmed
                || !isGameStartPending(state)) return;
        startTakingLonger = true;
        publish();
    }

    /** The host confirmed this attempt's game ready once; a manual reveal does not count. */
    public synchronized boolean gameReadyConfirmed() {
        return gameReadyConfirmed;
    }

    /**
     * The transition a restart after a dead transport begins: a game this controller saw
     * confirmed ready and whose launch was committed keeps running without its transport, so
     * the restart connects to it again and never starts it a second time.
     */
    public synchronized LaunchTransitionType restartType(LaunchTransitionType type,
                                                         boolean launchCommitted) {
        return type == LaunchTransitionType.GAME && gameReadyConfirmed && launchCommitted
                ? LaunchTransitionType.GAME_CONNECTION : type;
    }

    /**
     * The transition a failed switch keeps: its game is observed again; a stream carrying none
     * is the plain neutral stream again, never a game observation that can only time out.
     */
    public static LaunchTransitionType retainedRecoveryType(String oldGameId) {
        return oldGameId == null || oldGameId.isEmpty()
                ? LaunchTransitionType.GENERIC : LaunchTransitionType.GAME_CONNECTION;
    }

    /** A revealed game start recreates as an observation: its start never replays. */
    public static LaunchTransitionType recoveredTransitionType(
            LaunchTransitionType runtimeType, boolean restoredRevealed) {
        return restoredRevealed && runtimeType == LaunchTransitionType.GAME
                ? LaunchTransitionType.GAME_CONNECTION : runtimeType;
    }

    public static boolean isGameStartPending(LaunchTransitionState state) {
        return state == LaunchTransitionState.GAME_START_REQUESTED
                || state == LaunchTransitionState.GAME_STARTING
                || state == LaunchTransitionState.GAME_PROCESS_RUNNING
                || state == LaunchTransitionState.GAME_WINDOW_STABILIZING;
    }

    /**
     * A cover wait settled: its effect runs unless the wait was cancelled. The cover is
     * cosmetic, so a frame that never came (UNAVAILABLE) runs the effect like a submitted one.
     */
    public static boolean coverAllowsEffect(PresentationBarrier.Outcome outcome) {
        return outcome != PresentationBarrier.Outcome.CANCELLED;
    }

    /**
     * The one input routing decision of the stream screen: whether controller, keyboard, mouse
     * and remote input may reach the host. Never while the cover holds the input, nor for a
     * hidden warm-up behind the Home; {@code snapshot} null is a stream without a transition.
     */
    public static boolean inputReachesHost(LaunchTransitionSnapshot snapshot,
                                           boolean hiddenWarmUp) {
        return !hiddenWarmUp && (snapshot == null || !snapshot.inputBlocked);
    }

    /** How loud the stream is heard: not at all, attenuated under a cover, or in full. */
    public enum StreamAudio { MUTED, COVERED, FULL }

    /**
     * The stream's audio level: in full once the cover is off the stream (revealed),
     * attenuated under any cover (launch, switch, Home return, closing), muted for a hidden
     * warm-up behind the carousel. {@code snapshot} null is a stream without a transition.
     */
    public static StreamAudio streamAudio(LaunchTransitionSnapshot snapshot,
                                          boolean hiddenWarmUp) {
        if (hiddenWarmUp) return StreamAudio.MUTED;
        return snapshot != null && snapshot.overlayVisible
                ? StreamAudio.COVERED : StreamAudio.FULL;
    }

    /** A wait's receiver for an effect with no transition of its own (console loading). */
    public static Consumer<PresentationBarrier.Outcome> runUnlessCancelled(Runnable effect) {
        return outcome -> {
            if (coverAllowsEffect(outcome)) effect.run();
        };
    }

    /**
     * The cover of {@code transitionId} settled with {@code outcome}. Returns whether the
     * effect waiting on it may run; false (cancelled, this transition cancelled, or no longer
     * the current one) ends the operation through its cancel path. A transition still before
     * its connection is authorized to connect, whether or not its surface came first.
     * SUBMITTED is no proof of visibility and never reveals anything.
     */
    public synchronized boolean coverSettled(String transitionId,
                                             PresentationBarrier.Outcome outcome) {
        if (!coverAllowsEffect(outcome) || !accept(transitionId, null)) return false;
        if (!operationAuthorized && (state == LaunchTransitionState.PREPARING_SESSION
                || state == LaunchTransitionState.WAITING_FOR_VIDEO_SURFACE)) {
            operationAuthorized = true;
            if (state == LaunchTransitionState.PREPARING_SESSION) {
                state = LaunchTransitionState.CONNECTING_STREAM;
            }
            publish();
        }
        return true;
    }

    public synchronized void surfaceReady(String transitionId) {
        if (!accept(transitionId, null)) return;
        surfaceReady = true;
        if (!streamConnected && !requiresUserInteraction()) {
            state = LaunchTransitionState.WAITING_FOR_VIDEO_SURFACE;
        }
        evaluateReady();
    }

    public synchronized void streamConnected(String transitionId) {
        if (!accept(transitionId, null)) return;
        streamConnected = true;
        if (requiresUserInteraction()) {
            evaluateReady();
            return;
        }
        if (spec.type == LaunchTransitionType.PLAYNITE) {
            state = LaunchTransitionState.PLAYNITE_STARTING;
        } else if (spec.type == LaunchTransitionType.GAME && !targetProcessRunning) {
            // A game started before the stream keeps its confirmed phase 2; never back to 1.
            state = LaunchTransitionState.GAME_START_REQUESTED;
        }
        evaluateReady();
    }

    /**
     * The decoder rendered a frame armed for {@code edge}, at {@code renderNanos}
     * (System.nanoTime base). Any frame proves the transport renders; only a frame of the
     * current edge rendered after it proves the target is on screen.
     */
    public synchronized void videoFrameRendered(String transitionId, long edge, long renderNanos) {
        if (!accept(transitionId, null) || rejectsLateTargetSignal()) return;
        transportHasFrames = true;
        if (edge == frameEdge && renderNanos >= edgeNanos) {
            freshFrameAfterEdge = true;
            MoonWakerDiagnostics.record("INFO", "android.launch-transition",
                    "video_frame.fresh", "transition_id", transitionId, "frame_edge", edge,
                    "after_edge_ms", (renderNanos - edgeNanos) / 1_000_000L);
        } else if (!freshFrameAfterEdge) {
            rejectedPreEdgeFrames++;
            MoonWakerDiagnostics.record("INFO", "android.launch-transition",
                    "video_frame.rejected_pre_edge",
                    "transition_id", transitionId,
                    "frame_edge", edge,
                    "current_edge", frameEdge,
                    "rendered_before_edge_ms", (edgeNanos - renderNanos) / 1_000_000L,
                    "count", rejectedPreEdgeFrames);
        }
        if (freshFrameAfterEdge && uncoverAwaitsFrame) endUserCover();
        evaluateReady();
    }

    /**
     * The fresh-frame deadline of {@code edge} passed: without a fresh frame the stream keeps
     * only the manual "Reveal now"; a fresh frame arriving later still reveals automatically.
     */
    public synchronized void freshFrameDeadline(String transitionId, long edge) {
        if (!accept(transitionId, null) || edge != frameEdge || freshFrameAfterEdge
                || freshFrameOverdue) return;
        freshFrameOverdue = true;
        MoonWakerDiagnostics.record("WARN", "android.launch-transition",
                "video_frame.fresh_deadline",
                "transition_id", transitionId,
                "frame_edge", edge,
                "transport_has_frames", transportHasFrames);
        // Cosmetic: a released user cover never stays up for want of a frame.
        if (uncoverAwaitsFrame) endUserCover();
        evaluateReady();
    }

    /**
     * The user started an operation that covers this transition first: the Home return or a
     * game switch. Returns false (nothing covered) when the transition is not current or
     * already holds that cover; a switch takes over a Home return's cover, never the reverse.
     */
    public synchronized boolean coverForUser(String transitionId, UserCover cover) {
        if (cover == null || cover == UserCover.NONE || !accept(transitionId, null)) return false;
        boolean held = !uncoverAwaitsFrame && userCover != UserCover.NONE;
        if (held && (userCover == cover || userCover == UserCover.SWITCH)) return false;
        userCover = cover;
        uncoverAwaitsFrame = false;
        publish();
        return true;
    }

    /**
     * The user operation behind {@code cover} ended without replacing this transition (the
     * user is back from the Home, a switch was cancelled or failed): the transition's own
     * presentation returns. A revealed stream shows again on its transport's next fresh frame.
     */
    public synchronized void releaseUserCover(String transitionId, UserCover cover) {
        if (!accept(transitionId, null) || cover == UserCover.NONE || userCover != cover
                || uncoverAwaitsFrame) return;
        if (revealCompleted) {
            uncoverAwaitsFrame = true;
            startFrameEdge(frameEdge + 1L);
            publish();
            return;
        }
        endUserCover();
        evaluateReady();
    }

    /**
     * A hidden warm-up became the user's stream (TAKE_OVER_WARM_UP). Its frames were never
     * armed while hidden, so its showing is a readiness edge (an IDR and a fresh-frame
     * deadline): the first frame then proves the transport, and without one in time the user
     * still gets "Reveal now" once any frame rendered.
     */
    public synchronized void takenOver(String transitionId) {
        if (!accept(transitionId, null) || revealCompleted || rejectsLateTargetSignal()) return;
        startFrameEdge(frameEdge + 1L);
        evaluateReady();
    }

    private void endUserCover() {
        userCover = UserCover.NONE;
        uncoverAwaitsFrame = false;
    }

    /** Edge 0 is the transition's start; each later edge is a target readiness edge. */
    private void startFrameEdge(long edge) {
        frameEdge = edge;
        edgeNanos = nanoClock.getAsLong();
        freshFrameAfterEdge = false;
        freshFrameOverdue = false;
        MoonWakerDiagnostics.record("INFO", "android.launch-transition", "video_frame.edge",
                "transition_id", spec.id, "frame_edge", frameEdge);
    }

    public synchronized void inputPipelineReady(String transitionId) {
        if (!accept(transitionId, null)) return;
        inputReady = true;
        evaluateReady();
    }

    public synchronized void gatewayConnected(String transitionId, String hostId) {
        if (!accept(transitionId, hostId)) return;
        gatewayReady = true;
        evaluateReady();
    }

    public synchronized void targetProcessRunning(String transitionId, String hostId,
                                                  LaunchTransitionType kind,
                                                  String gameId) {
        targetProcessRunning(transitionId, hostId, kind, gameId, "");
    }

    public synchronized void targetProcessRunning(String transitionId, String hostId,
                                                  LaunchTransitionType kind,
                                                  String gameId, String reason) {
        if (!acceptTarget(transitionId, hostId, kind, gameId)
                || rejectsLateTargetSignal()) return;
        targetProcessRunning = true;
        detail = reason == null ? "" : reason;
        state = kind == LaunchTransitionType.GAME
                ? LaunchTransitionState.GAME_PROCESS_RUNNING
                : LaunchTransitionState.PLAYNITE_PROCESS_RUNNING;
        publish();
    }

    public synchronized void targetStarting(String transitionId, String hostId,
                                            LaunchTransitionType kind, String gameId) {
        if (rejectsLateTargetSignal()) return;
        if (kind == LaunchTransitionType.GAME
                && accept(transitionId, hostId)
                && currentTarget == LaunchTransitionType.PLAYNITE
                && !normalizedGameId(gameId).isEmpty()) {
            currentTarget = LaunchTransitionType.GAME;
            currentGameId = normalizedGameId(gameId);
            overlayVisible = true;
            inputBlocked = true;
            revealAuthorized = false;
            manualRevealAvailable = false;
            manualRevealOverride = false;
            revealCompleted = false;
            targetProcessRunning = false;
            targetWindowReady = false;
            freshFrameAfterEdge = false;
            uncertain = false;
            detail = "";
            state = LaunchTransitionState.GAME_STARTING;
            publish();
            return;
        }
        if (!acceptTarget(transitionId, hostId, kind, gameId)) return;
        state = kind == LaunchTransitionType.GAME
                ? LaunchTransitionState.GAME_STARTING
                : LaunchTransitionState.PLAYNITE_STARTING;
        publish();
    }

    public synchronized void targetWindowStabilizing(String transitionId, String hostId,
                                                     LaunchTransitionType kind,
                                                     String gameId, String reason) {
        if (!acceptTarget(transitionId, hostId, kind, gameId)
                || rejectsLateTargetSignal()) return;
        targetWindowReady = false;
        detail = reason == null ? "" : reason;
        state = kind == LaunchTransitionType.GAME
                ? LaunchTransitionState.GAME_WINDOW_STABILIZING
                : LaunchTransitionState.PLAYNITE_FULLSCREEN_STARTING;
        publish();
    }

    public synchronized void targetWindowReady(String transitionId, String hostId,
                                               LaunchTransitionType kind,
                                               String gameId) {
        if (!acceptTarget(transitionId, hostId, kind, gameId)
                || rejectsLateTargetSignal()) return;
        boolean newlyReady = !targetWindowReady;
        targetWindowReady = true;
        targetProcessRunning = true;
        startTakingLonger = false;
        if (kind == LaunchTransitionType.GAME) gameReadyConfirmed = true;
        // A frame rendered before the target window became ready can still contain
        // the desktop or a launcher. Require one frame produced after this exact
        // readiness edge before the privacy overlay may be removed.
        if (newlyReady) startFrameEdge(frameEdge + 1L);
        state = kind == LaunchTransitionType.GAME
                ? LaunchTransitionState.GAME_READY
                : LaunchTransitionState.PLAYNITE_FULLSCREEN_READY;
        detail = "";
        evaluateReady();
    }

    public synchronized void targetWindowLost(String transitionId, String hostId,
                                              LaunchTransitionType kind,
                                              String gameId, String reason) {
        if (!acceptTarget(transitionId, hostId, kind, gameId)
                || rejectsLateTargetSignal()) return;
        // The live stream keeps its frames for "Reveal now"; automatic reveal still needs a
        // fresh frame after the next readiness edge (targetWindowReady).
        targetWindowReady = false;
        detail = reason == null ? "" : reason;
        state = kind == LaunchTransitionType.GAME
                ? LaunchTransitionState.GAME_WINDOW_STABILIZING
                : LaunchTransitionState.PLAYNITE_FULLSCREEN_STARTING;
        if (reportAfterReveal(detail)) return;
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = false;
        revealCompleted = false;
        evaluateReady();
    }

    /** The host refused the game start until its launcher is answered: shown at once. */
    public synchronized void launcherInteractionRequired(
            String transitionId, String hostId, String gameId, String reason) {
        if (!acceptTarget(transitionId, hostId, LaunchTransitionType.GAME, gameId)
                || revealAuthorized || revealCompleted) return;
        launcherPendingSince = null;
        launcherReturnState = null;
        enterLauncherAttention(reason);
    }

    /**
     * A host observation reports a launcher prompt. A start often shows one only briefly, so
     * it becomes the attention state only once it persisted {@link
     * #LAUNCHER_ATTENTION_DEBOUNCE_MS}: repeated past that time, or at {@link
     * #launcherAttentionDue}. Returns the generation the caller passes to that deadline after
     * the debounce, or 0 when no new signal started pending.
     */
    public synchronized long launcherInteractionObserved(
            String transitionId, String hostId, String gameId, String reason) {
        if (!acceptTarget(transitionId, hostId, LaunchTransitionType.GAME, gameId)
                || !launcherAttentionAllowed()
                || state == LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED) return 0L;
        launcherPendingReason = reason;
        long now = nanoClock.getAsLong();
        if (launcherPendingSince == null) {
            launcherPendingSince = now;
            return ++launcherGeneration;
        }
        if (now - launcherPendingSince >= LAUNCHER_ATTENTION_DEBOUNCE_MS * 1_000_000L) {
            enterObservedLauncherAttention();
        }
        return 0L;
    }

    /** The debounce of launcher signal {@code generation} elapsed with the signal pending. */
    public synchronized void launcherAttentionDue(String transitionId, long generation) {
        if (!accept(transitionId, null) || launcherPendingSince == null
                || generation != launcherGeneration || !launcherAttentionAllowed()) return;
        enterObservedLauncherAttention();
    }

    /**
     * A host observation without a launcher prompt: a pending signal is dropped, and an
     * observed launcher attention returns to the state it covered at once.
     */
    public synchronized void launcherInteractionCleared(String transitionId) {
        if (!accept(transitionId, null)) return;
        launcherPendingSince = null;
        if (state != LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED
                || launcherReturnState == null) return;
        // Its window readiness was dropped on entry: the next readiness edge restores it.
        state = launcherReturnState == LaunchTransitionState.GAME_READY
                ? LaunchTransitionState.GAME_WINDOW_STABILIZING : launcherReturnState;
        launcherReturnState = null;
        detail = "";
        evaluateReady();
    }

    private boolean launcherAttentionAllowed() {
        return !revealAuthorized && !revealCompleted && terminalFailureState == null
                && !rejectsLateTargetSignal();
    }

    private void enterObservedLauncherAttention() {
        LaunchTransitionState covered = state;
        launcherPendingSince = null;
        enterLauncherAttention(launcherPendingReason);
        launcherReturnState = covered;
    }

    private void enterLauncherAttention(String reason) {
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        revealCompleted = false;
        targetWindowReady = false;
        detail = reason == null ? "" : reason;
        state = LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED;
        manualRevealAvailable = transportReady();
        publish();
    }

    /**
     * The host reports a stale earlier launch's game running instead of this attempt's game.
     * The user may reveal, end that game, or cancel; nothing is decided automatically.
     */
    public synchronized void previousGameRunning(String transitionId, String hostId,
                                                 String reason) {
        if (!accept(transitionId, hostId) || currentTarget != LaunchTransitionType.GAME
                || revealAuthorized || revealCompleted || rejectsLateTargetSignal()) return;
        overlayVisible = true;
        inputBlocked = true;
        targetWindowReady = false;
        detail = reason == null ? "" : reason;
        state = LaunchTransitionState.PREVIOUS_GAME_RUNNING;
        manualRevealAvailable = transportReady();
        publish();
    }

    /**
     * The host updates this transition's game before starting it (progress 0-100, -1 without
     * byte counts). Nothing expires here; only readiness, cancel or a host failure end it. After
     * a reveal the cover never returns.
     */
    public synchronized void gameUpdating(String transitionId, String hostId, int progress) {
        if (!accept(transitionId, hostId) || currentTarget != LaunchTransitionType.GAME
                || revealAuthorized || revealCompleted || rejectsLateTargetSignal()) return;
        overlayVisible = true;
        inputBlocked = true;
        targetWindowReady = false;
        startTakingLonger = false;
        updateProgress = Math.max(-1, Math.min(100, progress));
        state = LaunchTransitionState.GAME_UPDATING;
        evaluateReady();
    }

    /** A host observation that does not report an update: the start continues as a launch. */
    public synchronized void gameUpdateFinished(String transitionId) {
        if (!accept(transitionId, null) || state != LaunchTransitionState.GAME_UPDATING) return;
        updateProgress = -1;
        state = LaunchTransitionState.GAME_STARTING;
        evaluateReady();
    }

    public synchronized void hostSessionLocked(
            String transitionId, String hostId, LaunchTransitionType kind,
            String gameId, String reason) {
        if (!acceptTarget(transitionId, hostId, kind, gameId)
                || rejectsLateTargetSignal()) return;
        targetWindowReady = false;
        detail = reason == null ? "" : reason;
        state = LaunchTransitionState.HOST_SESSION_LOCKED;
        if (reportAfterReveal(detail)) return;
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        revealCompleted = false;
        manualRevealAvailable = transportReady();
        publish();
    }

    /** Returns whether this transition's game stop is now covered (false: refused). */
    public synchronized boolean gameStopping(String transitionId, String hostId, String gameId) {
        if (!acceptTarget(transitionId, hostId, LaunchTransitionType.GAME, gameId)) return false;
        // A failed stop restores this presentation (streamClosingFailed).
        if (state != LaunchTransitionState.GAME_STOPPING) {
            streamWasRevealedBeforeClosing = revealCompleted;
        }
        // The closing the user (or the game) started takes over a user cover.
        endUserCover();
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = false;
        revealCompleted = false;
        targetWindowReady = false;
        freshFrameAfterEdge = false;
        state = LaunchTransitionState.GAME_STOPPING;
        publish();
        return true;
    }

    public synchronized void playniteReturning(String transitionId, String hostId) {
        if (!accept(transitionId, hostId)) return;
        currentTarget = LaunchTransitionType.PLAYNITE;
        currentGameId = "";
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = false;
        revealCompleted = false;
        targetProcessRunning = false;
        targetWindowReady = false;
        freshFrameAfterEdge = false;
        state = LaunchTransitionState.PLAYNITE_RETURNING;
        publish();
    }

    public synchronized void playniteStopping(String transitionId, String hostId) {
        if (!accept(transitionId, hostId)) return;
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = false;
        revealCompleted = false;
        state = LaunchTransitionState.PLAYNITE_STOPPING;
        publish();
    }

    public synchronized void closingStream(String transitionId) {
        if (!accept(transitionId, null)) return;
        if (state != LaunchTransitionState.CLOSING_STREAM) {
            streamWasRevealedBeforeClosing = revealCompleted;
        }
        endUserCover();
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = false;
        revealCompleted = false;
        state = LaunchTransitionState.CLOSING_STREAM;
        publish();
    }

    /**
     * A close or game stop under the cover failed: a stream revealed before it is shown again
     * (a manual reveal keeps its override), otherwise the failure keeps "Reveal now" for a live
     * stream. Returns whether the stream was revealed before.
     */
    public synchronized boolean streamClosingFailed(String transitionId, String reason) {
        if (!accept(transitionId, null)) return false;
        revealAuthorized = false;
        manualRevealAvailable = false;
        if (streamWasRevealedBeforeClosing) {
            overlayVisible = false;
            inputBlocked = false;
            revealCompleted = true;
            state = currentTarget == LaunchTransitionType.GAME
                    || currentTarget == LaunchTransitionType.GAME_CONNECTION
                    ? LaunchTransitionState.GAME_RUNNING
                    : currentTarget == LaunchTransitionType.PLAYNITE
                    ? LaunchTransitionState.PLAYNITE_FULLSCREEN_READY
                    : LaunchTransitionState.IDLE;
        } else {
            overlayVisible = true;
            inputBlocked = true;
            revealCompleted = false;
            state = LaunchTransitionState.ERROR;
            terminalFailureState = LaunchTransitionState.ERROR;
            manualRevealAvailable = transportReady();
            detail = reason == null ? "" : reason;
        }
        publish();
        return streamWasRevealedBeforeClosing;
    }

    public synchronized void returningToDashboard(String transitionId) {
        if (!accept(transitionId, null)) return;
        state = LaunchTransitionState.RETURNING_TO_DASHBOARD;
        publish();
    }

    public synchronized void timedOut(String transitionId, String reason) {
        timedOut(transitionId, reason, false);
    }

    public synchronized void timedOut(String transitionId, String reason,
                                      boolean revealPreferred) {
        if (!accept(transitionId, null) || reportAfterReveal(reason)) return;
        state = LaunchTransitionState.TIMED_OUT;
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = transportReady();
        this.revealPreferred = revealPreferred;
        uncertain = true;
        terminalFailureState = LaunchTransitionState.TIMED_OUT;
        detail = reason == null ? "" : reason;
        publish();
    }

    public synchronized void error(String transitionId, String reason) {
        error(transitionId, reason, false);
    }

    /** A failure never rejects the user: a live stream keeps "Reveal now" available. */
    public synchronized void error(String transitionId, String reason, boolean revealPreferred) {
        if (!accept(transitionId, null) || reportAfterReveal(reason)) return;
        state = LaunchTransitionState.ERROR;
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = transportReady();
        this.revealPreferred = revealPreferred;
        terminalFailureState = LaunchTransitionState.ERROR;
        detail = reason == null ? "" : reason;
        publish();
    }

    /** Returns the snapshot the cancel replaced (the current one when it does not apply). */
    public synchronized LaunchTransitionSnapshot cancel(String transitionId) {
        LaunchTransitionSnapshot before = snapshotValue();
        if (!accept(transitionId, null)) return before;
        endUserCover();
        state = LaunchTransitionState.CANCELLED;
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        publish();
        return before;
    }

    public synchronized void showStreamAnyway(String transitionId) {
        if (!accept(transitionId, null) || !manualRevealAvailable
                || userCover != UserCover.NONE) return;
        terminalFailureState = null;
        manualRevealOverride = true;
        revealAuthorized = true;
        manualRevealAvailable = false;
        publish();
    }

    public synchronized void revealCompleted(String transitionId) {
        if (!accept(transitionId, null) || !revealAuthorized || revealCompleted
                || userCover != UserCover.NONE) return;
        revealCompleted = true;
        overlayVisible = false;
        inputBlocked = false;
        state = currentTarget == LaunchTransitionType.GAME
                || currentTarget == LaunchTransitionType.GAME_CONNECTION
                ? LaunchTransitionState.GAME_RUNNING
                : currentTarget == LaunchTransitionType.PLAYNITE
                ? LaunchTransitionState.PLAYNITE_FULLSCREEN_READY
                : LaunchTransitionState.IDLE;
        publish();
    }

    public synchronized LaunchTransitionSnapshot snapshot() {
        return snapshotValue();
    }

    private boolean accept(String transitionId, String hostId) {
        if (spec == null || transitionId == null || !spec.id.equals(transitionId)) return false;
        if (hostId != null && !spec.hostId.equals(hostId)) return false;
        return state != LaunchTransitionState.CANCELLED;
    }

    private boolean acceptTarget(String transitionId, String hostId,
                                 LaunchTransitionType kind, String gameId) {
        if (!accept(transitionId, hostId) || kind == LaunchTransitionType.GENERIC) return false;
        if (kind != currentTarget
                && !(currentTarget == LaunchTransitionType.GAME_CONNECTION
                && kind == LaunchTransitionType.GAME)) return false;
        if (kind == LaunchTransitionType.GAME) {
            return !currentGameId.isEmpty()
                    && currentGameId.equals(normalizedGameId(gameId));
        }
        return true;
    }

    private boolean rejectsLateTargetSignal() {
        return state == LaunchTransitionState.GAME_STOPPING
                || state == LaunchTransitionState.PLAYNITE_STOPPING
                || state == LaunchTransitionState.CLOSING_STREAM
                || state == LaunchTransitionState.RETURNING_TO_DASHBOARD;
    }

    private boolean requiresUserInteraction() {
        return state == LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED
                || state == LaunchTransitionState.PREVIOUS_GAME_RUNNING
                || state == LaunchTransitionState.HOST_SESSION_LOCKED;
    }

    private static String normalizedGameId(String gameId) {
        return gameId == null ? "" : gameId.trim().toLowerCase();
    }

    private void evaluateReady() {
        if (rejectsLateTargetSignal()
                || state == LaunchTransitionState.PLAYNITE_RETURNING) {
            manualRevealAvailable = false;
            revealAuthorized = false;
            publish();
            return;
        }
        boolean transportReady = transportReady();
        boolean targetReady = gatewayReady && targetProcessRunning && targetWindowReady;
        if (terminalFailureState != null) {
            state = terminalFailureState;
            manualRevealAvailable = transportReady;
            publish();
            return;
        }
        if (transportReady && targetReady && freshFrameAfterEdge && !revealAuthorized) {
            manualRevealAvailable = false;
            revealAuthorized = true;
            if (currentTarget == LaunchTransitionType.GAME
                    || currentTarget == LaunchTransitionType.GAME_CONNECTION) {
                state = LaunchTransitionState.GAME_READY;
            }
            else if (currentTarget == LaunchTransitionType.PLAYNITE) {
                state = LaunchTransitionState.PLAYNITE_FULLSCREEN_READY;
            }
        } else if (transportReady && (!targetReady || freshFrameOverdue) && !revealAuthorized) {
            // The user can explicitly reveal a rendering stream while the host-side readiness
            // is still catching up or its fresh frame did not arrive in time, whatever its kind.
            manualRevealAvailable = true;
        }
        publish();
    }

    /**
     * This transition's stream is revealed (automatically or manually), or the user chose
     * "Reveal now" and its reveal is under way: the cover never returns until a new transition
     * begins. A later readiness failure or window loss is only a notice; a chosen reveal still
     * completes. A pending automatic reveal is not one yet: losing its evidence revokes it.
     */
    private boolean streamRevealed() {
        return revealCompleted || manualRevealOverride && revealAuthorized;
    }

    private boolean reportAfterReveal(String reason) {
        if (!streamRevealed()) return false;
        manualRevealAvailable = false;
        notice = reason == null ? "" : reason;
        publish();
        return true;
    }

    private boolean transportReady() {
        return surfaceReady && streamConnected && transportHasFrames && inputReady;
    }

    private LaunchTransitionSnapshot publish() {
        if (state != LaunchTransitionState.ERROR
                && state != LaunchTransitionState.TIMED_OUT
                && state != LaunchTransitionState.CANCELLED) {
            activeStep = stepForState(state);
        }
        publishSequence++;
        LaunchTransitionSnapshot value = snapshotValue();
        LaunchTransitionType publishedTarget = currentTarget;
        String publishedGameId = currentGameId;
        String nextSignature = value.spec.id + '|' + value.state + '|' + value.step + '|'
                + value.overlayVisible + '|' + value.inputBlocked + '|'
                + value.operationAuthorized + '|' + value.revealAuthorized + '|'
                + value.manualRevealAvailable + '|' + value.uncertain + '|' + value.detail
                + '|' + value.userCover;
        if (!nextSignature.equals(diagnosticSignature)) {
            LaunchTransitionState previous = value.spec.id.equals(diagnosticTransitionId)
                    ? diagnosticState : LaunchTransitionState.IDLE;
            diagnosticSignature = nextSignature;
            diagnosticTransitionId = value.spec.id;
            diagnosticState = value.state;
            MoonWakerDiagnostics.record(
                    value.state == LaunchTransitionState.ERROR
                            || value.state == LaunchTransitionState.TIMED_OUT ? "WARN" : "INFO",
                    "android.launch-transition", "launch_transition.changed",
                    "transition_id", value.spec.id,
                    "host_id", value.spec.hostId,
                    "game_id", publishedGameId,
                    "kind", publishedTarget.name(),
                    "from", previous.name(),
                    "to", value.state.name(),
                    "state", value.state.name(),
                    "stage", value.step,
                    "overlay_visible", value.overlayVisible,
                    "input_blocked", value.inputBlocked,
                    "operation_authorized", value.operationAuthorized,
                    "reveal_authorized", value.revealAuthorized,
                    "manual_reveal_available", value.manualRevealAvailable,
                    "uncertain", value.uncertain,
                    "user_cover", value.userCover.name(),
                    "reason", value.detail);
        }
        if (listener != null) listener.onTransitionChanged(value);
        return value;
    }

    private LaunchTransitionSnapshot snapshotValue() {
        boolean held = userCover != UserCover.NONE;
        boolean covered = overlayVisible || held;
        return new LaunchTransitionSnapshot(spec, state, activeStep,
                covered, inputBlocked || held, operationAuthorized,
                revealAuthorized && !held, manualRevealAvailable && !held, uncertain, detail,
                startTakingLonger && isGameStartPending(state), revealPreferred, notice,
                manualRevealOverride,
                covered && !freshFrameAfterEdge && !rejectsLateTargetSignal()
                        && state != LaunchTransitionState.CANCELLED, frameEdge, userCover,
                uncoverAwaitsFrame, state == LaunchTransitionState.GAME_UPDATING
                        ? updateProgress : -1, publishSequence);
    }

    static int stepForState(LaunchTransitionState state) {
        switch (state) {
            case PREPARING_SESSION:
                return 1;
            case CONNECTING_STREAM:
            case WAITING_FOR_VIDEO_SURFACE:
                return 2;
            case PLAYNITE_STARTING:
            case PLAYNITE_PROCESS_RUNNING:
            case GAME_UPDATING:
            case GAME_START_REQUESTED:
            case GAME_STARTING:
            case GAME_PROCESS_RUNNING:
                return 3;
            case PLAYNITE_FULLSCREEN_STARTING:
            case GAME_WINDOW_STABILIZING:
            case HOST_SESSION_LOCKED:
            case LAUNCHER_INTERACTION_REQUIRED:
            case PREVIOUS_GAME_RUNNING:
            case GAME_STOPPING:
            case PLAYNITE_RETURNING:
            case PLAYNITE_STOPPING:
            case CLOSING_STREAM:
                return 4;
            case PLAYNITE_FULLSCREEN_READY:
            case GAME_READY:
            case GAME_RUNNING:
            case IDLE:
                return 5;
            default:
                return 4;
        }
    }
}
