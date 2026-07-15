package com.limelight.console;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Opaque Wake-style preparation surface updated without changing focus. */
final class ConsoleLoadingController {
    private final Context context;
    private final float density;
    private TextView title;
    private TextView status;

    ConsoleLoadingController(Context context) {
        this.context = context;
        density = context.getResources().getDisplayMetrics().density;
    }

    View build() {
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        root.addView(new ConsoleGenerativeBackdrop(context), match());
        View shade = new View(context);
        shade.setBackgroundColor(0x57000000);
        root.addView(shade, match());

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_HORIZONTAL);
        title = label("", 38, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        copy.addView(title, new LinearLayout.LayoutParams(matchWidth(), wrapSize()));
        TextView message = label("Preparing your game…", 23, 0xFFE5E8F5, false);
        message.setGravity(Gravity.CENTER);
        copy.addView(message, top(dp(18)));
        status = label("Preparing wake sequence…", 14, 0xFFB8C0D9, false);
        status.setGravity(Gravity.CENTER);
        copy.addView(status, top(dp(14)));
        TextView hint = label("Press BACK to return to MoonWaker Home",
                14, 0xBFFFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        copy.addView(hint, top(dp(34)));
        root.addView(copy, new FrameLayout.LayoutParams(dp(850), wrapSize(), Gravity.CENTER));
        return root;
    }

    void show(String appName) {
        if (title != null) title.setText(appName == null ? "Preparing stream" : appName);
        updateStatus("Preparing wake sequence…");
    }

    void updateStatus(String value) {
        if (status != null) status.setText(value);
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.DEFAULT, bold ? 1 : 0);
        return view;
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(matchWidth(), wrapSize());
        params.topMargin = margin;
        return params;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(matchWidth(), matchHeight());
    }

    private int dp(int value) { return Math.round(value * density); }
    private static int matchWidth() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int matchHeight() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrapSize() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
}
