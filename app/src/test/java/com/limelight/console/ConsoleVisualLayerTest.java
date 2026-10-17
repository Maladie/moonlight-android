package com.limelight.console;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class ConsoleVisualLayerTest {
    @Test
    public void boxRadiusApproximatesTheGaussian() {
        // 32 px at 1080p on the 1/8 copy is sigma 4; three boxes of 9 px give sigma ~4.5.
        assertEquals(4, ConsoleMenuBackdrop.boxRadius(4f, 3));
        assertEquals(8, ConsoleMenuBackdrop.boxRadius(8f, 3));
        assertEquals(1, ConsoleMenuBackdrop.boxRadius(.1f, 3));
    }

    @Test
    public void blurKeepsFlatColourAndSpreadsAPointSymmetrically() {
        int[] flat = new int[12 * 7];
        Arrays.fill(flat, 0xFF336699);
        ConsoleMenuBackdrop.blur(flat, 12, 7, 2, 3);
        for (int pixel : flat) assertEquals(0xFF336699, pixel);

        int width = 21;
        int[] point = new int[width * width];
        Arrays.fill(point, 0xFF000000);
        point[10 * width + 10] = 0xFFFFFFFF;
        ConsoleMenuBackdrop.blur(point, width, width, 2, 3);
        int centre = point[10 * width + 10] & 0xFF;
        assertTrue(centre > 0 && centre < 255);
        assertEquals(point[10 * width + 7], point[10 * width + 13]);
        assertEquals(point[7 * width + 10], point[13 * width + 10]);
        assertTrue((point[10 * width + 7] & 0xFF) < centre);
    }

    @Test
    public void glassMatrixLeavesGreysGreyAndAppliesTintAndVeil() {
        float[] matrix = ConsoleGlass.colorMatrix(1.3f, 0x10121A, .5f, .07f);
        float keep = .5f * .93f;
        for (int row = 0; row < 3; row++) {
            float sum = matrix[row * 5] + matrix[row * 5 + 1] + matrix[row * 5 + 2];
            assertEquals(keep, sum, 1e-5f);
        }
        assertEquals(0x10 * .5f * .93f + 255f * .07f, matrix[4], 1e-4f);
        assertEquals(0x12 * .5f * .93f + 255f * .07f, matrix[9], 1e-4f);
        assertEquals(0x1A * .5f * .93f + 255f * .07f, matrix[14], 1e-4f);
        assertArrayEquals(new float[]{0f, 0f, 0f, 1f, 0f},
                Arrays.copyOfRange(matrix, 15, 20), 0f);
        // Saturation pushes a channel away from the others.
        assertTrue(matrix[0] > keep * 1.2f);
    }

    @Test
    public void bakedGlassColourMatchesTheMatrix() {
        float[] matrix = ConsoleGlass.colorMatrix(1.3f, 0x10121A, .5f, .07f);
        int[] pixels = {0xFF000000, 0xFFFFFFFF, 0xFF808080};
        ConsoleGlass.applyColorMatrix(pixels, matrix);
        // Black becomes the tinted veil: 16*.465+17.85 = 25, 18*.465+17.85 = 26, 26*.465+17.85 = 30.
        assertEquals(0xFF191A1E, pixels[0]);
        // White and grey stay neutral apart from the tint.
        assertEquals(Math.round(255 * .465f + 0x10 * .465f + 17.85f), (pixels[1] >> 16) & 0xFF);
        assertEquals(Math.round(128 * .465f + 0x1A * .465f + 17.85f), pixels[2] & 0xFF);
        // A saturated red would overshoot and is clamped.
        int[] red = {0xFFFF0000};
        ConsoleGlass.applyColorMatrix(red, ConsoleGlass.colorMatrix(3f, 0, 0f, 0f));
        assertEquals(0xFFFF0000, red[0]);
    }

    @Test
    public void glassUnderTheBackdropTintSamplesTheTintedBackdrop() {
        float[] glass = ConsoleGlass.colorMatrix(1.3f, 0x10121A, .5f, 7f / 255f);
        float[] under = ConsoleGlass.under(glass, 0x4D06080C);
        float a = 0x4D / 255f;
        int[] direct = {0xFF000000 | Math.round(200 * (1 - a) + 6 * a) << 16
                | Math.round(8 * a) << 8 | Math.round(12 * a)};
        int[] composed = {0xFFC80000};
        ConsoleGlass.applyColorMatrix(direct, glass);
        ConsoleGlass.applyColorMatrix(composed, under);
        for (int shift = 0; shift <= 16; shift += 8) {
            assertEquals((direct[0] >> shift) & 0xFF, (composed[0] >> shift) & 0xFF, 1);
        }
    }

    @Test
    public void baselineShiftCentresTheCapHeight() {
        // Roboto at 30 px: ascent -27.8, descent 7.3, cap height 21.3 -> already centred.
        assertEquals(0, ConsoleText.baselineShift(-27.8f, 7.3f, 21.3f));
        // A tall ascent leaves the capitals low in the box, so the text moves up.
        assertEquals(-3, ConsoleText.baselineShift(-34f, 7f, 21f));
    }
}
