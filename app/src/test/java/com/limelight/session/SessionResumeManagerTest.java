package com.limelight.session;

import com.limelight.SourceText;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionResumeManagerTest {
    private static String source() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/session/SessionResumeManager.java");
        return SourceText.read(source);
    }

    @Test public void reconnectPreservesChildSessionAndPolicyContext() throws IOException {
        String text = source();
        String save = text.substring(text.indexOf("static void save("),
                text.indexOf("public static Intent buildResumeIntent("));
        String restore = text.substring(text.indexOf("public static Intent buildResumeIntent("),
                text.indexOf("static boolean clearIfMatches("));
        for (String extra : new String[] {"EXTRA_EXECUTION_PROFILE_ID", "EXTRA_CHILD_PROFILE", "EXTRA_CHILD_SESSION_ID", "EXTRA_CHILD_EXECUTION_PROFILE_ID",
                "EXTRA_CHILD_GAME_ID", "EXTRA_CHILD_DEADLINE_ELAPSED_MS", "EXTRA_CHILD_POLICY_REVISION"}) {
            assertTrue(extra, save.contains("Game." + extra));
            assertTrue(extra, restore.contains("Game." + extra));
        }
    }

    @Test public void resumedCoverCountsFromTheResumeNotTheParkedStreamsStart() throws IOException {
        String text = source();
        String save = text.substring(text.indexOf("static void save("),
                text.indexOf("public static Intent buildResumeIntent("));
        String restore = text.substring(text.indexOf("public static Intent buildResumeIntent("),
                text.indexOf("static boolean clearIfMatches("));
        assertFalse(save.contains("EXTRA_CONSOLE_LOADING_EPOCH"));
        assertTrue(restore.contains(
                "putExtra(Game.EXTRA_CONSOLE_LOADING_EPOCH, SystemClock.elapsedRealtime())"));
    }

    private static Map<String, Object> record(String streamSessionId) {
        Map<String, Object> stored = new HashMap<>();
        stored.put("pending", true);
        stored.put("profileId", "parent");
        stored.put("pcUuid", "host");
        stored.put("appId", 42);
        if (streamSessionId != null) stored.put("streamSessionId", streamSessionId);
        return stored;
    }

    @Test public void processLossReconnectPreservesStoredStreamSessionId() {
        SessionResumeManager.PendingSession pending = SessionResumeManager.read(record("stream-a"));
        assertEquals("stream-a", pending.streamSessionId);
        assertEquals("parent", pending.executionProfileId);
        assertTrue(pending.matches(" stream-a "));
    }

    @Test public void recordWithoutItsOwnStreamSessionIdIsNotARecord() {
        assertNull(SessionResumeManager.read(record(null)));
        Map<String, Object> noProfile = record("stream-a");
        noProfile.remove("profileId");
        assertNull(SessionResumeManager.read(noProfile));
    }

    @Test public void clearingRemovesOnlyTheMatchingRecord() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.values.putAll(record("stream-a"));
        assertFalse(SessionResumeManager.clearIfMatches(prefs, "stream-b"));
        assertTrue(SessionResumeManager.clearIfMatches(prefs, "stream-a"));
        assertNull(SessionResumeManager.read(prefs.getAll()));
    }

    @Test public void reconnectDoesNotRestartProviderGame() {
        assertEquals("GAME_CONNECTION", SessionResumeManager.reconnectTransitionType("GAME"));
        assertEquals("PLAYNITE", SessionResumeManager.reconnectTransitionType("PLAYNITE"));
    }

    @Test public void reconnectPersistsExactNeutralTargetIdentity() throws IOException {
        String text = source();
        assertEquals(2, text.split(
                "gameIntent.getStringExtra\\(Game.EXTRA_STREAM_TARGET_NAME\\)", -1).length);
        assertEquals(2, text.split(
                "Game.EXTRA_NEUTRAL_STREAM_TARGET", -1).length - 1);
        assertTrue(text.contains("public final boolean neutralStreamTarget"));
        assertTrue(text.contains("this.neutralStreamTarget = prefs.getBoolean("));
        assertTrue(text.contains("public final String executionProfileId"));
        assertTrue(text.contains("public final String childSessionId"));
        assertTrue(text.contains("editor.putString(KEY_PROFILE_ID"));
        assertTrue(text.contains("intent.putExtra(Game.EXTRA_PROFILE_ID, current.profileId);"));
    }
}
