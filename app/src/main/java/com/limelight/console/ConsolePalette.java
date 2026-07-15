package com.limelight.console;

/** Pure color math and shared Wake-derived console palette. */
final class ConsolePalette {
    static final int ACCENT = 0xFF7C4DFF;
    static final int FOCUS_STROKE = 0xFFECE7FF;
    static final int REST_STROKE = 0x5C9AA6C4;

    private ConsolePalette() { }

    static int blend(int from, int to, float amount) {
        float value = Math.max(0f, Math.min(1f, amount));
        int red = Math.round(red(from) + (red(to) - red(from)) * value);
        int green = Math.round(green(from) + (green(to) - green(from)) * value);
        int blue = Math.round(blue(from) + (blue(to) - blue(from)) * value);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    static int withAlpha(int color, int alpha) {
        return (alpha & 0xff) << 24 | color & 0x00ffffff;
    }

    private static int red(int color) { return color >> 16 & 0xff; }
    private static int green(int color) { return color >> 8 & 0xff; }
    private static int blue(int color) { return color & 0xff; }
}
