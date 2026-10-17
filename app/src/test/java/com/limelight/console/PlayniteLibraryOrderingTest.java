package com.limelight.console;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PlayniteLibraryOrderingTest {
    @Test
    public void localLaunchWinsOverStalePlayniteActivity() {
        PlayniteLibraryGame older = game(1, "Cuphead", true,
                "2026-01-01T10:00:00Z");
        PlayniteLibraryGame newer = game(2, "Other", true,
                "2026-08-01T10:00:00Z");

        List<PlayniteLibraryGame> ordered = PlayniteLibraryOrdering.order(
                Arrays.asList(newer, older), PlayniteLibraryFilter.RECENTLY_PLAYED,
                Locale.ENGLISH, Collections.singletonMap(older.playniteGameId,
                        1_800_000_000_000L));

        assertEquals("Cuphead", ordered.get(0).name);
    }
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

    @Test public void supportsEveryLibraryFilterAndItsOrdering() {
        PlayniteLibraryGame recent = game(1, "Recent", true,
                "2026-07-28T10:00:00Z", 2);
        PlayniteLibraryGame frequent = game(2, "Frequent", true,
                "2026-07-20T10:00:00Z", 12);
        PlayniteLibraryGame unavailable = game(3, "Unavailable", false,
                "2026-07-25T10:00:00Z", 3);
        PlayniteLibraryGame never = game(4, "Never", false, "", 0);
        List<PlayniteLibraryGame> source = Arrays.asList(
                never, recent, unavailable, frequent);

        assertEquals(Arrays.asList("Recent", "Frequent"), names(
                PlayniteLibraryOrdering.order(source, PlayniteLibraryFilter.INSTALLED,
                        Locale.ENGLISH)));
        assertEquals(Arrays.asList("Recent", "Unavailable", "Frequent"), names(
                PlayniteLibraryOrdering.order(source, PlayniteLibraryFilter.RECENTLY_PLAYED,
                        Locale.ENGLISH)));
        assertEquals(Arrays.asList("Unavailable", "Never"), names(
                PlayniteLibraryOrdering.order(source, PlayniteLibraryFilter.UNINSTALLED,
                        Locale.ENGLISH)));
        assertEquals(Arrays.asList("Frequent", "Unavailable", "Recent"), names(
                PlayniteLibraryOrdering.order(source, PlayniteLibraryFilter.MOST_LAUNCHED,
                        Locale.ENGLISH)));
        assertEquals(Arrays.asList("Never"), names(
                PlayniteLibraryOrdering.order(source, PlayniteLibraryFilter.NEVER_LAUNCHED,
                        Locale.ENGLISH)));
    }

    @Test public void collapsesExcessiveDescriptionParagraphSpacing() {
        PlayniteLibraryGame item = new PlayniteLibraryGame(id(1), "Game", true,
                false, 0L, "", "", "", "First\n\n\n\nSecond", 0, "Steam");
        assertEquals("First\n\nSecond", item.description);
    }

    @Test public void carouselPutsTheLastPlayedGameFirstAndTheRestByName() {
        List<PlayniteDashboardItem> host = Arrays.asList(item(game(3, "Zeta", true, "")),
                item(game(1, "Played", true, "")), item(game(2, "Alpha", true, "")),
                item(game(4, "Absent", false, "")), item(game(5, "Older", true, "")));
        Map<String, Long> activity = new HashMap<>();
        activity.put(id(5), 1_000L);
        activity.put(id(1), 2_000L);
        List<PlayniteDashboardItem> before = carousel(host, activity, "");
        assertEquals(Arrays.asList("Played", "Older", "Alpha", "Zeta"), itemNames(before));

        // A host answering in another order, the same facts: the same carousel.
        List<PlayniteDashboardItem> reversed = new ArrayList<>(host);
        Collections.reverse(reversed);
        assertEquals(itemNames(before), itemNames(carousel(reversed, activity, "")));

        // Zeta played last lands first, and stays there once its session ends.
        activity.put(id(3), 3_000L);
        assertEquals(Arrays.asList("Zeta", "Played", "Older", "Alpha"),
                itemNames(carousel(host, activity, "")));
    }

    @Test public void carouselLeadsWithTheResumableGameThenInstallsWithinTheLimit() {
        List<PlayniteDashboardItem> host = Arrays.asList(item(game(1, "Recent", true, "")),
                item(game(2, "Session", true, "")), item(game(3, "Installing", false, "")),
                item(game(4, "Alpha", true, "")));
        Map<String, Long> activity = Collections.singletonMap(id(1), 5_000L);
        List<PlayniteDashboardItem> ordered = PlayniteLibraryOrdering.carousel(host, 3, id(2),
                Collections.singleton(id(3)),
                item -> activity.getOrDefault(item.stableId(), 0L), Locale.ENGLISH);
        assertEquals(Arrays.asList("Session", "Installing", "Recent"), itemNames(ordered));
    }

    /** A suspended session's game leads too, matched ignoring the id's case. */
    @Test public void carouselLeadsWithTheSessionGameIgnoringCase() {
        String suspendedId = "abcdef00-0000-0000-0000-000000000000";
        List<PlayniteDashboardItem> host = Arrays.asList(item(game(1, "Recent", true, "")),
                item(new PlayniteLibraryGame(suspendedId, "Suspended", true, false,
                        0L, "", "", "", "", 0, "Steam")));
        Map<String, Long> activity = Collections.singletonMap(id(1), 5_000L);
        assertEquals(Arrays.asList("Suspended", "Recent"), itemNames(
                carousel(host, activity, suspendedId.toUpperCase(Locale.ROOT))));
    }

    private static List<PlayniteDashboardItem> carousel(List<PlayniteDashboardItem> items,
                                                        Map<String, Long> activity,
                                                        String resumeGameId) {
        return PlayniteLibraryOrdering.carousel(items, 10, resumeGameId,
                Collections.emptySet(), item -> activity.getOrDefault(item.stableId(), 0L),
                Locale.ENGLISH);
    }

    private static PlayniteDashboardItem item(PlayniteLibraryGame game) {
        return new PlayniteDashboardItem(game, 1, PlayniteDashboardItem.MappingState.MAPPED);
    }

    private static List<String> itemNames(List<PlayniteDashboardItem> items) {
        List<String> names = new ArrayList<>();
        for (PlayniteDashboardItem item : items) names.add(item.game.name);
        return names;
    }

    private static PlayniteLibraryGame game(int suffix, String name, boolean installed,
                                            String lastActivity) {
        return game(suffix, name, installed, lastActivity, 0);
    }

    private static PlayniteLibraryGame game(int suffix, String name, boolean installed,
                                            String lastActivity, int playCount) {
        return new PlayniteLibraryGame(id(suffix), name, installed, false,
                0L, lastActivity, "", "", "", playCount, "Steam");
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
