package com.limelight.console;

import java.util.Objects;

/** Selects artwork that matches the landscape TV card without losing a cover fallback. */
final class PlayniteArtworkSpec {
    final String kind;
    final String version;
    final String fallbackKind;
    final String fallbackVersion;

    private PlayniteArtworkSpec(String kind, String version,
                                String fallbackKind, String fallbackVersion) {
        this.kind = kind;
        this.version = version;
        this.fallbackKind = fallbackKind;
        this.fallbackVersion = fallbackVersion;
    }

    static PlayniteArtworkSpec forGame(PlayniteLibraryGame game) {
        return forBackdrop(game);
    }

    static PlayniteArtworkSpec forBackdrop(PlayniteLibraryGame game) {
        Objects.requireNonNull(game, "game");
        if (!game.backgroundKey.isEmpty()) {
            return new PlayniteArtworkSpec("background", game.backgroundKey,
                    game.coverKey.isEmpty() ? "" : "cover", game.coverKey);
        }
        if (!game.coverKey.isEmpty()) {
            return new PlayniteArtworkSpec("cover", game.coverKey, "", "");
        }
        return new PlayniteArtworkSpec("", "", "", "");
    }

    static PlayniteArtworkSpec forScreenSaver(PlayniteLibraryGame game) {
        Objects.requireNonNull(game, "game");
        return game.backgroundKey.isEmpty()
                ? new PlayniteArtworkSpec("", "", "", "")
                : new PlayniteArtworkSpec("background", game.backgroundKey, "", "");
    }

    static PlayniteArtworkSpec forCard(PlayniteLibraryGame game) {
        Objects.requireNonNull(game, "game");
        if (!game.coverKey.isEmpty()) {
            return new PlayniteArtworkSpec("cover", game.coverKey,
                    game.backgroundKey.isEmpty() ? "" : "background", game.backgroundKey);
        }
        if (!game.backgroundKey.isEmpty()) {
            return new PlayniteArtworkSpec("background", game.backgroundKey, "", "");
        }
        return new PlayniteArtworkSpec("", "", "", "");
    }

    boolean available() {
        return !kind.isEmpty() && !version.isEmpty();
    }

    boolean hasFallback() {
        return !fallbackKind.isEmpty() && !fallbackVersion.isEmpty();
    }

    String cacheIdentity() {
        return available() ? kind + ":" + version : "none";
    }
}
