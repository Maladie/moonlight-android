package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleActivityLibraryPerformanceContractTest {
    @Test public void childHomeUsesTheSameProfileScopedCacheAndImmediateRefresh() throws IOException {
        String source = consoleActivitySource();
        String loading = source.substring(source.indexOf("private boolean restoreInitialLocalPresentationData("),
                source.indexOf("private void settleInitialLocalPresentation("));
        assertFalse(loading.contains("selectedProfileIsChild"));
        assertTrue(loading.contains("playniteLibraryRepository.cached(requestKey)"));
        String frame = source.substring(source.indexOf("protected final void completeInitialCarouselFrame()"),
                source.indexOf("static boolean initialLocalPresentationReady("));
        assertTrue(frame.contains("requestPlayniteRefresh(currentHost(selectedHostUuid), false)"));
    }

    @Test public void gridAndArtworkWorkStayBoundedToTheVisibleNeighborhood()
            throws IOException {
        String source = consoleActivitySource();

        String grid = source("ExpandedLibraryGrid.java");
        assertTrue(grid.contains("extends RecyclerView"));
        assertTrue(grid.contains("PREFETCH_ROWS = 2"));
        assertTrue(grid.contains("NAVIGATION_INTERVAL_MS = 70L"));
        assertTrue(source.contains("PLAYNITE_ARTWORK_PREFETCH_WORKERS = 2"));
        assertFalse(source.contains("GridLayout"));

        String entrance = source.substring(source.indexOf(
                        "private void enterExpandedLibrary(boolean"),
                source.indexOf("private void exitExpandedLibrary()"));
        assertFalse(entrance.contains("createCarouselToGridGhosts"));
        assertFalse(entrance.contains("staggerExpandedGridEntrance"));
        String exit = source.substring(source.indexOf("private void exitExpandedLibrary()"),
                source.indexOf("private void setNormalLibraryVisibility("));
        assertFalse(exit.contains("createGridToCarouselGhosts"));
        assertTrue(source.contains("private static final LruCache<String, Bitmap>"));
        assertTrue(source.contains("deferSecondaryLayersAfterFirstLayout(container)"));

        String prefetch = source.substring(source.indexOf(
                        "private void schedulePlayniteArtworkPrefetch(ComputerDetails host,"),
                source.indexOf("private void prefetchPlayniteArtwork("));
        assertTrue(prefetch.contains(
                "Math.min(PLAYNITE_ARTWORK_PREFETCH_WORKERS, snapshot.size())"));

        String decode = source.substring(source.indexOf(
                        "private Bitmap cachePlayniteBitmap("),
                source.indexOf("private String playniteBitmapCacheKey("));
        assertTrue(decode.contains("playniteBitmapDecodeLocks.computeIfAbsent"));
        assertTrue(decode.contains("synchronized (decodeLock)"));

        String backdrop = source.substring(source.indexOf(
                        "private void showArtworkSettled("),
                source.indexOf("private void finishBackdropSwap("));
        assertTrue(backdrop.indexOf("if (token != artworkGeneration.get()) return;")
                < backdrop.indexOf("decodeArtwork(file"));
        assertTrue(backdrop.contains("playniteBitmapCache.get(bitmapKey)"));
        assertTrue(backdrop.contains("playniteBitmapCache.put(bitmapKey, bitmap)"));
        String heroDecode = source.substring(source.indexOf("private Bitmap decodeArtwork("),
                source.indexOf("private Drawable cloneDrawable("));
        assertTrue(heroDecode.contains("Bitmap.Config.RGB_565"));

        String style = source.substring(source.indexOf("private void styleCard("),
                source.indexOf("private void styleControllerPill("));
        assertTrue(style.contains("instanceof StateListDrawable"));
        assertTrue(style.contains("card.setBackground(carouselCardBackground())"));

        String render = source.substring(source.indexOf(
                        "private void renderExpandedLibrary("),
                source.indexOf("private ExpandedLibraryGrid createExpandedGrid("));
        assertTrue(render.contains("expandedGrid.setItems(expandedItems)"));
        assertTrue(render.contains("expandedGrid.rebindAll("));
        assertTrue(grid.contains("DiffUtil.calculateDiff("));
        assertTrue(grid.contains("getRecycledViewPool().putRecycledView("));
        assertTrue(grid.contains("setHasFixedSize(true)"));
        // The grid's row must not measure it with an unbounded height (all cards bound).
        String body = source.substring(source.indexOf("LinearLayout body = new LinearLayout(this);"),
                source.indexOf("LinearLayout details = new LinearLayout(this);"));
        assertTrue(body.contains("body.setBaselineAligned(false)"));
        // Focus must not relayout grid cards (title marquee) nor re-parse profile JSON.
        String marquee = source.substring(source.indexOf("private void updateCarouselMarquee("),
                source.indexOf("private boolean isQuickLaunch("));
        assertTrue(marquee.indexOf("isDescendant(expandedGrid, title)")
                < marquee.indexOf("title.setSingleLine(true)"));
        assertTrue(source("HostGatewayStore.java").contains("parsed.raw.equals(raw)"));
        // Session polls re-render the library under an open grid only on a visible change.
        String gate = source.substring(source.indexOf("private void renderLibraryForSessionChange("),
                source.indexOf("private void invalidateSessionProjectionRequest("));
        assertTrue(gate.contains("if (!sessionPresentationKey(host, snapshot)"
                + ".equals(renderedSessionPresentationKey)) {"));
        // A passing minute of the status line (its key changes at most once a minute) updates
        // the top bar and the host tile only; a poll that changes neither key renders nothing.
        assertTrue(gate.contains(
                "} else if (!snapshot.display.key().equals(renderedSessionDisplayKey)) {"));
        assertTrue(gate.indexOf("renderPlayniteLibrary(host, currentSunshineApps);")
                < gate.indexOf("snapshot.display.key()"));
        assertTrue(gate.indexOf("snapshot.display.key()") < gate.indexOf("updateHostStatus();"));
        assertTrue(gate.indexOf("renderPlayniteLibrary(")
                == gate.lastIndexOf("renderPlayniteLibrary("));
        assertTrue(source.contains(
                "renderedSessionDisplayKey = renderedSessionSnapshot(host).display.key();"));
        assertTrue(source.contains("renderLibraryForSessionChange(currentHost(expectedHostId))"));
        assertTrue(grid.contains("grid.setItemPrefetchEnabled(false)"));
        // Library resolve: per-game reuse, prepared off the main thread before renders.
        String library = source.substring(source.indexOf("private void renderPlayniteLibrary("),
                source.indexOf("private void requestPendingInitialGameFocus("));
        assertTrue(library.contains("resolvePlayniteTarget(targets, game)"));
        assertFalse(library.contains("PlayniteTargetResolver.resolve("));
        assertTrue(source.contains(
                "if (cached != null) prepareResolvedTargets(host, cached.games, currentSunshineApps);"));
        assertTrue(grid.contains("callbacks.releaseCard(holder.itemView)"));
        String applyPrefetched = source.substring(source.indexOf(
                        "private void applyPrefetchedPlayniteArtwork("),
                source.indexOf("private View directChildWithTag("));
        assertFalse(applyPrefetched.contains("loadPlayniteBitmap("));
        assertFalse(applyPrefetched.contains("cachedPlayniteArtwork("));

        String reset = source.substring(source.indexOf(
                        "private void renderExpandedLibraryFromStart("),
                source.indexOf("private void renderPlayniteLibrary("));
        assertTrue(reset.contains("expandedGrid.focusPosition(0)"));

        String focus = source.substring(source.indexOf(
                        "private void bindPlayniteCard("),
                source.indexOf("private void stylePlayniteSessionCard("));
        assertTrue(focus.contains("schedulePlayniteSelectionSave("));
        assertFalse(focus.contains("preferences.edit().putString(\"selected_playnite."));
        String pause = source.substring(source.indexOf("protected void onPause()"),
                source.indexOf("protected void onDestroy()"));
        assertTrue(pause.contains("flushPendingPlayniteSelection()"));
        assertTrue(pause.contains("playniteBitmapCache.evictAll()"));
        assertTrue(pause.contains("playniteBitmapCache.trimToSize(16 * 1024 * 1024)"));
        assertTrue(pause.contains("TRIM_MEMORY_MODERATE"));

        String cache = source("PlayniteLibraryCache.java");
        assertTrue(cache.contains("private static final Map<String, Entry> MEMORY"));
        assertTrue(cache.contains("MEMORY.get(cacheKey)"));
    }

    private static String consoleActivitySource() throws IOException {
        return source("ConsoleActivity.java");
    }

    private static String source(String file) throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/" + file);
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/console/" + file);
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
