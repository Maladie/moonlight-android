package com.limelight.console;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.ui.ControllerGlyphs;

import java.util.Locale;

/** Full-screen PIN board with direct controller shortcuts and a TV-remote keypad. */
final class ConsolePinEntryView extends FrameLayout {
    interface Listener {
        void onDigit(int digit);
    }

    private final TextView[] markers = new TextView[4];
    private final View[] keys = new View[10];
    private final TextView[] keyGlyphs = new TextView[10];
    private final TextView[] actionGlyphs = new TextView[2];
    private TextView feedback;
    private boolean playStationButtons;
    private boolean directControllerInput;

    ConsolePinEntryView(Context context) {
        super(context);
        setVisibility(GONE);
        setBackgroundColor(0xFF0B1019);
    }

    void show(String hostName, HostGatewayClient.IntegrationProfile profile,
              boolean playStationButtons, boolean directControllerInput,
              Listener listener) {
        this.playStationButtons = playStationButtons;
        this.directControllerInput = directControllerInput;
        removeAllViews();

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(40), dp(24), dp(40), dp(18));
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView eyebrow = label(hostName == null ? "" : hostName.toUpperCase(Locale.ROOT),
                11, 0xFF8EA7C5, true);
        eyebrow.setGravity(Gravity.CENTER);
        content.addView(eyebrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));

        TextView title = label(getContext().getString(R.string.console_pin_title),
                25, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        TextView profileName = label(profile.name.toUpperCase(Locale.ROOT),
                14, 0xFFD3DAE5, true);
        profileName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(27));
        profileParams.bottomMargin = dp(3);
        content.addView(profileName, profileParams);

        LinearLayout markerRow = new LinearLayout(getContext());
        markerRow.setGravity(Gravity.CENTER);
        for (int index = 0; index < markers.length; index++) {
            markers[index] = label("", 22, Color.WHITE, true);
            markers[index].setGravity(Gravity.CENTER);
            markers[index].setIncludeFontPadding(false);
            LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(
                    dp(42), dp(34));
            if (index > 0) markerParams.leftMargin = dp(5);
            markerRow.addView(markers[index], markerParams);
        }
        content.addView(markerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        feedback = label("", 12, 0xFFFFC857, false);
        feedback.setGravity(Gravity.CENTER);
        feedback.setMaxLines(2);
        content.addView(feedback, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        LinearLayout board = new LinearLayout(getContext());
        board.setOrientation(LinearLayout.VERTICAL);
        board.setGravity(Gravity.CENTER_HORIZONTAL);
        board.setPadding(dp(10), dp(10), dp(10), dp(8));
        GradientDrawable boardBackground = new GradientDrawable();
        boardBackground.setColor(0x8C121923);
        boardBackground.setCornerRadius(dp(3));
        boardBackground.setStroke(dp(1), 0x241F2A35);
        board.setBackground(boardBackground);
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(
                dp(310), ViewGroup.LayoutParams.WRAP_CONTENT);
        content.addView(board, boardParams);

        GridLayout keypad = new GridLayout(getContext());
        keypad.setColumnCount(3);
        keypad.setRowCount(4);
        for (int digit = 1; digit <= 9; digit++) addKey(keypad, digit, listener);
        addSpacer(keypad);
        addKey(keypad, 0, listener);
        addSpacer(keypad);
        board.addView(keypad, new LinearLayout.LayoutParams(dp(288), dp(184)));

        LinearLayout legend = new LinearLayout(getContext());
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setGravity(Gravity.CENTER);
        addActionLegendItem(legend, 0, ControllerGlyphs.Button.CONFIRM,
                getContext().getString(R.string.console_pin_submit));
        addActionLegendItem(legend, 1, ControllerGlyphs.Button.CANCEL,
                getContext().getString(R.string.console_pin_delete_or_back));
        board.addView(legend, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        setVisibility(VISIBLE);
        renderMarkers(0);
        if (keys[1] != null) keys[1].post(keys[1]::requestFocus);
    }

    void setPlayStationButtons(boolean playStationButtons) {
        if (this.playStationButtons == playStationButtons) return;
        this.playStationButtons = playStationButtons;
        for (int digit = 0; digit < keyGlyphs.length; digit++) {
            if (keyGlyphs[digit] != null) updateGlyph(keyGlyphs[digit], digit);
        }
        if (actionGlyphs[0] != null) updateActionGlyph(actionGlyphs[0],
                ControllerGlyphs.Button.CONFIRM);
        if (actionGlyphs[1] != null) updateActionGlyph(actionGlyphs[1],
                ControllerGlyphs.Button.CANCEL);
    }

    void render(int enteredDigits, String message, boolean enabled) {
        renderMarkers(enteredDigits);
        feedback.setText(message == null ? "" : message);
        setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.82f);
    }

    private void renderMarkers(int enteredDigits) {
        if (feedback == null && markers[0] == null) return;
        for (int index = 0; index < markers.length; index++) {
            boolean filled = index < enteredDigits;
            boolean active = index == enteredDigits;
            markers[index].setText(filled ? "•" : active ? "" : "―");
            markers[index].setTextColor(filled || active ? Color.WHITE : 0xFF6A7480);
            markers[index].setBackground(filled || active
                    ? markerBackground(filled, active) : null);
        }
    }

    private void addSpacer(GridLayout keypad) {
        keypad.addView(new View(getContext()), keyParams());
    }

    private View addKey(GridLayout keypad, int digit, Listener listener) {
        LinearLayout key = new LinearLayout(getContext());
        key.setId(View.generateViewId());
        key.setOrientation(LinearLayout.HORIZONTAL);
        key.setGravity(Gravity.CENTER);
        key.setPadding(dp(5), 0, dp(5), 0);
        key.setFocusable(true);
        key.setFocusableInTouchMode(true);
        key.setClickable(true);
        key.setSoundEffectsEnabled(false);

        TextView number = label(Integer.toString(digit), 18, Color.WHITE, true);
        number.setGravity(Gravity.CENTER);
        number.setIncludeFontPadding(false);
        key.addView(number, new LinearLayout.LayoutParams(dp(22), dp(30)));

        TextView glyph = label("", 24, 0xFFD8E0E8, false);
        glyph.setGravity(Gravity.CENTER);
        glyph.setIncludeFontPadding(false);
        glyph.setTypeface(ControllerGlyphs.typeface(getContext()));
        LinearLayout.LayoutParams glyphParams = new LinearLayout.LayoutParams(dp(34), dp(30));
        glyphParams.leftMargin = dp(4);
        key.addView(glyph, glyphParams);
        keyGlyphs[digit] = glyph;
        updateGlyph(glyph, digit);

        key.setContentDescription(Integer.toString(digit));
        key.setBackground(keyBackground(false));
        key.setOnClickListener(view -> listener.onDigit(digit));
        key.setOnFocusChangeListener((view, focused) -> {
            styleKey(key, focused);
        });
        keypad.addView(key, keyParams());
        keys[digit] = key;
        return key;
    }

    void setControllerInputMode(boolean directControllerInput) {
        if (this.directControllerInput == directControllerInput) return;
        this.directControllerInput = directControllerInput;
        for (View key : keys) {
            if (key != null) styleKey(key, key.hasFocus());
        }
    }

    private void styleKey(View key, boolean focused) {
        boolean visibleFocus = focused && !directControllerInput;
        key.setBackground(keyBackground(visibleFocus));
        key.animate().scaleX(visibleFocus ? 1.08f : 1f)
                .scaleY(visibleFocus ? 1.08f : 1f).setDuration(100).start();
    }

    private void updateGlyph(TextView glyph, int digit) {
        glyph.setText(ControllerGlyphs.text(playStationButtons, buttonForDigit(digit)));
    }

    private void addActionLegendItem(LinearLayout legend, int index,
                                     ControllerGlyphs.Button button, String label) {
        if (legend.getChildCount() > 0) {
            View separator = new View(getContext());
            separator.setBackgroundColor(0x336E8291);
            LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(
                    dp(1), dp(18));
            separatorParams.setMargins(dp(12), 0, dp(12), 0);
            legend.addView(separator, separatorParams);
        }
        TextView glyph = label("", 18, 0xFFD8E0E8, false);
        glyph.setGravity(Gravity.CENTER);
        glyph.setIncludeFontPadding(false);
        glyph.setTypeface(ControllerGlyphs.typeface(getContext()));
        legend.addView(glyph, new LinearLayout.LayoutParams(dp(24), dp(24)));
        actionGlyphs[index] = glyph;
        updateActionGlyph(glyph, button);
        TextView description = label(label, 12, 0xFFD3DAE5, true);
        description.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(26));
        descriptionParams.leftMargin = dp(4);
        legend.addView(description, descriptionParams);
    }

    private void updateActionGlyph(TextView glyph, ControllerGlyphs.Button button) {
        glyph.setText(ControllerGlyphs.text(playStationButtons, button));
    }

    private ControllerGlyphs.Button buttonForDigit(int digit) {
        switch (digit) {
            case 1: return ControllerGlyphs.Button.DPAD_LEFT;
            case 2: return ControllerGlyphs.Button.DPAD_UP;
            case 3: return ControllerGlyphs.Button.DPAD_RIGHT;
            case 4: return ControllerGlyphs.Button.DPAD_DOWN;
            case 5: return ControllerGlyphs.Button.RIGHT_BUMPER;
            case 6: return ControllerGlyphs.Button.RIGHT_TRIGGER;
            case 7: return ControllerGlyphs.Button.LEFT_BUMPER;
            case 8: return ControllerGlyphs.Button.LEFT_TRIGGER;
            case 9: return ControllerGlyphs.Button.NORTH;
            case 0: return ControllerGlyphs.Button.WEST;
            default: throw new IllegalArgumentException("Invalid PIN digit");
        }
    }

    private GridLayout.LayoutParams keyParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = dp(90);
        params.height = dp(42);
        params.setMargins(dp(3), dp(2), dp(3), dp(2));
        return params;
    }

    private GradientDrawable keyBackground(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(7));
        drawable.setColor(focused ? 0xFF29476B : 0x0018263A);
        drawable.setStroke(dp(focused ? 2 : 1),
                focused ? 0xFFF4F8FF : 0x405E7189);
        return drawable;
    }

    private GradientDrawable markerBackground(boolean filled, boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(4));
        drawable.setColor(filled ? 0xFF263443 : 0x00141C26);
        drawable.setStroke(dp(active ? 2 : 1), active ? 0xFFE8F1F5 : 0x705E7189);
        return drawable;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
