package com.limelight.console.transition;

import java.util.Objects;

/**
 * Thread-safe transition state machine. Every asynchronous signal is correlated
 * with the active transition and host before it can change privacy or input state.
 */
public final class LaunchTransitionController {
    public interface Listener {
        void onTransitionChanged(LaunchTransitionSnapshot snapshot);
    }

    private final Listener listener;
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
    private boolean revealCompleted;
    private boolean surfaceReady;
    private boolean streamConnected;
    private boolean videoFrameReady;
    private boolean inputReady;
    private boolean gatewayReady;
    private boolean targetProcessRunning;
    private boolean targetWindowReady;
    private boolean uncertain;
    private String detail = "";

    public LaunchTransitionController(Listener listener) {
        this.listener = listener;
    }

    public synchronized LaunchTransitionSnapshot begin(LaunchTransitionSpec next) {
        Objects.requireNonNull(next, "next");
        spec = next;
        currentTarget = next.type;
        currentGameId = next.type == LaunchTransitionType.GAME
                ? normalizedGameId(next.playniteGameId) : "";
        state = LaunchTransitionState.PREPARING_SESSION;
        activeStep = 1;
        overlayVisible = true;
        inputBlocked = true;
        operationAuthorized = false;
        revealAuthorized = false;
        manualRevealAvailable = false;
        revealCompleted = false;
        surfaceReady = false;
        streamConnected = false;
        videoFrameReady = false;
        inputReady = false;
        gatewayReady = next.type == LaunchTransitionType.GENERIC;
        targetProcessRunning = next.type == LaunchTransitionType.GENERIC;
        targetWindowReady = next.type == LaunchTransitionType.GENERIC;
        uncertain = false;
        detail = "";
        return publish();
    }

    public synchronized void overlayRendered(String transitionId) {
        if (!accept(transitionId, null) || operationAuthorized) return;
        operationAuthorized = true;
        state = LaunchTransitionState.CONNECTING_STREAM;
        publish();
    }

    public synchronized void surfaceReady(String transitionId) {
        if (!accept(transitionId, null)) return;
        surfaceReady = true;
        if (!streamConnected) state = LaunchTransitionState.WAITING_FOR_VIDEO_SURFACE;
        evaluateReady();
    }

    public synchronized void streamConnected(String transitionId) {
        if (!accept(transitionId, null)) return;
        streamConnected = true;
        if (spec.type == LaunchTransitionType.PLAYNITE) {
            state = LaunchTransitionState.PLAYNITE_STARTING;
        } else if (spec.type == LaunchTransitionType.GAME) {
            state = LaunchTransitionState.GAME_START_REQUESTED;
        }
        evaluateReady();
    }

    public synchronized void videoFrameRendered(String transitionId) {
        if (!accept(transitionId, null)) return;
        videoFrameReady = true;
        evaluateReady();
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
        if (!acceptTarget(transitionId, hostId, kind, gameId)) return;
        targetProcessRunning = true;
        state = kind == LaunchTransitionType.GAME
                ? LaunchTransitionState.GAME_PROCESS_RUNNING
                : LaunchTransitionState.PLAYNITE_PROCESS_RUNNING;
        publish();
    }

    public synchronized void targetStarting(String transitionId, String hostId,
                                            LaunchTransitionType kind, String gameId) {
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
            revealCompleted = false;
            targetProcessRunning = false;
            targetWindowReady = false;
            videoFrameReady = false;
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
        if (!acceptTarget(transitionId, hostId, kind, gameId)) return;
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
        if (!acceptTarget(transitionId, hostId, kind, gameId)) return;
        targetWindowReady = true;
        targetProcessRunning = true;
        state = kind == LaunchTransitionType.GAME
                ? LaunchTransitionState.GAME_READY
                : LaunchTransitionState.PLAYNITE_FULLSCREEN_READY;
        detail = "";
        evaluateReady();
    }

    public synchronized void targetWindowLost(String transitionId, String hostId,
                                              LaunchTransitionType kind,
                                              String gameId, String reason) {
        if (!acceptTarget(transitionId, hostId, kind, gameId)) return;
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = false;
        revealCompleted = false;
        targetWindowReady = false;
        videoFrameReady = false;
        detail = reason == null ? "" : reason;
        state = kind == LaunchTransitionType.GAME
                ? LaunchTransitionState.GAME_WINDOW_STABILIZING
                : LaunchTransitionState.PLAYNITE_FULLSCREEN_STARTING;
        publish();
    }

