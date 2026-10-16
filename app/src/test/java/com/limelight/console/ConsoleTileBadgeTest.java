package com.limelight.console;

import com.limelight.R;
import com.limelight.gateway.GatewayConnection;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Every tile state of mockups/tile-badges-v2.html and the badge it shows. */
public class ConsoleTileBadgeTest {
    private static final String ID = "00000001-0000-0000-0000-000000000000";
    private static final PlayniteSessionPresentation.State READY =
            PlayniteSessionPresentation.State.READY;

    private final GameOperationsController controller = new GameOperationsController(
            new GameOperationsController.Gateway() {
                @Override public void install(GatewayConnection connection, String gameId) { }
                @Override public void uninstall(GatewayConnection connection, String gameId) { }
                @Override public void focusInstallation(GatewayConnection connection,
                                                        String gameId) { }
            }, Runnable::run, Runnable::run);

    private static PlayniteLibraryGame game(boolean installed, boolean attention,
                                            String operationState, int operationProgress) {
        return new PlayniteLibraryGame(ID, "Game", installed, false, false, 0L, "", "", "", "",
                0, "Steam", "", attention, "", "", "", operationState, operationProgress, false);
    }

    private static PlayniteLibraryGame installed() {
        return game(true, false, "", -1);
    }

    private ConsoleTileBadge badge(PlayniteLibraryGame game) {
        return badge(READY, false, game, true, false);
    }

    private ConsoleTileBadge badge(PlayniteSessionPresentation.State session, boolean queued,
                                   PlayniteLibraryGame game, boolean mapped,
                                   boolean childBlocked) {
        return ConsoleTileBadge.of(session, queued, controller.presentation("host", game), game,
                mapped, childBlocked);
    }

    private static void assertForm(ConsoleTileBadge badge, ConsoleTileBadge.Kind kind,
                                   int icon, int color, boolean filled, String percent,
                                   boolean bar) {
        assertEquals(kind, badge.kind);
        assertTrue(badge.visible());
        assertEquals(icon, badge.kind.icon);
        assertEquals(color, badge.kind.color);
        assertEquals(filled, badge.kind.filled);
        assertEquals(percent, badge.percent());
        assertEquals(bar, badge.kind.bar);
        assertEquals(filled ? color : ConsoleTileBadge.OUTLINED_FILL, badge.fillColor());
    }

    @Test public void readyGameShowsNoBadgeButIsStillSpokenAsReady() {
        ConsoleTileBadge badge = badge(installed());
        assertEquals(ConsoleTileBadge.Kind.READY, badge.kind);
        assertFalse(badge.visible());
        assertFalse(badge.kind.bar);
        assertEquals(R.string.playnite_game_installed, badge.kind.label);
    }

    @Test public void requiredUpdateIsAnOutlinedAmberIcon() {
        assertForm(badge(installed().withUpdate("required", -1)),
                ConsoleTileBadge.Kind.UPDATE_REQUIRED, R.drawable.ic_console_badge_update,
                ConsoleTileBadge.AMBER, false, "", false);
    }

    @Test public void updatingIsFilledAmberWithItsPercentAndBar() {
        ConsoleTileBadge badge = badge(installed().withUpdate("downloading", 42));
        assertForm(badge, ConsoleTileBadge.Kind.UPDATING, R.drawable.ic_console_badge_update,
                ConsoleTileBadge.AMBER, true, "42%", true);
        assertEquals(42, badge.progress);
        assertEquals(0xFF1C1405, badge.contentColor());
        assertEquals(R.string.playnite_update_downloading, badge.kind.label);
    }

    @Test public void updatingWithoutAPercentIsARoundIconOverAnIndeterminateBar() {
        ConsoleTileBadge badge = badge(installed().withUpdate("downloading", -1));
        assertForm(badge, ConsoleTileBadge.Kind.UPDATING, R.drawable.ic_console_badge_update,
                ConsoleTileBadge.AMBER, true, "", true);
        assertEquals(-1, badge.progress);
    }

    @Test public void patchingHasItsOwnIconWithAndWithoutPercent() {
        assertForm(badge(installed().withUpdate("patching", 87)),
                ConsoleTileBadge.Kind.PATCHING, R.drawable.ic_console_badge_patch,
                ConsoleTileBadge.AMBER, true, "87%", true);
        assertForm(badge(installed().withUpdate("patching", -1)),
                ConsoleTileBadge.Kind.PATCHING, R.drawable.ic_console_badge_patch,
                ConsoleTileBadge.AMBER, true, "", true);
        assertNotEquals(ConsoleTileBadge.Kind.PATCHING.icon, ConsoleTileBadge.Kind.UPDATING.icon);
    }

