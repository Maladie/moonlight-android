package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayniteTransitionGatewayTest {
    @Test public void childObservationUsesScopedReadsWithoutParentHealth() throws IOException {
        Path root = Paths.get("src/main/java/com/limelight");
        if (!Files.exists(root)) root = Paths.get("app/src/main/java/com/limelight");
        String text = new String(Files.readAllBytes(root.resolve(
                "console/PlayniteTransitionGateway.java")), StandardCharsets.UTF_8);
        String snapshot = text.substring(text.indexOf("public Snapshot snapshot()"),
                text.indexOf("public void startGame("));
        assertTrue(snapshot.contains("childSession ? null"));
        assertTrue(snapshot.contains(": client.getPlayniteHealth(connection)"));
        assertTrue(snapshot.contains("client.getPlayniteCurrentGame(connection)"));
        assertTrue(snapshot.contains("client.getPlayniteReadiness(connection)"));
        assertTrue(snapshot.contains("readiness.ready"));
        assertFalse(snapshot.contains("DEFAULT_PROFILE_ID"));
        String game = new String(Files.readAllBytes(root.resolve("Game.java")),
                StandardCharsets.UTF_8);
        String coordinator = game.substring(game.indexOf(
                "LaunchTransitionSpec spec) {"), game.indexOf("private void completeInstallationFailure"));
        assertTrue(coordinator.contains("spec.profileId,"));
        assertTrue(coordinator.contains("isChildSession());"));
    }

    @Test public void missingConnectionIsDiagnosedWithoutAddressOrCredentials()
            throws IOException {
        Path source = Paths.get(
                "src/main/java/com/limelight/console/PlayniteTransitionGateway.java");
        if (!Files.exists(source)) source = Paths.get(
                "app/src/main/java/com/limelight/console/PlayniteTransitionGateway.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String connect = text.substring(text.indexOf(
                        "public static PlayniteTransitionGateway connect("),
                text.indexOf("public Snapshot snapshot()"));

        assertTrue(connect.contains("gateway.connect_failed"));
        assertTrue(connect.contains("connection_missing"));
        assertTrue(connect.contains("host_id"));
        assertFalse(connect.contains("\"active_host\""));
        assertFalse(connect.contains("authorization"));
        assertFalse(connect.contains("token"));
    }

    @Test public void suspendAcceptanceRequiresEchoedIdAndExactTarget() {
        PlayniteTransitionGateway.SuspendAcceptance acceptance =
                new PlayniteTransitionGateway.SuspendAcceptance(
                        true, "suspend-a", 42, "game");

        assertTrue(acceptance.matches("suspend-a", 42, "GAME"));
        assertFalse(acceptance.matches("suspend-b", 42, "game"));
        assertFalse(acceptance.matches("suspend-a", 7, "game"));
        assertFalse(acceptance.matches("suspend-a", 42, "other"));
    }

    @Test public void rejectedSuspendNeverMatches() {
        PlayniteTransitionGateway.SuspendAcceptance acceptance =
                new PlayniteTransitionGateway.SuspendAcceptance(
                        false, "suspend-a", 42, "game");

        assertFalse(acceptance.matches("suspend-a", 42, "game"));
    }

    @Test public void acceptedLegacySuspendWithoutCorrelationMatches() {
        PlayniteTransitionGateway.SuspendAcceptance acceptance =
                new PlayniteTransitionGateway.SuspendAcceptance(true, "", 0, "");

        assertTrue(acceptance.matches("suspend-a", 42, "game"));
    }

    @Test public void partialCorrelationIsNotTreatedAsLegacy() {
        PlayniteTransitionGateway.SuspendAcceptance acceptance =
                new PlayniteTransitionGateway.SuspendAcceptance(true, "suspend-a", 0, "");

        assertFalse(acceptance.matches("suspend-a", 42, "game"));
    }
}
