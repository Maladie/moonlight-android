package com.limelight.console;

enum PlayniteLibrarySort {
    RECENT("recent"),
    NAME("name"),
    LIBRARY("library"),
    GENRE("genre"),
    PLAYTIME("playtime"),
    MOST_LAUNCHED("most_launched");

    final String preferenceValue;

    PlayniteLibrarySort(String preferenceValue) {
        this.preferenceValue = preferenceValue;
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
