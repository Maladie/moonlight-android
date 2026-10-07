package com.limelight;

import org.junit.Test;

import com.limelight.console.transition.LaunchTransitionState;
import com.limelight.console.transition.LaunchTransitionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test public void retainedStreamDisconnectDoesNotStopTheProviderGame() throws IOException {
        String text = source();
        Path runtimePath = Paths.get("src/main/java/com/limelight/stream/StreamRuntime.java");
        if (!Files.exists(runtimePath)) {
            runtimePath = Paths.get("app/src/main/java/com/limelight/stream/StreamRuntime.java");
        }
        String runtime = new String(Files.readAllBytes(runtimePath), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
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

    @Test public void reattachedWarmUpAdoptsOnlyAnAcceptedFrameOrRetainedWarmUp() {
        assertTrue(Game.shouldResumeAcceptedWarmUp(true, true, false));
        assertFalse("unaccepted preparing starts its own Home frame",
                Game.shouldResumeAcceptedWarmUp(true, false, true));
        assertTrue(Game.shouldResumeAcceptedWarmUp(false, false, true));
        assertFalse(Game.shouldResumeAcceptedWarmUp(false, false, false));
    }

    @Test public void repeatedSnapshotsDoNotRecoverOverlayButReadinessRegressionDoes() {
        assertFalse(Game.shouldShowTransitionOverlay(true, false, true, false));
        assertFalse(Game.shouldShowTransitionOverlay(true, true, true, true));
        assertTrue(Game.shouldShowTransitionOverlay(false, false, true, false));
        assertTrue(Game.shouldShowTransitionOverlay(true, true, true, false));
    }

    @Test public void dashboardCancellationStopsAtTheReuseOwnershipBoundary() {
        assertTrue(Game.shouldHonorExternalSwitchCancellation(false, true));
        assertFalse(Game.shouldHonorExternalSwitchCancellation(true, true));
        assertFalse(Game.shouldHonorExternalSwitchCancellation(false, false));
    }

    @Test public void cancelDoesNotWaitForAnotherVideoFrame() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String method = text.substring(text.indexOf("private void cancelTransition()"),
                text.indexOf("private boolean returnRetainedObservationToDashboard()"));

        assertFalse(method.contains("doAfterNextFrame"));
        assertTrue(method.contains("transitionCancelInFlight = true"));
        assertTrue(method.contains("transitionCoordinator.cancel()"));
        assertTrue(method.contains("new SessionEvent.ReconnectCleared(streamSessionId)"));
        assertTrue(method.indexOf("stopProviderGame") < method.indexOf("stopConnection"));
        assertTrue(method.contains("stopConnection(finishOnce)"));
    }

    @Test public void cancelStopsOnlyTheExactObservedProviderProcess() throws IOException {
        String text = source();
        String stop = text.substring(text.indexOf("private void stopProviderGame("),
                text.indexOf("private void terminateWholeSessionVerified("));

        assertTrue(stop.contains("gateway.snapshot()"));
        assertTrue(stop.contains("PlayniteIdentityResolutionPolicy.verifiedStopTarget("));
        assertTrue(stop.contains("!current.hasVerifiedProcessFor(exactGame)"));
        assertTrue(stop.contains("gateway.stopGame(exactGame, current.processToken)"));
        assertFalse(stop.contains("gateway.stopGame(gameId)"));
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

    @Test public void normalProviderQuitRunsOnlyInActivityFreePermitOwner() throws IOException {
        String text = source();
        String close = text.substring(text.indexOf("private void closeStreamWithPrivacy("),
                text.indexOf("private void stopConnection(Runnable afterStopped)"));
        String helper = text.substring(text.indexOf(
                        "private void terminateWholeSessionVerified("),
                text.indexOf("private void applyImmediateTerminalPrivacy("));
        Path path = Paths.get("src/main/java/com/limelight/stream/HostSessionTermination.java");
        if (!Files.exists(path)) path = Paths.get(
                "app/src/main/java/com/limelight/stream/HostSessionTermination.java");
        String host = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertTrue(close.contains("if (quitApplication)"));
        assertTrue(close.contains("terminateWholeSessionVerified(null)"));
        assertFalse(close.contains("pendingApplicationQuit"));
        assertFalse(close.contains("doQuit()"));
        assertTrue(helper.contains("HostSessionTermination.run("));
        assertFalse(helper.contains("closeStreamWithPrivacy(true)"));
        assertTrue(host.indexOf("control.acquire(") < host.indexOf("provider.stopEvidenceWithinControl("));
        assertTrue(host.indexOf("provider.stopEvidenceWithinControl(") < host.indexOf("provider.stopGameWithinControl("));
        assertTrue(host.indexOf("provider.stopGameWithinControl(") < host.indexOf("http.quitApp()"));
        assertTrue(host.contains("provider_game_mismatch"));
    }

    @Test public void liveReconnectWithoutWarmUpRelinquishesOnlyItsOwnMarker()
            throws IOException {
        String text = source();
        String started = text.substring(text.indexOf("public void connectionStarted()"),
                text.indexOf("private void relinquishReconnectedMarker()"));
        String direct = started.substring(started.indexOf("if (!warmUpConnection)"),
                started.indexOf("completeAutoWarmUpPreparing("));
        assertTrue(direct.indexOf("relinquishReconnectedMarker()")
                < direct.indexOf("onStreamConnected("));
        assertFalse(started.substring(started.indexOf("completeAutoWarmUpPreparing("))
                .contains("relinquishReconnectedMarker"));
        String relinquish = text.substring(
                text.indexOf("private void relinquishReconnectedMarker()"),
                text.indexOf("private void onStreamConnected("));
        assertTrue(relinquish.contains("\"reconnect\".equals("));
        assertTrue(relinquish.contains("marked.state != SessionModel.State.RECONNECT_REQUIRED"));
        assertTrue(relinquish.contains("marked.streamSessionId.equals(streamSessionId)"));
        assertTrue(relinquish.contains("marked.playniteGameId.equalsIgnoreCase("));
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
        assertTrue(helper.contains("terminateWholeSessionVerified(completion, false, false)"));
        assertTrue(helper.contains("HostSessionTermination.run("));
        assertFalse(helper.contains("quitApp("));
        // The model runs this for a requesting console; Game must not clear it by opening Home.
        String requested = text.substring(text.indexOf(
                        "public void terminateRetainedSession("),
                text.indexOf("public void switchGame("));
        assertTrue(requested.contains("}, false, true);"));
        assertFalse(requested.contains("openTerminalConsoleHome"));
        String home = text.substring(text.indexOf("private void openTerminalConsoleHome("),
                text.indexOf("private void restoreParkedStream("));
        assertTrue(home.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP"));
        Path homePath = Paths.get("src/main/java/com/limelight/console/StreamHomeActivity.java");
        if (!Files.exists(homePath)) homePath = Paths.get("app").resolve(homePath);
        String streamHome = new String(Files.readAllBytes(homePath), StandardCharsets.UTF_8);
        assertTrue(streamHome.contains("protected void onRequestedStreamClosed()"));
        assertTrue(streamHome.contains("EXTRA_SUPPRESS_AUTO_WARM_UP, true"));
        Path consolePath = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        if (!Files.exists(consolePath)) consolePath = Paths.get("app").resolve(consolePath);
        String console = new String(Files.readAllBytes(consolePath), StandardCharsets.UTF_8);
        // Host-panel closes show progress until done; a failure dismissed over a closed
        // stream hands the Home over instead of leaving a stream Home without a stream.
        for (String request : new String[] {"private void requestTerminateSession(ComputerDetails"
                + " host, String", "private void requestCloseHostStream("}) {
            String body = console.substring(console.indexOf(request),
                    console.indexOf("sessionModel.dispatch(", console.indexOf(request)));
            assertTrue(body.contains("showSidePanelBusy("));
        }
        String cancel = console.substring(console.indexOf("@Override public void onCancel()"),
                console.indexOf("@Override public void onRetry()"));
        assertTrue(cancel.contains("onRequestedStreamClosed();"));
        assertTrue(cancel.contains("launchedExtra(EXTRA_RETAINED_STREAM_SESSION_ID)"));
        String terminated = text.substring(text.indexOf(
                        "public void connectionTerminated("),
                text.indexOf("public void connectionStatusUpdate("));
        assertFalse(text.contains("wholeSessionTerminationInFlight"));
        assertTrue(text.contains("WholeSessionTermination.Owner wholeSessionTermination"));
        assertTrue(terminated.indexOf("if (wholeSessionTermination.isRunning())")
                < terminated.indexOf("cleanupUnrevealedProviderLaunch("));
        String verifiedGuard = terminated.substring(terminated.indexOf(
                "if (wholeSessionTermination.isRunning())"), terminated.indexOf(
                "cleanupUnrevealedProviderLaunch("));
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
        if (!Files.exists(game)) game = Paths.get("app/src/main/java/com/limelight/Game.java");
        if (!Files.exists(coordinator)) coordinator = Paths.get(
                "app/src/main/java/com/limelight/console/ConsoleStreamTransitionCoordinator.java");
        String gameText = new String(Files.readAllBytes(game), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String coordinatorText = new String(
                Files.readAllBytes(coordinator), StandardCharsets.UTF_8).replace("\r\n", "\n");

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

    @Test public void renderedFrameProofCanBeRearmedAfterTheFirstFrame() throws IOException {
        Path source = Paths.get(
                "src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java");
        if (!Files.exists(source)) source = Paths.get(
                "app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String listener = text.substring(text.indexOf("private void installFrameRenderedListener()"),
                text.indexOf("public void requestNextFrameRendered"));

        assertTrue(listener.contains("nextFrameRenderedCallback.getAndSet(null)"));
        assertFalse(listener.contains("setOnFrameRenderedListener(null"));
    }

    @Test public void retainedGameAttemptArmsOneExactPostOpaqueFrameProof()
            throws IOException {
        String text = source();
        String attempt = text.substring(text.indexOf(
                        "private void startRetainedGameAttempt("),
                text.indexOf("private void pollRetainedSwitchCancellation("));
        String arm = text.substring(text.indexOf(
                        "private void armNextVideoFrameForTransition("),
                text.indexOf("private void retryTransition()"));
        String snapshot = text.substring(text.indexOf(
                        "private void applyTransitionSnapshot("),
                text.indexOf("private void scheduleAutomaticReveal()"));

        assertTrue(attempt.indexOf("consoleLoadingView.showOpaque()")
                < attempt.indexOf("consoleLoadingView.doAfterNextFrame("));
        assertTrue(attempt.indexOf("consoleLoadingView.doAfterNextFrame(")
                < attempt.indexOf("applyRetainedTransition(operation, next"));
        assertTrue(attempt.indexOf("armNextVideoFrameForTransition(next.id")
                < attempt.indexOf("transitionCoordinator.start()"));
        assertTrue(text.contains("AtomicReference<String> armedVideoFrameTransitionId"));
        assertTrue(arm.contains("transitionId.equals(armed)"));
        assertTrue(arm.contains("compareAndSet(transitionId, \"\")"));
        assertTrue(arm.indexOf("transitionSpec.id.equals(transitionId)")
                < arm.indexOf("transitionController.videoFrameRendered(transitionId)"));
        assertTrue(arm.contains("video_frame.armed"));
        assertTrue(arm.contains("video_frame.rendered"));
        assertTrue(arm.contains("video_frame.stale"));
        assertTrue(snapshot.contains(
                "armNextVideoFrameForTransition(snapshot.spec.id, \"transition-gate\")"));
        assertFalse(snapshot.contains(
                "transitionController.videoFrameRendered(transitionSpec.id)"));
    }

    @Test public void exactRetainedObservationCancelReturnsHomeWithoutStoppingAnything()
            throws IOException {
        String text = source();
        String cancel = text.substring(text.indexOf("private void cancelTransition()"),
                text.indexOf("private void retryTransition()"));
        String retainedReturn = cancel.substring(cancel.indexOf(
                        "private boolean returnRetainedObservationToDashboard()"),
                cancel.indexOf("private void armNextVideoFrameForTransition("));
        String eligibility = cancel.substring(cancel.indexOf(
                        "private boolean isExactLiveRetainedObservation()"),
                cancel.indexOf("private void armNextVideoFrameForTransition("));

        assertTrue(cancel.indexOf("returnRetainedObservationToDashboard()")
                < cancel.indexOf("transitionController.cancel("));
        assertTrue(retainedReturn.indexOf("showOpaque()")
                < retainedReturn.indexOf("doAfterNextFrame("));
        assertTrue(retainedReturn.contains("if (retainedDashboardReturnInFlight) return true"));
        assertTrue(retainedReturn.contains("openConsoleHome()"));
        assertFalse(retainedReturn.contains("cancelTransition()"));
        assertFalse(retainedReturn.contains("stopConnection"));
        assertFalse(retainedReturn.contains("stopGame"));
        assertFalse(retainedReturn.contains("quitApp"));
        assertTrue(eligibility.contains("LaunchTransitionType.GAME_CONNECTION"));
        assertTrue(eligibility.contains("ownsLiveOrParkedSession("));
        String back = text.substring(text.indexOf("public void onBackPressed()"),
                text.indexOf("public boolean handleKeyDown("));
        assertTrue(back.contains("returnRetainedObservationToDashboard()"));
    }

    @Test public void retainedObservationDashboardReturnRequiresExactForegroundHomeLive() {
        assertTrue(Game.isForegroundRetainedObservation(
                true, false, false, LaunchTransitionType.GAME_CONNECTION, "game-a",
                com.limelight.session.SessionModel.State.HOME_LIVE, true));
        assertFalse(Game.isForegroundRetainedObservation(
                true, false, false, LaunchTransitionType.GAME_CONNECTION, "game-a",
                com.limelight.session.SessionModel.State.PARKED_LIVE, true));
        assertFalse(Game.isForegroundRetainedObservation(
                true, false, true, LaunchTransitionType.GAME_CONNECTION, "game-a",
                com.limelight.session.SessionModel.State.HOME_LIVE, true));

        assertTrue(Game.shouldOpenRetainedObservationDashboard(
                "gc-a", "game-a", "gc-a", "game-a", true));
        assertFalse(Game.shouldOpenRetainedObservationDashboard(
                "gc-a", "game-a", "game-b", "game-b", true));
        assertFalse(Game.shouldOpenRetainedObservationDashboard(
                "gc-a", "game-a", "gc-b", "game-b", true));
        assertFalse(Game.shouldOpenRetainedObservationDashboard(
                "gc-a", "game-a", "gc-a", "game-a", false));
    }

    @Test public void backOnTimedOutRevealedStreamReturnsToDashboardWithoutStoppingProvider()
            throws IOException {
        assertTrue(Game.shouldReturnTimedOutRevealedStreamToDashboard(
                true, false, true, LaunchTransitionState.TIMED_OUT));
        assertFalse(Game.shouldReturnTimedOutRevealedStreamToDashboard(
                true, false, false, LaunchTransitionState.TIMED_OUT));
        assertFalse(Game.shouldReturnTimedOutRevealedStreamToDashboard(
                false, false, true, LaunchTransitionState.TIMED_OUT));
        assertFalse(Game.shouldReturnTimedOutRevealedStreamToDashboard(
                true, true, true, LaunchTransitionState.TIMED_OUT));
        assertFalse(Game.shouldReturnTimedOutRevealedStreamToDashboard(
                true, false, true, LaunchTransitionState.GAME_STOPPING));
        assertFalse(Game.shouldReturnTimedOutRevealedStreamToDashboard(
                true, false, true, null));

        String text = source();
        String keyDown = text.substring(text.indexOf("public boolean handleKeyDown("),
                text.indexOf("if (isImeVisible", text.indexOf("public boolean handleKeyDown(")));
        // The loading view turns Back/B key-down into a Cancel click, so the decision must
        // precede its delegation; its key-up handling then consumes the matching release.
        int backDown = keyDown.indexOf("isBackKeyDown(event)");
        int decision = keyDown.indexOf("returnTimedOutRevealedStreamToDashboard()");
        int delegation = keyDown.indexOf("consoleLoadingView.handleControllerKey(event)");
        assertTrue(backDown >= 0 && decision > backDown && delegation > decision);
        String back = text.substring(text.indexOf("public void onBackPressed()"),
                text.indexOf("public boolean handleKeyDown("));
        assertTrue(back.indexOf("returnTimedOutRevealedStreamToDashboard()") >= 0
                && back.indexOf("returnTimedOutRevealedStreamToDashboard()")
                < back.indexOf("returnRetainedObservationToDashboard()"));
        String helper = text.substring(text.indexOf("private boolean returnTimedOutRevealedStreamToDashboard()"),
                text.indexOf("private boolean isTimedOutRevealedStreamForeground()"));
        assertTrue(helper.contains("isTimedOutRevealedStreamForeground()"));
        assertTrue(helper.contains("if (timedOutRevealedReturnInFlight) return true"));
        // The timeout is published on the observer thread and covered on the UI thread later:
        // cover first, navigate only after the frame, and recheck identity/state then.
        int cover = helper.indexOf("consoleLoadingView.showOpaque()");
        int frame = helper.indexOf("consoleLoadingView.doAfterNextFrame(");
        int navigate = helper.indexOf("openConsoleHome();");
        assertTrue(cover >= 0 && frame > cover && navigate > frame);
        assertTrue(helper.contains("shouldOpenTimedOutRevealedDashboard("));
        assertFalse(helper.contains("cancelTransition()"));
        assertFalse(helper.contains("stopProviderGame("));
        assertTrue(Game.shouldOpenTimedOutRevealedDashboard("t-a", "t-a", true));
        assertFalse(Game.shouldOpenTimedOutRevealedDashboard("t-a", "t-b", true));
        assertFalse(Game.shouldOpenTimedOutRevealedDashboard("t-a", "t-a", false));
        assertFalse(Game.shouldOpenTimedOutRevealedDashboard(null, "", true));
        assertFalse(keyDown.contains("cancelTransition()"));
        String keyUp = text.substring(text.indexOf("public boolean handleKeyUp("),
                text.indexOf("if (isImeVisible", text.indexOf("public boolean handleKeyUp(")));
        assertTrue(keyUp.contains("consoleLoadingView.handleControllerKey(event)"));
        Path viewPath = Paths.get("src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        if (!Files.exists(viewPath)) {
            viewPath = Paths.get("app/src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        }
        String view = new String(Files.readAllBytes(viewPath), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String control = view.substring(view.indexOf("public boolean handleControllerKey("),
                view.indexOf("KeyEvent.KEYCODE_DPAD_LEFT", view.indexOf("public boolean handleControllerKey(")));
        assertTrue(control.contains("return isLoadingActionKey(keyCode);"));
        assertTrue(control.contains("return cancelView.performClick()"));
        int onCancel = text.indexOf("public void onCancel()");
        assertTrue(text.substring(onCancel, onCancel + 120).contains("cancelTransition();"));
        String home = text.substring(text.indexOf("private void openConsoleHome()"),
                text.indexOf("private void openPreparingConsoleHome()"));
        assertFalse(home.contains("stopProviderGame("));
        assertFalse(home.contains("stopConnection"));
        assertFalse(home.contains("revealStream"));
    }

    @Test public void connectingStateMakesInFlightTransportStoppable() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
        Path runtimePath = Paths.get("src/main/java/com/limelight/stream/StreamRuntime.java");
        if (!Files.exists(runtimePath)) {
            runtimePath = Paths.get("app/src/main/java/com/limelight/stream/StreamRuntime.java");
        }
        String runtime = new String(Files.readAllBytes(runtimePath), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String start = runtime.substring(runtime.indexOf("public void start() {"),
                runtime.indexOf("public void stop(TransportStopCoordinator.Completion"));
        String stop = text.substring(text.indexOf("private void stopConnection(Runnable"),
                text.indexOf("public void stageFailed"));

        // The runtime marks CONNECTING before the native start, so a stop always reaches it.
        assertTrue(start.indexOf("state = State.CONNECTING")
                < start.indexOf("transport.start(this)"));
        assertTrue(runtime.contains("new TransportStopCoordinator(this::stopNative"));
        assertFalse(stop.contains("conn.stop()"));
        assertTrue(stop.contains("captured.stop("));
    }

    @Test public void providerObservationIsArmedBeforeTransportCanReportConnected()
            throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String setup = text.substring(text.indexOf("consoleLoadingView.doAfterNextFrame(() ->"),
                text.indexOf("} else {", text.indexOf("consoleLoadingView.doAfterNextFrame(() ->")));

        assertTrue(setup.indexOf("transitionCoordinator.start()")
                < setup.indexOf("startConnectionIfReady("));
    }

    @Test public void earlyProviderStartIsCleanedWhenTransportSetupFails()
            throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String failed = text.substring(text.indexOf("public void stageFailed("),
                text.indexOf("public void connectionTerminated("));
        String terminated = text.substring(text.indexOf("public void connectionTerminated("),
                text.indexOf("public void connectionStatusUpdate("));

        assertTrue(failed.contains("cleanupUnrevealedProviderLaunch("));
        assertTrue(terminated.contains("cleanupUnrevealedProviderLaunch("));
        assertFalse(terminated.contains("!connected"));
        assertTrue(Game.shouldCleanupUnrevealedProviderLaunch(true, false));
        assertFalse(Game.shouldCleanupUnrevealedProviderLaunch(true, true));
        assertFalse(Game.shouldCleanupUnrevealedProviderLaunch(false, false));
    }

    @Test public void freshNeutralFailureOwnsProviderAndSunshineCleanupExactlyOnce()
            throws IOException {
        assertTrue(Game.shouldTerminateFreshOwnedSunshineSession(
                true, true, LaunchTransitionType.GAME, true, false, false));
        assertFalse(Game.shouldTerminateFreshOwnedSunshineSession(
                false, true, LaunchTransitionType.GAME, true, false, false));
        assertFalse(Game.shouldTerminateFreshOwnedSunshineSession(
                true, false, LaunchTransitionType.GAME, true, false, false));
        assertFalse(Game.shouldTerminateFreshOwnedSunshineSession(
                true, true, LaunchTransitionType.GAME_CONNECTION, false, false, false));
        assertFalse(Game.shouldTerminateFreshOwnedSunshineSession(
                true, true, LaunchTransitionType.GAME, true, true, false));
        assertFalse(Game.shouldTerminateFreshOwnedSunshineSession(
                true, true, LaunchTransitionType.GAME, true, false, true));

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
        assertTrue(callback.contains("terminateWholeSessionVerified(null, true, false)"));
        assertTrue(terminate.contains("providerAlreadyStopped"));
        assertTrue(terminate.contains("HostSessionTermination.run("));
        assertTrue(terminate.contains(
                "terminateWholeSessionVerified(completion, false, false)"));
        assertTrue(terminate.contains(
                "DirectTerminationCompleted.abandoned"));
        assertTrue(terminate.contains("stopConnectionResult("));
        assertFalse(terminate.contains("enterHome("));
        assertFalse(terminate.contains("openConsoleHome("));
    }

    @Test public void intentionalReconnectDoesNotStopProviderGameOrStartItAgain()
            throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String destroyed = text.substring(text.indexOf("protected void onDestroy()"),
                text.indexOf("super.onDestroy()"));
        String bitrate = text.substring(text.indexOf("private void applyBitrateAndReconnect("),
                text.indexOf("private void showOverlayMenuHint()"));

        assertTrue(destroyed.contains("cleanupUnrevealedProviderLaunch("));
        assertFalse(Game.shouldCleanupUnrevealedProviderLaunch(false, false));
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

        assertTrue(setup.contains("transitionController.begin(next)"));
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

        assertTrue(gate.indexOf("showOpaque()") < gate.indexOf("doAfterNextFrame"));
        assertTrue(gate.indexOf("doAfterNextFrame") < gate.indexOf("detachForSwitch()"));
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
                text.indexOf("private void retryTransition()"));
        String retry = text.substring(text.indexOf("private void retryTransition()"),
                text.indexOf("private void retryRetainedObservation()"));

        assertTrue(cancel.indexOf("retainedSwitch != null")
                < cancel.indexOf("stopConnection(finishOnce)"));
        assertTrue(cancel.contains("requestRetainedSwitchCancellation(retainedSwitch)"));
        assertTrue(retry.indexOf("retainedSwitch != null")
                < retry.indexOf("stopConnection("));
        assertTrue(retry.contains("requestRetainedSwitchCancellation(retainedSwitch)"));
        String observationRetry = text.substring(text.indexOf(
                        "private void retryRetainedObservation()"),
                text.indexOf("public void displayMessage", text.indexOf(
                        "private void retryRetainedObservation()")));
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
        assertTrue(settled.contains("boolean returnToDashboard = operation.consoleSignalled.get()"));
        assertTrue(settled.contains("if (returnToDashboard) openConsoleHome()"));
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
        String renderedFrame = acceptedExit.substring(acceptedExit.indexOf(
                "consoleLoadingView.doAfterNextFrame(() ->"));

        assertTrue(acceptedExit.contains(
                "operation.request.newGameId.equalsIgnoreCase(gameId)"));
        assertTrue(acceptedExit.indexOf("consoleLoadingView.showOpaque()")
                < acceptedExit.indexOf("consoleLoadingView.doAfterNextFrame(() ->"));
        assertTrue(renderedFrame.indexOf("isCurrentRetainedSwitch(operation)")
                < renderedFrame.indexOf("updateRetainedGame(operation, gameId, \"\","));
        assertTrue(renderedFrame.indexOf("operation.newSpec.id.equals(transitionId)")
                < renderedFrame.indexOf("updateRetainedGame(operation, gameId, \"\","));
        assertTrue(renderedFrame.indexOf(
                        "operation.request.newGameId")
                < renderedFrame.indexOf("updateRetainedGame(operation, gameId, \"\","));
        assertTrue(acceptedExit.indexOf("updateRetainedGame(operation, gameId, \"\")")
                < acceptedExit.indexOf("settleNeutralRetainedStream(operation)"));
        assertTrue(acceptedExit.indexOf("settleNeutralRetainedStream(operation)")
                < acceptedExit.indexOf("\"provider_game_exited\""));
        assertTrue(acceptedExit.contains("operation.consoleSignalled.get()"));
        assertTrue(acceptedExit.contains("if (returnToDashboard) openConsoleHome()"));
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
        assertTrue(stop.indexOf("consoleLoadingView.showOpaque()")
                < stop.indexOf("gateway.snapshot()"));
        assertTrue(stop.contains("PlayniteIdentityResolutionPolicy.verifiedStopTarget("));
        assertTrue(stop.contains("!current.hasVerifiedProcessFor(exactGame)"));
        assertTrue(stop.indexOf("gateway.stopGame(exactGame, current.processToken)")
                < stop.indexOf("retainNeutralStreamAfterNaturalGameStop("));
        assertTrue(stop.contains("providerEndGameInFlight = true"));
        assertTrue(stop.split("isFinishing\\(\\) \\|\\| isDestroyed\\(\\)", -1).length - 1 >= 2);
        assertTrue(stop.contains("retryRetainedObservation()"));
        assertFalse(stop.contains("conn.start"));
        assertFalse(stop.contains("conn.stop"));
        assertFalse(stop.contains("quitApp"));
        assertFalse(stop.contains("stopConnection"));
    }

    @Test public void stoppedOldIdentityIsPersistedAsNeutralBeforeNewStart()
            throws IOException {
        String text = source();
        String stopped = text.substring(text.indexOf("private void afterRetainedGameStopped("),
                text.indexOf("private void beginNewRetainedGameAttempt("));
        String neutral = text.substring(text.indexOf("private void settleNeutralRetainedStream("),
                text.indexOf("private void updateRetainedTransitionIntent("));

        assertTrue(stopped.indexOf("updateRetainedGame(")
                < stopped.indexOf("settleNeutralRetainedStream(operation)"));
        assertTrue(stopped.indexOf("settleNeutralRetainedStream(operation)")
                < stopped.indexOf("beginNewRetainedGameAttempt(operation)"));
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

        assertTrue(retained.indexOf("Neutral retain stale expectedTransition=")
                < retained.indexOf("consoleLoadingView.showOpaque()"));
        assertTrue(retained.indexOf("consoleLoadingView.showOpaque()")
                < retained.indexOf("transitionCoordinator.detachAfterConfirmedGameStop()"));
        assertTrue(retained.indexOf("transitionCoordinator.detachAfterConfirmedGameStop()")
                < retained.indexOf("new SessionEvent.GameStoppedRetainNeutral("));
        assertTrue(retained.contains("EXTRA_NEUTRAL_STREAM_TARGET"));
        assertTrue(retained.contains("settleNeutralRetainedStream("));
        assertTrue(retained.contains("Neutral retain stale"));
        assertTrue(retained.contains("Neutral retain stale after frame"));
        assertTrue(retained.indexOf("Neutral retain stale after frame")
                < retained.indexOf("reason=provider_owner"));
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

        assertTrue(neutral.contains("if (parked)"));
        assertTrue(neutral.contains("saveReconnect(true)"));
        assertTrue(neutral.contains("saveReconnect(false)"));
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

        assertTrue(provider.contains("operation.request.oldGameId.isEmpty()"));
        assertTrue(provider.contains("\"idle\".equalsIgnoreCase(current.gameState)"));
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
        assertTrue(recovery.contains("transitionController.begin(observation)"));
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
                stopped.indexOf("settleNeutralRetainedStream(operation)", cas));

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
                < recovery.indexOf("transitionController.begin(recovery)"));
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
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test public void revealedGameRecreatesAsObservationOnly() throws IOException {
        assertTrue(Game.recoveredTransitionType(LaunchTransitionType.GAME, true)
                == LaunchTransitionType.GAME_CONNECTION);
        assertTrue(Game.recoveredTransitionType(LaunchTransitionType.GAME, false)
                == LaunchTransitionType.GAME);
        assertTrue(Game.recoveredTransitionType(LaunchTransitionType.GAME_CONNECTION, true)
                == LaunchTransitionType.GAME_CONNECTION);
        assertFalse(Game.shouldCleanupUnrevealedProviderLaunch(false, false));

        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String reveal = game.substring(game.indexOf("private void revealTransitionNow()"),
                game.indexOf("private void cancelPendingAutomaticReveal()"));
        String retry = game.substring(game.indexOf("private void retryTransition()"),
                game.indexOf("public void displayMessage"));

        assertTrue(reveal.indexOf("streamEverRevealed = true")
                < reveal.indexOf("persistRevealedTransitionForRecovery()"));
        assertTrue(reveal.indexOf("persistRevealedTransitionForRecovery()")
                < reveal.indexOf("transitionController.revealCompleted"));
        assertTrue(retry.contains("retryRetainedObservation()"));
        String retainedRetry = retry.substring(retry.indexOf(
                "private void retryRetainedObservation()"));
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
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String status = game.substring(game.indexOf("private String transitionStatus("),
                game.indexOf("private static String playniteInstallNotificationKey("));

        int stabilizing = status.indexOf("case GAME_WINDOW_STABILIZING:");
        String branch = status.substring(stabilizing,
                status.indexOf("case LAUNCHER_INTERACTION_REQUIRED:", stabilizing));
        assertTrue(branch.contains("snapshot.detail.isEmpty()"));
        assertTrue(branch.contains(": snapshot.detail"));
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

    @Test public void retryFenceSurvivesRecreationAndUnownedTransportIsStopped()
            throws IOException {
        String game = source();
        String create = game.substring(game.indexOf("protected void onCreate("),
                game.indexOf("private MediaCodecDecoderRenderer createDecoderRenderer"));
        String save = game.substring(game.indexOf("protected void onSaveInstanceState("),
                game.indexOf("if (transitionCoordinator != null)"));
        String retry = game.substring(game.indexOf("private void retryTransition()"),
                game.indexOf("private void retryRetainedObservation()"));
        String destroy = game.substring(game.indexOf("protected void onDestroy()"),
                game.indexOf("if (transitionCoordinator != null)",
                        game.indexOf("protected void onDestroy()")));

        assertTrue(create.contains("savedInstanceState.getString(STATE_RETRY_TRANSITION_ID"));
        assertTrue(save.contains("outState.putString(STATE_RETRY_TRANSITION_ID"));
        assertTrue(retry.contains("if (!retryStillCurrent(retryId)) return;"));
        assertTrue(retry.contains("cancelTransition();"));
        String cancel = game.substring(game.indexOf("private void cancelTransition()"),
                game.indexOf("private boolean returnRetainedObservationToDashboard()"));
        assertTrue(cancel.indexOf("retryTransitionId = \"\"")
                < cancel.indexOf("returnRetainedObservationToDashboard()"));
        assertTrue(cancel.contains("retainedSwitch.retryRequested = false;"));
        assertTrue(cancel.indexOf("retainedSwitch.retryRequested = false;")
                < cancel.indexOf("requestRetainedSwitchCancellation(retainedSwitch)"));
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

        assertTrue(destroyed.indexOf("runtime.surfaceLost(attachment)")
                < destroyed.indexOf("SessionEvent.ParkRequested.byOwner("));
        assertTrue(destroyed.contains("decoderDetachedBySurfaceLoss = true"));
        assertTrue(created.contains("backgroundStreamParked || decoderDetachedBySurfaceLoss"));
        assertTrue(restore.contains("decoderDetachedBySurfaceLoss = false"));
        // A session that is ending is never reattached, and every end forgets the detach.
        assertTrue(restore.indexOf("if (!isTransportConnected() || userInitiatedDisconnect)")
                < restore.indexOf("runtime.attachDecoder("));
        assertTrue(text.split("decoderDetachedBySurfaceLoss = false", -1).length - 1 >= 6);
        assertTrue(text.contains("public boolean canPark()"));
        assertTrue(restore.indexOf("if (!streamHomeVisible)")
                < restore.indexOf("setInputGrabState(true)"));
        assertTrue(destroyed.contains("SessionEvent.ParkRequested.byOwner(runtime,"));
        assertTrue(destroyed.contains("if (decoderRenderer != null) decoderRenderer.prepareForStop()"));
        assertTrue(restore.contains("new SessionEvent.PresentationRestored(runtime, streamSessionId)"));
        String create = text.substring(text.indexOf("beginAutoWarmUpPreparing(begun -> {"));
        create = create.substring(0, create.indexOf("});"));
        assertTrue(create.indexOf("new SessionEvent.PreparingCancelled(")
                < create.indexOf("openPreparingConsoleHome()"));
    }
}
