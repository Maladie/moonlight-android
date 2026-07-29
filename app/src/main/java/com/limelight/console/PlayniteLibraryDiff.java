package com.limelight.console;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keyed diff payloads used by the legacy TV row without recreating unchanged cards. */
final class PlayniteLibraryDiff {
    static final int TEXT = 1;
    static final int ARTWORK = 1 << 1;
    static final int LAUNCH = 1 << 2;
    static final int POSITION = 1 << 3;

    static final class Change {
        final String id;
        final int oldIndex;
        final int newIndex;
        final int payload;

        Change(String id, int oldIndex, int newIndex, int payload) {
            this.id = id;
            this.oldIndex = oldIndex;
            this.newIndex = newIndex;
            this.payload = payload;
        }
    }

    static List<Change> calculate(List<PlayniteDashboardItem> oldItems,
                                  List<PlayniteDashboardItem> newItems) {
        Map<String, Integer> oldPositions = positions(oldItems);
        Map<String, PlayniteDashboardItem> oldById = values(oldItems);
        Map<String, PlayniteDashboardItem> newById = values(newItems);
        List<Change> changes = new ArrayList<>();
        for (int newIndex = 0; newIndex < newItems.size(); newIndex++) {
            PlayniteDashboardItem next = newItems.get(newIndex);
            Integer oldIndex = oldPositions.get(next.stableId());
            if (oldIndex == null) {
                changes.add(new Change(next.stableId(), -1, newIndex,
                        TEXT | ARTWORK | LAUNCH | POSITION));
                continue;
            }
            PlayniteDashboardItem old = oldById.get(next.stableId());
            int payload = oldIndex == newIndex ? 0 : POSITION;
            if (!old.game.name.equals(next.game.name) ||
                    old.game.installed != next.game.installed ||
                    old.game.playtimeSeconds != next.game.playtimeSeconds ||
                    old.game.playCount != next.game.playCount ||
                    !old.game.lastActivity.equals(next.game.lastActivity) ||
                    !old.game.description.equals(next.game.description) ||
                    !old.game.source.equals(next.game.source)) payload |= TEXT;
            if (!old.game.coverKey.equals(next.game.coverKey) ||
                    !old.game.backgroundKey.equals(next.game.backgroundKey)) payload |= ARTWORK;
            if (!java.util.Objects.equals(old.sunshineAppId, next.sunshineAppId) ||
                    old.mappingState != next.mappingState) payload |= LAUNCH;
            if (payload != 0) changes.add(new Change(next.stableId(), oldIndex, newIndex, payload));
        }
        for (int oldIndex = 0; oldIndex < oldItems.size(); oldIndex++) {
            PlayniteDashboardItem old = oldItems.get(oldIndex);
            if (!newById.containsKey(old.stableId())) {
                changes.add(new Change(old.stableId(), oldIndex, -1, POSITION));
            }
        }
        return changes;
    }

    static String selectionAfter(String focusedId, int previousIndex,
                                 List<PlayniteDashboardItem> newItems) {
        if (focusedId != null) {
            for (PlayniteDashboardItem item : newItems) {
                if (focusedId.equals(item.stableId())) return focusedId;
            }
        }
        if (newItems.isEmpty()) return null;
        int index = Math.max(0, Math.min(previousIndex, newItems.size() - 1));
        return newItems.get(index).stableId();
    }

    private static Map<String, Integer> positions(List<PlayniteDashboardItem> items) {
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < items.size(); index++) {
            result.put(items.get(index).stableId(), index);
        }
        return result;
    }

    private static Map<String, PlayniteDashboardItem> values(List<PlayniteDashboardItem> items) {
        Map<String, PlayniteDashboardItem> result = new HashMap<>();
        for (PlayniteDashboardItem item : items) result.put(item.stableId(), item);
        return result;
    }

    private PlayniteLibraryDiff() { }
}
