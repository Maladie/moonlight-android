package com.limelight.console;

import com.limelight.console.SessionOrchestrator.Action;
import com.limelight.console.SessionOrchestrator.ActionKind;
import com.limelight.console.SessionOrchestrator.State;
import com.limelight.console.SessionOrchestrator.UserIntent;
import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.session.SessionModel;
import com.limelight.session.SessionModelDriver;
import com.limelight.session.SessionResumeManager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** One row per line of the table: session state x user intent -> action. */
public class SessionTransitionTableTest {
    // ---------------------------------------------------------------- Cancel on the cover

    @Test public void cancelWithoutAPresentedTransitionOrDuringACancelIsIgnored() {
        State state = reconnectToGame();
        state.transitionActive = false;
        assertEquals(ActionKind.IGNORE, cancel(state));
    }

    @Test public void cancelledReconnectToARunningGameKeepsTheGameAndItsHostStream() {
        // Before and after the host granted the reconnect, and whatever was revealed.
        for (boolean granted : new boolean[] {false, true}) {
            for (boolean revealed : new boolean[] {false, true}) {
                State state = reconnectToGame();
                state.controlGranted = granted;
                state.revealed = revealed;
                state.gameReadyConfirmed = revealed;
                assertEquals(ActionKind.STOP_TRANSPORT_KEEP_SESSION, cancel(state));
            }
        }
    }

    @Test public void cancelledUnrevealedDesktopClosesItsHostStreamAlsoAfterTheAcquire() {
        for (LaunchTransitionType type : new LaunchTransitionType[] {
                LaunchTransitionType.GENERIC, LaunchTransitionType.PLAYNITE,
                LaunchTransitionType.GAME_CONNECTION}) {
            for (boolean granted : new boolean[] {false, true}) {
                State state = covered(type, false);
                state.controlGranted = granted;
                state.connected = granted;
                assertEquals(type + " granted=" + granted,
                        ActionKind.STOP_TRANSPORT_CLOSE_HOST_STREAM, cancel(state));
            }
        }
    }

    @Test public void cancelledGameStartBeforeTheGrantLeavesTheCloseToTheStoppedTransport() {
        State state = covered(LaunchTransitionType.GAME, true);
        assertEquals(ActionKind.STOP_TRANSPORT_CLOSE_HOST_STREAM, cancel(state));
    }

    @Test public void cancelledGameStartClosesTheSessionUntilTheHostConfirmedTheGameReady() {
        // Unrevealed, or only manually revealed: the whole session closes.
        for (boolean[] row : new boolean[][] {{false, false}, {true, false}, {false, true}}) {
            State state = covered(LaunchTransitionType.GAME, true);
            state.controlGranted = true;
            state.revealed = row[0];
            state.gameReadyConfirmed = row[1];
            assertEquals(ActionKind.CLOSE_STARTED_SESSION, cancel(state));
        }
    }

    @Test public void cancelAfterARevealOnlyStopsTheTransport() {
        State started = covered(LaunchTransitionType.GAME, true);
        started.controlGranted = true;
        started.revealed = true;
        started.gameReadyConfirmed = true;
        assertEquals(ActionKind.STOP_TRANSPORT, cancel(started));

        State desktop = covered(LaunchTransitionType.GENERIC, false);
        desktop.controlGranted = true;
        desktop.revealed = true;
        assertEquals(ActionKind.STOP_TRANSPORT, cancel(desktop));
    }

    @Test public void cancelOnAUsedNeutralStreamSettlingToTheDashboardKeepsItsSessionRecord() {
        // Its reveal fact was reset with the game that ended; the stream itself was used and
        // stays paused on the host, so the next start must still see it.
        State state = covered(LaunchTransitionType.GAME_CONNECTION, false);
        state.connected = true;
        state.controlGranted = true;
        state.returningToDashboard = true;
        assertEquals(ActionKind.STOP_TRANSPORT_KEEP_SESSION, cancel(state));
    }

    @Test public void cancelOnAFailedStartObservationClosesTheWholeSession() {
        State state = reconnectToGame();
        state.failedStartObservation = true;
        state.retainedHomeLive = true;
        state.connected = true;
        assertEquals(ActionKind.CLOSE_HOST_SESSION, cancel(state));
    }

    @Test public void cancelOnALiveRetainedObservationShowsHomeAndStopsNothing() {
        State state = reconnectToGame();
        state.connected = true;
        state.retainedHomeLive = true;
        assertEquals(ActionKind.COVER_AND_SHOW_HOME, cancel(state));
        // Also while a retained switch is recorded: the live stream wins.
        state.retainedSwitch = true;
        assertEquals(ActionKind.COVER_AND_SHOW_HOME, cancel(state));

        state.parked = true;
        assertEquals(ActionKind.CANCEL_RETAINED_SWITCH, cancel(state));
        state.parked = false;
        state.homeVisible = true;
        assertEquals(ActionKind.CANCEL_RETAINED_SWITCH, cancel(state));
        state.homeVisible = false;
        state.retainedHomeLive = false;
        assertEquals(ActionKind.CANCEL_RETAINED_SWITCH, cancel(state));
    }

    @Test public void cancelOfARetainedSwitchNeverStopsTheTransport() {
        for (LaunchTransitionType type : LaunchTransitionType.values()) {
            State state = covered(type, true);
            state.connected = true;
            state.controlGranted = true;
            state.retainedSwitch = true;
            assertEquals(ActionKind.CANCEL_RETAINED_SWITCH, cancel(state));
        }
    }

