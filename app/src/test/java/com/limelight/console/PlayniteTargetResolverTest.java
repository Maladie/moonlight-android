package com.limelight.console;

import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PlayniteTargetResolverTest {
    @Test public void missingGameTargetRemainsUnconfiguredEvenWithPlayniteFallback() {
        PlayniteLibraryGame game = game("My Game");

        PlayniteDashboardItem item = PlayniteTargetResolver.resolve("host", game,
                Collections.singletonList(new NvApp("Playnite", 7, false)), null);

        assertEquals(PlayniteDashboardItem.MappingState.MISSING,
                item.mappingState);
        assertNull(item.sunshineAppId);
    }

    @Test public void synchronizedGameAlwaysWinsOverPlayniteFallback() {
        PlayniteDashboardItem item = PlayniteTargetResolver.resolve("host", game("My Game"),
                Arrays.asList(new NvApp("Playnite", 7, false),
                        new NvApp("My Game", 9, false)), null);

        assertEquals(PlayniteDashboardItem.MappingState.MAPPED, item.mappingState);
        assertEquals(Integer.valueOf(9), item.sunshineAppId);
    }

    @Test public void duplicateExactNamesAreNotSelected() {
        assertNull(PlayniteTargetResolver.findUniqueExactName(Arrays.asList(
                new NvApp("My Game", 1, false), new NvApp(" my   game ", 2, false)),
                "My Game"));
    }

    @Test public void duplicateGamestreamRowsWithSameUuidAreOnePlayableTarget() {
        NvApp transientRow = app("My Game", 1,
                "11223344-5566-7788-99aa-bbccddeeff00", "default");
        NvApp enrichedRow = app("My Game", 2,
                "11223344-5566-7788-99aa-bbccddeeff00", "sha256:cover");

        PlayniteDashboardItem item = PlayniteTargetResolver.resolve("host", game("My Game"),
                Arrays.asList(new NvApp("Playnite", 7, false), transientRow, enrichedRow), null);

        assertEquals(PlayniteDashboardItem.MappingState.MAPPED, item.mappingState);
        assertEquals(Integer.valueOf(2), item.sunshineAppId);
    }

    @Test public void offlineMappedGameCreatesLaunchTargetFromPersistedIdentity() {
        PlayniteDashboardItem item = new PlayniteDashboardItem(game("My Game"), 42,
                "My Game", PlayniteDashboardItem.MappingState.MAPPED);

        NvApp target = PlayniteTargetResolver.launchTarget(item,
                Collections.emptyList(), false);

        assertNotNull(target);
        assertEquals(42, target.getAppId());
        assertEquals("My Game", target.getAppName());
    }

    @Test public void onlineMissingMappingDoesNotCreatePlaceholderTarget() {
        PlayniteDashboardItem item = new PlayniteDashboardItem(game("My Game"), 42,
                "My Game", PlayniteDashboardItem.MappingState.MAPPED);

        assertNull(PlayniteTargetResolver.launchTarget(item,
                Collections.emptyList(), true));
    }

    @Test public void installationPrefersNeutralDesktopOverPlaynite() {
        NvApp target = PlayniteTargetResolver.resolveInstallationStream("host",
                Arrays.asList(new NvApp("Playnite", 7, false),
                        new NvApp("Desktop", 8, false)), null);

        assertNotNull(target);
        assertEquals(8, target.getAppId());
    }

    @Test public void installationFallsBackToPlayniteWithoutDesktop() {
        NvApp target = PlayniteTargetResolver.resolveInstallationStream("host",
                Collections.singletonList(new NvApp("Playnite", 7, false)), null);

        assertNotNull(target);
        assertEquals(7, target.getAppId());
    }

    private static NvApp app(String name, int id, String uuid, String artVersion) {
        NvApp app = new NvApp(name, id, false);
        app.setAppUuid(uuid);
        app.setArtVersion(artVersion);
        return app;
    }

    private static PlayniteLibraryGame game(String name) {
        return new PlayniteLibraryGame("11223344-5566-7788-99aa-bbccddeeff00", name,
                true, false, 0, "", "", "", "Steam");
    }
}
