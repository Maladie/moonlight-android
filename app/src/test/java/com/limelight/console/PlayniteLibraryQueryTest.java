package com.limelight.console;

import static org.junit.Assert.assertEquals;

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

    private static PlayniteDashboardItem item(String id, String name,
                                               long playtime, int launches) {
        PlayniteLibraryGame game = new PlayniteLibraryGame(id, name, true,
                false, false, playtime, "", "", "", "", launches,
                "Steam", "Action");
        return new PlayniteDashboardItem(game, null, "",
                PlayniteDashboardItem.MappingState.MISSING);
    }
}