    @Test public void cancelOnAnOfferedAuthorizationRetryDeclinesItFirst() {
        State state = reconnectToGame();
        state.retainedSwitch = true;
        state.authorizationRetryOffered = true;
        state.failedStartObservation = true;
        assertEquals(ActionKind.DECLINE_AUTHORIZATION_RETRY, cancel(state));
    }

    /** The TV state: an authorization retry offered over the live neutral warm-up stream. */
    @Test public void offeredAuthorizationRetryOverTheNeutralStreamAlwaysHasAWayOut() {
        State state = covered(LaunchTransitionType.GAME_CONNECTION, false);
        state.connected = true;
        state.controlGranted = true;
        state.retainedSwitch = true;
        state.authorizationRetryOffered = true;
        state.retainedHomeLive = true;
        assertEquals(ActionKind.CANCEL_ON_COVER, back(state));
        assertEquals(ActionKind.DECLINE_AUTHORIZATION_RETRY, cancel(state));
        assertEquals(ActionKind.ACCEPT_AUTHORIZATION_RETRY, retry(state));
    }

    // ---------------------------------------------------------------- Back

    @Test public void backOnTheCoverIsTheCoversCancel() {
        assertEquals(ActionKind.CANCEL_ON_COVER, back(reconnectToGame()));
        assertEquals(ActionKind.CANCEL_ON_COVER,
                back(covered(LaunchTransitionType.GENERIC, false)));
    }

    @Test public void backOnATimedOutRevealedStreamShowsHomeEvenUnderTheCover() {
        State state = covered(LaunchTransitionType.GAME, true);
        state.connected = true;
        state.revealed = true;
        state.timedOut = true;
        assertEquals(ActionKind.COVER_AND_SHOW_HOME, back(state));
        state.covered = false;
        assertEquals(ActionKind.COVER_AND_SHOW_HOME, back(state));

        state.revealed = false;
        state.covered = true;
        assertEquals(ActionKind.CANCEL_ON_COVER, back(state));
        state.revealed = true;
        state.connected = false;
        assertEquals(ActionKind.CANCEL_ON_COVER, back(state));
        state.connected = true;
        state.homeVisible = true;
        assertEquals(ActionKind.CANCEL_ON_COVER, back(state));
    }

    @Test public void backOnARevealedLiveStreamShowsHomeOverIt() {
        State state = new State();
        state.type = LaunchTransitionType.GAME;
        state.hasGame = true;
        state.connected = true;
        state.revealed = true;
        assertEquals(ActionKind.SHOW_HOME, back(state));
        // A stream without any console transition behaves the same.
        State plain = new State();
        plain.connected = true;
        assertEquals(ActionKind.SHOW_HOME, back(plain));
    }

    @Test public void backOnAnUncoveredLiveRetainedObservationCoversFirst() {
        State state = reconnectToGame();
        state.covered = false;
        state.connected = true;
        state.retainedHomeLive = true;
        assertEquals(ActionKind.COVER_AND_SHOW_HOME, back(state));
    }

    @Test public void backWithNothingLiveOrUnderTheHomeLeavesTheScreen() {
        State disconnected = new State();
        assertEquals(ActionKind.LEAVE_SCREEN, back(disconnected));
        State underHome = new State();
        underHome.connected = true;
        underHome.homeVisible = true;
        assertEquals(ActionKind.LEAVE_SCREEN, back(underHome));
    }

    // ---------------------------------------------------------------- Retry

    @Test public void retryWithoutAPresentedTransitionOrDuringACancelIsIgnored() {
        State state = failedStart();
        state.transitionActive = false;
        assertEquals(ActionKind.IGNORE, retry(state));
    }

    @Test public void repeatedRetryOfTheSameTransitionIsIgnored() {
        State start = failedStart();
        start.controlGranted = true;
        start.retryStarted = true;
        assertEquals(ActionKind.IGNORE, retry(start));

        State gone = failedStart();
        gone.connected = false;
        gone.retryStarted = true;
        assertEquals(ActionKind.IGNORE, retry(gone));
    }

    @Test public void retryNeverCoversAStreamTheUserRevealed() {
        for (LaunchTransitionType type : LaunchTransitionType.values()) {
            State state = covered(type, true);
            state.connected = true;
            state.covered = false;
            state.revealed = true;
            assertEquals(ActionKind.IGNORE, retry(state));
        }
        // "Reveal now" was chosen and its reveal still runs: the cover is already given up.
        State revealing = failedStart();
        revealing.manuallyRevealed = true;
        assertEquals(ActionKind.IGNORE, retry(revealing));
        revealing.connected = false;
        assertEquals(ActionKind.IGNORE, retry(revealing));
    }

    @Test public void retryWithoutALiveTransportStartsTheStreamAgain() {
        for (LaunchTransitionType type : LaunchTransitionType.values()) {
            State state = covered(type, type != LaunchTransitionType.GENERIC);
            assertEquals(type.name(), ActionKind.RESTART_STREAM, retry(state));
        }
        // Whatever a retained switch recorded: nothing lives to retry on.
        State offered = failedStart();
        offered.connected = false;
        offered.retainedSwitch = true;
        offered.retainedSwitchAttempted = true;
        offered.authorizationRetryOffered = true;
        assertEquals(ActionKind.RESTART_STREAM, retry(offered));
    }

    @Test public void retryOfAFailedGameStartStartsTheGameAgainOnTheLiveStream() {
        State state = failedStart();
        assertEquals(ActionKind.RETRY_GAME_START, retry(state));
        // Granted or not, retained as home-live or not: the stream stays.
        state.controlGranted = true;
        state.retainedHomeLive = true;
        assertEquals(ActionKind.RETRY_GAME_START, retry(state));
    }

