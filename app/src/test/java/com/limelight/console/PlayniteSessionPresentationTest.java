package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayniteSessionPresentationTest {
    @Test public void activeAndReconnectProjectAsResumeActive() {
        PlayniteDashboardItem game = item("game", 42);
        for (SessionSnapshot.State state : Arrays.asList(
                SessionSnapshot.State.ACTIVE,
                SessionSnapshot.State.RECONNECT_REQUIRED)) {
            PlayniteSessionPresentation.Projection projection =
                    PlayniteSessionPresentation.project(
                            snapshot(state, 42, "game"),
                            Collections.singletonList(game), "");
            assertEquals(PlayniteSessionPresentation.State.RESUME_ACTIVE,
                    projection.stateFor("game"));
        }
    }

    @Test public void suspendedProjectsAsResumeSuspended() {
        PlayniteSessionPresentation.Projection projection =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.SUSPENDED, 42, "game"),
                        Collections.singletonList(item("game", 42)), "");

        assertEquals(PlayniteSessionPresentation.State.RESUME_SUSPENDED,
                projection.stateFor("game"));
    }

    @Test public void noneTerminatingAndUncertainProjectAsReady() {
        for (SessionSnapshot.State state : Arrays.asList(
                SessionSnapshot.State.NONE, SessionSnapshot.State.TERMINATING,
                SessionSnapshot.State.UNCERTAIN)) {
            PlayniteSessionPresentation.Projection projection =
                    PlayniteSessionPresentation.project(snapshot(state, 42, "game"),
                            Collections.singletonList(item("game", 42)), "");
            assertEquals(PlayniteSessionPresentation.State.READY,
                    projection.stateFor("game"));
        }
    }

    @Test public void exactPlayniteIdWinsOverSharedSunshineTarget() {
        List<PlayniteDashboardItem> items = Arrays.asList(
                item("game-a", 42), item("game-b", 42));
        PlayniteSessionPresentation.Projection projection =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.ACTIVE, 42, "game-b"),
                        items, "game-a");

        assertEquals(PlayniteSessionPresentation.State.READY,
                projection.stateFor("game-a"));
        assertEquals(PlayniteSessionPresentation.State.RESUME_ACTIVE,
                projection.stateFor("game-b"));
    }

    @Test public void missingExactPlayniteIdNeverFallsBackToSharedTarget() {
        PlayniteSessionPresentation.Projection projection =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.ACTIVE, 42, "missing"),
                        Arrays.asList(item("game-a", 42), item("game-b", 42)),
                        "game-a");

        assertEquals("", projection.resumeGameId);
    }

    @Test public void uniqueSunshineAppIdIsUsedAsFallback() {
        PlayniteSessionPresentation.Projection projection =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.ACTIVE, 42, ""),
                        Arrays.asList(item("game-a", 7), item("game-b", 42)), "");

        assertEquals("game-b", projection.resumeGameId);
    }

    @Test public void previousSelectionDoesNotGuessSharedSunshineTarget() {
        PlayniteSessionPresentation.Projection projection =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.ACTIVE, 42, ""),
                        Arrays.asList(item("game-a", 42), item("game-b", 42)),
                        "GAME-B");

        assertEquals("", projection.resumeGameId);
    }

    @Test public void ambiguousSharedSunshineTargetWithoutSelectionStaysUnselected() {
        PlayniteSessionPresentation.Projection projection =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.ACTIVE, 42, ""),
                        Arrays.asList(item("game-a", 42), item("game-b", 42)), "other");

        assertEquals("", projection.resumeGameId);
    }

    @Test public void neutralTargetWithoutGameNeverProjectsResume() {
        for (SessionSnapshot.State state : Arrays.asList(
                SessionSnapshot.State.ACTIVE,
                SessionSnapshot.State.RECONNECT_REQUIRED)) {
            PlayniteSessionPresentation.Projection projection =
                    PlayniteSessionPresentation.project(
                            snapshot(state, 42, "", true),
                            Collections.singletonList(item("game", 42)), "");
            assertEquals(PlayniteSessionPresentation.State.READY,
                    projection.stateFor("game"));
            assertEquals("", projection.resumeGameId);
        }
    }

    @Test public void neutralTargetWithRealGameStillProjectsResume() {
        PlayniteSessionPresentation.Projection projection =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.ACTIVE, 42, "game", true),
                        Collections.singletonList(item("game", 42)), "");

        assertEquals(PlayniteSessionPresentation.State.RESUME_ACTIVE,
                projection.stateFor("game"));
    }

    @Test public void neutralWarmUpWithoutGameDoesNotInvalidateCardPresentation() {
        PlayniteDashboardItem game = item("game", 42);
        PlayniteSessionPresentation.Projection idle =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.NONE, 0, ""),
                        Collections.singletonList(game), "");
        PlayniteSessionPresentation.Projection preparing =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.PREPARING, 42, "", true),
                        Collections.singletonList(game), "");
        PlayniteSessionPresentation.Projection active =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.ACTIVE, 42, "", true),
                        Collections.singletonList(game), "");

        assertEquals(idle.signature(), preparing.signature());
        assertEquals(idle.signature(), active.signature());
    }

    @Test public void signatureChangesOnlyWhenTheVisibleResumeStateChanges() {
        PlayniteDashboardItem game = item("game", 42);
        PlayniteSessionPresentation.Projection active =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.ACTIVE, 42, "game"),
                        Collections.singletonList(game), "");
        PlayniteSessionPresentation.Projection reconnect =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.RECONNECT_REQUIRED, 42, "game"),
                        Collections.singletonList(game), "");
        PlayniteSessionPresentation.Projection suspended =
                PlayniteSessionPresentation.project(
                        snapshot(SessionSnapshot.State.SUSPENDED, 42, "game"),
                        Collections.singletonList(game), "");

        assertEquals(active.signature(), reconnect.signature());
        org.junit.Assert.assertNotEquals(active.signature(), suspended.signature());
    }

    @Test public void idleNeutralObservationDoesNotInvalidateEveryGameCard() {
        assertFalse(ConsoleActivity.runningGamePresentationChanged(
                null, null, null, "idle", true));
        assertFalse(ConsoleActivity.runningGamePresentationChanged(
                "", "", "unknown", "idle", false));
        assertTrue(ConsoleActivity.runningGamePresentationChanged(
                null, "game", "idle", "running", false));
        assertTrue(ConsoleActivity.runningGamePresentationChanged(
                "game", null, "running", "idle", false));
        assertTrue(ConsoleActivity.runningGamePresentationChanged(
                "game", "game", "running", "running", true));
    }

    private static SessionSnapshot snapshot(SessionSnapshot.State state, int appId,
                                            String gameId) {
        return snapshot(state, appId, gameId, false);
    }

    private static SessionSnapshot snapshot(SessionSnapshot.State state, int appId,
                                            String gameId, boolean neutralStreamTarget) {
        return new SessionSnapshot("host", state, appId, gameId,
                state == SessionSnapshot.State.ACTIVE, false, false, false, false,
                "", neutralStreamTarget);
    }

    private static PlayniteDashboardItem item(String gameId, int appId) {
        PlayniteLibraryGame game = new PlayniteLibraryGame(gameId, gameId, true,
                false, 0L, "", "", "", "Steam");
        return new PlayniteDashboardItem(game, appId, "MoonWaker",
                PlayniteDashboardItem.MappingState.MAPPED);
    }
}
