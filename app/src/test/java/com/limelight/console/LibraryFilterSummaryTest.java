package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LibraryFilterSummaryTest {
    private static final Map<String, String> SOURCES = new LinkedHashMap<>();

    static {
        SOURCES.put("epic", "Epic");
        SOURCES.put("gog", "GOG");
        SOURCES.put("steam", "Steam");
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    @Test public void defaultsShowNoChipsAndNoCount() {
        LibraryFilterSummary summary = new LibraryFilterSummary(PlayniteLibraryFilter.ALL, null,
                SOURCES, PlayniteLibrarySort.RECENT);
        assertEquals(0, summary.activeCount());
        assertFalse(summary.stateActive());
        assertFalse(summary.sortActive());
        assertTrue(summary.platforms.isEmpty());
        assertTrue(summary.showsSource("gog"));
    }

    @Test public void everyActiveFilterCountsOnceAndPlatformsKeepPanelOrder() {
        LibraryFilterSummary summary = new LibraryFilterSummary(
                PlayniteLibraryFilter.INSTALLED, set("steam", "epic"), SOURCES,
                PlayniteLibrarySort.PLAYTIME);
        assertEquals(3, summary.activeCount());
        assertEquals(Arrays.asList("Epic", "Steam"), summary.platforms);
        assertFalse(summary.showsSource("gog"));
    }

    @Test public void completeOrVanishedPlatformChoiceIsNoFilter() {
        assertTrue(new LibraryFilterSummary(PlayniteLibraryFilter.ALL,
                set("epic", "gog", "steam"), SOURCES, PlayniteLibrarySort.RECENT)
                .platforms.isEmpty());
        LibraryFilterSummary vanished = new LibraryFilterSummary(PlayniteLibraryFilter.ALL,
                set("uplay"), SOURCES, PlayniteLibrarySort.RECENT);
        assertEquals(0, vanished.activeCount());
        assertTrue(vanished.showsSource("steam"));
    }

    @Test public void togglingStartsFromEveryPlatformAndEndsAtNone() {
        Set<String> all = SOURCES.keySet();
        assertEquals(set("epic", "gog"), LibraryFilterSummary.toggleSource(null, all, "steam"));
        assertNull(LibraryFilterSummary.toggleSource(set("epic", "gog"), all, "steam"));
        assertNull(LibraryFilterSummary.toggleSource(set("gog"), all, "gog"));
        assertEquals(set("gog", "steam"),
                LibraryFilterSummary.toggleSource(set("gog"), all, "steam"));
        assertEquals(Collections.singleton("gog"),
                LibraryFilterSummary.toggleSource(set("gog", "uplay"), all, "uplay"));
    }
}
