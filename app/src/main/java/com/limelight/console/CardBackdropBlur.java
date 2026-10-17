package com.limelight.console;

import android.graphics.Bitmap;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Blurred copies of decoded card posters, made once on the decode thread so cards draw a
 * plain bitmap instead of running a per-frame blur effect. Entries live as long as their
 * source bitmap.
 */
final class CardBackdropBlur {
    /** Shorter side of the blurred copy; with bilinear upscaling this approximates a 10dp blur. */
    private static final int SHORT_SIDE_PX = 24;
    private static final int PASSES = 3;
    private static final Map<Bitmap, Bitmap> blurred =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CardBackdropBlur() {
    }

    /** Decode thread only. */
    static void prepare(Bitmap source) {
        float scale = SHORT_SIDE_PX / (float) Math.min(source.getWidth(), source.getHeight());
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap small = Bitmap.createScaledBitmap(source, width, height, true);
        int[] pixels = new int[width * height];
        small.getPixels(pixels, 0, width, 0, 0, width, height);
        if (small != source) small.recycle();
        blur(pixels, width, height, PASSES);
        blurred.put(source, Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888));
    }

    static Bitmap get(Bitmap source) {
        return blurred.get(source);
    }

    /** Separable 3-tap box blur repeated {@code passes} times, clamping at the edges. */
    static void blur(int[] argb, int width, int height, int passes) {
        int[] scratch = new int[argb.length];
        for (int pass = 0; pass < passes; pass++) {
            boxPass(argb, scratch, width, height, true);
            boxPass(scratch, argb, width, height, false);
        }
    }

    private static void boxPass(int[] from, int[] to, int width, int height,
                                boolean horizontal) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = 0, r = 0, g = 0, b = 0;
                for (int d = -1; d <= 1; d++) {
                    int sx = horizontal ? Math.max(0, Math.min(width - 1, x + d)) : x;
                    int sy = horizontal ? y : Math.max(0, Math.min(height - 1, y + d));
                    int color = from[sy * width + sx];
                    a += color >>> 24;
                    r += (color >> 16) & 0xFF;
                    g += (color >> 8) & 0xFF;
                    b += color & 0xFF;
                }
                to[y * width + x] = (a + 1) / 3 << 24 | (r + 1) / 3 << 16
                        | (g + 1) / 3 << 8 | (b + 1) / 3;
            }
        }
    }
}
