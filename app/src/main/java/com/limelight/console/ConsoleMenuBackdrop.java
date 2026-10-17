package com.limelight.console;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import com.limelight.LimeLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Backdrop of a large menu (UX_MENU_PL.md section 4.3): when the menu opens, one downscaled
 * copy of the window behind is taken, blurred once on a worker and drawn full screen under a
 * moderate tint; {@link ConsoleGlass} panels crop the same bitmap. Nothing is blurred per frame.
 * Over a retained stream, or when the copy is unavailable, the backdrop only darkens.
 */
final class ConsoleMenuBackdrop extends View {
    /** 240x135 at 1080p: the blur is wide enough that bilinear upscaling shows no blocks. */
    static final int SNAPSHOT_DIVISOR = 8;
    static final long FADE_MS = 150L;
    private static final float[] GLASS_MATRIX = ConsoleGlass.under(ConsoleGlass.colorMatrix(
            ConsoleGlass.SATURATION, ConsoleGlass.TINT, ConsoleGlass.TINT_ALPHA, ConsoleGlass.VEIL),
            ConsoleMenuBackdrop.TINT);
    /** One worker for every backdrop: a menu opening never copies or blurs on the main thread. */
    private static Handler worker;
    /** Backdrop blur, 32 px at 1080p. */
    static final int BLUR_DP = 16;
    static final int BOX_PASSES = 3;
    /** rgb(6,8,12) at 30 % over the blurred copy. */
    static final int TINT = 0x4D06080C;
    /** Without a blurred copy the content stays sharp, so it is darkened much more. */
    static final int DARKEN_ONLY = 0xB306080C;

    private final boolean overStream;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final int[] position = new int[2];
    private final List<View> surfaces = new ArrayList<>();
    private Bitmap blurred;
    private Bitmap glassBitmap;
    private float originX;
    private float originY;
    private float scale = 1f;
    /** Read by the blur worker to skip stale work; written on the main thread only. */
    private volatile int generation;
    /** A copy is on its way (or arrived): the plain tint, not the stronger darkening. */
    private boolean copying;
    private float blurAlpha;
    private ValueAnimator fade;

    /** @param overStream the menu opens over a retained stream: darken only, never copy it. */
    ConsoleMenuBackdrop(Context context, boolean overStream) {
        super(context);
        this.overStream = overStream;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setAlpha(0f);
    }

    /** Glass surfaces redraw when the blurred copy arrives. */
    void addSurface(View surface) {
        surfaces.add(surface);
    }

