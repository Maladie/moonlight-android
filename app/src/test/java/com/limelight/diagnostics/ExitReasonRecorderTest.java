package com.limelight.diagnostics;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExitReasonRecorderTest {
    @Test public void recordsOnlyNewExitsOldestFirstWithAllowlistedFields() {
        List<Object[]> recorded = new ArrayList<>();
        long newest = ExitReasonRecorder.recordNew(Arrays.asList(
                new ExitReasonRecorder.Exit(300L, 30, 6, 0, 100),
                new ExitReasonRecorder.Exit(100L, 10, 3, 0, 400),
                new ExitReasonRecorder.Exit(200L, 20, 10, 0, 125)),
                100L, recorded::add);

        assertEquals(300L, newest);
        assertEquals(2, recorded.size());
        assertEquals(Arrays.asList("reason", "USER_REQUESTED", "status", 0, "importance", 125,
                "timestamp", 200L, "pid", 20), Arrays.asList(recorded.get(0)));
        assertEquals("ANR", recorded.get(1)[1]);
        for (Object[] fields : recorded) {
            assertEquals("only allowlisted fields", 10, fields.length);
        }
    }

    @Test public void nothingNewKeepsTheWatermark() {
        List<Object[]> recorded = new ArrayList<>();
        assertEquals(500L, ExitReasonRecorder.recordNew(Arrays.asList(
                new ExitReasonRecorder.Exit(400L, 1, 4, 0, 100)), 500L, recorded::add));
        assertTrue(recorded.isEmpty());
    }

    @Test public void distinguishesProcessDeathReasons() {
        assertEquals("LOW_MEMORY", ExitReasonRecorder.reasonName(3));
        assertEquals("CRASH", ExitReasonRecorder.reasonName(4));
        assertEquals("ANR", ExitReasonRecorder.reasonName(6));
        assertEquals("USER_STOPPED", ExitReasonRecorder.reasonName(11));
        assertEquals("UNKNOWN", ExitReasonRecorder.reasonName(0));
    }
}
