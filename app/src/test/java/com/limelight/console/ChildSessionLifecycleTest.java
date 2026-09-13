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

    private static ChildSessionClient.State state(
            String sessionId, String phase, String reason, boolean cleanup) throws Exception {
        JSONObject body = new JSONObject()
                .put("ok", true).put("state", phase).put("phase", phase)
                .put("reason", reason).put("actor_profile_id", "kid")
                .put("execution_profile_id", "parent").put("game_id", "playnite:game")
                .put("playable_now_seconds", 60).put("cleanup_required", cleanup);
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
        long now = 1_000;
        final ChildSessionLifecycle lifecycle = new ChildSessionLifecycle(
                scheduler, () -> now, this, 1_000, 5_000);
        boolean canRetain;
        int retained;
        String terminated = "";

        @Override public boolean active() { return true; }
        @Override public void render(long remainingSeconds) { }
        @Override public void warning(long remainingMinutes) { }
        @Override public void requestHeartbeat(ChildSessionLifecycle.HeartbeatCallback callback) {
            heartbeats.add(callback);
        }
        @Override public boolean canRetainGameOnly() { return canRetain; }
        @Override public void retainGameOnly() { retained++; }
        @Override public void terminate(String reason) { terminated = reason; }
    }
}
