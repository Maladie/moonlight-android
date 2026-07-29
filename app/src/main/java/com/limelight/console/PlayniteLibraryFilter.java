package com.limelight.console;

enum PlayniteLibraryFilter {
    INSTALLED("installed"),
    ALL("all"),
    RECENTLY_PLAYED("recently_played"),
    UNINSTALLED("uninstalled"),
    MOST_LAUNCHED("most_launched"),
    NEVER_LAUNCHED("never_launched");

    final String preferenceValue;

    PlayniteLibraryFilter(String preferenceValue) {
        this.preferenceValue = preferenceValue;
    }

    static PlayniteLibraryFilter fromPreference(String value,
                                                boolean legacyInstalledOnly) {
        if (value != null) {
            for (PlayniteLibraryFilter filter : values()) {
                if (filter.preferenceValue.equals(value)) return filter;
            }
        }
        return legacyInstalledOnly ? INSTALLED : ALL;
    }
}
