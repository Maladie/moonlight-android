package com.limelight.console;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A cover's artwork: the very file the carousel shows as the game's backdrop
 * ({@link PlayniteArtworkSpec#forBackdrop} in {@link PlayniteArtworkCache}), only when it is the
 * game's full background; never the portrait cover fallback. The game comes from the profile's
 * whole library, not from the filtered list a Home happens to have rendered.
 */
final class CoverArtwork {
    enum Outcome { FOUND, NO_ITEM, NO_BACKGROUND, CACHE_MISS }

    /**
     * How long a launch waits for its game's background that is not cached yet before it
     * starts on the standard cover: long enough for one artwork request over the LAN.
     */
    static final long FETCH_WAIT_MS = 1_500L;

    interface Delay {
        void post(Runnable action, long delayMillis);
    }

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

    /**
     * Decides a CACHE_MISS once: {@code decided} runs once on {@code ui} with the fetched file
     * when {@code fetch} lands within {@link #FETCH_WAIT_MS}, else with null (the standard
     * cover). A fetch failing, or landing after that answer, changes nothing on screen; a late
     * file only stays cached for the next launch. {@code delay} posts on the same thread as
     * {@code ui}. {@code current} is read on {@code ui}: once the Play that asked is no longer
     * current (cancelled, or another Play took the cover), {@code decided} never runs.
     */
    static void fetchWithin(Callable<File> fetch, Executor background, Executor ui, Delay delay,
                            BooleanSupplier current, Consumer<File> decided) {
        boolean[] answered = {false};
        Consumer<File> once = file -> {
            if (answered[0] || !current.getAsBoolean()) return;
            answered[0] = true;
            decided.accept(file);
        };
        delay.post(() -> once.accept(null), FETCH_WAIT_MS);
        background.execute(() -> {
            File fetched;
            try {
                fetched = fetch.call();
            } catch (Exception failed) {
                fetched = null;
            }
            File result = fetched;
            ui.execute(() -> once.accept(result));
        });
    }
}
