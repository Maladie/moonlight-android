package com.limelight.console;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * A tile's rounded amber work bar: a fill for a known percent; without one a sliding
 * segment, or with reduced motion a still hatching. Nothing here redraws per frame: the
 * fill is a scaled child, the sweep a translated one, so scrolling a carousel of these
 * costs only property updates.
 */
final class ConsoleTileProgressBar extends FrameLayout {
    private static final int TRACK = 0xBF404950;
    private static final int FILL = ConsoleTileBadge.AMBER;
    private static final int HATCH_LIGHT = 0x59E4B34B;
    private static final long SWEEP_MS = 1_400L;
    private static final float SEGMENT = .35f;

    private final View fill = new View(getContext());
    private final Hatching hatching = new Hatching(
            3 * getResources().getDisplayMetrics().density);
    private final android.graphics.drawable.ColorDrawable solid =
            new android.graphics.drawable.ColorDrawable(FILL);
    private ValueAnimator sweep;
    private int progress = -1;
    private boolean reducedMotion;

    ConsoleTileProgressBar(Context context) {
        super(context);
        setBackgroundColor(TRACK);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        view.getHeight() / 2f);
            }
        });
        fill.setBackground(solid);
        addView(fill, new LayoutParams(0, LayoutParams.MATCH_PARENT));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 0-100, or -1 for work without a known progress. */
    void setProgress(int value, boolean reducedMotion) {
        if (value == progress && reducedMotion == this.reducedMotion) return;
        progress = value;
        this.reducedMotion = reducedMotion;
        fill.setBackground(progress < 0 && reducedMotion ? hatching : solid);
        layoutFill();
        updateSweep();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        invalidateOutline();
        layoutFill();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        layoutFill();
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        updateSweep();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateSweep();
    }

    @Override protected void onDetachedFromWindow() {
        stopSweep();
        super.onDetachedFromWindow();
    }

    /** Fill width by percent, a sweeping segment's width, or the whole bar for hatching. */
    private void layoutFill() {
        int width = getWidth();
        if (width == 0) return;
        float target = progress >= 0 ? width * Math.min(100, progress) / 100f
                : reducedMotion ? width : width * SEGMENT;
        LayoutParams params = (LayoutParams) fill.getLayoutParams();
        if (params.width != Math.round(target)) {
            params.width = Math.round(target);
            fill.setLayoutParams(params);
        }
        if (progress >= 0 || reducedMotion) fill.setTranslationX(0f);
    }

    private void updateSweep() {
        boolean run = progress < 0 && !reducedMotion && isAttachedToWindow()
                && getVisibility() == VISIBLE && ValueAnimator.areAnimatorsEnabled();
        if (!run) {
            stopSweep();
            return;
        }
        if (sweep != null) return;
        sweep = ValueAnimator.ofFloat(0f, 1f);
        sweep.setDuration(SWEEP_MS);
        sweep.setInterpolator(new LinearInterpolator());
        sweep.setRepeatCount(ValueAnimator.INFINITE);
        sweep.addUpdateListener(animation -> {
            float phase = (float) animation.getAnimatedValue();
            fill.setTranslationX((phase * (1f + SEGMENT) - SEGMENT) * getWidth());
        });
        sweep.start();
    }

    private void stopSweep() {
        if (sweep == null) return;
        sweep.cancel();
        sweep = null;
    }

    /** -45 degree stripes: 3 dp amber, 3 dp faint amber; drawn once, never animated. */
    private static final class Hatching extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint();
        private final float stripe;

        Hatching(float stripe) {
            this.stripe = stripe;
            paint.setShader(new LinearGradient(0, 0, stripe * 2 * .7071f, stripe * 2 * .7071f,
                    new int[]{FILL, FILL, HATCH_LIGHT, HATCH_LIGHT},
                    new float[]{0f, .5f, .5f, 1f}, Shader.TileMode.REPEAT));
        }

        @Override public void draw(Canvas canvas) {
            canvas.drawRect(getBounds(), paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
