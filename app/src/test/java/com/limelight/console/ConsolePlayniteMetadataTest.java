package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConsolePlayniteMetadataTest {
    @Test public void reportsPlaytimeAndLastLaunchWithoutPlayniteLabel() {
        assertEquals("2H 05M  ·  LAST 15.07.26",
                ConsolePlayniteMetadata.format(125, "2026-07-15T20:10:00Z", true));
    }

    @Test public void keepsUninstalledStateCompact() {
        assertEquals("NOT INSTALLED", ConsolePlayniteMetadata.format(0, "", false));
    }
}
