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
                source.indexOf("optionsButton = hostSelector"));
        String menu = source.substring(source.indexOf("private void showHostSelectionOptions("),
                source.indexOf("private TextView hostSelectionMenuAction("));

        assertTrue(selector.contains("showHostSelectionOptions(host)"));
        assertTrue(menu.contains("R.string.overlay_menu_quit_session"));
        assertTrue(menu.contains("confirmTerminateSession(host)"));
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
