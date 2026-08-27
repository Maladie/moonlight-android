/*
 * Copyright (C) 2026 MoonWaker contributors
 *
 * Licensed under the GNU General Public License, Version 3 or later.
 *
 * Navigation and wide-key layout concepts were independently implemented with reference to
 * AOSP LeanbackIME's LeanbackKeyboardView.java (Copyright (C) 2019 The Android Open Source
 * Project; Apache-2.0): https://android.googlesource.com/platform/packages/inputmethods/
 * LeanbackIME/+/45d52e825cf7ca99e1a01522abb1f865eea252ec/src/com/android/inputmethod/leanback/
 * LeanbackKeyboardView.java
 */
package com.limelight.console;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.ui.ControllerGlyphs;

import java.util.LinkedHashMap;
import java.util.Map;

/** A non-focusable, controller-driven keyboard; the associated EditText always owns focus. */
final class EmbeddedTvKeyboardView extends FrameLayout {
    interface Callback {
        void onText(String value);
        void onBackspace();
        void onMoveCursor(int direction);
        void onMicrophone();
        void onSend();
        void onOpenVisibleAdditionalContent();
    }

    private static final int TEXT = 0xFFF4F7FF;
    private static final int MUTED = 0xFFA2A9BB;
    private static final int KEY = 0xFF243041;
    private static final int SELECTED = 0xFF18749E;
    private static final int KEYBOARD_HEIGHT_DP = 168;
    private static final int LEGEND_HEIGHT_DP = 22;
    private final EmbeddedTvKeyboardModel model = new EmbeddedTvKeyboardModel();
    private final Callback callback;
    private final String microphoneLabel;
    private final String spaceLabel;
    private final String sendLabel;
    private final String shiftLegend;
    private final String backspaceLegend;
    private final String cursorLegend;
    private final String sendLegend;
    private final String historyScrollLegend;
    private final String mediaOpenLegend;
    private final Map<String, TextView> keyViews = new LinkedHashMap<>();
    private LinearLayout controlLegend;

    EmbeddedTvKeyboardView(Context context, Callback callback, String microphoneLabel,
                           String spaceLabel, String sendLabel, String shiftLegend,
                           String backspaceLegend, String cursorLegend, String sendLegend,
                           String historyScrollLegend, String mediaOpenLegend) {
        super(context);
        this.callback = callback;
        this.microphoneLabel = microphoneLabel;
        this.spaceLabel = spaceLabel;
        this.sendLabel = sendLabel;
        this.shiftLegend = shiftLegend;
        this.backspaceLegend = backspaceLegend;
        this.cursorLegend = cursorLegend;
        this.sendLegend = sendLegend;
        this.historyScrollLegend = historyScrollLegend;
        this.mediaOpenLegend = mediaOpenLegend;
        setFocusable(false);
        setFocusableInTouchMode(false);
        setPadding(dp(2), dp(2), dp(2), dp(2));
        rebuild();
    }

    String selectedKeyId() { return model.selectedId(); }
    EmbeddedTvKeyboardModel.Page page() { return model.page(); }
    EmbeddedTvKeyboardModel.Shift shift() { return model.shift(); }

    void reset() {
        model.reset();
        rebuild();
    }

    boolean handleNavigationKey(KeyEvent event) {
        if (event == null) return false;
        int keyCode = event.getKeyCode();
        if (event.getAction() != KeyEvent.ACTION_DOWN) return isEmbeddedKeyboardHandledKey(keyCode);
        if (isTriangleSpaceKey(keyCode)) {
            if (event.getRepeatCount() == 0) {
                callback.onText(" ");
            }
            return true;
        }
        if (event.getRepeatCount() == 0 && keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            callback.onOpenVisibleAdditionalContent();
            return true;
        }
        if (event.getRepeatCount() == 0 && keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            model.cycleShift();
            refresh();
            return true;
        }
        if (event.getRepeatCount() == 0 && keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            callback.onSend();
            return true;
        }
        if (event.getRepeatCount() == 0 && keyCode == KeyEvent.KEYCODE_BUTTON_X) {
            callback.onBackspace();
            return true;
        }
        if (event.getRepeatCount() == 0 && keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            callback.onMoveCursor(-1);
            return true;
        }
        if (event.getRepeatCount() == 0 && keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
            callback.onMoveCursor(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (model.move(keyCode)) refresh();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            activate();
            return true;
        }
        return false;
    }

    static boolean isTriangleSpaceKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_Y;
    }

