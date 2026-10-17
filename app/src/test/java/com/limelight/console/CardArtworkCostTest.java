package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CardArtworkCostTest {
    @Test public void titleIsHiddenOnlyWhenTruncatedBelowReadableLength() {
        assertTrue(CardTitleFit.readable(4, 4));
        assertFalse(CardTitleFit.readable(8, 4));
        assertFalse(CardTitleFit.readable(20, CardTitleFit.MIN_READABLE_CHARS));
        assertTrue(CardTitleFit.readable(20, CardTitleFit.MIN_READABLE_CHARS + 1));
    }

    @Test public void blurKeepsUniformColourAndSpreadsAPointWithClampedEdges() {
        int[] uniform = {0xFF336699, 0xFF336699, 0xFF336699, 0xFF336699};
        CardBackdropBlur.blur(uniform, 2, 2, 3);
        assertArrayEquals(new int[]{0xFF336699, 0xFF336699, 0xFF336699, 0xFF336699}, uniform);

        int[] point = new int[9];
        java.util.Arrays.fill(point, 0xFF000000);
        point[4] = 0xFFF0F0F0;
        CardBackdropBlur.blur(point, 3, 3, 1);
        int centre = point[4] & 0xFF;
        int corner = point[0] & 0xFF;
        assertEquals(0xF0 / 9, centre, 1);
        assertEquals(centre, corner);
        assertEquals(0xFF, point[0] >>> 24);
    }
}
