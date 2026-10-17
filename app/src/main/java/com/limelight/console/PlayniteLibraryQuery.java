package com.limelight.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure search and ordering logic shared by every full-library render. */
final class PlayniteLibraryQuery {
    private PlayniteLibraryQuery() { }

    static final class Cache {
        private List<PlayniteDashboardItem> source;
        private String searchQuery;
        private PlayniteLibrarySort sort;
        private Locale locale;
        private List<PlayniteDashboardItem> result = Collections.emptyList();
        private Map<String, Integer> positions = Collections.emptyMap();

        List<PlayniteDashboardItem> apply(List<PlayniteDashboardItem> items,
                                          String searchQuery,
                                          PlayniteLibrarySort sort,
                                          Locale locale) {
            String safeQuery = searchQuery == null ? "" : searchQuery;
            PlayniteLibrarySort safeSort = sort == null ? PlayniteLibrarySort.RECENT : sort;
            Locale safeLocale = locale == null ? Locale.getDefault() : locale;
            if (source == items && safeQuery.equals(this.searchQuery) && safeSort == this.sort &&
                    safeLocale.equals(this.locale)) {
                return result;
            }
            source = items;
            this.searchQuery = safeQuery;
            this.sort = safeSort;
            this.locale = safeLocale;
            result = Collections.unmodifiableList(
                    PlayniteLibraryQuery.apply(items, safeQuery, safeSort, safeLocale));
            Map<String, Integer> nextPositions = new HashMap<>();
            for (int index = 0; index < result.size(); index++) {
                nextPositions.putIfAbsent(result.get(index).stableId(), index);
            }
            positions = nextPositions;
            return result;
        }

        int indexOf(String stableId) {
            if (stableId == null || stableId.isEmpty()) return -1;
            Integer position = positions.get(stableId);
            return position == null ? -1 : position;
        }
    }

    static List<PlayniteDashboardItem> apply(List<PlayniteDashboardItem> items,
                                              String searchQuery,
                                              PlayniteLibrarySort sort,
                                              Locale locale) {
        Locale safeLocale = locale == null ? Locale.getDefault() : locale;
        String query = searchQuery == null ? ""
                : searchQuery.toLowerCase(safeLocale).trim();
        List<PlayniteDashboardItem> result = new ArrayList<>();
        for (PlayniteDashboardItem item : items) {
            if (query.isEmpty() || item.game.name.toLowerCase(safeLocale).contains(query)) {
                result.add(item);
            }
        }
        result.sort(comparator(sort));
        return result;
    }

    static int indexOf(List<PlayniteDashboardItem> items, String stableId) {
        if (stableId == null || stableId.isEmpty()) return -1;
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).stableId().equals(stableId)) return index;
        }
        return -1;
    }

    private static Comparator<PlayniteDashboardItem> comparator(PlayniteLibrarySort sort) {
        Comparator<PlayniteDashboardItem> byName = (left, right) ->
                String.CASE_INSENSITIVE_ORDER.compare(left.game.name, right.game.name);
        switch (sort == null ? PlayniteLibrarySort.RECENT : sort) {
            case NAME:
                return byName;
            case LIBRARY:
                return Comparator.comparing(
                        (PlayniteDashboardItem item) -> item.game.libraryName,
                        String.CASE_INSENSITIVE_ORDER).thenComparing(byName);
            case GENRE:
                return Comparator.comparing(
                        (PlayniteDashboardItem item) -> item.game.genres,
                        String.CASE_INSENSITIVE_ORDER).thenComparing(byName);
            case PLAYTIME:
                return Comparator.<PlayniteDashboardItem>comparingLong(
                        item -> item.game.playtimeSeconds).reversed().thenComparing(byName);
            case MOST_LAUNCHED:
                return Comparator.<PlayniteDashboardItem>comparingInt(
                        item -> item.game.playCount).reversed().thenComparing(byName);
            case RECENT:
            default:
                return Comparator.<PlayniteDashboardItem>comparingLong(
                        item -> item.game.activityEpoch)
                        .reversed().thenComparing(byName);
        }
    }
}
