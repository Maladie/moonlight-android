package com.limelight.console;

import com.limelight.SourceText;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** S06 migration contract: current-game inventory is no longer an Activity truth source. */
public class RunningGameInventoryTest {
    @Test public void exactStopUsesOneFreshVerifiedObservation() throws Exception {
        String source = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String stop = source.substring(source.indexOf("private void stopActiveProviderGame("),
                source.indexOf("private void finishHostWaking("));
        assertTrue(stop.contains("PlayniteTransitionGateway.Snapshot current"));
        assertTrue(stop.contains("hasVerifiedProcessFor(gameId)"));
        assertTrue(stop.contains("hostGatewayClient.stopGame(gateway, gameId, current.processToken)"));
        assertFalse(stop.contains("getPlayniteCurrentGame("));
    }

    private static String source(String modulePath) throws Exception {
        Path path = Paths.get(modulePath);
        return SourceText.read(path);
    }
}
