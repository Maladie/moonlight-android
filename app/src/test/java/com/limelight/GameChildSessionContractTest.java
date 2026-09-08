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

public class GameChildSessionContractTest {
    @Test public void childDashboardUsesTheSameLiveTransportAsParent() throws IOException {
        String text = source();
        for (String[] boundaries : new String[][] {
                {"public void onBackPressed()", "public boolean handleKeyDown("},
                {"private void openConsoleHome()", "private void launchConsoleHome("},
                {"private boolean canParkBackgroundStream()", "public boolean parkPreparingTransport("},
                {"private void restoreParkedStream(", "private void scheduleRetainedRestoreWatchdog()"},
                {"private boolean hasOwnRetainedSession()", "private boolean hasAutoWarmUpTransportIdentity()"}}) {
            String method = text.substring(text.indexOf(boundaries[0]), text.indexOf(boundaries[1]));
            assertFalse(boundaries[0], method.contains("isChildSession()"));
            assertFalse(boundaries[0], method.contains("finishChildSession"));
        }
        String home = text.substring(text.indexOf("private void openConsoleHome()"),
                text.indexOf("private void openPreparingConsoleHome()"));
        assertTrue(home.contains("RetainedStreamSessionCoordinator.enterHome"));
        assertTrue(home.contains("currentProfileId()"));
        String destroy = text.substring(text.indexOf("protected void onDestroy()"),
                text.indexOf("protected void onPause()"));
        assertTrue(destroy.contains("stopChildMonitoring()"));
        assertFalse(destroy.contains("closeChildSessionForDestroy"));
    }

    @Test public void deadlineRemainingUsesCeilingAndExpiresAtZero() {
        assertEquals(62L, Game.childRemainingSeconds(61_001L, 0L));
        assertEquals(60L, Game.childRemainingSeconds(60_000L, 0L));
        assertEquals(0L, Game.childRemainingSeconds(10L, 10L));
        assertEquals(0L, Game.childRemainingSeconds(9L, 10L));
    }

    @Test public void callbackCorrelationRequiresEveryChildIdentity() {
        assertTrue(Game.childCorrelationMatches(
                "host-a", "child-a", "session-a",
                "playnite:123e4567-e89b-12d3-a456-426614174000", "transition-a",
                "host-a", "child-a", "session-a",
                "123e4567-e89b-12d3-a456-426614174000", "transition-a"));
        assertFalse(Game.childCorrelationMatches(
                "host-a", "child-a", "session-a",
                "playnite:123e4567-e89b-12d3-a456-426614174000", "transition-a",
                "host-a", "child-b", "session-a",
                "123e4567-e89b-12d3-a456-426614174000", "transition-a"));
        assertFalse(Game.childCorrelationMatches(
                "host-a", "child-a", "session-a",
                "playnite:123e4567-e89b-12d3-a456-426614174000", "transition-a",
                "host-a", "child-a", "session-a",
                "123e4567-e89b-12d3-a456-426614174000", "transition-b"));
    }

    @Test public void streamLifecycleHasNoChildRetryOrCloseStateMachine() throws IOException {
        String text = source();
        assertFalse(text.contains("retryChildTransition"));
        assertFalse(text.contains("childStreamDetachReason"));
        assertFalse(text.contains("beginChildSessionClose"));
        assertFalse(text.contains("childSessionClosed"));
        String menu = text.substring(text.indexOf("private void showOverlayMenuWithBattery()"),
                text.indexOf("private void openDiscordDmShortcutOrOverlay()"));
        assertFalse(menu.contains("stopConnection"));
        assertFalse(menu.contains("finishChildSession"));
        String failure = text.substring(text.indexOf("public void stageFailed("),
                text.indexOf("public void connectionTerminated("));
        assertFalse(failure.contains("isChildSession"));
    }

    @Test public void stoppedGameDoesNotStartAnEmptyTargetObserverOrRevealDesktop() throws IOException {
        String text = source();
        String settle = text.substring(text.indexOf("private void settleNeutralRetainedStream(String hostId"),
                text.indexOf("private void retainNeutralStreamAfterNaturalGameStop("));
        assertTrue(settle.contains("transitionController.returningToDashboard(neutral.id)"));
        assertFalse(settle.contains("transitionCoordinator.start()"));
        assertFalse(settle.contains("stopConnection("));
    }

    private static String source() throws IOException {
        Path path = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(path)) {
            path = Paths.get("app/src/main/java/com/limelight/Game.java");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
