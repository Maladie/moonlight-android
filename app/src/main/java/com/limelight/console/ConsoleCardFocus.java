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

import java.util.HashMap;
import java.util.Map;

/**
 * Card surface around an image card (UX_MENU_PL.md section 4.2, type 1; mockup "focus_ring" and
 * "soft_shadow"): a soft drop shadow, and when focused a glow tinted from the card plus a thin
 * white ring outside it. Set as the background of a frame whose padding ({@link #MARGIN_DP})
 * surrounds the card; shadow and glow spill past the frame unclipped. Each is a blurred mask,
 * made once per card shape and blur at quarter resolution and tinted when drawn.
 */
final class ConsoleCardFocus extends Drawable {
    /** One blurred copy of the card: Gaussian sigma, downward offset and growth, all in dp. */
    static final class Layer {
        final float blurDp;
        final float dyDp;
        final float spreadDp;

        Layer(float blurDp, float dyDp, float spreadDp) {
            this.blurDp = blurDp;
            this.dyDp = dyDp;
            this.spreadDp = spreadDp;
        }
    }

    /** Ring room around the card. */
    static final int MARGIN_DP = 10;
    static final float GAP_DP = 2.5f;
    static final float RING_DP = 1.5f;
    static final int RING = 0xF5FFFFFF;
    /** Host tiles: one 30 px blur at 1080p for the shadow, the focus shadow and the glow. */
    static final float BLUR_DP = 15f;
    static final float SPREAD_DP = 2f;
    static final int MASK_SCALE = 4;
    static final float GLOW_ALPHA = 150f / 255f;
    static final float FOCUS_SHADOW_ALPHA = 150f / 255f;
    static final float SHADOW_ALPHA = 120f / 255f;

    /** One mask per card shape and blur; the entry screens use a handful. */
    private static final Map<String, Bitmap> MASKS = new HashMap<>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect dst = new Rect();
    private final float density;
    private final float inset;
    private final float corner;
    private Layer rest = new Layer(BLUR_DP, 6f, SPREAD_DP);
    private Layer lift = new Layer(BLUR_DP, 8f, SPREAD_DP);
    private Layer halo = new Layer(BLUR_DP, 2f, SPREAD_DP);
    private float gap = GAP_DP;
    private boolean focused;
    private int glow = 0xFFFFFFFF;
    private int shadowColor = 0xFF000000;
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

    /** The resting shadow, the shadow under a focused card and its glow. */
    void setLayers(Layer rest, Layer lift, Layer halo) {
        this.rest = rest;
        this.lift = lift;
        this.halo = halo;
    }

    /** Distance of the ring from the card and its width, in dp. */
    void setRing(float gapDp, float widthDp) {
        gap = gapDp;
        ring.setStrokeWidth(widthDp * density);
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

    /** Black unless the "shadow" is a light halo, as behind a focused menu row. */
    void setShadowColor(int color) {
        shadowColor = color;
    }

    /** The card rectangle grown by {@code outsetPx}, for drawables that share this geometry. */
    static void cardRect(RectF out, Rect bounds, float insetPx, float outsetPx) {
        out.set(bounds);
        out.inset(insetPx - outsetPx, insetPx - outsetPx);
    }

    @Override
    public void draw(Canvas canvas) {
        if (focused) drawMask(canvas, lift, shadowColor, FOCUS_SHADOW_ALPHA);
        else drawMask(canvas, rest, shadowColor, shadow);
        if (!focused) return;
        drawMask(canvas, halo, glow, GLOW_ALPHA);
        float outset = gap * density + ring.getStrokeWidth() / 2f;
        cardRect(rect, getBounds(), inset, outset);
        canvas.drawRoundRect(rect, corner + outset, corner + outset, ring);
    }

    private void drawMask(Canvas canvas, Layer layer, int color, float alpha) {
        cardRect(rect, getBounds(), inset, 0f);
        Bitmap shape = mask(Math.round(rect.width()), Math.round(rect.height()), layer);
        cardRect(rect, getBounds(), inset, pad(layer));
        rect.offset(0f, layer.dyDp * density);
        rect.round(dst);
        paint.setColorFilter(new PorterDuffColorFilter(color | 0xFF000000,
                PorterDuff.Mode.SRC_IN));
        paint.setAlpha(Math.round(255 * alpha));
        canvas.drawBitmap(shape, null, dst, paint);
    }

    private float pad(Layer layer) {
        return 3f * layer.blurDp * density;
    }

    /** White rounded card blurred as {@code layer} asks, alpha only, with room for the blur. */
    private Bitmap mask(int width, int height, Layer layer) {
        synchronized (ConsoleCardFocus.class) {
            String key = width + "x" + height + "r" + corner + "b" + layer.blurDp
                    + "s" + layer.spreadDp;
            Bitmap mask = MASKS.get(key);
            if (mask != null) return mask;
            float pad = pad(layer);
            int w = Math.round((width + 2 * pad) / MASK_SCALE);
            int h = Math.round((height + 2 * pad) / MASK_SCALE);
            Bitmap draw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(draw);
            canvas.drawColor(0xFF000000);
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFFFFFFFF);
            float spread = layer.spreadDp * density;
            float scale = 1f / MASK_SCALE;
            canvas.drawRoundRect(new RectF((pad - spread) * scale, (pad - spread) * scale,
                            (pad + width + spread) * scale, (pad + height + spread) * scale),
                    (corner + spread) * scale, (corner + spread) * scale, fill);
            int[] pixels = new int[w * h];
            draw.getPixels(pixels, 0, w, 0, 0, w, h);
            ConsoleMenuBackdrop.blur(pixels, w, h,
                    ConsoleMenuBackdrop.boxRadius(layer.blurDp * density * scale, 3), 3);
            for (int index = 0; index < pixels.length; index++) {
                pixels[index] = (pixels[index] & 0xFF) << 24 | 0xFFFFFF;
            }
            draw.setPixels(pixels, 0, w, 0, 0, w, h);
            MASKS.put(key, draw);
            return draw;
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
