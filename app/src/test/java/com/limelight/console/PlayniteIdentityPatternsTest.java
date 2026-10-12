package com.limelight.console;

import android.content.ContextWrapper;

import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pins the accepted and rejected inputs of the identity checks on the library read path. */
public class PlayniteIdentityPatternsTest {
    private static final String GUID = "11223344-5566-7788-99aa-bbccddeeff00";

    @Test public void playniteIdIsAGuidOrAProviderScopedId() {
        assertTrue(HostGatewayClient.isPlayniteId(GUID));
        assertTrue(HostGatewayClient.isPlayniteId(GUID.toUpperCase()));
        assertTrue(HostGatewayClient.isPlayniteId("steam:42"));
        assertTrue(HostGatewayClient.isPlayniteId("gog-2_x:A.b_c-9"));
        assertTrue(HostGatewayClient.isPlayniteId("ab:" + repeat('x', 128)));

        assertFalse(HostGatewayClient.isPlayniteId(null));
        assertFalse(HostGatewayClient.isPlayniteId(""));
        assertFalse(HostGatewayClient.isPlayniteId(GUID + "\n"));
        assertFalse(HostGatewayClient.isPlayniteId(" " + GUID));
        assertFalse(HostGatewayClient.isPlayniteId("playnite:" + GUID.replace('-', '/')));
        assertFalse(HostGatewayClient.isPlayniteId(GUID.replace('a', 'g')));
        assertFalse(HostGatewayClient.isPlayniteId("Steam:42"));
        assertFalse(HostGatewayClient.isPlayniteId("s:42"));
        assertFalse(HostGatewayClient.isPlayniteId("steam:"));
        assertFalse(HostGatewayClient.isPlayniteId("steam:4 2"));
        assertFalse(HostGatewayClient.isPlayniteId("steam:42\n"));
        assertFalse(HostGatewayClient.isPlayniteId("ab:" + repeat('x', 129)));
        assertFalse(HostGatewayClient.isPlayniteId(repeat('a', 33) + ":1"));
    }

    @Test public void providerFallsBackToTheSourceAndThenToPlaynite() {
        assertEquals("steam", HostGatewayClient.providerOrLegacy(" Steam ", "gog"));
        assertEquals("gog", HostGatewayClient.providerOrLegacy("", " GOG "));
        assertEquals("epic", HostGatewayClient.providerOrLegacy("x", "epic"));
        assertEquals("gog", HostGatewayClient.providerOrLegacy("bad provider", "gog"));
        assertEquals("playnite", HostGatewayClient.providerOrLegacy("9steam", "also bad!"));
        assertEquals("playnite", HostGatewayClient.providerOrLegacy(null, null));
        assertEquals("playnite",
                HostGatewayClient.providerOrLegacy(repeat('a', 33), "steam\nx"));
    }

    @Test public void exactProviderIdentityNeverLoosens() {
        assertTrue(HostGatewayClient.hasExactProviderIdentity(GUID, "playnite", ""));
        assertTrue(HostGatewayClient.hasExactProviderIdentity(
                " PLAYNITE:" + GUID.toUpperCase() + " ", " Playnite ", null));
        assertFalse(HostGatewayClient.hasExactProviderIdentity("playnite:42", "playnite", "42"));
        assertFalse(HostGatewayClient.hasExactProviderIdentity("x" + GUID, "playnite", ""));
        assertFalse(HostGatewayClient.hasExactProviderIdentity(null, "playnite", ""));

        assertTrue(HostGatewayClient.hasExactProviderIdentity("steam:42", " Steam ", " 42 "));
        assertFalse(HostGatewayClient.hasExactProviderIdentity("steam:42", "steam", ""));
        assertFalse(HostGatewayClient.hasExactProviderIdentity("steam:42", "gog", "42"));
        assertFalse(HostGatewayClient.hasExactProviderIdentity("Steam:42", "steam", "42"));
        assertFalse(HostGatewayClient.hasExactProviderIdentity(GUID, null, null));
    }

    @Test public void appNamesMatchAcrossCaseAndAsciiWhitespaceRunsOnly() {
        List<NvApp> apps = Collections.singletonList(new NvApp("My \t\r\n Game", 9, false));
        assertNotNull(PlayniteTargetResolver.findPlayableExactName(apps, "  my game "));
        assertNull(PlayniteTargetResolver.findPlayableExactName(apps, "mygame"));
        assertNull(PlayniteTargetResolver.findPlayableExactName(apps, "my00a0game"));
        assertNull(PlayniteTargetResolver.findPlayableExactName(apps, null));
    }

    @Test public void gameBuiltFromASourceDerivesProviderAndGuidIdentity() {
        assertEquals("gog", game(GUID, " GOG ", "").provider);
        assertEquals("playnite", game(GUID, "Epic Games", "").provider);
        assertEquals("playnite", game(GUID, "x", "").provider);
        assertEquals("playnite", game(GUID, "steam\n1", "").provider);
        assertEquals(GUID, game(GUID, "", "").metadataPlayniteGameId);
        assertEquals("", game("steam:42", "", "").metadataPlayniteGameId);
        assertEquals("", game(GUID + "0", "", "").metadataPlayniteGameId);
        assertEquals("", game(repeat('-', 36), "", "").metadataPlayniteGameId);
    }

    @Test public void descriptionCollapsesThreeOrMoreBlankLineBreaks() {
        assertEquals("a\n\nb", description("a\n\n\n\nb"));
        assertEquals("a\n\nb", description("  a\r\n \r\n\t\r\nb  "));
        assertEquals("a\n\nb", description("a\n\nb"));
        assertEquals("a\r\n\r\nb", description("a\r\n\r\nb"));
        assertEquals("", description(null));
    }

    @Test public void cachedGameWithoutMetadataIdDerivesItOnlyFromAGuidShapedId()
            throws Exception {
        File files = Files.createTempDirectory("playnite-cache-test").toFile();
        File directory = new File(files, "playnite-library");
        assertTrue(directory.mkdirs());
        HostProfileKey key = new HostProfileKey("pattern-test-host", "default");
        String json = "{\"games\":["
                + "{\"id\":\"" + GUID.toUpperCase() + "\",\"name\":\"A\"},"
                + "{\"id\":\"steam:42\",\"name\":\"B\",\"provider\":\"steam\","
                + "\"provider_game_id\":\"42\"},"
                + "{\"id\":\"steam:" + repeat('a', 30) + "\",\"name\":\"C\"},"
                + "{\"id\":\"" + repeat('-', 36) + "\",\"name\":\"D\"}]}";
        Files.write(new File(directory, key.cacheKey() + ".json").toPath(),
                json.getBytes(StandardCharsets.UTF_8));

        PlayniteLibraryCache.Entry entry = new PlayniteLibraryCache(new ContextWrapper(null) {
            @Override public File getFilesDir() { return files; }
        }).read(key);

        assertEquals(3, entry.games.size());
        assertEquals(GUID, entry.games.get(0).metadataPlayniteGameId);
        assertEquals("", entry.games.get(1).metadataPlayniteGameId);
        assertEquals("", entry.games.get(2).metadataPlayniteGameId);
    }

    private static String description(String value) {
        return game(GUID, "Playnite", value).description;
    }

    private static PlayniteLibraryGame game(String id, String source, String description) {
        return new PlayniteLibraryGame(id, "Game", true, false, 0, "", "", "", description,
                source);
    }

    private static String repeat(char value, int count) {
        return new String(new char[count]).replace('\0', value);
    }
}
