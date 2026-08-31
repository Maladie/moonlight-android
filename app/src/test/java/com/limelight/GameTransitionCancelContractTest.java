package com.limelight;

import org.junit.Test;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.stream.RetainedStreamSessionCoordinator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameTransitionCancelContractTest {
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
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String method = text.substring(text.indexOf("private void cancelTransition()"),
                text.indexOf("private boolean returnRetainedObservationToDashboard()"));

        assertFalse(method.contains("doAfterNextFrame"));
        assertTrue(method.contains("transitionCancelInFlight = true"));
        assertTrue(method.contains("transitionCoordinator.cancel()"));
        assertTrue(method.contains("SessionResumeManager.clearIfMatches"));
        assertTrue(method.indexOf("stopProviderGame") < method.indexOf("stopConnection"));
        assertTrue(method.contains("stopConnection(finishOnce)"));
    }

    @Test public void retainedSessionStopsProviderGameBeforeTransport() throws IOException {
        String text = source();
        String terminate = text.substring(text.indexOf(
                        "public void terminateRetainedSession("),
                text.indexOf("private interface ProviderStopCallback"));
        String helper = text.substring(text.indexOf(
                        "private void terminateWholeSessionVerified("),
                text.indexOf("private void verifySunshineSessionStopped("));
        String finish = text.substring(text.indexOf(
                        "private void finishWholeSessionTermination("),
                text.indexOf("private void restoreParkedStream("));

        assertTrue(terminate.contains("terminateWholeSessionVerified("));
        assertTrue(helper.contains(
                "else stopVerifiedProviderGame(expectedHostId, expectedGameId, afterProvider)"));
        assertTrue(helper.contains("verifySunshineSessionStopped("));
        assertTrue(finish.contains("if (success)"));
        assertTrue(finish.indexOf("completion.complete(true)")
                < finish.indexOf("stopConnection(() ->"));
    }

    @Test public void normalProviderQuitAttemptsStopBeforeClosingSunshine() throws IOException {
        String text = source();
        String close = text.substring(text.indexOf("private void closeStreamWithPrivacy("),
                text.indexOf("private void stopConnection(Runnable afterStopped)"));
        String helper = text.substring(text.indexOf(
                        "private void terminateWholeSessionVerified("),
                text.indexOf("private void verifySunshineSessionStopped("));

        assertTrue(close.contains("if (quitApplication)"));
        assertTrue(close.contains("terminateWholeSessionVerified(null)"));
        assertFalse(close.contains("pendingApplicationQuit"));
        assertFalse(close.contains("doQuit()"));
        assertTrue(helper.indexOf("consoleLoadingView.showOpaque()")
                < helper.indexOf("consoleLoadingView.doAfterNextFrame(stopProvider)"));
        assertFalse(helper.contains("closeStreamWithPrivacy(true)"));
        String provider = text.substring(text.indexOf(
                        "private void stopVerifiedProviderGame("),
                text.indexOf("private boolean isCurrentWholeSessionTermination("));
        assertTrue(provider.indexOf("gateway.snapshot()")
                < provider.indexOf("gateway.stopGame(exactGame)"));
        assertTrue(provider.contains("PlayniteIdentityResolutionPolicy.verifiedStopTarget("));
        assertTrue(provider.contains("expectedGameId.isEmpty()"));
        assertTrue(provider.contains("\"idle\".equalsIgnoreCase(current.gameState)"));
    }

    @Test public void directQuitPublishesClosingStateUntilSunshineResponds()
            throws IOException {
        String text = source();
        String helper = text.substring(text.indexOf(
                        "private void terminateWholeSessionVerified("),
                text.indexOf("private void restoreParkedStream("));
        String verify = helper.substring(helper.indexOf(
                        "private void verifySunshineSessionStopped("),
                helper.indexOf("private boolean isCurrentWholeSessionTermination("));

        assertTrue(helper.contains("boolean marked = RetainedStreamSessionCoordinator"
                + ".markTerminating("));
        assertFalse(helper.contains("if (!marked)"));
        assertTrue(verify.indexOf("getComputerDetails(true)")
                < verify.indexOf("http.quitApp()"));
        assertTrue(verify.contains("freshAppId != expectedAppId"));
        assertTrue(verify.indexOf("http.quitApp()")
                < verify.lastIndexOf("getComputerDetails(true)"));
        assertFalse(verify.contains("ServerHelper.doQuit"));
        String terminated = text.substring(text.indexOf(
                        "public void connectionTerminated("),
                text.indexOf("public void connectionStatusUpdate("));
        assertTrue(text.contains("private volatile boolean wholeSessionTerminationInFlight"));
        assertTrue(terminated.indexOf("if (wholeSessionTerminationInFlight)")
                < terminated.indexOf("cleanupUnrevealedProviderLaunch("));
        String verifiedGuard = terminated.substring(terminated.indexOf(
                "if (wholeSessionTerminationInFlight)"), terminated.indexOf(
                "cleanupUnrevealedProviderLaunch("));
        assertTrue(verifiedGuard.contains("stopConnection()"));
        assertFalse(verifiedGuard.contains("finish()"));
        assertFalse(verifiedGuard.contains("cleanupUnrevealedProviderLaunch"));
        assertFalse(verifiedGuard.contains(
                "ML_ERROR_GRACEFUL_TERMINATION"));
        String failure = helper.substring(helper.indexOf(
                "private void finishWholeSessionTermination("));
        assertTrue(failure.contains("if (connected && !streamHomeVisible"));
    }

    @Test public void observedProviderExitSettlesNeutralWithoutQuittingSunshine() throws IOException {
        Path game = Paths.get("src/main/java/com/limelight/Game.java");
        Path coordinator = Paths.get(
                "src/main/java/com/limelight/console/ConsoleStreamTransitionCoordinator.java");
        if (!Files.exists(game)) game = Paths.get("app/src/main/java/com/limelight/Game.java");
        if (!Files.exists(coordinator)) coordinator = Paths.get(
                "app/src/main/java/com/limelight/console/ConsoleStreamTransitionCoordinator.java");
        String gameText = new String(Files.readAllBytes(game), StandardCharsets.UTF_8);
        String coordinatorText = new String(
                Files.readAllBytes(coordinator), StandardCharsets.UTF_8);

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
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String listener = text.substring(text.indexOf("private void installFrameRenderedListener()"),
                text.indexOf("public void requestNextFrameRendered"));

        assertTrue(listener.contains("nextFrameRenderedCallback.getAndSet(null)"));
        assertFalse(listener.contains("setOnFrameRenderedListener(null"));
    }

    @Test public void retainedGameAttemptArmsOneExactPostOpaqueFrameProof()
            throws IOException {
        String text = source();
        String attempt = text.substring(text.indexOf(
                        "private void beginNewRetainedGameAttempt("),
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
                RetainedStreamSessionCoordinator.State.HOME_LIVE, true));
        assertFalse(Game.isForegroundRetainedObservation(
                true, false, false, LaunchTransitionType.GAME_CONNECTION, "game-a",
                RetainedStreamSessionCoordinator.State.PARKED_LIVE, true));
        assertFalse(Game.isForegroundRetainedObservation(
                true, false, true, LaunchTransitionType.GAME_CONNECTION, "game-a",
                RetainedStreamSessionCoordinator.State.HOME_LIVE, true));

        assertTrue(Game.shouldOpenRetainedObservationDashboard(
                "gc-a", "game-a", "gc-a", "game-a", true));
        assertFalse(Game.shouldOpenRetainedObservationDashboard(
                "gc-a", "game-a", "game-b", "game-b", true));
        assertFalse(Game.shouldOpenRetainedObservationDashboard(
                "gc-a", "game-a", "gc-b", "game-b", true));
        assertFalse(Game.shouldOpenRetainedObservationDashboard(
                "gc-a", "game-a", "gc-a", "game-a", false));
    }

    @Test public void connectingStateMakesInFlightTransportStoppable() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String start = text.substring(text.indexOf("private void startConnectionIfReady("),
                text.indexOf("public void surfaceCreated("));
        String stop = text.substring(text.indexOf("private void stopConnection(Runnable"),
                text.indexOf("public void stageFailed"));

        assertTrue(start.indexOf("connecting = true") < start.indexOf("conn.start("));
        assertTrue(stop.contains("if (connecting || connected)"));
        assertTrue(stop.contains("conn.stop()"));
    }

    @Test public void providerObservationIsArmedBeforeTransportCanReportConnected()
            throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String setup = text.substring(text.indexOf("consoleLoadingView.doAfterNextFrame(() ->"),
                text.indexOf("} else {", text.indexOf("consoleLoadingView.doAfterNextFrame(() ->")));

        assertTrue(setup.indexOf("transitionCoordinator.start()")
                < setup.indexOf("startConnectionIfReady("));
    }

    @Test public void earlyProviderStartIsCleanedWhenTransportSetupFails()
            throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
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
                text.indexOf("private void verifySunshineSessionStopped("));

        assertTrue(failed.contains("if (cleanupUnrevealedProviderLaunch("));
        assertTrue(terminated.contains("if (cleanupUnrevealedProviderLaunch("));
        assertTrue(cleanup.contains("freshOwnedFailureCleanupInFlight.compareAndSet(false, true)"));
        assertTrue(callback.indexOf("isFreshOwnedFailureCleanup(")
                < callback.indexOf("RetainedSwitch operation"));
        assertTrue(callback.contains("terminateWholeSessionVerified(null, true, false)"));
        assertTrue(terminate.contains("if (providerAlreadyStopped) afterProvider.complete(true)"));
        assertTrue(terminate.contains("else stopVerifiedProviderGame("));
        assertTrue(terminate.contains(
                "terminateWholeSessionVerified(completion, false, true)"));
        String finish = text.substring(text.indexOf(
                        "private void finishWholeSessionTermination("),
                text.indexOf("private void restoreParkedStream("));
        String freshFailure = finish.substring(finish.indexOf(
                        "if (!restoreRetainedOnFailure)"),
                finish.indexOf("if (connected && !streamHomeVisible"));
        assertTrue(freshFailure.contains("RetainedStreamSessionCoordinator.clearIfMatches"));
        assertTrue(freshFailure.contains("SessionResumeManager.clearIfMatches"));
        assertTrue(freshFailure.contains("stopConnection()"));
        assertFalse(freshFailure.contains("enterHome("));
        assertFalse(freshFailure.contains("openConsoleHome("));
    }

    @Test public void intentionalReconnectDoesNotStopProviderGameOrStartItAgain()
            throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
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
                text.indexOf("private void pollRetainedSwitchCancellation("));
        String setup = text.substring(text.indexOf("private void applyRetainedTransition("),
                text.indexOf("private boolean advancePreparingSwitch("));

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
                < provider.indexOf("gateway.stopGame(exactGame)"));
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
        assertTrue(cleanup.contains("boolean returnToDashboard = operation.consoleSignalled.get()"));
        assertTrue(cleanup.contains("if (returnToDashboard) openConsoleHome()"));
        assertTrue(cleanup.contains("updateRetainedGame(operation, gameId, \"\")"));
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
                < renderedFrame.indexOf("updateRetainedGame(operation, gameId, \"\")"));
        assertTrue(renderedFrame.indexOf("operation.newSpec.id.equals(transitionId)")
                < renderedFrame.indexOf("updateRetainedGame(operation, gameId, \"\")"));
        assertTrue(renderedFrame.indexOf(
                        "operation.request.newGameId")
                < renderedFrame.indexOf("updateRetainedGame(operation, gameId, \"\")"));
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

        assertTrue(eligibility.contains("connected && streamEverRevealed"));
        assertTrue(eligibility.contains("EXTRA_NEUTRAL_STREAM_TARGET"));
        assertTrue(eligibility.contains("hasProviderGameTransition()"));
        assertTrue(eligibility.contains("retainedSwitch == null"));
        assertTrue(stop.indexOf("consoleLoadingView.showOpaque()")
                < stop.indexOf("gateway.snapshot()"));
        assertTrue(stop.contains("PlayniteIdentityResolutionPolicy.verifiedStopTarget("));
        assertTrue(stop.indexOf("gateway.stopGame(exactGame)")
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
        assertTrue(neutral.contains("SessionResumeManager.saveActive"));
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
                < retained.indexOf("retainNeutralAfterGameStopped("));
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
                text.indexOf("public void terminateRetainedSession("));

        assertTrue(neutral.contains("if (parked)"));
        assertTrue(neutral.contains("SessionResumeManager.save("));
        assertTrue(neutral.contains("SessionResumeManager.saveActive("));
        assertTrue(parking.contains("BackgroundStreamPreferences.readMinutes(this) == 0"));
        assertTrue(parking.contains("int retentionMinutes = BackgroundStreamPreferences.readMinutes(this)"));
        assertTrue(parking.contains("BackgroundStreamService.park("));
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
        assertTrue(provider.contains("if (!exactGame.isEmpty()) gateway.stopGame(exactGame)"));
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
        int conflict = accepted.indexOf("if (!updateRetainedGame(");
        String conflictBranch = accepted.substring(conflict,
                accepted.indexOf("SessionResumeManager.saveActive", conflict));

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
        int cas = stopped.indexOf("if (!updateRetainedGame(");
        String staleBranch = stopped.substring(cas,
                stopped.indexOf("settleNeutralRetainedStream(operation)", cas));

        assertTrue(staleBranch.contains("completeRetainedSwitch(operation"));
        assertFalse(staleBranch.contains("SessionResumeManager.save"));
        assertFalse(staleBranch.contains("settleNeutralRetainedStream"));
        assertFalse(staleBranch.contains("beginNewRetainedGameAttempt"));
    }

    @Test public void everyAsyncRetainedIdentityMutationUsesExactSessionGuard()
            throws IOException {
        String text = source();
        String helper = text.substring(text.indexOf("private boolean updateRetainedGame("),
                text.indexOf("private void completeRetainedSwitch("));
        String callbacks = text.substring(text.indexOf(
                        "@Override public void onProviderGameStartAccepted("),
                text.indexOf("@Override public void onInstallationVerified()", text.indexOf(
                        "@Override public void onProviderGameStartAccepted(")));

        assertTrue(helper.contains("RetainedStreamSessionCoordinator.updateOwnedSwitchGame("));
        assertTrue(helper.contains("this, operation.request, expectedGameId, newGameId"));
        assertFalse(helper.contains("newGameId.equalsIgnoreCase(retained.playniteGameId)"));
        assertTrue(callbacks.split("updateRetainedGame\\(", -1).length - 1 >= 3);
    }

    private static String source() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
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
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
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
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
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
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
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
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String connected = game.substring(game.indexOf("public void connectionStarted()"),
                game.indexOf("private ConsoleStreamTransitionCoordinator createTransitionCoordinator"));
        String reveal = game.substring(game.indexOf("private void revealTransitionNow()"),
                game.indexOf("private void cancelPendingAutomaticReveal()"));

        assertTrue(connected.contains("if (transitionController == null) showOverlayMenuHint()"));
        assertTrue(reveal.indexOf("transitionController.revealCompleted")
                < reveal.indexOf("showOverlayMenuHint()"));
    }

    @Test public void connectedStreamKeepsProcessDeathRecoveryCorrelation() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String connected = game.substring(game.indexOf("public void connectionStarted()"),
                game.indexOf("private ConsoleStreamTransitionCoordinator createTransitionCoordinator"));
        String restore = game.substring(game.indexOf("private void restoreParkedStream("),
                game.indexOf("private void scheduleRetainedRestoreWatchdog("));

        assertTrue(connected.contains("SessionResumeManager.saveActive"));
        assertFalse(connected.contains("SessionResumeManager.clearIfMatches"));
        assertFalse(restore.contains("RetainedStreamSessionCoordinator.clearIfMatches"));
        assertFalse(restore.contains("SessionResumeManager.clearIfMatches"));
    }

    @Test public void lockedWindowsSessionReasonIsShownOnTheOverlay() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String status = game.substring(game.indexOf("private String transitionStatus("),
                game.indexOf("private static String playniteInstallNotificationKey("));

        int stabilizing = status.indexOf("case GAME_WINDOW_STABILIZING:");
        String branch = status.substring(stabilizing,
                status.indexOf("case LAUNCHER_INTERACTION_REQUIRED:", stabilizing));
        assertTrue(branch.contains("snapshot.detail.isEmpty()"));
        assertTrue(branch.contains(": snapshot.detail"));
    }
}
