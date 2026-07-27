package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlaynitePlaytimeFormatterTest {
    private static final PlaynitePlaytimeFormatter.Labels EN =
            new PlaynitePlaytimeFormatter.Labels("Never played", "hr", "min");
    private static final PlaynitePlaytimeFormatter.Labels PL =
            new PlaynitePlaytimeFormatter.Labels("Nie grano", "godz.", "min");

    @Test public void formatsZeroAndMissingAsNeverPlayedInBothLocales() {
        assertEquals("Never played", PlaynitePlaytimeFormatter.format(0, EN));
        assertEquals("Nie grano", PlaynitePlaytimeFormatter.format(-1, PL));
    }

    @Test public void formatsMinutesHoursAndRemainder() {
        assertEquals("35 min", PlaynitePlaytimeFormatter.format(35 * 60L, EN));
        assertEquals("2 hr", PlaynitePlaytimeFormatter.format(2 * 3600L, EN));
        assertEquals("12 hr 30 min", PlaynitePlaytimeFormatter.format(45_000L, EN));
        assertEquals("12 godz. 30 min", PlaynitePlaytimeFormatter.format(45_000L, PL));
    }

    @Test public void largeValuesDoNotOverflowOrExposeSeconds() {
        assertEquals("100000 hr", PlaynitePlaytimeFormatter.format(360_000_000L, EN));
    }
}
