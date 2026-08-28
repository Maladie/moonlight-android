package com.limelight;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameTransitionCancelContractTest {
    @Test public void repeatedSnapshotsDoNotRecoverOverlayButReadinessRegressionDoes() {
        assertFalse(Game.shouldShowTransitionOverlay(true, false, true, false));
        assertFalse(Game.shouldShowTransitionOverlay(true, true, true, true));
        assertTrue(Game.shouldShowTransitionOverlay(false, false, true, false));
        assertTrue(Game.shouldShowTransitionOverlay(true, true, true, false));
    }

    @Test public void cancelDoesNotWaitForAnotherVideoFrame() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String method = text.substring(text.indexOf("private void cancelTransition()"),
                text.indexOf("private void retryTransition()"));

        assertFalse(method.contains("doAfterNextFrame"));
        assertTrue(method.contains("transitionCancelInFlight = true"));
        assertTrue(method.contains("transitionCoordinator.cancel()"));
        assertTrue(method.contains("SessionResumeManager.clearIfMatches"));
        assertTrue(method.indexOf("stopProviderGame") < method.indexOf("stopConnection"));
        assertTrue(method.contains("stopConnection(finishOnce)"));
    }

    @Test public void retainedSessionStopsProviderGameBeforeTransport() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String terminate = text.substring(text.indexOf(
                        "public void terminateRetainedSession("),
                text.indexOf("private interface ProviderStopCallback"));
        String providerTransition = text.substring(text.indexOf(
                        "private boolean hasProviderGameTransition()"),
                text.indexOf("private void stopProviderGame(ProviderStopCallback completion)"));
        String stopProvider = text.substring(text.indexOf(
                        "private void stopProviderGame(ProviderStopCallback completion)"),
                text.indexOf("private void quitRetainedApplication(Runnable completion)"));

        assertTrue(providerTransition.contains("LaunchTransitionType.GAME"));
        assertTrue(providerTransition.contains("LaunchTransitionType.GAME_CONNECTION"));
        assertTrue(providerTransition.contains("transitionSpec.playniteGameId"));
        assertTrue(terminate.indexOf("stopProviderGame")
                < terminate.indexOf("stopConnection"));
        assertTrue(terminate.contains("completion.complete(false)"));
        assertTrue(stopProvider.contains("gateway.stopGame(gameId)"));
    }

    @Test public void normalProviderQuitWaitsForConfirmedStop() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String method = text.substring(text.indexOf("private void closeStreamWithPrivacy("),
                text.indexOf("private void stopConnection(Runnable afterStopped)"));

        assertTrue(method.contains("hasProviderGameTransition()"));
        assertTrue(method.contains("stopProviderGame(success ->"));
        assertTrue(method.indexOf("stopProviderGame(success ->")
                < method.indexOf("stopConnection()"));
        assertTrue(method.contains("if (!success)"));
        assertTrue(method.contains("console_terminate_session_failed"));
    }

    @Test public void observedProviderExitClosesTheStreamWithoutPlayniteReturn() throws IOException {
        Path game = Paths.get("src/main/java/com/limelight/Game.java");
        Path coordinator = Paths.get(
                "src/main/java/com/limelight/console/ConsoleStreamTransitionCoordinator.java");
        if (!Files.exists(game)) game = Paths.get("app/src/main/java/com/limelight/Game.java");
        if (!Files.exists(coordinator)) coordinator = Paths.get(
                "app/src/main/java/com/limelight/console/ConsoleStreamTransitionCoordinator.java");
        String gameText = new String(Files.readAllBytes(game), StandardCharsets.UTF_8);
        String coordinatorText = new String(
                Files.readAllBytes(coordinator), StandardCharsets.UTF_8);

        assertTrue(gameText.contains("onProviderGameStopped()"));
        assertTrue(gameText.contains("providerStopConfirmed = true"));
        assertTrue(gameText.contains("closeStreamWithPrivacy(true)"));
        assertFalse(coordinatorText.contains(
                "\"game-stopped\".equals(name) || \"game-stopping\".equals(name)"));
    }

    @Test public void renderedFrameProofCanBeRearmedAfterTheFirstFrame() throws IOException {
        Path source = Paths.get(
                "src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java");
        if (!Files.exists(source)) source = Paths.get(
                "app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String listener = text.substring(text.indexOf("private void installFrameRenderedListener()"),
                text.indexOf("public void requestNextFrameRendered"));

        assertTrue(listener.contains("nextFrameRenderedCallback.getAndSet(null)"));
        assertFalse(listener.contains("setOnFrameRenderedListener(null"));
    }

    @Test public void connectingStateMakesInFlightTransportStoppable() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String start = text.substring(text.indexOf("private void startConnectionIfReady("),
                text.indexOf("public void surfaceCreated("));
        String stop = text.substring(text.indexOf("private void stopConnection(Runnable"),
                text.indexOf("private void doQuit()"));

        assertTrue(start.indexOf("connecting = true") < start.indexOf("conn.start("));
        assertTrue(stop.contains("if (connecting || connected)"));
        assertTrue(stop.contains("conn.stop()"));
    }

    @Test public void providerObservationIsArmedBeforeTransportCanReportConnected()
            throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String setup = text.substring(text.indexOf("consoleLoadingView.doAfterNextFrame(() ->"),
                text.indexOf("} else {", text.indexOf("consoleLoadingView.doAfterNextFrame(() ->")));

        assertTrue(setup.indexOf("transitionCoordinator.start()")
                < setup.indexOf("startConnectionIfReady("));
    }

    @Test public void consoleConnectionFailureUsesFriendlyStageInStatus() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String failed = text.substring(text.indexOf("public void stageFailed("),
                text.indexOf("public void connectionTerminated("));
        int transitionStart = failed.indexOf("if (transitionController != null)");
        String transitionError = failed.substring(transitionStart,
                failed.indexOf("} else {", transitionStart));

        assertTrue(transitionError.contains("consoleLoadingView.friendlyStage(stage)"));
        assertFalse(transitionError.contains("+ \" \" + stage"));
    }

    @Test public void missingInflatedStreamToastGetsAProgrammaticFallback() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int lookup = game.indexOf("discordDmToastView = findViewById(");
        int controller = game.indexOf("discordOverlayController =", lookup);
        String initialization = game.substring(lookup, controller);

        assertTrue(initialization.contains("if (discordDmToastView == null)"));
        assertTrue(initialization.contains("discordDmToastView = new DiscordDmToastView(this)"));
        assertTrue(initialization.contains("gameRoot.addView(discordDmToastView"));
        assertTrue(initialization.contains("registerHost(discordDmToastView)"));
    }

    @Test public void overlayHintWaitsUntilPrivacyRevealCompletes() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String game = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String connected = game.substring(game.indexOf("public void connectionStarted()"),
                game.indexOf("private ConsoleStreamTransitionCoordinator createTransitionCoordinator"));
        String reveal = game.substring(game.indexOf("private void revealTransitionNow()"),
                game.indexOf("private void cancelPendingAutomaticReveal()"));

        assertTrue(connected.contains("if (transitionController == null) showOverlayMenuHint()"));
        assertTrue(reveal.indexOf("transitionController.revealCompleted")
                < reveal.indexOf("showOverlayMenuHint()"));
    }
}
