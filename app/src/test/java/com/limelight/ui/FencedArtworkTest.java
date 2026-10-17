package com.limelight.ui;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The cover's artwork: cache hit at once, any decode fenced by the latest request. */
public class FencedArtworkTest {
    private final FencedArtwork.Cache<String> cache = new FencedArtwork.Cache<>();
    private final Queue<Runnable> decodes = new ArrayDeque<>();
    private final Queue<Runnable> ui = new ArrayDeque<>();
    private final List<String> decoded = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private final FencedArtwork<String> artwork = new FencedArtwork<>(cache,
            path -> {
                decoded.add(path);
                return path.startsWith("broken") ? null : "bitmap:" + path;
            },
            queue(decodes), queue(ui), shown::add);

    private static Executor queue(Queue<Runnable> tasks) {
        return tasks::add;
    }

    private static void drain(Queue<Runnable> tasks) {
        while (!tasks.isEmpty()) tasks.remove().run();
    }

    /** A launch deciding its artwork: plain ground (pending), and a decode from before never paints. */
    @Test public void heldArtworkIsPendingUntilTheDecidedRequest() {
        artwork.request("/art/old.png");
        artwork.hold();
        assertTrue(artwork.pending());
        assertEquals("", artwork.requested());
        drain(decodes);
        drain(ui);
        assertEquals(Arrays.asList(null, null), shown);

        artwork.request("");
        assertEquals("the standard cover once decided", false, artwork.pending());
        artwork.hold();
        artwork.request("/art/fez.png");
        drain(decodes);
        drain(ui);
        assertEquals("/art/fez.png", artwork.requested());
        assertEquals(Arrays.asList(null, null, null, null, null, "bitmap:/art/fez.png"), shown);
    }

    /** TV 2026-10-15: another game's cached artwork never serves a different request. */
    @Test public void theCacheServesOnlyItsOwnPath() {
        cache.put("/art/hollow-knight.png", "bitmap:/art/hollow-knight.png");

        artwork.request("/art/fez.png");
        drain(decodes);
        drain(ui);
        artwork.request("");

        assertEquals(Arrays.asList(null, "bitmap:/art/fez.png", null), shown);
    }

    @Test public void cachedArtworkShowsSynchronouslyWithoutADecode() {
        cache.put("/art/a.png", "bitmap:/art/a.png");

        artwork.request("/art/a.png");

        assertEquals(Collections.singletonList("bitmap:/art/a.png"), shown);
        assertTrue(decodes.isEmpty());
    }

    @Test public void aLateDecodeOfAnOlderTransitionNeverPaints() {
        artwork.request("/art/a.png");
        artwork.request("/art/b.png");
        // Nothing waits: both requests cleared the old artwork and returned at once.
        assertEquals(Arrays.asList(null, null), shown);

        drain(decodes);
        drain(ui);

        assertEquals(Arrays.asList(null, null, "bitmap:/art/b.png"), shown);
        // The shown one is the next cover's at once.
        assertEquals(Arrays.asList("/art/a.png", "/art/b.png"), decoded);
        assertEquals("bitmap:/art/b.png", cache.get("/art/b.png"));
    }

    @Test public void theSameTransitionBackToItsOwnArtworkDropsTheSwitchDecode() {
        artwork.request("/art/a.png");
        drain(decodes);
        drain(ui);
        artwork.request("/art/b.png");
        artwork.request("/art/a.png");
        drain(decodes);
        drain(ui);

        assertEquals(Arrays.asList(null, "bitmap:/art/a.png", null, "bitmap:/art/a.png"),
                shown);
    }

    @Test public void aStoppedCoverNeverPaintsAndARepeatedRequestDecodesOnce() {
        artwork.request("/art/a.png");
        artwork.request("/art/a.png");
        assertEquals(1, decodes.size());
        drain(decodes);
        artwork.stop();
        drain(ui);
        artwork.request("/art/c.png");

        assertEquals(Collections.singletonList(null), shown);
        assertTrue(decodes.isEmpty());
        // Decoded all the same: the next window shows it at once.
        assertEquals("bitmap:/art/a.png", cache.get("/art/a.png"));
    }

    @Test public void aSwitchCoverAndItsNewTransitionAskingForTheSameArtworkDecodeItOnce() {
        // The switch's cover asks first; its new transition asks again for the same path.
        artwork.request("/art/b.png");
        artwork.request("/art/b.png");
        drain(decodes);
        drain(ui);

        assertEquals(Collections.singletonList("/art/b.png"), decoded);
        // Cleared once (the previous game's art), then B: no blank flash in between.
        assertEquals(Arrays.asList(null, "bitmap:/art/b.png"), shown);
    }

    /**
     * TV 2026-10-15 handoff flash: the console cover is stopped (its Activity paused) before its
     * decode's UI post runs; the stream screen's cover must still get that artwork at once.
     */
    @Test public void aDecodeFinishedAfterItsCoverStoppedServesTheNextCoverSynchronously() {
        artwork.request("/art/a.png");
        artwork.stop();
        drain(decodes);
        ui.clear(); // The paused window's handler drops the post.
        List<String> next = new ArrayList<>();
        new FencedArtwork<>(cache, path -> { throw new AssertionError("decoded again"); },
                queue(decodes), queue(ui), next::add).request("/art/a.png");

        assertEquals(Collections.singletonList("bitmap:/art/a.png"), next);
        assertTrue(decodes.isEmpty());
    }

    /** The artwork on screen stays when its path is asked again after the cache moved on. */
    @Test public void askingAgainForTheShownArtworkNeverClearsIt() {
        artwork.request("/art/a.png");
        drain(decodes);
        drain(ui);
        cache.put("/art/other.png", "bitmap:/art/other.png");

        artwork.request("/art/a.png");

        assertEquals(Arrays.asList(null, "bitmap:/art/a.png"), shown);
        assertTrue(decodes.isEmpty());
    }

    @Test public void emptyPathOrUnusableArtworkLeavesThePlainBackground() {
        artwork.request("broken.png");
        assertTrue("decoding: no plain background yet", artwork.pending());
        drain(decodes);
        drain(ui);
        assertTrue(!artwork.pending());
        artwork.request("  ");

        // Cleared at the request, the plain background once unusable, cleared again.
        assertEquals(Arrays.asList(null, null, null), shown);
        assertEquals(Collections.singletonList("broken.png"), decoded);
    }
}
