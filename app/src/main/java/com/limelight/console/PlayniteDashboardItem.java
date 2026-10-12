package com.limelight.console;

import java.util.Objects;

/** Joins library metadata with an optional, existing Sunshine launch target. */
final class PlayniteDashboardItem {
    enum MappingState { MAPPED, MISSING, NOT_INSTALLED }

    final PlayniteLibraryGame game;
    final Integer sunshineAppId;
    final MappingState mappingState;

    PlayniteDashboardItem(PlayniteLibraryGame game, Integer sunshineAppId,
                          MappingState mappingState) {
        this.game = game;
        this.sunshineAppId = sunshineAppId;
        this.mappingState = mappingState;
    }

    String stableId() { return game.playniteGameId; }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof PlayniteDashboardItem)) return false;
        PlayniteDashboardItem item = (PlayniteDashboardItem) value;
        return game.equals(item.game) && Objects.equals(sunshineAppId, item.sunshineAppId) &&
                mappingState == item.mappingState;
    }

    @Override public int hashCode() {
        return Objects.hash(game, sunshineAppId, mappingState);
    }
}
