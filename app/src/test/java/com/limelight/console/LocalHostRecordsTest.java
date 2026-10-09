package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.session.MemoryPreferences;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

public class LocalHostRecordsTest {
    private static final String HOST = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER = "99999999-8888-7777-6666-555555555555";

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test public void sweepRemovesEveryKeyNamingTheHostAndTheLastHostPointer() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.edit()
                .putString("selected_host", HOST)
                .putString("auto_login_host", HOST.toUpperCase())
                .putString("selected_playnite." + HOST, "game")
                .putInt("app_scroll." + HOST, 3)
                .putStringSet("playnite_hidden." + HOST, Collections.singleton("g"))
                .putBoolean("playnite_install_pending." + HOST + ":g", true)
                .putString(HOST + ":7", "{}")
                .putString(HOST.toLowerCase() + ".game.g", "x")
                .putString("selected_playnite." + OTHER, "game")
                .putString("theme", "dark")
                .apply();

        assertTrue(LocalHostRecords.mentions(preferences, HOST));
        LocalHostRecords.sweep(preferences, HOST);

        Map<String, ?> left = preferences.getAll();
        assertEquals(2, left.size());
        assertTrue(left.containsKey("selected_playnite." + OTHER));
        assertTrue(left.containsKey("theme"));
        assertFalse(LocalHostRecords.mentions(preferences, HOST));
    }

    @Test public void profileCachesOfOnlyThatHostAreDeleted() throws IOException {
        File root = folder.getRoot();
        File artwork = new File(root, HOST + "__default");
        assertTrue(artwork.mkdirs());
        assertTrue(new File(artwork, "g_cover.img").createNewFile());
        assertTrue(new File(root, HOST + "__child.json").createNewFile());
        File other = new File(root, OTHER + "__default.json");
        assertTrue(other.createNewFile());

        LocalHostRecords.deleteProfileEntries(root, HOST.toUpperCase());

        assertFalse(artwork.exists());
        assertFalse(new File(root, HOST + "__child.json").exists());
        assertTrue(other.exists());
    }
}
