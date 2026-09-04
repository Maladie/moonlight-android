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
    @Test public void gridAndArtworkWorkStayBoundedToTheVisibleNeighborhood()
            throws IOException {
        String source = consoleActivitySource();

        assertTrue(source.contains("EXPANDED_CACHE_ROWS_EACH_SIDE = 2"));
        assertTrue(source.contains("EXPANDED_PREFETCH_ROWS_EACH_SIDE = 1"));
        assertTrue(source.contains("PLAYNITE_ARTWORK_PREFETCH_WORKERS = 2"));
        assertTrue(source.contains("EXPANDED_NAVIGATION_INTERVAL_MS = 70L"));
        assertTrue(source.contains("EXPANDED_FOCUS_TRANSITION_TIMEOUT_MS = 300L"));

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

        String launchTargets = source("PlayniteLaunchTargetStore.java");
        assertTrue(launchTargets.contains("preferences.getInt(key, 0) == appId"));

        String style = source.substring(source.indexOf("private void styleCard("),
                source.indexOf("private void styleControllerPill("));
        assertTrue(style.contains("instanceof StateListDrawable"));
        assertTrue(style.contains("card.setBackground(carouselCardBackground())"));

        String render = source.substring(source.indexOf(
                        "private void renderExpandedLibrary("),
                source.indexOf("private List<PlayniteDashboardItem> mergeArtworkWarmup("));
        assertFalse(render.contains("expandedGrid.removeAllViews()"));
        assertTrue(render.contains("expandedGrid.removeViewAt(childIndex)"));
        assertTrue(render.contains("card.getParent() == expandedGrid"));
        assertTrue(render.contains("expandedGrid.removeView(discarded)"));

        String reset = source.substring(source.indexOf(
                        "private void renderExpandedLibraryFromStart("),
                source.indexOf("private void renderPlayniteLibrary("));
        assertTrue(reset.contains("removeCallbacks(expandedWindowWarmupRunnable)"));
        assertTrue(reset.contains("expandedWindowWarmupPosted = false"));
        assertTrue(reset.contains("pendingExpandedWindowWarmupIndex = -1"));
        assertTrue(reset.contains("removeCallbacks(expandedFocusRestoreRunnable)"));
        assertTrue(reset.contains("expandedGridWindowStartRow = 0"));
        assertTrue(reset.contains("pendingExpandedFocusIndex = 0"));
        assertTrue(reset.contains("requestExpandedFocusOnce(firstFocusableChild(expandedGrid))"));

        String boundary = source.substring(source.indexOf(
                        "private void scheduleExpandedBoundaryLoad("),
                source.indexOf("private void scheduleExpandedWindowWarmup("));
        assertTrue(boundary.contains("mainHandler.post(expandedWindowWarmupRunnable)"));
        assertFalse(boundary.contains("postDelayed(expandedWindowWarmupRunnable, 70L)"));

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
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
