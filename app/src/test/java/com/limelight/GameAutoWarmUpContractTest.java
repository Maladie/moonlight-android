package com.limelight;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameAutoWarmUpContractTest {
    @Test public void warmUpOpensHomeBeforeAnyConnectionOrProviderStart()
            throws IOException {
        String game = source();
        String create = between(game, "protected void onCreate(",
                "private MediaCodecDecoderRenderer createDecoderRenderer");
        String accepted = between(game, "public boolean preparingHomeFrameSubmitted(",
                "public void terminateRetainedSession(");

        // A fresh warm-up claims PREPARING before opening Home; only a reattached presentation
        // whose Home frame the coordinator already accepted skips that (resumeAcceptedWarmUp).
        assertTrue(create.indexOf("if (reattachPending && resumeAcceptedWarmUp())")
                < create.indexOf("beginAutoWarmUpPreparing("));
        assertTrue(create.indexOf("beginAutoWarmUpPreparing(")
                < create.lastIndexOf("openPreparingConsoleHome()"));
        String resume = between(game, "private boolean resumeAcceptedWarmUp()",
                "static boolean shouldResumeAcceptedWarmUp(");
        assertTrue(resume.indexOf("isPreparingHomeFrameAccepted(")
                < resume.indexOf("transitionCoordinator.start()"));
        String warmUp = create.substring(create.lastIndexOf(
                "if (hasAutoWarmUpTransportIdentity())"));
        assertFalse(warmUp.substring(0, warmUp.indexOf("} else {")).contains(
                "transitionCoordinator.start()"));
        assertFalse(warmUp.substring(0, warmUp.indexOf("} else {")).contains(
                "startConnectionIfReady("));
        assertTrue(accepted.indexOf("compareAndSet(false, true)")
                < accepted.indexOf("transitionController.overlayRendered"));
        assertTrue(accepted.indexOf("transitionController.overlayRendered")
                < accepted.indexOf("transitionCoordinator.start()"));
        assertTrue(accepted.indexOf("transitionCoordinator.start()")
                < accepted.indexOf("startConnectionIfReady("));
    }

    @Test public void warmUpUsesNeutralOpaqueGateAndRestoresGameTransitionUi()
            throws IOException {
        String game = source();
        String create = between(game, "protected void onCreate(",
                "private MediaCodecDecoderRenderer createDecoderRenderer");
        String apply = between(game, "private void applyRetainedTransition(",
                "private void advancePreparingSwitch(");

        assertTrue(create.contains("if (hasAutoWarmUpTransportIdentity() && !autoWarmUpConvertedToGame)"));
        assertTrue(create.contains("consoleLoadingView.showNeutralWarmUpAppearance()"));
        assertTrue(create.contains("else {\n"
                + "                consoleLoadingView.setSplashArtwork("));
        assertTrue(create.indexOf("consoleLoadingView.showNeutralWarmUpAppearance()")
                < create.indexOf("consoleLoadingView.doAfterNextFrame("));
        assertTrue(apply.indexOf("consoleLoadingView.showFullTransitionAppearance()")
                < apply.indexOf("transitionController.begin(next)"));
    }

    @Test public void warmUpMapsOnlyToGenericOrObservationConnection() throws IOException {
        String create = between(source(), "protected void onCreate(",
                "private MediaCodecDecoderRenderer createDecoderRenderer");
        String mapping = between(create,
                "if (hasAutoWarmUpTransportIdentity() && !autoWarmUpConvertedToGame) {",
                "LaunchTransitionType recoveredType");
        // A warm-up already converted to a selected game keeps its GAME semantics across
        // Activity recreation (the conversion is restored from the intent first).
        assertTrue(create.indexOf("getBooleanExtra(EXTRA_AUTO_WARM_UP_CONVERTED, false)")
                < create.indexOf("if (hasAutoWarmUpTransportIdentity() && !autoWarmUpConvertedToGame) {"));

        assertTrue(mapping.contains("LaunchTransitionType.GENERIC"));
        assertTrue(mapping.contains("LaunchTransitionType.GAME_CONNECTION"));
        assertFalse(mapping.contains("LaunchTransitionType.GAME;"));
    }

    @Test public void connectionStartsOnceAndCompletesOnlyFromConnectionStarted()
            throws IOException {
        String game = source();
        String start = between(game, "private void startConnectionIfReady(",
                "private void logLaunchMilestone(");
        String stages = between(game, "public void stageStarting(",
                "public void connectionStarted()");
        String connected = between(game, "public void connectionStarted()",
                "private ConsoleStreamTransitionCoordinator createTransitionCoordinator");

        String runtime = sourceFile("app/src/main/java/com/limelight/stream/StreamRuntime.java");
        String runtimeStarted = between(runtime, "@Override public void connectionStarted()",
                "@Override public void connectionTerminated(");
        // The one native start is owned by the runtime; Game only requests it once.
        assertEquals(1, occurrences(game, "runtime.start();"));
        assertEquals(1, occurrences(runtime, "transport.start(this)"));
        assertTrue(start.indexOf("attemptedConnection = true")
                < start.indexOf("runtime.start();"));
        assertFalse(stages.contains("completeAutoWarmUpPreparing"));
        // Connected state is the runtime's fact, set only from connectionStarted.
        assertTrue(runtimeStarted.contains("state = State.CONNECTED"));
        assertTrue(runtimeStarted.indexOf("state = State.CONNECTED")
                < runtimeStarted.indexOf("p.connectionStarted()"));
        assertTrue(connected.contains("completeAutoWarmUpPreparing("));
        assertTrue(connected.indexOf("spinner.dismiss()")
                < connected.indexOf("completeAutoWarmUpPreparing("));
        assertTrue(connected.indexOf(
                "autoWarmUpTransportStopRequested.compareAndSet(false, true)")
                < connected.indexOf("stopConnection(() -> finish())"));
    }

    @Test public void warmUpFramesAudioAndInputStayPrivateByExactOwnership()
            throws IOException {
        String game = source();
        String firstFrame = between(game, "private void onFirstVideoFrameRendered()",
                "private void onTransitionChanged(");
        String snapshot = between(game, "private void applyTransitionSnapshot(",
                "static boolean shouldShowTransitionOverlay(");
        String start = between(game, "private void startConnectionIfReady(",
                "private void logLaunchMilestone(");
        String connected = between(game, "public void connectionStarted()",
                "private ConsoleStreamTransitionCoordinator createTransitionCoordinator");

        assertTrue(firstFrame.contains("isOwnedHiddenAutoWarmUp()"));
        assertTrue(firstFrame.indexOf("isOwnedHiddenAutoWarmUp()")
                < firstFrame.indexOf("transitionController.videoFrameRendered"));
        assertTrue(snapshot.contains(
                "isOwnedHiddenAutoWarmUp() || snapshot.inputBlocked"));
        assertTrue(snapshot.contains("!isOwnedHiddenAutoWarmUp()"));
        assertTrue(start.indexOf("if (isOwnedHiddenAutoWarmUp()) runtime.setAudioVolume(attachment, 0f)")
                < start.indexOf("runtime.start();"));
        assertTrue(connected.contains(
                "if (!isDestroyed() && !isOwnedHiddenAutoWarmUp())"));
        assertTrue(connected.contains("setInputGrabState(true)"));
    }

    @Test public void preparingHomeDoesNotEnterHomeAndBackgroundUsesFullToken()
            throws IOException {
        String game = source();
        String preparingHome = between(game, "private void openPreparingConsoleHome()",
                "private void launchConsoleHome(");
        String background = between(game, "public boolean parkPreparingTransport(",
                "public void terminateRetainedSession(");

        assertFalse(preparingHome.contains("enterHome("));
        assertTrue(preparingHome.contains("runtime.setAudioVolume(attachment, 0f)"));
        assertTrue(preparingHome.contains("setInputSuppressed(true)"));
        assertTrue(background.contains("isExactAutoWarmUpPreparing(preparing)"));
        assertTrue(background.contains("parkWarmUpWhenConnected = true"));
        assertTrue(background.indexOf("markAutoWarmUpReconnectRequired(")
                < background.indexOf("stopConnection(() -> finish())"));
        assertTrue(background.indexOf(
                "autoWarmUpTransportStopRequested.compareAndSet(false, true)")
                < background.indexOf("stopConnection(() -> finish())"));
        assertFalse(background.contains("quitApp"));
    }

    @Test public void externalPreparingCancelRechecksTokenAndStopsOnlyItsTransport()
            throws IOException {
        String cancel = between(source(), "public boolean cancelPreparingTransport(",
                "public void terminateRetainedSession(");

        assertTrue(cancel.indexOf("isExactAutoWarmUpPreparing(preparing)")
                < cancel.indexOf("autoWarmUpTransportStopRequested.compareAndSet(false, true)"));
        assertTrue(cancel.indexOf("autoWarmUpTransportStopRequested.compareAndSet(false, true)")
                < cancel.indexOf("boolean converted = autoWarmUpConvertedToGame"));
        assertTrue(cancel.indexOf("markAutoWarmUpReconnectRequired(")
                < cancel.indexOf("transitionCoordinator.cancel()"));
        assertTrue(cancel.indexOf("if (converted && transitionCoordinator != null)")
                < cancel.indexOf("transitionCoordinator.cancel()"));
        assertTrue(cancel.indexOf("transitionCoordinator.cancel()")
                < cancel.indexOf("stopConnection(() -> finish())"));
        assertEquals(1, occurrences(cancel, "transitionCoordinator.cancel()"));
        assertEquals(1, occurrences(cancel, "stopConnection(() -> finish())"));
        assertTrue(cancel.contains("saveReconnect(true)"));
        assertFalse(cancel.contains("quitApp"));
    }

    @Test public void preFrameCancelClearsWithoutSavingOrStoppingTransport()
            throws IOException {
        String game = source();
        String cancel = between(game, "private boolean cancelUnstartedAutoWarmUp(",
                "private void completeAutoWarmUpPreparing(");
        String external = between(game, "public boolean parkPreparingTransport(",
                "public void terminateRetainedSession(");
        String submitted = between(game, "public boolean preparingHomeFrameSubmitted(",
                "public void terminateRetainedSession(");
        String destroy = between(game, "protected void onDestroy()",
                "protected void onPause()" );

        assertTrue(cancel.contains("new SessionEvent.PreparingCancelled("));
        assertFalse(cancel.contains("saveReconnect(true)"));
        assertFalse(cancel.contains("stopConnection"));
        assertFalse(cancel.contains("stopProviderGame"));
        assertTrue(external.contains("if (!autoWarmUpHomeFrameAccepted.get())"));
        assertTrue(external.contains("cancelUnstartedAutoWarmUp(true)"));
        assertTrue(submitted.contains("synchronized (autoWarmUpGateLock)"));
        assertTrue(submitted.indexOf("autoWarmUpTransportStopRequested.get()")
                < submitted.indexOf("compareAndSet(false, true)"));
        assertTrue(submitted.indexOf("compareAndSet(false, true)")
                < submitted.indexOf("transitionCoordinator.start()"));
        // Destroy only detaches; the runtime saves reconnect state and stops an unprotected
        // preparing transport (behavior covered by StreamRuntimeTest).
        assertTrue(destroy.contains("runtime.presentationLost(attachment, isFinishing())"));
        assertFalse(destroy.contains("stopConnection"));
        assertFalse(destroy.contains("BackgroundStreamService."));
    }

    @Test public void repeatedFailureCallbacksShareOneLocalStopClaim()
            throws IOException {
        String failure = between(source(),
                "private boolean handleAutoWarmUpConnectionFailure()",
                "private void beginAutoWarmUpPreparing(");
        String completed = between(failure,
                "if (autoWarmUpRetained && isOwnedHiddenAutoWarmUp())",
                "sessionModel.snapshot()");

        assertTrue(failure.indexOf("if (autoWarmUpTransportStopRequested.get()) return true")
                < failure.indexOf("sessionModel.snapshot()"));
        assertTrue(completed.contains(
                "return !autoWarmUpTransportStopRequested.compareAndSet(false, true)"));
        assertTrue(failure.indexOf(
                "autoWarmUpTransportStopRequested.compareAndSet(false, true)")
                < failure.indexOf("markAutoWarmUpReconnectRequired("));
        assertEquals(1, occurrences(failure, "stopConnection(() -> finish())"));
    }

    @Test public void neutralWarmUpDoesNotEnterTvGameHistory() throws IOException {
        String connected = between(source(), "public void connectionStarted()",
                "private ConsoleStreamTransitionCoordinator createTransitionCoordinator()");

        assertTrue(connected.contains("if (appName != null && !warmUpConnection)"));
        assertTrue(connected.indexOf("if (appName != null && !warmUpConnection)")
                < connected.indexOf("shortcutHelper.reportGameLaunched(computer, app)"));
    }

    @Test public void preparingGameSwitchUsesOpaqueGateAndExistingProviderPaths()
            throws IOException {
        String game = source();
        String begin = between(game, "private void beginRetainedGameSwitch(",
                "private void performRetainedProviderSwitch(");
        String newGame = between(game, "private void beginNewRetainedGameAttempt(",
                "private void adoptPreparingObservedGame(");
        String sameGame = between(game, "private void adoptPreparingObservedGame(",
                "private void applyRetainedTransition(");
        String setup = between(game, "private void applyRetainedTransition(",
                "private void advancePreparingSwitch(");

        assertTrue(begin.indexOf("consoleLoadingView.doAfterNextFrame")
                < begin.indexOf("beginRetainedProviderSwitch(operation)"));
        assertTrue(begin.contains("operation.oldSpec.type == LaunchTransitionType.GENERIC"));
        assertTrue(begin.indexOf("operation.oldCoordinator.close()")
                < begin.indexOf("beginNewRetainedGameAttempt(operation)"));
        assertTrue(begin.contains("!operation.request.preparing"));
        assertTrue(begin.contains("operation.request.oldGameId.isEmpty()"));
        assertTrue(begin.indexOf("operation.oldCoordinator.close()",
                begin.indexOf("!operation.request.preparing"))
                < begin.indexOf("performRetainedProviderSwitch(operation)",
                begin.indexOf("!operation.request.preparing")));
        assertTrue(begin.contains("operation.oldCoordinator.detachForSwitch()"));
        assertTrue(sameGame.contains("transferProviderOwnershipTo(replacement)"));
        assertFalse(sameGame.contains("startGame("));
        assertFalse(sameGame.contains("stopGame("));
        assertTrue(setup.indexOf("transitionController.begin(next)")
                < setup.indexOf("autoWarmUpConvertedToGame = true"));
        assertFalse(newGame.contains("startConnectionIfReady"));
    }

    @Test public void completedWarmUpConversionClearsDurableIdentityAndPreparingHomeDoesNotFade()
            throws IOException {
        String game = source();
        String clear = between(game, "private void clearAutoWarmUpIdentity()",
                "private boolean handleAutoWarmUpConnectionFailure()");
        String apply = between(game, "private void applyRetainedTransition(",
                "private void advancePreparingSwitch(");
        String launchHome = between(game, "private void launchConsoleHome(",
                "private void showOverlayMenuWithBattery()");

        assertTrue(clear.contains("autoWarmUpAttempt = 0L"));
        assertTrue(clear.contains("autoWarmUpRetained = false"));
        assertTrue(clear.contains("removeExtra(EXTRA_AUTO_WARM_UP_ATTEMPT)"));
        assertTrue(clear.contains("removeExtra(ConsoleActivity.EXTRA_WARM_UP_ATTEMPT)"));
        assertTrue(apply.contains("else if (hasAutoWarmUpTransportIdentity())"
                + " clearAutoWarmUpIdentity()"));
        assertTrue(launchHome.contains("preparing ? 0 : android.R.anim.fade_in"));
    }

    @Test public void convertedFailureCleansProviderBeforeItsOwnTransportOnce()
            throws IOException {
        String game = source();
        String stageFailed = between(game, "public void stageFailed(",
                "public void connectionTerminated(");
        String terminated = between(game, "public void connectionTerminated(",
                "public void connectionStarted()");
        String failure = between(game,
                "private boolean handleAutoWarmUpConnectionFailure()",
                "private void beginAutoWarmUpPreparing(");

        assertTrue(stageFailed.indexOf("cleanupUnrevealedProviderLaunch(")
                < stageFailed.indexOf("handleAutoWarmUpConnectionFailure()"));
        assertTrue(terminated.indexOf("cleanupUnrevealedProviderLaunch(")
                < terminated.indexOf("handleAutoWarmUpConnectionFailure()"));
        assertEquals(1, occurrences(failure, "stopConnection(() -> finish())"));
    }

    @Test public void convertedGameKeepsCompletionTokenButDropsPrivacyIdentity()
            throws IOException {
        String game = source();
        String newGame = between(game, "private void beginNewRetainedGameAttempt(",
                "private void adoptPreparingObservedGame(");
        String setup = between(game, "private void applyRetainedTransition(",
                "private void advancePreparingSwitch(");
        String completion = between(game, "private void completeAutoWarmUpPreparing(",
                "private void markAutoWarmUpReconnectRequired(String reason,");

        assertTrue(newGame.indexOf("advancePreparingSwitch(")
                < newGame.indexOf("applyRetainedTransition("));
        assertTrue(setup.indexOf("transitionController.begin(next)")
                < setup.indexOf("autoWarmUpConvertedToGame = true"));
        assertTrue(completion.contains("new SessionEvent.PreparingCompleted(runtime, preparing)"));
        assertTrue(completion.contains("clearAutoWarmUpIdentity()"));
    }

    @Test public void cancelIsCheckedBeforeTheProviderCoordinatorStarts()
            throws IOException {
        String newGame = between(source(), "private void beginNewRetainedGameAttempt(",
                "private void adoptPreparingObservedGame(");

        assertTrue(newGame.indexOf("operation.request.cancelled.getAsBoolean()")
                < newGame.indexOf("transitionCoordinator.start()"));
    }

    @Test public void preparingSwitchAlwaysReleasesItsCapturedLock()
            throws IOException {
        String game = source();
        String completed = between(game, "private void completeRetainedSwitch(",
                "private static final class RetainedSwitch");

        assertEquals(2, occurrences(completed, "finishRetainedSwitchLock(operation)"));
        // The model releases a preparing lock by its transition and attempt.
        assertTrue(completed.contains("new SessionEvent.SwitchFinished(runtime, operation.request)"));
    }

    @Test public void childMonitoringResumesOnlyAfterRetainedSwitchMarkerIsCleared()
            throws IOException {
        String game = source();
        String finish = between(game, "private void finishRetainedSwitch(",
                "private static final class RetainedSwitch");
        int marker = finish.indexOf("retainedSwitch = null");
        int monitor = finish.indexOf("startChildMonitoring()");
        assertTrue(marker >= 0);
        assertTrue(monitor > marker);

        String recovery = between(game, "private void recoverRetainedSwitch(",
                "private void afterRetainedGameStopped(");
        assertTrue(recovery.indexOf("completeRetainedSwitch(")
                < recovery.indexOf("startChildMonitoring()"));
    }

    @Test public void rejectedRetainedStartNeverClaimsRunningGame() throws IOException {
        String game = source();
        String failed = between(game, "@Override public void onProviderGameStartFailed(",
                "@Override public void onProviderGameCleanupComplete(");
        String cleanup = between(game, "@Override public void onProviderGameCleanupComplete(",
                "@Override public void onInstallationVerified()");

        String interaction = failed.substring(failed.indexOf("INTERACTION_REQUIRED"));
        assertTrue(interaction.indexOf("providerStartRejectedTransitionId = transitionId")
                < interaction.indexOf("RetainedSwitch operation = retainedSwitch"));
        assertTrue(interaction.contains("persistRejectedProviderTransitionAsNeutral()"));
        assertTrue(interaction.contains("operation.providerStartRejected = true"));
        assertTrue(interaction.contains("signalRetainedSwitchReuse(operation)"));
        assertFalse(interaction.contains("updateRetainedGame("));
        assertFalse(interaction.contains("saveReconnect(false)"));
        assertTrue(cleanup.contains("if (operation.providerStartRejected)"));
        assertTrue(between(game, "private void settleStartFailedSwitch(",
                "private void afterRetainedGameStopped(").contains(
                "operation.providerStartRejected = false"));
    }

    @Test public void rejectedRevealHomeAndRecoveryRemainNeutral() throws IOException {
        String game = source();
        String reveal = between(game, "private void revealTransitionNow()",
                "private void persistRevealedTransitionForRecovery()");
        String neutral = between(game,
                "private void persistRejectedProviderTransitionAsNeutral()",
                "private String currentSessionGameId()");
        String home = between(game, "private void openConsoleHome()",
                "private void openPreparingConsoleHome()");
        String retry = between(game, "private void restartStreamForRetry()",
                "private boolean retryStillCurrent(");

        assertTrue(reveal.contains("!rejectedProviderStart && transitionCoordinator != null"));
        assertTrue(reveal.contains("if (rejectedProviderStart)"
                + " persistRejectedProviderTransitionAsNeutral()"));
        assertTrue(reveal.contains("else persistRevealedTransitionForRecovery()"));
        assertTrue(neutral.contains("LaunchTransitionType.GAME_CONNECTION.name()"));
        assertTrue(neutral.contains("EXTRA_TRANSITION_PLAYNITE_GAME_ID, \"\""));
        assertTrue(neutral.contains("saveReconnect(false)"));
        assertTrue(home.contains("String sessionGameId = currentSessionGameId()"));
        assertEquals(3, occurrences(home, "sessionGameId"));
        assertTrue(retry.indexOf("providerStartRejectedTransitionId = \"\"")
                < retry.indexOf("retry.putExtra(EXTRA_TRANSITION_ID"));
        assertTrue(retry.contains("retry.putExtra(EXTRA_TRANSITION_TYPE"));
        assertTrue(retry.contains("retry.putExtra(EXTRA_TRANSITION_PLAYNITE_GAME_ID"));
    }

    @Test public void retainedGameRetryKeepsTransportAndColdPadsArePrimed()
            throws IOException {
        String game = source();
        // The table decides (SessionTransitionTableTest); on a live transport Game only
        // starts the game again or observes again, and neither touches the transport.
        String retry = between(game, "private void retryGameStart()",
                "public void displayMessage");
        assertTrue(retry.contains("LaunchTransitionType.GAME,"));
        assertTrue(retry.contains("LaunchTransitionType.GAME_CONNECTION"));
        assertFalse(retry.contains("stopConnection("));

        Path controllerPath = Paths.get(
                "src/main/java/com/limelight/binding/input/ControllerHandler.java");
        if (!Files.exists(controllerPath)) {
            controllerPath = Paths.get(
                    "app/src/main/java/com/limelight/binding/input/ControllerHandler.java");
        }
        String controller = new String(Files.readAllBytes(controllerPath),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String announce = between(controller, "public void announceConnectedControllers()",
                "public void destroy()");
        assertTrue(announce.contains("primeControllerIfNeeded"));
        assertTrue(announce.contains("sendNeutralControllerInput"));
        assertFalse(announce.contains("inputSuppressed = false"));
    }

    private static String source() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/Game.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String sourceFile(String repositoryPath) throws IOException {
        Path source = Paths.get(repositoryPath.substring("app/".length()));
        if (!Files.exists(source)) source = Paths.get(repositoryPath);
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end,
                source.indexOf(start)));
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        for (int at = source.indexOf(value); at >= 0;
             at = source.indexOf(value, at + value.length())) count++;
        return count;
    }
}
