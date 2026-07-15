package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class UnifiedConsoleHomeSessionTest {
    @Test public void fallsBackUntilUnifiedLaunchBegins() {
        UnifiedConsoleHomeSession status = new UnifiedConsoleHomeSession();
        ConsoleDataRepository.Session legacy = session("ended", false);

        assertSame(legacy, status.visibleOr(legacy));
    }

    @Test public void ownsConnectingAndConnectedHomeStatus() {
        UnifiedConsoleHomeSession status = new UnifiedConsoleHomeSession();
        status.begin(host(), app());

        ConsoleDataRepository.Session connecting = status.visibleOr(null);
        assertEquals("connecting", connecting.state);
        assertEquals("Steam Big Picture", connecting.app);
        assertFalse(connecting.alive);

        status.planned(1920, 1080, 60);
        status.connected();

        ConsoleDataRepository.Session active = status.visibleOr(null);
        assertEquals("streaming", active.state);
        assertEquals(1920, active.width);
        assertEquals(1080, active.height);
        assertEquals(60, active.fps);
        assertTrue(active.alive);
    }

    @Test public void clearRestoresLegacyProviderStatus() {
        UnifiedConsoleHomeSession status = new UnifiedConsoleHomeSession();
        ConsoleDataRepository.Session legacy = session("ended", false);
        status.begin(host(), app());
        status.connected();

        status.clear();

        assertSame(legacy, status.visibleOr(legacy));
    }

    private static ConsoleDataRepository.Host host() {
        return new ConsoleDataRepository.Host("host-a", "Host", "host");
    }

    private static ConsoleDataRepository.App app() {
        return new ConsoleDataRepository.App(1, "Steam Big Picture", null);
    }

    private static ConsoleDataRepository.Session session(String state, boolean alive) {
        return new ConsoleDataRepository.Session(
                state, null, "Host", "Steam Big Picture", 0, 0, 0, alive);
    }
}
