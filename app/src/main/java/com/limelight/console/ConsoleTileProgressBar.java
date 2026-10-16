package com.limelight.console;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * A tile's rounded amber work bar: a fill for a known percent; without one a sliding
 * segment, or with reduced motion a still hatching.
 */
final class ConsoleTileProgressBar extends View {
    private static final int TRACK = 0xBF404950;
    private static final int FILL = ConsoleTileBadge.AMBER;
    private static final int HATCH_LIGHT = 0x59E4B34B;
    private static final long SWEEP_MS = 1_400L;
    private static final float SEGMENT = .35f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path clip = new Path();
    private final float hatchPx;
    private Shader hatch;
    private int progress = -1;
    private boolean reducedMotion;

    ConsoleTileProgressBar(Context context) {
        super(context);
        hatchPx = 3 * context.getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 0-100, or -1 for work without a known progress. */
    void setProgress(int value, boolean reducedMotion) {
        if (value == progress && reducedMotion == this.reducedMotion) return;
        progress = value;
        this.reducedMotion = reducedMotion;
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        bounds.set(0, 0, width, height);
        clip.reset();
        clip.addRoundRect(bounds, height / 2f, height / 2f, Path.Direction.CW);
        // -45 degree stripes: 3 dp amber, 3 dp faint amber.
        hatch = new LinearGradient(0, 0, hatchPx * 2 * .7071f, hatchPx * 2 * .7071f,
                new int[]{FILL, FILL, HATCH_LIGHT, HATCH_LIGHT},
                new float[]{0f, .5f, .5f, 1f}, Shader.TileMode.REPEAT);
    }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth();
        if (width == 0) return;
        canvas.save();
        canvas.clipPath(clip);
        paint.setShader(null);
        paint.setColor(TRACK);
        canvas.drawRect(bounds, paint);
        paint.setColor(FILL);
        if (progress >= 0) {
            canvas.drawRect(0, 0, width * progress / 100f, getHeight(), paint);
        } else if (reducedMotion) {
            paint.setShader(hatch);
            canvas.drawRect(bounds, paint);
        } else {
            float phase = (SystemClock.uptimeMillis() % SWEEP_MS) / (float) SWEEP_MS;
            float left = (phase * (1f + SEGMENT) - SEGMENT) * width;
            canvas.drawRect(left, 0, left + SEGMENT * width, getHeight(), paint);
            // Draws stop on their own once the bar is hidden or detached.
            postInvalidateOnAnimation();
        }
        canvas.restore();
    }
}