    static boolean isEmbeddedKeyboardHandledKey(int keyCode) {
        return isTriangleSpaceKey(keyCode) || keyCode == KeyEvent.KEYCODE_BUTTON_X
                || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_R1
                || keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2
                || keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;
        if (event.getAction() == MotionEvent.ACTION_UP) {
            String hit = keyAt(event.getX(), event.getY());
            if (hit != null) {
                model.select(hit);
                activate();
            }
        }
        return true;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = dp(KEYBOARD_HEIGHT_DP + LEGEND_HEIGHT_DP) + getPaddingTop() + getPaddingBottom();
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode == MeasureSpec.EXACTLY) height = MeasureSpec.getSize(heightMeasureSpec);
        else if (heightMode == MeasureSpec.AT_MOST) height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
        setMeasuredDimension(width, height);
        int keyAreaHeight = Math.max(1, height - getPaddingTop() - getPaddingBottom() - dp(LEGEND_HEIGHT_DP));
        measureKeyViews(width, keyAreaHeight);
        if (controlLegend != null) controlLegend.measure(
                MeasureSpec.makeMeasureSpec(Math.max(1, width - getPaddingLeft() - getPaddingRight()), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(LEGEND_HEIGHT_DP), MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        float availableWidth = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
        int legendHeight = dp(LEGEND_HEIGHT_DP);
        float availableHeight = Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom() - legendHeight);
        for (EmbeddedTvKeyboardModel.Key key : model.keys()) {
            TextView view = keyViews.get(key.id);
            if (view == null) continue;
            EmbeddedTvKeyboardModel.Bounds bounds = EmbeddedTvKeyboardModel.bounds(key,
                    Math.round(availableWidth), Math.round(availableHeight));
            int x = getPaddingLeft() + bounds.left;
            int nextX = getPaddingLeft() + bounds.right;
            int y = getPaddingTop() + bounds.top;
            int nextY = getPaddingTop() + bounds.bottom;
            int childWidth = Math.max(0, nextX - x - dp(4));
            int childHeight = Math.max(0, nextY - y - dp(4));
            view.layout(x + dp(2), y + dp(2), x + dp(2) + childWidth, y + dp(2) + childHeight);
        }
        if (controlLegend != null) {
            int legendTop = getHeight() - getPaddingBottom() - legendHeight;
            controlLegend.layout(getPaddingLeft(), legendTop, getWidth() - getPaddingRight(),
                    legendTop + legendHeight);
        }
    }

    private void activate() {
        EmbeddedTvKeyboardModel.Activation activation = model.activateSelected();
        switch (activation.action) {
            case TEXT:
            case SPACE:
                callback.onText(activation.text);
                break;
            case BACKSPACE:
                callback.onBackspace();
                break;
            case MICROPHONE:
                callback.onMicrophone();
                break;
            case SEND:
                callback.onSend();
                break;
            default:
                break;
        }
        rebuild();
    }

    private void rebuild() {
        removeAllViews();
        keyViews.clear();
        for (EmbeddedTvKeyboardModel.Key key : model.keys()) {
            TextView view = new TextView(getContext());
            view.setGravity(Gravity.CENTER);
            view.setTextColor(TEXT);
            view.setTextSize(13);
            view.setSingleLine(true);
            view.setText(displayLabel(key));
            view.setContentDescription(displayLabel(key));
            view.setFocusable(false);
            view.setClickable(false);
            keyViews.put(key.id, view);
            addView(view, new ViewGroup.LayoutParams(0, 0));
        }
        buildLegend();
        refresh();
    }

