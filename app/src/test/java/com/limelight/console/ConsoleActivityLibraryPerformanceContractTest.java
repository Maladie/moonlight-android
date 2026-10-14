package com.limelight.console;

import com.limelight.SourceText;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.limelight.SourceText.assertOrdered;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleActivityLibraryPerformanceContractTest {
    @Test public void childHomeUsesTheSameProfileScopedCacheAndImmediateRefresh() throws IOException {
        String source = consoleActivitySource();
        String loading = source.substring(source.indexOf("private boolean restoreInitialLocalPresentationData("),
                source.indexOf("private void settleInitialLocalPresentation("));
        assertFalse(loading.contains("selectedProfileIsChild"));
        assertTrue(loading.contains("readCachedLibrary(host, requestKey, sortLocale)"));
        assertTrue(loading.contains("playniteLibraryRepository.cached(key)"));
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
                "if (cached != null) prepareResolvedTargets(host, cached.games, loadApps(host));"));
        // An unchanged refresh keeps the list the targets are keyed by; a changed one is
        // resolved on the worker, and the PIN gate reads and resolves in memory only.
        assertTrue(source.contains(
                "if (libraryChanged) currentPlayniteGames = result.entry.games;"));
        assertTrue(source.contains(
                "prepareResolvedTargets(host, result.entry.games, currentSunshineApps);"));
        assertTrue(source.substring(source.indexOf("private void showPinEntry("),
                source.indexOf("private void submitProfilePin()")).contains(
                "playniteExecutor.execute(() -> readCachedLibrary(host, gatedLibraryKey, sortLocale));"));
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

    @Test public void carouselBuildsManyMissingCardsAcrossFrames() throws IOException {
        String source = consoleActivitySource();
        String diff = source.substring(source.indexOf("private void applyPlayniteDiff("),
                source.indexOf("private List<PlayniteDashboardItem> preserveOrderDuringFocus("));
        // The render decides per position; a prepared returning frame is built whole.
        assertTrue(diff.contains("if (card == null && !suppressInitialCarouselMotion && (introSteps\n"
                + "                    || !PlayniteLibraryDiff.buildsInRender(index, missingCards))) {"));
        assertTrue(diff.contains("carouselGhost(PENDING_CARD_TAG + item.stableId(), .28f)"));
        // Every render reconciles the placeholders and restarts the single frame step.
        assertTrue(diff.contains("placeholders.put(pendingId, child);"));
        assertTrue(diff.contains("appRow.removeCallbacks(carouselCardBuildStep);\n"
                + "        if (placeholdersLeft) {"));
        assertTrue(diff.contains("            }\n"
                + "            appRow.postOnAnimation(carouselCardBuildStep);"));
        assertTrue(diff.contains("View target = carouselCard(selection);"));
        // Placeholders are not cards: nothing addressed to \"playnite:\" tags reaches them.
        assertTrue(source.contains(
                "private static final String PENDING_CARD_TAG = \"playnite.pending:\";"));

        String step = source.substring(source.indexOf(
                        "private void buildNextPendingCarouselCard()"),
                source.indexOf("private View buildPendingCarouselCard("));
        // One card at a time with a gap, none while the row animates, no list captured by the step.
        assertTrue(step.contains("transition.isChangingLayout()"));
        assertTrue(step.contains("} else if (buildPendingCarouselCard(child) != null) {"));
        assertTrue(step.contains(
                "appRow.postDelayed(carouselCardBuildStep, CAROUSEL_CARD_BUILD_GAP_MS);"));
        String build = source.substring(source.indexOf("private View buildPendingCarouselCard("),
                source.indexOf("private void releaseExpandedGrid()"));
        assertTrue(build.contains("for (PlayniteDashboardItem rendered : renderedPlayniteItems)"));
        assertTrue(build.contains("currentHost(selectedHostUuid)"));
        assertTrue(build.contains("appRow.removeViewAt(index);\n"
                + "        appRow.addView(card, index, playniteCardSpacing());"));
        assertTrue(build.contains("card.layout(placeholder.getLeft(), placeholder.getTop(),"));

        // Navigation and the return from the grid get a real card at a pending position.
        String navigation = source.substring(source.indexOf(
                        "private boolean handleCarouselNavigation("),
                source.indexOf("private void handleConsoleAudioKey("));
        assertTrue(navigation.contains("candidate = buildPendingCarouselCard(candidate);"));
        String reveal = source.substring(source.indexOf(
                        "private void revealNormalLibraryAfterTransition()"),
                source.indexOf("private void createCarouselToGridGhosts()"));
        assertEquals(2, reveal.split("carouselCard\\(libraryTransitionGameId\\)", -1).length - 1);
    }

    @Test public void menuAmbienceContinuesWhereTheLastConsoleStoppedIt() throws IOException {
        String audio = source("ConsoleAudioEngine.java");
        String update = audio.substring(audio.indexOf("private synchronized void updateAmbient("),
                audio.indexOf("private synchronized void applyFadeStep("));
        assertTrue(audio.contains("private static int ambientPositionMs;"));
        assertOrdered(update, "ambientPlayer.seekTo(ambientPositionMs);",
                "ambientPlayer.start();");
        assertFalse(audio.contains("ambientPlayer.pause();\n        }"));
        String pause = audio.substring(audio.indexOf("private void pauseAmbient()"),
                audio.indexOf("private void setAmbientVolume("));
        assertOrdered(pause, "ambientPositionMs = ambientPlayer.getCurrentPosition();",
                "ambientPlayer.pause();");
        assertEquals(1, audio.split("ambientPlayer\\.pause\\(\\)", -1).length - 1);
    }

    private static String consoleActivitySource() throws IOException {
        return source("ConsoleActivity.java");
    }

    private static String source(String file) throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/" + file);
        return SourceText.read(source);
    }
}
