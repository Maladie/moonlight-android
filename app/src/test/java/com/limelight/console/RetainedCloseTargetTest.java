package com.limelight.console;

import com.limelight.stream.RetainedStreamSessionCoordinator;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RetainedCloseTargetTest {
    private static final String GAME = "playnite:0f8fad5b-d9cb-469f";

    private final RetainedStreamSessionCoordinator.Controller owner =
            new RetainedStreamSessionCoordinator.Controller() {
                @Override public boolean isRetainedTransportLive() { return true; }
                @Override public boolean parkRetainedTransport() { return true; }
                @Override public void disconnectRetainedStream(
                        RetainedStreamSessionCoordinator.TerminationCallback completion) { }
                @Override public void terminateRetainedSession(
                        RetainedStreamSessionCoordinator.TerminationCallback completion) { }
                @Override public void switchGame(
                        RetainedStreamSessionCoordinator.SwitchRequest request,
                        RetainedStreamSessionCoordinator.SwitchCallback completion) { }
            };

    @After public void tearDown() throws ReflectiveOperationException {
        java.lang.reflect.Method clear = RetainedStreamSessionCoordinator.class
                .getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
    }

    @Test public void afterProcessDeathTheResumeRecordNamesHostActorAndGame() {
        // The coordinator was cleared (process death): its profile is only the default.
        RetainedCloseTarget target = RetainedCloseTarget.of(
                RetainedStreamSessionCoordinator.snapshot(), "host-b", "child", GAME);

        assertEquals("host-b", target.hostId);
        assertEquals("child", target.profileId);
        assertEquals(GAME, target.providerGameId);
    }

    @Test public void heldSessionOutranksTheRecord() {
        RetainedStreamSessionCoordinator.enterHome(owner, "session", "host-a", "kid", 7, GAME);

        RetainedCloseTarget target = RetainedCloseTarget.of(
                RetainedStreamSessionCoordinator.snapshot(), "host-b", "child", "");

        assertEquals("host-a", target.hostId);
        assertEquals("kid", target.profileId);
        assertEquals(GAME, target.providerGameId);
    }

    @Test public void nonPlayniteGameIsNotAProviderTarget() {
        RetainedCloseTarget target = RetainedCloseTarget.of(
                RetainedStreamSessionCoordinator.snapshot(), "host-b", "child", "steam app");

        assertEquals("", target.providerGameId);
    }

    @Test public void noSessionAndNoRecordHasNoTarget() {
        assertNull(RetainedCloseTarget.of(
                RetainedStreamSessionCoordinator.snapshot(), null, null, null));
    }
}
