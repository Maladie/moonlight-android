package com.limelight;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.limelight.SourceText.assertOrdered;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameTransportStopContractTest {
    @Test public void runtimeOwnsTheOneConnectionAndGameRoutesStopThroughIt()
            throws IOException {
        String game = source("src/main/java/com/limelight/Game.java");
        String runtime = source("src/main/java/com/limelight/stream/StreamRuntime.java");
        String android = source(
                "src/main/java/com/limelight/stream/AndroidStreamRuntime.java");
        String stop = between(game, "private void stopConnection(Runnable afterStopped)",
                "public void stageFailed(");

        // S07: the physical transport, its stop completion, listener and locks leave Game.
        assertTrue(runtime.contains("new TransportStopCoordinator(predecessor, this::stopNative,"));
        assertTrue(runtime.contains("transport.start(this)"));
        assertTrue(runtime.contains("TransportStopCoordinator.Result fenced = fence(result)"));
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
        assertTrue(game.contains("completion.transportUnsafe()"));
        assertTrue(stop.contains("StreamRuntime captured = runtime"));
        assertTrue(stop.contains("captured.stop("));
        assertTrue(stop.contains("TransportStopCoordinator.completeSafely("));
        assertFalse(stop.contains("else if (afterStopped != null)"));
        assertFalse(stop.contains("new Thread()"));
    }

    @Test public void retainedAndReplacementCompletionWaitForTheSharedStopResult()
            throws IOException {
        String game = source("src/main/java/com/limelight/Game.java");
        String runtime = source("src/main/java/com/limelight/stream/StreamRuntime.java");
        String disconnect = between(runtime, "@Override public void disconnectRetainedStream(",
                "@Override public void terminateRetainedSession(");
        String finish = between(game, "private void terminateWholeSessionVerified(",
                "private void applyImmediateTerminalPrivacy(");
        String console = source(
                "src/main/java/com/limelight/console/ConsoleActivity.java");
        String retainedClose = between(console,
                "private Boolean terminateLiveRetainedForReplacement(",
                "private void beginInstallationSupportStream(");

        assertOrdered(disconnect, "stop(result ->",
                "completion.complete(stopped)");
        assertOrdered(disconnect, "completion.transportUnsafe()",
                "completion.complete(stopped)");
        assertOrdered(finish, "stopConnectionResult(",
                "local.complete(stopped)");
        assertTrue(finish.contains("if (completion == null)"));
        assertOrdered(finish, "if (completion == null)",
                "DirectTerminationCompleted.completed(");
        assertOrdered(finish, "DirectTerminationCompleted.completed(",
                "completion.complete(success)");
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
                "src/main/java/com/limelight/session/SessionModel.java");
        String finish = between(coordinator,
                "void terminate(String expectedStreamSessionId",
                "boolean completeDirectTermination(");

        assertTrue(finish.contains("terminationOperation.completions.add(completion)"));
        assertTrue(finish.contains("reply.accept(TerminationResult.IN_PROGRESS)"));
        assertTrue(finish.contains("terminationOperation == operation"));
        assertTrue(finish.contains("complete(waiting, current && success)"));
    }

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end,
                source.indexOf(start)));
    }

    private static String source(String relative) throws IOException {
        Path path = Paths.get(relative);
        return SourceText.read(path);
    }
}
