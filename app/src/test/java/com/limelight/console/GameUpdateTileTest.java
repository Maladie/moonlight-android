package com.limelight.console;

import com.limelight.gateway.GatewayConnection;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Steam's pending update on a library tile: shown, startable from the card, never an install. */
public class GameUpdateTileTest {
    private static final String ID = "00000001-0000-0000-0000-000000000000";

    private static PlayniteLibraryGame installed() {
        return new PlayniteLibraryGame(ID, "Game", true, false, false, 0L, "", "", "", "", 0,
                "Steam", "", false, "", "", "", "", -1, false);
    }

    @Test public void updateStateIsPartOfTheGameAndItsEquality() {
        PlayniteLibraryGame plain = installed();
        assertFalse(plain.updateRequired());
        PlayniteLibraryGame required = plain.withUpdate("required", -1);
        assertTrue(required.updateRequired());
        assertFalse(required.updateActive());
        PlayniteLibraryGame patching = plain.withUpdate("patching", 40);
        assertTrue(patching.updateActive());
        assertEquals(40, patching.updateProgress);
        assertFalse(required.equals(patching));
        assertFalse(plain.equals(required));
        assertEquals(plain, plain.withUpdate("", -1));
        assertEquals("the copy keeps everything else", plain.name, patching.name);
    }

    @Test public void cardOffersUpdateOnlyForAnInstalledGameWithNothingRunning() {
        ConsoleGameCard.Facts facts = new ConsoleGameCard.Facts();
        facts.installed = true;
        facts.canLaunch = true;
        assertFalse(rows(facts).contains(ConsoleGameCard.Action.UPDATE));
        facts.canUpdate = true;
        assertTrue(rows(facts).contains(ConsoleGameCard.Action.UPDATE));
        assertTrue("Play stays available beside the update",
                rows(facts).contains(ConsoleGameCard.Action.LAUNCH));
        facts.installed = false;
        assertFalse(rows(facts).contains(ConsoleGameCard.Action.UPDATE));
    }

    private static List<ConsoleGameCard.Action> rows(ConsoleGameCard.Facts facts) {
        return ConsoleGameCard.menu(facts).actions();
    }

    @Test public void startingAnUpdateIsNoPendingInstallation() {
        List<String> installs = new ArrayList<>();
        GameOperationsController controller = new GameOperationsController(
                new GameOperationsController.Gateway() {
                    @Override public void install(GatewayConnection connection, String gameId) {
                        installs.add(gameId);
                    }
                    @Override public void uninstall(GatewayConnection connection, String gameId)
                            throws IOException { }
                    @Override public void focusInstallation(GatewayConnection connection,
                                                            String gameId) throws IOException { }
                }, Runnable::run, Runnable::run);
        boolean[] result = new boolean[1];

        controller.requestUpdate("host", installed(), null, success -> result[0] = success);

        assertEquals(1, installs.size());
        assertTrue(result[0]);
        GameOperationsController.Presentation shown =
                controller.presentation("host", installed());
        assertEquals(GameOperationsController.Phase.IDLE, shown.phase);
        assertFalse("the host's operation state alone says it is running", shown.installActive);
        assertTrue(controller.reconcile("host",
                java.util.Collections.singletonList(installed())).isEmpty());
    }
}
