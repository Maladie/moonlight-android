package com.limelight.console;

import com.limelight.console.SessionOrchestrator.Action;
import com.limelight.console.SessionOrchestrator.ActionKind;
import com.limelight.console.SessionOrchestrator.State;
import com.limelight.console.SessionOrchestrator.UserIntent;
import com.limelight.console.transition.LaunchTransitionType;

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

    @Test public void playDuringAWarmUpReusesItsNeutralStreamOrFails() {
        SessionSnapshot preparing = snapshot(SessionSnapshot.State.PREPARING, 77, "");
        State state = playState(preparing, true, false, false);
        assertEquals(SessionFailure.UNCERTAIN_PREPARING_NOT_SWITCHABLE,
                SessionOrchestrator.transition(state, UserIntent.play(game())).failure);
        state.canSwitchRetained = true;
        assertEquals(ActionKind.REUSE_STREAM,
                SessionOrchestrator.transition(state, UserIntent.play(game())).kind);
        state.reuseOnly = true;
        assertEquals(HostLaunchPreflight.Action.SWITCH_RETAINED,
                SessionOrchestrator.transition(state, UserIntent.play(game())).preflight);
        state.targetPrepared = true;
        assertEquals(ActionKind.CLOSE_COMPETING,
                SessionOrchestrator.transition(state, UserIntent.play(game())).kind);

        State named = playState(snapshot(SessionSnapshot.State.PREPARING, 77, "other"),
                true, false, false);
        assertEquals(ActionKind.REFRESH_PREPARING,
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

    // ---------------------------------------------------------------- helpers

    private static ActionKind cancel(State state) {
        return SessionOrchestrator.transition(state, UserIntent.CANCEL).kind;
    }

    private static ActionKind back(State state) {
        return SessionOrchestrator.transition(state, UserIntent.BACK).kind;
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
                targetPrepared, observationRefreshed);
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
