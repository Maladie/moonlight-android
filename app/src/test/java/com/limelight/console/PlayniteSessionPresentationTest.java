package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

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

    @Test public void noneAndTerminatingProjectAsReady() {
        for (SessionSnapshot.State state : Arrays.asList(
                SessionSnapshot.State.NONE, SessionSnapshot.State.TERMINATING)) {
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

    private static SessionSnapshot snapshot(SessionSnapshot.State state, int appId,
                                            String gameId) {
        return new SessionSnapshot("host", state, appId, gameId,
                state == SessionSnapshot.State.ACTIVE, false, false, false, false);
    }

    private static PlayniteDashboardItem item(String gameId, int appId) {
        PlayniteLibraryGame game = new PlayniteLibraryGame(gameId, gameId, true,
                false, 0L, "", "", "", "Steam");
        return new PlayniteDashboardItem(game, appId, "MoonWaker",
                PlayniteDashboardItem.MappingState.MAPPED);
    }
}
