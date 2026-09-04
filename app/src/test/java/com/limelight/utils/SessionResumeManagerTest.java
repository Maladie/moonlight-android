package com.limelight.utils;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionResumeManagerTest {
    @Test public void processLossReconnectPreservesStoredStreamSessionId() {
        assertEquals("stream-a", SessionResumeManager.resolveStreamSessionId(
                "stream-a", "host", 42, "client"));
    }

    @Test public void legacyRecordGetsStableCompatibleIdentityWithoutWrite() {
        String first = SessionResumeManager.resolveStreamSessionId(
                "", "HOST", 42, "client");
        String second = SessionResumeManager.resolveStreamSessionId(
                null, "HOST", 42, "client");

        assertFalse(first.isEmpty());
        assertEquals(first, second);
    }

    @Test public void reconnectDoesNotRestartProviderGame() {
        assertEquals("GAME_CONNECTION", SessionResumeManager.reconnectTransitionType("GAME"));
        assertEquals("PLAYNITE", SessionResumeManager.reconnectTransitionType("PLAYNITE"));
    }

    @Test public void reconnectPersistsExactNeutralTargetIdentity() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/utils/SessionResumeManager.java");
        if (!Files.exists(source)) source = Paths.get(
                "app/src/main/java/com/limelight/utils/SessionResumeManager.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertEquals(2, text.split(
                "gameIntent.getStringExtra\\(Game.EXTRA_STREAM_TARGET_NAME\\)", -1).length);
        assertEquals(2, text.split(
                "Game.EXTRA_NEUTRAL_STREAM_TARGET", -1).length - 1);
        assertTrue(text.contains("public final boolean neutralStreamTarget"));
        assertTrue(text.contains("this.neutralStreamTarget = prefs.getBoolean("));
    }

    @Test public void reconnectPersistsProfileAndRejectsAmbiguousLegacyState()
            throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/utils/SessionResumeManager.java");
        if (!Files.exists(source)) source = Paths.get(
                "app/src/main/java/com/limelight/utils/SessionResumeManager.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertTrue(text.contains("editor.putString(KEY_PROFILE_ID"));
        assertTrue(text.contains("if (!prefs.contains(KEY_PROFILE_ID)) return null;"));
        assertTrue(text.contains("intent.putExtra(Game.EXTRA_PROFILE_ID, current.profileId);"));
    }
}
