package com.limelight.console;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;

import org.junit.Test;

public class ExpandedLibraryGridRowsTest {
    private static final int LEAVE = ExpandedLibraryGrid.Rows.LEAVE;

    private static int step(int index, int keyCode) {
        // 309 games in 6 columns: the last row holds indices 306..308.
        return ExpandedLibraryGrid.Rows.navigationTarget(index, 6, 309, keyCode);
    }

    @Test public void dpadStepsWithinTheGridAndLeavesUpOrLeftToTheFilterBar() {
        assertEquals(LEAVE, step(3, KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(LEAVE, step(12, KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(7, step(13, KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(12, step(13, KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(14, step(13, KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(19, step(13, KeyEvent.KEYCODE_DPAD_DOWN));
    }

    @Test public void dpadStopsAtTheRightEdgeAndTheLastRow() {
        assertEquals(11, step(11, KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(308, step(308, KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(306, step(306, KeyEvent.KEYCODE_DPAD_DOWN));
        // Below a column the short last row lacks, down lands on the last game.
        assertEquals(308, step(305, KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(306, step(300, KeyEvent.KEYCODE_DPAD_DOWN));
    }

    @Test public void prefetchCoversVisibleRowsPlusTwoRowsInTheScrollDirection() {
        // Rows 5..7 visible (items 30..47): never more than visible + 2 rows.
        assertArrayEquals(new int[]{30, 60},
                ExpandedLibraryGrid.Rows.prefetchRange(30, 47, 6, 309, 1));
        assertArrayEquals(new int[]{18, 48},
                ExpandedLibraryGrid.Rows.prefetchRange(30, 47, 6, 309, -1));
    }

    @Test public void prefetchIsClampedToTheLibrary() {
        assertArrayEquals(new int[]{0, 18},
                ExpandedLibraryGrid.Rows.prefetchRange(0, 17, 6, 309, -1));
        assertArrayEquals(new int[]{288, 309},
                ExpandedLibraryGrid.Rows.prefetchRange(288, 308, 6, 309, 1));
        assertArrayEquals(new int[]{0, 0},
                ExpandedLibraryGrid.Rows.prefetchRange(-1, -1, 6, 309, 1));
        assertArrayEquals(new int[]{0, 0},
                ExpandedLibraryGrid.Rows.prefetchRange(0, 0, 6, 0, 1));
    }
}
