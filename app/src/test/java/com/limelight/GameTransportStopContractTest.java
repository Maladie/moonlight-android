package com.limelight;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameTransportStopContractTest {
    @Test public void gameBindsOneOwnerToTheExactConnectionAndRoutesStopThroughIt()
            throws IOException {
        String game = source("app/src/main/java/com/limelight/Game.java");
        String setup = between(game, "conn = new NvConnection(",
                "keyboardTranslator = new KeyboardTranslator()");
        String stop = between(game, "private void stopConnection(Runnable afterStopped)",
                "public void stageFailed(");

        assertTrue(setup.contains("new TransportStopCoordinator(conn::stop"));
        assertTrue(game.contains("transportStop.start(this::runOnUiThread"));
        assertTrue(game.contains("@Override public void transportUnsafe()"));
        assertTrue(game.contains("completion.transportUnsafe()"));
        assertTrue(stop.contains("TransportStopCoordinator captured = transportStop"));
        assertTrue(stop.contains("private synchronized void stopConnectionResult("));
        assertTrue(stop.contains("captured.stop("));
        assertTrue(stop.contains("TransportStopCoordinator.completeSafely("));
        assertTrue(stop.contains("captured.fence(result)"));
        assertFalse(stop.contains("conn.stop()"));
        assertFalse(stop.contains("else if (afterStopped != null)"));
        assertFalse(stop.contains("new Thread()"));
    }

    @Test public void retainedAndReplacementCompletionWaitForTheSharedStopResult()
            throws IOException {
        String game = source("app/src/main/java/com/limelight/Game.java");
        String disconnect = between(game, "public void disconnectRetainedStream(",
                "public void terminateRetainedSession(");
        String finish = between(game, "private void terminateWholeSessionVerified(",
                "private void applyImmediateTerminalPrivacy(");
        String console = source(
                "app/src/main/java/com/limelight/console/ConsoleActivity.java");
        String retainedClose = between(console,
                "private Boolean terminateLiveRetainedForReplacement(",
                "private void beginInstallationSupportStream(");

        assertTrue(disconnect.indexOf("stopConnectionResult(")
                < disconnect.indexOf("completion.complete(stopped)"));
        assertTrue(disconnect.indexOf("completion.transportUnsafe()")
                < disconnect.indexOf("completion.complete(stopped)"));
        assertTrue(finish.indexOf("stopConnectionResult(")
                < finish.indexOf("local.complete(stopped)"));
        assertTrue(finish.contains("if (completion == null)"));
        assertTrue(finish.indexOf("if (completion == null)")
                < finish.indexOf("RetainedStreamSessionCoordinator.completeDirectTermination("));
        assertTrue(finish.indexOf("RetainedStreamSessionCoordinator.completeDirectTermination(")
                < finish.indexOf("completion.complete(success)"));
        assertTrue(finish.contains("wholeSessionTermination.run("));
        assertTrue(finish.contains("completion.transportUnsafe()"));
        assertFalse(finish.contains("RetainedStreamSessionCoordinator.snapshot()"
                + ".streamSessionId"));
        assertTrue(retainedClose.contains("TerminationResult.IN_PROGRESS"));
        assertTrue(retainedClose.contains("completed.await("));
    }

    @Test public void retainedCoordinatorJoinsCallbacksWithoutStartingAnotherEffect()
            throws IOException {
        String coordinator = source(
                "app/src/main/java/com/limelight/stream/RetainedStreamSessionCoordinator.java");
        String finish = between(coordinator,
                "private static TerminationResult finish(",
                "public static synchronized boolean clearIfMatches(");

        assertTrue(finish.contains("terminationOperation.completions.add(completion)"));
        assertTrue(finish.contains("return TerminationResult.IN_PROGRESS"));
        assertTrue(finish.contains("terminationOperation == capturedOperation"));
        assertTrue(finish.contains("boolean result = current && success"));
    }

    @Test public void transportRegistryDoesNotKeepAStaticStrongOwnerChain()
            throws IOException {
        String owner = source(
                "app/src/main/java/com/limelight/stream/TransportStopCoordinator.java");

        assertTrue(owner.contains("static WeakReference<TransportStopCoordinator> current"));
        assertTrue(owner.contains("private TransportStopCoordinator predecessor"));
        assertFalse(owner.contains("private static TransportStopCoordinator current"));
        assertFalse(owner.contains("private final TransportStopCoordinator predecessor"));
        assertTrue(owner.contains("predecessor = null"));
    }

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end,
                source.indexOf(start)));
    }

    private static String source(String relative) throws IOException {
        Path path = Paths.get(relative);
        if (!Files.exists(path) && relative.startsWith("app/")) {
            path = Paths.get(relative.substring(4));
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
