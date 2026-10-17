package com.limelight.console;

import com.limelight.gateway.ChildSessionClient;
import com.limelight.stream.ChildTerminalPolicy;

/** Activity-free owner of child countdown and heartbeat monitoring. */
public final class ChildSessionLifecycle {
    public interface Scheduler {
        void post(Runnable action);
        void postDelayed(Runnable action, long delayMs);
        void remove(Runnable action);
    }

    public interface Clock { long now(); }

    public interface HeartbeatCallback {
        void complete(ChildSessionClient.State state);
        void unavailable();
    }

    public interface Listener {
        boolean active();
        void render(long remainingSeconds);
        /** A transient remaining-time notice: after the stream starts and at each warning. */
        void notice(long remainingSeconds);
        void requestHeartbeat(HeartbeatCallback callback);
        void requestGameStopState(HeartbeatCallback callback);
        boolean canRetainGameOnly();
        void retainGameOnly();
        void terminate(String reason);
    }

    private final Scheduler scheduler;
    private final Clock clock;
    private final Listener listener;
    private final long countdownIntervalMs;
    private final long heartbeatIntervalMs;
    private ChildSessionCountdown countdown;
    private String sessionId = "";
    private boolean heartbeatInFlight;
    private boolean running;
    private boolean gameStopInFlight;
    private long generation;

    private final Runnable countdownTick = new Runnable() {
        @Override public void run() {
            if (!running || !listener.active() || countdown == null) return;
            long now = clock.now();
            long remaining = countdown.remainingSeconds(now);
            listener.render(remaining);
            if (remaining <= 0L) {
                stop();
                listener.terminate("time_expired");
                return;
            }
            if (countdown.crossedWarningMinutes(now) > 0) listener.notice(remaining);
            scheduler.postDelayed(this, countdownIntervalMs);
        }
    };

    private final Runnable heartbeatTick = new Runnable() {
        @Override public void run() {
            if (!running || !listener.active()) return;
            if (!heartbeatInFlight) {
                heartbeatInFlight = true;
                final long requestGeneration = generation;
                listener.requestHeartbeat(new HeartbeatCallback() {
                    @Override public void complete(ChildSessionClient.State state) {
                        if (requestGeneration != generation) return;
                        heartbeatInFlight = false;
                        applyHeartbeat(state);
                    }

                    @Override public void unavailable() {
                        if (requestGeneration == generation) heartbeatInFlight = false;
                    }
                });
            }
            scheduler.postDelayed(this, heartbeatIntervalMs);
        }
    };

    public ChildSessionLifecycle(Scheduler scheduler, Clock clock, Listener listener,
                                 long countdownIntervalMs, long heartbeatIntervalMs) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.listener = listener;
        this.countdownIntervalMs = countdownIntervalMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public void configure(String sessionId, long remainingSeconds, long now) {
        this.sessionId = sessionId == null ? "" : sessionId;
        countdown = new ChildSessionCountdown(this.sessionId);
        countdown.update(this.sessionId, remainingSeconds, now);
    }

    public void update(String sessionId, long remainingSeconds, long now) {
        if (countdown != null) countdown.update(sessionId, remainingSeconds, now);
    }

    public void start() {
        stop();
        if (sessionId.isEmpty() || countdown == null) return;
        running = true;
        scheduler.post(countdownTick);
        scheduler.post(heartbeatTick);
    }

    /** Seconds left now, or -1 before a session is configured. */
    public long remainingSeconds() {
        return countdown == null || sessionId.isEmpty() ? -1L : countdown.remainingSeconds(clock.now());
    }

    /** Shows the remaining time once the child sees the stream. */
    public void announce() {
        if (!running || !listener.active() || countdown == null) return;
        long remaining = countdown.remainingSeconds(clock.now());
        if (remaining > 0L) listener.notice(remaining);
    }

    public void stop() {
        running = false;
        heartbeatInFlight = false;
        gameStopInFlight = false;
        generation++;
        scheduler.remove(countdownTick);
        scheduler.remove(heartbeatTick);
    }

    private void applyHeartbeat(ChildSessionClient.State state) {
        if (!running || !listener.active() || state == null
                || !sessionId.equals(state.sessionId)) return;
        if (state.cleanupRequired || terminal(state)) {
            handleTerminalState(state);
            return;
        }
        if (!state.ok) return;
        long now = clock.now();
        if (!countdown.update(sessionId, state.playableNowSeconds, now)
                || countdown.isExpired(now)) {
            stop();
            listener.terminate("time_expired");
        }
    }

    public void handleTerminalState(ChildSessionClient.State state) {
        stop();
        String reason = reason(state);
        if (state != null && "game_stopped".equalsIgnoreCase(reason)
                && (state.playableNowSeconds <= 0L
                || (countdown != null && countdown.isExpired(clock.now())))) {
            reason = "time_expired";
        }
        if (state != null && state.ok && ChildTerminalPolicy.action(
                reason, state.cleanupRequired) == ChildTerminalPolicy.Action.RETAIN_GAME_ONLY
                && listener.canRetainGameOnly()) {
            listener.retainGameOnly();
        } else {
            listener.terminate(reason);
        }
    }

    /** A provider exit must be classified before the child's allowance is cleared. */
    public void providerGameStopped() {
        if (!listener.active() || gameStopInFlight) return;
        gameStopInFlight = true;
        final long requestGeneration = generation;
        listener.requestGameStopState(new HeartbeatCallback() {
            @Override public void complete(ChildSessionClient.State state) {
                if (requestGeneration != generation || !listener.active()) return;
                if (state == null || !sessionId.equals(state.sessionId)
                        || !terminal(state)) {
                    unavailable();
                    return;
                }
                handleTerminalState(state);
            }

            @Override public void unavailable() {
                if (requestGeneration != generation || !listener.active()) return;
                stop();
                listener.terminate("child_session_unavailable");
            }
        });
    }

    public static boolean terminal(ChildSessionClient.State state) {
        if (state == null) return true;
        String value = state.state == null ? "" : state.state;
        String phase = state.phase == null ? "" : state.phase;
        return "ended".equalsIgnoreCase(value) || "stopped".equalsIgnoreCase(value)
                || "expired".equalsIgnoreCase(value) || "blocked".equalsIgnoreCase(value)
                || "cleanup_required".equalsIgnoreCase(value)
                || "ended".equalsIgnoreCase(phase) || "stopped".equalsIgnoreCase(phase)
                || "expired".equalsIgnoreCase(phase) || "blocked".equalsIgnoreCase(phase)
                || "cleanup_required".equalsIgnoreCase(phase);
    }

    public static String reason(ChildSessionClient.State state) {
        if (state == null || state.reason == null || state.reason.trim().isEmpty()) {
            return "child_session_unavailable";
        }
        return state.reason.trim();
    }
}
