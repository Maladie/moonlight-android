package com.limelight.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

public final class BatteryLevelDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int percentage;
    private final boolean charging;
    private final int color;

    public BatteryLevelDrawable(Context context, int percentage, boolean charging, int color) {
        density = context.getResources().getDisplayMetrics().density;
        this.percentage = percentage;
        this.charging = charging;
        this.color = color;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        float width = getBounds().width();
        float height = getBounds().height();
        float stroke = Math.max(1.5f * density, width * 0.075f);
        float terminalWidth = width * 0.09f;
        float bodyRight = width - terminalWidth - stroke;
        RectF body = new RectF(stroke, height * 0.18f, bodyRight, height * 0.82f);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(color);
        canvas.drawRoundRect(body, stroke, stroke, paint);

        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(bodyRight + stroke * 0.45f, height * 0.36f,
                width, height * 0.64f), stroke * 0.4f, stroke * 0.4f, paint);

        if (percentage >= 0) {
            float inset = stroke * 0.75f;
            RectF inner = new RectF(body.left + inset, body.top + inset,
                    body.right - inset, body.bottom - inset);
            float fill = Math.max(0f, Math.min(1f, percentage / 100f));
            if (fill > 0f) {
                inner.right = inner.left + inner.width() * fill;
                canvas.drawRoundRect(inner, stroke * 0.45f, stroke * 0.45f, paint);
            }
        }

        if (charging) {
            paint.setColor(0xFF101010);
            paint.setStrokeWidth(stroke * 1.1f);
            paint.setStyle(Paint.Style.STROKE);
            float cx = (body.left + body.right) / 2f;
            canvas.drawLine(cx + width * 0.06f, height * 0.29f,
                    cx - width * 0.04f, height * 0.50f, paint);
            canvas.drawLine(cx - width * 0.04f, height * 0.50f,
                    cx + width * 0.03f, height * 0.50f, paint);
            canvas.drawLine(cx + width * 0.03f, height * 0.50f,
                    cx - width * 0.06f, height * 0.71f, paint);
        }
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return (int) (24 * density); }
    @Override public int getIntrinsicHeight() { return (int) (24 * density); }
}
