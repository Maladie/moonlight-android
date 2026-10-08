package com.limelight.console;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** S06 migration contract: current-game inventory is no longer an Activity truth source. */
public class RunningGameInventoryTest {
    @Test public void activityUsesOnlyThePublishedSessionProjection() throws Exception {
        String source = source("src/main/java/com/limelight/console/ConsoleActivity.java",
                "app/src/main/java/com/limelight/console/ConsoleActivity.java");
        // The projection owner publishes; the Activity keeps no copy of it.
        assertFalse(source.contains("publishedSessionSnapshots"));
        assertTrue(source.contains("gateway.snapshot().sessionSnapshot"));
        assertFalse(source.contains("getPlayniteCurrentGame("));
        assertFalse(source.contains("RunningGameObservation"));
        assertFalse(source.contains("activePlayniteGame"));
        assertFalse(source.contains("activePlayniteGameIds"));
        assertFalse(source.contains("activePlayniteGameStates"));
        assertFalse(source.contains("activePlayniteGameAppIds"));
        assertFalse(source.contains("activePlayniteGameResolvedAt"));
    }

    @Test public void exactStopUsesOneFreshVerifiedObservation() throws Exception {
        String source = source("src/main/java/com/limelight/console/ConsoleActivity.java",
                "app/src/main/java/com/limelight/console/ConsoleActivity.java");
        String stop = source.substring(source.indexOf("private void stopActiveProviderGame("),
                source.indexOf("private void finishHostWaking("));
        assertTrue(stop.contains("PlayniteTransitionGateway.Snapshot current"));
        assertTrue(stop.contains("hasVerifiedProcessFor(gameId)"));
        assertTrue(stop.contains("hostGatewayClient.stopGame(gateway, gameId, current.processToken)"));
        assertFalse(stop.contains("getPlayniteCurrentGame("));
    }

    private static String source(String modulePath, String repositoryPath) throws Exception {
        Path path = Paths.get(modulePath);
        if (!Files.exists(path)) path = Paths.get(repositoryPath);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
