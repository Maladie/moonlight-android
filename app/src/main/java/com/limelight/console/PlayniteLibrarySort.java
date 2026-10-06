package com.limelight.console;

import com.limelight.R;

enum PlayniteLibrarySort {
    RECENT("recent", R.string.playnite_sort_recent),
    NAME("name", R.string.playnite_sort_name),
    LIBRARY("library", R.string.playnite_sort_library),
    GENRE("genre", R.string.playnite_sort_genre),
    PLAYTIME("playtime", R.string.playnite_sort_playtime),
    MOST_LAUNCHED("most_launched", R.string.playnite_sort_most_launched);

    final String preferenceValue;
    final int label;

    PlayniteLibrarySort(String preferenceValue, int label) {
        this.preferenceValue = preferenceValue;
        this.label = label;
    }

    static PlayniteLibrarySort fromPreference(String value) {
        if (value != null) {
            for (PlayniteLibrarySort sort : values()) {
                if (sort.preferenceValue.equals(value)) return sort;
            }
        }
        return RECENT;
    }
}
