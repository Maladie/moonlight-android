package com.limelight;

import com.limelight.console.transition.PresentationBarrier;

import org.junit.Test;

import com.limelight.console.transition.LaunchTransitionController;
import com.limelight.console.transition.LaunchTransitionType;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.limelight.SourceText.assertOrdered;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GameTransitionCancelContractTest {
    @Test public void overlayQuitRequiresExplicitConfirmation() throws IOException {
        String text = source();
        String listener = text.substring(text.indexOf("public void onQuitSession()"),
                text.indexOf("public void onEndGame()", text.indexOf("public void onQuitSession()")));
        String confirmation = text.substring(text.indexOf("private void confirmQuitSession()"),
                text.indexOf("private boolean canEndManagedGame()"));

        assertTrue(listener.contains("confirmQuitSession()"));
        assertFalse(listener.contains("closeStreamWithPrivacy(true)"));
        assertTrue(confirmation.contains("ConsoleConfirmDialog.show("));
        assertTrue(confirmation.contains("applist_quit_confirmation"));
        assertTrue(confirmation.contains("() -> closeStreamWithPrivacy(true)"));
    }

    /** A switch submitted from the stream's Home that failed tells the user why on that Home. */
    @Test public void failedPendingSwitchShowsItsReason() throws IOException {
        String text = source();
        String submit = text.substring(text.indexOf("private void submitPendingRetainedSwitch()"),
                text.indexOf("private void logLaunchMilestone("));
        String failed = submit.substring(submit.indexOf("SwitchOutcome.FAILED"),
                submit.indexOf("returnFromStreamHome();"));
        assertTrue(failed.contains("SessionFailure.ofSwitchFailure(error).details"));
        assertTrue(failed.contains(".show()"));
    }

    @Test public void retainedStreamDisconnectDoesNotStopTheProviderGame() throws IOException {
        String text = source();
        Path runtimePath = Paths.get("src/main/java/com/limelight/stream/StreamRuntime.java");
        String runtime = SourceText.read(runtimePath);
        String disconnect = runtime.substring(
                runtime.indexOf("@Override public void disconnectRetainedStream("),
                runtime.indexOf("@Override public void terminateRetainedSession("));

        assertTrue(disconnect.contains("stop(result ->"));
        assertTrue(disconnect.contains("platform.clearReconnectRecord("));
        assertFalse(disconnect.contains("terminateHost("));
        assertFalse(disconnect.contains("terminateWholeSessionVerified("));
        assertFalse(disconnect.contains("stopProviderGame("));
        String cleanup = text.substring(text.indexOf(
                        "private boolean cleanupUnrevealedProviderLaunch("),
                text.indexOf("private boolean isFreshOwnedFailureCleanup("));
        assertTrue(cleanup.contains(
                "if (userInitiatedDisconnect) return false"));
    }

    /** The cover follows every snapshot except while a reveal the controller authorized runs. */
    @Test public void coverFollowsTheControllerExceptDuringItsAuthorizedReveal() {
        assertTrue(Game.shouldShowTransitionOverlay(true, false));
        assertFalse("an authorized reveal is never cancelled by its own snapshot",
                Game.shouldShowTransitionOverlay(true, true));
        assertFalse(Game.shouldShowTransitionOverlay(false, false));
        assertFalse(Game.shouldShowTransitionOverlay(false, true));
    }

    /**
     * TV 2026-10-14: after the stream took the switch over (REUSED), the console closed and its
     * ended orchestration read as cancelled; Retry then completed CANCELLED and left the neutral
     * stream in RETURNING_TO_DASHBOARD with no way out. Every step of a retained switch asks the
     * gated cancellation, and a failure the stream carries returns to the Home.
     */
    @Test public void aSwitchTheStreamCarriesIsNeverCancelledByTheClosedConsole()
            throws IOException {
        String text = source();
        // The requester's cancellation is read only to place a settled Cancel, never to cancel.
        assertEquals(text.indexOf("operation.request.cancelled.getAsBoolean()"),
                text.lastIndexOf("operation.request.cancelled.getAsBoolean()"));
        assertTrue(text.substring(text.indexOf("private static boolean settledSwitchShowsHome("),
                text.indexOf("private void afterRetainedGameStopped(")).contains(
                "operation.request.cancelled.getAsBoolean()"));
        String gate = text.substring(text.indexOf("boolean cancelled() {"),
                text.indexOf("private boolean hasProviderGameTransition()"));
        assertTrue(gate.contains("retainedSwitchCancelled("));
        assertTrue(gate.contains("cancelRequested, retryRequested"));
        String handoff = text.substring(text.indexOf("private void runRetainedActorHandoff("),
                text.indexOf("static final long RETAINED_AUTHORIZATION_RETRY_DELAY_MS"));
        assertTrue(handoff.contains("else if (streamCarriesRequest) returnToConsoleHome(operation);"));
        String home = text.substring(text.indexOf("private void returnToConsoleHome(RetainedSwitch operation)"),
                text.indexOf("private boolean openConsoleHome()"));
        assertTrue("a dropped transport closes to the console, never a silent no-op",
                home.contains("openTerminalConsoleHome()") && home.contains("finish()"));
    }

    @Test public void cancelDoesNotWaitForAnotherVideoFrame() throws IOException {
        String text = source();
        String method = text.substring(text.indexOf("private void endCancelledTransition("),
                text.indexOf("static boolean isFailedStartObservation("));

        // A Cancel waits for no frame; it settles every effect waiting on this cover.
        assertFalse(method.contains("awaitCover("));
        assertOrdered(method, "transitionController.cancel(transitionSpec.id);",
                "consoleLoadingView.frames().cancel(transitionSpec.id);");
        assertTrue(method.contains("transitionCancelInFlight = true"));
        assertTrue(method.contains("transitionCoordinator.cancel()"));
        assertTrue(method.contains("new SessionEvent.ReconnectCleared(streamSessionId)"));
        assertTrue(method.contains("stopConnection(finishOnce)"));
    }

    @Test public void cancelOfUnrevealedProviderLaunchClosesTheWholeHostSession()
            throws IOException {
        String text = source();
        String facts = text.substring(
                text.indexOf("private SessionOrchestrator.State transitionState()"),
                text.indexOf("private SessionOrchestrator.ActionKind runStreamIntent("));
        String cancel = text.substring(text.indexOf("private void endCancelledTransition("),
                text.indexOf("static boolean isFailedStartObservation("));
        String host = text.substring(text.indexOf("private void runWholeSessionTermination("),
                text.indexOf("private void applyImmediateTerminalPrivacy("));

        // Cancel (and Back on the start error) never only drops the transport, which Sunshine
        // would merely pause with its virtual display left on the host. Which close runs is
        // SessionOrchestrator's row; this method only executes it.
        assertOrdered(cancel, "transitionController.cancel(transitionSpec.id)",
                "if (decided == SessionOrchestrator.ActionKind.CLOSE_STARTED_SESSION) {",
                "stopConnection(finishOnce)");
        assertTrue(facts.contains("transitionController.gameReadyConfirmed()"));
        // The live transport's generation, not only the one recorded at stage completion, read
        // once: a grant arriving after that read is the abandoned launch's, closed by its runtime
        // after the stop (so a grant racing the stop through the live path is closed too).
        assertTrue(facts.contains("state.controlGranted = bindControlGeneration() > 0L;"));
        assertOrdered(cancel, "stopConnection(finishOnce)",
                "cancelled.abandonLaunch()");
        String bind = text.substring(text.indexOf("private long bindControlGeneration()"),
                text.indexOf("private void stopConnection()"));
        assertTrue(bind.contains("runtime.controlGeneration()"));
        // Claim first, then read: a host failure whose cleanup already ran still closes.
        assertOrdered(cancel, "freshOwnedFailureCleanupInFlight.set(true)",
                "transitionCoordinator.providerCleanupResult()");
        // The close always verifies the permit's game itself, whatever the cleanup saw.
        assertTrue(cancel.contains("terminateWholeSessionVerified(null);"));
        assertTrue(cancel.contains("transitionCoordinator.cancel()"));
        assertFalse(cancel.contains("stopGame("));
        assertOrdered(host, "HostSessionTermination.run(",
                "reportGameLeftOnHost(application, outcome, completion == null)");
    }

    /**
     * Cancel during the reveal fade: the controller's revocation renders synchronously on the UI
     * thread and showOpaque invalidates the fade before provider cleanup starts, so the fade's
     * completion can never commit the launch and disarm the cleanup that closes the session.
     */
    @Test public void cancelDuringRevealFadeInterruptsItBeforeProviderCleanup() throws IOException {
        String text = source();
        String cancel = text.substring(text.indexOf("private void endCancelledTransition("),
                text.indexOf("AtomicBoolean finished = new AtomicBoolean();",
                        text.indexOf("private void endCancelledTransition(")));
        String apply = text.substring(text.indexOf("private void applyTransitionSnapshot("),
                text.indexOf("static boolean shouldShowTransitionOverlay("));

        assertTrue(text.contains("runOnUiThread(() -> applyTransitionSnapshot(snapshot))"));
        assertTrue(cancel.indexOf("transitionController.cancel(transitionSpec.id)")
                < cancel.indexOf("SessionOrchestrator.ActionKind.CLOSE_STARTED_SESSION"));
        assertTrue("a revoked reveal covers", Game.shouldShowTransitionOverlay(true, false));
        assertTrue(apply.indexOf("consoleLoadingView.showOpaque()")
                < apply.indexOf("consoleLoadingView.showCancelling()"));

        Path viewPath = Paths.get("src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        String view = SourceText.read(viewPath);
        String opaque = view.substring(view.indexOf("public void showOpaque()"),
                view.indexOf("public void stopAndHide()"));
        String reveal = view.substring(view.indexOf("public void revealStream(String fence, Runnable"),
                view.indexOf("public void showOpaque()"));
        assertTrue(opaque.indexOf("revealGeneration++") < opaque.indexOf("animate().cancel()"));
        assertTrue(reveal.indexOf("generation != revealGeneration")
                < reveal.indexOf("afterReveal.run()"));
    }

    @Test public void modelMarkersCarryTheSessionsOwnGameNotTheNamedOne() throws IOException {
        String text = source();
        String method = text.substring(
                text.indexOf("boolean requesterPresentsHome, String namedGameId) {"),
                text.indexOf("private Runnable terminalPrivacyOnce()"));
        // Only the host stop takes the named game; both model markers keep the session's own.
        assertTrue(method.contains("final String expectedGameId =\n"
                + "                    transitionSpec == null ? \"\" : transitionSpec.playniteGameId;"));
        assertEquals(2, method.split("stoppedGameId", -1).length - 1);
        assertTrue(method.contains(
                "expectedSessionId, expectedHostId, expectedAppId, stoppedGameId,\n"
                        + "                            actorContext, expectedControlGeneration);"));
        assertTrue(method.contains("new SessionEvent.DirectTerminationBegun(runtime,\n"
                + "                        expectedSessionId, expectedHostId, expectedAppId, expectedGameId)"));
        assertTrue(method.contains("new SessionEvent.TerminatingMarked(expectedSessionId,\n"
                + "                        expectedHostId, expectedAppId, expectedGameId)"));
    }

    @Test public void ownCloseReportsAGameLeftAndConsoleRequestsKeepTheirOwn()
            throws IOException {
        String text = source();
        String report = text.substring(text.indexOf("public static void reportGameLeftOnHost("),
                text.indexOf("private void applyImmediateTerminalPrivacy("));
        // A requesting console shows the one message for its result; Game adds none.
        assertOrdered(report, "if (!ownClose || outcome != HostSessionTermination.Outcome"
                + ".CLOSED_GAME_UNVERIFIED) return;", "R.string.console_terminate_session_game_left");
        assertFalse(text.contains("transition_host_session_left_open"));
    }

    /**
     * An own close the host did not confirm (refused, uncorrelated or unanswered) is the
     * session projection's fact for the Home (SessionModelTest), posted after the local stop's
     * own result; it restores no local ownership.
     */
    @Test public void unconfirmedOwnCloseBecomesTheHomesSessionState() throws IOException {
        String text = source();
        String host = text.substring(text.indexOf("private void runWholeSessionTermination("),
                text.indexOf("public static void reportGameLeftOnHost("));
        String refused = host.substring(host.indexOf("if (!marked) {"),
                host.indexOf("new Thread(() -> {"));
        assertTrue(refused.contains("host.complete(false);"));
        assertFalse(refused.contains("hostConfirmed.set(true)"));
        assertOrdered(host, "hostConfirmed.set(outcome.streamClosed());",
                "host.complete(outcome.streamClosed());",
                "SessionEvent.DirectTerminationCompleted.abandoned(",
                "if (!hostConfirmed.get()) {",
                "new SessionEvent.TerminationUnconfirmed(\n"
                        + "                                        expectedHostId, "
                        + "actorContext.actorProfileId,",
                "} else if (success && gameLeft.get()) {");
        assertFalse(host.contains("ReconnectSaved"));
    }

    @Test public void consoleRequestLearnsOfAGameLeftBehindAClosedStream() throws IOException {
        String text = source();
        String host = text.substring(text.indexOf("private void runWholeSessionTermination("),
                text.indexOf("public static void reportGameLeftOnHost("));
        assertOrdered(host, "gameLeft.set(outcome == HostSessionTermination.Outcome",
                "host.complete(outcome.streamClosed());");
        assertFalse(host.contains("outcome.closed()"));
        assertOrdered(host, "} else if (success && gameLeft.get()) {",
                "completion.gameLeftOnHost();");
        assertOrdered(host, "completion.gameLeftOnHost();",
                "if (!success) completion.transportUnsafe();");
    }

    @Test public void cancelNeverStopsAProviderGameItself() throws IOException {
        String text = source();
        String cancel = text.substring(text.indexOf("private void cancelTransition()"),
                text.indexOf("static boolean isFailedStartObservation("));

        // A cancelled reconnect leaves the running game alone; only a whole-session close
        // stops a game, and only the one its permit verifies.
        assertFalse(cancel.contains("stopGame("));
        assertTrue(cancel.contains("cancelled.keepLaunch()"));
        int keep = cancel.indexOf("SessionOrchestrator.ActionKind.STOP_TRANSPORT_KEEP_SESSION) {");
        int lost = cancel.indexOf("new SessionEvent.TransportLost(");
        int clear = cancel.indexOf("new SessionEvent.Cleared(streamSessionId)");
        assertTrue(keep >= 0 && lost > keep && lost < clear);
    }

    @Test public void retainedSessionStartsLocalStopWithoutWaitingForHostCleanup() throws IOException {
        String text = source();
        String terminate = text.substring(text.indexOf(
                        "public void terminateRetainedSession("),
                text.indexOf("public void switchGame(", text.indexOf(
                        "public void terminateRetainedSession(")));
        String helper = text.substring(text.indexOf(
                        "private void terminateWholeSessionVerified("),
                text.indexOf("private void applyImmediateTerminalPrivacy("));

        assertTrue(terminate.contains("terminateWholeSessionVerified("));
        assertFalse(terminate.contains("terminateChildSession("));
        assertTrue(helper.contains("wholeSessionTermination.run("));
        assertTrue(helper.contains("stopConnectionResult("));
        assertTrue(helper.contains("HostSessionTermination.run("));
        assertFalse(helper.contains("quitApp("));
    }

    /**
     * Wiring only; the behaviour is in StreamRuntimeTest and SessionModelTest. An intentional
     * close stops through the runtime's intentional close (records go only once it STOPPED); a
     * game stop seen after the transport was lost or a close began never closes it again.
     */
    @Test public void intentionalCloseStopsThroughTheRuntimesIntentionalClose() throws IOException {
        String text = source();
        String close = text.substring(text.indexOf("private void closeStreamWithPrivacy("),
                text.indexOf("private void stopConnection(Runnable afterStopped)"));
        assertFalse(close.contains("stopConnection("));
        assertFalse(close.contains("SessionEvent.Cleared("));
        assertTrue(close.contains("captured.closeIntentionally("));
        String retain = text.substring(
                text.indexOf("private void retainNeutralStreamAfterNaturalGameStop("),
                text.indexOf("private void closeAfterFailedNeutralRetain("));
        assertFalse(retain.contains("closeStreamWithPrivacy(false)"));
        assertOrdered(retain, "\"neutral_retain.skipped\"",
                "closeAfterFailedNeutralRetain(transitionId, unretainable)");
    }

    @Test public void normalProviderQuitRunsOnlyInActivityFreePermitOwner() throws IOException {
        String text = source();
        String close = text.substring(text.indexOf("private void closeStreamWithPrivacy("),
                text.indexOf("private void stopConnection(Runnable afterStopped)"));
        String helper = text.substring(text.indexOf(
                        "private void terminateWholeSessionVerified("),
                text.indexOf("private void applyImmediateTerminalPrivacy("));
        Path path = Paths.get("src/main/java/com/limelight/stream/HostSessionTermination.java");
        String host = SourceText.read(path);

        assertTrue(close.contains("if (quitApplication)"));
        assertTrue(close.contains("terminateWholeSessionVerified(null)"));
        assertFalse(close.contains("pendingApplicationQuit"));
        assertFalse(close.contains("doQuit()"));
        assertTrue(helper.contains("HostSessionTermination.run("));
        assertFalse(helper.contains("closeStreamWithPrivacy(true)"));
        assertOrdered(host, "control.acquire(",
                "provider.stopEvidenceWithinControl(");
        assertOrdered(host, "provider.stopEvidenceWithinControl(",
                "provider.stopGameWithinControl(");
        assertOrdered(host, "provider.stopGameWithinControl(",
                "http.quitApp()");
        assertTrue(host.contains("gameLeftUnverified = exact == null;"));
    }

    @Test public void liveReconnectWithoutWarmUpRelinquishesOnlyItsOwnMarker()
            throws IOException {
        String text = source();
        String started = text.substring(text.indexOf("public void connectionStarted()"),
                text.indexOf("private void relinquishReconnectedMarker()"));
        String direct = started.substring(started.indexOf("if (!warmUpConnection)"),
                started.indexOf("completeAutoWarmUpPreparing("));
        assertOrdered(direct, "relinquishReconnectedMarker()",
                "onStreamConnected(");
        assertFalse(started.substring(started.indexOf("completeAutoWarmUpPreparing("))
                .contains("relinquishReconnectedMarker"));
        String relinquish = text.substring(
                text.indexOf("private void relinquishReconnectedMarker()"),
                text.indexOf("private void onStreamConnected("));
        // Any live stream of the marker's host and execution profile supersedes it (TV
        // 2026-10-14: a launch the host turned into "resume" used a new stream session).
        assertTrue(relinquish.contains("marked.reconnectMarkerSupersededBy("));
        assertTrue(relinquish.contains("getExecutionProfileId()"));
        assertFalse(relinquish.contains("EXTRA_CONTROL_INTENT"));
        assertFalse(relinquish.contains("marked.streamSessionId.equals(streamSessionId)"));
        assertTrue(relinquish.contains("new SessionEvent.ReconnectRelinquished(marked)"));
    }

    @Test public void directQuitPublishesClosingStateAndDelegatesHostEffects()
            throws IOException {
        String text = source();
        String helper = text.substring(text.indexOf(
                        "private void terminateWholeSessionVerified("),
                text.indexOf("private void applyImmediateTerminalPrivacy("));

        assertTrue(helper.contains("new SessionEvent.DirectTerminationBegun("));
        assertTrue(helper.contains("new SessionEvent.TerminatingMarked("));
        assertTrue(helper.contains("Runnable privacy = terminalPrivacyOnce()"));
        assertTrue(helper.contains(
                "requesterPresentsHome ? () -> { } : this::openTerminalConsoleHome"));
        assertTrue(helper.contains("terminateWholeSessionVerified(completion, false, \"\")"));
        assertTrue(helper.contains("HostSessionTermination.run("));
        assertFalse(helper.contains("quitApp("));
        // The model runs this for a requesting console; Game must not clear it by opening Home.
        String requested = text.substring(text.indexOf(
                        "public void terminateRetainedSession("),
                text.indexOf("public void switchGame("));
        assertTrue(requested.contains("terminateWholeSessionVerified(completion, true, gameId);"));
        assertFalse(requested.contains("openTerminalConsoleHome"));
        String home = text.substring(text.indexOf("private void openTerminalConsoleHome("),
                text.indexOf("private void restoreParkedStream("));
        assertTrue(home.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP"));
        Path homePath = Paths.get("src/main/java/com/limelight/console/StreamHomeActivity.java");
        String streamHome = SourceText.read(homePath);
        assertTrue(streamHome.contains("protected void onRequestedStreamClosed()"));
        assertTrue(streamHome.contains("EXTRA_SUPPRESS_AUTO_WARM_UP, true"));
        Path consolePath = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        String console = SourceText.read(consolePath);
        // The host-panel close shows its progress in the host status, not a panel; a failure
        // dismissed over a closed stream hands the Home over instead of leaving a stream Home
        // without a stream.
        String request = "private void requestTerminateSession(ComputerDetails host, String";
        assertFalse(console.substring(console.indexOf(request), console.indexOf(
                "sessionModel.dispatch(", console.indexOf(request))).contains("showSidePanelBusy("));
        String cancel = console.substring(console.indexOf("@Override public void onCancel()"),
                console.indexOf("@Override public void onRetry()"));
        assertTrue(cancel.contains("onRequestedStreamClosed();"));
        assertTrue(cancel.contains("launchedExtra(EXTRA_RETAINED_STREAM_SESSION_ID)"));
        String terminated = text.substring(text.indexOf(
                        "public void connectionTerminated("),
                text.indexOf("public void connectionStatusUpdate("));
        assertFalse(text.contains("wholeSessionTerminationInFlight"));
        assertTrue(text.contains("WholeSessionTermination.Owner wholeSessionTermination"));
        assertOrdered(terminated, "if (wholeSessionTermination.isRunning())",
                "cleanupUnrevealedProviderLaunch(");
        // The guard ends where a host-ended stream is checked against the host's record.
        String verifiedGuard = terminated.substring(terminated.indexOf(
                "if (wholeSessionTermination.isRunning())"), terminated.indexOf(
                "// The host ended the stream"));
        assertTrue(verifiedGuard.contains("stopConnection()"));
        assertFalse(verifiedGuard.contains("finish()"));
        assertFalse(verifiedGuard.contains("cleanupUnrevealedProviderLaunch"));
        assertFalse(verifiedGuard.contains(
                "ML_ERROR_GRACEFUL_TERMINATION"));
        assertTrue(helper.contains("if (!success) completion.transportUnsafe()"));
    }

    @Test public void observedProviderExitSettlesNeutralWithoutQuittingSunshine() throws IOException {
        Path game = Paths.get("src/main/java/com/limelight/Game.java");
        Path coordinator = Paths.get(
                "src/main/java/com/limelight/console/ConsoleStreamTransitionCoordinator.java");
        String gameText = SourceText.read(game);
        String coordinatorText = SourceText.read(coordinator);

        assertTrue(gameText.contains("onProviderGameStopped("));
        assertTrue(gameText.contains("providerStopConfirmed = true"));
        int callback = gameText.indexOf("@Override public void onProviderGameStopped(");
        String providerExit = gameText.substring(callback,
                gameText.indexOf("@Override public void onInstallationVerified()", callback));
        assertTrue(providerExit.contains("retainNeutralStreamAfterNaturalGameStop("));
        assertFalse(providerExit.contains("closeStreamWithPrivacy(false)"));
        assertFalse(providerExit.contains("closeStreamWithPrivacy(true)"));
        assertFalse(coordinatorText.contains(
                "\"game-stopped\".equals(name) || \"game-stopping\".equals(name)"));
    }

    @Test public void exactRetainedObservationCancelReturnsHomeWithoutStoppingAnything()
            throws IOException {
        String text = source();
        String run = text.substring(
                text.indexOf("private SessionOrchestrator.ActionKind runStreamIntent("),
                text.indexOf("private void endCancelledTransition("));
        String facts = text.substring(
                text.indexOf("private SessionOrchestrator.State transitionState()"),
                text.indexOf("private SessionOrchestrator.ActionKind runStreamIntent("));
        String home = text.substring(text.indexOf("private void coverThenShowHome("),
                text.indexOf("private static boolean isBackKeyDown("));

        // Decided before anything is cancelled; the effect only covers and navigates.
        assertTrue(run.indexOf("SessionOrchestrator.transition(transitionState(), intent)")
                < run.indexOf("coverThenShowHome(intent)"));
        assertFalse(run.contains("transitionController.cancel("));
        // The controller's Home-return cover; a repeated Back is the same return.
        assertOrdered(home, "if (!transitionController.coverForUser(transitionId,",
                "LaunchTransitionController.UserCover.HOME_RETURN)) return;", "awaitCover(");
        assertFalse(home.contains("showOpaque"));
        assertTrue(home.contains("!transitionId.equals(transitionSpec.id)"));
        assertTrue(home.indexOf("SessionOrchestrator.transition(transitionState(), intent)")
                < home.indexOf("openConsoleHome()"));
        assertFalse(home.contains("cancelTransition()"));
        assertFalse(home.contains("stopConnection"));
        assertFalse(home.contains("stopGame"));
        assertFalse(home.contains("quitApp"));
        assertTrue(facts.contains("SessionModel.State.HOME_LIVE"));
        assertTrue(facts.contains("ownsLiveOrParkedSession("));
        String back = text.substring(text.indexOf("public void onBackPressed()"),
                text.indexOf("public boolean handleKeyDown("));
        assertTrue(back.contains("runNavigationIntent(SessionOrchestrator.UserIntent.BACK)"));
        assertOrdered(back, "SessionOrchestrator.ActionKind decided = runStreamIntent(intent);",
                "CANCEL_ON_COVER) cancelTransition();", "LEAVE_SCREEN) super.onBackPressed();");
    }

    /**
     * Shortcut equivalence: Back, the pad's Home and the overlay's Home all go through the one
     * table (rows: SessionTransitionTableTest), never straight to the Home.
     */
    @Test public void everyWayToTheHomeIsDecidedByTheTable() throws IOException {
        String text = source();
        String keyUp = text.substring(text.indexOf("public boolean handleKeyUp("),
                text.indexOf("// Pass-through virtual navigation keys",
                        text.indexOf("public boolean handleKeyUp(")));
        assertTrue(keyUp.contains("runNavigationIntent(SessionOrchestrator.UserIntent.BACK);"));
        String overlay = text.substring(text.indexOf("private void setupOverlayMenu()"),
                text.indexOf("public void onQuitSession()"));
        assertOrdered(overlay, "public void onHomeShortcut() {",
                "runNavigationIntent(SessionOrchestrator.UserIntent.HOME)",
                "public void onHome() {",
                "runNavigationIntent(SessionOrchestrator.UserIntent.HOME);");
        assertFalse(keyUp.contains("openConsoleHome()"));
        assertFalse(overlay.contains("openConsoleHome"));
    }

    @Test public void backOnTimedOutRevealedStreamReturnsToDashboardWithoutStoppingProvider()
            throws IOException {
        String text = source();
        String keyDown = text.substring(text.indexOf("public boolean handleKeyDown("),
                text.indexOf("if (isImeVisible", text.indexOf("public boolean handleKeyDown(")));
        // The loading view turns Back/B key-down into a Cancel click, so the decision must
        // precede its delegation; its key-up handling then consumes the matching release.
        int backDown = keyDown.indexOf("isBackKeyDown(event)");
        int decision = keyDown.indexOf("runStreamIntent(SessionOrchestrator.UserIntent.BACK)");
        int delegation = keyDown.indexOf("consoleLoadingView.handleControllerKey(event)");
        assertTrue(backDown >= 0 && decision > backDown && delegation > decision);
        assertTrue(keyDown.contains("!= SessionOrchestrator.ActionKind.CANCEL_ON_COVER"));
        String helper = text.substring(text.indexOf("private void coverThenShowHome("),
                text.indexOf("private static boolean isBackKeyDown("));
        // The timeout is published on the observer thread and covered on the UI thread later:
        // cover first, navigate only after the frame, and decide again then; a Home that does
        // not open gives the stream back.
        assertOrdered(helper, "transitionController.coverForUser(", "awaitCover(",
                "!openConsoleHome()", "transitionController.releaseUserCover(transitionId,");
        assertFalse(keyDown.contains("cancelTransition()"));
        String keyUp = text.substring(text.indexOf("public boolean handleKeyUp("),
                text.indexOf("if (isImeVisible", text.indexOf("public boolean handleKeyUp(")));
        assertTrue(keyUp.contains("consoleLoadingView.handleControllerKey(event)"));
        Path viewPath = Paths.get("src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        String view = SourceText.read(viewPath);
        String control = view.substring(view.indexOf("public boolean handleControllerKey("),
                view.indexOf("KeyEvent.KEYCODE_DPAD_LEFT", view.indexOf("public boolean handleControllerKey(")));
        assertTrue(control.contains("return isLoadingActionKey(keyCode);"));
        assertTrue(control.contains("return cancelView.performClick()"));
        int onCancel = text.indexOf("public void onCancel()");
        assertTrue(text.substring(onCancel, onCancel + 120).contains("cancelTransition();"));
        String home = text.substring(text.indexOf("private boolean openConsoleHome()"),
                text.indexOf("private void openPreparingConsoleHome()"));

        assertFalse(home.contains("stopConnection"));
        assertFalse(home.contains("revealStream"));
    }

    @Test public void connectingStateMakesInFlightTransportStoppable() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        String text = SourceText.read(source);
        Path runtimePath = Paths.get("src/main/java/com/limelight/stream/StreamRuntime.java");
        String runtime = SourceText.read(runtimePath);
        String start = runtime.substring(runtime.indexOf("public void start() {"),
                runtime.indexOf("public void stop(TransportStopCoordinator.Completion"));
        String stop = text.substring(text.indexOf("private void stopConnection(Runnable"),
                text.indexOf("public void stageFailed"));

        // The runtime marks CONNECTING before the native start, so a stop always reaches it.
        assertOrdered(start, "state = State.CONNECTING",
                "transport.start(this)");
        assertTrue(runtime.contains("new TransportStopCoordinator(predecessor, this::stopNative"));
        assertFalse(stop.contains("conn.stop()"));
        assertTrue(stop.contains("captured.stop("));
    }

    @Test public void providerObservationIsArmedBeforeTransportCanReportConnected()
            throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        String text = SourceText.read(source);
        assertOrdered(text, "awaitCover(startId, outcome -> {", "transitionCoordinator.start()",
                "startConnectionIfReady(", "} else {");
    }

    @Test public void earlyProviderStartIsCleanedWhenTransportSetupFails()
            throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        String text = SourceText.read(source);
        String failed = text.substring(text.indexOf("public void stageFailed("),
                text.indexOf("public void connectionTerminated("));
        String terminated = text.substring(text.indexOf("public void connectionTerminated("),
                text.indexOf("public void connectionStatusUpdate("));

        assertTrue(failed.contains("cleanupUnrevealedProviderLaunch("));
        assertTrue(terminated.contains("cleanupUnrevealedProviderLaunch("));
        assertFalse(terminated.contains("!connected"));
    }

    /**
     * Lab s3-3: Play on a fresh neutral start while the host held an unfinished operation.
     * The acquire was refused (409), yet the "fresh neutral" cleanup claimed the failure,
     * asked for a whole-session close that had no control generation, showed "the host
     * session could not be closed" and finished Game before any error screen.
     */
    @Test public void refusedAcquireOnAFreshNeutralStartClosesNothingAndShowsItsError()
            throws IOException {
        // The lab case's rule (host refused the start: nothing to close) is a table row:
        // SessionTransitionTableTest.streamScreenClosesOnlyTheFreshSessionItStarted.
        String text = source();
        // Only the answer to the start's own acquire says that nothing was started; a 409 on
        // a later complete or reconcile follows a sent effect and keeps the cleanup.
        Path nv = Paths.get("src/main/java/com/limelight/nvstream/NvConnection.java");
        String connection = SourceText.read(nv);
        String start = connection.substring(connection.indexOf("private boolean startApp("),
                connection.indexOf("private boolean startAppEffect("));
        assertEquals(start.indexOf("startRefused = true;"),
                start.lastIndexOf("startRefused = true;"));
        // Only a 4xx answer is a refusal. A 5xx or an unreadable 2xx may follow a granted
        // permit, so it takes the lost-answer path below it: reconcile, then abandon.
        assertOrdered(start, "catch (IOException unavailable)",
                "if (refusedStatus >= 400 && refusedStatus < 500) {", "startRefused = true;",
                "throw unavailable;", "reconciled = controlBinding.client.reconcile(",
                "abandonControlStart(operationId");
        // A refused acquire is a control rejection whatever its 4xx status: none of them
        // reaches the generic stage failure with its port hints, and a pairing or grant
        // refusal (no reason in its body) keeps its cause.
        assertTrue(connection.contains(
                "e instanceof GatewayTransport.GatewayException && (startRefused\n"));
        assertTrue(connection.contains(
                "if (reason == null) reason = status == 401 ? \"unauthorized\"\n"
                + "                                : status == 403 ? \"forbidden\" : \"\";\n"
                + "                        context.connListener.controlRejected("
                + "appName, reason, startRefused);"));

        String rejected = text.substring(text.indexOf("public void controlRejected("),
                text.indexOf("public void connectionTerminated("));
        // The refusal is recorded before the failure is handled...
        assertOrdered(rejected, "hostRefusedStart = nothingStarted;",
                "stageFailed(stage, 0, 0,");
        assertEquals(text.indexOf("hostRefusedStart = "), text.lastIndexOf("hostRefusedStart = "));
        // ...and reaches the one rule that claims a failure for a host close, on this
        // failure and again when the answered error screen finishes Game.
        String cleanup = text.substring(
                text.indexOf("private boolean cleanupUnrevealedProviderLaunch("),
                text.indexOf("private boolean isFreshOwnedFailureCleanup("));
        assertTrue(cleanup.contains(
                "retainedSwitch != null || hasOwnRetainedSession(), hostRefusedStart);"));
        // So the claim that leads to terminateWholeSessionVerified, the only sender of
        // "termination unconfirmed" on this path, is not made, and the error screen follows.
        assertOrdered(cleanup, "if (ownsFreshSession) {",
                "freshOwnedFailureCleanupInFlight.compareAndSet(false, true)");
        assertFalse(rejected.contains("terminateWholeSessionVerified("));
        assertFalse(rejected.contains("TerminationUnconfirmed"));
        assertOrdered(rejected, "cleanupUnrevealedProviderLaunch(",
                "if (!displayedFailureDialog) {");
        int dialog = rejected.indexOf("if (!displayedFailureDialog) {");
        // The screen stays until the user answers it: both dialogs finish Game themselves.
        String shown = rejected.substring(dialog);
        assertTrue(shown.contains("if (rejection != null && rejection.offersHardTerminate()) {"));
        assertTrue(shown.contains("Dialog.displayDialog(Game.this, title, dialogText, true);"));
        // A host's refusal is never titled as a connection error.
        assertTrue(shown.contains("? R.string.control_rejected_title : R.string.conn_error_title"));
        assertFalse(shown.contains("finish();"));

        // What each refused acquire shows: only an uncertain host operation offers the hard
        // close; a running one, a stream conflict and a child without a session do not.
        for (String reason : new String[] {"terminal_result_timeout", "effect_result_uncertain",
                "start_abandoned", "gateway_restarted_before_terminal_result",
                "reconcile_required", "terminal_owner_required"}) {
            assertTrue(reason, com.limelight.console.SessionFailure
                    .fromControlRejection(reason).offersHardTerminate());
        }
        for (String reason : new String[] {"operation_in_progress", "child_session_unmanaged",
                "managed_stream_conflict", "unmanaged_stream", "operation_id_reused",
                "retained_process_unconfirmed", ""}) {
            assertFalse(reason, com.limelight.console.SessionFailure
                    .fromControlRejection(reason).offersHardTerminate());
        }
    }

    @Test public void freshNeutralFailureOwnsProviderAndSunshineCleanupExactlyOnce()
            throws IOException {
        String text = source();
        String failed = text.substring(text.indexOf("public void stageFailed("),
                text.indexOf("public void connectionTerminated("));
        String terminated = text.substring(text.indexOf("public void connectionTerminated("),
                text.indexOf("public void connectionStatusUpdate("));
        String cleanup = text.substring(text.indexOf(
                        "private boolean cleanupUnrevealedProviderLaunch("),
                text.indexOf("private void cancelPendingAutomaticReveal("));
        String callback = text.substring(text.indexOf(
                        "@Override public void onProviderGameCleanupComplete("),
                text.indexOf("@Override public void onInstallationVerified()"));
        String terminate = text.substring(text.indexOf(
                        "private void terminateWholeSessionVerified("),
                text.indexOf("private void applyImmediateTerminalPrivacy("));

        assertTrue(failed.contains("if (cleanupUnrevealedProviderLaunch("));
        assertTrue(terminated.contains("if (cleanupUnrevealedProviderLaunch("));
        assertTrue(cleanup.contains("freshOwnedFailureCleanupInFlight.compareAndSet(false, true)"));
        assertTrue(callback.indexOf("isFreshOwnedFailureCleanup(")
                < callback.indexOf("RetainedSwitch operation"));
        // A failed or unconfirmed provider cleanup still closes the host stream.
        String fresh = callback.substring(0, callback.indexOf("RetainedSwitch operation"));
        assertTrue(fresh.contains("terminateWholeSessionVerified(null);"));
        assertFalse(fresh.contains("transitionController.error("));
        assertFalse(text.contains("providerAlreadyStopped"));
        assertTrue(terminate.contains("HostSessionTermination.run("));
        assertTrue(terminate.contains(
                "terminateWholeSessionVerified(completion, false, \"\")"));
        assertTrue(terminate.contains(
                "DirectTerminationCompleted.abandoned"));
        assertTrue(terminate.contains("stopConnectionResult("));
        assertFalse(terminate.contains("enterHome("));
        assertFalse(terminate.contains("openConsoleHome("));
    }

    @Test public void intentionalReconnectDoesNotStopProviderGameOrStartItAgain()
            throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        String text = SourceText.read(source);
        String destroyed = text.substring(text.indexOf("protected void onDestroy()"),
                text.indexOf("super.onDestroy()"));
        String bitrate = text.substring(text.indexOf("private void applyBitrateAndReconnect("),
                text.indexOf("private void showOverlayMenuHint()"));

        assertTrue(destroyed.contains("cleanupUnrevealedProviderLaunch("));
        assertTrue(bitrate.contains("LaunchTransitionType.GAME_CONNECTION.name()"));
    }

    @Test public void retainedSwitchReusesTransportAndRequiresFreshFrame() throws IOException {
        String text = source();
        String begin = text.substring(text.indexOf("private void beginNewRetainedGameAttempt("),
                text.indexOf("private void compensateRetainedAuthorization("))
                + text.substring(text.indexOf("private void startRetainedGameAttempt("),
                text.indexOf("private void pollRetainedSwitchCancellation("));
        String setup = text.substring(text.indexOf("private void applyRetainedTransition("),
                text.indexOf("private void advancePreparingSwitch("));

        assertTrue(setup.contains("transitionController.beginOnKeptTransport(next)"));
        assertTrue(begin.contains("transitionCoordinator.start()"));
        assertTrue(begin.contains("transitionCoordinator.onStreamConnected()"));
        assertTrue(begin.contains("transitionController.streamConnected(next.id)"));
        String reuse = begin + setup;
        assertFalse(reuse.contains("videoFrameRendered"));
        assertFalse(reuse.contains("conn.start"));
        assertFalse(reuse.contains("conn.stop"));
        assertFalse(reuse.contains("quitApp"));
        assertFalse(reuse.contains("stopConnection"));
    }

    @Test public void providerCleanupIsDisarmedOnlyInsideRealRevealCompletion()
            throws IOException {
        String text = source();
        String reveal = text.substring(text.indexOf("private void revealTransitionNow()"),
                text.indexOf("private void persistRevealedTransitionForRecovery()"));

        assertTrue(reveal.indexOf("consoleLoadingView.revealStream(() ->")
                < reveal.indexOf("transitionCoordinator.commitProviderLaunch()"));
        assertTrue(reveal.indexOf("transitionCoordinator.commitProviderLaunch()")
                < reveal.indexOf("persistRevealedTransitionForRecovery()"));
        assertTrue(reveal.indexOf("transitionCoordinator.commitProviderLaunch()")
                < reveal.indexOf("transitionController.revealCompleted"));
    }

    @Test public void retainedSwitchGatePrecedesExactStopAndStaleExitIsIgnored()
            throws IOException {
        String text = source();
        String gate = text.substring(text.indexOf("private void beginRetainedGameSwitch("),
                text.indexOf("private void performRetainedProviderSwitch("));
        String provider = text.substring(text.indexOf("private void performRetainedProviderSwitch("),
                text.indexOf("private void recoverRetainedSwitch("));
        int callback = text.indexOf("@Override public void onProviderGameStopped(");
        String stopped = text.substring(callback,
                text.indexOf("@Override public void onProviderGameStartAccepted", callback));

        assertOrdered(gate, "presentSwitchCover(operation);", "awaitCover(", "detachForSwitch()");
        assertTrue(provider.indexOf("gateway.snapshot()")
                < provider.indexOf("if (!childSessionId.isEmpty())"));
        assertTrue(provider.contains("releaseChildTimeAttachmentAndWait(\"game_stopped\");"));
        assertTrue(provider.contains("if (!exactGame.isEmpty())"));
        assertTrue(stopped.contains("operation.oldSpec.id.equals(transitionId)"));
        assertTrue(stopped.contains("operation.cancelRequested"));
        assertTrue(stopped.contains("transitionSpec.id.equals(transitionId)"));
    }

    @Test public void retainedSwitchCancelAndRetryNeverRestartTransport()
            throws IOException {
        String text = source();
        String cancel = text.substring(text.indexOf("private void cancelTransition()"),
                text.indexOf("private void restartStreamForRetry()"));
        String executed = text.substring(
                text.indexOf("private SessionOrchestrator.ActionKind runStreamIntent("),
                text.indexOf("private void endCancelledTransition("));
        // Only the retry decided for a dead transport stops it and starts the stream again.
        String restart = text.substring(text.indexOf("private void restartStreamForRetry()"),
                text.indexOf("private boolean retryStillCurrent("));
        String onStream = text.substring(text.indexOf("private void retryGameStart()"),
                text.indexOf("public void displayMessage", text.indexOf(
                        "private void retryGameStart()")));

        assertOrdered(cancel, "retainedSwitch != null",
                "stopConnection(finishOnce)");
        assertTrue(cancel.contains("requestRetainedSwitchCancellation(retainedSwitch)"));
        assertOrdered(executed, "case RETRY_RETAINED_SWITCH:",
                "retainedSwitch.retryRequested = true;",
                "requestRetainedSwitchCancellation(retainedSwitch)");
        assertFalse(executed.contains("stopConnection("));
        assertTrue(restart.contains("stopConnection("));
        assertTrue(restart.contains(
                "retry.putExtra(EXTRA_CONSOLE_LOADING_EPOCH, SystemClock.elapsedRealtime())"));
        assertFalse(onStream.contains("stopConnection"));
        assertFalse(onStream.contains("startActivity("));
        assertTrue(onStream.contains("LaunchTransitionType.GAME,"));
        // The failed start's cleanup is already ordered: Retry orders no stop by itself.
        assertFalse(onStream.contains("transitionCoordinator.cancel()"));
        assertTrue(text.contains("gameRestartTransitionId = \"\";\n"
                + "                                continueGameRetry(transitionId, success);"));
        String observationRetry = text.substring(text.indexOf(
                        "private void retryLiveObservation()"),
                text.indexOf("public void displayMessage", text.indexOf(
                        "private void retryLiveObservation()")));
        assertTrue(observationRetry.contains("transferProviderOwnershipTo(replacement)"));
        assertFalse(observationRetry.contains("conn.start"));
        assertFalse(observationRetry.contains("conn.stop"));
        assertFalse(observationRetry.contains("stopConnection"));
    }

    @Test public void acceptedRetainedFailureSettlesNeutralDashboardWithoutStaleMutation()
            throws IOException {
        String text = source();
        String cleanup = text.substring(text.indexOf(
                        "@Override public void onProviderGameCleanupComplete("),
                text.indexOf("@Override public void onInstallationVerified()", text.indexOf(
                        "@Override public void onProviderGameCleanupComplete(")));
        String guard = cleanup.substring(0, cleanup.indexOf("if (success)"));

        assertFalse(guard.contains("!operation.cancelRequested"));
        assertFalse(guard.contains("!operation.startFailedCleanupPending"));
        String settled = text.substring(text.indexOf("private void settleStartFailedSwitch("),
                text.indexOf("private void afterRetainedGameStopped("));
        assertTrue(cleanup.contains("settleStartFailedSwitch(operation)"));
        assertTrue(settled.contains("boolean returnToDashboard = settledSwitchShowsHome(operation, operation.cancelRequested)"));
        assertTrue(settled.contains("if (returnToDashboard) returnToConsoleHome(operation)"));
        assertTrue(cleanup.contains("updateRetainedGame(operation, gameId, \"\","));
    }

    @Test public void acceptedRetainedGameNaturalExitReleasesSwitchIntoNeutralDashboard()
            throws IOException {
        String text = source();
        String stopped = text.substring(text.indexOf(
                        "@Override public void onProviderGameStopped("),
                text.indexOf("@Override public void onProviderGameStartAccepted(",
                        text.indexOf("@Override public void onProviderGameStopped(")));
        String acceptedExit = stopped.substring(stopped.indexOf(
                        "operation.newSpec.id.equals(transitionId)"),
                stopped.indexOf("if (transitionSpec == null"));
        // The exited game's closing cover is the controller's; no bookkeeping on destroy.
        assertOrdered(acceptedExit, "transitionController.gameStopping(transitionId,",
                "awaitCover(transitionId, outcome -> {");
        assertFalse(acceptedExit.contains("showOpaque"));
        String renderedFrame = acceptedExit.substring(acceptedExit.indexOf(
                "awaitCover(transitionId, outcome -> {"));

        assertTrue(acceptedExit.contains(
                "operation.request.newGameId.equalsIgnoreCase(gameId)"));
        assertTrue(renderedFrame.indexOf("isCurrentRetainedSwitch(operation)")
                < renderedFrame.indexOf("updateRetainedGame(operation, gameId, \"\","));
        assertTrue(renderedFrame.indexOf("operation.newSpec.id.equals(transitionId)")
                < renderedFrame.indexOf("updateRetainedGame(operation, gameId, \"\","));
        assertTrue(renderedFrame.indexOf(
                        "operation.request.newGameId")
                < renderedFrame.indexOf("updateRetainedGame(operation, gameId, \"\","));
        assertTrue(acceptedExit.indexOf("updateRetainedGame(operation, gameId, \"\")")
                < acceptedExit.indexOf("settleNeutralRetainedStream(operation)"));
        assertTrue(renderedFrame.indexOf("settleNeutralRetainedStream(operation)")
                < renderedFrame.indexOf("\"provider_game_exited\""));
        assertTrue(acceptedExit.contains("operation.consoleSignalled.get()"));
        assertTrue(acceptedExit.contains("if (returnToDashboard) returnToConsoleHome(operation)"));
        assertFalse(acceptedExit.contains("stopGame("));
        assertFalse(acceptedExit.contains("stopConnection("));
        assertFalse(acceptedExit.contains("closeStreamWithPrivacy("));
    }

    @Test public void overlayEndGameUsesExactStopAndNeutralRetainWithoutTransportQuit()
            throws IOException {
        String text = source();
        String eligibility = text.substring(text.indexOf("private boolean canEndManagedGame()"),
                text.indexOf("private void confirmEndManagedGame()"));
        String stop = text.substring(text.indexOf("private void endManagedGameKeepingStream()"),
                text.indexOf("private void clearResumedSuspendedSession()"));

        assertTrue(eligibility.contains("isTransportConnected() && streamEverRevealed"));
        assertTrue(eligibility.contains("EXTRA_NEUTRAL_STREAM_TARGET"));
        assertTrue(eligibility.contains("hasProviderGameTransition()"));
        assertTrue(eligibility.contains("retainedSwitch == null"));
        assertOrdered(stop, "transitionController.gameStopping(", "gateway.snapshot()");
        // Variant B: after the closing cover the Home shows at once; the tile's "Ending the
        // game…" is the model's local close until the host confirms (or fails) the stop.
        assertOrdered(stop, "if (!transitionController.coverSettled(expected.id, outcome)) {",
                "new SessionEvent.LocalCloseBegun(hostId, actorProfileId,",
                "executionProfileId, true), closeToken -> {",
                "new SessionEvent.LocalCloseFinished(hostId,", "openConsoleHome();",
                "runEndManagedGame(expected, actorContext, hostConfirmed);",
                "gateway.stopGame(exactGame, current.processToken);",
                "hostConfirmed.accept(true);", "retainNeutralStreamAfterNaturalGameStop(");
        assertTrue(stop.contains("PlayniteIdentityResolutionPolicy.verifiedStopTarget("));
        assertTrue(stop.contains("!current.hasVerifiedProcessFor(exactGame)"));
        assertTrue(stop.indexOf("gateway.stopGame(exactGame, current.processToken)")
                < stop.indexOf("retainNeutralStreamAfterNaturalGameStop("));
        assertTrue(stop.contains("providerEndGameInFlight = true"));
        assertTrue(stop.split("isFinishing\\(\\) \\|\\| isDestroyed\\(\\)", -1).length - 1 >= 2);
        assertTrue(stop.contains("retryLiveObservation()"));
        assertFalse(stop.contains("conn.start"));
        assertFalse(stop.contains("conn.stop"));
        assertFalse(stop.contains("quitApp"));
        assertFalse(stop.contains("stopConnection"));
    }

    /**
     * TV 2026-10-14: a failed End game left the opaque cover over a revealed stream with no
     * way out. The controller covers the stop and restores the presentation it replaced; the
     * view only follows it (variant B: the failure is a message over the Home).
     */
    @Test public void failedEndGameOrSuspendRestoresThePresentationBeforeIt() throws IOException {
        String text = source();
        String stop = text.substring(text.indexOf("private void endManagedGameKeepingStream()"),
                text.indexOf("private void clearResumedSuspendedSession()"));
        String failure = stop.substring(stop.indexOf("catch (IOException | RuntimeException error)"));
        String suspend = text.substring(text.indexOf("private void suspendSessionAndSleep()"),
                text.indexOf("private void applyBitrateAndReconnect("));
        String snapshot = text.substring(text.indexOf("private void applyTransitionSnapshot("),
                text.indexOf("private void scheduleAutomaticReveal()"));

        assertOrdered(stop,
                "if (!transitionController.gameStopping(", "providerEndGameInFlight = true;",
                "gateway.snapshot()");
        assertOrdered(failure, "hostConfirmed.accept(false);",
                "Toast.makeText(application, R.string.overlay_menu_end_game_failed",
                "transitionController.streamClosingFailed(expected.id");
        assertFalse("a dialog on the stream under the Home is never seen",
                failure.contains("AlertDialog"));
        assertFalse(suspend.contains("transitionController.cancel("));
        assertFalse(suspend.contains("stopAndHide()"));
        assertFalse(suspend.contains("showOpaque"));
        assertEquals(2, suspend.split("transitionController.streamClosingFailed\\(expected.id",
                -1).length - 1);
        // The view comes off once the controller stops covering, whatever it showed.
        assertOrdered(snapshot, "if (lastTransitionOverlayVisible && !snapshot.overlayVisible",
                "consoleLoadingView.showFullTransitionAppearance();",
                "consoleLoadingView.revealStream(snapshot.spec.id,");
    }

    /**
     * TV 2026-10-14 13:12: a revealed reconnect, Home, two failed switches and a return left the
     * Game's own opaque cover ("Gotowe", no action) over the live stream. The Home return and
     * the switch are the controller's covers now, released back to the revealed stream.
     */
    @Test public void revealedStreamReturnsUncoveredAfterHomeAndFailedSwitch() {
        com.limelight.console.transition.LaunchTransitionSpec spec =
                new com.limelight.console.transition.LaunchTransitionSpec("re3-reconnect",
                        "host", LaunchTransitionType.GAME_CONNECTION, 42, "steam:952060",
                        1_000L);
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(spec);
        controller.coverSettled(spec.id, PresentationBarrier.Outcome.SUBMITTED);
        controller.surfaceReady(spec.id);
        controller.inputPipelineReady(spec.id);
        controller.streamConnected(spec.id);
        controller.videoFrameRendered(spec.id, controller.snapshot().frameEdge, System.nanoTime());
        controller.gatewayConnected(spec.id, "host");
        controller.targetProcessRunning(spec.id, "host", LaunchTransitionType.GAME,
                "steam:952060", "not fullscreen");
        controller.coverForUser(spec.id, LaunchTransitionController.UserCover.HOME_RETURN);
        controller.releaseUserCover(spec.id, LaunchTransitionController.UserCover.HOME_RETURN);
        assertTrue("still covered: its own start never revealed",
                controller.snapshot().overlayVisible);
        controller.showStreamAnyway(spec.id);
        controller.revealCompleted(spec.id);
        // The host's readiness keeps flapping after the manual reveal; the cover stays off.
        controller.targetWindowStabilizing(spec.id, "host", LaunchTransitionType.GAME,
                "steam:952060", "stabilizing");
        controller.targetWindowReady(spec.id, "host", LaunchTransitionType.GAME,
                "steam:952060");
        assertFalse(controller.snapshot().overlayVisible);

        for (LaunchTransitionController.UserCover cover : new LaunchTransitionController.UserCover[] {
                LaunchTransitionController.UserCover.HOME_RETURN,
                LaunchTransitionController.UserCover.SWITCH}) {
            assertTrue(controller.coverForUser(spec.id, cover));
            assertTrue(controller.snapshot().overlayVisible);
            controller.releaseUserCover(spec.id, cover);
            controller.videoFrameRendered(spec.id, controller.snapshot().frameEdge,
                    System.nanoTime());
            assertFalse("the stream comes back uncovered", controller.snapshot().overlayVisible);
            assertFalse(controller.snapshot().inputBlocked);
        }
    }

    /**
     * Review K5.2: a switch whose game was already stopped (the session changed under it, or
     * the new game exited) must not reveal the stale manually revealed spec over the host
     * desktop; it ends as that game's closing cover (endSwitchCover).
     */
    @Test public void switchThatStoppedTheGameKeepsItsCoverOverAStaleRevealedSpec()
            throws IOException {
        com.limelight.console.transition.LaunchTransitionSpec spec =
                new com.limelight.console.transition.LaunchTransitionSpec("revealed-old",
                        "host", LaunchTransitionType.GAME, 42, "steam:952060", 1_000L);
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(spec);
        controller.coverSettled(spec.id, PresentationBarrier.Outcome.SUBMITTED);
        controller.surfaceReady(spec.id);
        controller.inputPipelineReady(spec.id);
        controller.streamConnected(spec.id);
        controller.videoFrameRendered(spec.id, controller.snapshot().frameEdge, System.nanoTime());
        controller.showStreamAnyway(spec.id);
        controller.revealCompleted(spec.id);
        assertFalse(controller.snapshot().overlayVisible);

        assertTrue(controller.coverForUser(spec.id, LaunchTransitionController.UserCover.SWITCH));
        // The switch stopped the old game and ended on its transition.
        assertTrue(controller.gameStopping(spec.id, "host", "steam:952060"));
        controller.releaseUserCover(spec.id, LaunchTransitionController.UserCover.SWITCH);
        controller.videoFrameRendered(spec.id, controller.snapshot().frameEdge, System.nanoTime());
        assertTrue("the old game is stopped: no stale reveal over the desktop",
                controller.snapshot().overlayVisible);
        assertTrue(controller.snapshot().closingPresentation());

        String text = source();
        String end = text.substring(text.indexOf("private void endSwitchCover("),
                text.indexOf("private void beginRetainedProviderSwitch("));
        assertOrdered(end, "if (transitionSpec == operation.oldSpec) {",
                "if (operation.gameStopped) {", "transitionController.gameStopping(",
                "return;", "transitionController.releaseUserCover(transitionSpec.id,");
    }

    /**
     * TV 2026-10-14 13:03:08: Play during a neutral warm-up stopped no game, yet a neutral
     * GAME_CONNECTION "returning to library" transition (generic title, no artwork) was begun
     * before the game's own transition. A game-less stream goes straight to the next game.
     */
    @Test public void switchFromGameLessStreamBeginsNoNeutralTransition() throws IOException {
        String text = source();
        String stopped = text.substring(text.indexOf("private void afterRetainedGameStopped("),
                text.indexOf("private void beginNewRetainedGameAttempt("));
        assertOrdered(stopped,
                "if (operation.request.oldGameId.isEmpty() && !cancelled) {",
                "beginNewRetainedGameAttempt(operation);", "return;",
                "settleNeutralRetainedStream(operation, !cancelled);");
    }

    @Test public void homeReturnAndFailedSwitchRestoreTheControllerPresentation()
            throws IOException {
        String text = source();
        String resume = text.substring(text.indexOf("protected void onResume()"),
                text.indexOf("private void returnFromStreamHome()"));
        String complete = text.substring(text.indexOf("private void completeRetainedSwitch("),
                text.indexOf("private void signalRetainedSwitchReuse("));
        String stopped = text.substring(text.indexOf("private void afterRetainedGameStopped("),
                text.indexOf("private void beginNewRetainedGameAttempt("));
        String exited = text.substring(text.indexOf("public void onProviderGameStopped("),
                text.indexOf("hasProviderGameTransition() && !providerStopInFlight"));
        String frame = text.substring(text.indexOf("private void onFirstVideoFrameRendered()"),
                text.indexOf("private void onTransitionChanged("));
        // Back from the Home: the controller's Home-return cover comes off (on a fresh frame).
        assertOrdered(resume, "if (returningFromStreamHome) {",
                "returnFromStreamHome();", "transitionController.releaseUserCover(",
                "LaunchTransitionController.UserCover.HOME_RETURN);");
        assertOrdered(complete, "retainedSwitch = null;",
                "runOnUiThread(() -> endSwitchCover(operation));",
                "operation.completion.complete(");
        assertOrdered(stopped,
                "operation.gameStopped = !operation.request.oldGameId.isEmpty();",
                "updateRetainedGame(", "settleNeutralRetainedStream(operation, !cancelled);",
                "presentSwitchCover(operation);", "beginNewRetainedGameAttempt(operation);");
        assertOrdered(exited, "operation.gameStopped = true;",
                "transitionController.gameStopping(transitionId,");
        assertFalse("readiness frames come only from the armed (id, edge) proof",
                frame.contains("videoFrameRendered("));
        assertFalse("the cover follows the controller, a hidden warm-up's included",
                frame.contains("showOpaque"));
        // The only cover writes left in Game execute the controller's snapshot.
        String snapshot = text.substring(text.indexOf("private void applyTransitionSnapshot("),
                text.indexOf("static boolean shouldShowTransitionOverlay("));
        assertEquals(2, text.split("consoleLoadingView\\.showOpaque\\(\\)", -1).length - 1);
        assertEquals(2, snapshot.split("consoleLoadingView\\.showOpaque\\(\\)", -1).length - 1);
        for (String removed : new String[] {"restoreControllerPresentation",
                "restoresStreamAfterLocalCover", "revealUncoveredStream", "coverWithdrawn",
                "restorePresentationAfterFrameId", "homeReturnInFlight", "postOnAnimation"}) {
            assertFalse(removed, text.contains(removed));
        }
    }

    /** End game the controller refuses covers nothing and starts nothing. */
    @Test public void refusedEndGameNeitherCoversNorStops() throws IOException {
        String text = source();
        String stop = text.substring(text.indexOf("private void endManagedGameKeepingStream()"),
                text.indexOf("private void clearResumedSuspendedSession()"));
        String begin = stop.substring(0, stop.indexOf("new Thread("));
        assertOrdered(begin,
                "if (!transitionController.gameStopping(", ")) return;", "stopChildMonitoring();",
                "providerEndGameInFlight = true;", "awaitCover(");
        assertFalse("the controller's snapshot covers the stop", begin.contains("showOpaque()"));
    }

    /**
     * The host stopped the game and then End game failed: the stop report it held back is
     * settled as the natural stop, never a GAME_RUNNING restore of a game that is gone.
     */
    @Test public void endGameFailingAfterTheObservedStopSettlesTheNaturalStop()
            throws IOException {
        String text = source();
        String stop = text.substring(text.indexOf("private void endManagedGameKeepingStream()"),
                text.indexOf("private void clearResumedSuspendedSession()"));
        String failure = stop.substring(stop.indexOf("catch (IOException | RuntimeException error)"));
        assertOrdered(failure, "if (current && providerStopConfirmed) {",
                "providerEndGameInFlight = false;", "retainNeutralStreamAfterNaturalGameStop(",
                "return;", "transitionController.streamClosingFailed(expected.id");
    }

    /** A reattached live runtime supersedes a stale reconnect marker as connectionStarted does. */
    @Test public void reattachedTransportRelinquishesTheReconnectMarker() throws IOException {
        String text = source();
        String resume = text.substring(text.indexOf("private void resumeAttachedTransport()"),
                text.indexOf("private void announceAttachedTransport()"));
        String warmUp = resume.substring(0, resume.indexOf("relinquishReconnectedMarker();"));
        assertOrdered(resume, "completeAutoWarmUpPreparing(",
                "return;", "relinquishReconnectedMarker();", "announceAttachedTransport();");
        assertTrue(warmUp.contains("isExactAutoWarmUpPreparing("));
    }

    /**
     * TV 2026-10-15 (switch A to B): the neutral settle between the games painted its own
     * closing card and cleared the artwork for one frame. A switch going on holds its cover
     * from the neutral transition's start and keeps its look.
     */
    /**
     * TV 2026-10-15 15:56:10: the Play handed to a warm-up showed the stream for a moment
     * before the switch covered it. The handed Play takes the switch cover on its intent.
     */
    @Test public void handedPlayCoversItsStreamOnArrival() throws IOException {
        String text = source();
        String newIntent = text.substring(text.indexOf("protected void onNewIntent(Intent intent)"),
                text.indexOf("private void coverPendingSwitch("));
        assertOrdered(newIntent, "\"on_new_intent\", next, \"OFFER_ACCEPTED\");",
                "coverPendingSwitch(next);", "submitPendingRetainedSwitch();");
        assertTrue(text.contains("coverPendingSwitch(createdPendingSwitch);"));
        String cover = text.substring(text.indexOf("private void coverPendingSwitch("),
                text.indexOf("private void submitPendingRetainedSwitch()"));
        assertOrdered(cover, "consoleLoadingView.showFullTransitionAppearance();",
                "transitionController.coverForUser(transitionSpec.id,",
                "LaunchTransitionController.UserCover.SWITCH);",
                "transitionController.releaseUserCover(covered,");
    }

    /**
     * TV 2026-10-15 16:27:17: "Close session" over the neutral stream left by an ended game.
     * Its closing card is the session's (host, no game artwork, "Closing the session").
     */
    @Test public void aGamelessStreamClosesWithTheNeutralSessionLook() throws IOException {
        String text = source();
        String closing = text.substring(text.indexOf("} else if (snapshot.closingPresentation()) {"),
                text.indexOf("} else if (snapshot.state == LaunchTransitionState.HOST_SESSION_LOCKED) {"));
        assertOrdered(closing,
                "boolean sessionClosing = snapshot.state == LaunchTransitionState.CLOSING_STREAM;",
                "boolean gameEnded = !sessionClosing",
                "if (sessionClosing && snapshot.spec.playniteGameId.isEmpty()) {",
                "consoleLoadingView.setTitle(pcName);",
                "consoleLoadingView.setSplashArtwork(\"\");",
                "consoleLoadingView.showClosing(");
    }

    @Test public void switchGoingOnKeepsItsCoverThroughTheNeutralSettle() throws IOException {
        String text = source();
        String kept = text.substring(text.indexOf("private void beginKeptStreamTransition("),
                text.indexOf("private void retainNeutralStreamAfterNaturalGameStop("));
        assertOrdered(kept,
                "updateRetainedTransitionIntent(spec, \"\", title, artworkPath, !underSwitchCover);",
                "transitionController.beginOnKeptTransport(spec);",
                "if (underSwitchCover) {",
                "LaunchTransitionController.UserCover.SWITCH);",
                "transitionController.coverSettled(spec.id, coverOutcome);");
        String intent = text.substring(text.indexOf("boolean present) {"),
                text.indexOf("private boolean isCurrentRetainedSwitch("));
        assertTrue(intent.contains("if (present) consoleLoadingView.setTitle("));
        assertTrue(intent.contains("if (present) consoleLoadingView.setSplashArtwork("));
    }

    @Test public void stoppedOldIdentityIsPersistedAsNeutralBeforeNewStart()
            throws IOException {
        String text = source();
        String stopped = text.substring(text.indexOf("private void afterRetainedGameStopped("),
                text.indexOf("private void beginNewRetainedGameAttempt("));
        String neutral = text.substring(text.indexOf("private void settleNeutralRetainedStream("),
                text.indexOf("private void updateRetainedTransitionIntent("));

        assertOrdered(stopped, "updateRetainedGame(",
                "settleNeutralRetainedStream(operation, !cancelled)");
        assertOrdered(stopped, "settleNeutralRetainedStream(operation, !cancelled)",
                "beginNewRetainedGameAttempt(operation)");
        assertTrue(neutral.contains("LaunchTransitionType.GAME_CONNECTION"));
        assertTrue(neutral.contains("operation.request.streamTargetName"));
        assertTrue(neutral.contains("saveReconnect(false)"));
        assertTrue(neutral.contains("transitionCoordinator = createTransitionCoordinator()"));
        assertTrue(neutral.contains("transitionCoordinator.start()"));
        assertFalse(neutral.contains("conn.start"));
        assertFalse(neutral.contains("conn.stop"));
    }

    @Test public void naturalGameStopRetainsExactNeutralTransportAndDashboard()
            throws IOException {
        String text = source();
        String retained = text.substring(text.indexOf(
                        "private void retainNeutralStreamAfterNaturalGameStop("),
                text.indexOf("private boolean rearmFailedNewGameObservation("));
        String callback = text.substring(text.indexOf(
                        "@Override public void onProviderGameStopped("),
                text.indexOf("@Override public void onProviderGameStartAccepted(",
                        text.indexOf("@Override public void onProviderGameStopped(")));

        // One closing cover (the controller's), keeping the ended game's artwork to the Home.
        assertOrdered(retained, "Neutral retain stale expectedTransition=",
                "transitionController.gameStopping(transitionId, transitionSpec.hostId, gameId);",
                "transitionCoordinator.detachAfterConfirmedGameStop()",
                "getIntent().getStringExtra(EXTRA_CONSOLE_LOADING_ARTWORK), false);");
        assertFalse(retained.contains("showOpaque"));

        assertTrue(retained.indexOf("transitionCoordinator.detachAfterConfirmedGameStop()")
                < retained.indexOf("new SessionEvent.GameStoppedRetainNeutral("));
        assertTrue(retained.contains("EXTRA_NEUTRAL_STREAM_TARGET"));
        assertTrue(retained.contains("settleNeutralRetainedStream("));
        assertTrue(retained.contains("Neutral retain stale"));
        assertTrue(retained.contains("Neutral retain stale after frame"));
        assertTrue(retained.indexOf("Neutral retain stale after frame")
                < retained.indexOf("\"provider_owner\""));
        assertTrue(retained.contains("if (!backgroundStreamParked && !streamHomeVisible)"));
        assertTrue(retained.contains("openConsoleHome()"));
        assertFalse(retained.contains("conn.start"));
        assertFalse(retained.contains("conn.stop"));
        assertFalse(retained.contains("quitApp"));
        assertFalse(retained.contains("stopGame("));
        assertTrue(callback.contains("retainNeutralStreamAfterNaturalGameStop("));
        assertFalse(callback.contains("closeStreamWithPrivacy(false)"));
    }

    @Test public void neutralSettlingKeepsExistingBackgroundRetentionPolicy()
            throws IOException {
        String text = source();
        String neutral = text.substring(text.indexOf(
                        "private void settleNeutralRetainedStream(String hostId"),
                text.indexOf("private void retainNeutralStreamAfterNaturalGameStop("));
        String parking = text.substring(text.indexOf("private boolean canParkBackgroundStream()"),
                text.indexOf("public boolean parkPreparingTransport("));

        assertTrue(neutral.contains("saveReconnect(parked)"));
        assertTrue(parking.contains("BackgroundStreamPreferences.readMinutes(this) == 0"));
        // Park goes through the runtime, which owns the service and the retention timer.
        assertTrue(parking.contains("return runtime.park();"));
        assertFalse(parking.contains("BackgroundStreamService."));
        assertFalse(neutral.contains("postDelayed"));
    }

    @Test public void neutralOldIdentityRequiresFreshIdleAndNeverStopsAReportedGame()
            throws IOException {
        String text = source();
        String provider = text.substring(text.indexOf(
                        "private void performRetainedProviderSwitch("),
                text.indexOf("private void recoverRetainedSwitch("));

        // The gameless decision and the adoption are behaviour-tested in
        // PlayniteIdentityResolutionPolicyTest and stream.RetainedRunningAdoptionTest.
        assertTrue(provider.contains("operation.request.oldGameId.isEmpty()"));
        assertTrue(provider.contains("if (!exactGame.isEmpty())"));
        assertTrue(provider.contains("!current.hasVerifiedProcessFor(exactGame)"));
        assertTrue(provider.contains("gateway.stopGame(exactGame, current.processToken);"));
    }

    @Test public void failedCompensatingStopRearmsNewGameObservationOnSameTransport()
            throws IOException {
        String text = source();
        String callback = text.substring(text.indexOf(
                        "@Override public void onProviderGameCleanupComplete("),
                text.indexOf("@Override public void onInstallationVerified()", text.indexOf(
                        "@Override public void onProviderGameCleanupComplete(")));
        String recovery = text.substring(text.indexOf(
                        "private boolean rearmFailedNewGameObservation("),
                text.indexOf("private void updateRetainedTransitionIntent("));
        String failedCleanup = callback.substring(callback.lastIndexOf("} else {"));
        int rearm = failedCleanup.indexOf(
                "rearmFailedNewGameObservation(operation, gameId)");

        assertTrue(failedCleanup.indexOf("updateRetainedGame(")
                < failedCleanup.indexOf("rearmFailedNewGameObservation(operation, gameId)"));
        assertTrue(rearm < failedCleanup.indexOf("completeRetainedSwitch(operation,", rearm));
        assertTrue(recovery.contains("LaunchTransitionType.GAME_CONNECTION"));
        assertTrue(recovery.contains("transitionController.beginOnKeptTransport(observation)"));
        assertTrue(recovery.contains("transitionController.error(observation.id"));
        assertTrue(recovery.contains("transitionCoordinator.onStreamConnected()"));
        assertFalse(recovery.contains("revealStream"));
        assertFalse(recovery.contains("videoFrameRendered"));
        assertFalse(recovery.contains("conn.start"));
        assertFalse(recovery.contains("conn.stop"));
        assertFalse(recovery.contains("quitApp"));
        assertFalse(recovery.contains("stopConnection"));
    }

    @Test public void identityConflictWaitsForCleanupWithoutMutatingRetainedSession()
            throws IOException {
        String text = source();
        int acceptedStart = text.indexOf("@Override public void onProviderGameStartAccepted(");
        String accepted = text.substring(acceptedStart,
                text.indexOf("@Override public void onProviderGameStartFailed(", acceptedStart));
        int conflict = accepted.indexOf("if (!updated)");
        String conflictBranch = accepted.substring(conflict,
                accepted.indexOf("saveReconnect(false)", conflict));

        assertTrue(conflictBranch.contains("operation.identityConflict = true"));
        assertTrue(conflictBranch.contains("requestRetainedSwitchCancellation(operation)"));
        assertFalse(conflictBranch.contains("settleNeutralRetainedStream"));
        assertFalse(conflictBranch.contains("completeRetainedSwitch"));
    }

    @Test public void replacedSessionDuringOldStopIsNeverNeutralizedOrStarted()
            throws IOException {
        String text = source();
        String stopped = text.substring(text.indexOf("private void afterRetainedGameStopped("),
                text.indexOf("private void beginNewRetainedGameAttempt("));
        int cas = stopped.indexOf("if (!updated)");
        String staleBranch = stopped.substring(cas,
                stopped.indexOf("boolean cancelled =", cas));

        assertTrue(staleBranch.contains("completeRetainedSwitch(operation"));
        assertFalse(staleBranch.contains("saveReconnect(true)"));
        assertFalse(staleBranch.contains("settleNeutralRetainedStream"));
        assertFalse(staleBranch.contains("beginNewRetainedGameAttempt"));
    }

    @Test public void everyAsyncRetainedIdentityMutationUsesExactSessionGuard()
            throws IOException {
        String text = source();
        String helper = text.substring(text.indexOf("private void updateRetainedGame("),
                text.indexOf("private void completeRetainedSwitch("));
        String callbacks = text.substring(text.indexOf(
                        "@Override public void onProviderGameStartAccepted("),
                text.indexOf("@Override public void onInstallationVerified()", text.indexOf(
                        "@Override public void onProviderGameStartAccepted(")));

        assertTrue(helper.contains("new SessionEvent.OwnedSwitchGameUpdated("));
        assertTrue(helper.contains("operation.request, expectedGameId, newGameId"));
        assertFalse(helper.contains("newGameId.equalsIgnoreCase(retained.playniteGameId)"));
        assertTrue(callbacks.split("updateRetainedGame\\(", -1).length - 1 >= 3);
    }

    @Test public void sameAllowedRunningGameUsesActorAdoptionWithoutProviderRestart()
            throws IOException {
        String text = source();
        String decision = text.substring(text.indexOf("private void beginRetainedProviderSwitch("),
                text.indexOf("private void performRetainedProviderSwitch("));
        assertTrue(decision.contains("operation.request.oldGameId.equalsIgnoreCase("));
        assertTrue(decision.contains("beginRetainedActorHandoff(operation, true)"));
        String adoption = text.substring(text.indexOf("private void adoptAuthorizedRunningGame("),
                text.indexOf("private void compensateRetainedAuthorization("));
        assertTrue(adoption.contains("transferProviderOwnershipTo(replacement, authorization,"));
        assertTrue(text.contains("new SessionEvent.ProviderReauthorize("));
        assertTrue(adoption.contains("signalRetainedSwitchReuse(operation)"));
        assertFalse(adoption.contains("gateway.startGame("));
        assertFalse(adoption.contains("conn.start("));
    }

    @Test public void sameGameTakeoverResolvesFallbackBeforeMutationAndRestoresObservation()
            throws IOException {
        String text = source();
        String handoff = text.substring(text.indexOf("private void beginRetainedActorHandoff("),
                text.indexOf("private void adoptAuthorizedRunningGame("));
        assertTrue(handoff.contains("resolveRetainedProviderIdentity(operation)"));
        assertTrue(handoff.contains("operation.actorContext"));
        assertTrue(handoff.contains(
                "if (!reuseRunningGame && transitionCoordinator != null)"));
        int resolve = handoff.indexOf("resolveRetainedProviderIdentity(operation)");
        int release = handoff.indexOf("releaseChildTimeAttachmentAndWait(");
        assertTrue(resolve >= 0 && release > resolve);
        assertTrue(handoff.indexOf("ownsSwitchBeforeMutation(") < release);
        String recovery = text.substring(text.indexOf("private void recoverRetainedSwitch("),
                text.indexOf("private void afterRetainedGameStopped("));
        assertTrue(recovery.contains("transferProviderOwnershipTo(recoveryCoordinator)"));
        assertTrue(recovery.indexOf("transferProviderOwnershipTo(recoveryCoordinator)")
                < recovery.indexOf("transitionController.beginOnKeptTransport(recovery)"));
        // The recovery runs on the same live transport: its frames count, its start is an edge.
        assertFalse(recovery.contains("transitionController.begin("));
        int ownershipFailure = recovery.indexOf("if (!ownershipRecovered");
        assertTrue(ownershipFailure >= 0);
        assertTrue(recovery.indexOf("completeRetainedSwitch(operation,", ownershipFailure)
                < recovery.indexOf("terminateWholeSessionVerified(null)", ownershipFailure));
        assertTrue(recovery.indexOf("completeRetainedSwitch(operation,")
                < recovery.indexOf("startChildMonitoring()"));
        assertFalse(recovery.contains("revealStream"));
    }

    @Test public void failedActorTimeHandoffClosesTheCommonSessionFailClosed()
            throws IOException {
        String text = source();
        String handoff = text.substring(text.indexOf("private void beginRetainedActorHandoff("),
                text.indexOf("private void adoptAuthorizedRunningGame("));
        assertTrue(handoff.contains("boolean timeAttachmentMayHaveChanged = false"));
        assertTrue(handoff.contains("timeAttachmentMayHaveChanged = true;\n"
                + "                    releaseChildTimeAttachmentAndWait(reuseRunningGame ? \"replacement\" : \"game_stopped\");"));
        assertTrue(handoff.contains("timeAttachmentMayHaveChanged = allowance.ok"
                + " || !childSessionId.isEmpty();"));
        assertTrue(handoff.contains("releaseUnusedAllowance("));
        assertTrue(handoff.contains("pendingChildSessionId, allowance"));
        assertTrue(handoff.contains("boolean failClosed = timeAttachmentMayHaveChanged"));
        int failed = handoff.indexOf("completeRetainedSwitch(operation,",
                handoff.indexOf("boolean failClosed"));
        int terminate = handoff.indexOf("if (failClosed) terminateWholeSessionVerified(null)",
                failed);
        assertTrue(failed >= 0 && terminate > failed);
    }

    @Test public void rejectedChildStartIsNeverEndedAsIfItCreatedAnAttachment()
            throws IOException {
        String text = source();
        String handoff = text.substring(text.indexOf("private void beginRetainedActorHandoff("),
                text.indexOf("private void adoptAuthorizedRunningGame("));
        String release = text.substring(text.indexOf("private void releaseUnusedAllowance("),
                text.indexOf("private void startRetainedGameAttempt("));

        assertTrue(handoff.contains("pendingChildSessionId, allowance"));
        assertTrue(release.contains("client.releaseUnusedAllowance(gateway, sessionId, startResult)"));
        assertFalse(release.contains("client.end("));
    }

    private static String source() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        return SourceText.read(source);
    }

    @Test public void revealedGameRecreatesAsObservationOnly() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        String game = SourceText.read(source);
        String reveal = game.substring(game.indexOf("private void revealTransitionNow()"),
                game.indexOf("private void cancelPendingAutomaticReveal()"));
        String retainedRetry = game.substring(
                game.indexOf("private void retryLiveObservation()"),
                game.indexOf("public void displayMessage"));

        assertTrue(reveal.indexOf("streamEverRevealed = true")
                < reveal.indexOf("persistRevealedTransitionForRecovery()"));
        assertTrue(reveal.indexOf("persistRevealedTransitionForRecovery()")
                < reveal.indexOf("transitionController.revealCompleted"));
        assertTrue(retainedRetry.contains("LaunchTransitionType.GAME_CONNECTION"));
        assertTrue(retainedRetry.contains("transitionCoordinator.start()"));
        assertTrue(retainedRetry.contains("transitionCoordinator.onStreamConnected()"));
        assertFalse(retainedRetry.contains("conn.start("));
        assertFalse(retainedRetry.contains("stopConnection("));

        String create = game.substring(game.indexOf("protected void onCreate("),
                game.indexOf("private MediaCodecDecoderRenderer createDecoderRenderer"));
        String save = game.substring(game.indexOf("protected void onSaveInstanceState("),
                game.indexOf("protected void onDestroy()"));
        assertTrue(create.contains("savedInstanceState.getBoolean(STATE_TRANSITION_REVEALED"));
        assertTrue(create.contains("recoveredTransitionType("));
        assertTrue(save.contains("STATE_TRANSITION_REVEALED, streamEverRevealed"));
    }

    @Test public void consoleConnectionFailureUsesFriendlyStageInStatus() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        String text = SourceText.read(source);
        String failed = text.substring(text.indexOf("public void stageFailed("),
                text.indexOf("public void connectionTerminated("));
        int transitionStart = failed.indexOf("if (transitionController != null)");
        String transitionError = failed.substring(transitionStart,
                failed.indexOf("} else {", transitionStart));

        assertTrue(transitionError.contains("consoleLoadingView.friendlyStage(stage)"));
        assertFalse(transitionError.contains("+ \" \" + stage"));
    }

    @Test public void missingInflatedStreamToastGetsAProgrammaticFallback() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        String game = SourceText.read(source);
        int lookup = game.indexOf("discordDmToastView = findViewById(");
        int controller = game.indexOf("discordOverlayController =", lookup);
        String initialization = game.substring(lookup, controller);

        assertTrue(initialization.contains("if (discordDmToastView == null)"));
        assertTrue(initialization.contains("discordDmToastView = new DiscordDmToastView(this)"));
        assertTrue(initialization.contains("gameRoot.addView(discordDmToastView"));
        assertTrue(initialization.contains("registerHost(discordDmToastView)"));
    }

    @Test public void overlayHintWaitsUntilPrivacyRevealCompletes() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        String game = SourceText.read(source);
        String connected = game.substring(game.indexOf("public void connectionStarted()"),
                game.indexOf("private ConsoleStreamTransitionCoordinator createTransitionCoordinator"));
        String reveal = game.substring(game.indexOf("private void revealTransitionNow()"),
                game.indexOf("private void cancelPendingAutomaticReveal()"));

        assertTrue(connected.contains("if (transitionController == null) showOverlayMenuHint()"));
        assertTrue(reveal.indexOf("transitionController.revealCompleted")
                < reveal.indexOf("showOverlayMenuHint()"));
    }

    @Test public void retainedRestoreKeepsRequestingIdrBeforeReconnecting() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        String game = SourceText.read(source);
        String watchdog = game.substring(game.indexOf("private void scheduleRetainedRestoreWatchdog("),
                game.indexOf("private void reconnectRetainedStream("));

        // Lab tail reached 7.3 s to the first restored frame; 5 s forced a needless reconnect.
        assertTrue(game.contains("RETAINED_RESTORE_TIMEOUT_MS = 10_000L"));
        assertTrue(watchdog.contains("transitionUiHandler.postDelayed(this, RETAINED_RESTORE_IDR_RETRY_MS)"));
        assertTrue(watchdog.contains("transitionUiHandler.removeCallbacks(retainedRestoreIdrRetry)"));
        assertTrue(watchdog.contains("postDelayed(retainedRestoreWatchdog, RETAINED_RESTORE_TIMEOUT_MS)"));
    }

    @Test public void connectedStreamKeepsProcessDeathRecoveryCorrelation() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        String game = SourceText.read(source);
        String connected = game.substring(game.indexOf("public void connectionStarted()"),
                game.indexOf("private ConsoleStreamTransitionCoordinator createTransitionCoordinator"));
        String restore = game.substring(game.indexOf("private void restoreParkedStream("),
                game.indexOf("private void scheduleRetainedRestoreWatchdog("));

        assertTrue(connected.contains("saveReconnect(false)"));
        assertFalse(connected.contains("SessionResumeManager.clearIfMatches"));
        assertFalse(restore.contains("new SessionEvent.Cleared"));
        assertFalse(restore.contains("SessionResumeManager.clearIfMatches"));
    }

    @Test public void lockedWindowsSessionReasonIsShownOnTheOverlay() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        String game = SourceText.read(source);
        // The reason is a sentence: the wrapping notice, never the single-line stage.
        String notice = game.substring(game.indexOf("private String transitionNotice("),
                game.indexOf("private String transitionStatus("));
        assertTrue(notice.contains("LaunchTransitionState.GAME_WINDOW_STABILIZING"));
        assertTrue(notice.contains("snapshot.detail"));
        String status = game.substring(game.indexOf("private String transitionStatus("),
                game.indexOf("private static String playniteInstallNotificationKey("));
        int stabilizing = status.indexOf("case GAME_WINDOW_STABILIZING:");
        assertFalse(status.substring(stabilizing,
                status.indexOf("case LAUNCHER_INTERACTION_REQUIRED:", stabilizing))
                .contains("snapshot.detail"));
    }

    @Test public void retryCompletionCannotOutrunCancelOrAnotherGeneration() {
        assertTrue(Game.shouldContinueRetry(
                "retry-1", "retry-1", "retry-1", false, false, false));
        assertFalse(Game.shouldContinueRetry(
                "retry-1", "retry-1", "retry-1", true, false, false));
        assertFalse(Game.shouldContinueRetry(
                "retry-1", "retry-2", "retry-1", false, false, false));
        assertFalse(Game.shouldContinueRetry(
                "retry-1", "retry-1", "", false, false, false));
        assertFalse(Game.shouldContinueRetry(
                "retry-1", "retry-1", "retry-1", false, true, false));
    }

    @Test public void cancelOnFailedStartObservationClosesTheWholeSession() throws IOException {
        String text = source();
        String facts = text.substring(
                text.indexOf("private SessionOrchestrator.State transitionState()"),
                text.indexOf("private SessionOrchestrator.ActionKind runStreamIntent("));
        String cancel = text.substring(text.indexOf("private void endCancelledTransition("),
                text.indexOf("static boolean isFailedStartObservation("));
        String rearm = text.substring(
                text.indexOf("private boolean rearmFailedNewGameObservation("),
                text.indexOf("private void updateRetainedTransitionIntent("));

        assertTrue(rearm.contains("failedStartObservationId = observation.id;"));
        assertTrue(facts.contains("isFailedStartObservation(failedStartObservationId,"));
        String close = cancel.substring(cancel.indexOf(
                "if (decided == SessionOrchestrator.ActionKind.CLOSE_HOST_SESSION) {"),
                cancel.indexOf(
                        "if (decided == SessionOrchestrator.ActionKind.CLOSE_STARTED_SESSION) {"));
        assertTrue(close.contains("terminateWholeSessionVerified(null)"));
        assertFalse(cancel.contains("openConsoleHome()"));
        assertTrue(Game.isFailedStartObservation("observation-1", "observation-1"));
        assertFalse(Game.isFailedStartObservation("", ""));
        assertFalse("ordinary retained observation keeps its dashboard return",
                Game.isFailedStartObservation("observation-1", "observation-2"));
    }

    @Test public void refusedAuthorizationAfterAStoppedGameRetriesOnceThenOffersRetry()
            throws IOException {
        String text = source();
        String handoff = text.substring(text.indexOf("private void runRetainedActorHandoff("),
                text.indexOf("static final long RETAINED_AUTHORIZATION_RETRY_DELAY_MS"));
        String cancel = text.substring(
                text.indexOf("private SessionOrchestrator.ActionKind runStreamIntent("),
                text.indexOf("private void endCancelledTransition("));
        String settle = text.substring(
                text.indexOf("private boolean settleOfferedAuthorizationRetry("),
                text.indexOf("ProviderProcessIdentity\n            resolveRetainedProviderIdentity("));

        assertTrue(handoff.contains("HttpURLConnection.HTTP_CONFLICT"));
        assertTrue(handoff.contains("SystemClock.sleep(RETAINED_AUTHORIZATION_RETRY_DELAY_MS)"));
        assertTrue(handoff.contains("offerRetainedAuthorizationRetry(operation)"));
        assertOrdered(cancel, "case DECLINE_AUTHORIZATION_RETRY:",
                "settleOfferedAuthorizationRetry(false)");
        assertOrdered(cancel, "case ACCEPT_AUTHORIZATION_RETRY:",
                "settleOfferedAuthorizationRetry(true)");
        assertOrdered(cancel, "settleOfferedAuthorizationRetry(true)",
                "case RETRY_RETAINED_SWITCH:");
        assertTrue(settle.contains("settleStartFailedSwitch(operation)"));
    }

    @Test public void childIsNotOfferedToEndThePreviousLaunchGame() throws IOException {
        String text = source();
        String render = text.substring(
                text.indexOf("snapshot.state == LaunchTransitionState.PREVIOUS_GAME_RUNNING) {\n"
                        + "            String previousName"),
                text.indexOf("} else if (snapshot.state == LaunchTransitionState.ERROR"));

        assertTrue(render.contains("offersPreviousGameStop(isChildSession())"));
        assertTrue(render.contains("R.string.transition_previous_game_running_title_named"));
        assertTrue(text.contains("&& SessionOrchestrator.offersPreviousGameStop(\n"
                + "                                            isChildSession())) {\n"
                + "                                transitionCoordinator.stopPreviousGame();"));
    }

    @Test public void failedStartObservationSurvivesRecreation() throws IOException {
        String text = source();
        String save = text.substring(text.indexOf("protected void onSaveInstanceState("),
                text.indexOf("private void recordActivityIdentity("));
        String create = text.substring(text.indexOf("protected void onCreate("),
                text.indexOf("readChildSessionExtras();", text.indexOf("protected void onCreate(")));

        assertTrue(save.contains("STATE_FAILED_START_OBSERVATION_ID, failedStartObservationId"));
        assertTrue(create.contains("savedInstanceState.getString(\n"
                + "                    STATE_FAILED_START_OBSERVATION_ID"));
    }

    @Test public void retriedStartContinuesOnlyWhileItsCoverAndTransitionStand() {
        assertEquals(Game.RetryStep.START_GAME,
                Game.afterRetryCleanup("t1", "t1", false, false, true));
        // The failed attempt's game could not be stopped: only observed, never started again.
        assertEquals(Game.RetryStep.OBSERVE,
                Game.afterRetryCleanup("t1", "t1", false, false, false));
        for (boolean cleaned : new boolean[] {true, false}) {
            assertEquals("cancel", Game.RetryStep.NONE,
                    Game.afterRetryCleanup("t1", "t1", true, false, cleaned));
            assertEquals("newer transition", Game.RetryStep.NONE,
                    Game.afterRetryCleanup("t1", "t2", false, false, cleaned));
            assertEquals("no transition", Game.RetryStep.NONE,
                    Game.afterRetryCleanup("t1", "", false, false, cleaned));
            assertEquals("revealed by hand during the cleanup", Game.RetryStep.NONE,
                    Game.afterRetryCleanup("t1", "t1", false, true, cleaned));
        }
    }

    @Test public void restartAfterADeadTransportNeverStartsARunningGameTwice()
            throws IOException {
        // The rule: LaunchTransitionControllerTest.restartOfAConfirmedCommittedGameOnlyReconnectsToIt.
        String game = source();
        String restart = game.substring(game.indexOf("private void restartStreamForRetry()"),
                game.indexOf("private boolean retryStillCurrent("));
        // Decided before the dead transport's cleanup is ordered; no warm-up identity rides on.
        assertOrdered(restart, "restartType(transitionSpec.type,",
                "transitionCoordinator.onStreamFailed()");
        assertTrue(restart.contains("removeAutoWarmUpExtras(retry);"));
        assertFalse(restart.contains("transitionSpec.type,\n                transitionSpec.sun"));
    }

    @Test public void retryFenceSurvivesRecreationAndUnownedTransportIsStopped()
            throws IOException {
        String game = source();
        String create = game.substring(game.indexOf("protected void onCreate("),
                game.indexOf("private MediaCodecDecoderRenderer createDecoderRenderer"));
        String save = game.substring(game.indexOf("protected void onSaveInstanceState("),
                game.indexOf("if (transitionCoordinator != null)",
                        game.indexOf("protected void onSaveInstanceState(")));
        String retry = game.substring(game.indexOf("private void restartStreamForRetry()"),
                game.indexOf("private void retryGameStart()"));
        String destroy = game.substring(game.indexOf("protected void onDestroy()"),
                game.indexOf("if (transitionCoordinator != null)",
                        game.indexOf("protected void onDestroy()")));

        assertTrue(create.contains("savedInstanceState.getString(STATE_RETRY_TRANSITION_ID"));
        assertTrue(save.contains("outState.putString(STATE_RETRY_TRANSITION_ID"));
        assertTrue(retry.contains("if (!retryStillCurrent(retryId)) return;"));
        String cancel = game.substring(
                game.indexOf("private SessionOrchestrator.ActionKind runStreamIntent("),
                game.indexOf("private void endCancelledTransition("));
        // A repeated Retry of the same transition is decided by the table alone.
        assertTrue(game.contains("state.retryStarted = transitionSpec != null\n"
                + "                && transitionSpec.id.equals(retryTransitionId);"));
        assertTrue(cancel.contains("retryTransitionId = transitionSpec.id;"));
        assertFalse(cancel.contains("UserIntent.CANCEL;"));
        // An observation retry that could not begin leaves the offered Retry usable.
        String observe = game.substring(game.indexOf("private void retryLiveObservation()"),
                game.indexOf("private void presentRetryAttempt("));
        assertOrdered(observe, "retryTransitionId = \"\";",
                "transitionController.error(previousSpec.id,");
        assertOrdered(cancel, "retryTransitionId = \"\"",
                "switch (decided)");
        assertTrue(cancel.contains("retainedSwitch.retryRequested = false;"));
        assertOrdered(cancel, "retainedSwitch.retryRequested = false;",
                "requestRetainedSwitchCancellation(retainedSwitch)");
        // Destroy hands the transport to the runtime: survives only when retained and
        // protected, otherwise the runtime stops it (StreamRuntimeTest).
        assertTrue(destroy.contains("runtime.presentationLost(attachment, isFinishing())"));
        assertFalse(destroy.contains("stopConnection"));
    }

    @Test public void surfaceLossDetachIsReattachedWhetherOrNotTheParkHappened()
            throws IOException {
        String text = source();
        String destroyed = text.substring(text.indexOf("public void surfaceDestroyed("),
                text.indexOf("public void mouseMove("));
        String created = text.substring(text.indexOf("public void surfaceCreated("),
                text.indexOf("public void surfaceDestroyed("));
        String restore = text.substring(text.indexOf("private void restoreParkedStream("),
                text.indexOf("private void scheduleRetainedRestoreWatchdog("));

        assertOrdered(destroyed, "runtime.surfaceLost(attachment)",
                "SessionEvent.ParkRequested.byOwner(");
        assertTrue(destroyed.contains("decoderDetachedBySurfaceLoss = true"));
        assertTrue(created.contains("backgroundStreamParked || decoderDetachedBySurfaceLoss"));
        assertTrue(restore.contains("decoderDetachedBySurfaceLoss = false"));
        // A session that is ending is never reattached, and every end forgets the detach.
        assertOrdered(restore, "if (!isTransportConnected() || userInitiatedDisconnect)",
                "runtime.attachDecoder(");
        assertTrue(text.split("decoderDetachedBySurfaceLoss = false", -1).length - 1 >= 6);
        assertTrue(text.contains("public boolean canPark()"));
        assertOrdered(restore, "if (!streamHomeVisible)",
                "setInputGrabState(true)");
        assertTrue(destroyed.contains("SessionEvent.ParkRequested.byOwner(runtime,"));
        assertTrue(destroyed.contains("if (decoderRenderer != null) decoderRenderer.prepareForStop()"));
        assertTrue(restore.contains("new SessionEvent.PresentationRestored(runtime, streamSessionId)"));
        String create = text.substring(text.indexOf("beginAutoWarmUpPreparing(begun -> {"));
        create = create.substring(0, create.indexOf("});"));
        assertOrdered(create, "new SessionEvent.PreparingCancelled(",
                "openPreparingConsoleHome()");
    }
}