    @Test public void retryAfterTheGameWasConfirmedReadyOnlyObservesAgain() {
        State state = failedStart();
        state.gameReadyConfirmed = true;
        assertEquals(ActionKind.RETRY_OBSERVATION, retry(state));
    }

    @Test public void retryNeverStopsAGameWhoseStartTheHostDidNotReportFailed() {
        // Readiness timed out while the game runs (its window is not in the foreground):
        // no cleanup was ordered, so the running game is only observed again.
        State running = failedStart();
        running.failedStartCleanup = false;
        running.timedOut = true;
        assertEquals(ActionKind.RETRY_OBSERVATION, retry(running));
        running.controlGranted = true;
        running.retainedHomeLive = true;
        assertEquals(ActionKind.RETRY_OBSERVATION, retry(running));
        // A refused start (launcher interaction, locked host) ordered no cleanup either.
        State refused = failedStart();
        refused.failedStartCleanup = false;
        assertEquals(ActionKind.RETRY_OBSERVATION, retry(refused));

        // A timeout whose cleanup was ordered (the stream never set up) is a failed start.
        State failed = failedStart();
        failed.timedOut = true;
        assertEquals(ActionKind.RETRY_GAME_START, retry(failed));
    }

    @Test public void retryOfAFailedConnectionOrObservationObservesAgainOnTheLiveStream() {
        for (LaunchTransitionType type : new LaunchTransitionType[] {
                LaunchTransitionType.GAME_CONNECTION, LaunchTransitionType.PLAYNITE,
                LaunchTransitionType.GENERIC}) {
            State state = covered(type, type == LaunchTransitionType.GAME_CONNECTION);
            state.connected = true;
            assertEquals(type.name(), ActionKind.RETRY_OBSERVATION, retry(state));
        }
        // The observation kept after a failed start whose game could not be stopped.
        State kept = reconnectToGame();
        kept.connected = true;
        kept.failedStartObservation = true;
        kept.retainedHomeLive = true;
        assertEquals(ActionKind.RETRY_OBSERVATION, retry(kept));
        // A GAME transition without a game has no start to repeat.
        State noGame = covered(LaunchTransitionType.GAME, false);
        noGame.connected = true;
        assertEquals(ActionKind.RETRY_OBSERVATION, retry(noGame));
    }

    @Test public void retryOnAnOfferedAuthorizationRetryAcceptsIt() {
        State state = reconnectToGame();
        state.connected = true;
        state.retainedSwitch = true;
        state.authorizationRetryOffered = true;
        assertEquals(ActionKind.ACCEPT_AUTHORIZATION_RETRY, retry(state));
    }

    @Test public void retryDuringARetainedSwitchRestartsItsAttemptOrWaitsForIt() {
        State state = failedStart();
        state.retainedSwitch = true;
        state.retainedSwitchAttempted = true;
        assertEquals(ActionKind.RETRY_RETAINED_SWITCH, retry(state));
        // Also when the attempt only observes: the switch owns its start.
        state.type = LaunchTransitionType.GAME_CONNECTION;
        assertEquals(ActionKind.RETRY_RETAINED_SWITCH, retry(state));

        state.retainedSwitchAttempted = false;
        assertEquals(ActionKind.IGNORE, retry(state));
    }

    @Test public void noRetryOnALiveTransportStopsOrRestartsTheStream() {
        for (LaunchTransitionType type : LaunchTransitionType.values()) {
            for (int facts = 0; facts < 64; facts++) {
                State state = covered(type, (facts & 1) != 0);
                state.connected = true;
                state.gameReadyConfirmed = (facts & 2) != 0;
                state.retainedSwitch = (facts & 4) != 0;
                state.retainedSwitchAttempted = state.retainedSwitch && (facts & 8) != 0;
                state.authorizationRetryOffered = state.retainedSwitch && (facts & 16) != 0;
                state.failedStartCleanup = (facts & 32) != 0;
                ActionKind decided = retry(state);
                assertTrue(type + " facts=" + facts + " -> " + decided,
                        decided == ActionKind.IGNORE
                                || decided == ActionKind.ACCEPT_AUTHORIZATION_RETRY
                                || decided == ActionKind.RETRY_RETAINED_SWITCH
                                || decided == ActionKind.RETRY_GAME_START
                                || decided == ActionKind.RETRY_OBSERVATION);
            }
        }
    }

    // ---------------------------------------------------------------- Play

    @Test public void playWhileASessionClosesIsRefused() {
        Action toast = play(snapshot(SessionSnapshot.State.TERMINATING, 42, "game"), game(),
                false, false, false);
        assertEquals(ActionKind.REFUSE_CLOSING, toast.kind);
        assertNull(toast.failure);
        Action covered = play(snapshot(SessionSnapshot.State.TERMINATING, 42, "game"), game(),
                true, false, false);
        assertEquals(SessionFailure.UNCERTAIN_TERMINATING, covered.failure);
    }

    @Test public void playWithNoSessionCoversPreflightsThenLaunchesWhenStillIdle() {
        SessionSnapshot none = snapshot(SessionSnapshot.State.NONE, 0, "");
        Action loading = play(none, game(), false, false, false);
        assertEquals(ActionKind.SHOW_LOADING, loading.kind);
        assertEquals(LaunchTransitionType.GAME, loading.type);
        Action preflight = play(none, game(), true, false, false);
        assertEquals(ActionKind.PREFLIGHT, preflight.kind);
        assertEquals(HostLaunchPreflight.Action.LAUNCH, preflight.preflight);
        Action launch = play(none, game(), true, true, false);
        assertEquals(ActionKind.LAUNCH_IF_IDLE, launch.kind);
        assertEquals(LaunchTransitionType.GAME, launch.type);

        assertEquals(LaunchTransitionType.GENERIC, play(none, desktop(), false, false,
                false).type);
    }

