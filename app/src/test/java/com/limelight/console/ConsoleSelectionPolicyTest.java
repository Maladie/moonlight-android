package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class ConsoleSelectionPolicyTest {
    @Test public void rememberedHostWins() {
        assertEquals(1, ConsoleSelectionPolicy.hostIndex(
                Arrays.asList("host-a", "host-b"), "host-b"));
    }

    @Test public void missingHostFallsBackToFirst() {
        assertEquals(0, ConsoleSelectionPolicy.hostIndex(
                Arrays.asList("host-a", "host-b"), "removed-host"));
    }

    @Test public void rememberedAppIsScopedToRenderedList() {
        assertEquals(2, ConsoleSelectionPolicy.appIndex(Arrays.asList(1, 2, 7), 7));
        assertEquals(0, ConsoleSelectionPolicy.appIndex(Arrays.asList(1, 2, 7), 99));
    }

    @Test public void emptyRowsHaveNoFocusTarget() {
        assertEquals(-1, ConsoleSelectionPolicy.hostIndex(Collections.emptyList(), null));
        assertEquals(-1, ConsoleSelectionPolicy.appIndex(Collections.emptyList(), -1));
    }

    @Test public void appStorageKeyIsHostScoped() {
        assertEquals("selected_app.host-a", ConsoleSelectionStore.appKey("host-a"));
    }
}
