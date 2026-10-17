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

/** The entry intro after a PIN: built while it is typed, home's UI work after its entrance. */
public class ConsoleIntroEntranceContractTest {
    @Test public void theIntroIsBuiltOnThePinScreenAndReleasedWithIt() throws IOException {
        String activity = source("ConsoleActivity.java");
        String show = between(activity, "private void showPinEntry(",
                "private void showPinPage(");
        // After the screen is shown, and only for a PIN that ends in a host entry.
        assertTrue(show.indexOf("showPinPage(")
                < show.indexOf("if (introView != null && completeHostEntry) introView.prepare(profile);"));
        // Every way the PIN screen closes goes through here: cancel, another profile, the gate.
        String clear = between(activity, "private void clearPinEntry()",
                "private void enterHostAfterProfileGate(");
        assertTrue(clear.contains("if (introView != null) introView.discard();"));
        assertTrue(between(activity, "private void closeProfileGate()",
                "private void prepareSelectedHost(").contains("clearPinEntry();"));
        assertTrue(show.contains("clearPinEntry();"));
        assertTrue(between(activity, "protected void onPause()", "protected void onDestroy()")
                .contains("if (introView != null) introView.end();"));
        // The start comes before the PIN screen is cleared, so it finds what was prepared.
        String verified = between(activity, "private void finishProfilePinVerification(",
                "private void renderPinCooldown()");
        assertTrue(verified.indexOf("introView.start(profile, hostId, ConsoleIntroView.Entry.PIN)")
                < verified.indexOf("enterHostAfterProfileGate(host, pinFocusApps, pinPrepareHost)"));

        String intro = source("ConsoleIntroView.java");
        String prepare = between(intro, "void prepare(", "private void prebuild()");
        // Not during the PIN screen's opening, and a running intro is never dropped.
        assertTrue(prepare.contains("postDelayed(prepareStep, PREPARE_AFTER_MS);"));
        assertTrue(prepare.contains("if (running) removeCallbacks(prepareStep);\n"
                + "        else end();"));
        assertTrue(ConsoleIntroView.PREPARE_AFTER_MS > ConsolePinEntryView.MOVE_MS);
        assertTrue(ConsoleIntroView.PREPARE_AFTER_MS > ConsoleEntryShell.CROSS_FADE_MS);
        String prebuild = between(intro, "private void prebuild()", "private static boolean sameLook(");
        assertTrue(prebuild.contains("if (running || awaited == null) return;"));
        // Laid out but never drawn, and no clock: nothing moves or shows until start().
        assertTrue(prebuild.contains("setVisibility(INVISIBLE);"));
        assertFalse(prebuild.contains("clock.start()"));
        assertFalse(prebuild.contains("VISIBLE);\n        frame("));

        String start = between(intro, "void start(", "static void shown(");
        // Only a PIN entry for the same look and motion reuses it; any other start builds.
        assertTrue(start.contains("boolean reuse = prebuilt && entry == Entry.PIN && reduce == reduced\n"
                + "                && sameLook(awaited, profile);"));
        assertTrue(start.contains("if (reuse) prebuilt = false;\n        else end();"));
        assertTrue(start.contains(
                "if (!reuse) build(profile, entry == Entry.PROFILE && shell.getWidth() > 0);"));
        // The avatar's start position is read at the start, from the layout as it then is.
        assertTrue(start.indexOf("if (!reuse) build(")
                < start.indexOf("avatarFrom = from == null ? null : rectIn(from);"));
        String end = between(intro, "void end() {", "private void focusCarousel()");
        assertTrue(end.contains("removeCallbacks(prepareStep);\n        awaited = null;\n"
                + "        prebuilt = false;"));
        assertTrue(end.contains("removeAllViews();"));
        assertTrue(end.contains("setVisibility(GONE);"));
    }

