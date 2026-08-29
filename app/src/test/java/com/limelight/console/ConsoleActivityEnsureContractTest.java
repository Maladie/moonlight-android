package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    private static String consoleActivitySource() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/console/ConsoleActivity.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
