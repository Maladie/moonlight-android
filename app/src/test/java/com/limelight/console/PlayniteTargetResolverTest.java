package com.limelight.console;

import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PlayniteTargetResolverTest {
    @Test public void onlyTheHostDesktopStreamShowsTheLocalizedLabel() {
        assertEquals("Pulpit", PlayniteTargetResolver.appDisplayName("Desktop", "Pulpit"));
        assertEquals("Desktop 2", PlayniteTargetResolver.appDisplayName("Desktop 2", "Pulpit"));
        assertNull(PlayniteTargetResolver.appDisplayName(null, "Pulpit"));
    }

    @Test public void gameCoverTitleIsTheLibraryNameNeverTheStreamTarget() {
        assertEquals("FEZ", PlayniteTargetResolver.gameCoverTitle(" FEZ ", "MoonWaker Stream"));
        assertEquals("", PlayniteTargetResolver.gameCoverTitle(
                "MoonWaker Stream", "MoonWaker Stream"));
        assertEquals("", PlayniteTargetResolver.gameCoverTitle("moonwaker  stream", ""));
        assertEquals("", PlayniteTargetResolver.gameCoverTitle("Desktop", "Desktop"));
        assertEquals("", PlayniteTargetResolver.gameCoverTitle(null, "MoonWaker Stream"));
    }

    @Test public void everyInstalledGameMapsToTheNeutralStream() {
        NvApp neutral = app("MoonWaker Stream", 11,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID, "default");
        for (PlayniteLibraryGame game : Arrays.asList(game("Celeste"),
                providerGame("epic:Salt", "Celeste", "epic"))) {
            PlayniteDashboardItem item = PlayniteTargetResolver.resolve(game,
                    Arrays.asList(app("Celeste", 9, game.playniteGameId, "sha256:cover"),
                            app("Desktop", 8, "desktop", "default"), neutral), true);

            assertEquals(PlayniteDashboardItem.MappingState.MAPPED, item.mappingState);
            assertEquals(Integer.valueOf(11), item.sunshineAppId);
        }
    }

    @Test public void noOtherSunshineAppIsAGameLaunchTarget() {
        for (PlayniteLibraryGame game : Arrays.asList(game("My Game"),
                providerGame("steam:289070", "My Game", "steam"))) {
            PlayniteDashboardItem item = PlayniteTargetResolver.resolve(game,
                    Arrays.asList(new NvApp("Playnite", 7, false),
                            app("My Game", 9, game.playniteGameId, "sha256:cover"),
                            app("Desktop", 8, "desktop", "default"),
                            app("MoonWaker Stream", 12, "manual", "default")), true);

            assertEquals(PlayniteDashboardItem.MappingState.MISSING, item.mappingState);
            assertNull(item.sunshineAppId);
        }
    }

    @Test public void offlineHostKeepsTheGameLaunchableForPreflight() {
        PlayniteDashboardItem item = PlayniteTargetResolver.resolve(game("My Game"),
                Collections.emptyList(), false);

        assertEquals(PlayniteDashboardItem.MappingState.MAPPED, item.mappingState);
        assertNull(item.sunshineAppId);
    }

    @Test public void notInstalledGameHasNoTarget() {
        PlayniteLibraryGame game = new PlayniteLibraryGame(
                "11223344-5566-7788-99aa-bbccddeeff00", "My Game",
                false, false, 0, "", "", "", "Playnite");

        PlayniteDashboardItem item = PlayniteTargetResolver.resolve(game,
                Collections.singletonList(app("MoonWaker Stream", 11,
                        PlayniteTargetResolver.MOONWAKER_STREAM_UUID, "default")), true);

        assertEquals(PlayniteDashboardItem.MappingState.NOT_INSTALLED, item.mappingState);
        assertNull(item.sunshineAppId);
    }

    @Test public void duplicateNeutralStreamsWithTheSameUuidResolveToTheEnrichedOne() {
        NvApp plain = app("MoonWaker Stream", 11,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID, "default");
        NvApp enriched = app(" moonwaker   stream ", 12,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID.toUpperCase(Locale.ROOT),
                "sha256:cover");

        assertEquals(12, PlayniteTargetResolver.resolveNeutralStream(
                Arrays.asList(plain, enriched)).getAppId());
        assertEquals(12, PlayniteTargetResolver.resolveNeutralStream(
                Arrays.asList(enriched, plain)).getAppId());
    }

    @Test public void sameNamedStreamsWithDifferentUuidsAreNotANeutralTarget() {
        NvApp neutral = app("MoonWaker Stream", 11,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID, "sha256:cover");
        NvApp collision = app("MoonWaker Stream", 12, "manual", "default");

        assertNull(PlayniteTargetResolver.resolveNeutralStream(
                Arrays.asList(neutral, collision)));
        assertNull(PlayniteTargetResolver.resolveNeutralStream(
                Arrays.asList(collision, neutral)));
    }

    @Test public void manualNameCollisionIsNotAValidNeutralTarget() {
        NvApp collision = app("MoonWaker Stream", 11, "manual", "default");

        assertNull(PlayniteTargetResolver.resolveNeutralStream(
                Collections.singletonList(collision)));
    }

    @Test public void playniteUuidRemainsArtworkIdentityWithoutSessionGameId() {
        NvApp target = app("Hollow Knight", 2141160162,
                "28EF7397-541C-4870-9BA3-1D34737F5414", "sha256:cover");

        assertEquals("28ef7397-541c-4870-9ba3-1d34737f5414",
                PlayniteTargetResolver.playniteGameId(target));
    }

    @Test public void nonPlayniteUuidIsNotUsedAsArtworkIdentity() {
        assertEquals("", PlayniteTargetResolver.playniteGameId(
                app("Desktop", 1, "not-a-playnite-id", "default")));
    }

    @Test public void installationPrefersNeutralDesktopOverPlaynite() {
        NvApp target = PlayniteTargetResolver.resolveInstallationStream(
                Arrays.asList(new NvApp("Playnite", 7, false),
                        new NvApp("Desktop", 8, false)));

        assertNotNull(target);
        assertEquals(8, target.getAppId());
    }

    @Test public void installationFallsBackToPlayniteWithoutDesktop() {
        NvApp target = PlayniteTargetResolver.resolveInstallationStream(
                Collections.singletonList(new NvApp("Playnite", 7, false)));

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
                true, false, 0, "", "", "", "Playnite");
    }

    private static PlayniteLibraryGame providerGame(String id, String name, String provider) {
        String providerGameId = id.substring(id.indexOf(':') + 1);
        return new PlayniteLibraryGame(id, name, true, false, false, 0, "", "", "",
                "", 0, provider, "", false, "", "", "", "", -1, false,
                provider, providerGameId, "11223344-5566-7788-99aa-bbccddeeff00",
                provider, provider, true, true, true, false);
    }
}
