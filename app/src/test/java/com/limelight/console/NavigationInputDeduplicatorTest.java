package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NavigationInputDeduplicatorTest {
    @Test public void suppressesNearSimultaneousDuplicateDirection() {
        NavigationInputDeduplicator filter = new NavigationInputDeduplicator(70L);

        assertFalse(filter.shouldSuppress(22, 1_000L));
        assertTrue(filter.shouldSuppress(22, 1_025L));
    }

    @Test public void preservesIntentionalRepeatOutsideWindow() {
        NavigationInputDeduplicator filter = new NavigationInputDeduplicator(70L);

        assertFalse(filter.shouldSuppress(22, 1_000L));
        assertFalse(filter.shouldSuppress(22, 1_100L));
    }

    @Test public void preservesImmediateChangeOfDirection() {
        NavigationInputDeduplicator filter = new NavigationInputDeduplicator(70L);

        assertFalse(filter.shouldSuppress(22, 1_000L));
        assertFalse(filter.shouldSuppress(21, 1_010L));
    }
}
