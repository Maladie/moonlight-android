package com.limelight.console;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Host tile artwork (UX_MENU_PL.md 3.7, mockup recipe "blob_avatar" + veil): a muted base in the
 * host's hue with six soft Gaussian blobs of neighbouring hues, one thin light wave, blurred and
 * veiled dark. Rendered once per host at half size and cached; the card upscales it.
 */
final class HostTileArt {
    static final int SIZE = 120;
    /** The mockup darkens the art with rgb(8,10,18) at 70/255. */
    static final int VEIL = 0x080A12;
    static final float VEIL_ALPHA = 70f / 255f;

    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    private HostTileArt() { }

    static float hue(String id) {
        return Math.abs((id == null ? 0 : id.hashCode()) % 360);
    }

    /** The host's light colour for its focus glow. */
    static int accent(String id) {
        return Color.HSVToColor(new float[]{hue(id), .6f, 1f});
    }

    static Bitmap bitmap(String id) {
        return cached(id, true);
    }

    /** The same recipe without the dark veil: the mockup's profile avatar ("blob_avatar"). */
    static Bitmap avatar(String id) {
        return cached(id, false);
    }

    private static synchronized Bitmap cached(String id, boolean veiled) {
        String key = veiled + ":" + id;
        Bitmap cached = CACHE.get(key);
        if (cached == null) {
            cached = render(id, veiled);
            CACHE.put(key, cached);
        }
        return cached;
    }

    private static Bitmap render(String id, boolean veiled) {
        int n = SIZE;
        float hue = hue(id);
        Random random = new Random(id == null ? 0 : id.hashCode());
        float[] r = new float[n * n];
        float[] g = new float[n * n];
        float[] b = new float[n * n];
        int base = Color.HSVToColor(new float[]{hue, .58f, .55f});
        java.util.Arrays.fill(r, Color.red(base));
        java.util.Arrays.fill(g, Color.green(base));
        java.util.Arrays.fill(b, Color.blue(base));
        for (int i = 0; i < 6; i++) {
            float cx = .12f + .17f * (i % 4);
            float cy = .12f + .21f * random.nextInt(4);
            float rx = .38f + .06f * (i % 3);
            float ry = .2f + .05f * ((i + 1) % 3);
            double angle = random.nextDouble() - .5;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            int blob = Color.HSVToColor(new float[]{(hue + 48f + i * 43f) % 360f, .62f, .94f});
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    float dx = x / (float) n - cx;
                    float dy = y / (float) n - cy;
                    float u = (dx * cos + dy * sin) / rx;
                    float v = (-dx * sin + dy * cos) / ry;
                    float k = (float) Math.exp(-(u * u + v * v) * 1.6) * .62f;
                    int p = y * n + x;
                    r[p] += (Color.red(blob) - r[p]) * k;
                    g[p] += (Color.green(blob) - g[p]) * k;
                    b[p] += (Color.blue(blob) - b[p]) * k;
                }
            }
        }
        int[] pixels = new int[n * n];
        for (int p = 0; p < pixels.length; p++) {
            pixels[p] = Color.rgb(Math.round(r[p]), Math.round(g[p]), Math.round(b[p]));
        }
        Bitmap bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, n, 0, 0, n, n);

        Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeWidth(n / 40f);
        wave.setStrokeCap(Paint.Cap.ROUND);
        wave.setColor(Color.argb(70, 255, 255, 255));
        Path path = new Path();
        for (int step = 0; step <= 60; step++) {
            float t = step / 60f;
            float x = n * (-.1f + 1.2f * t);
            float y = n * (.62f - .25f * (float) Math.sin(t * 3.4f) - .15f * t);
            if (step == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        new Canvas(bitmap).drawPath(path, wave);

        bitmap.getPixels(pixels, 0, n, 0, 0, n, n);
        ConsoleMenuBackdrop.blur(pixels, n, n, 1, 2);
        for (int p = 0; veiled && p < pixels.length; p++) {
            int c = pixels[p];
            pixels[p] = Color.rgb(veil(Color.red(c), Color.red(VEIL)),
                    veil(Color.green(c), Color.green(VEIL)), veil(Color.blue(c), Color.blue(VEIL)));
        }
        bitmap.setPixels(pixels, 0, n, 0, 0, n, n);
        return bitmap;
    }

    private static int veil(int channel, int veil) {
        return Math.round(channel + (veil - channel) * VEIL_ALPHA);
    }
}
