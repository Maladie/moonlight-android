package com.limelight.console;

import com.limelight.gateway.ChildSessionClient;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChildSessionLifecycleTest {
    @Test public void deadlineAndHeartbeatAreOwnedWithoutAnActivity() {
        Fixture fixture = new Fixture();
        fixture.lifecycle.configure("session-a", 2, 1_000);
        fixture.lifecycle.start();

        fixture.scheduler.runPosted(0);
        fixture.scheduler.runPosted(0);
        assertEquals(1, fixture.heartbeats.size());
        fixture.scheduler.runDelayed(1);
        assertEquals(1, fixture.heartbeats.size());

        fixture.now = 3_000;
        fixture.scheduler.runDelayed(0);
        assertEquals("time_expired", fixture.terminated);
    }

    @Test public void remainingTimeIsAnnouncedOnRevealThenOnlyAtWarnings() {
        Fixture fixture = new Fixture();
        fixture.now = 0;
        fixture.lifecycle.configure("session-a", 2 * 3_600L + 60L, 0);
        fixture.lifecycle.announce();
        assertTrue(fixture.notices.isEmpty());

        fixture.lifecycle.start();
        fixture.scheduler.runPosted(0);
        fixture.lifecycle.announce();
        assertEquals(List.of(2 * 3_600L + 60L), fixture.notices);

        fixture.now = 30_000;
        fixture.scheduler.runDelayed(0);
        assertEquals(1, fixture.notices.size());

        fixture.now = 61_000;
        fixture.scheduler.runDelayed(0);
        assertEquals(List.of(2 * 3_600L + 60L, 2 * 3_600L - 1L), fixture.notices);
        assertEquals(2 * 3_600L - 1L, fixture.lifecycle.remainingSeconds());
    }

    @Test public void staleHeartbeatCannotAffectANewGeneration() throws Exception {
        Fixture fixture = new Fixture();
        fixture.lifecycle.configure("session-a", 60, 0);
        fixture.lifecycle.start();
        fixture.scheduler.runPosted(1);
        ChildSessionLifecycle.HeartbeatCallback stale = fixture.heartbeats.get(0);

        fixture.lifecycle.stop();
        fixture.lifecycle.configure("session-a", 60, 0);
        fixture.lifecycle.start();
        stale.complete(state("session-a", "expired", "time_expired", false));

        assertEquals("", fixture.terminated);
        assertEquals(0, fixture.retained);
    }

    @Test public void terminalPolicyIsAppliedByTheLifecycleOwner() throws Exception {
        Fixture fixture = new Fixture();
        fixture.canRetain = true;

        fixture.lifecycle.handleTerminalState(
                state("session-a", "ended", "game_stopped", false));
        assertEquals(1, fixture.retained);
        assertEquals("", fixture.terminated);

        fixture.lifecycle.handleTerminalState(
                state("session-a", "cleanup_required", "game_stopped", true));
        assertEquals("game_stopped", fixture.terminated);
    }

    @Test public void gameStoppedAtExhaustedBudgetCannotRetainTheTransport() throws Exception {
        Fixture fixture = new Fixture();
        fixture.canRetain = true;
        fixture.lifecycle.handleTerminalState(
                state("session-a", "ended", "game_stopped", false, 0));
        assertEquals(0, fixture.retained);
        assertEquals("time_expired", fixture.terminated);
    }

    @Test public void providerExitWaitsForHostClassificationAndOnlyOrdinaryStopRetains()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.canRetain = true;
        fixture.lifecycle.configure("session-a", 60, 1_000);
        fixture.lifecycle.start();
        fixture.lifecycle.providerGameStopped();
        fixture.lifecycle.providerGameStopped();
        assertEquals(1, fixture.gameStops.size());
        assertEquals(0, fixture.retained);
        assertEquals("", fixture.terminated);
        fixture.gameStops.get(0).complete(
                state("session-a", "ended", "game_stopped", false));
        assertEquals(1, fixture.retained);
        assertEquals("", fixture.terminated);
    }

    @Test public void providerExitDuringNaturalCleanupUsesWholeSessionTermination()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.canRetain = true;
        fixture.lifecycle.configure("session-a", 60, 1_000);
        fixture.lifecycle.start();
        fixture.lifecycle.providerGameStopped();
        fixture.gameStops.get(0).complete(
                state("session-a", "cleanup_required", "daily_limit_reached", true, 0));
        assertEquals(0, fixture.retained);
        assertEquals("daily_limit_reached", fixture.terminated);
    }

    @Test public void lateProviderClassificationCannotUndoLocalExpiry() throws Exception {
        Fixture fixture = new Fixture();
        fixture.canRetain = true;
        fixture.lifecycle.configure("session-a", 1, 1_000);
        fixture.lifecycle.start();
        fixture.scheduler.runPosted(0);
        fixture.lifecycle.providerGameStopped();
        fixture.now = 2_000;
        fixture.scheduler.runDelayed(0);
        fixture.gameStops.get(0).complete(
                state("session-a", "ended", "game_stopped", false));
        assertEquals(0, fixture.retained);
        assertEquals("time_expired", fixture.terminated);
    }

    @Test public void staleProviderResultCannotTerminateANewerSession() throws Exception {
        Fixture fixture = new Fixture();
        fixture.lifecycle.configure("session-a", 60, 1_000);
        fixture.lifecycle.start();
        fixture.lifecycle.providerGameStopped();
        ChildSessionLifecycle.HeartbeatCallback old = fixture.gameStops.get(0);
        fixture.lifecycle.stop();
        fixture.lifecycle.configure("session-b", 60, 1_000);
        fixture.lifecycle.start();
        old.complete(state("session-a", "expired", "daily_limit_reached", false, 0));
        old.unavailable();
        assertEquals("", fixture.terminated);
        assertEquals(0, fixture.retained);
    }

    @Test public void providerResponseBeforeQueuedTickCannotRetainAfterDeadline() throws Exception {
        Fixture fixture = new Fixture();
        fixture.canRetain = true;
        fixture.lifecycle.configure("session-a", 1, 1_000);
        fixture.lifecycle.start();
        fixture.lifecycle.providerGameStopped();
        fixture.now = 2_000;
        fixture.gameStops.get(0).complete(
                state("session-a", "ended", "game_stopped", false, 1));
        assertEquals(0, fixture.retained);
        assertEquals("time_expired", fixture.terminated);
        assertTrue(fixture.scheduler.posted.isEmpty());
        assertTrue(fixture.scheduler.delayed.isEmpty());
    }

    @Test public void stoppedMonitoringStillEnforcesDeadlineOnProviderResponse() throws Exception {
        Fixture fixture = new Fixture();
        fixture.canRetain = true;
        fixture.lifecycle.configure("session-a", 1, 1_000);
        fixture.lifecycle.start();
        fixture.lifecycle.stop();
        fixture.now = 2_000;
        fixture.lifecycle.providerGameStopped();
        fixture.gameStops.get(0).complete(
                state("session-a", "ended", "game_stopped", false, 1));
        assertEquals(0, fixture.retained);
        assertEquals("time_expired", fixture.terminated);
    }

    @Test public void stoppedMonitoringCanRetainOrdinaryStopBeforeDeadline() throws Exception {
        Fixture fixture = new Fixture();
        fixture.canRetain = true;
        fixture.lifecycle.configure("session-a", 1, 1_000);
        fixture.lifecycle.start();
        fixture.lifecycle.stop();
        fixture.now = 1_999;
        fixture.lifecycle.providerGameStopped();
        fixture.gameStops.get(0).complete(
                state("session-a", "ended", "game_stopped", false, 1));
        assertEquals(1, fixture.retained);
        assertEquals("", fixture.terminated);
    }

    @Test public void wrongSessionProviderResponseSettlesEvenWhenMonitoringStopped() throws Exception {
        Fixture fixture = new Fixture();
        fixture.canRetain = true;
        fixture.lifecycle.configure("session-a", 60, 1_000);
        fixture.lifecycle.start();
        fixture.lifecycle.stop();
        fixture.lifecycle.providerGameStopped();
        fixture.gameStops.get(0).complete(
                state("session-b", "ended", "game_stopped", false));
        assertEquals(0, fixture.retained);
        assertEquals("child_session_unavailable", fixture.terminated);
    }

    @Test public void heartbeatBudgetExpiryFencesPendingProviderResponse() throws Exception {
        Fixture fixture = new Fixture();
        fixture.canRetain = true;
        fixture.lifecycle.configure("session-a", 60, 1_000);
        fixture.lifecycle.start();
        fixture.scheduler.runPosted(1);
        fixture.lifecycle.providerGameStopped();
        fixture.heartbeats.get(0).complete(state("session-a", "active", "", false, 0));
        fixture.gameStops.get(0).complete(
                state("session-a", "ended", "game_stopped", false));
        assertEquals(0, fixture.retained);
        assertEquals("time_expired", fixture.terminated);
        assertTrue(fixture.scheduler.posted.isEmpty());
        assertTrue(fixture.scheduler.delayed.isEmpty());
    }

    @Test public void unavailableProviderClassificationCannotRetain() {
        Fixture fixture = new Fixture();
        fixture.canRetain = true;
        fixture.lifecycle.configure("session-a", 60, 1_000);
        fixture.lifecycle.start();
        fixture.lifecycle.providerGameStopped();
        fixture.gameStops.get(0).unavailable();
        assertEquals(0, fixture.retained);
        assertEquals("child_session_unavailable", fixture.terminated);
    }

    private static ChildSessionClient.State state(
            String sessionId, String phase, String reason, boolean cleanup) throws Exception {
        return state(sessionId, phase, reason, cleanup, 60);
    }

    private static ChildSessionClient.State state(
            String sessionId, String phase, String reason, boolean cleanup,
            long remaining) throws Exception {
        JSONObject body = new JSONObject()
                .put("ok", true).put("state", phase).put("phase", phase)
                .put("reason", reason).put("actor_profile_id", "kid")
                .put("execution_profile_id", "parent").put("game_id", "playnite:game")
                .put("playable_now_seconds", remaining).put("cleanup_required", cleanup);
        Constructor<ChildSessionClient.State> constructor =
                ChildSessionClient.State.class.getDeclaredConstructor(
                        JSONObject.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(body, sessionId);
    }

    private static final class Scheduler implements ChildSessionLifecycle.Scheduler {
        final List<Runnable> posted = new ArrayList<>();
        final List<Runnable> delayed = new ArrayList<>();

        @Override public void post(Runnable action) { posted.add(action); }
        @Override public void postDelayed(Runnable action, long delayMs) {
            delayed.add(action);
        }
        @Override public void remove(Runnable action) {
            posted.remove(action);
            delayed.remove(action);
        }
        void runPosted(int index) { posted.remove(index).run(); }
        void runDelayed(int index) { delayed.remove(index).run(); }
    }

    private static final class Fixture implements ChildSessionLifecycle.Listener {
        final Scheduler scheduler = new Scheduler();
        final List<ChildSessionLifecycle.HeartbeatCallback> heartbeats = new ArrayList<>();
        final List<ChildSessionLifecycle.HeartbeatCallback> gameStops = new ArrayList<>();
        final List<Long> notices = new ArrayList<>();
        long now = 1_000;
        final ChildSessionLifecycle lifecycle = new ChildSessionLifecycle(
                scheduler, () -> now, this, 1_000, 5_000);
        boolean canRetain;
        int retained;
        String terminated = "";

        @Override public boolean active() { return true; }
        @Override public void render(long remainingSeconds) { }
        @Override public void notice(long remainingSeconds) { notices.add(remainingSeconds); }
        @Override public void requestHeartbeat(ChildSessionLifecycle.HeartbeatCallback callback) {
            heartbeats.add(callback);
        }
        @Override public void requestGameStopState(ChildSessionLifecycle.HeartbeatCallback callback) {
            gameStops.add(callback);
        }
        @Override public boolean canRetainGameOnly() { return canRetain; }
        @Override public void retainGameOnly() { retained++; }
        @Override public void terminate(String reason) { terminated = reason; }
    }
}