    @Test public void anUpdateStartedFromTheCardShowsTheOperationsPhase() {
        assertForm(badge(game(true, false, "patching", 10)),
                ConsoleTileBadge.Kind.PATCHING, R.drawable.ic_console_badge_patch,
                ConsoleTileBadge.AMBER, true, "10%", true);
        assertForm(badge(game(true, false, "downloading", 55)),
                ConsoleTileBadge.Kind.UPDATING, R.drawable.ic_console_badge_update,
                ConsoleTileBadge.AMBER, true, "55%", true);
    }

    @Test public void installingIsAnArrowIntoATrayWithPercent() {
        assertForm(badge(game(false, false, "installing", 37)),
                ConsoleTileBadge.Kind.INSTALLING, R.drawable.ic_console_badge_install,
                ConsoleTileBadge.AMBER, true, "37%", true);
    }

    @Test public void attentionIsFilledRedAndOutranksTheInstall() {
        ConsoleTileBadge badge = badge(game(false, true, "attention_required", 40));
        assertForm(badge, ConsoleTileBadge.Kind.ATTENTION, R.drawable.ic_console_badge_attention,
                ConsoleTileBadge.RED, true, "", false);
        assertEquals(0xFF1E0807, badge.contentColor());
        assertEquals(R.string.playnite_install_attention, badge.kind.label);
    }

    @Test public void runningOrRecoveringGameResumesWithAFilledBluePlay() {
        for (PlayniteSessionPresentation.State session : new PlayniteSessionPresentation.State[]{
                PlayniteSessionPresentation.State.RESUME_ACTIVE,
                PlayniteSessionPresentation.State.RECOVERY_REQUIRED}) {
            ConsoleTileBadge badge = badge(session, true,
                    installed().withUpdate("required", -1), true, false);
            assertForm(badge, ConsoleTileBadge.Kind.RESUME, R.drawable.ic_console_badge_play,
                    ConsoleTileBadge.BLUE, true, "", false);
        }
    }

    @Test public void suspendedSessionIsAnOutlinedBluePauseNotAClock() {
        ConsoleTileBadge badge = badge(PlayniteSessionPresentation.State.RESUME_SUSPENDED,
                false, installed(), true, false);
        assertForm(badge, ConsoleTileBadge.Kind.SUSPENDED, R.drawable.ic_console_badge_pause,
                ConsoleTileBadge.BLUE, false, "", false);
        assertEquals(ConsoleTileBadge.BLUE, badge.contentColor());
        assertNotEquals(ConsoleTileBadge.Kind.QUEUED.icon, badge.kind.icon);
    }

    @Test public void queuedPlayIsAnOutlinedAmberClock() {
        ConsoleTileBadge badge = badge(READY, true, installed(), true, false);
        assertForm(badge, ConsoleTileBadge.Kind.QUEUED, R.drawable.ic_console_badge_queued,
                ConsoleTileBadge.AMBER, false, "", false);
        assertEquals(R.string.console_queued, badge.kind.label);
    }

    @Test public void notInstalledIsAnOutlinedGreyCloud() {
        ConsoleTileBadge badge = badge(game(false, false, "", -1));
        assertForm(badge, ConsoleTileBadge.Kind.NOT_INSTALLED, R.drawable.ic_console_badge_cloud,
                ConsoleTileBadge.GREY, false, "", false);
        assertEquals(0xFFE6EBEF, badge.contentColor());
    }

    @Test public void unmappedGameIsAnOutlinedRedCrossedCircle() {
        assertForm(badge(READY, false, installed(), false, false),
                ConsoleTileBadge.Kind.NOT_CONFIGURED, R.drawable.ic_console_badge_not_configured,
                ConsoleTileBadge.RED, false, "", false);
    }

    @Test public void uninstallingIsAnArrowOutOfATrayWithoutPercent() {
        ConsoleTileBadge badge = badge(game(true, false, "uninstalling", 30));
        assertForm(badge, ConsoleTileBadge.Kind.UNINSTALLING,
                R.drawable.ic_console_badge_uninstall, ConsoleTileBadge.AMBER, true, "", true);
        assertEquals(-1, badge.progress);
    }

    @Test public void childBlockedGameShowsNothing() {
        ConsoleTileBadge badge = badge(READY, true,
                installed().withUpdate("downloading", 42), true, true);
        assertEquals(ConsoleTileBadge.Kind.NONE, badge.kind);
        assertFalse(badge.visible());
        assertFalse(badge.kind.bar);
        assertEquals(0, badge.kind.label);
    }

    @Test public void panelTextTakesTheBadgeHue() {
        assertEquals(0xFFFFE2A8, badge(installed().withUpdate("required", -1)).panelTextColor());
        assertEquals(0xFFFFC4BE,
                badge(READY, false, installed(), false, false).panelTextColor());
        assertEquals(0xFFE1F7FF, badge(PlayniteSessionPresentation.State.RESUME_ACTIVE, false,
                installed(), true, false).panelTextColor());
    }
}
