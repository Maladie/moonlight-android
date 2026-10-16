package com.limelight.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextClock;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

/**
 * The console's wall clock, top right of a full-screen layer: host/profile screens, the
 * library bar and the transition cover show this one view, which ticks by itself.
 */
public final class ConsoleClock {
    private static final float SIZE_SP = 22f;
    private static final int COLOR = 0xFFF5F6F9;
    private static final int TOP_DP = 31;
    private static final int END_DP = 48;

    private ConsoleClock() { }

    public static TextClock create(Context context) {
        TextClock clock = new TextClock(context);
        // The locale's short time ("09:05", "9:05 AM") whatever the 12/24 h system setting.
        String pattern = ((SimpleDateFormat) DateFormat.getTimeInstance(DateFormat.SHORT))
                .toPattern();
        clock.setFormat12Hour(pattern);
        clock.setFormat24Hour(pattern);
        clock.setTextSize(SIZE_SP);
        clock.setTextColor(COLOR);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setGravity(Gravity.CENTER);
        clock.setIncludeFontPadding(false);
        return clock;
    }

    /** Its place in a full-screen {@link FrameLayout}. */
    public static FrameLayout.LayoutParams topEnd(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(TOP_DP * density);
        params.setMarginEnd(Math.round(END_DP * density));
        return params;
    }
}