    public synchronized void gameStopping(String transitionId, String hostId, String gameId) {
        if (!acceptTarget(transitionId, hostId, LaunchTransitionType.GAME, gameId)) return;
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = false;
        revealCompleted = false;
        targetWindowReady = false;
        videoFrameReady = false;
        state = LaunchTransitionState.GAME_STOPPING;
        publish();
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
        videoFrameReady = false;
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
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = false;
        revealCompleted = false;
        state = LaunchTransitionState.CLOSING_STREAM;
        publish();
    }

    public synchronized void returningToDashboard(String transitionId) {
        if (!accept(transitionId, null)) return;
        state = LaunchTransitionState.RETURNING_TO_DASHBOARD;
        publish();
    }

    public synchronized void timedOut(String transitionId, String reason) {
        if (!accept(transitionId, null)) return;
        state = LaunchTransitionState.TIMED_OUT;
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = false;
        uncertain = true;
        detail = reason == null ? "" : reason;
        publish();
    }

    public synchronized void error(String transitionId, String reason) {
        if (!accept(transitionId, null)) return;
        state = LaunchTransitionState.ERROR;
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        manualRevealAvailable = false;
        detail = reason == null ? "" : reason;
        publish();
    }

    public synchronized void cancel(String transitionId) {
        if (!accept(transitionId, null)) return;
        state = LaunchTransitionState.CANCELLED;
        overlayVisible = true;
        inputBlocked = true;
        revealAuthorized = false;
        publish();
    }

    public synchronized void showStreamAnyway(String transitionId) {
        if (!accept(transitionId, null) || (!manualRevealAvailable && !uncertain)) return;
        revealAuthorized = true;
        manualRevealAvailable = false;
        publish();
    }

    public synchronized void revealCompleted(String transitionId) {
        if (!accept(transitionId, null) || !revealAuthorized || revealCompleted) return;
        revealCompleted = true;
        overlayVisible = false;
        inputBlocked = false;
        state = currentTarget == LaunchTransitionType.GAME
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
        if (kind != currentTarget) return false;
        if (kind == LaunchTransitionType.GAME) {
            return !currentGameId.isEmpty()
                    && currentGameId.equals(normalizedGameId(gameId));
        }
        return true;
    }

    private static String normalizedGameId(String gameId) {
        return gameId == null ? "" : gameId.trim().toLowerCase();
    }

    private void evaluateReady() {
        boolean transportReady = surfaceReady && streamConnected && videoFrameReady && inputReady;
        boolean targetReady = gatewayReady && targetProcessRunning && targetWindowReady;
        if (transportReady && targetReady && !revealAuthorized) {
            manualRevealAvailable = false;
            revealAuthorized = true;
            if (currentTarget == LaunchTransitionType.GAME) state = LaunchTransitionState.GAME_READY;
            else if (currentTarget == LaunchTransitionType.PLAYNITE) {
                state = LaunchTransitionState.PLAYNITE_FULLSCREEN_READY;
            }
        } else if (transportReady && !targetReady && !revealAuthorized
                && currentTarget != LaunchTransitionType.GENERIC) {
            // The user can explicitly reveal a confirmed video stream while the
            // host-side readiness signal is still catching up.
            manualRevealAvailable = true;
        }
        publish();
    }

    private LaunchTransitionSnapshot publish() {
        if (state != LaunchTransitionState.ERROR
                && state != LaunchTransitionState.TIMED_OUT
                && state != LaunchTransitionState.CANCELLED) {
            activeStep = stepForState(state);
        }
        LaunchTransitionSnapshot value = snapshotValue();
        if (listener != null) listener.onTransitionChanged(value);
        return value;
    }

    private LaunchTransitionSnapshot snapshotValue() {
        return new LaunchTransitionSnapshot(spec, state, activeStep,
                overlayVisible, inputBlocked, operationAuthorized, revealAuthorized,
                manualRevealAvailable, uncertain, detail);
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
            case GAME_START_REQUESTED:
            case GAME_STARTING:
            case GAME_PROCESS_RUNNING:
                return 3;
            case PLAYNITE_FULLSCREEN_STARTING:
            case GAME_WINDOW_STABILIZING:
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
