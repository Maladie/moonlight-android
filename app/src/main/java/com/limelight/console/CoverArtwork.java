package com.limelight.console;

import java.io.File;

/**
 * A cover's artwork: the very file the carousel shows as the game's backdrop
 * ({@link PlayniteArtworkSpec#forBackdrop} in {@link PlayniteArtworkCache}), only when it is the
 * game's full background; never the portrait cover fallback. The game comes from the profile's
 * whole library, not from the filtered list a Home happens to have rendered.
 */
final class CoverArtwork {
    enum Outcome { FOUND, NO_ITEM, NO_BACKGROUND, CACHE_MISS }

    final Outcome outcome;
    /** The cached background; set only when FOUND. */
    final File file;

    private CoverArtwork(Outcome outcome, File file) {
        this.outcome = outcome;
        this.file = file;
    }

    /** {@code game}: the library's entry for the cover's game, or null when it lists none. */
    static CoverArtwork find(PlayniteArtworkCache cache, HostProfileKey key,
                             PlayniteLibraryGame game) {
        if (key == null || game == null) return new CoverArtwork(Outcome.NO_ITEM, null);
        PlayniteArtworkSpec spec = PlayniteArtworkSpec.forBackdrop(game);
        if (!"background".equals(spec.kind)) return new CoverArtwork(Outcome.NO_BACKGROUND, null);
        File file = cache.get(key, game.playniteGameId, spec.kind, spec.version);
        return file != null && file.isFile() && file.length() > 0
                ? new CoverArtwork(Outcome.FOUND, file)
                : new CoverArtwork(Outcome.CACHE_MISS, null);
    }
}
