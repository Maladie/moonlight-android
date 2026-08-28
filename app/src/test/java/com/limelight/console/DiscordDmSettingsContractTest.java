package com.limelight.console;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DiscordDmSettingsContractTest {
    @Test public void preferenceIsGlobalDefaultOnAndAvailableInCommunitySocial() throws Exception {
        String preferences = source("DiscordDmNotificationPreferences.java");
        String view = source("DiscordCommunityView.java");

        assertTrue(preferences.contains("\"discord_dm_notifications\""));
        assertTrue(preferences.contains("getBoolean(KEY, true)"));
        assertTrue(view.contains("private void addSocial()"));
        assertTrue(view.contains("discord.community.social.dm_notifications"));
        assertTrue(view.contains("onDmNotificationsChanged"));
    }

    @Test public void cueUsesSonificationWithoutAudioFocusOrStreamDucking() throws Exception {
        String cue = source("DiscordDmCuePlayer.java");

        assertTrue(cue.contains("USAGE_ASSISTANCE_SONIFICATION"));
        assertTrue(cue.contains("DURATION_MS = 220"));
        assertFalse(cue.contains("requestAudioFocus"));
        assertFalse(cue.contains("setVolume(0"));
    }

    @Test public void bothForegroundHostsInitializeTheSameProcessCoordinator() throws Exception {
        String game = sourceFrom("app/src/main/java/com/limelight/Game.java");
        String console = source("ConsoleActivity.java");

        assertTrue(game.contains("discordDmNotifications.initialize(this)"));
        assertTrue(console.contains("discordDmNotifications.initialize(this)"));
    }

    @Test public void socialSnapshotPublishesBeforeEventsAndAuthStartsBlocked() throws Exception {
        String social = sourceFrom("app/src/main/java/com/limelight/discord/DiscordSocialClient.java");
        int pump = social.indexOf("private static void pumpCallbacks()");
        int publish = social.indexOf("publishSnapshot(parsed)", pump);
        int events = social.indexOf("enqueueMessageEvents(messageRecords)", pump);

        assertTrue(publish > pump && events > publish);
        assertTrue(social.contains("notifySocialStateObservers(snapshot, true)"));
        assertTrue(social.contains("onSocialStateChanged(Snapshot snapshot"));
    }

    @Test public void loadingSurfaceBlocksNotificationsAndStaysOnTop() throws Exception {
        String console = source("ConsoleActivity.java");
        int loadingArtwork = console.indexOf("String loadingArtworkPath)");
        int loading = console.lastIndexOf("private void showLoading", loadingArtwork);

        assertTrue(loading >= 0);
        assertTrue(console.indexOf("setPresentationBlocked(discordDmHostToken, true)", loading)
                > loading);
        assertTrue(console.indexOf("loadingLayer.bringToFront()", loading) > loading);
        assertTrue(console.indexOf("setPresentationBlocked(discordDmHostToken, false)",
                console.indexOf("private void showHome()")) > 0);
    }

    private static String source(String name) throws Exception {
        return sourceFrom("app/src/main/java/com/limelight/console/" + name);
    }

    private static String sourceFrom(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path) && relative.startsWith("app/")) {
            path = Paths.get(relative.substring(4));
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
