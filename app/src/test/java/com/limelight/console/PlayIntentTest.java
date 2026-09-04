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

    @Test public void autoWarmUpCarriesOnlyTheNeutralHostIntent() {
        PlayIntent intent = PlayIntent.autoWarmUp(" HOST ");

        assertEquals(PlayIntent.Kind.AUTO_WARM_UP, intent.kind);
        assertEquals("HOST", intent.hostId);
        assertEquals(0, intent.sunshineAppId);
        assertEquals(PlayniteTargetResolver.MOONWAKER_STREAM_NAME, intent.appName);
        assertEquals("", intent.playniteGameId);
        assertFalse(intent.matches(snapshot("host", 0, "")));
        assertEquals(LaunchTransitionType.GENERIC,
                intent.transitionType(LaunchTransitionType.GENERIC));
    }

    @Test public void playniteGameCarriesItsPerGameStreamSettingsKey() {
        PlayIntent intent = PlayIntent.playniteGame(
                "host", 42, "Game", false, "game", "game", "settings:game");

        assertEquals("settings:game", intent.quickLaunchId);
    }

    @Test public void providerLaunchTimingComesFromDeclaredCapability() {
        PlayIntent intent = PlayIntent.playniteGame(
                "host", 42, "GOG Game", false, "gog:Some_Game", "gog:Some_Game",
                "settings:gog", false, true, true);

        assertTrue(intent.startBeforeStream);
        assertFalse(intent.requiresConnector);
        assertTrue(intent.neutralStream);
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

    @Test public void calibrationCopyKeepsExactTargetAndAddsOnlyRuntimeSettings() {
        PlayIntent original = PlayIntent.playniteGame(
                "host", 42, "Game", true, "game-id", "art", "quick");

        PlayIntent calibration = original.withCalibration(
                "settings:game", 1920, 1080, 60, 20_000);

        assertTrue(calibration.isCalibration());
        assertEquals(original.kind, calibration.kind);
        assertEquals(original.sunshineAppId, calibration.sunshineAppId);
        assertEquals(original.playniteGameId, calibration.playniteGameId);
        assertEquals(original.quickLaunchId, calibration.quickLaunchId);
        assertEquals("settings:game", calibration.calibrationAppKey);
        assertEquals(1920, calibration.runtimeWidth);
        assertEquals(1080, calibration.runtimeHeight);
        assertEquals(60, calibration.runtimeFps);
        assertEquals(20_000, calibration.runtimeBitrateKbps);
    }

    @Test public void profileIsImmutableAndRequiredForSessionMatch() {
        PlayIntent intent = PlayIntent.playniteGame(
                "host", "Basia", 42, "Game", false, "game", "game");
        PlayIntent calibrated = intent.withCalibration(
                "settings:game", 1920, 1080, 60, 20_000);

        assertEquals("Basia", intent.profileId);
        assertEquals(intent.profileKey, calibrated.profileKey);
        assertTrue(intent.matches(snapshot("host", "Basia", 42, "game")));
        assertFalse(intent.matches(snapshot("host", "Gry", 42, "game")));
    }

    private static SessionSnapshot snapshot(String host, int appId, String gameId) {
        return new SessionSnapshot(host, SessionSnapshot.State.ACTIVE, appId, gameId,
                false, false, false, false, false);
    }

    private static SessionSnapshot snapshot(String host, String profileId,
                                            int appId, String gameId) {
        return new SessionSnapshot(host, profileId, SessionSnapshot.State.ACTIVE,
                appId, gameId, false, false, false, false, false);
    }
}
