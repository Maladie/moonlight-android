package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlayniteLibrarySortTest {
    @Test
    public void unknownPreferenceFallsBackToRecent() {
        assertEquals(PlayniteLibrarySort.RECENT,
                PlayniteLibrarySort.fromPreference("future-value"));
    }

    @Test
    public void everySortValueRoundTrips() {
        for (PlayniteLibrarySort sort : PlayniteLibrarySort.values()) {
            assertEquals(sort, PlayniteLibrarySort.fromPreference(sort.preferenceValue));
        }
    }
}
