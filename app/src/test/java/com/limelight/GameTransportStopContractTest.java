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
    @Test public void runtimeOwnsTheOneConnectionAndGameRoutesStopThroughIt()
            throws IOException {
        String game = source("app/src/main/java/com/limelight/Game.java");
        String runtime = source("app/src/main/java/com/limelight/stream/StreamRuntime.java");
        String android = source(
                "app/src/main/java/com/limelight/stream/AndroidStreamRuntime.java");
        String stop = between(game, "private void stopConnection(Runnable afterStopped)",
                "public void stageFailed(");

        // S07: the physical transport, its stop completion, listener and locks leave Game.
        assertTrue(runtime.contains("new TransportStopCoordinator(this::stopNative,"));
        assertTrue(runtime.contains("transport.start(this)"));
        assertTrue(runtime.contains("stopCoordinator.fence("));
        assertTrue(android.contains("connection.start(audio, video, listener)"));
        assertTrue(android.contains("WIFI_MODE_FULL_HIGH_PERF"));
        assertFalse(game.contains("private NvConnection conn"));
        assertFalse(game.contains("TransportStopCoordinator transportStop"));
        assertFalse(game.contains("WifiLock"));
        assertFalse(game.contains("conn.stop()"));
        assertFalse(game.contains("conn.start("));
        assertFalse(game.contains("new TransportStopCoordinator("));
        assertFalse(game.contains("SessionModel.Controller"));
        assertTrue(game.contains("runtime.start();"));
        assertTrue(game.contains("@Override public void transportUnsafe()"));
        assertTrue(game.contains("completion.transportUnsafe()"));
        assertTrue(stop.contains("StreamRuntime captured = runtime"));
        assertTrue(stop.contains("captured.stop("));
        assertTrue(stop.contains("TransportStopCoordinator.completeSafely("));
        assertFalse(stop.contains("else if (afterStopped != null)"));
        assertFalse(stop.contains("new Thread()"));
    }

    @Test public void retainedAndReplacementCompletionWaitForTheSharedStopResult()
            throws IOException {
        String game = source("app/src/main/java/com/limelight/Game.java");
        String runtime = source("app/src/main/java/com/limelight/stream/StreamRuntime.java");
        String disconnect = between(runtime, "@Override public void disconnectRetainedStream(",
                "@Override public void terminateRetainedSession(");
        String finish = between(game, "private void terminateWholeSessionVerified(",
                "private void applyImmediateTerminalPrivacy(");
        String console = source(
                "app/src/main/java/com/limelight/console/ConsoleActivity.java");
        String retainedClose = between(console,
                "private Boolean terminateLiveRetainedForReplacement(",
                "private void beginInstallationSupportStream(");

        assertTrue(disconnect.indexOf("stop(result ->")
                < disconnect.indexOf("completion.complete(stopped)"));
        assertTrue(disconnect.indexOf("completion.transportUnsafe()")
                < disconnect.indexOf("completion.complete(stopped)"));
        assertTrue(finish.indexOf("stopConnectionResult(")
                < finish.indexOf("local.complete(stopped)"));
        assertTrue(finish.contains("if (completion == null)"));
        assertTrue(finish.indexOf("if (completion == null)")
                < finish.indexOf("DirectTerminationCompleted.completed("));
        assertTrue(finish.indexOf("DirectTerminationCompleted.completed(")
                < finish.indexOf("completion.complete(success)"));
        assertTrue(finish.contains("wholeSessionTermination.run("));
        assertTrue(finish.contains("completion.transportUnsafe()"));
        assertFalse(finish.contains("sessionModel.snapshot()"
                + ".streamSessionId"));
        assertTrue(retainedClose.contains("TerminationResult.IN_PROGRESS"));
        assertTrue(retainedClose.contains("completed.await("));
    }

    @Test public void retainedCoordinatorJoinsCallbacksWithoutStartingAnotherEffect()
            throws IOException {
        String coordinator = source(
                "app/src/main/java/com/limelight/session/SessionModel.java");
        String finish = between(coordinator,
                "void terminate(String expectedStreamSessionId",
                "boolean completeDirectTermination(");

        assertTrue(finish.contains("terminationOperation.completions.add(completion)"));
        assertTrue(finish.contains("reply.accept(TerminationResult.IN_PROGRESS)"));
        assertTrue(finish.contains("terminationOperation == operation"));
        assertTrue(finish.contains("complete(waiting, current && success)"));
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
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
