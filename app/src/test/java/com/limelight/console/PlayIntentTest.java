package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PlayIntentTest {
    @Test public void preservesRoutableHostIdentityAndNormalizesComparisons() {
        PlayIntent intent = PlayIntent.playniteGame(
                " HOST ", 42, "Game", false, " GAME-ID ", " ART ");

        assertTrue(intent.matches(snapshot("host", 7, "game-id")));
        assertTrue("HOST".equals(intent.hostId));
        assertTrue("GAME-ID".equals(intent.playniteGameId));
        assertTrue("ART".equals(intent.loadingArtworkGameId));
    }

    @Test public void rejectsInvalidTargets() {
        assertThrows(IllegalArgumentException.class,
                () -> PlayIntent.sunshineApp("", 42, "App", false, ""));
        assertThrows(IllegalArgumentException.class,
                () -> PlayIntent.playniteGame("host", 42, "App", false, " ", ""));
        assertThrows(IllegalArgumentException.class,
                () -> PlayIntent.playniteFullscreen("host", 0, "App", false));
    }

    @Test public void playniteGameRequiresExactGameNotSharedSunshineTarget() {
        PlayIntent intent = PlayIntent.playniteGame(
                "host", 42, "Game A", false, "game-a", "");

        assertTrue(intent.matches(snapshot("host", 42, "game-a")));
        assertFalse(intent.matches(snapshot("host", 42, "game-b")));
        assertFalse(intent.matches(snapshot("other", 42, "game-a")));
    }

    @Test public void playniteGameMayDeferMissingSunshineTargetToPreflight() {
        PlayIntent intent = PlayIntent.playniteGame(
                "host", 0, "Game", false, "game", "game");

        assertTrue(intent.sunshineAppId == 0);
    }

    @Test public void directSunshineTargetMatchesDespitePlayniteIdentity() {
        PlayIntent intent = PlayIntent.sunshineApp("host", 42, "App", false, "");

        assertTrue(intent.matches(snapshot("host", 42, "game-b")));
        assertFalse(intent.matches(snapshot("host", 7, "game-b")));
    }

    @Test public void directSunshineTargetMayRetainLoadingArtworkIdentity() {
        PlayIntent intent = PlayIntent.sunshineApp(
                "host", 42, "App", false, "", " GAME-B ");

        assertTrue("GAME-B".equals(intent.loadingArtworkGameId));
        assertTrue(intent.playniteGameId.isEmpty());
        assertTrue(intent.matches(snapshot("host", 42, "different-game")));
    }

    @Test public void genericGameConnectionRetainsProviderIdentity() {
        PlayIntent game = PlayIntent.playniteGame(
                "host", 42, "Baba Is You", false,
                "steam:736260", "steam:736260");
        PlayIntent desktop = PlayIntent.sunshineApp(
                "host", 42, "Desktop", false, "");

        assertEquals("steam:736260", game.transitionGameId());
        assertEquals(LaunchTransitionType.GAME_CONNECTION,
                game.transitionType(LaunchTransitionType.GENERIC));
        assertEquals("", desktop.transitionGameId());
        assertEquals(LaunchTransitionType.GENERIC,
                desktop.transitionType(LaunchTransitionType.GENERIC));
    }

    @Test public void fullscreenDoesNotMatchIdentifiedPlayniteGame() {
        PlayIntent intent = PlayIntent.playniteFullscreen("host", 42, "Playnite", false);

        assertTrue(intent.matches(snapshot("host", 42, "")));
        assertFalse(intent.matches(snapshot("host", 42, "game-b")));
    }

    private static SessionSnapshot snapshot(String host, int appId, String gameId) {
        return new SessionSnapshot(host, SessionSnapshot.State.ACTIVE, appId, gameId,
                false, false, false, false, false);
    }
}
