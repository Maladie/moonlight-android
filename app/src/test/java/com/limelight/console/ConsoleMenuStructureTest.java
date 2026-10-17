package com.limelight.console;

import com.limelight.R;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleMenuStructureTest {
    @Test public void hostPanelPutsMainActionsFirstAndDestructiveLast() {
        ConsoleHostPanel.Facts facts = new ConsoleHostPanel.Facts();
        facts.reachable = true;
        facts.online = true;
        facts.paired = true;
        facts.gatewayAvailable = true;
        facts.streamRunning = true;
        facts.inHost = true;
        ConsoleActionMenu<ConsoleHostPanel.Action> menu = ConsoleHostPanel.menu(facts);

        assertEquals(Arrays.asList(ConsoleHostPanel.Action.DESKTOP,
                ConsoleHostPanel.Action.SLEEP, ConsoleHostPanel.Action.CLOSE_SESSION,
                ConsoleHostPanel.Action.TEST_CONNECTION, ConsoleHostPanel.Action.CHANGE_HOST,
                ConsoleHostPanel.Action.HARD_TERMINATE, ConsoleHostPanel.Action.UNPAIR),
                menu.actions());
        assertEquals(Arrays.asList(R.string.console_section_more), sections(menu));
        assertEquals(Arrays.asList(ConsoleHostPanel.Action.HARD_TERMINATE,
                ConsoleHostPanel.Action.UNPAIR), destructive(menu));
    }

    @Test public void activeSessionLeadsWithReturnCloseAndSleep() {
        ConsoleHostPanel.Facts facts = new ConsoleHostPanel.Facts();
        facts.reachable = true;
        facts.online = true;
        facts.paired = true;
        facts.gatewayAvailable = true;
        facts.streamRunning = true;
        facts.sessionActive = true;
        facts.resumeAvailable = true;
        ConsoleActionMenu<ConsoleHostPanel.Action> menu = ConsoleHostPanel.menu(facts);

        assertEquals(Arrays.asList(ConsoleHostPanel.Action.RESUME,
                ConsoleHostPanel.Action.CLOSE_SESSION, ConsoleHostPanel.Action.SLEEP,
                ConsoleHostPanel.Action.TEST_CONNECTION, ConsoleHostPanel.Action.HARD_TERMINATE,
                ConsoleHostPanel.Action.UNPAIR,
                ConsoleHostPanel.Action.REMOVE), menu.actions());
        assertTrue(menu.items.get(0).enabled);

        facts.resumeAvailable = false;
        assertFalse(ConsoleHostPanel.menu(facts).items.get(0).enabled);
    }

    @Test public void activeSessionClosesEvenWithTheStreamerUnknown() {
        ConsoleHostPanel.Facts facts = new ConsoleHostPanel.Facts();
        facts.online = true;
        facts.paired = true;
        facts.sessionActive = true;
        assertTrue(closeEnabled(ConsoleHostPanel.menu(facts)));
        facts.sessionActive = false;
        assertFalse(closeEnabled(ConsoleHostPanel.menu(facts)));
    }

    private static boolean closeEnabled(ConsoleActionMenu<ConsoleHostPanel.Action> menu) {
        for (ConsoleActionMenu.Item<ConsoleHostPanel.Action> item : menu.items) {
            if (item.action == ConsoleHostPanel.Action.CLOSE_SESSION) return item.enabled;
        }
        throw new AssertionError("no close action");
    }

    @Test public void offlineHostOffersWakeInsteadOfSleep() {
        ConsoleHostPanel.Facts facts = new ConsoleHostPanel.Facts();
        facts.canWake = true;
        List<ConsoleHostPanel.Action> actions = ConsoleHostPanel.menu(facts).actions();
        assertTrue(actions.contains(ConsoleHostPanel.Action.WAKE));
        assertFalse(actions.contains(ConsoleHostPanel.Action.SLEEP));
        // From host selection there is no host to leave.
        assertFalse(actions.contains(ConsoleHostPanel.Action.CHANGE_HOST));
    }

    @Test public void hostSelectionOffersRemoveLastEvenForAnUnreachableUnpairedHost() {
        ConsoleActionMenu<ConsoleHostPanel.Action> menu =
                ConsoleHostPanel.menu(new ConsoleHostPanel.Facts());

        List<ConsoleHostPanel.Action> destructive = destructive(menu);
        assertEquals(ConsoleHostPanel.Action.REMOVE, destructive.get(destructive.size() - 1));
        assertTrue(menu.items.get(menu.items.size() - 1).enabled);

        ConsoleHostPanel.Facts inHost = new ConsoleHostPanel.Facts();
        inHost.inHost = true;
        assertFalse(ConsoleHostPanel.menu(inHost).actions()
                .contains(ConsoleHostPanel.Action.REMOVE));
    }

    @Test public void idleInstalledGameShowsOnlyMeaningfulGroups() {
        ConsoleGameCard.Facts facts = new ConsoleGameCard.Facts();
        facts.installed = true;
        facts.canLaunch = true;
        facts.canUninstall = true;
        ConsoleActionMenu<ConsoleGameCard.Action> menu = ConsoleGameCard.menu(facts);

        assertEquals(ConsoleGameCard.Action.LAUNCH, menu.actions().get(0));
        assertEquals(Arrays.asList(R.string.console_options_section_streaming,
                R.string.console_settings_library), sections(menu));
        assertEquals(Arrays.asList(ConsoleGameCard.Action.UNINSTALL), destructive(menu));
        assertFalse(menu.actions().contains(ConsoleGameCard.Action.END_GAME));
    }

    @Test public void runningGameResumesAndOffersTheSessionGroup() {
        ConsoleGameCard.Facts facts = new ConsoleGameCard.Facts();
        facts.installed = true;
        facts.canLaunch = true;
        facts.gameRunning = true;
        facts.sessionActive = true;
        facts.childSharing = true;
        facts.hidden = true;
        ConsoleActionMenu<ConsoleGameCard.Action> menu = ConsoleGameCard.menu(facts);

        assertEquals(Arrays.asList(ConsoleGameCard.Action.RESUME,
                ConsoleGameCard.Action.END_GAME, ConsoleGameCard.Action.CLOSE_GAME_AND_STREAM,
                ConsoleGameCard.Action.STREAM_SETTINGS, ConsoleGameCard.Action.OPTIMIZE_STREAMING,
                ConsoleGameCard.Action.SHARE_WITH_CHILD, ConsoleGameCard.Action.SHOW,
                ConsoleGameCard.Action.DETAILS), menu.actions());
        assertEquals(Arrays.asList(R.string.console_section_session,
                R.string.console_options_section_streaming, R.string.console_section_family,
                R.string.console_settings_library), sections(menu));
    }

    @Test public void installAttentionIsTheMainAction() {
        ConsoleGameCard.Facts facts = new ConsoleGameCard.Facts();
        facts.installing = true;
        facts.installNeedsAttention = true;
        assertEquals(ConsoleGameCard.Action.CONTINUE_INSTALL,
                ConsoleGameCard.menu(facts).actions().get(0));
    }

    private static <A> List<Integer> sections(ConsoleActionMenu<A> menu) {
        List<Integer> sections = new ArrayList<>();
        for (ConsoleActionMenu.Item<A> item : menu.items) {
            if (item.section != 0) sections.add(item.section);
        }
        return sections;
    }

    private static <A> List<A> destructive(ConsoleActionMenu<A> menu) {
        List<A> actions = new ArrayList<>();
        for (ConsoleActionMenu.Item<A> item : menu.items) {
            if (item.destructive) actions.add(item.action);
        }
        return actions;
    }
}
