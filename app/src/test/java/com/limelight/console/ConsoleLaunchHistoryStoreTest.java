package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConsoleLaunchHistoryStoreTest {
    @Test public void historyKeysRemainWakeCompatibleAndHostScoped() {
        assertEquals("played_at.app.host-a.17",
                ConsoleLaunchHistoryStore.appKey("host-a", 17));
        assertEquals("played_at.host.host-a",
                ConsoleLaunchHistoryStore.hostKey("host-a"));
    }

    @Test public void relativeLabelsMatchWakeHome() {
        assertEquals("just now", ConsoleLaunchHistoryStore.formatRelative(30_000));
        assertEquals("5m ago", ConsoleLaunchHistoryStore.formatRelative(5 * 60_000L));
        assertEquals("3h ago", ConsoleLaunchHistoryStore.formatRelative(3 * 60 * 60_000L));
        assertEquals("2d ago", ConsoleLaunchHistoryStore.formatRelative(2 * 24 * 60 * 60_000L));
    }
}
