package com.limelight.console;

import com.limelight.SourceText;
import org.junit.Test;

import java.io.IOException;

import static com.limelight.SourceText.assertOrdered;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleActivityEnsureContractTest {
    /**
     * The Home renders the projection's unconfirmed end (SessionStateProjectionTest) and its
     * "Check again" is a forced read of the host, decided again from the model afterwards.
     */
    @Test public void unconfirmedEndShowsOnTheHomeWithCheckAgain() throws IOException {
        String source = consoleActivitySource();
        String status = source.substring(source.indexOf(
                        "private String hostStatus(ComputerDetails host, SessionSnapshot snapshot,"
                                + " boolean timed)"),
                source.indexOf("private String libraryGameName("));
        assertOrdered(status, "if (snapshot.terminationUnconfirmed) {",
                "R.string.console_status_termination_unconfirmed",
                "String closing = closingStatus(snapshot);");
        String offer = source.substring(source.indexOf("private void offerTerminationCheck("),
                source.indexOf("private void updateHostSelector()"));
        assertOrdered(offer, ".terminationUnconfirmed;", "if (terminationCheckAction == null)"
                + " showTerminationCheck(host);", "R.string.console_check_again",
                "refreshSessionProjection(host, accepted -> {",
                "selectedProfileId(current.uuid)).terminationUnconfirmed) {",
                "showTerminationCheck(current);", "hideSidePanel();");
        String flush = source.substring(source.indexOf("private void flushConsoleUpdates()"),
                source.indexOf("private void offerTerminationCheck("));
        assertTrue(flush.contains("offerTerminationCheck(selected);"));
    }

    /**
     * The queued Play is the process's (PlayQueue): leaving a screen (onPause, the stream Home
     * handed over to the console) never clears it; the resumed console renders and advances
     * it; only the user leaving MoonWaker, or an explicit cancel or host/profile change, ends it.
     */
    @Test public void queuedPlayOutlivesScreenHandoffsAndEndsWhenTheAppLeaves() throws IOException {
        String source = consoleActivitySource();
        String preparation = source.substring(source.indexOf(
                        "private void cancelCurrentPreparation(ChildLaunchBinding preservedBinding)"),
                source.indexOf("private void resumeSession(ComputerDetails host)"));
        assertFalse(preparation.contains("cancelQueuedPlay()"));
        assertTrue(source.contains("}, executor, mainHandler::post, PlayQueue.of(this));"));
        int resumeStart = source.indexOf("protected void onResume() {");
        String resume = source.substring(resumeStart,
                source.indexOf("hideSystemUi();", resumeStart));
        // The stale-cover cleanup runs first: it never removes the cover the queue just drew.
        assertOrdered(resume,
                "if (loadingLayer != null && loadingLayer.getVisibility() == View.VISIBLE) showHome();",
                "renderQueue();", "sessionOrchestrator.sessionChanged();");
        String drives = source.substring(source.indexOf("public boolean drivesQueue()"));
        assertOrdered(drives, "active && !isFinishing()", "initialHostSelectionResolved",
                "!hostSelectionVisible && profileGateHostUuid == null", "isStreamHome()",
                "launchedExtra(EXTRA_RETAINED_STREAM_SESSION_ID)");
        // TV 2026-10-15: a queued Play the paused screen started (undecided) goes back to the
        // queue before that screen's cancel; the entered host starts it, never the PIN gate.
        String pause = source.substring(source.indexOf("protected void onPause() {"),
                source.indexOf("protected void onStop()"));
        assertOrdered(pause, "sessionOrchestrator.screenLeft();",
                "cancelCurrentPreparation(childStreamHandoff);");
        String entered = source.substring(source.indexOf(
                "private void enterHostAfterProfileGate("),
                source.indexOf("private void closeProfileGate()"));
        assertOrdered(entered, "selectHost(host, focusApps);",
                "sessionOrchestrator.sessionChanged();");
        // Owner 2026-10-15: a queued Play waits on its own launch cover ("waiting for X to
        // close"), continues on that same cover, and Back on it cancels the queued Play.
        String queueChanged = source.substring(source.indexOf("@Override public void queueChanged()"),
                source.indexOf("@Override public void queueDropped("));
        assertTrue(queueChanged.contains("if (waiting != null) showQueuedCover(waiting);"));
        assertFalse(queueChanged.contains("stopAndHide"));
        String effectLoading = source.substring(source.indexOf("@Override public void showLoading("),
                source.indexOf("@Override public void refreshSession("));
        assertOrdered(effectLoading, "if (intent == queuedCoverIntent",
                "queuedCoverIntent = null;", "} else {", "ConsoleActivity.this.showLoading(");
        String queuedCover = source.substring(source.indexOf("private void showQueuedCover("),
                source.indexOf("private void endQueuedCover()"));
        assertOrdered(queuedCover, "R.string.console_queued_waiting_session",
                "R.string.console_queued_waiting_close, closingName");
        String cancel = source.substring(source.indexOf("    private void showLoading(String hostName, String appName, String loadingArtworkPath)"));
        assertOrdered(cancel, "@Override public void onCancel() {",
                "if (queuedCoverIntent != null) {", "sessionOrchestrator.cancelQueued();",
                "cancelCurrentPreparation();");
        // TV 2026-10-15: an earlier launch's cover never shows again when this window does.
        String stop = source.substring(source.indexOf("@Override protected void onStop() {"),
                source.indexOf("public void onTrimMemory("));
        assertOrdered(stop, "loadingLayer.getVisibility() == View.VISIBLE) showHome();",
                "super.onStop();");
        // TV 2026-10-15: a launch's cover names its game, never the Gateway's stream target.
        String launch = source.substring(source.indexOf("presentation.putString(Game.EXTRA_STREAM_TARGET_NAME"),
                source.indexOf("ServerHelper.doStart(this, app, host"));
        assertOrdered(launch, "PlayniteTargetResolver.gameCoverTitle(playIntent.appName, app.getAppName())",
                "presentation.putString(Game.EXTRA_APP_NAME, gameTitle);");
        String gate = source.substring(source.indexOf("private void showProfileGate("),
                source.indexOf("private void showPinEntry("));
        assertFalse("the gate of the same profile keeps the queued Play",
                gate.contains("cancelQueuedPlay()"));
        String application = SourceText.main("MoonWakerApplication.java");
        assertOrdered(application, "STARTED_ACTIVITIES.decrementAndGet() == 0",
                "!activity.isChangingConfigurations()", "playQueue.appLeft();");
    }

    @Test public void playniteProjectionAndArtworkAreFencedByExactHostProfileKey()
            throws IOException {
        String source = consoleActivitySource();
        String change = source.substring(source.indexOf("private boolean changeSelectedProfile("),
                source.indexOf("private void refreshHostProfiles("));
        String load = source.substring(source.indexOf("private void loadPlayniteForHost("),
                source.indexOf("private void settleInitialLocalPresentation("));
        String render = source.substring(source.indexOf("private void renderPlayniteLibrary("),
                source.indexOf("private void requestPendingInitialGameFocus("));
        String fetch = source.substring(source.indexOf("private ArtworkResult fetchPlayniteArtwork("),
                source.indexOf("private void applyPrefetchedPlayniteArtwork("));
        String poster = source.substring(source.indexOf("private void loadPlaynitePoster("),
                source.indexOf("private void schedulePlayniteArtworkPrefetch("));

        assertTrue(source.contains("volatile HostProfileKey currentPlayniteProfileKey"));
        assertTrue(change.contains("currentPlayniteProfileKey = null"));
        assertTrue(change.contains("allPlayniteItems = Collections.emptyList()"));
        assertTrue(load.contains("requestKey.equals(currentPlayniteProfileKey)"));
        assertTrue(load.contains("currentPlayniteProfileKey = requestKey"));
        assertTrue(render.contains("currentPlayniteProjectionMatches(requestedKey)"));
        assertTrue(render.indexOf("currentPlayniteProjectionMatches(requestedKey)")
                < render.indexOf("for (PlayniteLibraryGame game : currentPlayniteGames)"));
        assertTrue(poster.contains("currentPlayniteProjectionMatches(profileKey)"));
        assertTrue(poster.contains("profileKey.profileId"));
        assertTrue(fetch.indexOf("currentPlayniteProjectionMatches(key)")
                < fetch.indexOf("playniteArtworkCache.fetch("));
        assertTrue(fetch.indexOf("key.profileId.equals(connection.profileId())")
                < fetch.indexOf("playniteArtworkCache.fetch("));
        assertTrue(poster.contains("if (!currentPlayniteProjectionMatches(profileKey)\n"
                + "                    || !expectedTag.equals(poster.getTag(R.id.playnite_artwork_key)))"));
        assertTrue(source.contains("currentPlayniteProjectionMatches(key)) {\n"
                + "            PlayniteLibraryGame current"));
        assertTrue(source.contains("cachedPlayniteArtwork(profileKey, hostUuid, item, spec)"));
        assertTrue(source.contains("connection, profileKey, hostUuid, item, spec"));
    }

    @Test public void screenSaverUsesSharedBoundedArtworkSizing() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf("private ImageView screenSaverImage()"),
                source.indexOf("private void buildHostSelectionLayer"));

        assertTrue(method.contains("new ArtworkImageView(this)"));
        assertFalse(method.contains("setScaleType("));
    }

    @Test public void dashboardTerminationUsesRetainedOwnerOrExactControlPlane() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf(
                        "private void requestTerminateSession(ComputerDetails host, String"),
                source.indexOf("private boolean isPairingContextCurrent("));

        assertOrdered(method, "new SessionEvent.TerminateRequested(",
                "executor.execute(");
        assertTrue(method.contains("terminateKnownControlPlane(host, profileKey.profileId"));
        assertFalse(method.contains("quitApp("));
        assertFalse(method.contains("stopActiveProviderGame("));
    }

    @Test public void retainedCloseMapsAGameLeftOnHostToItsOwnOutcome() throws IOException {
        String source = consoleActivitySource();
        String adapter = source.substring(source.indexOf(
                        "private static SessionModel.TerminationCallback terminationOutcome("),
                source.indexOf("private void terminateSessionByControlPlane("));
        assertTrue(adapter.contains("@Override public void gameLeftOnHost() {\n"
                + "                finished.accept(HostSessionTermination.Outcome"
                + ".CLOSED_GAME_UNVERIFIED);"));
        String request = source.substring(source.indexOf(
                        "private void requestTerminateSession(ComputerDetails host, String"),
                source.indexOf("private static SessionModel.TerminationCallback terminationOutcome("));
        assertTrue(request.contains("terminationOutcome(outcome -> {"));
        assertFalse(request.contains("Outcome.of(success)"));
        String exit = source.substring(source.indexOf(
                        "private void terminateRetainedSessionAndExit()"),
                source.indexOf("private void closeRecordedSessionAndExit("));
        assertOrdered(exit, "if (!outcome.closed()) {",
                "HostSessionTermination.Outcome.CLOSED_GAME_UNVERIFIED");
        assertOrdered(exit, "if (!outcome.closed()) {",
                "R.string.console_terminate_session_game_left");
        String replacement = source.substring(source.indexOf(
                        "private Boolean terminateLiveRetainedForReplacement("),
                source.indexOf("private void beginInstallationSupportStream("));
        assertOrdered(replacement, "throw SessionFailure.CLOSE_GAME_LEFT.raise();",
                "return result.get().closed();");
        String close = source.substring(source.indexOf(
                        "Destructive close path=direct-nonretained"),
                source.indexOf("@Override public void launch(PlayIntent intent, NvApp app,"));
        assertTrue(close.contains("reconnectTermination, outcome.streamClosed(),"));
        assertOrdered(close, "SessionEvent.SuspendedEnded.forExecution(",
                "if (!outcome.closed()) throw SessionFailure.CLOSE_GAME_LEFT");
        assertEquals(com.limelight.R.string.console_terminate_session_game_left,
                SessionFailure.CLOSE_GAME_LEFT.details);
    }

    @Test public void hardResetBypassesOrdinaryTerminationAndClearsLocalOwnerFirst()
            throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf(
                        "private void requestHardTerminateSession("),
                source.indexOf("private void finishHardTerminate("));

        assertOrdered(method, "clearHardResetSessionState(host, profileKey)",
                "executor.execute(");
        assertTrue(method.contains("ChildLaunchBinding childBinding = selectedProfileIsChild"));
        assertTrue(method.contains("boolean success = hardResetHost(gateway, childSessionId)"));
        assertTrue(method.contains("hostGatewayClient.hardResetSession(gateway, childSessionId)"));
        assertFalse(method.contains("terminateKnownControlPlane("));
        assertFalse(method.contains("new SessionEvent.TerminateRequested("));
    }

    @Test public void retainedFallbackKeepsAllHostEffectsInsideControlPermit() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf(
                        "private void terminateRetainedSessionAndExit()"),
                source.indexOf("private String currentRetainedStreamSessionId()"));

        assertTrue(method.contains("new SessionEvent.TerminateRequested("));
        assertTrue(method.contains("knownControlPlaneTermination(host, target.profileId"));
        assertTrue(method.contains("providerGameId)"));
        assertTrue(method.contains("complete.complete(success)"));
        assertFalse(method.contains("stopActiveProviderGame("));
        assertFalse(method.contains("quitApp("));
    }

    @Test public void closeAndExitLeavesAtOnceAndTheCloseOutlivesTheActivity() throws IOException {
        String source = consoleActivitySource();
        String exit = source.substring(source.indexOf(
                        "private void terminateRetainedSessionAndExit()"),
                source.indexOf("private void closeRecordedSessionAndExit("));
        int request = exit.indexOf("new SessionEvent.TerminateRequested(");
        // Leaves right after asking, never waiting for the result; only the process-scoped exit
        // request (MoonWakerExitTest) decides whether the completed close ends MoonWaker.
        assertOrdered(exit, "MoonWakerExit.request()", "new SessionEvent.TerminateRequested(",
                "moveTaskToBack(true);");
        assertTrue(exit.contains("MoonWakerExit.take(exit)"));
        assertTrue(exit.contains("if (away) MoonWakerExit.finishTasks(app);"));
        assertFalse(exit.substring(request).contains("finishAffinity()"));
        assertFalse(exit.contains("active"));
        assertFalse(exit.contains("showSidePanelBusy("));
        assertFalse(exit.substring(request).contains("exitMoonWaker()"));
        String recorded = source.substring(source.indexOf(
                        "private void closeRecordedSessionAndExit("),
                source.indexOf("private void exitMoonWaker()"));
        int thread = recorded.indexOf("new Thread(() -> {");
        // The close is resolved before the thread and runs on app-scoped state only.
        assertOrdered(recorded, "knownControlPlaneTermination(", "new Thread(() -> {",
                "close.get()");
        assertFalse(recorded.substring(thread).contains("terminateKnownControlPlane("));
        assertFalse(recorded.contains("executor.execute("));
        assertFalse(recorded.contains("showSidePanelBusy("));
    }

    @Test public void hostSleepSettlesOutsideTheActivityAndAnObservedSleepReadsAsleep()
            throws IOException {
        String source = consoleActivitySource();
        String sleep = source.substring(source.indexOf("private void requestHostSleep("),
                source.indexOf("private void wakeHost("));
        int thread = sleep.indexOf("new Thread(() -> {");
        // The decisions live in HostReadiness (HostReadinessTest); here only that the thread
        // routes through them and touches no Activity state but through a weak reference.
        for (String inThread : new String[] {"client.sleepHost(", "HostReadiness.sleepAnswered(",
                "HostReadiness.settleSleep(model, target.uuid, acceptedAt,"}) {
            assertOrdered(sleep, "new Thread(() -> {", inThread);
        }
        assertFalse(sleep.substring(thread).contains("sessionModel"));
        assertFalse(sleep.substring(thread).contains("hostGatewayClient"));
        assertFalse(sleep.contains("executor.execute("));
        assertTrue(source.contains("return snapshot.hostSleepObserved "
                + "|| host.state != ComputerDetails.State.ONLINE;"));
    }

    @Test public void retainedFallbackRequiresPersistedControlGeneration() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf(
                        "private HostSessionTermination.Outcome terminateKnownControlPlane("),
                source.indexOf("private String knownProviderGameId("));

        assertTrue(method.contains("retained.controlGeneration > 0L"));
        assertTrue(method.contains("pending.controlGeneration > 0L"));
        assertTrue(method.contains("generation <= 0L"));
        assertTrue(method.contains("HostSessionTermination.run("));
        assertFalse(method.contains("quitApp("));
    }

    @Test public void replacementHasNoUnfencedLegacyCloseHelper() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf(
                        "private void beginInstallationSupportStream("),
                source.indexOf("private void launchPreparedStream(", source.indexOf(
                        "private void beginInstallationSupportStream(")));

        assertTrue(method.contains("terminateKnownControlPlane(ready, transitionProfileId"));
        assertFalse(method.contains("quitApp("));
    }

    @Test public void terminationRefreshCannotSubmitAfterActivityDestroy() throws IOException {
        String source = consoleActivitySource();
        String destroy = source.substring(source.indexOf("protected void onDestroy()"),
                source.indexOf("super.onDestroy()"));
        String refresh = source.substring(source.indexOf("private void refreshSessionState("),
                source.indexOf("private void invalidateHostStateAsync("));
        String close = source.substring(source.indexOf("private void finishTerminateSession("),
                source.indexOf("private boolean isPairingContextCurrent("));

        assertOrdered(destroy, "active = false;",
                "executor.shutdownNow()");
        // Late work after teardown is dropped by the pool itself, not guarded per call site.
        assertTrue(source.contains("ExecutorService executor = lifecycleExecutor(3)"));
        assertTrue(source.contains("new ThreadPoolExecutor.DiscardPolicy()"));
        assertFalse(refresh.contains("RejectedExecutionException"));
        assertTrue(close.contains("refreshSessionState(host.uuid)"));
        assertOrdered(close, "if (outcome.streamClosed())", "refreshSessionState(host.uuid)");
    }

    @Test public void playniteStopKeepsCapturedProfileStateAcrossConfirmationAndCompletion()
            throws IOException {
        String source = consoleActivitySource();
        String confirmation = source.substring(
                source.indexOf("private void confirmEndPlayniteGame("),
                source.indexOf("private void requestEndPlayniteGame("));
        String stop = source.substring(
                source.indexOf("private void requestEndPlayniteGame("),
                source.indexOf("private void restoreCachedPlaynitePoster("));
        String hardReset = source.substring(
                source.indexOf("private void clearHardResetSessionState("),
                source.indexOf("private String knownProviderGameId("));

        assertTrue(confirmation.contains(
                "HostProfileKey confirmedKey = selectedProfileKey(host.uuid)"));
        assertTrue(confirmation.contains(
                "requestEndPlayniteGame(host, item, confirmedKey)"));
        assertTrue(stop.contains("!confirmedKey.equals(selectedProfileKey(host.uuid))"));
        assertTrue(stop.contains("stopActiveProviderGame(host, expectedGameId, true"));
        assertTrue(stop.contains("refreshSessionProjection(host, ignored -> { })"));
        assertTrue(hardReset.contains("invalidateSessionProjectionRequest(host.uuid)"));
    }

    @Test public void destructiveReplacementUsesExactRetainedTerminationBeforeFallback()
            throws IOException {
        String source = consoleActivitySource();
        // Which close runs is the table's (SessionOrchestrator.closePath); the effect runs it.
        String effects = source.substring(source.indexOf(
                        "@Override public SessionOrchestrator.CloseResult closeSession("),
                source.indexOf("@Override public void launch(", source.indexOf(
                        "@Override public SessionOrchestrator.CloseResult closeSession(")));
        String helper = source.substring(source.indexOf(
                        "private Boolean terminateLiveRetainedForReplacement("),
                source.indexOf("private void beginInstallationSupportStream("));

        assertTrue(effects.contains("terminateLiveRetainedForReplacement("));
        assertTrue(effects.contains("terminateKnownControlPlane(host, intent.profileId"));
        assertTrue(effects.matches("(?s).*new SessionEvent\\.DirectTerminationBegun\\(retained\\).*"));
        assertFalse(effects.contains(".restoreReconnectIfTerminating("));
        assertTrue(helper.contains("new SessionEvent.TerminateRequested("));
        assertTrue(helper.contains("cancelled.getAsBoolean()"));
    }

    @Test public void sunshineAppMenusUseTheProviderAwareTerminationPath() throws IOException {
        String source = consoleActivitySource();
        String quickActions = source.substring(source.indexOf(
                        "private void showQuickLaunchItemActions("),
                source.indexOf("private void confirmRemoveQuickLaunchItem("));
        String appActions = source.substring(source.indexOf("private void showAppActions("),
                source.indexOf("private void addAppAction("));

        assertTrue(quickActions.contains("confirmTerminateSession(host)"));
        assertFalse(quickActions.contains("ServerHelper.doQuit"));
        assertTrue(appActions.contains("confirmTerminateSession(host)"));
        assertFalse(appActions.contains("ServerHelper.doQuit"));
    }

    @Test public void networkPreflightDoesNotClaimWakeOnLanUnconditionally()
            throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf("private String preflightStageMessage("),
                source.indexOf("private String preflightFailureMessage("));

        assertTrue(method.contains("R.string.console_checking_host"));
        assertTrue(method.contains("R.string.transition_checking_game_launch_service"));
        assertFalse(method.contains("R.string.console_wol_status"));
        assertFalse("no game runs before the stream starts it",
                method.contains("R.string.transition_verifying_readiness"));
    }

    @Test public void terminatingSessionDisablesHostAndGameSideActions()
            throws IOException {
        String source = consoleActivitySource();
        String host = source.substring(source.indexOf("private void showHostSelectionOptions("),
                source.indexOf("static HostGatewayClient.IntegrationProfile activeAuthorizedProfile("));
        String playnite = source.substring(source.indexOf("private void showPlayniteGameActions("),
                source.indexOf("private void confirmPlayniteUninstall("));
        String sunshine = source.substring(source.indexOf("private void showAppActions("),
                source.indexOf("private void addAppAction("));
        String quick = source.substring(source.indexOf("private void showQuickLaunchItemActions("),
                source.indexOf("private void confirmRemoveQuickLaunchItem("));

        assertTrue(host.contains("SessionSnapshot.State.TERMINATING"));
        assertTrue(host.contains("!terminating && ConsoleHostPresentation.canWake(host)"));
        // Closing or ending a game: one owner of what the busy row says.
        assertTrue(source.contains("return snapshot.state == SessionSnapshot.State.TERMINATING"));
        assertTrue(playnite.contains("String closing = closingStatus(session);"));
        assertTrue(sunshine.contains("String closing = closingStatus(session);"));
        assertTrue(quick.contains("String closing = closingStatus(session);"));
    }

    @Test public void carouselHistoryUsesExactGameIdentityInsteadOfSharedDesktopApp() throws IOException {
        String source = consoleActivitySource();
        String activity = source.substring(source.indexOf("private long playActivityEpoch("),
                source.indexOf("private static String playniteCarouselInstallActivityKey("));
        String launch = source.substring(source.indexOf("private void launchPreparedStream("),
                source.indexOf("private boolean handleRetainedStreamLaunch("));
        String retainedSwitch = source.substring(source.indexOf(
                        "private SessionOrchestrator.CloseResult switchRetainedProviderGame("),
                source.indexOf("private void showLoading(String hostName",
                        source.indexOf("private SessionOrchestrator.CloseResult "
                                + "switchRetainedProviderGame(")));
        String completedInstall = source.substring(source.indexOf(
                        "private void completePlayniteInstallation("),
                source.indexOf("private static String playniteInstallNotificationKey("));

        assertTrue(activity.contains(
                "PlayHistory.gameKey(hostUuid, item.stableId())"));
        assertFalse(activity.contains("PlayHistory.appKey("));
        // Launching a game's stream, or reusing one, is not playing the game: the stream
        // screen records it once the host confirmed the game ready.
        assertTrue(launch.contains("transition.type != LaunchTransitionType.GAME"));
        assertTrue(launch.contains("transition.type != LaunchTransitionType.GAME_CONNECTION"));
        assertFalse(launch.contains("PlayHistory.gameKey("));
        assertFalse(source.contains("PlayHistory.recordGame("));
        assertOrdered(retainedSwitch, "result == SessionModel.SwitchOutcome.REUSED",
                "result == SessionModel.SwitchOutcome.CANCELLED");
        assertFalse(retainedSwitch.contains("PlayHistory."));
        assertFalse(retainedSwitch.contains("preferences.edit()"));
        assertTrue(launch.contains("Game.EXTRA_STREAM_TARGET_NAME"));
        assertTrue(launch.contains("Game.EXTRA_NEUTRAL_STREAM_TARGET"));
        assertTrue(launch.contains("PlayniteTargetResolver.isNeutralStream(app)"));
        assertTrue(completedInstall.contains("playniteCarouselInstallActivityKey("));
    }

    @Test public void freshSunshineOwnershipRequiresAuthoritativeFreshNeutralGameLaunch()
            throws IOException {
        String source = consoleActivitySource();
        String launch = source.substring(source.indexOf("@Override public void launch("),
                source.indexOf("@Override public void preflightFailed("));
        String prepared = source.substring(source.indexOf("private void launchPreparedStream("),
                source.indexOf("private boolean handleRetainedStreamLaunch("));

        assertTrue(launch.contains("ownsFreshSunshineSession"));
        assertTrue(launch.contains("transitionType == LaunchTransitionType.GAME"));
        assertTrue(launch.contains("PlayniteTargetResolver.isNeutralStream(app)"));
        assertTrue(prepared.contains("Game.EXTRA_FRESH_SUNSHINE_SESSION_OWNER"));
        assertTrue(prepared.contains("ownsFreshSunshineSession"));
    }

    @Test public void uncertainSessionUsesOpaqueRefreshAndGamesStartOnlyOnTheNeutralStream()
            throws IOException {
        String source = consoleActivitySource();
        String orchestration = source.substring(source.indexOf(
                        "private SessionOrchestrator createSessionOrchestrator()"),
                source.indexOf("private void beginInstallationSupportStream("));
        String activation = source.substring(source.indexOf(
                        "private void activatePlayniteItem("),
                source.indexOf("private void startPlayniteInstallation("));
        String resolver = source.substring(source.indexOf(
                        "private void refreshSessionProjection("),
                source.indexOf("private void invalidateSessionProjectionRequest("));
        String actions = source.substring(source.indexOf("private void showPlayniteGameActions("),
                source.indexOf("private void confirmPlayniteUninstall("));

        assertTrue(orchestration.contains("@Override public void refreshSession("));
        assertTrue(orchestration.contains("streamLoadingView.setStep(1,"));
        assertTrue(orchestration.contains(
                "refreshSessionProjection(host, intent.profileKey, accepted ->"));
        assertTrue(orchestration.contains("console_session_uncertain_title"));
        assertTrue(orchestration.contains("@Override public void sessionFailed("));
        // A host that does not answer is woken by the table's own step under the visible
        // waking status, with the existing bounded wake; the refresh itself never wakes.
        String wake = orchestration.substring(orchestration.indexOf(
                        "@Override public SessionFailure awaitHost("),
                orchestration.indexOf("@Override public SessionSnapshot resolve(HostProfileKey"));
        assertTrue(orchestration.contains("@Override public boolean isHostOnline(String hostId)"));
        assertTrue(wake.contains("HostReadiness.await("));
        assertTrue(wake.contains("getString(R.string.console_status_waking)"));
        assertTrue(wake.contains("streamLoadingView.setStep(1, message)"));
        // No wake address: no empty WoL, the wait stays. A host this client put to sleep is
        // woken while its polls still call it online. The Gateway is awaited after the port.
        assertTrue(wake.contains(
                "ConsoleHostPresentation.hasWakeAddress(host) ? host : null, cancelled,"));
        assertOrdered(wake, "HostReadiness.awaitDown(current, cancelled)",
                "HostReadiness.await(current,",
                "if (!ConsoleActionCatalog.isPaired(ready)) return null;",
                "getString(R.string.transition_waiting_gateway)",
                "hostLaunchPreflight.awaitGateway(");
        assertTrue(wake.contains("? null : SessionFailure.HOST_GATEWAY_TIMEOUT"));
        assertTrue(wake.contains("new SessionEvent.HostSleepCleared(host.uuid)"));
        assertFalse(wake.contains("WakeOnLanSender"));
        assertFalse(resolver.contains("HostReadiness"));
        assertTrue(resolver.contains("invalidateSessionProjectionRequest(host.uuid)"));
        assertTrue(resolver.contains("completion.accept(false)"));
        // No exit before the poll starts may strand a waiting completion (endless loader).
        String beforePoll = resolver.substring(resolver.indexOf("HostProfileKey requestKey"),
                resolver.indexOf("executor.execute("));
        assertEquals(3, beforePoll.split("if \\(completion != null\\) completion\\.accept"
                + "\\(false\\);\\s+return;", -1).length - 1);
        assertTrue(resolver.contains(
                "completion.accept(projected != null && sameRenderScope)"));
        assertTrue(activation.contains(
                "playPlayniteGame(host, PlayniteTargetResolver.resolveNeutralStream(apps), item.game);"));
        // A child's blocked tile answers with a popup before any cover or start.
        SourceText.assertOrdered(activation, "if (childPolicy.blocked) {",
                "ConsoleUiFeedback.makeText(this, childLaunchFailureMessage(childPolicy.reason),",
                "playPlayniteGame(");
        assertTrue(orchestration.contains(
                "LaunchTransitionType transitionType = intent.transitionType(type);"));
        assertFalse(actions.contains("playnite_choose_launch_method"));
        assertFalse(source.contains("showPlayniteTargetPicker("));
    }

    @Test public void terminationSupportsManualStreamAndGameOnlySessions()
            throws IOException {
        String source = consoleActivitySource();
        String confirm = source.substring(source.indexOf(
                        "private void confirmTerminateSession("),
                source.indexOf("private void requestTerminateSession("));
        String actions = source.substring(source.indexOf("private void showPlayniteGameActions("),
                source.indexOf("private void confirmPlayniteUninstall("));

        assertTrue(confirm.contains("session.streamerAppId == 0"));
        assertTrue(confirm.contains("SessionSnapshot.State.ACTIVE"));
        assertTrue(actions.contains("session.state == SessionSnapshot.State.ACTIVE"));
        assertTrue(actions.contains("session.playniteGameId"));
        assertFalse(actions.contains("resumePlayniteGameId)\n"
                + "                    && host.runningGameId != 0"));
    }

    @Test public void unknownStreamerKeepsTheSleepResumeRecordAndRendersBadgeChanges()
            throws IOException {
        String source = consoleActivitySource();
        String sleep = source.substring(source.indexOf("private void requestHostSleep("),
                source.indexOf("renderHostSelection();", source.indexOf(
                        "private void requestHostSleep(")));
        assertOrdered(sleep, "session.streamerAppId == SessionSnapshot.STREAMER_APP_UNKNOWN",
                "? session.hostGameAppId : session.streamerAppId;", "if (runningAppId > 0) {");
        String key = source.substring(source.indexOf("private String sessionPresentationKey("),
                source.indexOf("private void resumeSelectedSession("));
        assertTrue(key.contains("snapshot.streamerAppId"));
    }

    @Test public void retainedSwitchSkipsWakeAndRequiresPreparedNeutralTarget()
            throws IOException {
        String source = consoleActivitySource();
        String preflight = source.substring(source.indexOf(
                        "private HostLaunchPreflight createHostLaunchPreflight()"),
                source.indexOf("private String preflightStageMessage("));
        assertOrdered(preflight, "Action.SWITCH_RETAINED", "HostReadiness.requestWake(");
        assertFalse(preflight.contains("HostReadiness.await("));
        assertFalse(preflight.contains("HostReadiness.awaitAfterWakeDecision("));
        assertTrue(preflight.contains("State.HOME_LIVE"));
        assertTrue(preflight.contains("State.PREPARING"));
        // When a close switches the retained stream: SessionTransitionTableTest
        // competingSessionGivesWayBySwitchConfirmationOrTheExactClose.
    }

    @Test public void retryKeepsIntentAndFreshObservationRestoresResumePresentation()
            throws IOException {
        String source = consoleActivitySource();
        String retry = source.substring(source.indexOf("@Override public void onRetry()"),
                source.indexOf("@Override public void onShowStreamAnyway()", source.indexOf(
                        "@Override public void onRetry()")));
        String resolve = source.substring(source.indexOf(
                        "private void refreshSessionProjection("),
                source.indexOf("private void invalidateSessionProjectionRequest("));

        String reject = source.substring(source.indexOf("@Override public void reject("),
                source.indexOf("@Override public void confirmReplacement("));

        assertTrue(retry.contains("sessionOrchestrator.retry()"));
        // A rejection after Retry lands on the raised loading layer, not in a toast over it.
        assertTrue(reject.contains("if (streamLoadingView != null) {"));
        assertTrue(reject.contains("streamLoadingView.showError(getString(message), \"\");"));
        assertFalse(retry.contains("sessionOrchestrator.cancel()"));
        assertFalse(retry.contains("showHome()"));
        assertTrue(resolve.contains("gateway.snapshot().sessionSnapshot"));
        // Only the presentation key decides a render; no per-Activity copy of the projection.
        assertTrue(resolve.contains("renderLibraryForSessionChange(currentHost(expectedHostId))"));
    }

    @Test public void warmUpCancelAndFailureRemainScopedAndInline() throws IOException {
        String source = consoleActivitySource();
        String cancel = source.substring(source.indexOf("@Override public void onCancel()"),
                source.indexOf("@Override public void onRetry()", source.indexOf(
                        "@Override public void onCancel()")));
        String hostCancel = source.substring(source.indexOf(
                        "private void cancelOwnedWarmUp("),
                source.indexOf("private void startAppListPoller("));
        String preparation = source.substring(source.indexOf(
                        "private void prepareSelectedHost("),
                source.indexOf("private void armPendingWarmUpRelay("));
        String failure = source.substring(source.indexOf(
                        "@Override public void preparationFailed()"),
                source.indexOf("@Override public void preflightFailed("));
        String status = source.substring(source.indexOf(
                        "private String hostStatus(ComputerDetails host, SessionSnapshot snapshot)"),
                source.indexOf("private ConsoleHostPresentation.State consoleHostState("));
        String loading = source.substring(source.indexOf(
                         "@Override public void showLoading(PlayIntent intent"),
                source.indexOf("@Override public void refreshSession("));
        // The pause keeps the cover over the handoff; only the stop (the stream on screen) ends it.
        String pause = source.substring(source.indexOf("protected void onPause()"),
                source.indexOf("@Override protected void onStop() {"));
        String back = source.substring(source.indexOf("public void onBackPressed()"),
                source.indexOf("private void loadKnownHosts("));
        String scopedCancel = source.substring(source.indexOf(
                        "private void cancelCurrentPreparation()"),
                source.indexOf("private void resumeSession(ComputerDetails host)"));
        String worker = source.substring(source.indexOf(
                        "@Override public SessionOrchestrator.PreparedWarmUp prepareHost("),
                source.indexOf("@Override public void launchPreparedHost("));
        String english = resourceSource("values/strings.xml");
        String polish = resourceSource("values-pl/strings.xml");

        assertTrue(cancel.contains("cancelCurrentPreparation()"));
        assertTrue(back.contains("cancelCurrentPreparation()"));
        assertTrue(pause.contains("cancelCurrentPreparation(childStreamHandoff)"));
        assertFalse(pause.contains("showHome()"));
        assertTrue(scopedCancel.contains("cancelPreparation(warmUpClientHostId)"));
        assertTrue(scopedCancel.contains("renderHosts();"));
        assertTrue(scopedCancel.contains("updateHostSelector();"));
        assertTrue(scopedCancel.contains("else if (sessionOrchestrator != null)"));
        assertFalse(cancel.contains("cancelOwnedWarmUp"));
        assertTrue(hostCancel.contains(
                "new SessionEvent.CancelPreparingTransport(preparing)"));
        assertTrue(hostCancel.contains("if (cancelled && relinquishForHostChange)"));
        assertTrue(preparation.contains("warmUpStatus = WARM_UP_ERROR"));
        assertOrdered(preparation, "warmUpStatus = WARM_UP_ERROR",
                "renderHosts();");
        assertOrdered(preparation, "warmUpStatus = WARM_UP_ERROR",
                "updateHostSelector();");
        assertTrue(failure.contains("streamLoadingView.showError("));
        assertTrue(failure.contains("console_warm_up_fallback_details"));
        assertFalse(failure.contains("console_host_timeout"));
        assertFalse(failure.contains("Toast"));
        assertTrue(status.contains("sessionSubscriber.reconnectRequired(host.uuid)"));
        assertTrue(status.contains("sessionOrchestrator.hasPreparationForHost(host.uuid)"));
        assertTrue(status.contains("closingStatus(snapshot)"));
        assertOrdered(status, "closingStatus(snapshot)",
                "warmUpStatus == WARM_UP_ERROR");
        assertOrdered(status, "ConsoleHostPresentation.State.ACTIVE_SESSION",
                "warmUpStatus == WARM_UP_ERROR");
        assertTrue(status.contains("if (localWarmUp && preparingHost)"));
        // A sleep this client requested is shown at once, before every session status but
        // that of a live session or a game being closed.
        assertTrue(source.contains("return snapshot.hostSleepRequested "
                + "&& !snapshot.hasActiveSession()"));
        assertTrue(source.contains("                && !snapshot.endingGame;"));
        assertFalse(source.contains("if (snapshot.hostSleepRequested) {"));
        assertFalse(source.contains("|| session.hostSleepRequested"));
        assertOrdered(status, "if (showsHostSleep(snapshot)) {",
                "sessionSubscriber.warmUpStatus(");
        assertOrdered(status, "R.string.console_status_suspending_short",
                "closingStatus(snapshot)");
        String dot = source.substring(source.indexOf(
                "private ConsoleHostPresentation.State consoleHostState("),
                source.indexOf("private ConsoleHostPresentation.Profile consoleProfile("));
        assertOrdered(dot, "if (showsHostSleep(snapshot)) {",
                "SessionSnapshot.State.TERMINATING");
        // A saved session on an online host is named from its record (stable between host
        // polls), after every warm-up status and instead of a bare "Online".
        assertTrue(status.contains(
                "ConsoleHostPresentation.holdsPausedSession(state, snapshot)"));
        assertOrdered(status, "warmUpStatus == WARM_UP_ERROR",
                "ConsoleHostPresentation.holdsPausedSession(");
        assertOrdered(status, "ConsoleHostPresentation.holdsPausedSession(",
                "return getString(ConsoleHostPresentation.status(state));");
        assertTrue(status.contains("sessionModel.records().pending"));
        assertTrue(status.contains("R.string.console_status_paused_stream"));
        // A game running without this client's session is named after every session status
        // and instead of a bare "Online", from the projection owner's display fact and this
        // profile's library only (a child's library lists only its shared games).
        assertOrdered(status, "ConsoleHostPresentation.holdsPausedSession(",
                "ConsoleHostPresentation.showsRunningGame(state, snapshot)");
        assertOrdered(status, "ConsoleHostPresentation.showsRunningGame(",
                "return getString(ConsoleHostPresentation.status(state));");
        assertTrue(status.contains("libraryGameName(snapshot.display.runningGameId)"));
        assertTrue(status.contains("getString(R.string.console_status_game_running, name)"));
        String named = status.substring(status.indexOf("private String libraryGameName("),
                status.indexOf("private String pausedSessionName("));
        assertTrue(named.contains("unfilteredPlayniteItems"));
        assertFalse(named.contains("findAppName"));
        assertFalse(named.contains("loadApps"));
        // Durations come from the host through the same owner, in the shared format; a live
        // session shows its game's time, otherwise the stream's.
        assertTrue(status.contains("ConsoleHostPresentation.sessionMinutes("));
        assertTrue(status.contains("snapshot.display.gameMinutes"));
        assertTrue(named.contains("minutes < 1L ? status"));
        assertTrue(named.contains("PlaynitePlaytimeFormatter.minutes(minutes,"));
        assertFalse(status.contains("System.currentTimeMillis()"));
        // The library's presentation key reads the status without its minutes.
        assertTrue(source.contains("+ '|' + hostStatus(host, snapshot, false)\n"));
        assertTrue(english.contains(
                "name=\"console_status_game_running\">Game running · %1$s<"));
        assertTrue(polish.contains(
                "name=\"console_status_game_running\">Gra działa · %1$s<"));
        assertTrue(english.contains("name=\"console_status_with_duration\">%1$s · %2$s<"));
        assertTrue(polish.contains("name=\"console_status_with_duration\">%1$s · %2$s<"));
        // The status renders on the UI thread: the paused name reads no app list.
        String paused = status.substring(status.indexOf("private String pausedSessionName("),
                status.indexOf("private String closingStatus("));
        assertFalse(paused.contains("findAppName"));
        assertFalse(paused.contains("loadApps"));
        assertTrue(status.contains("getString(R.string.console_status_suspended_game, name)"));
        assertTrue(english.contains(
                "name=\"console_status_paused_stream\">Paused stream<"));
        assertTrue(polish.contains(
                "name=\"console_status_paused_stream\">Wstrzymany stream<"));
        assertFalse(status.contains("&& warmUpStatus == WARM_UP_PREPARING) {\n"
                + "            return getString(R.string.console_warm_up_preparing);"));
        assertOrdered(worker, "hostLaunchPreflight.run(", "refreshWarmUpHost(host)");
        assertFalse(worker.contains("HostReadiness.await("));
        assertFalse(worker.contains("HostReadiness.awaitAfterWakeDecision("));
        assertTrue(source.contains("return HostReadiness.mergeFreshDetails(\n"
                + "                    host, connection.getComputerDetails(true));"));
        assertTrue(english.contains("name=\"console_warm_up_fallback_details\""));
        assertTrue(english.contains("Retry will launch the game normally"));
        assertTrue(polish.contains("name=\"console_warm_up_fallback_details\""));
        assertTrue(polish.contains("Ponów uruchomi grę zwykłą ścieżką"));
        assertTrue(loading.contains("warmUpStatus == WARM_UP_ERROR"));
        assertTrue(loading.contains("!sessionOrchestrator.hasPreparationForHost(intent.hostId)"));
    }

    @Test public void staleWarmUpRelayFailsClosedWithoutPlay() throws IOException {
        String source = consoleActivitySource();
        String arm = source.substring(source.indexOf(
                        "private void armPendingWarmUpRelay()"),
                source.indexOf("protected final void acceptPreparingHomeFrame()"));
        String accepted = source.substring(source.indexOf(
                        "protected final void acceptPreparingHomeFrame()"),
                source.indexOf("private void dispatchPendingWarmUpRelay()"));
        String relay = source.substring(source.indexOf(
                        "private void dispatchPendingWarmUpRelay()"),
                source.indexOf("private void clearPendingWarmUpRelay()"));
        String mismatch = relay.substring(relay.indexOf("if (!exact)"),
                relay.indexOf("PlayIntent relay = pendingWarmUpRelay"));

        assertTrue(mismatch.contains("clearPendingWarmUpRelay()"));
        assertTrue(mismatch.contains("warmUpStatus = WARM_UP_ERROR"));
        assertTrue(mismatch.contains("showHome()"));
        assertFalse(mismatch.contains("sessionOrchestrator.play("));
        assertFalse(mismatch.contains("playProviderGame("));
        assertFalse(arm.contains("showLoading("));
        assertFalse(arm.contains("dispatchPendingWarmUpRelay()"));
        assertTrue(accepted.indexOf("showLoading(")
                < accepted.indexOf("dispatchPendingWarmUpRelay()"));
        assertTrue(relay.contains("!warmUpHomeFrameAccepted"));
        int play = relay.indexOf("playProviderGame(relay)");
        assertTrue(relay.indexOf("warmUpRelaySubmitted = true") < play);
        assertEquals(-1, relay.indexOf("clearPendingWarmUpRelay()", play));
        assertTrue(relay.contains("isPendingWarmUpRelayOwnedOrCompleted"));
        assertTrue(source.contains(
                "sessionModel.isPreparingSwitchOwned("));
    }

    @Test public void automaticHostSelectionResolvesProfileGateBeforeWarmUp() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(
                source.indexOf("private void resolveInitialHostSelection()"),
                source.indexOf("private void showHostSelection("));
        String enter = source.substring(
                source.indexOf("private void enterHostAfterProfileGate("),
                source.indexOf("private void closeProfileGate("));

        assertTrue(method.contains("resolveProfileGate(automatic, false, true)"));
        assertFalse(method.contains("selectHost(automatic, false)"));
        assertOrdered(enter, "selectHost(host, focusApps)",
                "prepareSelectedHost(host)");
        assertOrdered(method, "initialHostSelectionResolved = true",
                "resolveProfileGate(automatic, false, true)");
    }

    @Test public void retainedHomeStartingWindowIsOpaqueButTranslucent() throws IOException {
        String styles = resourceSource("values/styles.xml");
        String theme = styles.substring(styles.indexOf("name=\"ConsoleStreamHomeTheme\""),
                styles.indexOf("</style>", styles.indexOf(
                        "name=\"ConsoleStreamHomeTheme\"")));

        assertTrue(theme.contains("name=\"android:windowIsTranslucent\">true"));
        assertTrue(theme.contains("name=\"android:windowBackground\">"
                + "@color/console_background"));
        assertFalse(theme.contains("@android:color/transparent"));
    }

    @Test public void hiddenWarmUpUsesTransparentStartButCommitsOpaqueRoot()
            throws IOException {
        String styles = resourceSource("values/styles.xml");
        String warmUpTheme = styles.substring(styles.indexOf(
                        "name=\"ConsoleStreamHomeWarmUpTheme\""),
                styles.indexOf("</style>", styles.indexOf(
                        "name=\"ConsoleStreamHomeWarmUpTheme\"")));
        String streamHome = streamHomeActivitySource();
        int createStart = streamHome.indexOf("protected void onCreate(");
        String create = streamHome.substring(createStart,
                streamHome.indexOf("private SessionModel.Snapshot",
                        createStart));
        String console = consoleActivitySource();
        String build = console.substring(console.indexOf("private FrameLayout buildUi()"),
                console.indexOf("private void deferSecondaryLayersAfterFirstLayout("));

        assertTrue(warmUpTheme.contains("parent=\"ConsoleStreamHomeTheme\""));
        assertTrue(warmUpTheme.contains("@android:color/transparent"));
        assertTrue(create.indexOf("exactPreparingSnapshot()")
                < create.indexOf("super.onCreate(state)"));
        assertTrue(create.contains("preparingHomeFramePending = shouldAwaitPreparingFrame("));
        assertTrue(console.contains("setTheme(useTransparentWarmUpStartingWindow()"));
        assertTrue(streamHome.contains("return preparingHomeFramePending"));
        assertTrue(create.contains("preparingHomeFrameAccepted ="
                + " hasAcceptedPreparingHomeFrame()"));
        assertTrue(create.contains("preparingRelayOwned = hasOwnedPreparingRelay()"
                + " || hasCompletedPreparingRelay()"));
        assertTrue(create.contains("boolean ready = hasResolvedInitialHostSelection()"));
        // The Home's first frame waits on the barrier (frame commit, minSdk 29) with its
        // deadline; no animation-callback fallback.
        assertOrdered(create, "acceptPreparingHomeFrame()",
                "homeFrames.await(homeFrameFence(), PresentationBarrier.HOME_DEADLINE_MS,");
        assertFalse(create.contains("postOnAnimation("));
        assertTrue(build.contains("container.setBackgroundResource(R.color.console_background)"));
    }

    @Test public void hiddenWarmUpWaitsForSettledLocalCarouselPresentation()
            throws IOException {
        // Local cache is still pending: the loading ghosts must not be committed.
        assertFalse(ConsoleActivity.initialLocalPresentationReady("host", "", ""));
        assertFalse(ConsoleActivity.initialLocalPresentationReady(
                "host", "host", ""));
        assertFalse(ConsoleActivity.initialLocalPresentationReady(
                "host", "", "host"));
        assertFalse(ConsoleActivity.initialLocalPresentationReady(
                "host", "host", "other"));
        // Both a cache hit and a locally resolved empty-cache fallback use this gate.
        assertTrue(ConsoleActivity.initialLocalPresentationReady(
                "host", "host", "host"));

        String console = consoleActivitySource();
        String streamHome = streamHomeActivitySource();
        String select = console.substring(console.indexOf(
                        "private void selectHost(ComputerDetails host, boolean focusApps)"),
                console.indexOf("private void cancelOwnedWarmUp("));
        String settle = console.substring(console.indexOf(
                        "private void settleInitialLocalPresentation("),
                console.indexOf("private void requestPlayniteRefresh("));
        String localApps = console.substring(console.indexOf(
                        "private void renderAppsAsync(ComputerDetails host, boolean focusApps)"),
                console.indexOf("private void runLibraryUpdateWhenNavigationIdle("));
        String localLibrary = console.substring(console.indexOf(
                        "private void loadPlayniteForHost("),
                console.indexOf("private void settleInitialLocalPresentation("));
        String prepare = console.substring(console.indexOf(
                        "protected final boolean prepareInitialCarouselFrame()"),
                console.indexOf("protected final void completeInitialCarouselFrame()"));
        String preDraw = streamHome.substring(streamHome.indexOf(
                        "@Override public boolean onPreDraw()"),
                streamHome.indexOf("return true;", streamHome.indexOf(
                        "@Override public boolean onPreDraw()")));

        assertTrue(select.contains("initialLocalAppsHostId = \"\""));
        assertTrue(select.contains("initialLocalLibraryHostId = \"\""));
        assertTrue(console.contains("initialLocalAppsHostId = uuid"));
        assertTrue(console.contains("initialLocalLibraryHostId = host.uuid"));
        assertTrue(localLibrary.indexOf("playniteLibraryRepository.cached(host.uuid)")
                < localLibrary.indexOf("initialLocalLibraryHostId = host.uuid"));
        assertTrue(localLibrary.indexOf("initialLocalLibraryHostId = host.uuid")
                < localLibrary.lastIndexOf("requestPlayniteRefresh("));
        assertTrue(preDraw.contains("prepareInitialCarouselFrame()"));
        assertTrue(prepare.contains("initialLocalPresentationReady("));
        assertTrue(prepare.contains(
                "View target = inherited != null ? inherited : firstFocusableChild(appRow)"));
        assertTrue(prepare.contains("appScroll.scrollTo(scroll, 0)"));
        assertTrue(prepare.contains("target.requestFocus()"));
        assertTrue(prepare.contains("enterExpandedLibrary(true)"));
        assertTrue(prepare.contains("return false;"));
        assertTrue(settle.contains("currentPlayniteGames.isEmpty()"));
        assertTrue(settle.contains("renderApps(host, currentSunshineApps)"));
        assertTrue(settle.contains("renderPlayniteLibrary(host, currentSunshineApps)"));
        assertTrue(settle.contains("restoreInitialLibraryPresentation(host)"));
        assertFalse(settle.contains("requestPlayniteRefresh("));
        assertTrue(localApps.indexOf("initialLocalAppsHostId = uuid")
                < localApps.indexOf("if (requiresPreparedInitialCarouselFrame())"));
        assertTrue(localApps.indexOf("settleInitialLocalPresentation(latestHost)")
                < localApps.indexOf("renderPlayniteLibrary(latestHost, apps)"));
        assertTrue(localLibrary.contains("deferInitialPlayniteRefresh = true"));
        assertTrue(localLibrary.contains("if (!deferInitialPlayniteRefresh)"));
        assertTrue(console.contains("if (deferInitialPlayniteRefresh)"));
        assertTrue(console.contains("scheduleNextPlayniteRefresh()"));
        assertTrue(select.contains("deferInitialHostProfileRefresh = true"));
        assertTrue(console.contains("if (deferInitialHostProfileRefresh)"));
        assertTrue(console.contains("refreshHostProfiles(selected)"));
        String restore = console.substring(console.indexOf(
                        "private boolean restoreInitialLibraryPresentation("),
                console.indexOf("private void requestPlayniteRefresh("));
        assertTrue(restore.contains("cached.games != currentPlayniteGames"));
        assertTrue(restore.contains("selectedProfileKey(host.uuid).equals(cached.profileKey)"));
        assertTrue(restore.contains("applyPlayniteDiff("));
        // The returning Home projects the current session, never the one before the stream.
        assertTrue(restore.indexOf("SessionSnapshot session = renderedSessionSnapshot(host);")
                < restore.indexOf("applyPlayniteDiff("));
        assertTrue(restore.contains(
                "if (!playniteTileSessionSignature(projection, host)\n"
                + "                .equals(cached.sessionSignature)) return false;"));
        assertFalse(restore.contains("cached.sessionProjection"));
        assertFalse(console.contains("final PlayniteSessionPresentation.Projection sessionProjection;"));
        assertFalse(restore.contains("PlayniteTargetResolver.resolve("));
        assertTrue(console.contains("final HostProfileKey profileKey;"));
        assertTrue(console.contains("requestedKey, currentPlayniteGames"));
        assertTrue(console.contains("suppressInitialCarouselMotion = true"));
        assertTrue(console.contains("suppressInitialCarouselMotion = false"));
        assertTrue(console.contains("if (suppressInitialCarouselMotion) return;"));

        // Recreation after an accepted frame reuses the retained operation and does not gate
        // or submit the transport/provider start a second time.
        String create = streamHome.substring(streamHome.indexOf(
                        "protected void onCreate(Bundle state)"),
                streamHome.indexOf("protected boolean useTransparentWarmUpStartingWindow()"));
        assertOrdered(create, "if (operation > 0L && (preparingHomeFrameAccepted"
                        + " || preparingRelayOwned))", "return;", "homeFrames.await(");
    }

    @Test public void ordinaryRetainedHomeCommitsOnlyAReusedCarouselFrame()
            throws IOException {
        String console = consoleActivitySource();
        String streamHome = streamHomeActivitySource();
        String create = streamHome.substring(streamHome.indexOf(
                        "protected void onCreate(Bundle state)"),
                streamHome.indexOf("protected boolean useTransparentWarmUpStartingWindow()"));
        String select = console.substring(console.indexOf(
                        "private void selectHost(ComputerDetails host, boolean focusApps)"),
                console.indexOf("private void cancelOwnedWarmUp("));
        String restoreData = console.substring(console.indexOf(
                        "private boolean restoreInitialLocalPresentationData("),
                console.indexOf("private void loadPlayniteForHost("));
        String localLibrary = console.substring(console.indexOf(
                        "private void loadPlayniteForHost("),
                console.indexOf("private void settleInitialLocalPresentation("));
        String prepare = console.substring(console.indexOf(
                        "protected final boolean prepareInitialCarouselFrame()"),
                console.indexOf("protected final void completeInitialCarouselFrame()"));
        String restoreArtwork = console.substring(console.indexOf(
                        "private void restoreCachedPlaynitePoster("),
                console.indexOf("private void resetInitialCarouselArtworkWarmup("));

        assertTrue(create.contains(
                "initialCarouselFramePending = exactRetainedSnapshot() != null"));
        assertFalse(create.contains("if (attempt <= 0L) return;"));
        assertTrue(create.contains("this::onInitialCarouselFrameSettled"));
        assertTrue(streamHome.contains("return initialCarouselFramePending;"));
        assertTrue(streamHome.contains("if (!initialCarouselFramePending)"
                + " return super.dispatchKeyEvent"));
        assertTrue(select.contains("restoreInitialLocalPresentationData(host)"));
        assertTrue(select.indexOf("settleInitialLocalPresentation(host)")
                < select.indexOf("renderAppsAsync(host, focusApps)"));
        assertTrue(restoreData.contains("currentPlayniteGames = cached.games"));
        assertTrue(restoreData.contains("currentSunshineApps = cached.sunshineApps"));
        assertTrue(restoreData.contains("initialLocalAppsHostId = host.uuid"));
        assertTrue(restoreData.contains("initialLocalLibraryHostId = host.uuid"));
        assertTrue(localLibrary.indexOf("if (!requiresPreparedInitialCarouselFrame()) {")
                < localLibrary.indexOf("showCarouselLoadingGhosts();"));
        assertTrue(localLibrary.indexOf("showCarouselLoadingGhosts();")
                < localLibrary.indexOf("updatePlayniteLibraryStatus(host);"));
        assertTrue(prepare.contains("if (!initialCarouselArtworkLoads.isEmpty()) return false"));
        assertTrue(restoreArtwork.contains("cachePlayniteBitmap(cached.file)"));
        assertTrue(restoreArtwork.contains("appRow.postInvalidateOnAnimation()"));
        assertFalse(restoreArtwork.contains("fetchPlayniteArtwork("));
    }

    @Test public void heldFirstHomeFrameAlwaysEndsWithinItsLimit() throws IOException {
        long limit = StreamHomeActivity.INITIAL_FRAME_HOLD_LIMIT_MS;
        assertTrue(limit > 0L && limit <= 2_000L);
        assertTrue(StreamHomeActivity.holdsInitialFrame(false, 0L));
        assertTrue(StreamHomeActivity.holdsInitialFrame(false, limit - 1L));
        // Past the limit the frame draws whatever is still unprepared.
        assertFalse(StreamHomeActivity.holdsInitialFrame(false, limit));
        assertFalse(StreamHomeActivity.holdsInitialFrame(false, Long.MAX_VALUE));
        assertFalse(StreamHomeActivity.holdsInitialFrame(true, 0L));

        String streamHome = streamHomeActivitySource();
        String preDraw = streamHome.substring(streamHome.indexOf(
                        "@Override public boolean onPreDraw()"),
                streamHome.indexOf("return true;", streamHome.indexOf(
                        "@Override public boolean onPreDraw()")));
        // The only frame hold: every cancelled draw reschedules a traversal that asks again.
        assertEquals(1, occurrences(preDraw, "return false;"));
        assertTrue(preDraw.contains(
                "if (holdsInitialFrame(ready, now - heldSince)) return false;"));
        assertTrue(preDraw.contains("if (heldSince == 0L) heldSince = now;"));

        // The carousel's layout animation starts only with a drawn frame and suppresses the
        // row's layouts until it ends: it is not installed while the first frame is held.
        String console = consoleActivitySource();
        String build = console.substring(console.indexOf("private FrameLayout buildUi()"),
                console.indexOf("private void deferSecondaryLayersAfterFirstLayout("));
        assertTrue(build.contains("if (!requiresPreparedInitialCarouselFrame()) {\n"
                + "                appRow.setLayoutTransition(carouselTransition);"));
        String complete = console.substring(console.indexOf(
                        "protected final void completeInitialCarouselFrame()"),
                console.indexOf("static boolean initialLocalPresentationReady("));
        assertTrue(complete.contains(
                "if (carouselTransition != null) appRow.setLayoutTransition(carouselTransition);"));
        assertEquals(2, occurrences(console, "setLayoutTransition("));
    }

    @Test public void ownedPreparingRelayRecreationDoesNotKeepTheFrameInputGate()
            throws IOException {
        assertTrue(StreamHomeActivity.shouldAwaitPreparingFrame(true, false, false));
        assertFalse(StreamHomeActivity.shouldAwaitPreparingFrame(true, true, false));
        assertFalse(StreamHomeActivity.shouldAwaitPreparingFrame(true, false, true));
        assertFalse(StreamHomeActivity.shouldAwaitPreparingFrame(false, false, false));

        String source = streamHomeActivitySource();
        String recreation = source.substring(source.indexOf(
                        "if (operation > 0L && (preparingHomeFrameAccepted"
                                + " || preparingRelayOwned))"),
                source.indexOf("if (operation > 0L && preparingSnapshot == null)"));
        assertTrue(recreation.contains("initialCarouselFramePending = false"));
        assertTrue(recreation.contains("preparingHomeFramePending = false"));
        assertTrue(recreation.indexOf("completeInitialCarouselFrame()")
                < recreation.indexOf("acceptPreparingHomeFrame()"));
        assertFalse(recreation.contains("preparingHomeFrameSubmitted("));
        assertFalse(recreation.contains("cancelPreparing("));

        String input = source.substring(source.indexOf(
                        "public boolean dispatchKeyEvent("),
                source.indexOf("public void onBackPressed()"));
        assertTrue(input.contains("if (!initialCarouselFramePending) return super.dispatchKeyEvent"));
        assertTrue(input.contains("initialCarouselFramePending || super.dispatchTouchEvent"));
        assertTrue(input.contains("initialCarouselFramePending || super.dispatchGenericMotionEvent"));
    }

    @Test public void staleRetainedHomeCannotCreateAnExitSessionPrompt() throws IOException {
        String source = consoleActivitySource();
        String back = source.substring(source.indexOf("public void onBackPressed()"),
                source.indexOf("private void loadKnownHosts("));
        String retainedPrompt = back.substring(back.indexOf(
                "sessionModel.hasRetainedSession()"),
                back.indexOf("showRetainedStreamExitConfirmation()"));

        assertFalse(retainedPrompt.contains("retainedStreamHome"));
        assertTrue(retainedPrompt.contains(
                "sessionModel.hasRetainedSession()"));
        assertTrue(retainedPrompt.contains("sessionModel.records().pending != null"));
    }

    private static String consoleActivitySource() throws IOException {
        return SourceText.main("console/ConsoleActivity.java");
    }

    private static String streamHomeActivitySource() throws IOException {
        return SourceText.main("console/StreamHomeActivity.java");
    }

    private static String resourceSource(String name) throws IOException {
        return SourceText.module("src/main/res/" + name);
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        for (int at = source.indexOf(value); at >= 0;
             at = source.indexOf(value, at + value.length())) count++;
        return count;
    }
}
