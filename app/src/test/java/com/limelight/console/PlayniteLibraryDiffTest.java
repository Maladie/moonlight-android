package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlayniteLibraryDiffTest {
    @Test public void identicalResponseProducesNoVisibleUpdates() {
        List<PlayniteDashboardItem> items = Collections.singletonList(item("1", 60, "cover"));
        assertTrue(PlayniteLibraryDiff.calculate(items, items).isEmpty());
    }

    @Test public void playtimeAndArtworkProduceNarrowPayloads() {
        PlayniteLibraryDiff.Change time = PlayniteLibraryDiff.calculate(
                Collections.singletonList(item("1", 60, "cover")),
                Collections.singletonList(item("1", 120, "cover"))).get(0);
        assertEquals(PlayniteLibraryDiff.TEXT, time.payload);

        PlayniteLibraryDiff.Change art = PlayniteLibraryDiff.calculate(
                Collections.singletonList(item("1", 60, "cover")),
                Collections.singletonList(item("1", 60, "cover2"))).get(0);
        assertEquals(PlayniteLibraryDiff.ARTWORK, art.payload);
    }

    @Test public void selectionFollowsStableIdAcrossInsertMoveAndRemoval() {
        PlayniteDashboardItem first = item("1", 0, "");
        PlayniteDashboardItem selected = item("2", 0, "");
        PlayniteDashboardItem inserted = item("3", 0, "");
        assertEquals(selected.stableId(), PlayniteLibraryDiff.selectionAfter(
                selected.stableId(), 1, Arrays.asList(inserted, selected, first)));
        assertEquals(first.stableId(), PlayniteLibraryDiff.selectionAfter(
                selected.stableId(), 1, Arrays.asList(inserted, first)));
    }

    private static PlayniteDashboardItem item(String suffix, long seconds, String cover) {
        String id = ("00000000" + suffix);
        id = id.substring(id.length() - 8) + "-0000-0000-0000-000000000000";
        PlayniteLibraryGame game = new PlayniteLibraryGame(id, "Game " + suffix,
                true, false, seconds, "", cover, "", "Steam");
        return new PlayniteDashboardItem(game, 1, "Game " + suffix,
                PlayniteDashboardItem.MappingState.MAPPED);
    }
}
