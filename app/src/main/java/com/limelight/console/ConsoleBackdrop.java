package com.limelight.console;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Quiet graphite backdrop shared by the dashboard and its animated launch screen. */
final class ConsoleBackdrop extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient base;
    private RadialGradient glow;
    private boolean animated;
    private long startedAt;

    ConsoleBackdrop(Context context) {
        super(context);
    }

    void start() {
        animated = true;
        startedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    void stop() {
        animated = false;
    }

    long getStartedAt() {
        return startedAt;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width <= 0 || height <= 0) return;
        base = new LinearGradient(0, 0, width, height,
                new int[]{0xFF080A0E, 0xFF10151B, 0xFF0A0D12},
                new float[]{0f, .58f, 1f}, Shader.TileMode.CLAMP);
        // Centred on the origin; onDraw translates the canvas to the animated glow centre.
        glow = new RadialGradient(0, 0, Math.max(width, height) * .44f,
                new int[]{0x1F4A9BC2, 0x0C28495D, 0x00080A0E},
                new float[]{0f, .48f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        float phase = animated ? (SystemClock.uptimeMillis() - startedAt) / 12000f : 0f;
        paint.setShader(base);
        canvas.drawRect(0, 0, width, height, paint);
        float glowX = width * (.78f + (animated ? .015f * (float) Math.sin(phase) : 0f));
        float glowY = height * (.42f + (animated ? .015f * (float) Math.cos(phase) : 0f));
        paint.setShader(glow);
        canvas.save();
        canvas.translate(glowX, glowY);
        canvas.drawRect(-glowX, -glowY, width - glowX, height - glowY, paint);
        canvas.restore();
        paint.setShader(null);
        if (animated) postInvalidateDelayed(32);
    }
}
