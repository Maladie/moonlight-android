package com.limelight.session;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A host record read while this host's own session moves never adopts or retires its records. */
public class HostStreamRecordSessionTest {
    private final SessionModelDriver driver = new SessionModelDriver();

    private static final class Owner implements SessionModel.Controller {
        @Override public boolean isRetainedTransportLive() { return true; }
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
        assertTrue(driver.beginPreparing(owner, "s", "host", "parent", 7, "", "t1", 1L));
        assertTrue(HostStreamRecord.sessionOwnsRecords("HOST", driver.snapshot()));
        // Another host's start leaves this host's records to the host record.
        assertFalse(HostStreamRecord.sessionOwnsRecords("other", driver.snapshot()));
        // A reconnect record waits for the host record to adopt or retire it.
        assertTrue(driver.markPreparingReconnectRequired(owner, "s", "host", 7, "t1", 1L));
        assertFalse(HostStreamRecord.sessionOwnsRecords("host", driver.snapshot()));
    }
}
