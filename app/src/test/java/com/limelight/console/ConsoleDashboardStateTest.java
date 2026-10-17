package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ConsoleDashboardStateTest {
    @Test public void exposesDistinctHostStates() {
        assertEquals(ConsoleDashboardState.HostState.DISCOVERING,
                ConsoleDashboardState.hostState(false, false, false, 0));
        assertEquals(ConsoleDashboardState.HostState.OFFLINE,
                ConsoleDashboardState.hostState(false, true, true, 0));
        assertEquals(ConsoleDashboardState.HostState.UNPAIRED,
                ConsoleDashboardState.hostState(true, true, false, 0));
        assertEquals(ConsoleDashboardState.HostState.ONLINE,
                ConsoleDashboardState.hostState(true, true, true, 0));
        assertEquals(ConsoleDashboardState.HostState.ACTIVE_SESSION,
                ConsoleDashboardState.hostState(true, true, true, 42));
    }

    @Test public void preservesSelectionWhenDynamicListStillContainsIt() {
        assertEquals("app:2", ConsoleDashboardState.restoreSelection("app:2",
                Arrays.asList("app:1", "app:2", "app:3")));
    }

    @Test public void fallsBackWithoutInventingSelection() {
        assertEquals("app:1", ConsoleDashboardState.restoreSelection("app:9",
                Arrays.asList("app:1", "app:2")));
        assertNull(ConsoleDashboardState.restoreSelection("app:9", Collections.emptyList()));
    }
}