    @Test public void playOfTheLiveRetainedGameReturnsToItsStream() {
        SessionSnapshot live = snapshot(SessionSnapshot.State.ACTIVE, 42, "game", true, false);
        assertEquals(ActionKind.RETURN_TO_STREAM,
                play(live, game(), false, false, false).kind);
    }

    @Test public void playOfTheRunningGameWithoutATransportConnectsToIt() {
        for (SessionSnapshot.State state : new SessionSnapshot.State[] {
                SessionSnapshot.State.ACTIVE, SessionSnapshot.State.SUSPENDED}) {
            SessionSnapshot running = snapshot(state, 42, "game");
            assertEquals(LaunchTransitionType.GENERIC,
                    play(running, game(), false, false, false).type);
            assertEquals(HostLaunchPreflight.Action.LAUNCH,
                    play(running, game(), true, false, false).preflight);
            Action launch = play(running, game(), true, true, false);
            assertEquals(ActionKind.LAUNCH, launch.kind);
            assertEquals(LaunchTransitionType.GENERIC, launch.type);
            assertFalse(launch.ownsFreshSunshineSession);
        }
    }

    @Test public void playOfTheSavedGameReconnects() {
        SessionSnapshot saved = snapshot(SessionSnapshot.State.RECONNECT_REQUIRED, 42, "game");
        assertEquals(ActionKind.SHOW_LOADING, play(saved, game(), false, false, false).kind);
        assertEquals(HostLaunchPreflight.Action.RECONNECT,
                play(saved, game(), true, false, false).preflight);
        assertEquals(ActionKind.RECONNECT, play(saved, game(), true, true, false).kind);
    }

    @Test public void playOfAnotherGameAsksBeforeReplacingUnlessTheStreamCanBeReused() {
        SessionSnapshot other = snapshot(SessionSnapshot.State.ACTIVE, 42, "other");
        assertEquals(ActionKind.CONFIRM_REPLACEMENT,
                play(other, game(), false, false, false).kind);

        State authorized = playState(other, true, true, false);
        authorized.replacementAuthorized = true;
        Action close = SessionOrchestrator.transition(authorized, UserIntent.play(game()));
        assertEquals(ActionKind.CLOSE_COMPETING, close.kind);
        assertTrue(close.replacementAuthorized);
        assertFalse(close.reuseOnly);

        State reusable = playState(other, true, false, false);
        reusable.canSwitchRetained = true;
        Action preflight = SessionOrchestrator.transition(reusable, UserIntent.play(game()));
        assertEquals(HostLaunchPreflight.Action.SWITCH_RETAINED, preflight.preflight);
        assertTrue(preflight.reuseOnly);
        reusable.targetPrepared = true;
        Action reuse = SessionOrchestrator.transition(reusable, UserIntent.play(game()));
        assertEquals(ActionKind.CLOSE_COMPETING, reuse.kind);
        assertFalse(reuse.replacementAuthorized);
        assertTrue(reuse.reuseOnly);
    }

    /**
     * TV 2026-10-14 13:54: a neutral warm-up stream (no game of its own) while the host runs a
     * verified game. Play of exactly that game switches (the stream adopts it); Play of another
     * one is a replacement the user confirms, never a switch that ends in switch.failed.
     */
    @Test public void neutralStreamOverARunningGameSwitchesOnlyToThatGame() {
        SessionSnapshot runsGame = verifiedRunning("game");
        State same = playState(runsGame, true, false, false);
        same.canSwitchRetained = ConsoleActivity.switchKeepsHostGame("", runsGame, "game");
        Action preflight = SessionOrchestrator.transition(same, UserIntent.play(game()));
        assertEquals(HostLaunchPreflight.Action.SWITCH_RETAINED, preflight.preflight);
        assertTrue(preflight.reuseOnly);

        SessionSnapshot runsOther = verifiedRunning("other");
        State other = playState(runsOther, true, false, false);
        other.canSwitchRetained = ConsoleActivity.switchKeepsHostGame("", runsOther, "game");
        assertEquals(ActionKind.CONFIRM_REPLACEMENT,
                SessionOrchestrator.transition(other, UserIntent.play(game())).kind);
        other.replacementAuthorized = true;
        other.targetPrepared = true;
        Action replace = SessionOrchestrator.transition(other, UserIntent.play(game()));
        assertEquals(ActionKind.CLOSE_COMPETING, replace.kind);
        assertTrue(replace.replacementAuthorized);
        assertFalse(replace.reuseOnly);

        // A stream carrying its own game still switches away from it (it stops what it owns).
        assertTrue(ConsoleActivity.switchKeepsHostGame("other", runsOther, "game"));
        assertTrue(ConsoleActivity.switchKeepsHostGame("",
                snapshot(SessionSnapshot.State.ACTIVE, 42, "", true, true), "game"));
    }

