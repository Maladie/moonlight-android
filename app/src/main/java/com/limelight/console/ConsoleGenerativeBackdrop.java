package com.limelight.console;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** The original Wake Home generative background, kept independent from artwork. */
final class ConsoleGenerativeBackdrop extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ConsoleGenerativeBackdrop(Context context) {
        super(context);
    }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[]{0xFF090B14, 0xFF171633, 0xFF311A58}, null,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        paint.setColor(0x287C4DFF);
        canvas.drawCircle(width * .80f, height * .18f, height * .42f, paint);
        paint.setColor(0x2037B5FF);
        canvas.drawCircle(width * .18f, height * .88f, height * .50f, paint);
    }
}
