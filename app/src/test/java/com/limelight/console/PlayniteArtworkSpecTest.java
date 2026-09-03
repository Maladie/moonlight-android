package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayniteArtworkSpecTest {
    @Test public void landscapeBackgroundIsPreferredWithCoverFallback() {
        PlayniteArtworkSpec spec = PlayniteArtworkSpec.forBackdrop(game("cover", "background"));

        assertEquals("background", spec.kind);
        assertEquals("background", spec.version);
        assertEquals("cover", spec.fallbackKind);
        assertEquals("cover", spec.fallbackVersion);
        assertTrue(spec.hasFallback());
    }

    @Test public void coverIsUsedWhenBackgroundIsMissing() {
        PlayniteArtworkSpec spec = PlayniteArtworkSpec.forBackdrop(game("cover", ""));

        assertEquals("cover", spec.kind);
        assertEquals("cover", spec.version);
        assertFalse(spec.hasFallback());
    }

    @Test public void missingArtworkProducesUnavailableSpec() {
        assertFalse(PlayniteArtworkSpec.forGame(game("", "")).available());
    }

    @Test public void portraitCoverIsPreferredForCards() {
        PlayniteArtworkSpec spec = PlayniteArtworkSpec.forCard(game("cover", "background"));

        assertEquals("cover", spec.kind);
        assertEquals("cover", spec.version);
        assertEquals("background", spec.fallbackKind);
        assertEquals("background", spec.fallbackVersion);
        assertTrue(spec.hasFallback());
    }

    @Test public void screenSaverNeverFallsBackToCover() {
        PlayniteArtworkSpec spec = PlayniteArtworkSpec.forScreenSaver(
                game("cover", "background"));

        assertEquals("background", spec.kind);
        assertEquals("background", spec.version);
        assertFalse(spec.hasFallback());
        assertFalse(PlayniteArtworkSpec.forScreenSaver(game("cover", "")).available());
    }

    @Test public void loadingCurtainPrefersDedicatedHeroAndFallsBackToBackground() {
        PlayniteArtworkSpec spec = PlayniteArtworkSpec.forLoadingCurtain(
                game("cover", "background", "hero"));

        assertEquals("hero", spec.kind);
        assertEquals("hero", spec.version);
        assertEquals("background", spec.fallbackKind);
        assertEquals("background", spec.fallbackVersion);
        assertFalse(PlayniteArtworkSpec.forLoadingCurtain(game("cover", "", ""))
                .available());
    }

    private static PlayniteLibraryGame game(String cover, String background) {
        return new PlayniteLibraryGame("00000001-0000-0000-0000-000000000000",
                "Game", true, false, 0, "", cover, background, "Playnite");
    }

    private static PlayniteLibraryGame game(String cover, String background, String hero) {
        return new PlayniteLibraryGame("steam:620", "Game", true, false, false,
                0, "", cover, background, hero, "", 0, "Steam", "", false,
                "", "", "", "", -1, false, "", "steam", "620", "",
                "steam", "Steam", true, true, true, false, "neutral", false);
    }
}