    /**
     * Review K5 round 2 (3): a neutral stream while the host names a game without process
     * proof (a stale Z, or the very game X unproven). Never a replacement naming Z (its close
     * would end whatever runs now), never a switch that can only fail: refreshed once, then the
     * uncertain refusal.
     */
    @Test public void neutralStreamOverAnUnprovenNamedGameRefreshesInsteadOfReplacing() {
        for (String named : new String[] {"stale-z", "game"}) {
            SessionSnapshot unproven = snapshot(SessionSnapshot.State.ACTIVE, 42, named,
                    true, true);
            State state = playState(unproven, true, false, false);
            state.canSwitchRetained = ConsoleActivity.switchKeepsHostGame("", unproven, "game");
            state.retainedGameUnproven = ConsoleActivity.retainedGameUnproven("", unproven);
            assertFalse(named, state.canSwitchRetained);
            assertEquals(named, ActionKind.REFRESH_OBSERVATION,
                    SessionOrchestrator.transition(state, UserIntent.play(game())).kind);
            state.observationRefreshed = true;
            Action refused = SessionOrchestrator.transition(state, UserIntent.play(game()));
            assertEquals(named, ActionKind.FAIL, refused.kind);
            assertEquals(named, SessionFailure.UNCERTAIN_AFTER_REFRESH, refused.failure);
        }
        // A stream carrying its own game, or one whose host proves the game: no refresh.
        SessionSnapshot unproven = snapshot(SessionSnapshot.State.ACTIVE, 42, "game", true, true);
        assertFalse(ConsoleActivity.retainedGameUnproven("game", unproven));
        assertFalse(ConsoleActivity.retainedGameUnproven("", verifiedRunning("other")));
    }

    /**
     * Owner 2026-10-14: a child on its parent's Windows profile may replace the parent's running
     * game. It is asked like any actor (CONFIRM_REPLACEMENT), then the session closes and the
     * child's own game starts; it never adopts the parent's game (see gamelessSwitch).
     */
    @Test public void childReplacesAParentGameAfterConfirmation() {
        SessionSnapshot parentRuns = verifiedRunning("parent-y");
        State child = playState(parentRuns, true, true, false);
        child.canSwitchRetained = ConsoleActivity.switchKeepsHostGame("", parentRuns, "game");
        assertFalse("the parent's game is never adopted", child.canSwitchRetained);
        assertEquals(ActionKind.CONFIRM_REPLACEMENT,
                SessionOrchestrator.transition(child, UserIntent.play(game())).kind);
        child.replacementAuthorized = true;
        Action close = SessionOrchestrator.transition(child, UserIntent.play(game()));
        assertEquals(ActionKind.CLOSE_COMPETING, close.kind);
        assertTrue(close.replacementAuthorized);
        assertFalse(close.reuseOnly);
    }

    private static final class NoopController implements com.limelight.session.SessionModel.Controller {
        @Override public boolean isRetainedTransportLive() { return true; }
        @Override public com.limelight.session.SessionModel.ParkOutcome parkRetainedTransport() {
            return com.limelight.session.SessionModel.ParkOutcome.PARKED;
        }
        @Override public void disconnectRetainedStream(
                com.limelight.session.SessionModel.TerminationCallback completion) { }
        @Override public void terminateRetainedSession(
                String gameId, com.limelight.session.SessionModel.TerminationCallback completion) { }
        @Override public void switchGame(com.limelight.session.SessionModel.SwitchRequest request,
                                         com.limelight.session.SessionModel.SwitchCallback completion) { }
    }

    /** The warm-up carries the game the projection proves running (TV 13:54:36 projection). */
    @Test public void warmUpCarriesOnlyAGameWithACorrelatedProcessProof() {
        assertEquals("steam:952060",
                verifiedRunning("steam:952060").verifiedRunningGameId());
        assertEquals("", snapshot(SessionSnapshot.State.ACTIVE, 42, "steam:952060", true, true)
                .verifiedRunningGameId());
    }

    @Test public void playOnAnUncertainSessionRefreshesAGameAndLaunchesAnApp() {
        SessionSnapshot uncertain = snapshot(SessionSnapshot.State.UNCERTAIN, 0, "");
        assertEquals(ActionKind.SHOW_LOADING,
                play(uncertain, game(), false, false, false).kind);
        assertEquals(ActionKind.REFRESH_SESSION,
                play(uncertain, game(), true, false, false).kind);
        assertEquals(ActionKind.PREFLIGHT, play(uncertain, desktop(), true, false, false).kind);
        Action launch = play(uncertain, desktop(), true, true, false);
        assertEquals(ActionKind.LAUNCH, launch.kind);
        assertTrue(launch.ownsFreshSunshineSession);
    }

    @Test public void playDuringAWarmUpWaitsForItBeforeAnyHostOperation() {
        SessionSnapshot preparing = snapshot(SessionSnapshot.State.PREPARING, 77, "");
        assertEquals(ActionKind.SHOW_LOADING,
                play(preparing, game(), false, false, false).kind);
        State state = playState(preparing, true, false, false);
        // Switchable or not, neutral or observing a game: nothing runs beside its start.
        for (boolean switchable : new boolean[] {false, true}) {
            state.canSwitchRetained = switchable;
            assertEquals(ActionKind.AWAIT_PREPARED,
                    SessionOrchestrator.transition(state, UserIntent.play(game())).kind);
        }
        State named = playState(snapshot(SessionSnapshot.State.PREPARING, 77, "other"),
                true, false, false);
        named.canSwitchRetained = true;
        assertEquals(ActionKind.AWAIT_PREPARED,
                SessionOrchestrator.transition(named, UserIntent.play(game())).kind);
    }

