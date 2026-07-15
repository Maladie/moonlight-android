package com.limelight.console;

import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyGameSessionAdapterTest {
    @Test(expected = IllegalStateException.class)
    public void adapterCannotCreateSecondConnection() {
        new LegacyGameSessionAdapter(new Fake()).connect();
    }

    @Test public void disconnectAndQuitRemainSeparateDelegations() {
        Fake fake = new Fake();
        LegacyGameSessionAdapter adapter = new LegacyGameSessionAdapter(fake);
        adapter.disconnectTransport();
        assertEquals(1, fake.disconnects);
        assertEquals(0, fake.quits);
        adapter.quitHostApplication();
        assertEquals(1, fake.disconnects);
        assertEquals(1, fake.quits);
    }

    private static final class Fake implements LegacyGameSessionAdapter.Delegate {
        int disconnects;
        int quits;
        public StreamSessionController.SessionState state() { return StreamSessionController.SessionState.CONNECTED; }
        public void requestDisconnectTransport() { disconnects++; }
        public void requestQuitHostApplication() { quits++; }
    }
}
