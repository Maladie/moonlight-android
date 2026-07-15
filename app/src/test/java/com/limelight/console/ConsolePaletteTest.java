package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConsolePaletteTest {
    @Test public void blendClampsAtBothEnds() {
        assertEquals(0xFF102030, ConsolePalette.blend(0xFF102030, 0xFFA0B0C0, -1f));
        assertEquals(0xFFA0B0C0, ConsolePalette.blend(0xFF102030, 0xFFA0B0C0, 2f));
    }

    @Test public void blendUsesRgbChannels() {
        assertEquals(0xFF808080, ConsolePalette.blend(0xFF000000, 0xFFFFFFFF, 0.5f));
    }

    @Test public void alphaReplacementPreservesRgb() {
        assertEquals(0x40123456, ConsolePalette.withAlpha(0xFF123456, 0x40));
    }
}
