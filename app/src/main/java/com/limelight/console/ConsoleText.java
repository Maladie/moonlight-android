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
        centreOnInk(view, "H");
    }

    /**
     * Centres the ink of {@code sample} instead, e.g. a controller glyph: that font has no
     * capitals and draws its glyphs well off the cap height. The view must be at least as
     * tall as the font's line box, or gravity cannot centre it.
     */
    static void centreOnInk(TextView view, String sample) {
        view.setIncludeFontPadding(false);
        Paint paint = view.getPaint();
        Paint.FontMetrics metrics = paint.getFontMetrics();
        Rect ink = new Rect();
        paint.getTextBounds(sample, 0, sample.length(), ink);
        // Padding on one side moves gravity-centred text by half of it.
        int shift = 2 * baselineShift(metrics.ascent, metrics.descent, -(ink.top + ink.bottom));
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop() + Math.max(0, shift),
                view.getPaddingRight(), view.getPaddingBottom() + Math.max(0, -shift));
    }

    /**
     * Pixels the baseline moves down so ink from the baseline up to {@code capHeight} (twice
     * the ink's centre above the baseline), not ascent plus descent, is centred.
     */
    static int baselineShift(float ascent, float descent, float capHeight) {
        return Math.round(capHeight / 2f + (ascent + descent) / 2f);
    }

    /**
     * One rule for every status dot (mockups "dot_status", "gate_header"): how far a
     * {@code size} px dot that gravity centres on the line box of {@code text}, beside it in
     * a row or as its compound drawable, moves down to sit on the middle of the cap height.
     * A half pixel rounds up: a dot reads low before it reads high.
     */
    static int dotOffset(TextView text, int size) {
        Paint paint = text.getPaint();
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        Rect cap = new Rect();
        paint.getTextBounds("H", 0, 1, cap);
        int centred = (metrics.descent - metrics.ascent - size) / 2;
        return (int) Math.floor(-metrics.ascent + cap.top / 2f - centred - size / 2f);
    }
}
