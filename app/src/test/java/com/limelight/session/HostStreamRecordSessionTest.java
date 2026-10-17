package com.limelight.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A host record read while this host's own session moves never adopts or retires its records. */
public class HostStreamRecordSessionTest {
    private final SessionModelDriver driver = new SessionModelDriver();

    private static final class Owner implements SessionModel.Controller {
        @Override public boolean isRetainedTransportLive() { return true; }
        private final long fakeGeneration =
                com.limelight.session.SessionModelDriver.nextTransportGeneration();
        @Override public long transportGeneration() { return fakeGeneration; }
        @Override public SessionModel.ParkOutcome parkRetainedTransport() {
            return SessionModel.ParkOutcome.PARKED;
        }
        @Override public void disconnectRetainedStream(
                SessionModel.TerminationCallback completion) { }
        @Override public void terminateRetainedSession(
                String gameId, SessionModel.TerminationCallback completion) { }
        @Override public void switchGame(SessionModel.SwitchRequest request,
                                         SessionModel.SwitchCallback completion) { }
    }

    @Test public void startOfTheHostOwnsItsRecordsUntilItNeedsAReconnect() {
        assertFalse(HostStreamRecord.sessionOwnsRecords("host", driver.snapshot()));
        Owner owner = new Owner();
        assertTrue(driver.beginPreparing(owner, "s", "host", "parent", 7, "") > 0L);
        assertTrue(HostStreamRecord.sessionOwnsRecords("HOST", driver.snapshot()));
        // Another host's start leaves this host's records to the host record.
        assertFalse(HostStreamRecord.sessionOwnsRecords("other", driver.snapshot()));
        // A reconnect record waits for the host record to adopt or retire it.
        assertTrue(driver.markPreparingReconnectRequired(owner, "s", "host", 7,
                driver.snapshot().preparingOperation));
        assertFalse(HostStreamRecord.sessionOwnsRecords("host", driver.snapshot()));
    }

    /** Resume metadata is not a stream: a marker the host no longer holds ends with its record. */
    @Test public void markerTheHostNoLongerHoldsIsRetiredWithItsRecord() {
        Owner owner = new Owner();
        driver.enterHome(owner, "s", "host", "parent", 7, "steam:952060");
        driver.markReconnectRequired("s");
        driver.resume.edit().putBoolean("pending", true).putString("streamSessionId", "s")
                .putString("pcUuid", "host").putString("profileId", "parent").apply();
        assertFalse(HostStreamRecord.sessionOwnsRecords("host", driver.snapshot()));

        // What the model applies for RETIRE (SessionModelHostTest drives the event itself).
        assertEquals(HostStreamRecord.Action.RETIRE, HostStreamRecord.decide(null, "parent", "s"));
        assertTrue(driver.clearAfterAuthoritativeEndIfMatches("s"));
        assertTrue(driver.call(new SessionEvent.ReconnectCleared("s")));

        assertEquals(SessionModel.State.NONE, driver.state());
        assertNull(driver.model.records().pending);
    }
}
