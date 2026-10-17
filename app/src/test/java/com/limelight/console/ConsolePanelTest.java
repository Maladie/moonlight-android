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

    @Test public void stepperHintsOnlyTheDirectionsThatCanStillStep() {
        assertEquals("0/10  ›", ConsolePanel.stepperText(0, 0, 10, "0/10"));
        assertEquals("‹  6/10  ›", ConsolePanel.stepperText(6, 0, 10, "6/10"));
        assertEquals("‹  10/10", ConsolePanel.stepperText(10, 0, 10, "10/10"));
        assertEquals(false, ConsolePanel.canStep(0, -1, 0, 10));
        assertEquals(true, ConsolePanel.canStep(0, 1, 0, 10));
        assertEquals(false, ConsolePanel.canStep(10, 1, 0, 10));
    }

    /** Bitrate: 0.5–150 Mb/s in 1 Mb/s steps on whole megabits, faster strides when held. */
    @Test public void stepsSnapToTheStepGridAndStayInRange() {
        assertEquals(1_000, ConsolePanel.stepValue(500, 1, 500, 150_000, 1_000));
        assertEquals(500, ConsolePanel.stepValue(1_000, -1, 500, 150_000, 1_000));
        assertEquals(21_000, ConsolePanel.stepValue(20_500, 1, 500, 150_000, 1_000));
        assertEquals(20_000, ConsolePanel.stepValue(20_500, -1, 500, 150_000, 1_000));
        assertEquals(30_000, ConsolePanel.stepValue(20_000, 1, 500, 150_000, 10_000));
        assertEquals(150_000, ConsolePanel.stepValue(149_000, 1, 500, 150_000, 10_000));
        assertEquals(8, ConsolePanel.stepValue(7, 1, 0, 20, 1));
        assertEquals(0, ConsolePanel.stepValue(3, -1, 0, 200, 5));
    }

    /** At the range end a fresh press navigates, held repeats stay; OK stops at the maximum. */
    @Test public void heldKeysStayAtTheRangeEndAndOkDoesNotWrap() {
        assertEquals(true, ConsolePanel.handlesStepKey(1_000, -1, 500, 150_000, 0));
        assertEquals(false, ConsolePanel.handlesStepKey(500, -1, 500, 150_000, 0));
        assertEquals(true, ConsolePanel.handlesStepKey(500, -1, 500, 150_000, 12));
        assertEquals(false, ConsolePanel.handlesStepKey(150_000, 1, 500, 150_000, 0));
        assertEquals(true, ConsolePanel.handlesStepKey(150_000, 1, 500, 150_000, 1));
        assertEquals(150_000, ConsolePanel.stepValue(150_000, 1, 500, 150_000, 1_000));
        assertEquals(10, ConsolePanel.stepValue(10, 1, 0, 10, 1));
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
