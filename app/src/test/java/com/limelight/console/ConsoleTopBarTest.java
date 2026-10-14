package com.limelight.console;

import com.limelight.SourceText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ConsoleTopBarTest {
    @Test public void discordShowsOnlyWhenConfiguredForAnAllowedProfile() {
        assertTrue(ConsoleTopBar.discordVisible(true, true));
        assertFalse(ConsoleTopBar.discordVisible(true, false));
        assertFalse(ConsoleTopBar.discordVisible(false, true));
    }

    @Test public void microphoneShowsOnlyWhenEnabledAndAllowed() {
        assertTrue(ConsoleTopBar.microphoneVisible(true, true));
        assertFalse(ConsoleTopBar.microphoneVisible(true, false));
        assertFalse(ConsoleTopBar.microphoneVisible(false, true));
    }

    @Test public void unreadOutranksTheDiscordConnectionBadge() {
        assertEquals(ConsoleTopBar.DISCORD_UNREAD, ConsoleTopBar.discordBadge(true, 0xFF36B96C));
        assertEquals(0xFF36B96C, ConsoleTopBar.discordBadge(false, 0xFF36B96C));
        assertEquals(0, ConsoleTopBar.discordBadge(false, 0));
    }

    @Test public void streamingHostGetsTheGreenDot() {
        int online = ConsoleHostPresentation.color(ConsoleHostPresentation.State.ONLINE);
        assertEquals(online, ConsoleTopBar.statusDotColor(
                ConsoleHostPresentation.State.ACTIVE_SESSION));
        assertEquals(online, ConsoleTopBar.statusDotColor(ConsoleHostPresentation.State.ONLINE));
        assertEquals(ConsoleHostPresentation.color(ConsoleHostPresentation.State.OFFLINE),
                ConsoleTopBar.statusDotColor(ConsoleHostPresentation.State.OFFLINE));
    }

    @Test public void sessionStatusLineReadsStreamThenApp() throws Exception {
        assertTrue(resource("values-pl/strings.xml").contains(
                "name=\"console_status_active_app\">Stream · %1$s<"));
        assertTrue(resource("values/strings.xml").contains(
                "name=\"console_status_active_app\">Stream · %1$s<"));
    }

    /** A vertical LinearLayout defaults children to MATCH_PARENT: setText would not re-measure. */
    @Test public void hostTextsWrapSoALongerNameIsMeasuredAgain() throws Exception {
        String source = source("com/limelight/console/ConsoleTopBar.java");
        assertTrue(source.contains("texts.addView(hostName, wrapText());"));
        assertTrue(source.contains("texts.addView(hostLine, wrapText());"));
        assertFalse(source.contains("texts.addView(hostName);"));
        assertFalse(source.contains("texts.addView(hostLine);"));
    }

    private static String source(String relative) throws Exception {
        Path path = Paths.get("src/main/java", relative);
        return SourceText.read(path);
    }

    private static String resource(String relative) throws Exception {
        Path path = Paths.get("src/main/res", relative);
        return SourceText.read(path);
    }
}
