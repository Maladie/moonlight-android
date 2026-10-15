package com.limelight.console;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** TV 2026-10-15: the cover shows exactly the file the carousel shows as the game's backdrop. */
public class CoverArtworkTest {
    private static final String GAME_ID = "1b2bc7e6-7341-444a-86af-a8db35f4aa01";

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    /**
     * The carousel cached the background under the lowercase host directory of the selected
     * profile; the launch carries the uppercase host id and its own spelling of the game id, and
     * the Home's rendered (filtered) list does not hold the game: the profile's library does.
     */
    @Test public void coverFindsTheCarouselBackdropDespiteCaseAndRenderedListMismatch()
            throws IOException {
        PlayniteArtworkCache cache = new PlayniteArtworkCache(folder.getRoot());
        PlayniteLibraryGame game = game("cover-v1", "background-v7");
        HostProfileKey carousel = new HostProfileKey(
                "6dec648a-3ebd-7f05-101f-269fa8db35f4", "p-1b2bc7e67341444686afa8b35bfc55bc");
        File backdrop = cache.get(carousel, game.playniteGameId, "background", "background-v7");
        backdrop.getParentFile().mkdirs();
        Files.write(backdrop.toPath(), new byte[]{1, 2, 3});
        assertEquals("6dec648a-3ebd-7f05-101f-269fa8db35f4__p-1b2bc7e67341444686afa8b35bfc55bc",
                backdrop.getParentFile().getName());

        List<PlayniteLibraryGame> library = Arrays.asList(game("c", "b", OTHER_ID), game);
        CoverArtwork found = CoverArtwork.find(cache,
                new HostProfileKey("6DEC648A-3EBD-7F05-101F-269FA8DB35F4",
                        "p-1b2bc7e67341444686afa8b35bfc55bc"),
                ConsoleActivity.findProviderGame(library, GAME_ID.toUpperCase()));

        assertEquals(CoverArtwork.Outcome.FOUND, found.outcome);
        assertEquals(backdrop, found.file);
    }

    @Test public void eachMissingLinkHasItsOwnOutcome() throws IOException {
        PlayniteArtworkCache cache = new PlayniteArtworkCache(folder.getRoot());
        HostProfileKey key = new HostProfileKey("host", "p-1");

        assertEquals(CoverArtwork.Outcome.NO_ITEM, CoverArtwork.find(cache, key,
                ConsoleActivity.findProviderGame(Collections.emptyList(), GAME_ID)).outcome);
        // A cover-only game: the portrait fallback never becomes a full-screen cover.
        assertEquals(CoverArtwork.Outcome.NO_BACKGROUND,
                CoverArtwork.find(cache, key, game("cover-v1", "")).outcome);
        CoverArtwork miss = CoverArtwork.find(cache, key, game("cover-v1", "background-v7"));
        assertEquals(CoverArtwork.Outcome.CACHE_MISS, miss.outcome);
        assertNull(miss.file);
        // Another profile's cached background is not this profile's.
        File other = cache.get(new HostProfileKey("host", "p-2"), GAME_ID, "background",
                "background-v7");
        other.getParentFile().mkdirs();
        Files.write(other.toPath(), new byte[]{1});
        assertEquals(CoverArtwork.Outcome.CACHE_MISS,
                CoverArtwork.find(cache, key, game("cover-v1", "background-v7")).outcome);
    }

    private static final String OTHER_ID = "00000001-0000-0000-0000-000000000000";

    private static PlayniteLibraryGame game(String cover, String background) {
        return game(cover, background, GAME_ID);
    }

    private static PlayniteLibraryGame game(String cover, String background, String id) {
        return new PlayniteLibraryGame(id, "Game", true, false, 0, "", cover, background,
                "Playnite");
    }
}
