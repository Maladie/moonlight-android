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

        assertTrue(method.indexOf("stopActiveProviderGame(host, providerGameId)")
                < method.indexOf("connection.quitApp()"));
    }

    @Test public void retainedFallbackRequiresBothProviderAndSunshineStop() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf(
                        "private void terminateRetainedSessionAndExit()"),
                source.indexOf("private String currentRetainedStreamSessionId()"));

        assertTrue(method.contains("stopActiveProviderGame(host, providerGameId)"));
        assertTrue(method.contains("stopped = connection.quitApp()"));
        assertTrue(method.contains("complete.complete(success)"));
        assertFalse(method.contains("complete.run()"));
    }

    @Test public void replacingSessionStopsKnownProviderFirst() throws IOException {
        String source = consoleActivitySource();
        String method = source.substring(source.indexOf("private boolean closePreviousSession("),
                source.indexOf("private void showLoading(String hostName"));

        assertTrue(method.indexOf("stopActiveProviderGame")
                < method.indexOf("connection.quitApp()"));
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

    @Test public void carouselHistoryUsesExactGameIdentityInsteadOfSharedDesktopApp() throws IOException {
        String source = consoleActivitySource();
        String activity = source.substring(source.indexOf("private long playActivityEpoch("),
                source.indexOf("private static String playniteCarouselInstallActivityKey("));
        String launch = source.substring(source.indexOf("private void launchPreparedStream("),
                source.indexOf("private boolean handleRetainedStreamLaunch("));
        String completedInstall = source.substring(source.indexOf(
                        "private void completePlayniteInstallation("),
                source.indexOf("private void ensureVibepolloAfterInstall("));

        assertTrue(activity.contains(
                "playniteGameHistoryKey(hostUuid, item.stableId())"));
        assertFalse(activity.contains("appHistoryKey("));
        assertTrue(launch.contains("transition.type == LaunchTransitionType.GAME"));
        assertTrue(launch.contains("LaunchTransitionType.GAME_CONNECTION"));
        assertTrue(launch.contains("playniteGameHistoryKey("));
        assertTrue(completedInstall.contains("playniteCarouselInstallActivityKey("));
    }

    private static String consoleActivitySource() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/console/ConsoleActivity.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
