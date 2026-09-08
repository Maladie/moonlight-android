package com.limelight;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameChildDiscordContractTest {
    @Test public void childStreamDisablesInheritedDiscordSurfaceAndCalls() throws IOException {
        String game = readProjectFile("src/main/java/com/limelight/Game.java",
                "app/src/main/java/com/limelight/Game.java");
        String setup = game.substring(game.indexOf("private void configureActorPresentation()"),
                game.indexOf("private void setupOverlayMenu()"));
        assertTrue(setup.contains("boolean discordEnabled = !isChildSession();"));
        assertTrue(setup.contains("overlayMenuView.setDiscordFeatureEnabled(discordEnabled);"));
        int discordBranchStart = setup.indexOf("if (!discordEnabled)");
        int adultBranchStart = setup.indexOf("} else {", discordBranchStart);
        assertTrue(discordBranchStart >= 0);
        assertTrue(adultBranchStart > discordBranchStart);
        String childBranch = setup.substring(discordBranchStart, adultBranchStart);
        String adultBranch = setup.substring(adultBranchStart);
        assertTrue(childBranch.contains("discordDmToastView.setVisibility(View.GONE)"));
        assertTrue(childBranch.contains("discordDockView.setVisibility(View.GONE)"));
        assertFalse(childBranch.contains("DiscordDmNotificationCoordinator"));
        assertFalse(childBranch.contains("DiscordSocialClient.attach"));
        assertFalse(childBranch.contains("new DiscordOverlayController"));
        assertTrue(adultBranch.contains("DiscordDmNotificationCoordinator.getInstance()"));
        assertTrue(adultBranch.contains("DiscordSocialClient.attach(this)"));
        assertTrue(adultBranch.contains("new DiscordOverlayController"));

        String overlay = readProjectFile(
                "src/main/java/com/limelight/ui/overlay/OverlayMenuView.java",
                "app/src/main/java/com/limelight/ui/overlay/OverlayMenuView.java");
        assertTrue(overlay.contains("private boolean discordFeatureEnabled = true;"));
        assertTrue(overlay.contains("return discordFeatureEnabled && shouldShowDiscordCard"));
        assertTrue(overlay.contains("discordContainer.setVisibility(community && canShowDiscordCard()"));
    }

    private static String readProjectFile(String modulePath, String rootPath) throws IOException {
        Path path = Paths.get(modulePath);
        if (!Files.exists(path)) path = Paths.get(rootPath);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
