package com.limelight.console;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlayniteSessionPresentationTest {
    @Test
    public void suspendedSessionIsResumableWhileHostSleeps() {
        assertEquals(PlayniteSessionPresentation.State.RESUME_SUSPENDED,
                PlayniteSessionPresentation.resolve(true, false,
                        "hollow", 42, "hollow", 42, 0L, 0, ""));
    }

    @Test
    public void disconnectedLiveSessionIsResumable() {
        assertEquals(PlayniteSessionPresentation.State.RESUME_ACTIVE,
                PlayniteSessionPresentation.resolve(true, false,
                        "hollow", 42, "", 0, 0L, 42, "hollow"));
    }

    @Test
    public void resumedStoredSessionRemainsResumableWhileSunshineRunsIt() {
        assertEquals(PlayniteSessionPresentation.State.RESUME_ACTIVE,
                PlayniteSessionPresentation.resolve(true, false,
                        "hollow", 42, "hollow", 42, 123L, 42, ""));
    }

    @Test
    public void terminatedSessionTombstoneSuppressesStaleHostSnapshot() {
        assertEquals(PlayniteSessionPresentation.State.READY,
                PlayniteSessionPresentation.resolve(true, true,
                        "hollow", 42, "hollow", 42, 0L, 42, "hollow"));
    }

    @Test
    public void storedGameIdDoesNotMarkOtherTilesSharingLauncherApp() {
        assertEquals(PlayniteSessionPresentation.State.READY,
                PlayniteSessionPresentation.resolve(true, false,
                        "cuphead", 42, "hollow", 42, 0L, 0, ""));
    }
}
