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

        assertTrue(create.indexOf("beginAutoWarmUpPreparing()")
                < create.indexOf("openPreparingConsoleHome()"));
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
                "private boolean advancePreparingSwitch(");

        assertTrue(create.contains("if (hasAutoWarmUpTransportIdentity())"));
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
        String mapping = between(create, "if (hasAutoWarmUpTransportIdentity()) {",
                "LaunchTransitionType recoveredType");

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

        assertEquals(1, occurrences(game,
                "conn.start(streamAudioRenderer, switchableVideoRenderer, Game.this)"));
        assertTrue(start.indexOf("attemptedConnection = true")
                < start.indexOf("conn.start("));
        assertFalse(stages.contains("completeAutoWarmUpPreparing"));
        assertFalse(stages.contains("connected = true"));
        assertTrue(connected.contains("connected = true"));
        assertTrue(connected.contains("completeAutoWarmUpPreparing()"));
        assertTrue(connected.indexOf("spinner.dismiss()")
                < connected.indexOf("connected = true"));
        assertTrue(connected.indexOf("completeAutoWarmUpPreparing()")
                < connected.indexOf("connected = true"));
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
        assertTrue(start.indexOf("new AndroidAudioRenderer")
                < start.indexOf("setVolume(0f)"));
        assertTrue(connected.contains(
                "if (!isOwnedHiddenAutoWarmUp()) setInputGrabState(true)"));
    }

    @Test public void preparingHomeDoesNotEnterHomeAndBackgroundUsesFullToken()
            throws IOException {
        String game = source();
        String preparingHome = between(game, "private void openPreparingConsoleHome()",
                "private void launchConsoleHome(");
        String background = between(game, "public boolean parkPreparingTransport(",
                "public void terminateRetainedSession(");

        assertFalse(preparingHome.contains("enterHome("));
        assertTrue(preparingHome.contains("setVolume(0f)"));
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
        assertTrue(cancel.contains("SessionResumeManager.save("));
        assertFalse(cancel.contains("quitApp"));
    }

    @Test public void preFrameCancelClearsWithoutSavingOrStoppingTransport()
            throws IOException {
        String game = source();
        String cancel = between(game, "private boolean cancelUnstartedAutoWarmUp(",
                "private boolean completeAutoWarmUpPreparing()");
        String external = between(game, "public boolean parkPreparingTransport(",
                "public void terminateRetainedSession(");
        String submitted = between(game, "public boolean preparingHomeFrameSubmitted(",
                "public void terminateRetainedSession(");
        String destroy = between(game, "protected void onDestroy()",
                "protected void onPause()" );

        assertTrue(cancel.contains("RetainedStreamSessionCoordinator.cancelPreparing("));
        assertFalse(cancel.contains("SessionResumeManager.save"));
        assertFalse(cancel.contains("stopConnection"));
        assertFalse(cancel.contains("stopProviderGame"));
        assertTrue(external.contains("if (!autoWarmUpHomeFrameAccepted.get())"));
        assertTrue(external.contains("cancelUnstartedAutoWarmUp(true)"));
        assertTrue(submitted.contains("synchronized (autoWarmUpGateLock)"));
        assertTrue(submitted.indexOf("autoWarmUpTransportStopRequested.get()")
                < submitted.indexOf("compareAndSet(false, true)"));
        assertTrue(submitted.indexOf("compareAndSet(false, true)")
                < submitted.indexOf("transitionCoordinator.start()"));
        assertTrue(destroy.contains("activity_destroyed_before_transport"));
        assertTrue(destroy.contains("SessionResumeManager.save("));
        assertTrue(destroy.contains("BackgroundStreamService.transportLost("));
    }

    @Test public void repeatedFailureCallbacksShareOneLocalStopClaim()
            throws IOException {
        String failure = between(source(),
                "private boolean handleAutoWarmUpConnectionFailure()",
                "private boolean beginAutoWarmUpPreparing()");
        String completed = between(failure,
                "if (autoWarmUpRetained && isOwnedHiddenAutoWarmUp())",
                "RetainedStreamSessionCoordinator.snapshot()");

        assertTrue(failure.indexOf("if (autoWarmUpTransportStopRequested.get()) return true")
                < failure.indexOf("RetainedStreamSessionCoordinator.snapshot()"));
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
                "private boolean advancePreparingSwitch(");

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
                "private boolean advancePreparingSwitch(");
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
                "private boolean beginAutoWarmUpPreparing()");

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
                "private boolean advancePreparingSwitch(");
        String completion = between(game, "private boolean completeAutoWarmUpPreparing()",
                "private boolean markAutoWarmUpReconnectRequired(String reason)");

        assertTrue(newGame.indexOf("advancePreparingSwitch(")
                < newGame.indexOf("applyRetainedTransition("));
        assertTrue(setup.indexOf("transitionController.begin(next)")
                < setup.indexOf("autoWarmUpConvertedToGame = true"));
        assertTrue(completion.contains("preparing.transitionId"));
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
        assertTrue(completed.contains("if (operation.request.preparing)"));
        assertTrue(completed.contains("operation.request.transitionId"));
        assertTrue(completed.contains("operation.request.attempt"));
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
        assertFalse(interaction.contains("SessionResumeManager.saveActive"));
        assertTrue(cleanup.contains("!operation.providerStartRejected"));
        assertTrue(cleanup.contains("operation.providerStartRejected = false"));
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
        String retry = between(game, "private void retryTransition()",
                "private void retryRetainedObservation()");

        assertTrue(reveal.contains("!rejectedProviderStart && transitionCoordinator != null"));
        assertTrue(reveal.contains("if (rejectedProviderStart)"
                + " persistRejectedProviderTransitionAsNeutral()"));
        assertTrue(reveal.contains("else persistRevealedTransitionForRecovery()"));
        assertTrue(neutral.contains("LaunchTransitionType.GAME_CONNECTION.name()"));
        assertTrue(neutral.contains("EXTRA_TRANSITION_PLAYNITE_GAME_ID, \"\""));
        assertTrue(neutral.contains("SessionResumeManager.saveActive"));
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
        String retry = between(game, "private void retryTransition()",
                "private void retryRetainedObservation()");
        assertTrue(retry.contains("transitionSpec.type == LaunchTransitionType.GAME"));
        assertTrue(retry.indexOf("retryRetainedObservation()")
                < retry.indexOf("stopConnection("));

        Path controllerPath = Paths.get(
                "src/main/java/com/limelight/binding/input/ControllerHandler.java");
        if (!Files.exists(controllerPath)) {
            controllerPath = Paths.get(
                    "app/src/main/java/com/limelight/binding/input/ControllerHandler.java");
        }
        String controller = new String(Files.readAllBytes(controllerPath),
                StandardCharsets.UTF_8);
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
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
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
