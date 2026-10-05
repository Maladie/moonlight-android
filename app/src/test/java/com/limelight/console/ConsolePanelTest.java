package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class ConsolePanelTest {
    @Test public void steppedValuesMapToStoredPercentages() {
        assertEquals(0, ConsolePanel.stepFromPercent(0));
        assertEquals(6, ConsolePanel.stepFromPercent(60));
        assertEquals(10, ConsolePanel.stepFromPercent(100));
        assertEquals(0, ConsolePanel.percentFromStep(0));
        assertEquals(60, ConsolePanel.percentFromStep(6));
        assertEquals(100, ConsolePanel.percentFromStep(10));
        for (int step = 0; step <= ConsolePanel.STEPS; step++) {
            assertEquals(step, ConsolePanel.stepFromPercent(ConsolePanel.percentFromStep(step)));
        }
    }

    @Test public void legacyOffGridPercentagesRoundToTheNearestStepAndClamp() {
        assertEquals(5, ConsolePanel.stepFromPercent(46));
        assertEquals(4, ConsolePanel.stepFromPercent(44));
        assertEquals(0, ConsolePanel.stepFromPercent(-20));
        assertEquals(10, ConsolePanel.stepFromPercent(140));
        assertEquals(100, ConsolePanel.percentFromStep(12));
    }

    @Test public void destructiveRowsMoveLastKeepingRelativeOrder() {
        assertEquals(Arrays.asList("wake", "desktop", "test", "!close", "!unpair"),
                ConsolePanel.destructiveLast(
                        Arrays.asList("wake", "!close", "desktop", "!unpair", "test"),
                        row -> row.startsWith("!")));
        assertEquals(Arrays.asList("a", "b"),
                ConsolePanel.destructiveLast(Arrays.asList("a", "b"), row -> false));
    }
}