    @Test public void playOverAnUnresolvedObservationRefreshesOnceThenRefuses() {
        SessionSnapshot hole = observed(SessionSnapshot.State.ACTIVE, 42, "",
                SessionSnapshot.ObservationState.RECOVERY_REQUIRED, false, false);
        assertEquals(ActionKind.SHOW_LOADING, play(hole, game(), false, false, false).kind);
        assertEquals(ActionKind.REFRESH_OBSERVATION,
                play(hole, game(), true, false, false).kind);
        assertEquals(SessionFailure.UNCERTAIN_RECOVERY_REQUIRED,
                play(hole, game(), true, false, true).failure);
        SessionSnapshot namesOther = observed(SessionSnapshot.State.ACTIVE, 42, "other",
                SessionSnapshot.ObservationState.RECOVERY_REQUIRED, true, false);
        assertEquals(SessionFailure.UNCERTAIN_RECOVERY_REQUIRED,
                play(namesOther, game(), true, false, false).failure);
    }

    /**
     * TV 2026-10-14: a windowed RE3 never got its window proof, so its episode expired to
     * RECOVERY_REQUIRED while the host still proved its exact process. A switch stops that
     * process by its proof; the window never gates it.
     */
    @Test public void playOverAWindowedGameWithItsProcessProvenSwitchesIt() {
        SessionSnapshot windowed = new SessionSnapshot("host", "default", "default",
                SessionSnapshot.State.ACTIVE, 42, "steam:952060",
                true, false, false, false, false, "",
                false, SessionSnapshot.ObservationState.RECOVERY_REQUIRED, true,
                "", 0L, 0L, 0L, 0L, 0L, "", "", "",
                "VERIFIED", 7368, "627d3413cbb0", "PENDING", "target_not_fullscreen", 0,
                false);
        State state = playState(windowed, true, false, false);
        state.canSwitchRetained = true;
        Action action = SessionOrchestrator.transition(state, UserIntent.play(game()));
        assertEquals(ActionKind.PREFLIGHT, action.kind);
        assertEquals(HostLaunchPreflight.Action.SWITCH_RETAINED, action.preflight);
    }

    @Test public void playOverASavedNeutralStreamCarriesReplacesOrConfirms() {
        SessionSnapshot idle = observed(SessionSnapshot.State.RECONNECT_REQUIRED, 77, "",
                SessionSnapshot.ObservationState.IDLE, false, true);
        // Orphaned (the host proves no game runs): replaced without asking.
        Action orphaned = play(idle, game(), true, true, false);
        assertEquals(ActionKind.CLOSE_COMPETING, orphaned.kind);
        assertTrue(orphaned.replacementAuthorized);
        // A live warm-up carries the game into it: reconnect.
        State carried = playState(idle, true, true, false);
        carried.warmUpCarriesGame = true;
        assertEquals(ActionKind.RECONNECT,
                SessionOrchestrator.transition(carried, UserIntent.play(game())).kind);
        // An unresolved observation proves nothing: refreshed once, then confirmed.
        SessionSnapshot pending = observed(SessionSnapshot.State.RECONNECT_REQUIRED, 77, "",
                SessionSnapshot.ObservationState.PENDING, false, true);
        assertEquals(ActionKind.REFRESH_OBSERVATION,
                play(pending, game(), true, true, false).kind);
        assertEquals(ActionKind.CONFIRM_REPLACEMENT,
                play(pending, game(), true, true, true).kind);
    }

    /**
     * TV 2026-10-14: the marker of a stream whose game the user had quit still named that game.
     * Only a marker naming no game is carried; over one naming a game, Play of another game
     * replaces the session.
     */
    @Test public void playOfAnotherGameNeverReconnectsAMarkerThatNamesAGame() {
        SessionModelDriver m = new SessionModelDriver();
        SessionModel.Controller owner = new SessionModel.Controller() {
            @Override public boolean isRetainedTransportLive() { return true; }
            @Override public SessionModel.ParkOutcome parkRetainedTransport() {
                return SessionModel.ParkOutcome.PARKED;
            }
            @Override public void disconnectRetainedStream(
                    SessionModel.TerminationCallback completion) { }
            @Override public void terminateRetainedSession(
                    String gameId, SessionModel.TerminationCallback completion) { }
            @Override public void switchGame(SessionModel.SwitchRequest request,
                                             SessionModel.SwitchCallback completion) { }
        };
        SessionResumeManager.PendingSession saved = SessionModelDriver.reconnectRecord(
                "s", "host", "default", "default", 77, "steam:952060", true, true);
        m.enterHome(owner, "s", "host", "default", 77, "steam:952060");
        m.markReconnectRequired("s");
        // The projection names no game for a marker without fresh correlated evidence.
        SessionSnapshot marker = observed(SessionSnapshot.State.RECONNECT_REQUIRED, 77, "",
                SessionSnapshot.ObservationState.PENDING, false, true);
        State state = playState(marker, true, true, true);
        state.warmUpCarriesGame = ConsoleActivity.warmUpCarriesPendingGame(
                8L, "host", saved, m.snapshot());
        assertFalse(state.warmUpCarriesGame);
        assertEquals(ActionKind.CONFIRM_REPLACEMENT,
                SessionOrchestrator.transition(state, UserIntent.play(game())).kind);
        m.clear();
        m.enterHome(owner, "s", "host", "default", 77, "");
        m.markReconnectRequired("s");
        assertTrue(ConsoleActivity.warmUpCarriesPendingGame(8L, "host", saved, m.snapshot()));
    }

