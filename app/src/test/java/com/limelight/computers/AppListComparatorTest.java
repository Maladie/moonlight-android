package com.limelight.computers;

import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppListComparatorTest {
    @Test public void identicalVisibleMetadataIsUnchanged() {
        assertTrue(AppListComparator.same(
                Collections.singletonList(app("Game", 7, "uuid", "art", true)),
                Collections.singletonList(app("Game", 7, "uuid", "art", true))));
    }

    @Test public void orderAndVisibleMetadataChangesAreDetected() {
        NvApp first = app("First", 1, "one", "art-1", false);
        NvApp second = app("Second", 2, "two", "art-2", true);

        assertFalse(AppListComparator.same(
                Arrays.asList(first, second), Arrays.asList(second, first)));
        assertFalse(AppListComparator.same(
                Collections.singletonList(first),
                Collections.singletonList(app("Renamed", 1, "one", "art-1", false))));
        assertFalse(AppListComparator.same(
                Collections.singletonList(first),
                Collections.singletonList(app("First", 1, "one", "art-new", false))));
    }

    private static NvApp app(String name, int id, String uuid, String artVersion,
                             boolean hdr) {
        NvApp app = new NvApp(name, id, hdr);
        app.setAppUuid(uuid);
        app.setArtVersion(artVersion);
        return app;
    }
}
