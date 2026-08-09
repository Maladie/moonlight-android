package com.limelight.console;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Normalizes Playnite library sources and applies the shared grid/carousel selection. */
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
            labels.putIfAbsent(key(game.source), label(game.source));
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
        List<PlayniteLibraryGame> result = new ArrayList<>();
        for (PlayniteLibraryGame game : games) {
            if (selectedSources.contains(key(game.source))) result.add(game);
        }
        return result;
    }

    private PlayniteLibrarySources() { }
}
