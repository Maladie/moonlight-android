package com.limelight.console;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ConsoleActivityRequestedUiContractTest {
    @Test public void homeOmitsPlayniteAndQuickLaunchButtons() throws IOException {
        String source = consoleActivitySource();

        assertFalse(source.contains("launchPlayniteButton"));
        assertFalse(source.contains("launchDesktopButton"));
        assertFalse(topBarSource().contains("global.quick_launch"));
    }

    @Test public void hostCloseSessionUsesTheSingleTerminationPathAndKeepsHardTermination() throws IOException {
        String source = consoleActivitySource();
        String selector = source.substring(source.indexOf("topBar = new ConsoleTopBar("),
                source.indexOf("homeContent.addView(topBar"));
        String menu = source.substring(source.indexOf("private void showHostSelectionOptions("),
                source.indexOf("static HostGatewayClient.IntegrationProfile activeAuthorizedProfile("));

        assertTrue(selector.contains("showHostSelectionOptions(host)"));
        String panel = hostPanelSource();
        assertTrue(panel.contains("R.string.console_close_session"));
        assertTrue(menu.contains("case CLOSE_SESSION: confirmTerminateSession(host)"));
        assertFalse(source.contains("CloseHostStream("));
        assertTrue(panel.contains("R.string.console_hard_terminate_session"));
        assertTrue(menu.contains("confirmHardTerminateSession(host)"));
        assertTrue(panel.contains("facts.online && facts.paired && facts.gatewayAvailable"));
        assertTrue(panel.contains("R.string.console_launch_desktop"));
        assertTrue(menu.contains("launchDesktopSession(host)"));
    }

    @Test public void endGameShowsProgressUntilTheStopCompletes() throws IOException {
        String source = consoleActivitySource();
        String confirm = source.substring(source.indexOf("private void confirmEndPlayniteGame("),
                source.indexOf("private void requestEndPlayniteGame("));
        String request = source.substring(source.indexOf("private void requestEndPlayniteGame("),
                source.indexOf("private void restoreCachedPlaynitePoster("));
        String completion = request.substring(request.indexOf("mainHandler.post("));

        assertTrue(confirm.contains("hideSidePanelImmediately();"));
        assertTrue(request.indexOf("showSidePanelBusy(") < request.indexOf("executor.execute("));
        assertTrue(request.contains("R.string.playnite_ending_game"));
        assertTrue(request.contains("String busyPanelKey = currentPanelKey;"));
        assertTrue(completion.indexOf(
                "if (sidePanelTransient && busyPanelKey.equals(currentPanelKey)) hideSidePanel();")
                < completion.indexOf("return;"));
    }

    @Test public void closeSessionHidesOnlyItsOwnProgressPanel() throws IOException {
        String source = consoleActivitySource();
        String request = source.substring(source.indexOf("private void requestTerminateSession("),
                source.indexOf("private void terminateSessionByControlPlane("));
        String finish = source.substring(source.indexOf("private void finishTerminateSession("));

        assertTrue(request.indexOf("showSidePanelBusy(")
                < request.indexOf("String busyPanelKey = currentPanelKey;"));
        String guard =
                "if (sidePanelTransient && busyPanelKey.equals(currentPanelKey)) hideSidePanel();";
        assertTrue(finish.contains(guard));
        assertTrue(finish.indexOf(guard) < finish.indexOf("if (outcome.closed())"));
        assertFalse(source.contains("if (sidePanelTransient) hideSidePanel();"));
    }

    @Test public void profileSwitchDropsThePreviousProfilesPresentation() throws IOException {
        String source = consoleActivitySource();
        String change = source.substring(source.indexOf("private boolean changeSelectedProfile("),
                source.indexOf("private void refreshHostProfiles("));
        String bar = topBarSource();
        String showProfile = bar.substring(bar.indexOf("void showProfile("),
                bar.indexOf("void showDiscord("));

        assertTrue(change.contains("libraryTransitionCoordinator.cancel();"));
        assertTrue(change.contains("selectedGameMetadata.setVisibility(View.INVISIBLE);"));
        assertTrue(change.contains("selectedGameTitleRow.setVisibility(View.INVISIBLE);"));
        assertTrue(change.contains("selectedGameDescription.setText(\"\");"));
        assertTrue(change.contains("selectedGameFactsRow.setVisibility(View.GONE);"));
        assertTrue(showProfile.contains("if (profile.hasFocus()) showLabel(profile);"));
    }

    @Test public void focusedHostRemainsAStableTwoLineStatusNotAPowerAction()
            throws IOException {
        String source = consoleActivitySource();
        String label = source.substring(source.indexOf("private void updateHostStatus()"),
                source.indexOf("private String compactHostStatus("));
        String bar = topBarSource();

        assertTrue(bar.contains("TextUtils.TruncateAt.END"));
        assertTrue(bar.contains("ConsolePanel.FOCUS_FILL"));
        assertTrue(label.contains("compactHostStatus(host)"));
        assertTrue(label.contains("ConsoleTopBar.statusDotColor("));
        assertTrue(label.contains("R.string.console_host_status_description"));
        assertFalse(label.contains("console_host_power_focused_sleep"));
        assertFalse(label.contains("console_host_power_focused_wake"));
    }

    @Test public void compactCardsOverlayConsistentStateAndInstallProgress() throws IOException {
        String source = consoleActivitySource();
        String card = source.substring(source.indexOf("private View playniteCard("),
                source.indexOf("private LinearLayout.LayoutParams matchLinearWidth("));
        String binding = source.substring(source.indexOf("private void bindPlayniteCard("),
                source.indexOf("private void schedulePlayniteSelectionSave("));
        String state = source.substring(source.indexOf("private String playniteStateChipLabel("),
                source.indexOf("private void stylePlayniteStateChip("));
        String stateStyle = source.substring(source.indexOf("private void stylePlayniteStateChip("),
                source.indexOf("private boolean isVibepolloEnsureInFlight("));
        String expanded = source.substring(source.indexOf("expandedGameDescription = text("),
                source.indexOf("details.addView(expandedGameTitle"));

        assertTrue(card.contains("debugCarousel ? CAROUSEL_CARD_HEIGHT_DP : 190"));
        assertTrue(card.contains("state.setTag(\"playnite.state\")"));
        assertTrue(card.contains("Gravity.BOTTOM | Gravity.END"));
        assertTrue(card.contains("stateParams.rightMargin = dp(8)"));
        assertTrue(card.contains("stateParams.bottomMargin = dp(8)"));
        assertTrue(card.contains("playnite.install.progress"));
        assertTrue(card.contains("expandedCard ? CAROUSEL_FOCUSED_CARD_HEIGHT_DP"));
        assertTrue(card.contains("new int[]{0x00000000, 0xE6000000}"));
        assertTrue(card.contains("copy.setVisibility(expandedCard"));
        assertTrue(card.contains("shine.setTag(\"playnite.shine\")"));
        assertTrue(card.contains("new int[]{0x00FFFFFF, 0x5CFFFFFF, 0x00FFFFFF}"));
        assertTrue(binding.contains("installProgress.setVisibility(installing"));
        assertTrue(binding.contains("installProgress.setProgress(operation.progress)"));
        assertTrue(binding.contains("playniteCardAlpha(item, operation"));
        assertTrue(source.contains("return focused || resumeSession ? 1f : .93f"));
        assertTrue(source.contains("ordinaryReady && !focused ? .48f : 1f"));
        assertTrue(source.contains(
                "state.setVisibility(homeCarousel && ordinaryReady ? View.GONE : View.VISIBLE)"));
        assertTrue(source.contains("isDescendant(appRow, state)"));
        assertTrue(binding.contains("saturationFilter(.25f)"));
        assertTrue(binding.contains("backdrop.setColorFilter(muted)"));
        assertTrue(stateStyle.contains("fill = 0xE014241B"));
        assertTrue(stateStyle.contains("state.setShadowLayer"));
        assertTrue(state.contains("CONSOLE_UI_V2 ? operation.progress + \"%\""));
        assertTrue(state.contains("CONSOLE_UI_V2 ? \"\""));
        String grid = source("src/main/java/com/limelight/console/ExpandedLibraryGrid.java",
                "app/src/main/java/com/limelight/console/ExpandedLibraryGrid.java");
        assertTrue(grid.contains("CELL_HEIGHT_DP = 123"));
        assertTrue(grid.contains("CELL_MARGIN_DP = 4"));
        assertTrue(source.contains("homeContent.setPadding(dp(54), dp(14), dp(54), dp(27))"));
        assertTrue(source.contains("titleParams.topMargin = dp(103)"));
        assertTrue(source.contains("homeContent.addView(playniteLibraryStatus, 1, statusParams)"));
        assertTrue(source.contains("statusParams.topMargin = dp(16)"));
        assertTrue(source.contains("playniteLibraryStatus.setMinHeight(dp(18))"));
        assertTrue(source.contains("playniteLibraryStatus.setShadowLayer(dp(2)"));
        assertTrue(source.contains(
                "playniteLibraryStatus.setVisibility(showStatus ? View.VISIBLE : View.GONE)"));
        assertFalse(source.contains(
                "CONSOLE_UI_V2 && textId == R.string.playnite_data_current"));
        assertTrue(source.contains("Gravity.TOP | Gravity.START"));
        assertTrue(source.contains("new int[]{0x0004070B, 0x6004070B, 0x6004070B,"));
        assertTrue(source.contains("ARTWORK_FOCUS_SETTLE_MS = 420L"));
        assertTrue(source.contains("ARTWORK_CROSSFADE_MS = 560L"));
        assertTrue(source.contains("resizeHomeCarouselCard(card, focused)"));
        assertTrue(source.contains("LayoutTransition.CHANGING"));
        assertTrue(source.contains("CAROUSEL_CARD_WIDTH_DP = 66"));
        assertTrue(source.contains("CAROUSEL_FOCUSED_CARD_WIDTH_DP = 92"));
        assertTrue(source.contains("available - focusedGrowth"));
        assertTrue(source.contains("return Math.max(1, slots + 3)"));
        assertTrue(source.contains(
                "if (item.game.installed && included.add(item.stableId())) result.add(item)"));
        assertTrue(source.contains("CAROUSEL_PREVIOUS_CARD_COUNT"));
        assertTrue(source.contains("shine.postDelayed(this, 7_000L)"));
        assertTrue(source.contains("shine.postDelayed(animation, 2_200L)"));
        assertTrue(source.contains("selectedGameSource = new ImageView(this)"));
        assertTrue(source.contains("new LinearLayout.LayoutParams(dp(22), dp(22))"));
        assertTrue(source.contains("View carousel = carouselStage != null ? carouselStage"));
        assertTrue(source.contains("carousel.setVisibility(visibility)"));
        assertTrue(source.contains("selectedGameTitleRow.setVisibility(View.VISIBLE)"));
        assertTrue(source.contains("positionCarouselMetadata(card)"));
        assertTrue(source.contains("appRow.setGravity(Gravity.TOP)"));
        assertTrue(source.contains("appScroll.setClipChildren(true)"));
        assertTrue(source.contains("view.setClipBounds(new Rect(0, 0"));
        assertTrue(source.contains("int endPadding = Math.max(0, right - left - dp(92))"));
        assertTrue(source.contains("appScroll.setOnScrollChangeListener"));
        assertTrue(source.contains("card.setPivotY(homeCarousel ? 0f"));
        assertTrue(source.contains("focused && !homeCarousel ? -dp(2) : 0f"));
        assertTrue(card.contains("poster.setScaleType(ImageView.ScaleType.FIT_CENTER)"));
        assertTrue(source.contains("LoadingArtworkPolicy.canFillWithModestCrop("));
        assertTrue(source.contains("modestTileCrop || landscape && !CONSOLE_UI_V2"));
        assertTrue(source.contains("+ dp(CAROUSEL_CARD_GAP_DP)"));
        assertTrue(source.contains("card.getParent() != appRow || !card.hasFocus()"));
        assertTrue(source.contains("tile.getLeft() - (homeCarousel ? previousCards"));
        assertTrue(source.contains("selectedGameLastPlayedPill = metadataPill()"));
        assertTrue(source.contains("selectedGamePlaytimePill = metadataPill()"));
        assertTrue(source.contains("R.drawable.ic_console_clock"));
        assertTrue(source.contains("selectedGamePlaytimePill.setCompoundDrawablePadding(dp(5))"));
        assertTrue(source.contains("playtimePillParams.leftMargin = dp(12)"));
        assertTrue(source.contains("ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)"));
        assertTrue(source.contains("selectedGameMetadata.setPadding(dp(portraitLayout ? 8 : 62)"));
        assertTrue(source.contains("descriptionParams.topMargin = dp(8)"));
        assertTrue(source.contains("selectedGameDescription.setLineSpacing(0, 1.25f)"));
        assertTrue(source.contains("expandedGameDescription.setLineSpacing(0, 1.25f)"));
        assertTrue(source.contains("TextView pill = text(\"\", 10"));
        assertTrue(source.contains("pill.setMinHeight(dp(24))"));
        assertTrue(resource("drawable/ic_console_discord.xml").contains(
                "android:fillColor=\"#FF9FAAB2\""));
        assertTrue(source.contains("? \"?\" : controller.percentage + \"%\","));
        assertTrue(source.contains("11, batteryColor, true"));
        assertTrue(source.contains("libraryTransitionCoordinator.beginTransition(\"__library__\")"));
        assertTrue(source.contains("libraryTransitionCoordinator.beginTransition(\"\")"));
        assertTrue(source.contains("!isDescendant(expandedGrid, focus)"));
        assertTrue(source.contains(
                "selectedGameTitleRow.setVisibility(View.INVISIBLE)"));
        assertTrue(source.contains(
                "getString(R.string.playnite_full_library), \"\", \"\")"));
        assertTrue(resource("values/strings.xml").contains("playnite_playtime_pill"));
        assertTrue(resource("values-pl/strings.xml").contains("playnite_last_played_pill"));
        assertTrue(source.contains("params.rightMargin = dp(16)"));
        assertTrue(expanded.contains("expandedGameDescription.setPadding(0, 0, 0, dp(20))"));
    }

    @Test public void libraryFiltersRestartTheGridAndSearchTypesOverIt() throws IOException {
        String source = consoleActivitySource();
        String host = source.substring(source.indexOf("private LibraryFiltersPanel.Host libraryFiltersHost("),
                source.indexOf("private ExpandedLibraryGrid createExpandedGrid("));
        String search = source.substring(source.indexOf("private void searchLibrary("),
                source.indexOf("protected boolean useTransparentWarmUpStartingWindow("));

        assertTrue(host.contains("if (sortOnly) renderExpandedLibraryFromStart(host);"));
        assertTrue(host.contains("else renderFilteredPlayniteLibraryFromStart(host);"));
        // Typing filters the grid without moving focus off the keyboard.
        assertTrue(search.contains("expandedGrid.showPosition(0)"));
        assertFalse(search.contains("focusPosition("));
        assertTrue(source.contains("librarySearch.handleKey(event)"));
        assertFalse(source.contains("consoleFeedback.showInput(getString(R.string.playnite_search"));
    }

    @Test public void resumeChipAndSmallEmulatorArtworkKeepTvFriendlyGeometry()
            throws IOException {
        String source = consoleActivitySource();
        String artwork = source.substring(source.indexOf("private void applyPlayniteBitmap("),
                source.indexOf("private BitmapDrawable filteredBitmapDrawable("));

        assertTrue(source.contains("new RelativeSizeSpan(1.5f)"));
        assertTrue(source.contains("playniteStateChipText(glyph, label, resumeSession)"));
        assertFalse(resource("values/strings.xml").contains("name=\"console_resume\">RESUME  ›"));
        assertFalse(resource("values-pl/strings.xml").contains("name=\"console_resume\">WZNÓW  ›"));
        assertTrue(artwork.contains("boolean lowResolution = bitmap.getWidth() < targetWidth"));
        assertTrue(artwork.contains("lowResolution ? ImageView.ScaleType.CENTER_INSIDE"));
        assertTrue(artwork.contains("if (lowResolution || modestTileCrop"));
        assertTrue(resource("drawable/ic_source_playnite.xml").contains(
                "android:scaleX=\"1.15\""));
    }

    @Test public void initialCarouselFocusAlwaysTargetsTheFirstCard() throws IOException {
        String source = consoleActivitySource();
        String prepared = source.substring(source.indexOf(
                        "protected final boolean prepareInitialCarouselFrame("),
                source.indexOf("protected final void completeInitialCarouselFrame("));
        String focus = source.substring(source.indexOf(
                        "private void requestPendingInitialGameFocus("),
                source.indexOf("private int maxCarouselGameCount("));

        assertTrue(prepared.contains("View target = firstFocusableChild(appRow)"));
        assertTrue(prepared.contains("appScroll.scrollTo(0, 0)"));
        assertTrue(focus.contains("View target = firstFocusableChild(appRow)"));
        assertFalse(focus.contains("lastCarouselGameId"));
        assertFalse(focus.contains("selected_playnite."));
    }

    @Test public void hostSettingsOwnThePerHostAutomaticWarmUpToggle() throws IOException {
        String source = consoleActivitySource();
        String menu = source.substring(source.indexOf("private View[] hostSettings("),
                source.indexOf("private View[] aboutSettings("));

        assertTrue(menu.contains("HostAutoWarmUpPreferences.isEnabled(preferences, host.uuid)"));
        assertTrue(menu.contains("HostAutoWarmUpPreferences.setEnabled("));
        assertTrue(menu.contains("R.string.console_auto_stream_warm_up_description"));
    }

    @Test public void providerCapabilitiesSurviveWarmUpAndResume() throws IOException {
        String source = consoleActivitySource();
        String arm = source.substring(source.indexOf("private void armPendingWarmUpRelay()"),
                source.indexOf("protected final void acceptPreparingHomeFrame()"));
        String resume = source.substring(source.indexOf("private void resumeSession("),
                source.indexOf("private String uniquePlayniteGameIdForRunningApp("));
        String suspended = source.substring(source.indexOf("private void resumeSuspendedSession("),
                source.indexOf("private void confirmTerminateSession("));
        int warmUpStart = source.indexOf("private void applyWarmUpIntent(");
        String warmUp = source.substring(warmUpStart,
                source.indexOf("private void launchPreparedStream(", warmUpStart));
        String restore = source.substring(source.indexOf("private PlayIntent providerGameIntent("),
                source.indexOf("private static String playniteStreamSettingsKey("));

        assertTrue(warmUp.contains("pendingGame.requiresConnector"));
        assertTrue(warmUp.contains("pendingGame.neutralStream"));
        assertTrue(warmUp.contains("pendingGame.startBeforeStream"));
        assertTrue(arm.contains("EXTRA_WARM_UP_PENDING_REQUIRES_CONNECTOR"));
        assertTrue(arm.contains("EXTRA_WARM_UP_PENDING_NEUTRAL_STREAM"));
        assertTrue(arm.contains("EXTRA_WARM_UP_PENDING_START_BEFORE_STREAM"));
        assertTrue(resume.contains("providerGameIntent("));
        assertTrue(suspended.contains("providerGameIntent("));
        assertTrue(restore.contains("game.requiresConnector"));
        assertTrue(restore.contains("game.usesNeutralStream()"));
        assertTrue(restore.contains("game.startBeforeStream"));
    }

    @Test public void incomingDiscordMessageAddsAReadableBlueIndicator() throws IOException {
        String source = consoleActivitySource();

        assertTrue(source.contains("setDiscordNotificationPending(true)"));
        assertTrue(source.contains("topBar.setDiscordUnread(pending)"));
        assertTrue(topBarSource().contains("DISCORD_UNREAD = 0xFF3B82F6"));
        assertTrue(topBarSource().contains("R.string.overlay_discord_unread"));
    }

    @Test public void discordUsesAWhiteGlyphWithASeparateStatusDot()
            throws IOException {
        String source = consoleActivitySource();

        assertTrue(source.contains("hostGatewayClient.getDiscordVoice(connection, true)"));
        assertTrue(source.contains("applyDiscordVoiceIndicator(voice)"));
        assertTrue(source.contains("DiscordPanelController.discordIndicatorColor("));
        assertTrue(topBarSource().contains("ColorStateList.valueOf(Color.WHITE)"));
        assertTrue(topBarSource().contains("canvas.drawCircle(x, y, dp(3), paint)"));
    }

    @Test public void carouselMetadataStopsAtTheScreenMidpointOnTv() throws IOException {
        String source = consoleActivitySource();
        String metadata = source.substring(source.indexOf(
                        "selectedGameMetadata = new LinearLayout(this)"),
                source.indexOf("debugLibrarySpacer = new View(this)"));

        assertTrue(metadata.contains(
                "portraitLayout ? ViewGroup.LayoutParams.MATCH_PARENT : dp(484)"));
        assertTrue(metadata.contains("selectedGameDescription.setMaxLines(6)"));
        assertTrue(metadata.contains(
                "dp(showCarouselGameDescription ? 140 : 36)"));
    }

    @Test public void guineaPigFactExistsInEnglishAndPolishLoadingMessages() throws IOException {
        assertTrue(resource("values/strings.xml").contains(
                "Did you know that the scientific name for the guinea pig is Cavia Porcellus?"));
        assertTrue(resource("values-pl/strings.xml").contains(
                "Czy wiesz, że naukowa nazwa świnki morskiej to Cavia Porcellus?"));
    }

    @Test public void dashboardAndLoadingCurtainUseDifferentArtworkPresentation()
            throws IOException {
        String source = consoleActivitySource();
        String ui = source.substring(source.indexOf("private FrameLayout buildUi()"),
                source.indexOf("private void deferSecondaryLayersAfterFirstLayout("));
        String loading = source.substring(source.indexOf(
                        "private String cachedLoadingArtworkPath("),
                source.indexOf("private ArtworkResult fetchPlayniteArtwork("));

        assertTrue(ui.contains("artworkBackdrop = new ImageView(this)"));
        assertTrue(ui.contains("artworkBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP)"));
        assertTrue(ui.contains("artworkBackdropNext = new ImageView(this)"));
        assertTrue(ui.contains("artworkBackdropNext.setScaleType(ImageView.ScaleType.CENTER_CROP)"));
        assertTrue(loading.contains("PlayniteArtworkSpec.forLoadingCurtain(item.game)"));
        assertFalse(loading.contains("item.game.coverKey"));
        assertFalse(loading.contains("\"cover\""));
        assertTrue(loading.contains("bounds.outWidth > bounds.outHeight"));
        assertTrue(source("src/main/java/com/limelight/console/PlayniteArtworkCache.java",
                "app/src/main/java/com/limelight/console/PlayniteArtworkCache.java")
                .contains("\"hero\".equals(kind)"));
    }

    @Test public void sourceMovesBesideCarouselTitleAndOntoGridArtwork() throws IOException {
        String source = consoleActivitySource();
        String card = source.substring(source.indexOf("private View playniteCard("),
                source.indexOf("private LinearLayout.LayoutParams playniteCardSpacing("));
        String binding = source.substring(source.indexOf("private void bindPlayniteCard("),
                source.indexOf("private void schedulePlayniteSelectionSave("));

        assertTrue(card.contains("ImageView source = new ImageView(this)"));
        assertTrue(card.contains("source.setTag(\"playnite.source\")"));
        assertTrue(card.contains("copy.addView(source, new LinearLayout.LayoutParams(dp(22), dp(22)))"));
        assertTrue(card.contains("copy.setVisibility(expandedCard"));
        assertTrue(binding.contains("int sourceIcon = playniteSourceIcon(sourceKey)"));
        assertTrue(binding.contains("source.setImageResource(sourceIcon)"));
        assertTrue(source.contains("R.drawable.ic_source_steam"));
        assertTrue(source.contains("R.drawable.ic_source_epic_games"));
        assertTrue(source.contains("R.drawable.ic_source_playnite"));
        assertTrue(source.contains("selectedGameSource.setImageResource(sourceIcon)"));
        assertTrue(source.contains("selectedGameSource.setContentDescription(getString("));
        assertTrue(binding.contains("R.string.playnite_source"));
    }

    @Test public void playniteTileMenuOwnsPerGameStreamOverridesUsedAtLaunch() throws IOException {
        String source = consoleActivitySource();
        String menu = source.substring(source.indexOf("private void showPlayniteGameActions("),
                source.indexOf("private void showPlayniteGameDetails("));
        String launch = source.substring(source.indexOf("private void playPlayniteGame("),
                source.indexOf("private ComputerDetails currentHost("));

        assertTrue(gameCardSource().contains("R.string.playnite_stream_settings"));
        assertTrue(menu.contains("new ConsoleAppStreamSettings(this, settingsScreen).open("));
        assertTrue(menu.contains("streamSettingsKey,\n                                    item.game.name, true,"));
        assertTrue(menu.contains("playniteStreamSettingsKey(host.uuid, item.stableId())"));
        assertTrue(launch.contains(
                "playniteStreamSettingsKey(host.uuid, game.playniteGameId)"));
    }

    @Test public void exactRunningGameOwnsBadgeAndGameOnlyStopBeforeSessionStop()
            throws IOException {
        String source = consoleActivitySource();
        String card = source.substring(source.indexOf("private View playniteCard("),
                source.indexOf("private LinearLayout.LayoutParams playniteCardSpacing("));
        String binding = source.substring(source.indexOf("private void bindPlayniteCard("),
                source.indexOf("private void schedulePlayniteSelectionSave("));
        String menu = source.substring(source.indexOf("private void showPlayniteGameActions("),
                source.indexOf("private void showPlayniteGameDetails("));
        String stop = source.substring(source.indexOf("private void requestEndPlayniteGame("),
                source.indexOf("private void showPlayniteGameDetails("));

        assertTrue(card.contains("running.setTag(\"playnite.running\")"));
        assertTrue(card.contains("running.setSingleLine(true)"));
        assertTrue(card.contains("running.setTextSize(6)"));
        assertTrue(card.contains("Gravity.TOP | Gravity.START"));
        assertTrue(binding.contains("running.setVisibility(runningSession"));
        assertTrue(binding.contains("R.string.playnite_running_badge_description"));
        assertTrue(source.contains("private boolean isFreshExactRunningManagedGame("));
        assertTrue(menu.contains("isFreshExactRunningManagedGame(host, item)"));
        String gameCard = gameCardSource();
        assertTrue(gameCard.indexOf("R.string.overlay_menu_end_game")
                < gameCard.indexOf("R.string.console_close_session"));
        assertTrue(menu.contains("confirmTerminateSession(host, item.stableId())"));
        assertTrue(stop.contains("stopActiveProviderGame(host, expectedGameId, true, confirmedKey.profileId)"));
        assertTrue(stop.contains("refreshSessionState(host.uuid)"));
        assertTrue(stop.contains("R.string.overlay_menu_end_game_failed"));
        assertTrue(stop.contains("refreshSessionProjection(host"));
        assertFalse(stop.contains("quitSunshineIfRunning"));
        assertFalse(stop.contains("quitApp"));
        assertFalse(stop.contains("stopConnection"));
        assertTrue(resource("values/strings.xml").contains("Other games and the stream will not be closed"));
        assertTrue(resource("values-pl/strings.xml").contains("Inne gry i stream nie zostaną zamknięte"));
    }

    @Test public void runningBadgeAndStopRequireCanonicalProcessEvidence() throws Exception {
        String source = consoleActivitySource();
        String badge = source.substring(source.indexOf(
                        "private boolean isFreshExactRunningManagedGame("),
                source.indexOf("private void bindPlayniteCard("));
        assertTrue(badge.contains("\"VERIFIED\".equals(snapshot.processEvidence)"));
        assertTrue(badge.contains("snapshot.processToken.isEmpty()"));
        assertFalse(badge.contains("activePlayniteGame"));
    }

    @Test public void stopResultDoesNotMutateAnAlternateActivitySnapshot() throws Exception {
        String source = consoleActivitySource();
        assertFalse(source.contains("markExactPlayniteGameIdle("));
        assertFalse(source.contains("shouldApplyExactStopResult("));
        assertTrue(source.contains("refreshSessionProjection(host"));
    }

    private static String consoleActivitySource() throws IOException {
        return source("src/main/java/com/limelight/console/ConsoleActivity.java",
                "app/src/main/java/com/limelight/console/ConsoleActivity.java");
    }

    private static String topBarSource() throws IOException {
        return source("src/main/java/com/limelight/console/ConsoleTopBar.java",
                "app/src/main/java/com/limelight/console/ConsoleTopBar.java");
    }

    private static String gameCardSource() throws IOException {
        return source("src/main/java/com/limelight/console/ConsoleGameCard.java",
                "app/src/main/java/com/limelight/console/ConsoleGameCard.java");
    }

    private static String hostPanelSource() throws IOException {
        return source("src/main/java/com/limelight/console/ConsoleHostPanel.java",
                "app/src/main/java/com/limelight/console/ConsoleHostPanel.java");
    }

    private static String resource(String relative) throws IOException {
        return source("src/main/res/" + relative, "app/src/main/res/" + relative);
    }

    private static String source(String modulePath, String repositoryPath) throws IOException {
        Path source = Paths.get(modulePath);
        if (!Files.exists(source)) source = Paths.get(repositoryPath);
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
