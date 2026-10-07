package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class DiscordCommunityLegendTest {
    private static DiscordCommunityLegend.Item item(String key) {
        return new DiscordCommunityLegend.Item(key, false, key);
    }

    @Test public void shortLegendIsShownWhole() {
        List<DiscordCommunityLegend.Item> all = Arrays.asList(item("L1/R1"), item("OK"), item("Wstecz"));
        assertSame(all, DiscordCommunityLegend.visible(all, item("More")));
    }

    @Test public void chatLegendKeepsThreeAndEndsWithMore() {
        DiscordCommunityLegend.Item more = item("More");
        List<DiscordCommunityLegend.Item> all = Arrays.asList(item("L1/R1"), item("R2"),
                item("Stick"), item("Wstecz"), item("L2"), item("X"), item("R3"));
        List<DiscordCommunityLegend.Item> row = DiscordCommunityLegend.visible(all, more);
        assertEquals(DiscordCommunityLegend.MAX_ITEMS, row.size());
        assertEquals(all.subList(0, 3), row.subList(0, 3));
        assertSame(more, row.get(3));
    }

    @Test public void moreKeyAvoidsTheDmShortcutTrigger() {
        for (String trigger : new String[]{"select", "guide", "lb_rb"}) {
            assertFalse(DiscordCommunityLegend.moreOnSelect(trigger));
            assertTrue(DiscordCommunityLegend.isMoreKey(trigger, KeyEvent.KEYCODE_BUTTON_START));
            assertTrue(DiscordCommunityLegend.isMoreKey(trigger, KeyEvent.KEYCODE_MENU));
            assertFalse(DiscordCommunityLegend.isMoreKey(trigger, KeyEvent.KEYCODE_BUTTON_SELECT));
        }
        // Start is the DM shortcut: "Więcej" moves to Select, which a chat does not use.
        assertTrue(DiscordCommunityLegend.moreOnSelect("start"));
        assertTrue(DiscordCommunityLegend.isMoreKey("start", KeyEvent.KEYCODE_BUTTON_SELECT));
        assertFalse(DiscordCommunityLegend.isMoreKey("start", KeyEvent.KEYCODE_BUTTON_START));
        assertFalse(DiscordCommunityLegend.isMoreKey("start", KeyEvent.KEYCODE_MENU));
    }
}
