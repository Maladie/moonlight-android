package com.limelight.console;

import android.graphics.Paint;
import android.graphics.Rect;
import android.widget.TextView;

/**
 * Text centring for rows, pills and chips (UX_MENU_PL.md section 4.6): without font padding,
 * gravity centres the font's ascent-descent box; this moves the text so the cap height is
 * centred instead, whatever font the device ships.
 */
final class ConsoleText {
    private ConsoleText() { }

    /** Call after the text size, typeface and padding are final. */
    static void centreOnGlyphs(TextView view) {
        view.setIncludeFontPadding(false);
        Paint paint = view.getPaint();
        Paint.FontMetrics metrics = paint.getFontMetrics();
        Rect cap = new Rect();
        paint.getTextBounds("H", 0, 1, cap);
        // Padding on one side moves gravity-centred text by half of it.
        int shift = 2 * baselineShift(metrics.ascent, metrics.descent, -cap.top);
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop() + Math.max(0, shift),
                view.getPaddingRight(), view.getPaddingBottom() + Math.max(0, -shift));
    }

    /** Pixels the baseline moves down so cap height, not ascent plus descent, is centred. */
    static int baselineShift(float ascent, float descent, float capHeight) {
        return Math.round(capHeight / 2f + (ascent + descent) / 2f);
    }
}
