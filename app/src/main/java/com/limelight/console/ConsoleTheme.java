package com.limelight.console;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;

/** Wake-derived glass card focus and pressed states for the unified Home. */
final class ConsoleTheme {
    private final float density;
    private boolean uiSounds = true;
    private boolean reducedMotion;

    ConsoleTheme(Context context) {
        density = context.getResources().getDisplayMetrics().density;
    }

    void setInterfacePreferences(boolean uiSounds, boolean reducedMotion) {
        this.uiSounds = uiSounds;
        this.reducedMotion = reducedMotion;
    }

    void prepareInteractiveView(View view) {
        view.setSoundEffectsEnabled(uiSounds);
    }

    boolean isReducedMotion() {
        return reducedMotion;
    }

    void applyInterfacePreferences(View root) {
        if (root == null) return;
        if (root.isClickable() || root.isFocusable()) prepareInteractiveView(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                applyInterfacePreferences(group.getChildAt(index));
            }
        }
    }

    StateListDrawable cardBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, shape(true, true));
        states.addState(new int[]{android.R.attr.state_focused}, shape(true, false));
        states.addState(new int[0], shape(false, false));
        return states;
    }

    StateListDrawable hostCardBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, hostShape(true, true));
        states.addState(new int[]{android.R.attr.state_focused}, hostShape(true, false));
        states.addState(new int[]{android.R.attr.state_selected}, hostShape(false, true));
        states.addState(new int[0], hostShape(false, false));
        return states;
    }

    void onCardFocus(View card, boolean focused) {
        prepareInteractiveView(card);
        card.animate().cancel();
        card.setElevation(dp(focused ? 9 : 3));
        card.setTranslationZ(dp(focused ? 2 : 0));
        float scale = focused ? 1.022f : 1f;
        if (!reducedMotion && card.isLaidOut()) {
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

    private GradientDrawable hostShape(boolean focused, boolean selected) {
        int focusedTop = ConsolePalette.withAlpha(
                ConsolePalette.blend(0xFF62577F, ConsolePalette.ACCENT, 0.36f), 0xAF);
        int focusedBottom = ConsolePalette.withAlpha(
                ConsolePalette.blend(0xFF353047, ConsolePalette.ACCENT, 0.24f), 0x9A);
        int[] colors = focused ? new int[]{focusedTop, focusedBottom} :
                selected ? new int[]{0x18FFFFFF, 0x38212838, 0x58161B28} :
                        new int[]{0x10FFFFFF, 0x301C2333, 0x50131825};
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, colors);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFFDCD5F2 :
                selected ? 0x4C8B94AD : 0x407C89B2);
        return background;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
