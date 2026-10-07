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

    static int badgeColor(String source) {
        switch (key(source)) {
            case "steam": return 0xFF1B5A7A;
            case "epic":
            case "epic games": return 0xFF3C3C3C;
            case "gog":
            case "gog.com": return 0xFF7B2D8E;
            case "xbox":
            case "microsoft store": return 0xFF107C10;
            case "ea":
            case "origin": return 0xFFD63C48;
            case "ubisoft":
            case "uplay": return 0xFF006EF5;
            case "amazon":
            case "amazon games": return 0xFF008DBA;
            case PLAYNITE_KEY: return 0xFF238BC1;
            default: return 0xFF53636F;
        }
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
        List<PlayniteLibraryGame> result = new ArrayList<>();
        for (PlayniteLibraryGame game : games) {
            if (selectedSources.contains(key(game.libraryKey))) result.add(game);
        }
        // A choice matching none of the current platforms shows everything, as no choice does.
        return result.isEmpty() ? new ArrayList<>(games) : result;
    }

    private PlayniteLibrarySources() { }
}
