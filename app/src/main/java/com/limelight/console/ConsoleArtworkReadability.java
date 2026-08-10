package com.limelight.console;

/** Produces a restrained vignette strength from the perceived artwork brightness. */
final class ConsoleArtworkReadability {
    private ConsoleArtworkReadability() { }

    static int[] gradient(float luminance, boolean consoleLayout) {
        float safe = Math.max(0f, Math.min(1f, luminance));
        if (!consoleLayout) {
            int adjustment = Math.round((safe - .42f) * 34f);
            return new int[]{black(alpha(244, adjustment, 224, 252)),
                    black(alpha(212, adjustment, 190, 242)),
                    black(alpha(80, Math.round(adjustment * .65f), 58, 116))};
        }
        int adjustment = Math.round((safe - .42f) * 62f);
        return new int[]{
                black(alpha(216, adjustment, 188, 246)),
                black(alpha(114, Math.round(adjustment * .82f), 82, 166)),
                black(alpha(36, Math.round(adjustment * .58f), 24, 86)),
                black(alpha(96, Math.round(adjustment * .75f), 70, 146)),
                black(alpha(200, adjustment, 176, 240))};
    }

    static float perceivedLuminance(int red, int green, int blue) {
        return (0.2126f * red + 0.7152f * green + 0.0722f * blue) / 255f;
    }

    private static int alpha(int base, int adjustment, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, base + adjustment));
    }

    private static int black(int alpha) {
        return (alpha << 24) | 0x0005060A;
    }
}
