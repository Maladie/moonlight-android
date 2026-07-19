package com.limelight.console;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Native-canvas backdrop shared by the dashboard and its animated launch screen. */
final class ConsoleBackdrop extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        float phase = animated ? (SystemClock.uptimeMillis() - startedAt) / 9000f : 0f;
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[]{0xFF090B14, 0xFF171633, 0xFF311A58}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        paint.setColor(0x347C4DFF);
        canvas.drawCircle(width * (.76f + .05f * (float) Math.sin(phase)),
                height * (.20f + .05f * (float) Math.cos(phase)), height * .44f, paint);
        paint.setColor(0x2937B5FF);
        canvas.drawCircle(width * (.20f + .04f * (float) Math.cos(phase * .8f)),
                height * (.84f + .04f * (float) Math.sin(phase * .8f)), height * .52f, paint);
        if (animated) postInvalidateDelayed(32);
    }
}
