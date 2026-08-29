package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class PlayniteLibraryQueryTest {
    @Test
    public void searchIsCaseInsensitiveAndKeepsRequestedSort() {
        List<PlayniteDashboardItem> result = PlayniteLibraryQuery.apply(Arrays.asList(
                item("2", "Hollow Knight", 20L, 2),
                item("1", "Hollow Cocoon", 10L, 5),
                item("3", "Cuphead", 30L, 9)),
                "HOLLOW", PlayniteLibrarySort.PLAYTIME, Locale.ENGLISH);

        assertEquals(2, result.size());
        assertEquals("Hollow Knight", result.get(0).game.name);
        assertEquals("Hollow Cocoon", result.get(1).game.name);
    }

    @Test
    public void stableIndexDoesNotDependOnDisplayOrder() {
        List<PlayniteDashboardItem> items = Arrays.asList(
                item("a", "A", 0L, 0), item("b", "B", 0L, 0));
        assertEquals(1, PlayniteLibraryQuery.indexOf(items, "b"));
        assertEquals(-1, PlayniteLibraryQuery.indexOf(items, "missing"));
    }

    @Test
    public void cacheReusesOnlyTheSameQueryAndImmutableSource() {
        List<PlayniteDashboardItem> items = Arrays.asList(
                item("1", "Hollow Knight", 20L, 2),
                item("2", "Cuphead", 10L, 5));
        PlayniteLibraryQuery.Cache cache = new PlayniteLibraryQuery.Cache();

        List<PlayniteDashboardItem> first = cache.apply(
                items, "", PlayniteLibrarySort.RECENT, Locale.ENGLISH);
        assertSame(first, cache.apply(
                items, "", PlayniteLibrarySort.RECENT, Locale.ENGLISH));
        assertEquals(1, cache.indexOf("1"));

        List<PlayniteDashboardItem> filtered = cache.apply(
                items, "hollow", PlayniteLibrarySort.RECENT, Locale.ENGLISH);
        assertNotSame(first, filtered);
        assertEquals(1, filtered.size());
        assertEquals(0, cache.indexOf("1"));
        assertEquals(-1, cache.indexOf("2"));
        assertNotSame(filtered, cache.apply(
                Arrays.asList(items.get(0), items.get(1)), "hollow",
                PlayniteLibrarySort.RECENT, Locale.ENGLISH));

        List<PlayniteDashboardItem> byPlaytime = cache.apply(
                items, "", PlayniteLibrarySort.PLAYTIME, Locale.ENGLISH);
        assertNotSame(first, byPlaytime);
        assertEquals("Hollow Knight", byPlaytime.get(0).game.name);
        assertEquals(0, cache.indexOf("1"));
    }

    private static PlayniteDashboardItem item(String id, String name,
                                               long playtime, int launches) {
        PlayniteLibraryGame game = new PlayniteLibraryGame(id, name, true,
                false, false, playtime, "", "", "", "", launches,
                "Steam", "Action");
        return new PlayniteDashboardItem(game, null, "",
                PlayniteDashboardItem.MappingState.MISSING);
    }
}
