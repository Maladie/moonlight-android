package com.limelight.console;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;

/** Wake-derived glass card focus and pressed states for the unified Home. */
final class ConsoleTheme {
    private final float density;

    ConsoleTheme(Context context) {
        density = context.getResources().getDisplayMetrics().density;
    }

    StateListDrawable cardBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, shape(true, true));
        states.addState(new int[]{android.R.attr.state_focused}, shape(true, false));
        states.addState(new int[0], shape(false, false));
        return states;
    }

    void onCardFocus(View card, boolean focused) {
        card.animate().cancel();
        card.setElevation(dp(focused ? 9 : 3));
        card.setTranslationZ(dp(focused ? 2 : 0));
        float scale = focused ? 1.018f : 1f;
        if (card.isLaidOut()) {
            card.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        }
        else {
            card.setScaleX(scale);
            card.setScaleY(scale);
        }
    }

    private GradientDrawable shape(boolean focused, boolean pressed) {
        int focusedTop = ConsolePalette.withAlpha(
                ConsolePalette.blend(0xFF715BA8, ConsolePalette.ACCENT,
                        pressed ? 0.58f : 0.48f), pressed ? 0xE2 : 0xC8);
        int focusedBottom = ConsolePalette.withAlpha(
                ConsolePalette.blend(0xFF403362, ConsolePalette.ACCENT,
                        pressed ? 0.42f : 0.32f), pressed ? 0xC9 : 0xB1);
        int restingMiddle = ConsolePalette.withAlpha(
                ConsolePalette.blend(0xFF242B3D, ConsolePalette.ACCENT, 0.18f), 0x34);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                focused ? new int[]{focusedTop, focusedBottom} :
                        new int[]{0x18FFFFFF, restingMiddle, 0x5A131825});
        background.setCornerRadius(dp(14));
        background.setStroke(dp(focused ? 2 : 1),
                focused ? ConsolePalette.FOCUS_STROKE : ConsolePalette.REST_STROKE);
        return background;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
