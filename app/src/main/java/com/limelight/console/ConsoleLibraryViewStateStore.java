package com.limelight.console;

import android.content.SharedPreferences;

/** Persists navigation state independently for every Moonlight host. */
final class ConsoleLibraryViewStateStore {
    static final class State {
        final String carouselGameId;
        final String expandedGameId;
        final String searchQuery;
        final boolean expandedMode;

        State(String carouselGameId, String expandedGameId,
              String searchQuery, boolean expandedMode) {
            this.carouselGameId = safe(carouselGameId);
            this.expandedGameId = safe(expandedGameId);
            this.searchQuery = safe(searchQuery);
            this.expandedMode = expandedMode;
        }
    }

    interface Backend {
        String getString(String key, String fallback);
        boolean getBoolean(String key, boolean fallback);
        void putString(String key, String value);
        void putBoolean(String key, boolean value);
        void remove(String... keys);
    }

    private final Backend backend;

    ConsoleLibraryViewStateStore(SharedPreferences preferences) {
        this(new SharedPreferencesBackend(preferences));
    }

    ConsoleLibraryViewStateStore(Backend backend) {
        this.backend = backend;
    }

    State load(String hostUuid, String legacySelectedGameId) {
        return new State(
                backend.getString(key(hostUuid, "carousel_game"), legacySelectedGameId),
                backend.getString(key(hostUuid, "expanded_game"), ""),
                backend.getString(key(hostUuid, "expanded_search"), ""),
                backend.getBoolean(key(hostUuid, "expanded_mode"), false));
    }

    void saveCarouselGame(String hostUuid, String gameId) {
        backend.putString(key(hostUuid, "carousel_game"), safe(gameId));
    }

    void saveExpandedGame(String hostUuid, String gameId) {
        backend.putString(key(hostUuid, "expanded_game"), safe(gameId));
    }

    void saveSearch(String hostUuid, String query) {
        backend.putString(key(hostUuid, "expanded_search"), safe(query));
    }

    void saveExpandedMode(String hostUuid, boolean expanded) {
        backend.putBoolean(key(hostUuid, "expanded_mode"), expanded);
    }

    void clear(String hostUuid) {
        backend.remove(key(hostUuid, "carousel_game"),
                key(hostUuid, "expanded_game"),
                key(hostUuid, "expanded_search"),
                key(hostUuid, "expanded_mode"));
    }

    private static String key(String hostUuid, String suffix) {
        switch (suffix) {
            case "carousel_game":
                return "playnite_carousel_game." + safe(hostUuid);
            case "expanded_game":
                return "playnite_expanded_game." + safe(hostUuid);
            case "expanded_search":
                return "playnite_expanded_search." + safe(hostUuid);
            case "expanded_mode":
                return "playnite_expanded_mode." + safe(hostUuid);
            default:
                throw new IllegalArgumentException("Unknown library view key");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class SharedPreferencesBackend implements Backend {
        private final SharedPreferences preferences;

        SharedPreferencesBackend(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @Override public String getString(String key, String fallback) {
            return preferences.getString(key, fallback);
        }

        @Override public boolean getBoolean(String key, boolean fallback) {
            return preferences.getBoolean(key, fallback);
        }

        @Override public void putString(String key, String value) {
            preferences.edit().putString(key, value).apply();
        }

        @Override public void putBoolean(String key, boolean value) {
            preferences.edit().putBoolean(key, value).apply();
        }

        @Override public void remove(String... keys) {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : keys) editor.remove(key);
            editor.apply();
        }
    }
}