    @Test public void playThatNeedsAFreshSessionFirstWakesAHostThatDoesNotAnswer() {
        SessionSnapshot uncertain = snapshot(SessionSnapshot.State.UNCERTAIN, 0, "");
        SessionSnapshot hole = observed(SessionSnapshot.State.ACTIVE, 42, "",
                SessionSnapshot.ObservationState.RECOVERY_REQUIRED, false, false);
        SessionSnapshot neutral = observed(SessionSnapshot.State.RECONNECT_REQUIRED, 77, "",
                SessionSnapshot.ObservationState.PENDING, false, true);
        // Every entrance that would refresh the session wakes the host instead.
        for (SessionSnapshot session : new SessionSnapshot[] {uncertain, hole, neutral}) {
            State offline = playState(session, true, true, false);
            offline.hostOnline = false;
            assertEquals(ActionKind.WAKE_HOST,
                    SessionOrchestrator.transition(offline, UserIntent.play(game())).kind);
            // The loading layer is raised first: the wake is never invisible.
            State uncovered = playState(session, false, true, false);
            uncovered.hostOnline = false;
            assertEquals(ActionKind.SHOW_LOADING,
                    SessionOrchestrator.transition(uncovered, UserIntent.play(game())).kind);
        }
        // The grants of the request survive the wake.
        State authorized = playState(uncertain, true, false, false);
        authorized.hostOnline = false;
        authorized.replacementAuthorized = true;
        assertTrue(SessionOrchestrator.transition(
                authorized, UserIntent.play(game())).replacementAuthorized);
        // Decisions that refresh nothing are not touched: the preflight wakes on its own.
        State app = playState(uncertain, true, false, false);
        app.hostOnline = false;
        assertEquals(ActionKind.PREFLIGHT,
                SessionOrchestrator.transition(app, UserIntent.play(desktop())).kind);
        State idle = playState(snapshot(SessionSnapshot.State.NONE, 0, ""), true, false, false);
        idle.hostOnline = false;
        assertEquals(ActionKind.PREFLIGHT,
                SessionOrchestrator.transition(idle, UserIntent.play(game())).kind);
    }

    @Test public void hostThisClientPutToSleepIsWokenBeforeAnythingContactsIt() {
        // The polls still call it online; the sleep record alone makes the fact false.
        // Idle and uncertain sessions (a refresh or a preflight) and a saved one to reconnect.
        SessionSnapshot[] sessions = {asleep(SessionSnapshot.State.NONE, 0, ""),
                asleep(SessionSnapshot.State.UNCERTAIN, 0, ""),
                asleep(SessionSnapshot.State.NONE, 0, ""),
                asleep(SessionSnapshot.State.UNCERTAIN, 0, ""),
                asleep(SessionSnapshot.State.RECONNECT_REQUIRED, 42, "game")};
        PlayIntent[] intents = {game(), game(), desktop(), desktop(), game()};
        for (int index = 0; index < sessions.length; index++) {
            State covered = playState(sessions[index], true, false, false);
            assertFalse(covered.hostOnline);
            assertEquals(ActionKind.WAKE_HOST, SessionOrchestrator.transition(
                    covered, UserIntent.play(intents[index])).kind);
            // The loading layer is raised first: the wake is never invisible.
            assertEquals(ActionKind.SHOW_LOADING, SessionOrchestrator.transition(
                    playState(sessions[index], false, false, false),
                    UserIntent.play(intents[index])).kind);
        }
        // Once the wake cleared the record, the same request goes on to its preflight.
        assertEquals(ActionKind.PREFLIGHT, play(snapshot(SessionSnapshot.State.NONE, 0, ""),
                game(), true, false, false).kind);
        // A switch on the retained stream contacts no sleeping host.
        State retained = playState(asleep(SessionSnapshot.State.ACTIVE, 7, "other"),
                true, false, false);
        retained.canSwitchRetained = true;
        Action reuse = SessionOrchestrator.transition(retained, UserIntent.play(game()));
        assertEquals(ActionKind.PREFLIGHT, reuse.kind);
        assertEquals(HostLaunchPreflight.Action.SWITCH_RETAINED, reuse.preflight);
    }

    @Test public void unpairedHostIsRefusedOnEveryPassBeforeAnyOtherDecision() {
        for (SessionSnapshot.State session : SessionSnapshot.State.values()) {
            for (boolean opaqueReady : new boolean[] {false, true}) {
                State state = playState(snapshot(session, 42, "game"), opaqueReady, true, false);
                state.hostPaired = false;
                state.hostOnline = false;
                assertEquals(ActionKind.REFUSE_UNPAIRED,
                        SessionOrchestrator.transition(state, UserIntent.play(game())).kind);
            }
        }
    }

    // ---------------------------------------------------------------- Return from the Home

    /** TV 2026-10-14: a settled hidden warm-up the user returned to becomes theirs. */
    @Test public void returnToASettledHiddenWarmUpTakesItOver() {
        assertEquals(ActionKind.TAKE_OVER_WARM_UP, returnFromHome(hiddenWarmUpOnScreen()));
    }

    /**
     * A Home selection queued or running as a switch retargets the stream: taking the old
     * warm-up over first would reveal the old target and give it the input.
     */
    @Test public void returnWithASwitchPendingOrInFlightLeavesTheWarmUpHidden() {
        State queued = hiddenWarmUpOnScreen();
        queued.switchRequested = true;
        assertEquals(ActionKind.IGNORE, returnFromHome(queued));
        State running = hiddenWarmUpOnScreen();
        running.retainedSwitch = true;
        assertEquals(ActionKind.IGNORE, returnFromHome(running));
    }