    @Test public void homeStepsWaitForTheEntranceRunOneAFrameAndHoldTheExit() throws IOException {
        String intro = source("ConsoleIntroView.java");
        String queue = between(intro, "static void afterEntrance(", "boolean holding()");
        assertTrue(queue.contains("if (intro == null || !intro.entering()) step.run();\n"
                + "        else intro.untilEntered.add(step);"));
        assertTrue(queue.contains("return running && !entered;"));
        String frame = between(intro, "private void frame(float ms)", "private void phase(float t)");
        // Not ready, so not leaving, until every waiting step has run.
        assertTrue(frame.contains("boolean ready = entered && ready();"));
        // The limit leaves regardless: the rest runs first, in that same frame.
        assertTrue(frame.indexOf("runEntered();") < frame.indexOf("beginExit((long) ms);"));
        assertTrue(frame.contains(
                "if (running && !entered && ConsoleIntroTiming.entered(ms * speed)) {\n"
                + "            if (untilEntered.isEmpty()) entered = true;\n"
                + "            else untilEntered.remove(0).run();"));
        String end = between(intro, "void end() {", "private void focusCarousel()");
        assertTrue(end.indexOf("running = false;") < end.indexOf("runEntered();"));
        // Reduced motion has no entrance: nothing waits.
        assertTrue(between(intro, "void start(", "static void shown(")
                .contains("entered = reduced;"));

        String activity = source("ConsoleActivity.java");
        String select = between(activity, "private void selectHost(ComputerDetails host, boolean focusApps)",
                "private void cancelOwnedWarmUp(");
        // The host, its grant and the background reads start at once; only UI work waits.
        for (String immediate : new String[]{"selectedHostUuid = host.uuid;",
                "ProfileAuthorization.grant(host.uuid);", "updateHostSelector();",
                "else refreshHostProfiles(host);", "startAppListPoller(host);",
                "renderAppsAsync(host, focusApps);", "loadPlayniteForHost(host);"}) {
            assertTrue(immediate, select.contains("        " + immediate + "\n"));
        }
        assertTrue(select.contains(
                "ConsoleIntroView.afterEntrance(introView, this::refreshDiscordIndicator);"));
        String load = between(activity, "private void loadPlayniteForHost(",
                "private PlayniteLibraryCache.Entry readCachedLibrary(");
        assertTrue(load.contains("if (appRow != null) appRow.removeAllViews();\n"
                + "            ConsoleIntroView.afterEntrance(introView, () -> {\n"
                + "                if (playniteInitialLoadPending && currentPlayniteGames.isEmpty()) {\n"
                + "                    showCarouselLoadingGhosts();"));
        // The cached library is published and the host asked at once; its render waits.
        assertTrue(load.indexOf("currentPlayniteGames = cached.games;")
                < load.indexOf("renderLibraryAfterIntroEntrance(host.uuid);"));
        assertTrue(load.contains("requestPlayniteRefresh(latestHost, false);"));
        assertFalse(load.contains("afterEntrance(introView, () -> requestPlayniteRefresh"));
        assertTrue(between(activity, "private void renderAppsAsync(ComputerDetails host, boolean focusApps)",
                "private void runLibraryUpdateWhenNavigationIdle(").contains(
                "mainHandler.post(() -> ConsoleIntroView.afterEntrance(introView,\n"
                + "                    () -> runLibraryUpdateWhenNavigationIdle(() -> {"));
        assertTrue(between(activity, "private void refreshHostProfiles(ComputerDetails host)",
                "private void refreshVisibleHostProfiles()").contains(
                "mainHandler.post(() -> ConsoleIntroView.afterEntrance(introView, () -> {"));

        // The opening frame stands while home's local data is still to come or steps wait,
        // running one step a frame; the same supplier later gates the intro's readiness.
        String tick = between(intro, "private void tick(long total)", "private void frame(float ms)");
        assertTrue(tick.contains(
                "boolean still = !reduced && ConsoleIntroTiming.standsStill(total, stillFrames,\n"
                + "                    steps || cardsPending.getAsBoolean(), homeData.getAsBoolean());"));
        assertTrue(tick.contains("if (stillFrames > 1 && steps) untilEntered.remove(0).run();\n"
                + "                else if (stillFrames > 1) buildCard.run();\n"
                + "                return;"));
        assertTrue(tick.contains("movingSince = total;"));
        assertTrue(activity.contains("() -> initialLocalPresentationReady(selectedHostUuid, "
                + "initialLocalAppsHostId,\n                initialLocalLibraryHostId), loadingPosterTags"));

        // A render during the entrance builds no card: three steps of one card, then the rest.
        String diff = between(activity, "private void applyPlayniteDiff(",
                "private void moveCarouselCard(");
        assertTrue(diff.contains(
                "boolean introSteps = introView != null && introView.entering();"));
        assertTrue(diff.contains(
                "for (int card = 0; introSteps && card < PlayniteLibraryDiff.IMMEDIATE_CARDS; card++) {\n"
                + "                ConsoleIntroView.afterEntrance(introView, this::buildLeadingCarouselCard);\n"
                + "            }\n"
                + "            appRow.postOnAnimation(carouselCardBuildStep);"));
        // The rest are no step of the intro (they would start in its still frame and go on
        // through the entrance): their own chain waits until the entrance is over.
        assertFalse(diff.contains("afterEntrance(introView, carouselCardBuildStep)"));
        String rest = between(activity, "private void buildNextPendingCarouselCard()",
                "private void buildLeadingCarouselCard()");
        assertTrue(rest.indexOf("if (introView != null && introView.entering()) {\n"
                + "            appRow.postDelayed(carouselCardBuildStep, CAROUSEL_CARD_BUILD_GAP_MS);\n"
                + "            return;") > 0);
        assertTrue(rest.indexOf("introView.entering()") < rest.indexOf("buildPendingCarouselCard(child)"));
        String leading = between(activity, "private void buildLeadingCarouselCard()",
                "private View buildPendingCarouselCard(");
        // Only the leading positions: a second render's steps find them built and do nothing.
        assertTrue(leading.contains("int leading = Math.min(PlayniteLibraryDiff.IMMEDIATE_CARDS, "
                + "appRow.getChildCount());\n        for (int index = 0; index < leading; index++) {"));
        assertTrue(leading.contains("buildPendingCarouselCard(child);\n"
                + "            requestPendingInitialGameFocus(currentHost(selectedHostUuid));\n"
                + "            return;"));
        assertFalse(leading.contains("carouselCardBuildStep"));
    }

