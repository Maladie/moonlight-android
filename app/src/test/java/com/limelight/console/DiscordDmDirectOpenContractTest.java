package com.limelight.console;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DiscordDmDirectOpenContractTest {
    @Test public void gameReusesExistingHoldAndFallsBackToVisibleRoot() throws IOException {
        String game = projectFile("src/main/java/com/limelight/Game.java",
                "app/src/main/java/com/limelight/Game.java");
        String handler = projectFile("src/main/java/com/limelight/binding/input/ControllerHandler.java",
                "app/src/main/java/com/limelight/binding/input/ControllerHandler.java");

        assertTrue(game.contains("runOnUiThread(Game.this::openDiscordDmShortcutOrOverlay)"));
        int showRoot = game.indexOf("showOverlayMenuWithBattery();",
                game.indexOf("private void openDiscordDmShortcutOrOverlay"));
        int directOpen = game.indexOf("discordOverlayController.openDirectMessage(peerId)", showRoot);
        assertTrue(showRoot >= 0 && directOpen > showRoot);
        assertTrue(handler.contains("pendingOverlayTriggerPressFlag"));
        assertTrue(handler.contains("if (selectDownTime > 0)"));
        assertTrue(handler.contains("context.pendingOverlayTriggerPressFlag = 0"));
    }

    @Test public void consoleConsumesSequenceBeforeAndAfterDialogOpens() throws IOException {
        String console = projectFile("src/main/java/com/limelight/console/ConsoleActivity.java",
                "app/src/main/java/com/limelight/console/ConsoleActivity.java");

        assertTrue(occurrences(console,
                "discordDmShortcut != null && discordDmShortcut.handle(event)") >= 2);
        assertTrue(console.contains("discordDmNotifications.consumeQuickAction(discordDmHostToken)"));
        assertTrue(console.contains("discordSocialPanelController.showDirectMessage(peerId)"));
        assertTrue(console.contains("else discordSocialPanelController.showHub()"));
    }

    @Test public void directOpenRequiresExactPeerScopeAndLease() throws IOException {
        String stream = projectFile(
                "src/main/java/com/limelight/console/DiscordOverlayController.java",
                "app/src/main/java/com/limelight/console/DiscordOverlayController.java");
        String console = projectFile(
                "src/main/java/com/limelight/console/DiscordSocialPanelController.java",
                "app/src/main/java/com/limelight/console/DiscordSocialPanelController.java");

        assertTrue(stream.contains("findFriend(friendId) != null"));
        assertTrue(stream.contains("DiscordSocialClient.tryAcquireMessageEventLease()"));
        assertTrue(stream.contains("DiscordDmShortcutHandler.canDirectOpen"));
        assertTrue(console.contains("DiscordSocialClient.Friend friend = directFriend"));
        assertTrue(console.contains("hasMessageEventLease()"));
        assertTrue(console.contains("showHub();"));
    }

    @Test public void hostsPublishLifecycleAndExactVisiblePeer() throws IOException {
        String game = projectFile("src/main/java/com/limelight/Game.java",
                "app/src/main/java/com/limelight/Game.java");
        String console = projectFile("src/main/java/com/limelight/console/ConsoleActivity.java",
                "app/src/main/java/com/limelight/console/ConsoleActivity.java");

        assertTrue(game.contains("discordOverlayController.setVisiblePeerListener"));
        assertTrue(game.contains("discordDmNotifications.activateHost"));
        assertTrue(game.contains("discordDmNotifications.deactivateHost"));
        assertTrue(game.contains("setPictureInPicture"));
        assertTrue(game.contains("setPresentationBlocked"));
        assertTrue(console.contains("visiblePeerChanged(long peerId)"));
        assertTrue(console.contains("setScreenSaverActive"));
        assertTrue(console.contains("setWindowFocused"));
    }

    @Test public void consoleDirectOpenNeverInvokesSystemKeyboard() throws IOException {
        String community = projectFile(
                "src/main/java/com/limelight/console/DiscordCommunityView.java",
                "app/src/main/java/com/limelight/console/DiscordCommunityView.java");

        assertTrue(community.contains("directComposer.setShowSoftInputOnFocus(false)"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0;
             index += needle.length()) count++;
        return count;
    }

    private static String projectFile(String first, String second) throws IOException {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(second);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
