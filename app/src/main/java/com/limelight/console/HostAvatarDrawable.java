package com.limelight.console;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Round generated artwork for a profile, seeded by its id: hue field, soft blobs, light wave. */
final class HostAvatarDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int seed;

    HostAvatarDrawable(String value) {
        seed = value == null ? 0 : value.hashCode();
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        Path clip = new Path();
        clip.addCircle(cx, cy, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        float hue = Math.abs(seed % 360);
        paint.setColor(Color.HSVToColor(new float[]{hue, .58f, .62f}));
        canvas.drawCircle(cx, cy, radius, paint);
        for (int index = 0; index < 6; index++) {
            int shifted = seed >> (index * 3);
            float x = bounds.left + bounds.width() * (.12f + .17f * (index % 4));
            float y = bounds.top + bounds.height() * (.12f + .21f * ((shifted & 3)));
            float width = radius * (.75f + .12f * (index % 3));
            float height = radius * (.35f + .1f * ((index + 1) % 3));
            float blobHue = (hue + 48f + index * 43f) % 360f;
            paint.setColor(Color.HSVToColor(185,
                    new float[]{blobHue, .62f, .94f}));
            canvas.save();
            canvas.rotate((shifted & 63) - 31f, x, y);
            canvas.drawOval(new RectF(x - width, y - height,
                    x + width, y + height), paint);
            canvas.restore();
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, radius * .08f));
        paint.setColor(0x55FFFFFF);
        Path wave = new Path();
        wave.moveTo(bounds.left - radius * .2f, cy + radius * .35f);
        wave.cubicTo(cx - radius * .6f, cy - radius * .7f,
                cx + radius * .25f, cy + radius * .8f,
                bounds.right + radius * .2f, cy - radius * .2f);
        canvas.drawPath(wave, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(android.graphics.ColorFilter filter) {
        paint.setColorFilter(filter);
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
