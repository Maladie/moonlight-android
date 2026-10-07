package com.limelight.console;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Glass panel surface (UX_MENU_PL.md section 4.3): the menu's blurred backdrop copy, cropped to
 * the panel, saturated x1.3, tinted rgb(16,18,26) at 50 % and veiled 7/255 white (baked into the
 * copy once by {@link ConsoleMenuBackdrop}'s worker), with a 1.5 px
 * hairline that is brighter on top and 36 px corners. It never blurs on its own; without a copy
 * it is a near-opaque tint.
 */
final class ConsoleGlass extends Drawable {
    static final int RADIUS_DP = 18;
    static final float SATURATION = 1.3f;
    static final int TINT = 0x10121A;
    static final float TINT_ALPHA = .5f;
    /** The mockups' veil=7 is on the 0-255 scale (make_mockups.glass), i.e. 2.7 %. */
    static final float VEIL = 7f / 255f;
    static final int SOLID = 0xF210121A;
    static final float HAIRLINE_DP = .75f;
    static final int HAIRLINE_TOP = 0x2EFFFFFF;
    static final int HAIRLINE_BOTTOM = 0x10FFFFFF;

    private final View host;
    private final ConsoleMenuBackdrop backdrop;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final float radius;
    private Bitmap shaderBitmap;
    private BitmapShader shader;

    ConsoleGlass(View host, ConsoleMenuBackdrop backdrop) {
        this.host = host;
        this.backdrop = backdrop;
        float density = host.getResources().getDisplayMetrics().density;
        radius = RADIUS_DP * density;
        hairline.setStyle(Paint.Style.STROKE);
        hairline.setStrokeWidth(HAIRLINE_DP * density);
        backdrop.addSurface(host);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        hairline.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                HAIRLINE_TOP, HAIRLINE_BOTTOM, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        rect.set(getBounds());
        Bitmap bitmap = backdrop.map(host, matrix) == null ? null : backdrop.glassBitmap();
        float blurAlpha = bitmap == null ? 0f : backdrop.blurAlpha();
        if (blurAlpha < 1f) {
            fill.setShader(null);
            fill.setColor(SOLID);
            canvas.drawRoundRect(rect, radius, radius, fill);
        }
        if (bitmap == null) {
            shaderBitmap = null;
            shader = null;
        } else {
            if (bitmap != shaderBitmap) {
                shaderBitmap = bitmap;
                shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            shader.setLocalMatrix(matrix);
            fill.setShader(shader);
            fill.setColor(Color.BLACK);
            fill.setAlpha(Math.round(255 * blurAlpha));
            canvas.drawRoundRect(rect, radius, radius, fill);
        }
        float inset = hairline.getStrokeWidth() / 2f;
        rect.inset(inset, inset);
        canvas.drawRoundRect(rect, radius - inset, radius - inset, hairline);
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), radius);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /**
     * One 4x5 color matrix for saturation (Rec. 709 weights, as ColorMatrix.setSaturation), then
     * a {@code tintAlpha} mix towards {@code tint} (0xRRGGBB), then a {@code veil} mix to white.
     */
    static float[] colorMatrix(float saturation, int tint, float tintAlpha, float veil) {
        float[] luma = {.213f, .715f, .072f};
        float keep = (1f - tintAlpha) * (1f - veil);
        float[] matrix = new float[20];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                float weight = luma[column] * (1f - saturation)
                        + (row == column ? saturation : 0f);
                matrix[row * 5 + column] = weight * keep;
            }
            int channel = (tint >> (16 - 8 * row)) & 0xFF;
            matrix[row * 5 + 4] = channel * tintAlpha * (1f - veil) + 255f * veil;
        }
        matrix[18] = 1f;
        return matrix;
    }

    /**
     * {@code matrix} applied to the input first covered by {@code argb} at its alpha: the panel
     * glass samples the backdrop as seen, i.e. under the backdrop's own tint (make_mockups glass).
     */
    static float[] under(float[] matrix, int argb) {
        float alpha = (argb >>> 24) / 255f;
        float[] result = matrix.clone();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                int channel = (argb >> (16 - 8 * column)) & 0xFF;
                result[row * 5 + 4] += matrix[row * 5 + column] * alpha * channel;
                result[row * 5 + column] = matrix[row * 5 + column] * (1f - alpha);
            }
        }
        return result;
    }

    /** Applies the RGB rows of a {@link #colorMatrix} to opaque pixels, clamped to 0..255. */
    static void applyColorMatrix(int[] rgb, float[] m) {
        for (int index = 0; index < rgb.length; index++) {
            int color = rgb[index];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            rgb[index] = 0xFF000000 | channel(m, 0, r, g, b) << 16
                    | channel(m, 5, r, g, b) << 8 | channel(m, 10, r, g, b);
        }
    }

    private static int channel(float[] m, int row, int r, int g, int b) {
        float value = m[row] * r + m[row + 1] * g + m[row + 2] * b + m[row + 4];
        return Math.max(0, Math.min(255, Math.round(value)));
    }
}
