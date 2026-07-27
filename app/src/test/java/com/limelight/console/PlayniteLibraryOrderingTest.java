package com.limelight.console;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class PlayniteLibraryOrderingTest {
    @Test public void putsOnlyFiveNewestAtFrontWithoutDuplicates() {
        List<PlayniteLibraryGame> source = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            source.add(game(day, "Played " + day, true,
                    String.format(Locale.US, "2026-07-%02dT10:00:00Z", day)));
        }
        source.add(game(20, "Alpha", true, ""));
        source.add(game(21, "Beta", true, ""));

        List<PlayniteLibraryGame> ordered =
                PlayniteLibraryOrdering.order(source, false, Locale.ENGLISH);

        assertEquals(Arrays.asList("Played 7", "Played 6", "Played 5", "Played 4",
                "Played 3", "Alpha", "Beta", "Played 1", "Played 2"), names(ordered));
        assertEquals(source.size(), ordered.size());
    }

    @Test public void installedFilterRunsBeforeRecentSelectionAndNullDatesAreSafe() {
        List<PlayniteLibraryGame> ordered = PlayniteLibraryOrdering.order(Arrays.asList(
                game(1, "Unavailable", false, "2026-07-22T10:00:00Z"),
                game(2, "Installed", true, "2026-07-20T10:00:00Z"),
                game(3, "Never", true, null)), true, Locale.ENGLISH);

        assertEquals(Arrays.asList("Installed", "Never"), names(ordered));
    }

    @Test public void equalDatesUseStableNameThenIdTieBreaker() {
        String date = "2026-07-20T10:00:00.0000000+00:00";
        List<PlayniteLibraryGame> ordered = PlayniteLibraryOrdering.order(Arrays.asList(
                game(2, "Same", true, date), game(1, "Same", true, date),
                game(3, "Alpha", true, date)), false, Locale.ENGLISH);

        assertEquals(Arrays.asList("Alpha", "Same", "Same"), names(ordered));
        assertEquals(id(1), ordered.get(1).playniteGameId);
        assertEquals(id(2), ordered.get(2).playniteGameId);
    }

    @Test public void hiddenGamesAreExcluded() {
        PlayniteLibraryGame hidden = new PlayniteLibraryGame(id(1), "Hidden", true,
                true, 0, "", "", "", "Steam");
        assertEquals(0, PlayniteLibraryOrdering.order(
                Arrays.asList(hidden), false, Locale.ENGLISH).size());
    }

    private static PlayniteLibraryGame game(int suffix, String name, boolean installed,
                                            String lastActivity) {
        return new PlayniteLibraryGame(id(suffix), name, installed, false,
                0L, lastActivity, "", "", "Steam");
    }

    private static String id(int value) {
        return String.format(Locale.US, "%08d-0000-0000-0000-000000000000", value);
    }

    private static List<String> names(List<PlayniteLibraryGame> games) {
        List<String> names = new ArrayList<>();
        for (PlayniteLibraryGame game : games) names.add(game.name);
        return names;
    }
}