    @Test public void anEmptyLibraryStillLoadingIsNotRenderedAndItsLoadsEndIs()
            throws IOException {
        String activity = source("ConsoleActivity.java");
        String apps = between(activity, "private void renderAppsAsync(ComputerDetails host, boolean focusApps)",
                "private void runLibraryUpdateWhenNavigationIdle(");
        // The app list is still published; without a library connection the apps still render.
        assertTrue(apps.indexOf("currentSunshineApps = apps;")
                < apps.indexOf("if (playniteInitialLoadPending && currentPlayniteGames.isEmpty()) return;"));
        assertTrue(apps.indexOf("if (playniteAvailable) {")
                < apps.indexOf("if (playniteInitialLoadPending && currentPlayniteGames.isEmpty()) return;"));
        // Its key is recorded first: the render that follows the load uses this app list.
        assertTrue(apps.indexOf("renderedSunshineAppsKey = appsKey;")
                < apps.indexOf("if (playniteInitialLoadPending && currentPlayniteGames.isEmpty()) return;"));
        assertTrue(apps.contains("renderApps(latestHost, apps);"));

        // An answered initial load without games renders: an error or an empty answer. The row
        // then shows the empty-library text beside the status message. Without a connection the
        // app list renders its own row, which a library render must not replace.
        String refresh = between(activity, "private void requestPlayniteRefresh(",
                "private void scheduleNextPlayniteRefresh()");
        String ends = "if (initialLoad && currentPlayniteGames.isEmpty()) {\n";
        assertTrue(refresh.split(java.util.regex.Pattern.quote(ends), -1).length - 1 == 1);
        String noConnection = refresh.substring(refresh.indexOf("if (connection == null) {"),
                refresh.indexOf("cancelPlayniteRequest();"));
        assertFalse(noConnection.contains("renderLibraryAfterIntroEntrance"));
        String answered = refresh.substring(refresh.indexOf("mainHandler.post(() -> {"));
        assertTrue(answered.indexOf("boolean initialLoad = playniteInitialLoadPending;")
                < answered.indexOf("playniteInitialLoadPending = false;"));
        assertTrue(answered.indexOf("playniteLibraryError = result.error;")
                < answered.indexOf(ends + "                    renderLibraryAfterIntroEntrance(host.uuid);"));
        // That render, with the load over, no longer puts the loading cards back.
        String diff = between(activity, "private void applyPlayniteDiff(",
                "private void moveCarouselCard(");
        assertTrue(diff.contains("if (playniteInitialLoadPending || playniteLibraryRefreshing) {\n"
                + "                showCarouselLoadingGhosts();"));
        assertTrue(diff.contains("empty.setTag(\"playnite.empty\");"));
        assertTrue(between(activity, "private void renderLibraryAfterIntroEntrance(",
                "private void settleInitialLocalPresentation(").contains(
                "if (host != null) renderPlayniteLibrary(host, currentSunshineApps);"));
    }

