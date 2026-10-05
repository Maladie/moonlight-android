package com.limelight.console;

import android.view.View;
import android.widget.TextView;

/**
 * Hides a single-line card title when only a fragment of it fits. Measures the text itself,
 * so the decision is the same whether the title is ellipsized or running as a marquee.
 */
final class CardTitleFit {
    static final int MIN_READABLE_CHARS = 8;
    static final View.OnLayoutChangeListener ON_LAYOUT =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    apply((TextView) view);

    private CardTitleFit() {
    }

    /** Call after layout and after each text change; INVISIBLE keeps the layout stable. */
    static void apply(TextView title) {
        int available = title.getWidth() - title.getTotalPaddingLeft()
                - title.getTotalPaddingRight();
        if (available <= 0) return;
        CharSequence text = title.getText();
        int fitting = title.getPaint().breakText(text, 0, text.length(), true, available, null);
        title.setVisibility(readable(text.length(), fitting) ? View.VISIBLE : View.INVISIBLE);
    }

    /** One fitting character is given up to the ellipsis when the title is truncated. */
    static boolean readable(int length, int fittingChars) {
        return fittingChars >= length || fittingChars - 1 >= MIN_READABLE_CHARS;
    }
}
