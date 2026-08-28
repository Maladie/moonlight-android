package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DiscordDmToastViewContractTest {
    @Test public void defaultPlacementUsesTopEndAndMaximumWidth() {
        DiscordDmToastView.Placement placement = DiscordDmToastView.choosePlacement(
                1920, 1080, 0, 0, false, 18, 12, 420, 220, 88);

        assertEquals(420, placement.width);
        assertEquals(18, placement.rightMargin);
        assertEquals(18, placement.topMargin);
    }

    @Test public void gamePlacementStaysLeftOfVisibleDockWhenThereIsRoom() {
        DiscordDmToastView.Placement placement = DiscordDmToastView.choosePlacement(
                1920, 1080, 1612, 500, true, 18, 12, 420, 220, 88);

        assertEquals(420, placement.width);
        assertEquals(320, placement.rightMargin);
        assertEquals(1600, 1920 - placement.rightMargin);
    }

    @Test public void gamePlacementMovesBelowDockWhenLeftSideIsTooNarrow() {
        DiscordDmToastView.Placement placement = DiscordDmToastView.choosePlacement(
                800, 1080, 200, 400, true, 18, 12, 420, 220, 88);

        assertEquals(420, placement.width);
        assertEquals(18, placement.rightMargin);
        assertEquals(412, placement.topMargin);
    }

    @Test public void gamePlacementUsesLargestRemainingLeftStripWhenNeitherRegionFits() {
        DiscordDmToastView.Placement placement = DiscordDmToastView.choosePlacement(
                800, 1080, 200, 1000, true, 18, 12, 420, 220, 88);

        assertEquals(170, placement.width);
        assertEquals(612, placement.rightMargin);
        assertEquals(18, placement.topMargin);
    }

    @Test public void viewIsPassiveAnimatedAndAnnouncedOnceByContract() throws IOException {
        String source = projectFile("src/main/java/com/limelight/console/DiscordDmToastView.java",
                "app/src/main/java/com/limelight/console/DiscordDmToastView.java");

        assertTrue(source.contains("implements DiscordDmNotificationCoordinator.Host"));
        assertTrue(source.contains("FOCUS_BLOCK_DESCENDANTS"));
        assertTrue(source.contains("view.setFocusable(false)"));
        assertTrue(source.contains("view.setFocusableInTouchMode(false)"));
        assertTrue(source.contains("@Override public boolean onTouchEvent"));
        assertTrue(source.contains("return true;"));
        assertTrue(source.contains("ENTER_MS = 180L"));
        assertTrue(source.contains("EXIT_MS = 150L"));
        assertTrue(source.contains("if (entering && !reducedMotion)"));
        assertTrue(source.contains("DiscordCommunityPresentation.avatar"));
        assertTrue(source.contains("hideDiscordDmToastImmediately"));
        assertTrue(source.contains("model != null && model.actionable"));
        assertTrue(source.contains("addOnLayoutChangeListener"));
        assertTrue(source.contains("removeOnLayoutChangeListener"));
        assertEquals(1, occurrences(source, "announceForAccessibility("));
    }

    @Test public void bothRootsContainTheSameViewWithoutCoveringTheGameOverlay() throws IOException {
        String game = projectFile("src/main/res/layout/activity_game.xml",
                "app/src/main/res/layout/activity_game.xml");
        String console = projectFile("src/main/java/com/limelight/console/ConsoleActivity.java",
                "app/src/main/java/com/limelight/console/ConsoleActivity.java");

        int dock = game.indexOf("@+id/discordDockView");
        int toast = game.indexOf("@+id/discordDmToastView");
        int overlay = game.indexOf("@+id/overlayMenuView");
        assertTrue(dock >= 0 && dock < toast);
        assertTrue(toast < overlay);
        assertTrue(console.contains("discordDmToastView = new DiscordDmToastView(this)"));
        assertTrue(console.contains("container.addView(discordDmToastView, toastParams)"));
    }

    @Test public void localizedCopyContainsNoTechnicalIdentifiers() throws IOException {
        String english = projectFile("src/main/res/values/discord_dm_notification_strings.xml",
                "app/src/main/res/values/discord_dm_notification_strings.xml");
        String polish = projectFile("src/main/res/values-pl/discord_dm_notification_strings.xml",
                "app/src/main/res/values-pl/discord_dm_notification_strings.xml");

        assertTrue(english.contains("%1$s sends a message"));
        assertTrue(english.contains("new message or attachment"));
        assertTrue(polish.contains("%1$s przesyła wiadomość"));
        assertTrue(polish.contains("nowa wiadomość lub załącznik"));
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
