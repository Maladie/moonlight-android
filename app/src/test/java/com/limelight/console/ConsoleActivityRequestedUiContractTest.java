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
        String quickActions = source.substring(source.indexOf("private void buildQuickActions()"),
                source.indexOf("private ImageButton addQuickAction("));

        assertFalse(source.contains("launchPlayniteButton"));
        assertFalse(source.contains("launchDesktopButton"));
        assertFalse(quickActions.contains("global.quick_launch"));
    }

    @Test public void hostStatusClosesOnlyTheStreamAndKeepsHardTermination() throws IOException {
        String source = consoleActivitySource();
        String selector = source.substring(source.indexOf("hostSelector = compactButton("),
                source.indexOf("LinearLayout.LayoutParams selectorParams"));
        String menu = source.substring(source.indexOf("private void showHostSelectionOptions("),
                source.indexOf("private TextView hostSelectionMenuAction("));

        assertTrue(selector.contains("showHostSelectionOptions(host)"));
        assertTrue(menu.contains("R.string.console_close_stream"));
        assertTrue(menu.contains("confirmCloseHostStream(host)"));
        assertFalse(menu.contains("confirmTerminateSession(host)"));
        assertTrue(menu.contains("R.string.console_hard_terminate_session"));
        assertTrue(menu.contains("confirmHardTerminateSession(host)"));
        assertTrue(menu.contains("online && paired && gatewayAvailable"));
        assertTrue(menu.contains("R.string.console_launch_desktop"));
        assertTrue(menu.contains("launchDesktopSession(host)"));
    }

    @Test public void focusedHostRemainsAStableTwoLineStatusNotAPowerAction()
            throws IOException {
        String source = consoleActivitySource();
        String selector = source.substring(source.indexOf("hostSelector = compactButton("),
                source.indexOf("LinearLayout.LayoutParams selectorParams"));
        String label = source.substring(source.indexOf("private void updateHostPowerLabel()"),
                source.indexOf("private String compactHostStatus("));
        String update = source.substring(source.indexOf("private void updateHostSelector()"),
                source.indexOf("private void updateHostPowerLabel()"));

        assertTrue(selector.contains("hostSelector.setMaxLines(2)"));
        assertTrue(selector.contains("TextUtils.TruncateAt.END"));
        assertTrue(label.contains("R.string.console_host_status"));
        assertFalse(label.contains("console_host_power_focused_sleep"));
        assertFalse(label.contains("console_host_power_focused_wake"));
        assertTrue(selector.contains("hostSelector.setTextSize(11)"));
        assertTrue(selector.contains("hostSelector.setMinHeight(dp(36))"));
        assertTrue(label.contains("hostIcon.setTint(stateColor)"));
        assertTrue(label.contains("hostIcon.setBounds(0, 0, dp(24), dp(24))"));
        assertTrue(label.contains("hostSelector.setCompoundDrawables(hostIcon"));
        assertFalse(label.contains("BulletSpan"));
        assertFalse(resource("values/strings.xml").contains(
                "name=\"console_host_status\">●"));
        assertTrue(update.contains("R.string.console_host_status_description"));
        assertFalse(update.contains("console_host_power_sleep_description"));
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
        assertTrue(card.contains("(expandedCard ? Gravity.TOP : Gravity.BOTTOM) | Gravity.END"));
        assertTrue(card.contains("else stateParams.bottomMargin = dp(5)"));
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
        assertTrue(binding.contains("colors.setSaturation(.25f)"));
        assertTrue(binding.contains("backdrop.setColorFilter(muted)"));
        assertTrue(stateStyle.contains("fill = 0xE014241B"));
        assertTrue(stateStyle.contains("state.setShadowLayer"));
        assertTrue(state.contains("CONSOLE_UI_V2 ? operation.progress + \"%\""));
        assertTrue(state.contains("CONSOLE_UI_V2 ? \"\""));
        assertTrue(source.contains("params.height = dp(123)"));
        assertTrue(source.contains("* 131)"));
        assertTrue(source.contains("homeContent.setPadding(dp(54), dp(14), dp(54), dp(24))"));
        assertTrue(source.contains("headerParams.bottomMargin = dp(28)"));
        assertTrue(source.contains("titleParams.topMargin = dp(103)"));
        assertTrue(source.contains("carouselStage.addView(playniteLibraryStatus, statusParams)"));
        assertTrue(source.contains("statusParams.bottomMargin = dp(2)"));
        assertFalse(source.contains(
                "CONSOLE_UI_V2 && textId == R.string.playnite_data_current"));
        assertTrue(source.contains("hintParams.topMargin = dp(66)"));
        assertTrue(source.contains("ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)"));
        assertTrue(source.contains("Gravity.TOP | Gravity.START"));
        assertTrue(source.contains("positionQuickActionHint(button)"));
        assertTrue(source.contains("R.dimen.console_space_s"));
        assertTrue(source.contains("new int[]{0x6004070B, 0x3404070B, 0x1404070B, 0x0004070B}"));
        assertTrue(source.contains("ARTWORK_FOCUS_SETTLE_MS = 420L"));
        assertTrue(source.contains("ARTWORK_CROSSFADE_MS = 560L"));
        assertTrue(source.contains("halo.setShape(GradientDrawable.OVAL)"));
        assertTrue(source.contains("disc.setShape(GradientDrawable.OVAL)"));
        assertTrue(source.contains("animateScale(button, focused ? 1.10f : 1f)"));
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
        assertTrue(source.contains("setPadding(dp(16), dp(6), dp(16), dp(6))"));
        assertTrue(source.contains("selectedGameSource = new ImageView(this)"));
        assertTrue(source.contains("new LinearLayout.LayoutParams(dp(18), dp(18))"));
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
        assertTrue(source.contains("dp(CAROUSEL_FOCUSED_CARD_WIDTH_DP) + dp(8)"));
        assertTrue(source.contains("card.getParent() != appRow || !card.hasFocus()"));
        assertTrue(source.contains("tile.getLeft() - (homeCarousel ? previousCards"));
        assertTrue(source.contains("selectedGameLastPlayedPill = metadataPill()"));
        assertTrue(source.contains("selectedGamePlaytimePill = metadataPill()"));
        assertTrue(source.contains("R.drawable.ic_console_clock"));
        assertTrue(source.contains("selectedGamePlaytimePill.setCompoundDrawablePadding(dp(5))"));
        assertTrue(source.contains("ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)"));
        assertTrue(source.contains("selectedGameMetadata.setPadding(dp(8), dp(8), dp(12), dp(8))"));
        assertTrue(source.contains("descriptionParams.topMargin = dp(8)"));
        assertTrue(source.contains("TextView pill = text(\"\", 10"));
        assertTrue(source.contains("pill.setMinHeight(dp(24))"));
        assertTrue(source.contains("quickActionHint = text(\"\", 12"));
        assertTrue(source.contains("+ (discord ? dp(14) : 0)"));
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
        assertTrue(resource("values-pl/strings.xml").contains(
                "name=\"console_action_stream_settings\">Opcje<"));
        assertTrue(source.contains("params.rightMargin = dp(16)"));
        assertTrue(expanded.contains("expandedGameDescription.setPadding(0, 0, 0, dp(20))"));
    }

    @Test public void appliedLibraryFiltersFocusFirstCardWhileCancelReturnsToAnchor()
            throws IOException {
        String source = consoleActivitySource();
        String sourceFilter = source.substring(source.indexOf(
                        "private void showPlayniteSourceSelector("),
                source.indexOf("private void showPlayniteSortSelector("));
        String filter = source.substring(source.indexOf("private void applyPlayniteFilter("),
                source.indexOf("private void renderPlayniteLibrary("));
        String sort = source.substring(source.indexOf("private void showPlayniteSortSelector("),
                source.indexOf("private void showPlayniteSearchDialog("));
        String search = source.substring(source.indexOf("private void applyExpandedSearch("),
                source.indexOf("private LinearLayout popupMenu("));

        assertTrue(sourceFilter.contains("if (!applied[0])"));
        assertTrue(sourceFilter.contains("anchor.post(anchor::requestFocus)"));
        assertTrue(sourceFilter.contains("renderFilteredPlayniteLibraryFromStart(host)"));
        assertTrue(sourceFilter.contains("stylePlayniteFilterOption(done, false, focused)"));
        assertFalse(sourceFilter.contains("styleSourceFilterOption(done, false, focused)"));
        assertTrue(filter.contains("renderFilteredPlayniteLibraryFromStart(host)"));
        assertTrue(filter.contains("renderPlayniteLibrary(host, currentSunshineApps)"));
        assertTrue(sort.contains("renderExpandedLibraryFromStart(host)"));
        assertTrue(search.contains("renderExpandedLibraryFromStart(host)"));
        assertTrue(source.contains("if (!applied[0] && expandedSearchButton != null)"));
        assertFalse(sort.contains("expandedSortButton.post(expandedSortButton::requestFocus)"));
    }

    @Test public void hostMenuOwnsThePerHostAutomaticWarmUpToggle() throws IOException {
        String source = consoleActivitySource();
        String menu = source.substring(source.indexOf("private void showHostSelectionOptions("),
                source.indexOf("private TextView hostSelectionMenuAction("));

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
        assertTrue(source.contains("notificationPaint.setColor(0xFF3B82F6)"));
        assertTrue(source.contains("canvas.drawCircle("));
        assertTrue(source.contains("R.string.overlay_discord_unread"));
    }

    @Test public void discordUsesAWhiteGlyphWithASeparateStatusDot()
            throws IOException {
        String source = consoleActivitySource();
        String style = source.substring(source.indexOf("private void styleQuickAction("),
                source.indexOf("private void animateScale("));
        String action = source.substring(source.indexOf("private ImageButton addQuickAction("),
                source.indexOf("private void positionQuickActionHint("));

        assertTrue(source.contains("hostGatewayClient.getDiscordVoice(connection, true)"));
        assertTrue(source.contains("applyDiscordVoiceIndicator(voice)"));
        assertTrue(source.contains("DiscordPanelController.discordIndicatorColor("));
        assertTrue(style.contains("disc.setColor(0xFFF4F6F7)"));
        assertTrue(style.contains("discord ? 0xFFF4F6F7 : tint"));
        assertTrue(action.contains("statusPaint.setColor(discordIndicatorColor)"));
        assertTrue(action.contains("getWidth() - dp(10), getHeight() - dp(10)"));
        assertTrue(action.contains("dp(3), statusPaint"));
        assertFalse(style.contains("styleDiscordGlyph"));
        assertFalse(style.contains("outline.setTint"));
    }

    @Test public void carouselMetadataStopsAtTheScreenMidpointOnTv() throws IOException {
        String source = consoleActivitySource();
        String metadata = source.substring(source.indexOf(
                        "selectedGameMetadata = new LinearLayout(this)"),
                source.indexOf("debugLibrarySpacer = new View(this)"));

        assertTrue(metadata.contains(
                "portraitLayout ? ViewGroup.LayoutParams.MATCH_PARENT : dp(430)"));
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
        assertTrue(card.contains("copy.addView(source, new LinearLayout.LayoutParams(dp(18), dp(18)))"));
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

        assertTrue(menu.contains("R.string.playnite_stream_settings"));
        assertTrue(menu.contains("AppStreamSettings.EXTRA_INHERIT_APP_SETTINGS"));
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
        assertTrue(menu.indexOf("R.string.overlay_menu_end_game")
                < menu.indexOf("R.string.overlay_menu_quit_session"));
        assertTrue(menu.contains("confirmTerminateSession(host, item.stableId())"));
        assertTrue(stop.contains("stopActiveProviderGame(host, item.stableId(), true)"));
        assertTrue(stop.contains("markExactPlayniteGameIdle(host.uuid, expectedAppId"));
        assertTrue(stop.contains("refreshSessionState(host.uuid)"));
        assertTrue(stop.contains("R.string.overlay_menu_end_game_failed"));
        assertTrue(stop.indexOf("if (stopped && sameStopScope)")
                < stop.indexOf("markExactPlayniteGameIdle("));
        assertFalse(stop.contains("quitSunshineIfRunning"));
        assertFalse(stop.contains("quitApp"));
        assertFalse(stop.contains("stopConnection"));
        assertTrue(resource("values/strings.xml").contains("Other games and the stream will not be closed"));
        assertTrue(resource("values-pl/strings.xml").contains("Inne gry i stream nie zostaną zamknięte"));
    }

    @Test public void runningBadgeAndStopRequireFreshExactBridgeFact() {
        long now = 10_000L;
        assertTrue(ConsoleActivity.isFreshExactBridgeRunning(
                "host", "HOST", 42, "game", "running", "GAME", 42,
                9_000L, now, 5_000L));
        for (String state : new String[] {"unknown", "stopping", "idle", "ambiguous"}) {
            assertFalse(ConsoleActivity.isFreshExactBridgeRunning(
                    "host", "host", 42, "game", state, "game", 42,
                    9_000L, now, 5_000L));
        }
        assertFalse(ConsoleActivity.isFreshExactBridgeRunning(
                "host", "host", 42, "game", "running", "other", 42,
                9_000L, now, 5_000L));
        assertFalse(ConsoleActivity.isFreshExactBridgeRunning(
                "host", "host", 42, "game", "running", "game", 7,
                9_000L, now, 5_000L));
        assertFalse(ConsoleActivity.isFreshExactBridgeRunning(
                "host", "other-host", 42, "game", "running", "game", 42,
                9_000L, now, 5_000L));
        assertFalse(ConsoleActivity.isFreshExactBridgeRunning(
                "host", "host", 42, "game", "running", "game", 42,
                1_000L, now, 5_000L));
    }

    @Test public void stopResultCannotClearFailureNewerObservationOrForeignGame() {
        assertTrue(ConsoleActivity.shouldApplyExactStopResult(
                true, 42, "game", 100L, 42, "running", "game", 100L));
        assertFalse(ConsoleActivity.shouldApplyExactStopResult(
                false, 42, "game", 100L, 42, "running", "game", 100L));
        assertFalse(ConsoleActivity.shouldApplyExactStopResult(
                true, 42, "game", 100L, 42, "running", "game", 101L));
        assertFalse(ConsoleActivity.shouldApplyExactStopResult(
                true, 42, "game", 100L, 42, "running", "other", 100L));
        assertFalse(ConsoleActivity.shouldApplyExactStopResult(
                true, 42, "game", 100L, 7, "running", "game", 100L));
    }

    private static String consoleActivitySource() throws IOException {
        return source("src/main/java/com/limelight/console/ConsoleActivity.java",
                "app/src/main/java/com/limelight/console/ConsoleActivity.java");
    }

    private static String resource(String relative) throws IOException {
        return source("src/main/res/" + relative, "app/src/main/res/" + relative);
    }

    private static String source(String modulePath, String repositoryPath) throws IOException {
        Path source = Paths.get(modulePath);
        if (!Files.exists(source)) source = Paths.get(repositoryPath);
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