    @Test public void theAppListAndTheCachedLibraryRenderTheLibraryOnce() throws IOException {
        String activity = source("ConsoleActivity.java");
        String apps = between(activity, "private void renderAppsAsync(ComputerDetails host, boolean focusApps)",
                "private void runLibraryUpdateWhenNavigationIdle(");
        String load = between(activity, "private void loadPlayniteForHost(",
                "private PlayniteLibraryCache.Entry readCachedLibrary(");
        // Whichever of the two answers second renders: the app list result when the library
        // is in memory by then, the cached library only when this host's app list already is.
        assertOrdered(apps, "initialLocalAppsHostId = uuid;",
                "renderPlayniteLibrary(latestHost, apps);");
        assertTrue(load.contains(
                "} else if (cached != null && host.uuid.equals(initialLocalAppsHostId)) {\n"
                + "                    // Without this host's app list yet, its result renders the library: once.\n"
                + "                    renderLibraryAfterIntroEntrance(host.uuid);"));
        assertEquals(1, load.split("renderLibraryAfterIntroEntrance\\(", -1).length - 1);
        // A library emptied for a new load is not "the same library": the app list renders it.
        assertOrdered(load, "playniteInitialLoadPending = true;",
                "renderedSunshineAppsKey = \"\";");
        assertOrdered(load, "renderedSunshineAppsKey = \"\";",
                "playniteRequest = playniteExecutor.submit(");
        // Every entry resets the app list fact before both reads start.
        String select = between(activity, "private void selectHost(ComputerDetails host, boolean focusApps)",
                "private void cancelOwnedWarmUp(");
        assertOrdered(select, "initialLocalAppsHostId = \"\";",
                "renderAppsAsync(host, focusApps);");
        // A profile applied in place publishes the app list itself.
        assertTrue(between(activity, "private void applySelectedProfile(",
                "private boolean changeSelectedProfile(").contains(
                "currentSunshineApps = loadApps(host);\n        initialLocalAppsHostId = host.uuid;"));
    }

