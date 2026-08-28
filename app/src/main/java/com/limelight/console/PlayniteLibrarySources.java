package com.limelight.console;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Applies provider-neutral library descriptors to the shared grid/carousel selection. */
final class PlayniteLibrarySources {
    static final String PLAYNITE_KEY = "playnite";

    static String key(String source) {
        String value = source == null ? "" : source.trim();
        return value.isEmpty() ? PLAYNITE_KEY : value.toLowerCase(Locale.ROOT);
    }

    static String label(String source) {
        String value = source == null ? "" : source.trim();
        return value.isEmpty() ? "Playnite" : value;
    }

    static Map<String, String> available(List<PlayniteLibraryGame> games, Locale locale) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (PlayniteLibraryGame game : games) {
            labels.putIfAbsent(key(game.libraryKey), label(game.libraryName));
        }
        List<Map.Entry<String, String>> ordered = new ArrayList<>(labels.entrySet());
        Collator collator = Collator.getInstance(locale == null ? Locale.getDefault() : locale);
        collator.setStrength(Collator.SECONDARY);
        ordered.sort((left, right) -> collator.compare(left.getValue(), right.getValue()));
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : ordered) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    static List<PlayniteLibraryGame> filter(List<PlayniteLibraryGame> games,
                                             Set<String> selectedSources) {
        if (selectedSources == null) return new ArrayList<>(games);
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String selected : selectedSources) normalized.add(key(selected));
        Set<String> available = new java.util.LinkedHashSet<>();
        for (PlayniteLibraryGame game : games) available.add(key(game.libraryKey));
        normalized.retainAll(available);
        if (normalized.isEmpty() && !games.isEmpty()) return new ArrayList<>(games);
        List<PlayniteLibraryGame> result = new ArrayList<>();
        for (PlayniteLibraryGame game : games) {
            if (normalized.contains(key(game.libraryKey))) result.add(game);
        }
        return result;
    }

    static Set<String> migrateSelection(Set<String> stored,
                                        Map<String, String> available) {
        if (stored == null) return null;
        Set<String> migrated = new java.util.LinkedHashSet<>();
        for (String value : stored) {
            String candidate = key(value);
            if (available.containsKey(candidate)) {
                migrated.add(candidate);
                continue;
            }
            for (Map.Entry<String, String> entry : available.entrySet()) {
                if (key(entry.getValue()).equals(candidate)) {
                    migrated.add(entry.getKey());
                    break;
                }
            }
        }
        return migrated.isEmpty() ? null : migrated;
    }

    private PlayniteLibrarySources() { }
}
