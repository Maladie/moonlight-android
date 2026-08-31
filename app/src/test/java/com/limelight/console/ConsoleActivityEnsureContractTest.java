package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleActivityEnsureContractTest {
    @Test public void screenSaverShowsWholeArtworkWithoutUpscaling() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf("private ImageView screenSaverImage()"),
                source.indexOf("private void buildHostSelectionLayer"));

        assertTrue(method.contains("ImageView.ScaleType.CENTER_INSIDE"));
        assertFalse(method.contains("ImageView.ScaleType.CENTER_CROP"));
    }

    @Test public void successfulEnsurePollsBeforeAnyRenderOfTheOldAppList() throws IOException {
        String method = consoleActivitySource();
        method = method.substring(method.indexOf("private void ensureVibepolloAfterInstall"),
                method.indexOf("private static String playniteInstallNotificationKey"));

        assertFalse(method.contains("setGameTarget("));
        assertTrue(method.contains("if (\"playnite\".equals(game.provider)) {\n"
                + "                    result = hostGatewayClient.ensureVibepolloPlayniteApp("));
        assertTrue(method.contains("if (ensured != null) {\n"
                + "                    if (appListPoller != null) appListPoller.pollNow();\n"
                + "                    return;\n"
                + "                }"));
    }

    @Test public void dashboardTerminationStopsProviderBeforeSunshine() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf("private void requestTerminateSession("),
                source.indexOf("private String knownProviderGameId("));

        assertTrue(method.indexOf("stopActiveProviderGame(host, providerGameId")
                < method.indexOf("quitSunshineIfRunning(host)"));
        assertFalse(method.contains("requestProviderStopAsync"));
        assertTrue(method.contains("stopped = quitSunshineIfRunning(host)"));
    }

    @Test public void retainedFallbackStopsProviderBeforeSunshine() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf(
                        "private void terminateRetainedSessionAndExit()"),
                source.indexOf("private String currentRetainedStreamSessionId()"));

        assertTrue(method.indexOf("stopActiveProviderGame(host, providerGameId")
                < method.indexOf("quitSunshineIfRunning(host)"));
        assertTrue(method.contains("failureMessage.set("
                + "R.string.console_provider_stop_waiting_for_process)"));
        assertTrue(method.contains("stopped = quitSunshineIfRunning(host)"));
        assertTrue(method.contains("complete.complete(success)"));
        assertFalse(method.contains("complete.run()"));
    }

    @Test public void freshZeroReconcilesOnlyExactLostRetainedSession() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf(
                        "private void reconcileAuthoritativeSession("),
                source.indexOf("private final ServiceConnection serviceConnection"));

        assertTrue(method.contains("AuthoritativeSessionTransition.lostRetainedTransport("));
        assertTrue(method.contains("clearIfMatches(\n                retained.streamSessionId)"));
        assertTrue(method.contains("pending.matches(retained.streamSessionId)"));
        assertTrue(method.contains("retained.streamSessionId.equals(\n"
                + "                    retainedStreamSessionId)"));
        assertTrue(method.contains("retained.hostId.equalsIgnoreCase(retainedStreamHostId)"));
        assertTrue(method.contains("retainedStreamSessionId = \"\""));
        assertTrue(method.contains("retainedStreamHostId = \"\""));
        assertTrue(method.contains("retainedStreamPlayniteGameId = \"\""));
        assertTrue(method.contains("finish();"));
        assertFalse(method.contains("finishAffinity"));
        assertFalse(method.contains("!pending.autoResume"));
    }

    @Test public void retainedFallbackRefreshesSunshineBeforeQuit() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf(
                        "private boolean quitSunshineIfRunning("),
                source.indexOf("private String knownProviderGameId("));

        assertTrue(method.indexOf("getComputerDetails(true)")
                < method.indexOf("connection.quitApp()"));
        assertTrue(method.contains("host.runningGameId, fresh.runningGameId"));
        assertTrue(method.contains("SunshineStopAction.COMPLETE"));
        assertTrue(method.contains("SunshineStopAction.QUIT"));
    }

    @Test public void replacingSessionStopsKnownProviderFirst() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf("private boolean closePreviousSession("),
                source.indexOf("private void showLoading(String hostName"));

        assertTrue(method.indexOf("stopActiveProviderGame")
                < method.indexOf("connection.quitApp()"));
        assertTrue(method.contains("if (host.runningGameId == 0) return true"));
        assertTrue(method.contains("requireProviderVerification"));
    }

    @Test public void destructiveReplacementUsesExactRetainedTerminationBeforeFallback()
            throws IOException {
        String source = consoleActivitySource();
        String effects = source.substring(source.indexOf(
                        "@Override public SessionOrchestrator.CloseResult closePreviousSession("),
                source.indexOf("@Override public void launch(", source.indexOf(
                        "@Override public SessionOrchestrator.CloseResult closePreviousSession(")));
        String helper = source.substring(source.indexOf(
                        "private Boolean terminateLiveRetainedForReplacement("),
                source.indexOf("private void beginInstallationSupportStream("));

        assertTrue(effects.indexOf("terminateLiveRetainedForReplacement(")
                < effects.indexOf("ConsoleActivity.this.closePreviousSession("));
        assertTrue(effects.contains("RetainedStreamSessionCoordinator.markTerminating("));
        assertTrue(effects.contains(".restoreReconnectIfTerminating("));
        assertTrue(helper.contains("RetainedStreamSessionCoordinator.terminate("));
        assertTrue(helper.contains("cancelled.getAsBoolean()"));
    }

    @Test public void sunshineAppMenusUseTheProviderAwareTerminationPath() throws IOException {
        String source = consoleActivitySource();
        String quickActions = source.substring(source.indexOf(
                        "private void showQuickLaunchItemActions("),
                source.indexOf("private void confirmRemoveQuickLaunchItem("));
        String appActions = source.substring(source.indexOf("private void showAppActions("),
                source.indexOf("private void addAppAction("));

        assertTrue(quickActions.contains("requestTerminateSession(host)"));
        assertFalse(quickActions.contains("ServerHelper.doQuit"));
        assertTrue(appActions.contains("requestTerminateSession(host)"));
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
    }

    @Test public void terminatingSessionDisablesHostAndGameSideActions()
            throws IOException {
        String source = consoleActivitySource();
        String host = source.substring(source.indexOf("private void showHostSelectionOptions("),
                source.indexOf("private TextView hostSelectionMenuAction("));
        String playnite = source.substring(source.indexOf("private void showPlayniteGameActions("),
                source.indexOf("private void confirmPlayniteUninstall("));
        String sunshine = source.substring(source.indexOf("private void showAppActions("),
                source.indexOf("private void addAppAction("));
        String quick = source.substring(source.indexOf("private void showQuickLaunchItemActions("),
                source.indexOf("private void confirmRemoveQuickLaunchItem("));

        assertTrue(host.contains("SessionSnapshot.State.TERMINATING"));
        assertTrue(host.contains("!terminating && ConsoleHostPresentation.canWake(host)"));
        assertTrue(playnite.contains("SessionSnapshot.State.TERMINATING"));
        assertTrue(sunshine.contains("SessionSnapshot.State.TERMINATING"));
        assertTrue(quick.contains("SessionSnapshot.State.TERMINATING"));
    }

    @Test public void carouselHistoryUsesExactGameIdentityInsteadOfSharedDesktopApp() throws IOException {
        String source = consoleActivitySource();
        String activity = source.substring(source.indexOf("private long playActivityEpoch("),
                source.indexOf("private static String playniteCarouselInstallActivityKey("));
        String launch = source.substring(source.indexOf("private void launchPreparedStream("),
                source.indexOf("private boolean handleRetainedStreamLaunch("));
        String retainedSwitch = source.substring(source.indexOf(
                        "private SessionOrchestrator.CloseResult switchRetainedProviderGame("),
                source.indexOf("private boolean closePreviousSession("));
        String completedInstall = source.substring(source.indexOf(
                        "private void completePlayniteInstallation("),
                source.indexOf("private void ensureVibepolloAfterInstall("));

        assertTrue(activity.contains(
                "playniteGameHistoryKey(hostUuid, item.stableId())"));
        assertFalse(activity.contains("appHistoryKey("));
        assertTrue(launch.contains("transition.type == LaunchTransitionType.GAME"));
        assertTrue(launch.contains("LaunchTransitionType.GAME_CONNECTION"));
        assertTrue(launch.contains("playniteGameHistoryKey("));
        int reused = retainedSwitch.indexOf(
                "result == RetainedStreamSessionCoordinator.SwitchOutcome.REUSED");
        int cancelled = retainedSwitch.indexOf(
                "result == RetainedStreamSessionCoordinator.SwitchOutcome.CANCELLED");
        assertTrue(reused >= 0 && cancelled > reused);
        assertTrue(retainedSwitch.substring(reused, cancelled).contains(
                "playniteGameHistoryKey("));
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

    @Test public void uncertainSessionUsesOpaqueRefreshWhileTargetAmbiguityKeepsPanel()
            throws IOException {
        String source = consoleActivitySource();
        String orchestration = source.substring(source.indexOf(
                        "private SessionOrchestrator createSessionOrchestrator()"),
                source.indexOf("private void beginInstallationSupportStream("));
        String activation = source.substring(source.indexOf(
                        "private void activatePlayniteItem("),
                source.indexOf("private void startPlayniteInstallation("));
        String resolver = source.substring(source.indexOf(
                        "private void resolveActivePlayniteGame("),
                source.indexOf("private void invalidateActivePlayniteGameRequest("));
        String actions = source.substring(source.indexOf("private void showPlayniteGameActions("),
                source.indexOf("private void confirmPlayniteUninstall("));

        assertTrue(orchestration.contains("@Override public void refreshSession("));
        assertTrue(orchestration.contains("streamLoadingView.setStep(1,"));
        assertTrue(orchestration.contains("resolveActivePlayniteGame(host, true, accepted ->"));
        assertTrue(orchestration.contains("console_session_uncertain_title"));
        assertTrue(orchestration.contains("console_session_uncertain_details"));
        assertTrue(resolver.contains("invalidateActivePlayniteGameRequest(host.uuid)"));
        assertTrue(resolver.contains("completion.accept(false)"));
        assertTrue(resolver.contains("completion.accept(true)"));
        assertTrue(activation.contains("showUncertainSessionMessage()"));
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

        assertTrue(confirm.contains("host.runningGameId == 0"));
        assertTrue(confirm.contains("SessionSnapshot.State.ACTIVE"));
        assertTrue(actions.contains("session.state == SessionSnapshot.State.ACTIVE"));
        assertTrue(actions.contains("session.playniteGameId"));
        assertFalse(actions.contains("resumePlayniteGameId)\n"
                + "                    && host.runningGameId != 0"));
    }

    @Test public void retainedSwitchSkipsWakeAndRequiresPreparedNeutralTarget()
            throws IOException {
        String source = consoleActivitySource();
        String preflight = source.substring(source.indexOf(
                        "private HostLaunchPreflight createHostLaunchPreflight()"),
                source.indexOf("private String preflightStageMessage("));
        String close = source.substring(source.indexOf(
                        "@Override public SessionOrchestrator.CloseResult closePreviousSession("),
                source.indexOf("@Override public void launch(", source.indexOf(
                        "@Override public SessionOrchestrator.CloseResult closePreviousSession(")));

        assertTrue(preflight.indexOf("Action.SWITCH_RETAINED")
                < preflight.indexOf("HostReadiness.await("));
        assertTrue(preflight.contains("State.HOME_LIVE"));
        assertTrue(preflight.contains("State.PREPARING"));
        assertTrue(close.contains("PlayniteTargetResolver.isNeutralStream(target)"));
        assertTrue(close.contains("retained.appId == target.getAppId()"));
        assertTrue(close.contains("switchRetainedProviderGame"));
    }

    @Test public void noPromptRetainedSwitchRequiresExactLiveNeutralSession()
            throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf(
                        "@Override public boolean canAttemptRetainedSwitch("),
                source.indexOf("@Override public void showLoading(", source.indexOf(
                        "@Override public boolean canAttemptRetainedSwitch(")));

        assertTrue(method.contains("PlayIntent.Kind.PLAYNITE_GAME"));
        assertTrue(method.contains("State.HOME_LIVE"));
        assertTrue(method.contains("State.PREPARING"));
        assertTrue(method.contains("if (retained.playniteGameId.isEmpty())"));
        assertTrue(method.indexOf("if (retained.playniteGameId.isEmpty())")
                < method.indexOf("activePlayniteGameResolvedAt.getOrDefault"));
        assertTrue(method.contains("host.runningGameId == retained.appId"));
        assertTrue(method.contains("retained.hostId.equalsIgnoreCase(intent.hostId)"));
        assertTrue(method.contains("!retained.playniteGameId.equalsIgnoreCase("));
        assertTrue(method.contains("PlayniteTargetResolver.findById("));
        assertTrue(method.contains("PlayniteTargetResolver.isNeutralStream(retainedTarget)"));
        assertTrue(method.contains("RetainedStreamSessionCoordinator.canSwitchGame("));
    }

    @Test public void retryKeepsIntentAndFreshObservationRestoresResumePresentation()
            throws IOException {
        String source = consoleActivitySource();
        String retry = source.substring(source.indexOf("@Override public void onRetry()"),
                source.indexOf("@Override public void onShowStreamAnyway()", source.indexOf(
                        "@Override public void onRetry()")));
        String resolve = source.substring(source.indexOf(
                        "private void resolveActivePlayniteGame("),
                source.indexOf("private void invalidateActivePlayniteGameRequest("));

        assertTrue(retry.contains("sessionOrchestrator.retry()"));
        assertFalse(retry.contains("sessionOrchestrator.cancel()"));
        assertFalse(retry.contains("showHome()"));
        assertTrue(resolve.indexOf("boolean freshnessRecovered")
                < resolve.indexOf("activePlayniteGameResolvedAt.put("));
        assertTrue(resolve.contains("freshnessRecovered\n"
                + "                        || !Objects.equals(previous"));
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
        String pause = source.substring(source.indexOf("protected void onPause()"),
                source.indexOf("public void onTrimMemory("));
        String back = source.substring(source.indexOf("public void onBackPressed()"),
                source.indexOf("private void loadKnownHosts("));
        String scopedCancel = source.substring(source.indexOf(
                        "private void cancelCurrentPreparation()"),
                source.indexOf("private void updateDiscordActionDescription("));
        String worker = source.substring(source.indexOf(
                        "@Override public SessionOrchestrator.PreparedWarmUp prepareHost("),
                source.indexOf("@Override public void launchPreparedHost("));
        String english = resourceSource("values/strings.xml");
        String polish = resourceSource("values-pl/strings.xml");

        assertTrue(cancel.contains("cancelCurrentPreparation()"));
        assertTrue(back.contains("cancelCurrentPreparation()"));
        assertTrue(pause.contains("cancelCurrentPreparation()"));
        assertFalse(pause.contains("showHome()"));
        assertTrue(scopedCancel.contains("cancelPreparation(warmUpClientHostId)"));
        assertTrue(scopedCancel.contains("renderHosts();"));
        assertTrue(scopedCancel.contains("updateHostSelector();"));
        assertTrue(scopedCancel.contains("else if (sessionOrchestrator != null)"));
        assertFalse(cancel.contains("cancelOwnedWarmUp"));
        assertTrue(hostCancel.contains(
                "RetainedStreamSessionCoordinator.cancelPreparing(preparing)"));
        assertTrue(hostCancel.contains("if (cancelled && relinquishForHostChange)"));
        assertTrue(preparation.contains("warmUpStatus = WARM_UP_ERROR"));
        assertTrue(preparation.indexOf("renderHosts();")
                > preparation.indexOf("warmUpStatus = WARM_UP_ERROR"));
        assertTrue(preparation.indexOf("updateHostSelector();")
                > preparation.indexOf("warmUpStatus = WARM_UP_ERROR"));
        assertTrue(failure.contains("streamLoadingView.showError("));
        assertTrue(failure.contains("console_warm_up_fallback_details"));
        assertFalse(failure.contains("console_host_timeout"));
        assertFalse(failure.contains("Toast"));
        assertTrue(status.contains("retained.hostId.equalsIgnoreCase(host.uuid)"));
        assertTrue(status.contains("sessionOrchestrator.hasPreparationForHost(host.uuid)"));
        assertTrue(status.indexOf("SessionSnapshot.State.TERMINATING")
                < status.indexOf("warmUpStatus == WARM_UP_ERROR"));
        assertTrue(status.indexOf("ConsoleHostPresentation.State.ACTIVE_SESSION")
                < status.indexOf("warmUpStatus == WARM_UP_ERROR"));
        assertTrue(status.contains("if (localWarmUp && preparingHost)"));
        assertFalse(status.contains("&& warmUpStatus == WARM_UP_PREPARING) {\n"
                + "            return getString(R.string.console_warm_up_preparing);"));
        int ready = worker.indexOf("if (ready == null || cancelled.getAsBoolean())");
        int preparing = worker.indexOf(
                "setWarmUpStatus(intent.hostId, request, WARM_UP_PREPARING);", ready);
        int preflight = worker.indexOf("hostLaunchPreflight.run(", ready);
        assertTrue(ready >= 0 && preparing > ready && preparing < preflight);
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
        assertFalse(arm.contains("showLoading("));
        assertFalse(arm.contains("dispatchPendingWarmUpRelay()"));
        assertTrue(accepted.indexOf("showLoading(")
                < accepted.indexOf("dispatchPendingWarmUpRelay()"));
        assertTrue(relay.contains("!warmUpHomeFrameAccepted"));
        int play = relay.indexOf("sessionOrchestrator.play(relay)");
        assertTrue(relay.indexOf("warmUpRelaySubmitted = true") < play);
        assertEquals(-1, relay.indexOf("clearPendingWarmUpRelay()", play));
        assertTrue(relay.contains("isPendingWarmUpRelayOwnedOrCompleted"));
        assertTrue(source.contains(
                "RetainedStreamSessionCoordinator.isPreparingSwitchOwned("));
    }

    @Test public void automaticHostSelectionStartsWarmUpOnce() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(
                source.indexOf("private void resolveInitialHostSelection()"),
                source.indexOf("private void showHostSelection("));

        assertTrue(method.indexOf("selectHost(automatic, false)")
                < method.indexOf("prepareSelectedHost(automatic)"));
        assertEquals(1, occurrences(method, "prepareSelectedHost(automatic)"));
        assertTrue(method.indexOf("initialHostSelectionResolved = true")
                < method.indexOf("prepareSelectedHost(automatic)"));
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
                streamHome.indexOf("private RetainedStreamSessionCoordinator.Snapshot",
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
        assertTrue(create.indexOf("acceptPreparingHomeFrame()")
                < create.indexOf("registerFrameCommitCallback"));
        assertTrue(create.contains("!hasResolvedInitialHostSelection()"));
        assertTrue(create.contains("registerFrameCommitCallback"));
        assertEquals(2, occurrences(create, "postOnAnimation("));
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
        assertTrue(prepare.contains("preferences.getInt(\"app_scroll.\" + host.uuid"));
        assertTrue(prepare.contains("target.requestFocus()"));
        assertTrue(prepare.contains("enterExpandedLibrary(true)"));
        assertTrue(prepare.contains("return false;"));
        assertTrue(settle.contains("currentPlayniteGames.isEmpty()"));
        assertTrue(settle.contains("renderApps(host, currentSunshineApps)"));
        assertFalse(settle.contains("requestPlayniteRefresh("));
        assertTrue(console.contains("suppressInitialCarouselMotion = true"));
        assertTrue(console.contains("suppressInitialCarouselMotion = false"));
        assertTrue(console.contains("if (suppressInitialCarouselMotion) return;"));

        // Recreation after an accepted frame reuses the retained operation and does not gate
        // or submit the transport/provider start a second time.
        String create = streamHome.substring(streamHome.indexOf(
                        "protected void onCreate(Bundle state)"),
                streamHome.indexOf("protected boolean useTransparentWarmUpStartingWindow()"));
        assertTrue(create.indexOf("if (preparingHomeFrameAccepted || preparingRelayOwned)")
                < create.indexOf("registerFrameCommitCallback"));
        assertTrue(create.substring(create.indexOf(
                "if (preparingHomeFrameAccepted || preparingRelayOwned)"),
                create.indexOf("registerFrameCommitCallback")).contains("return;"));
    }

    @Test public void ownedPreparingRelayRecreationDoesNotKeepTheFrameInputGate()
            throws IOException {
        assertTrue(StreamHomeActivity.shouldAwaitPreparingFrame(true, false, false));
        assertFalse(StreamHomeActivity.shouldAwaitPreparingFrame(true, true, false));
        assertFalse(StreamHomeActivity.shouldAwaitPreparingFrame(true, false, true));
        assertFalse(StreamHomeActivity.shouldAwaitPreparingFrame(false, false, false));

        String source = streamHomeActivitySource();
        String recreation = source.substring(source.indexOf(
                        "if (preparingHomeFrameAccepted || preparingRelayOwned)"),
                source.indexOf("if (preparingSnapshot == null)"));
        assertTrue(recreation.contains("preparingHomeFramePending = false"));
        assertTrue(recreation.indexOf("completeInitialCarouselFrame()")
                < recreation.indexOf("acceptPreparingHomeFrame()"));
        assertFalse(recreation.contains("preparingHomeFrameSubmitted("));
        assertFalse(recreation.contains("cancelPreparing("));

        String input = source.substring(source.indexOf(
                        "public boolean dispatchKeyEvent("),
                source.indexOf("public void onBackPressed()"));
        assertTrue(input.contains("if (!preparingHomeFramePending) return super.dispatchKeyEvent"));
        assertTrue(input.contains("preparingHomeFramePending || super.dispatchTouchEvent"));
        assertTrue(input.contains("preparingHomeFramePending || super.dispatchGenericMotionEvent"));
    }

    @Test public void staleRetainedHomeCannotCreateAnExitSessionPrompt() throws IOException {
        String source = consoleActivitySource();
        String back = source.substring(source.indexOf("public void onBackPressed()"),
                source.indexOf("private void loadKnownHosts("));
        String retainedPrompt = back.substring(back.indexOf(
                "RetainedStreamSessionCoordinator.hasRetainedSession()"),
                back.indexOf("showRetainedStreamExitConfirmation()"));

        assertFalse(retainedPrompt.contains("retainedStreamHome"));
        assertTrue(retainedPrompt.contains(
                "RetainedStreamSessionCoordinator.hasRetainedSession()"));
        assertTrue(retainedPrompt.contains("SessionResumeManager.hasPendingSession(this)"));
    }

    private static String consoleActivitySource() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/console/ConsoleActivity.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String streamHomeActivitySource() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/StreamHomeActivity.java");
        if (!Files.exists(source)) {
            source = Paths.get(
                    "app/src/main/java/com/limelight/console/StreamHomeActivity.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String resourceSource(String name) throws IOException {
        Path source = Paths.get("src/main/res", name);
        if (!Files.exists(source)) source = Paths.get("app/src/main/res", name);
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        for (int at = source.indexOf(value); at >= 0;
             at = source.indexOf(value, at + value.length())) count++;
        return count;
    }
}