    /** PREPARING -> Back: refused now, taken over when asked again on completion. */
    @Test public void returnWhileStillPreparingWaitsForItsCompletion() {
        State preparing = hiddenWarmUpOnScreen();
        preparing.warmUpPreparing = true;
        assertEquals(ActionKind.IGNORE, returnFromHome(preparing));
        preparing.warmUpPreparing = false;
        assertEquals(ActionKind.TAKE_OVER_WARM_UP, returnFromHome(preparing));
    }

    @Test public void completionWithTheHomeStillShownOrInTheBackgroundTakesNothingOver() {
        State home = hiddenWarmUpOnScreen();
        home.homeVisible = true;
        assertEquals(ActionKind.IGNORE, returnFromHome(home));
        State background = hiddenWarmUpOnScreen();
        background.resumed = false;
        assertEquals(ActionKind.IGNORE, returnFromHome(background));
        State visible = hiddenWarmUpOnScreen();
        visible.hiddenWarmUp = false;
        assertEquals(ActionKind.IGNORE, returnFromHome(visible));
    }

    // ---------------------------------------------------------------- helpers

    private static ActionKind returnFromHome(State state) {
        return SessionOrchestrator.transition(state, UserIntent.RETURN_FROM_HOME).kind;
    }

    private static State hiddenWarmUpOnScreen() {
        State state = covered(LaunchTransitionType.GAME_CONNECTION, false);
        state.connected = true;
        state.resumed = true;
        state.hiddenWarmUp = true;
        return state;
    }

    private static ActionKind cancel(State state) {
        return SessionOrchestrator.transition(state, UserIntent.CANCEL).kind;
    }

    private static ActionKind back(State state) {
        return SessionOrchestrator.transition(state, UserIntent.BACK).kind;
    }

    private static ActionKind retry(State state) {
        return SessionOrchestrator.transition(state, UserIntent.RETRY).kind;
    }

    /** A covered game start the host reported failed while its stream stays connected. */
    private static State failedStart() {
        State state = covered(LaunchTransitionType.GAME, true);
        state.connected = true;
        state.failedStartCleanup = true;
        return state;
    }

    /** A covered, unrevealed transition before the host granted anything. */
    private static State covered(LaunchTransitionType type, boolean hasGame) {
        State state = new State();
        state.transitionActive = true;
        state.covered = true;
        state.type = type;
        state.hasGame = hasGame;
        return state;
    }

    private static State reconnectToGame() {
        return covered(LaunchTransitionType.GAME_CONNECTION, true);
    }

    private static Action play(SessionSnapshot snapshot, PlayIntent intent, boolean opaqueReady,
                               boolean targetPrepared, boolean observationRefreshed) {
        return SessionOrchestrator.transition(
                playState(snapshot, opaqueReady, targetPrepared, observationRefreshed),
                UserIntent.play(intent));
    }

    private static State playState(SessionSnapshot snapshot, boolean opaqueReady,
                                   boolean targetPrepared, boolean observationRefreshed) {
        return State.forPlay(snapshot, false, false, false, false, opaqueReady,
                targetPrepared, observationRefreshed, true, true);
    }

    /** A session of a host whose sleep this client requested. */
    private static SessionSnapshot asleep(SessionSnapshot.State state, int appId, String gameId) {
        return new SessionSnapshot("host", "default", "default", state, appId, gameId,
                false, false, false, true, false, "",
                false, SessionSnapshot.ObservationState.PENDING, false,
                "", 0L, 0L, 0L, 0L, 0L, "", "", "",
                "UNAVAILABLE", 0, "", "UNAVAILABLE", "test", 0, false);
    }

    private static PlayIntent game() {
        return PlayIntent.playniteGame("host", 42, "Game", false, "game", "game");
    }

    private static PlayIntent desktop() {
        return PlayIntent.sunshineApp("host", 7, "Desktop", false, "");
    }

    private static SessionSnapshot snapshot(SessionSnapshot.State state, int appId,
                                            String gameId) {
        return snapshot(state, appId, gameId, false, false);
    }

    /** A live neutral stream whose correlated host observation proves {@code gameId} running. */
    private static SessionSnapshot verifiedRunning(String gameId) {
        return new SessionSnapshot("host", "default", "default", SessionSnapshot.State.ACTIVE,
                42, gameId, true, false, false, false, false, "", true,
                SessionSnapshot.ObservationState.PENDING, true,
                "", 0L, 0L, 0L, 0L, 0L, "", "", "",
                "VERIFIED", 7368, "token", "PENDING", "target_not_fullscreen", 42, false);
    }

    private static SessionSnapshot snapshot(SessionSnapshot.State state, int appId,
                                            String gameId, boolean retained, boolean neutral) {
        return observed(state, appId, gameId, SessionSnapshot.ObservationState.PENDING, false,
                neutral, retained);
    }

    private static SessionSnapshot observed(SessionSnapshot.State state, int appId,
                                            String gameId,
                                            SessionSnapshot.ObservationState observation,
                                            boolean correlated, boolean neutral) {
        return observed(state, appId, gameId, observation, correlated, neutral, false);
    }

    private static SessionSnapshot observed(SessionSnapshot.State state, int appId,
                                            String gameId,
                                            SessionSnapshot.ObservationState observation,
                                            boolean correlated, boolean neutral,
                                            boolean retained) {
        return new SessionSnapshot("host", "default", "default", state, appId, gameId,
                retained, state == SessionSnapshot.State.SUSPENDED, false, false, false, "",
                neutral, observation, correlated,
                "", 0L, 0L, 0L, 0L, 0L, "", "", "",
                "UNAVAILABLE", 0, "", "UNAVAILABLE", "test", 0, false);
    }
}
