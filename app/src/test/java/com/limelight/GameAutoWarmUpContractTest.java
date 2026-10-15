package com.limelight;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.limelight.SourceText.assertOrdered;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameAutoWarmUpContractTest {
    @Test public void warmUpOpensHomeBeforeAnyConnectionOrProviderStart()
            throws IOException {
        String game = source();
        String create = between(game, "protected void onCreate(",
                "private MediaCodecDecoderRenderer createDecoderRenderer");

        // The Home-frame handshake itself (one claim per preparing operation, stale ones
        // rejected) is a model scenario: SessionModelTest, S09 C4.
        // A fresh warm-up claims PREPARING before opening Home; only a reattached presentation
        // whose Home frame the coordinator already accepted skips that (resumeAcceptedWarmUp).
        assertTrue(create.indexOf("if (reattachPending && resumeAcceptedWarmUp())")
                < create.indexOf("beginAutoWarmUpPreparing("));
        assertTrue(create.indexOf("beginAutoWarmUpPreparing(")
                < create.lastIndexOf("openPreparingConsoleHome()"));
        String warmUp = create.substring(create.lastIndexOf(
                "if (hasAutoWarmUpTransportIdentity())"));
        assertFalse(warmUp.substring(0, warmUp.indexOf("} else {")).contains(
                "transitionCoordinator.start()"));
        assertFalse(warmUp.substring(0, warmUp.indexOf("} else {")).contains(
                "startConnectionIfReady("));
    }

    /** TV 2026-10-14: resuming a retained warm-up kept it hidden, a black cover with no action. */
    @Test public void returningFromHomeTakesOverTheRetainedWarmUp() throws IOException {
        String game = source();
        String resume = between(game, "protected void onResume()",
                "private void returnFromStreamHome()");
        String takeOver = between(game, "private void returnFromStreamHome()",
                "protected void onStop()");
        String snapshot = between(game, "private void applyTransitionSnapshot(",
                "private void scheduleAutomaticReveal()");
        String state = between(game, "private SessionOrchestrator.State transitionState()",
                "private static final java.util.EnumSet<SessionOrchestrator.ActionKind>");

        // The parked decoder is restored first: the taken-over cover arms a frame on it.
        assertOrdered(resume, "restoreParkedStream(",
                "if (returningFromStreamHome) {", "returnFromStreamHome();");
        // SessionOrchestrator decides; this only executes its answer.
        assertOrdered(takeOver, "SessionOrchestrator.UserIntent.RETURN_FROM_HOME",
                "ActionKind.TAKE_OVER_WARM_UP", "clearAutoWarmUpIdentity()",
                "showFullTransitionAppearance()", "applyStreamAudio(currentTransitionSnapshot());",
                "enableSensors()", "transitionController.takenOver(transitionSpec.id);",
                "applyTransitionSnapshot(transitionController.snapshot())");
        assertFalse(takeOver.contains("isExactAutoWarmUpPreparing("));
        assertOrdered(state, "state.resumed = presentationResumed",
                "state.hiddenWarmUp = isOwnedHiddenAutoWarmUp()",
                "state.warmUpPreparing = isExactAutoWarmUpPreparing(",
                "state.switchRequested = pendingRetainedSwitch.busy()");
        // The controller says when the cover needs a fresh frame; FrameArmingPolicy arms it.
        assertTrue(snapshot.contains("armNextVideoFrame(snapshot, "));
        assertFalse(snapshot.contains("waitingForPostTargetFrame"));
    }

    /**
     * TV 2026-10-14 16:32 (after host sleep): Play of the game a warm-up already resumed took
     * that warm-up over and its cover fell from the game's artwork to "MoonWaker Stream". The
     * warm-up carries the host game's title and artwork; the takeover shows that artwork.
     */
    /** The lookup itself: PlayniteSessionPresentationTest.takenOverWarmUpKeepsItsGamesTitleAndArtwork. */
    @Test public void takenOverWarmUpShowsTheCoverItCarries() throws IOException {
        String takeOver = between(source(), "private void returnFromStreamHome()",
                "protected void onStop()");
        assertOrdered(takeOver, "clearAutoWarmUpIdentity()",
                "consoleLoadingView.setSplashArtwork(",
                "getIntent().getStringExtra(EXTRA_CONSOLE_LOADING_ARTWORK)",
                "showFullTransitionAppearance()");
    }

    /**
     * The user left the Home while the warm-up was still PREPARING: the refused takeover is
     * asked again on completion, and when a requested switch is released without applying.
     */
    @Test public void settledWarmUpOrReleasedSwitchAsksForTheTakeoverAgain() throws IOException {
        String game = source();
        String complete = between(game, "private void completeAutoWarmUpPreparing(",
                "private void markAutoWarmUpReconnectRequired(");
        String submit = between(game, "private void submitPendingRetainedSwitch()",
                "private void logLaunchMilestone(");

        assertOrdered(complete, "autoWarmUpRetained = true;", "done.accept(completed);",
                "if (completed) returnFromStreamHome();");
        assertOrdered(submit, "pendingRetainedSwitch.completed();", "returnFromStreamHome();",
                "pendingRetainedSwitch.completed();", "returnFromStreamHome();");
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
        assertOrdered(create, "consoleLoadingView.showNeutralWarmUpAppearance()",
                "awaitCover(startId, outcome -> {");
        assertTrue(apply.indexOf("consoleLoadingView.showFullTransitionAppearance()")
                < apply.indexOf("transitionController.beginOnKeptTransport(next)"));
    }

    @Test public void windowCoversWhatIsUnderItOnlyOnceItsOwnFrameIsReady() throws IOException {
        String game = source();
        String create = between(game, "protected void onCreate(",
                "private MediaCodecDecoderRenderer createDecoderRenderer");
        String cover = between(game, "private void coverScreen() {",
                "private void openPreparingConsoleHome()");
        String theme = sourceFile("src/main/res/values-v30/styles.xml");

        // The window is see-through from its start; only Game makes it opaque.
        assertTrue(theme.contains("<style name=\"StreamBaseTheme\" parent=\"AppBaseTheme\">"));
        assertTrue(theme.contains("<item name=\"android:windowIsTranslucent\">true</item>"));
        assertTrue(theme.contains(
                "<item name=\"android:windowBackground\">@android:color/transparent</item>"));
        assertTrue(cover.contains("if (screenCovered || isFinishing() || isDestroyed()) return;"));
        assertTrue(cover.contains("streamView.setVisibility(View.VISIBLE);"));
        assertTrue(cover.contains("consoleLoadingView.setSeeThrough(false);"));
        assertTrue(cover.contains(
                "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setTranslucent(false);"));
        assertEquals(1, game.split("setTranslucent\\(", -1).length - 1);

        // A hidden warm-up draws nothing and has no video surface; anything else covers the
        // screen after its first frame.
        String start = between(create, "streamView.setInputCallbacks(this);",
                "View backgroundTouchView");
        assertTrue(start.contains(
                "if (hasAutoWarmUpTransportIdentity() && !autoWarmUpConvertedToGame) {"));
        assertTrue(start.indexOf("streamView.setVisibility(View.INVISIBLE);")
                < start.indexOf("} else {"));
        assertTrue(start.indexOf("consoleLoadingView.setSeeThrough(true);")
                < start.indexOf("} else {"));
        // Its first frame settles through the barrier; every outcome covers the window.
        assertOrdered(start, "} else {",
                "new ViewFrameSource(getWindow().getDecorView()).await(\"window\",",
                "outcome -> coverScreen());");
        assertFalse(start.contains("postOnAnimation"));

        // The hidden warm-up covers the screen with the Home's accepted frame, before the
        // transport is asked to start, and whenever it turns into a game or loses its Home.
        String accepted = between(game, "public boolean preparingHomeFrameSubmitted(",
                "public void terminateRetainedSession(");
        assertTrue(accepted.indexOf("compareAndSet(false, true)")
                < accepted.indexOf("coverScreen();"));
        assertTrue(accepted.indexOf("coverScreen();")
                < accepted.indexOf("startConnectionIfReady("));
        String resumed = between(game, "private boolean resumeAcceptedWarmUp()",
                "private void resumeAttachedTransport()");
        assertTrue(resumed.indexOf("autoWarmUpHomeFrameAccepted.set(true);")
                < resumed.indexOf("coverScreen();"));
        String apply = between(game, "private void applyRetainedTransition(",
                "private void advancePreparingSwitch(");
        assertTrue(apply.indexOf("coverScreen();")
                < apply.indexOf("consoleLoadingView.showFullTransitionAppearance()"));
        String resume = between(game, "protected void onResume() {", "protected void onStop() {");
        assertTrue(resume.indexOf("if (isFinishing() || isDestroyed()) return;")
                < resume.indexOf("coverScreen();"));
    }

    @Test public void warmUpMapsOnlyToGenericOrObservationConnection() throws IOException {
        String create = between(source(), "protected void onCreate(",
                "private MediaCodecDecoderRenderer createDecoderRenderer");
        String mapping = between(create,
                "if (hasAutoWarmUpTransportIdentity() && !autoWarmUpConvertedToGame) {",
                "LaunchTransitionType recoveredType");
        // A warm-up already converted to a selected game keeps its GAME semantics across
        // Activity recreation (the conversion is restored from the intent first).
        assertOrdered(create, "getBooleanExtra(EXTRA_AUTO_WARM_UP_CONVERTED, false)",
                "if (hasAutoWarmUpTransportIdentity() && !autoWarmUpConvertedToGame) {");

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

        String runtime = sourceFile("src/main/java/com/limelight/stream/StreamRuntime.java");
        String runtimeStarted = between(runtime, "@Override public void connectionStarted()",
                "@Override public void connectionTerminated(");
        // The one native start is owned by the runtime; Game only requests it once.
        assertEquals(1, occurrences(game, "runtime.start();"));
        assertEquals(1, occurrences(runtime, "transport.start(this)"));
        assertOrdered(start, "attemptedConnection = true",
                "runtime.start();");
        assertFalse(stages.contains("completeAutoWarmUpPreparing"));
        // Connected state is the runtime's fact, set only from connectionStarted.
        assertTrue(runtimeStarted.contains("state = State.CONNECTED"));
        assertOrdered(runtimeStarted, "state = State.CONNECTED",
                "p.connectionStarted()");
        assertTrue(connected.contains("completeAutoWarmUpPreparing("));
        assertOrdered(connected, "spinner.dismiss()",
                "completeAutoWarmUpPreparing(");
        assertOrdered(connected, "autoWarmUpTransportStopRequested.compareAndSet(false, true)",
                "stopConnection(() -> finish())");
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

        // A hidden warm-up's cover follows the controller like any other; never uncovered.
        assertFalse(firstFrame.contains("showOpaque"));
        assertFalse(firstFrame.contains("videoFrameRendered("));
        assertOrdered(snapshot, "if (lastTransitionOverlayVisible && !snapshot.overlayVisible",
                "&& !isOwnedHiddenAutoWarmUp()) {", "consoleLoadingView.revealStream(");
        // The one routing decision (LaunchTransitionControllerTest): a hidden warm-up's input
        // never reaches the host.
        assertTrue(snapshot.contains("controllerHandler.setInputSuppressed(!LaunchTransitionController"
                + ".inputReachesHost(\n                    snapshot, isOwnedHiddenAutoWarmUp()));"));
        assertTrue(snapshot.contains("!isOwnedHiddenAutoWarmUp()"));
        assertTrue(start.indexOf("applyStreamAudio(currentTransitionSnapshot());")
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
        assertTrue(game.contains("streamHomeVisible = true;\n        applyStreamAudio(currentTransitionSnapshot());"));
        assertTrue(preparingHome.contains("setInputSuppressed(true)"));
        assertTrue(background.contains("isExactAutoWarmUpPreparing(preparing)"));
        assertTrue(background.contains("parkWarmUpWhenConnected = true"));
        assertOrdered(background, "markAutoWarmUpReconnectRequired(",
                "stopConnection(() -> finish())");
        assertOrdered(background, "autoWarmUpTransportStopRequested.compareAndSet(false, true)",
                "stopConnection(() -> finish())");
        assertFalse(background.contains("quitApp"));
    }

    @Test public void externalPreparingCancelRechecksTokenAndStopsOnlyItsTransport()
            throws IOException {
        String cancel = between(source(), "public boolean cancelPreparingTransport(",
                "public void terminateRetainedSession(");

        assertOrdered(cancel, "isExactAutoWarmUpPreparing(preparing)",
                "autoWarmUpTransportStopRequested.compareAndSet(false, true)");
        assertOrdered(cancel, "autoWarmUpTransportStopRequested.compareAndSet(false, true)",
                "boolean converted = autoWarmUpConvertedToGame");
        assertOrdered(cancel, "markAutoWarmUpReconnectRequired(",
                "transitionCoordinator.cancel()");
        assertOrdered(cancel, "if (converted && transitionCoordinator != null)",
                "transitionCoordinator.cancel()");
        assertOrdered(cancel, "transitionCoordinator.cancel()",
                "stopConnection(() -> finish())");
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

        assertOrdered(failure, "if (autoWarmUpTransportStopRequested.get()) return true",
                "sessionModel.snapshot()");
        assertTrue(completed.contains(
                "return !autoWarmUpTransportStopRequested.compareAndSet(false, true)"));
        assertOrdered(failure, "autoWarmUpTransportStopRequested.compareAndSet(false, true)",
                "markAutoWarmUpReconnectRequired(");
        assertEquals(1, occurrences(failure, "stopConnection(() -> finish())"));
    }

    @Test public void neutralWarmUpDoesNotEnterTvGameHistory() throws IOException {
        String connected = between(source(), "public void connectionStarted()",
                "private ConsoleStreamTransitionCoordinator createTransitionCoordinator()");

        assertTrue(connected.contains("if (appName != null && !warmUpConnection)"));
        assertOrdered(connected, "if (appName != null && !warmUpConnection)",
                "shortcutHelper.reportGameLaunched(computer, app)");
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

        assertOrdered(begin, "awaitCover(operation.oldSpec.id, cancelled, outcome -> {",
                "beginRetainedProviderSwitch(operation)");
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
        assertTrue(setup.indexOf("transitionController.beginOnKeptTransport(next)")
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

        assertTrue(clear.contains("autoWarmUp = false"));
        assertTrue(clear.contains("autoWarmUpRetained = false"));
        assertTrue(clear.contains("removeExtra(EXTRA_AUTO_WARM_UP)"));
        assertTrue(clear.contains("removeExtra(ConsoleActivity.EXTRA_PREPARING_OPERATION)"));
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

        assertOrdered(stageFailed, "cleanupUnrevealedProviderLaunch(",
                "handleAutoWarmUpConnectionFailure()");
        assertOrdered(terminated, "cleanupUnrevealedProviderLaunch(",
                "handleAutoWarmUpConnectionFailure()");
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

        assertOrdered(newGame, "advancePreparingSwitch(",
                "applyRetainedTransition(");
        assertOrdered(setup, "transitionController.beginOnKeptTransport(next)",
                "autoWarmUpConvertedToGame = true");
        assertTrue(completion.contains("new SessionEvent.PreparingCompleted(runtime, preparing)"));
        assertTrue(completion.contains("clearAutoWarmUpIdentity()"));
    }

    @Test public void cancelIsCheckedBeforeTheProviderCoordinatorStarts()
            throws IOException {
        String newGame = between(source(), "private void beginNewRetainedGameAttempt(",
                "private void adoptPreparingObservedGame(");

        assertOrdered(newGame, "operation.cancelled()",
                "transitionCoordinator.start()");
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
        assertOrdered(recovery, "completeRetainedSwitch(",
                "startChildMonitoring()");
    }

    @Test public void rejectedRetainedStartNeverClaimsRunningGame() throws IOException {
        String game = source();
        String failed = between(game, "@Override public void onProviderGameStartFailed(",
                "@Override public void onProviderGameCleanupComplete(");
        String cleanup = between(game, "@Override public void onProviderGameCleanupComplete(",
                "@Override public void onInstallationVerified()");

        String interaction = failed.substring(failed.indexOf("INTERACTION_REQUIRED"));
        assertOrdered(interaction, "providerStartRejectedTransitionId = transitionId",
                "RetainedSwitch operation = retainedSwitch");
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
        String home = between(game, "private boolean openConsoleHome()",
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
        String controller = SourceText.read(controllerPath);
        String announce = between(controller, "public void announceConnectedControllers()",
                "public void destroy()");
        assertTrue(announce.contains("primeControllerIfNeeded"));
        assertTrue(announce.contains("sendNeutralControllerInput"));
        assertFalse(announce.contains("inputSuppressed = false"));
    }

    private static String source() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        return SourceText.read(source);
    }

    private static String sourceFile(String modulePath) throws IOException {
        return SourceText.module(modulePath);
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
