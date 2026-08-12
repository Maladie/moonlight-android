package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActiveSessionSnapshotTest {
    @Test public void hostRunningAppMakesMatchingTileActiveWithoutBridgeIdentity() {
        ActiveSessionSnapshot snapshot = new ActiveSessionSnapshot("host", 42, "", true);
        assertTrue(snapshot.matches("game", 42));
        assertFalse(snapshot.matches("other", 7));
    }

    @Test public void resolvedPlayniteIdentityDisambiguatesSharedSunshineTargets() {
        ActiveSessionSnapshot snapshot = new ActiveSessionSnapshot("host", 42, "game-b", true);
        assertFalse(snapshot.matches("game-a", 42));
        assertTrue(snapshot.matches("game-b", 42));
    }

    @Test public void stoppedHostNeverLeavesAResumeTile() {
        ActiveSessionSnapshot snapshot = new ActiveSessionSnapshot("host", 0, "game", true);
        assertFalse(snapshot.matches("game", 42));
    }

    @Test public void retainedTransportKeepsMatchingTileActiveDuringCachedHostUpdate() {
        ActiveSessionSnapshot snapshot = new ActiveSessionSnapshot("host", 42, "game", true);
        assertTrue(snapshot.matches("game", 42));
    }
}
