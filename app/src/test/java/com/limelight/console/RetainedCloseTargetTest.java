package com.limelight.console;

import com.limelight.session.SessionModel;
import com.limelight.session.SessionModelDriver;


import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RetainedCloseTargetTest {
    private final SessionModelDriver m = new SessionModelDriver();
    private static final String GAME = "playnite:0f8fad5b-d9cb-469f";

    private final SessionModel.Controller owner =
            new SessionModel.Controller() {
                @Override public boolean isRetainedTransportLive() { return true; }
                private final long fakeGeneration =
                        com.limelight.session.SessionModelDriver.nextTransportGeneration();
                @Override public long transportGeneration() { return fakeGeneration; }
                @Override public SessionModel.ParkOutcome parkRetainedTransport() { return SessionModel.ParkOutcome.PARKED; }
                @Override public void disconnectRetainedStream(
                        SessionModel.TerminationCallback completion) { }
                @Override public void terminateRetainedSession(
                        String gameId, SessionModel.TerminationCallback completion) { }
                @Override public void switchGame(
                        SessionModel.SwitchRequest request,
                        SessionModel.SwitchCallback completion) { }
            };

    @Test public void afterProcessDeathTheResumeRecordNamesHostActorAndGame() {
        // The coordinator was cleared (process death): its profile is only the default.
        RetainedCloseTarget target = RetainedCloseTarget.of(
                m.snapshot(), "host-b", "child", GAME);

        assertEquals("host-b", target.hostId);
        assertEquals("child", target.profileId);
        assertEquals(GAME, target.providerGameId);
    }

    @Test public void heldSessionOutranksTheRecord() {
        m.enterHome(owner, "session", "host-a", "kid", 7, GAME);

        RetainedCloseTarget target = RetainedCloseTarget.of(
                m.snapshot(), "host-b", "child", "");

        assertEquals("host-a", target.hostId);
        assertEquals("kid", target.profileId);
        assertEquals(GAME, target.providerGameId);
    }

    @Test public void nonPlayniteGameIsNotAProviderTarget() {
        RetainedCloseTarget target = RetainedCloseTarget.of(
                m.snapshot(), "host-b", "child", "steam app");

        assertEquals("", target.providerGameId);
    }

    @Test public void noSessionAndNoRecordHasNoTarget() {
        assertNull(RetainedCloseTarget.of(
                m.snapshot(), null, null, null));
    }
}
