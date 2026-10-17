package com.limelight.console;

import com.limelight.SourceText;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleActivityFocusContractTest {
    @Test public void carouselDiffOnlyRestoresFocusOwnedByCarousel() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        String text = SourceText.read(source);
        String method = text.substring(text.indexOf("private void applyPlayniteDiff"),
                text.indexOf("private void moveCarouselCard("));

        assertTrue(method.contains("isDescendant(appRow, currentFocus)"));
        assertTrue(method.contains("Object focusTag = carouselFocused"));
        // A focused card moves with the order too: recency is never held back by focus.
        assertFalse(method.contains("focusedId == null"));
        assertTrue(method.contains("moveCarouselCard(card, currentIndex, index);"));
    }

    @Test public void downFromTheTopBarPicksTheNearestVisibleCard() {
        int[] lefts = {-200, 0, 100, 200, 300, 900};
        int[] rights = {-100, 90, 190, 290, 390, 990};
        // Viewport 0..800: the off-screen cards at either end never count.
        assertEquals(3, ConsoleActivity.nearestVisibleCard(lefts, rights, 0, 800, 260));
        assertEquals(1, ConsoleActivity.nearestVisibleCard(lefts, rights, 0, 800, -500));
        assertEquals(4, ConsoleActivity.nearestVisibleCard(lefts, rights, 0, 800, 2000));
        // Equal distance: the left card.
        assertEquals(2, ConsoleActivity.nearestVisibleCard(lefts, rights, 0, 800, 195));
        assertEquals(-1, ConsoleActivity.nearestVisibleCard(new int[0], new int[0], 0, 800, 10));
    }
}
