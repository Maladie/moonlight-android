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
        assertFalse(quickActions.contains("global.quick_launch"));
    }

    @Test public void hostStatusOpensTheExistingMenuWithSessionTermination() throws IOException {
        String source = consoleActivitySource();
        String selector = source.substring(source.indexOf("hostSelector = compactButton("),
                source.indexOf("LinearLayout.LayoutParams selectorParams"));
        String menu = source.substring(source.indexOf("private void showHostSelectionOptions("),
                source.indexOf("private TextView hostSelectionMenuAction("));

        assertTrue(selector.contains("showHostSelectionOptions(host)"));
        assertTrue(menu.contains("R.string.overlay_menu_quit_session"));
        assertTrue(menu.contains("confirmTerminateSession(host)"));
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
        assertTrue(update.contains("R.string.console_host_status_description"));
        assertFalse(update.contains("console_host_power_sleep_description"));
    }

    @Test public void compactCardsLeaveRoomForStateAndInstallProgress() throws IOException {
        String source = consoleActivitySource();
        String card = source.substring(source.indexOf("private View playniteCard("),
                source.indexOf("private LinearLayout.LayoutParams matchLinearWidth("));
        String state = source.substring(source.indexOf("private String playniteStateChipLabel("),
                source.indexOf("private void stylePlayniteStateChip("));
        String expanded = source.substring(source.indexOf("expandedGameDescription = text("),
                source.indexOf("details.addView(expandedGameTitle"));

        assertTrue(card.contains("stateParams.topMargin = dp(5)"));
        assertTrue(state.contains("CONSOLE_UI_V2 ? operation.progress + \"%\""));
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
        assertTrue(sourceFilter.contains("renderExpandedLibraryFromStart(host)"));
        assertTrue(filter.contains("renderExpandedLibraryFromStart(host)"));
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

    @Test public void incomingDiscordMessageAddsAReadableBlueIndicator() throws IOException {
        String source = consoleActivitySource();

        assertTrue(source.contains("setDiscordNotificationPending(true)"));
        assertTrue(source.contains("dotPaint.setColor(0xFF3B82F6)"));
        assertTrue(source.contains("canvas.drawCircle("));
        assertTrue(source.contains("R.string.overlay_discord_unread"));
    }

    @Test public void guineaPigFactExistsInEnglishAndPolishLoadingMessages() throws IOException {
        assertTrue(resource("values/strings.xml").contains(
                "Did you know that the scientific name for the guinea pig is Cavia Porcellus?"));
        assertTrue(resource("values-pl/strings.xml").contains(
                "Czy wiesz, że naukowa nazwa świnki morskiej to Cavia Porcellus?"));
    }

    @Test public void everyPlayniteCardBindsARealSourceIconInsteadOfLetters() throws IOException {
        String source = consoleActivitySource();
        String card = source.substring(source.indexOf("private View playniteCard("),
                source.indexOf("private LinearLayout.LayoutParams playniteCardSpacing("));
        String binding = source.substring(source.indexOf("private void bindPlayniteCard("),
                source.indexOf("private void schedulePlayniteSelectionSave("));

        assertTrue(card.contains("ImageView source = new ImageView(this)"));
        assertTrue(card.contains("source.setTag(\"playnite.source\")"));
        assertTrue(binding.contains("source.setImageResource(sourceIcon)"));
        assertTrue(binding.contains("R.drawable.ic_source_steam"));
        assertTrue(binding.contains("R.drawable.ic_source_epic_games"));
        assertTrue(binding.contains("R.drawable.ic_source_playnite"));
        assertTrue(binding.contains("default: return 0"));
        assertFalse(binding.contains("PlayniteLibrarySources.badge(sourceKey)"));
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
        assertTrue(launch.contains("playniteStreamSettingsKey(host.uuid, gameId)"));
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
        assertTrue(card.contains("Gravity.BOTTOM | Gravity.START"));
        assertTrue(binding.contains("running.setVisibility(runningSession"));
        assertTrue(binding.contains("R.string.playnite_running_badge_description"));
        assertTrue(source.contains("private boolean isFreshExactRunningManagedGame("));
        assertTrue(menu.contains("isFreshExactRunningManagedGame(host, item)"));
        assertTrue(menu.indexOf("R.string.overlay_menu_end_game")
                < menu.indexOf("R.string.overlay_menu_quit_session"));
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
