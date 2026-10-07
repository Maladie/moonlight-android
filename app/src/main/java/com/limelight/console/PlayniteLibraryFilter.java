package com.limelight.console;

import com.limelight.R;

/** Library state filter, in the order the Filtry panel lists it. */
enum PlayniteLibraryFilter {
    ALL("all", R.string.playnite_all_games),
    INSTALLED("installed", R.string.playnite_installed),
    UNINSTALLED("uninstalled", R.string.playnite_uninstalled),
    RECENTLY_PLAYED("recently_played", R.string.playnite_recently_played),
    MOST_LAUNCHED("most_launched", R.string.playnite_most_launched),
    NEVER_LAUNCHED("never_launched", R.string.playnite_never_launched);

    final String preferenceValue;
    final int label;

    PlayniteLibraryFilter(String preferenceValue, int label) {
        this.preferenceValue = preferenceValue;
        this.label = label;
    }

    static PlayniteLibraryFilter fromPreference(String value) {
        for (PlayniteLibraryFilter filter : values()) {
            if (filter.preferenceValue.equals(value)) return filter;
        }
        return ALL;
    }
}
