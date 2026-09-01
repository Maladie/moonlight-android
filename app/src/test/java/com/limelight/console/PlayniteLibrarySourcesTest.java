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

    @Test public void nullOrUnmappableSelectionFallsBackToEverything() {
        List<PlayniteLibraryGame> games = Arrays.asList(
                game(1, "Steam"), game(2, "Epic"));

        assertEquals(2, PlayniteLibrarySources.filter(games, null).size());
        assertEquals(2, PlayniteLibrarySources.filter(
                games, Collections.emptySet()).size());
    }

    @Test public void sourceSelectionIsCaseInsensitive() {
        List<PlayniteLibraryGame> filtered = PlayniteLibrarySources.filter(Arrays.asList(
                game(1, "Steam"), game(2, "Epic")),
                new LinkedHashSet<>(Collections.singletonList("steam")));

        assertEquals(1, filtered.size());
        assertEquals("Steam", filtered.get(0).source);
    }

    @Test public void sourceAndInstallationFiltersComposeFromTheOriginalLibrary() {
        List<PlayniteLibraryGame> games = Arrays.asList(
                game(1, "Steam", true), game(2, "Steam", false),
                game(3, "Epic", true));
        List<PlayniteLibraryGame> steam = PlayniteLibrarySources.filter(games,
                new LinkedHashSet<>(Collections.singletonList("steam")));

        assertEquals(1, PlayniteLibraryOrdering.order(steam,
                PlayniteLibraryFilter.INSTALLED, Locale.ENGLISH).size());
        assertEquals(2, PlayniteLibraryOrdering.order(steam,
                PlayniteLibraryFilter.ALL, Locale.ENGLISH).size());
        List<PlayniteLibraryGame> uninstalled = PlayniteLibraryOrdering.order(steam,
                PlayniteLibraryFilter.UNINSTALLED, Locale.ENGLISH);
        assertEquals(1, uninstalled.size());
        assertEquals("Game 2", uninstalled.get(0).name);
    }

    @Test public void legacyLabelsMigrateToLibraryKeysAndUnknownFallsBackToAll() {
        List<PlayniteLibraryGame> games = Arrays.asList(
                game(1, "Steam"), game(2, "Epic"));
        Map<String, String> available = PlayniteLibrarySources.available(
                games, Locale.ENGLISH);

        assertEquals(Collections.singleton("steam"),
                PlayniteLibrarySources.migrateSelection(
                        Collections.singleton("Steam"), available));
        assertEquals(null, PlayniteLibrarySources.migrateSelection(
                Collections.singleton("Missing"), available));
    }

    @Test public void sourceKeysAndColorsRemainStableForIconSelection() {
        assertEquals("steam", PlayniteLibrarySources.key("Steam"));
        assertEquals("epic games", PlayniteLibrarySources.key("Epic Games"));
        assertEquals("playnite", PlayniteLibrarySources.key(""));
        assertEquals(0xFF107C10, PlayniteLibrarySources.badgeColor("Xbox"));
    }

    private static PlayniteLibraryGame game(int suffix, String source) {
        return game(suffix, source, true);
    }

    private static PlayniteLibraryGame game(int suffix, String source, boolean installed) {
        return new PlayniteLibraryGame(String.format(Locale.US,
                "%08d-0000-0000-0000-000000000000", suffix),
                "Game " + suffix, installed, false, 0L, "", "", "", source);
    }
}
