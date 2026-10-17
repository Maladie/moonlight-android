package com.limelight.console;

import com.limelight.SourceText;
import com.limelight.console.transition.LaunchTransitionState;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.limelight.SourceText.assertOrdered;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** "Recently played" is written once the host confirmed the game ready or the user revealed it. */
public class PlayHistoryTest {
    private static boolean confirmed(LaunchTransitionState state, String gameId,
                                     boolean hiddenWarmUp) {
        return PlayHistory.gamePlayed(state, false, gameId, hiddenWarmUp);
    }

    @Test public void withoutAManualRevealOnlyAHostConfirmedReadyGameIsPlayed() {
        for (LaunchTransitionState state : LaunchTransitionState.values()) {
            assertEquals(state.name(), state == LaunchTransitionState.GAME_READY,
                    confirmed(state, "game", false));
        }
    }

    @Test public void aManuallyRevealedGameIsPlayedWhateverTheHostConfirmed() {
        // Including a readiness that timed out or failed before "Reveal now".
        for (LaunchTransitionState state : LaunchTransitionState.values()) {
            assertTrue(state.name(), PlayHistory.gamePlayed(state, true, "game", false));
            // Never a neutral stream and never the hidden warm-up.
            assertFalse(state.name(), PlayHistory.gamePlayed(state, true, "", false));
            assertFalse(state.name(), PlayHistory.gamePlayed(state, true, null, false));
            assertFalse(state.name(), PlayHistory.gamePlayed(state, true, "game", true));
        }
    }

    @Test public void oneTransitionIsRecordedOnce() {
        assertTrue(PlayHistory.recordsNow("", "t1", true));
        assertFalse("already recorded", PlayHistory.recordsNow("t1", "t1", true));
        assertTrue("a new transition", PlayHistory.recordsNow("t1", "t2", true));
        assertFalse("not played", PlayHistory.recordsNow("", "t1", false));
    }

    @Test public void retryOfferRefusalAndAcceptedRequestAreNotPlayed() {
        // The offered Retry and every failed start present ERROR or TIMED_OUT.
        assertFalse(confirmed(LaunchTransitionState.ERROR, "game", false));
        assertFalse(confirmed(
                LaunchTransitionState.TIMED_OUT, "game", false));
        // INTERACTION_REQUIRED refusals.
        assertFalse(confirmed(
                LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED, "game", false));
        assertFalse(confirmed(
                LaunchTransitionState.HOST_SESSION_LOCKED, "game", false));
        // The host only accepted the start request; the game is still starting.
        assertFalse(confirmed(
                LaunchTransitionState.GAME_START_REQUESTED, "game", false));
        assertFalse(confirmed(
                LaunchTransitionState.GAME_STARTING, "game", false));
        assertFalse(confirmed(
                LaunchTransitionState.GAME_PROCESS_RUNNING, "game", false));
    }

    @Test public void neutralStreamAndHiddenWarmUpAreNeverPlayed() {
        assertFalse(confirmed(LaunchTransitionState.GAME_READY, "", false));
        assertFalse(confirmed(LaunchTransitionState.GAME_READY, null, false));
        assertFalse(confirmed(
                LaunchTransitionState.GAME_READY, "game", true));
    }

    @Test public void theStreamScreenIsTheOnlyGameHistoryWriter() throws IOException {
        String game = source("com/limelight/Game.java");
        String console = source("com/limelight/console/ConsoleActivity.java");
        String record = game.substring(game.indexOf("private void recordGamePlayed("),
                game.indexOf("static boolean shouldShowTransitionOverlay("));
        String reuse = game.substring(game.indexOf("private void signalRetainedSwitchReuse("),
                game.indexOf("private void finishRetainedSwitch("));

        assertEquals(1, game.split("PlayHistory\\.recordGame\\(", -1).length - 1);
        assertTrue(record.contains("PlayHistory.recordsNow(gamePlayedTransitionId, "
                + "snapshot.spec.id,"));
        assertTrue(record.contains(
                "PlayHistory.gamePlayed(snapshot.state, snapshot.manuallyRevealed,"));
        assertTrue(record.contains("isOwnedHiddenAutoWarmUp()"));
        assertOrdered(record, "PlayHistory.recordsNow(",
                "gamePlayedTransitionId = snapshot.spec.id;");
        assertOrdered(record, "gamePlayedTransitionId = snapshot.spec.id;",
                "PlayHistory.recordGame(");
        // The reuse signal (retry offer, refused or accepted start) carries no history.
        assertFalse(reuse.contains("PlayHistory"));
        assertFalse(console.contains("PlayHistory.recordGame("));
        assertFalse(console.contains("\"played_at."));
        assertFalse(game.contains("\"played_at."));
    }

    private static String source(String relative) throws IOException {
        Path path = Paths.get("src/main/java/" + relative);
        return SourceText.read(path);
    }
}