    private void buildLegend() {
        controlLegend = new LinearLayout(getContext());
        controlLegend.setGravity(Gravity.CENTER_VERTICAL);
        controlLegend.setOrientation(LinearLayout.HORIZONTAL);
        boolean playStation = ControllerGlyphs.hasPlayStationController();
        addLegendItem(shiftLegend, null, false);
        addLegendItem(backspaceLegend, ControllerGlyphs.text(playStation, ControllerGlyphs.Button.WEST), true);
        addLegendItem(cursorLegend, null, false);
        addLegendItem(sendLegend, null, false);
        addLegendItem(historyScrollLegend, ControllerGlyphs.text(playStation,
                ControllerGlyphs.Button.RIGHT_STICK), true);
        addLegendItem(mediaOpenLegend, ControllerGlyphs.text(playStation,
                ControllerGlyphs.Button.RIGHT_STICK), true);
        addView(controlLegend, new ViewGroup.LayoutParams(0, 0));
    }

    private void addLegendItem(String value, String glyph, boolean glyphFirst) {
        LinearLayout item = new LinearLayout(getContext());
        item.setGravity(Gravity.CENTER);
        if (glyphFirst && glyph != null) {
            TextView icon = new TextView(getContext());
            icon.setText(glyph);
            icon.setTextColor(MUTED);
            icon.setTextSize(11);
            icon.setTypeface(ControllerGlyphs.typeface(getContext()));
            item.addView(icon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView label = new TextView(getContext());
        label.setText(value);
        label.setTextColor(MUTED);
        label.setTextSize(9);
        label.setSingleLine(true);
        if (glyphFirst && glyph != null) label.setPadding(dp(3), 0, 0, 0);
        item.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        controlLegend.addView(item, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void refresh() {
        for (EmbeddedTvKeyboardModel.Key key : model.keys()) {
            TextView view = keyViews.get(key.id);
            if (view == null) continue;
            view.setText(displayLabel(key));
            view.setContentDescription(displayLabel(key));
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(6));
            boolean selected = key.id.equals(model.selectedId());
            background.setColor(selected ? SELECTED : KEY);
            background.setStroke(selected ? dp(2) : 0, selected ? Color.WHITE : Color.TRANSPARENT);
            view.setBackground(background);
        }
        requestLayout();
    }

    private String keyAt(float x, float y) {
        float availableWidth = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
        float availableHeight = Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom()
                - dp(LEGEND_HEIGHT_DP));
        float localX = x - getPaddingLeft();
        float localY = y - getPaddingTop();
        for (EmbeddedTvKeyboardModel.Key key : model.keys()) {
            if (EmbeddedTvKeyboardModel.bounds(key, Math.round(availableWidth),
                    Math.round(availableHeight)).contains(localX, localY)) return key.id;
        }
        return null;
    }

    private void measureKeyViews(int width, int height) {
        int availableWidth = Math.max(1, width - getPaddingLeft() - getPaddingRight());
        int availableHeight = Math.max(1, height - getPaddingTop() - getPaddingBottom());
        for (EmbeddedTvKeyboardModel.Key key : model.keys()) {
            TextView view = keyViews.get(key.id);
            if (view == null) continue;
            EmbeddedTvKeyboardModel.Bounds bounds = EmbeddedTvKeyboardModel.bounds(key,
                    availableWidth, availableHeight);
            view.measure(MeasureSpec.makeMeasureSpec(Math.max(0, bounds.right - bounds.left - dp(4)),
                            MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, bounds.bottom - bounds.top - dp(4)),
                            MeasureSpec.EXACTLY));
        }
    }

    private String displayLabel(EmbeddedTvKeyboardModel.Key key) {
        if (key.type == EmbeddedTvKeyboardModel.Type.MICROPHONE) return microphoneLabel;
        if (key.type == EmbeddedTvKeyboardModel.Type.SPACE) return spaceLabel;
        if (key.type == EmbeddedTvKeyboardModel.Type.SEND) return sendLabel;
        return model.displayLabel(key);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