    @Test public void theLibraryIsReadAndResolvedOnThePinScreenForTheRenderAfterIt()
            throws IOException {
        String activity = source("ConsoleActivity.java");
        String show = between(activity, "private void showPinEntry(",
                "private void submitProfilePin()");
        // Worker only, and nothing but memory: no field of the home screen is written here.
        assertTrue(show.contains("playniteExecutor.execute(() -> "
                + "readCachedLibrary(host, gatedLibraryKey, sortLocale));"));
        for (String published : new String[]{"currentPlayniteGames =", "currentSunshineApps =",
                "renderPlayniteLibrary(", "mainHandler.post("}) {
            assertFalse(published, show.contains(published));
        }
        String read = between(activity, "private PlayniteLibraryCache.Entry readCachedLibrary(",
                "private void renderLibraryAfterIntroEntrance(");
        assertTrue(read.contains("Collator.getInstance(sortLocale);"));
        assertTrue(read.contains(
                "if (cached != null) prepareResolvedTargets(host, cached.games, loadApps(host));"));
        assertFalse(read.contains("mainHandler"));
        // The read after the PIN is the same one, and keeps what that one resolved.
        assertTrue(between(activity, "private void loadPlayniteForHost(",
                "private PlayniteLibraryCache.Entry readCachedLibrary(").contains(
                "PlayniteLibraryCache.Entry cached = readCachedLibrary(host, requestKey, sortLocale);"));
        String prepare = between(activity, "private void prepareResolvedTargets(",
                "private long playActivityEpoch(");
        assertTrue(prepare.contains(
                "if (known != null && known.matches(host.uuid, apps, targets.authoritative)) {"));
        assertTrue(prepare.contains("PlayniteDashboardItem item = known.items.get(game);"));
        // A fresh instance each time: nothing of an older library's games is kept.
        assertTrue(prepare.contains("ResolvedTargets targets = new ResolvedTargets(host.uuid, apps,"));
        assertTrue(activity.contains("Collections.synchronizedMap(new IdentityHashMap<>());"));
    }

    @Test public void aRenderReadsTheGameOperationsOnce() throws IOException {
        String activity = source("ConsoleActivity.java");
        String render = between(activity, "private void renderPlayniteLibraryInBatch(",
                "private void requestPendingInitialGameFocus(");
        assertEquals(1, render.split("isPlayniteInstalling\\(", -1).length - 1);
        assertTrue(render.contains("promoteInstallingGames(installing, ordered, sourceFiltered)"));
        assertTrue(render.contains("putInstallingItemsFirst(installing, items)"));
        assertTrue(render.contains("host, unfilteredItems, maxCarouselGameCount(), installing);"));
        for (String[] helper : new String[][]{
                {"private List<PlayniteDashboardItem> carouselPlayniteItems(",
                        "private long carouselActivityEpoch("},
                {"private List<PlayniteLibraryGame> promoteInstallingGames(",
                        "private void applyPlayniteDiff("}}) {
            assertFalse(between(activity, helper[0], helper[1]).contains("isPlayniteInstalling("));
        }
    }

    @Test public void aPreparedHostOpensItsStreamScreenOnlyAfterTheIntro() throws IOException {
        String activity = source("ConsoleActivity.java");
        String intro = source("ConsoleIntroView.java");
        // One decision: the orchestrator holds the prepared launch while the intro is on.
        assertTrue(between(activity, "@Override public boolean mayOpenPreparedHost() {",
                "@Override public void launchPreparedHost(")
                .contains("return introView == null || !introView.running();"));
        // The preparation itself starts with the host entry, as before.
        assertTrue(between(activity, "private void prepareSelectedHost(",
                "private void dispatchPendingHostPreparation()")
                .contains("sessionOrchestrator.prepareHost(host.uuid,"));
        // The intro's end opens it on a later turn: onPause ends the intro before it cancels
        // the preparation, and must not start a stream screen.
        assertTrue(activity.contains("introView.whenEnded(() -> mainHandler.post(() -> {\n"
                + "                if (sessionOrchestrator != null)"
                + " sessionOrchestrator.openPreparedHost();"));
        String pause = between(activity, "protected void onPause()", "protected void onDestroy()");
        assertTrue(pause.indexOf("if (introView != null) introView.end();")
                < pause.indexOf("cancelCurrentPreparation(childStreamHandoff);"));
        String end = between(intro, "    void end() {", "private void focusCarousel()");
        assertTrue(end.indexOf("boolean wasRunning = running;") < end.indexOf("running = false;"));
        assertTrue(end.indexOf("runEntered();") < end.indexOf("if (wasRunning) ended.run();"));
    }

    private static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(from, start >= 0);
        int end = source.indexOf(to, start);
        assertTrue(to, end > start);
        return source.substring(start, end);
    }

    private static String source(String file) throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/" + file);
        return SourceText.read(source);
    }
}
