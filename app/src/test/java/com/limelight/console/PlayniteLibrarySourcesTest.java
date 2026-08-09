package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PlayniteLibrarySourcesTest {
    @Test public void emptySourceIsPresentedAsPlayniteAndNamesAreDeduplicated() {
        Map<String, String> available = PlayniteLibrarySources.available(Arrays.asList(
                game(1, ""), game(2, "Steam"), game(3, "steam"),
                game(4, "Epic")), Locale.ENGLISH);

        assertEquals(Arrays.asList("Epic", "Playnite", "Steam"),
                Arrays.asList(available.values().toArray(new String[0])));
    }

    @Test public void nullSelectionShowsEverythingAndConfiguredEmptyShowsNothing() {
        List<PlayniteLibraryGame> games = Arrays.asList(
                game(1, "Steam"), game(2, "Epic"));

        assertEquals(2, PlayniteLibrarySources.filter(games, null).size());
        assertEquals(0, PlayniteLibrarySources.filter(
                games, Collections.emptySet()).size());
    }

    @Test public void sourceSelectionIsCaseInsensitive() {
        List<PlayniteLibraryGame> filtered = PlayniteLibrarySources.filter(Arrays.asList(
                game(1, "Steam"), game(2, "Epic")),
                new LinkedHashSet<>(Collections.singletonList("steam")));

        assertEquals(1, filtered.size());
        assertEquals("Steam", filtered.get(0).source);
    }

    private static PlayniteLibraryGame game(int suffix, String source) {
        return new PlayniteLibraryGame(String.format(Locale.US,
                "%08d-0000-0000-0000-000000000000", suffix),
                "Game " + suffix, true, false, 0L, "", "", "", source);
    }
}