    /**
     * Starts one menu opening: the tint shows at once, the copy of {@code source} is blurred on
     * a worker and cross-fades in when ready. A result for an older opening is dropped.
     */
    void capture(Window source, boolean reducedMotion) {
        release();
        int token = generation;
        View decor = source.getDecorView();
        int width = decor.getWidth() / SNAPSHOT_DIVISOR;
        int height = decor.getHeight() / SNAPSHOT_DIVISOR;
        // PixelCopy of a window needs API 26; older devices get the darkened backdrop.
        copying = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !overStream
                && width > 0 && height > 0;
        if (reducedMotion) setAlpha(1f);
        else animate().alpha(1f).setDuration(FADE_MS).start();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !copying) return;
        int[] origin = new int[2];
        decor.getLocationOnScreen(origin);
        Bitmap copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        float copyScale = width / (float) decor.getWidth();
        int radius = boxRadius(BLUR_DP * getResources().getDisplayMetrics().density * copyScale,
                BOX_PASSES);
        Handler worker = worker();
        worker.post(() -> {
            if (token != generation) return;
            long started = SystemClock.uptimeMillis();
            // PixelCopy.request blocks its caller until the copy is done, so it runs here.
            PixelCopy.request(source, copy, result -> {
                if (token != generation) return;
                if (result != PixelCopy.SUCCESS) {
                    handler.post(() -> {
                        if (token != generation) return;
                        copying = false;
                        invalidate();
                    });
                    return;
                }
                long copied = SystemClock.uptimeMillis();
                int[] pixels = new int[width * height];
                copy.getPixels(pixels, 0, width, 0, 0, width, height);
                blur(pixels, width, height, radius, BOX_PASSES);
                copy.setPixels(pixels, 0, width, 0, 0, width, height);
                // The glass colour is baked once here instead of filtered on every draw.
                ConsoleGlass.applyColorMatrix(pixels, GLASS_MATRIX);
                Bitmap glass = Bitmap.createBitmap(pixels, width, height,
                        Bitmap.Config.ARGB_8888);
                long blurMs = SystemClock.uptimeMillis() - copied;
                handler.post(() -> {
                    if (token != generation) return;
                    blurred = copy;
                    glassBitmap = glass;
                    originX = origin[0];
                    originY = origin[1];
                    scale = copyScale;
                    LimeLog.info("Menu backdrop " + width + "x" + height + ": copy "
                            + (copied - started) + " ms, blur " + blurMs + " ms (worker)");
                    fadeIn(reducedMotion);
                });
            }, worker);
        });
    }

    /** The blurred copy with the glass colour applied, for {@link ConsoleGlass}; may be null. */
    Bitmap glassBitmap() {
        return glassBitmap;
    }

    @Override
    public boolean hasOverlappingRendering() {
        // Fading the full-screen bitmap and tint needs no offscreen layer per frame.
        return false;
    }

    /** Runs small one-off preparation (e.g. resource parsing) on the backdrop worker. */
    static void inBackground(Runnable task) {
        worker().post(task);
    }

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread thread = new HandlerThread("console-backdrop");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    /** Drops the copy when the menu closes. */
    void release() {
        generation++;
        copying = false;
        blurred = null;
        glassBitmap = null;
        if (fade != null) fade.cancel();
        blurAlpha = 0f;
        animate().cancel();
        setAlpha(0f);
        invalidateAll();
    }

    /** 0 to 1 while the blurred copy cross-fades over the plain tint. */
    float blurAlpha() {
        return blurAlpha;
    }

    private void fadeIn(boolean reducedMotion) {
        if (reducedMotion) {
            blurAlpha = 1f;
            invalidateAll();
            return;
        }
        fade = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
        fade.addUpdateListener(animation -> {
            blurAlpha = (float) animation.getAnimatedValue();
            invalidateAll();
        });
        fade.start();
    }

    private void invalidateAll() {
        invalidate();
        for (View surface : surfaces) surface.invalidate();
    }

    /** Maps the blurred copy into {@code view}'s coordinates at its resting place; null if none. */
    Bitmap map(View view, Matrix out) {
        if (blurred == null) return null;
        restingOnScreen(view, position);
        out.setScale(1f / scale, 1f / scale);
        out.postTranslate(originX - position[0], originY - position[1]);
        return blurred;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap bitmap = map(this, matrix);
        if (bitmap != null) {
            paint.setAlpha(Math.round(255 * blurAlpha));
            canvas.drawBitmap(bitmap, matrix, paint);
        }
        canvas.drawColor(copying || bitmap != null ? TINT : DARKEN_ONLY);
    }

    /** Screen position of {@code view} from layout alone, ignoring running translations. */
    private static void restingOnScreen(View view, int[] out) {
        int x = 0;
        int y = 0;
        View current = view;
        while (current.getParent() instanceof View) {
            View parent = (View) current.getParent();
            x += current.getLeft() - parent.getScrollX();
            y += current.getTop() - parent.getScrollY();
            current = parent;
        }
        current.getLocationOnScreen(out);
        out[0] += x;
        out[1] += y;
    }

    /** Box radius whose {@code passes} repetitions approximate a Gaussian of {@code sigma}. */
    static int boxRadius(float sigma, int passes) {
        double width = Math.sqrt(12.0 * sigma * sigma / passes + 1.0);
        return Math.max(1, (int) Math.round((width - 1.0) / 2.0));
    }

    /** Opaque RGB blur: {@code passes} running-sum box passes per axis, clamped at the edges. */
    static void blur(int[] rgb, int width, int height, int radius, int passes) {
        int[] scratch = new int[rgb.length];
        for (int pass = 0; pass < passes; pass++) {
            boxTransposed(rgb, scratch, width, height, radius);
            boxTransposed(scratch, rgb, height, width, radius);
        }
    }

    /** Box-blurs every row of {@code from} (width x height) into {@code to} transposed. */
    private static void boxTransposed(int[] from, int[] to, int width, int height, int radius) {
        int window = 2 * radius + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = from[row + Math.max(0, Math.min(width - 1, i))];
                r += (color >> 16) & 0xFF;
                g += (color >> 8) & 0xFF;
                b += color & 0xFF;
            }
            for (int x = 0; x < width; x++) {
                to[x * height + y] = 0xFF000000 | (r / window) << 16 | (g / window) << 8
                        | b / window;
                int out = from[row + Math.max(0, x - radius)];
                int in = from[row + Math.min(width - 1, x + radius + 1)];
                r += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                g += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                b += (in & 0xFF) - (out & 0xFF);
            }
        }
    }
}
