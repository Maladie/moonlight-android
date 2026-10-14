package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Where the Gateway's stream record of this TV is read, and what follows from it. */
public class HostStreamRecordContractTest {
    private static String source(String file) throws IOException {
        Path path = Paths.get("src/main/java/com/limelight/" + file);
        if (!Files.exists(path)) path = Paths.get("app").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue(start, from >= 0);
        int to = source.indexOf(end, from + start.length());
        assertTrue(end, to > from);
        return source.substring(from, to);
    }

    @Test public void onlyAHostEndedStreamAsksWhetherTheSessionWasClosedThere()
            throws IOException {
        String game = source("Game.java");
        String terminated = section(game, "public void connectionTerminated(",
                "if (hasOwnRetainedSession()) {");
        // A lost network never terminates gracefully: it keeps its reconnect record.
        assertTrue(terminated.contains("errorCode == MoonBridge.ML_ERROR_GRACEFUL_TERMINATION"
                + " && !userInitiatedDisconnect"));
        // Only a stream the user saw is announced, never this client's own cleanup.
        assertTrue(terminated.contains("confirmClosedOnHost(streamEverRevealed\n"
                + "                    && !freshOwnedFailureCleanupInFlight.get());"));
        assertTrue(terminated.indexOf("confirmClosedOnHost(")
                < terminated.indexOf("cleanupUnrevealedProviderLaunch("));
        String confirm = section(game, "private void confirmClosedOnHost(boolean announce)",
                "private String currentProfileId()");
        // The first read waits as well, so the termination path's own saveReconnect lands first.
        assertTrue(confirm.indexOf("Thread.sleep(2_000L)") >= 0 && confirm.indexOf(
                "Thread.sleep(2_000L)") < confirm.indexOf("control.ownedStream(gateway)"));
        assertTrue(confirm.contains("HostStreamRecord.Action.RETIRE"));
        assertTrue(confirm.contains("SessionEvent.Cleared.byHost(sessionId)"));
        assertTrue(confirm.contains("new SessionEvent.ReconnectCleared(sessionId)"));
        assertTrue(confirm.contains("\"host_close.confirmed\""));
        assertTrue(confirm.indexOf("if (announce)") >= 0 && confirm.indexOf("if (announce)")
                < confirm.indexOf("R.string.session_closed_on_host"));
    }

    @Test public void consoleReadsTheRecordOncePerHostEntryAndNeverInItsPollLoop()
            throws IOException {
        String console = source("console/ConsoleActivity.java");
        assertTrue(section(console, "protected void onPause()", "childEligibilityLaunchToken++;")
                .contains("hostStreamReconciled.clear();"));
        String reconcile = section(console, "private void reconcileHostStreamRecord(",
                "private String ownReconnectRecord(");
        assertTrue(reconcile.contains("!hostStreamReconciled.add(key.cacheKey())"));
        assertTrue(reconcile.contains(".ownedStream(gateway)"));
        // The app list cache adoption material needs is read on the worker, never on the UI
        // or model thread; the decision itself is the session model's (SessionModelHostTest).
        assertTrue(reconcile.contains("? loadApps(host, true)"));
        assertFalse(section(console, "private Intent adoptedStreamRecord(",
                "private long knownControlGeneration(").contains("loadApps("));
        assertTrue(section(console, "private Intent adoptedStreamRecord(",
                "private long knownControlGeneration(")
                .contains(".putExtra(Game.EXTRA_CONTROL_GENERATION, owned.generation)"));
        // The stream screen that watched the stream end is the one that tells the user.
        assertFalse(console.contains("session_closed_on_host"));
    }
}