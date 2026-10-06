package com.limelight.console;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.limelight.R;

/**
 * Card surface around an image card (UX_MENU_PL.md section 4.2, type 1; mockup "focus_ring" and
 * "soft_shadow"): a soft drop shadow, and when focused a glow tinted from the card plus a 3 px
 * white ring 5 px outside it. Set as the background of a frame whose padding
 * ({@link #MARGIN_DP}) surrounds the card; shadow and glow spill past the frame unclipped. Both
 * are one blurred mask, made once per card size at quarter resolution and tinted when drawn.
 */
final class ConsoleCardFocus extends Drawable {
    /** Ring room around the card. */
    static final int MARGIN_DP = 10;
    static final float GAP_DP = 2.5f;
    static final float RING_DP = 1.5f;
    static final int RING = 0xF5FFFFFF;
    /** Mockup blur 30 px at 1080p, done on a quarter-size mask. */
    static final float BLUR_DP = 15f;
    static final float SPREAD_DP = 2f;
    static final int MASK_SCALE = 4;
    static final float GLOW_ALPHA = 150f / 255f;
    static final float FOCUS_SHADOW_ALPHA = 150f / 255f;
    static final float SHADOW_ALPHA = 120f / 255f;

    private static Bitmap mask;
    private static int maskKey;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect dst = new Rect();
    private final float density;
    private final float inset;
    private final float corner;
    private boolean focused;
    private int glow = 0xFFFFFFFF;
    private float shadow = SHADOW_ALPHA;

    /** {@code insetPx}: the frame padding; {@code cornerPx}: the card's corner radius. */
    ConsoleCardFocus(Resources resources, float insetPx, float cornerPx) {
        density = resources.getDisplayMetrics().density;
        inset = insetPx;
        corner = cornerPx;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(RING_DP * density);
        ring.setColor(RING);
    }

    void setFocused(boolean focused) {
        if (this.focused == focused) return;
        this.focused = focused;
        invalidateSelf();
    }

    void setGlowColor(int color) {
        if (glow == color) return;
        glow = color;
        if (focused) invalidateSelf();
    }

    /** Resting shadow strength, e.g. weaker under a dimmed card. */
    void setShadowAlpha(float alpha) {
        if (shadow == alpha) return;
        shadow = alpha;
        invalidateSelf();
    }

    /** The card rectangle grown by {@code outsetPx}, for drawables that share this geometry. */
    static void cardRect(RectF out, Rect bounds, float insetPx, float outsetPx) {
        out.set(bounds);
        out.inset(insetPx - outsetPx, insetPx - outsetPx);
    }

    @Override
    public void draw(Canvas canvas) {
        cardRect(rect, getBounds(), inset, 0f);
        Bitmap shape = mask(Math.round(rect.width()), Math.round(rect.height()));
        float pad = pad(density);
        drawMask(canvas, shape, pad, focused ? 8f : 6f, 0xFF000000,
                focused ? FOCUS_SHADOW_ALPHA : shadow);
        if (!focused) return;
        drawMask(canvas, shape, pad, 2f, glow, GLOW_ALPHA);
        float outset = (GAP_DP + RING_DP / 2f) * density;
        cardRect(rect, getBounds(), inset, outset);
        canvas.drawRoundRect(rect, corner + outset, corner + outset, ring);
    }

    private void drawMask(Canvas canvas, Bitmap shape, float pad, float dyDp, int color,
                          float alpha) {
        cardRect(rect, getBounds(), inset, pad);
        rect.offset(0f, dyDp * density);
        rect.round(dst);
        paint.setColorFilter(new PorterDuffColorFilter(color | 0xFF000000,
                PorterDuff.Mode.SRC_IN));
        paint.setAlpha(Math.round(255 * alpha));
        canvas.drawBitmap(shape, null, dst, paint);
    }

    private static float pad(float density) {
        return 3f * BLUR_DP * density;
    }

    /** White rounded card blurred by {@link #BLUR_DP}, alpha only, with room for the blur. */
    private Bitmap mask(int width, int height) {
        synchronized (ConsoleCardFocus.class) {
            int key = width * 31 + height;
            if (mask != null && maskKey == key) return mask;
            float pad = pad(density);
            int w = Math.round((width + 2 * pad) / MASK_SCALE);
            int h = Math.round((height + 2 * pad) / MASK_SCALE);
            Bitmap draw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(draw);
            canvas.drawColor(0xFF000000);
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFFFFFFFF);
            float spread = SPREAD_DP * density;
            float scale = 1f / MASK_SCALE;
            canvas.drawRoundRect(new RectF((pad - spread) * scale, (pad - spread) * scale,
                            (pad + width + spread) * scale, (pad + height + spread) * scale),
                    (corner + spread) * scale, (corner + spread) * scale, fill);
            int[] pixels = new int[w * h];
            draw.getPixels(pixels, 0, w, 0, 0, w, h);
            ConsoleMenuBackdrop.blur(pixels, w, h,
                    ConsoleMenuBackdrop.boxRadius(BLUR_DP * density * scale, 3), 3);
            for (int index = 0; index < pixels.length; index++) {
                pixels[index] = (pixels[index] & 0xFF) << 24 | 0xFFFFFF;
            }
            draw.setPixels(pixels, 0, w, 0, 0, w, h);
            mask = draw;
            maskKey = key;
            return mask;
        }
    }

    /** Scales a card about its centre; instant with reduced motion or before layout. */
    static void scale(View view, float scale, boolean reducedMotion) {
        view.animate().cancel();
        if (reducedMotion || !view.isLaidOut()) {
            view.setScaleX(scale);
            view.setScaleY(scale);
        } else {
            view.animate().scaleX(scale).scaleY(scale).setDuration(view.getResources()
                    .getInteger(R.integer.console_motion_focus_ms)).start();
        }
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter colorFilter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
