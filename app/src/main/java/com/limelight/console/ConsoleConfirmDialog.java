package com.limelight.console;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.ui.ControllerGlyphs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Small controller-first MoonWaker confirmation card usable from the stream Activity: the
 * console glass card (ConsoleGlass tint, hairline, corners) with the side panel's type scale,
 * focus pills and a controller-glyph legend (UX_MENU_PL.md section 4).
 */
public final class ConsoleConfirmDialog {
    private static final int WIDTH_DP = 480;
    private static final int ACTION_HEIGHT_DP = 44;
    private static final int BODY = 0xFFC8CCD6;

    private ConsoleConfirmDialog() {}

    public static void show(Activity activity, CharSequence title, CharSequence message,
                            CharSequence cancelLabel, CharSequence confirmLabel,
                            Runnable onConfirm) {
        show(activity, title, message, cancelLabel, confirmLabel, onConfirm,
                0L, false, null);
    }

    public static Dialog show(Activity activity, CharSequence title, CharSequence message,
                              CharSequence cancelLabel, CharSequence confirmLabel,
                              Runnable onConfirm, long actionDelayMs,
                              Runnable onDismiss) {
        if (actionDelayMs < 0) throw new IllegalArgumentException("Negative action delay");
        return show(activity, title, message, cancelLabel, confirmLabel, onConfirm,
                actionDelayMs, true, onDismiss);
    }

    private static Dialog show(Activity activity, CharSequence title, CharSequence message,
                               CharSequence cancelLabel, CharSequence confirmLabel,
                               Runnable onConfirm, long actionDelayMs,
                               boolean focusCancel, Runnable onDismiss) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 28), dp(activity, 24), dp(activity, 28), dp(activity, 18));
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(ConsoleGlass.SOLID);
        glass.setCornerRadius(dp(activity, ConsoleGlass.RADIUS_DP));
        glass.setStroke(hairline(activity), ConsoleGlass.HAIRLINE_TOP);
        card.setBackground(glass);

        TextView heading = label(activity, title, ConsolePanel.TITLE_SP, ConsolePanel.TEXT, true);
        card.addView(heading, matchWrap());
        TextView body = label(activity, message, ConsolePanel.LABEL_SP, BODY, false);
        body.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(activity, 10);
        bodyParams.bottomMargin = dp(activity, 22);
        card.addView(body, bodyParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        TextView cancel = action(activity, cancelLabel);
        TextView confirm = action(activity, confirmLabel);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, ACTION_HEIGHT_DP));
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, ACTION_HEIGHT_DP));
        confirmParams.leftMargin = dp(activity, 10);
        actions.addView(cancel, actionParams);
        actions.addView(confirm, confirmParams);
        card.addView(actions, matchWrap());

        LinearLayout legend = new LinearLayout(activity);
        legend.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        boolean playStation = ControllerGlyphs.hasPlayStationController();
        List<DiscordCommunityLegend.Item> legendItems = new ArrayList<>();
        legendItems.add(new DiscordCommunityLegend.Item(ControllerGlyphs.text(playStation,
                ControllerGlyphs.Button.CONFIRM), true,
                activity.getString(R.string.console_select_hint)));
        legendItems.add(new DiscordCommunityLegend.Item(ControllerGlyphs.text(playStation,
                ControllerGlyphs.Button.CANCEL), true,
                activity.getString(R.string.discord_community_back)));
        DiscordCommunityLegend.fillRow(legend, legendItems);
        LinearLayout.LayoutParams legendParams = matchWrap();
        legendParams.topMargin = dp(activity, 16);
        card.addView(legend, legendParams);

        boolean delayed = actionDelayMs > 0;
        boolean[] actionsEnabled = {!delayed};
        Set<Integer> blockedKeys = new HashSet<>();
        cancel.setEnabled(!delayed);
        confirm.setEnabled(!delayed);
        cancel.setAlpha(delayed ? .45f : 1f);
        confirm.setAlpha(delayed ? .45f : 1f);
        dialog.setCancelable(!delayed);
        cancel.setOnClickListener(view -> {
            if (actionsEnabled[0]) dialog.dismiss();
        });
        confirm.setOnClickListener(view -> {
            if (!actionsEnabled[0]) return;
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        if (onDismiss != null) dialog.setOnDismissListener(ignored -> onDismiss.run());
        dialog.setContentView(card);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (isActionKey(keyCode) && !actionsEnabled[0]) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) blockedKeys.add(keyCode);
                return true;
            }
            if (blockedKeys.contains(keyCode)) {
                if (event.getAction() == KeyEvent.ACTION_UP) blockedKeys.remove(keyCode);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_BUTTON_B)) {
                dialog.dismiss();
                return true;
            }
            return false;
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(.6f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(dp(activity, WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (delayed) {
            card.postDelayed(() -> {
                if (!dialog.isShowing()) return;
                actionsEnabled[0] = true;
                dialog.setCancelable(true);
                cancel.setEnabled(true);
                confirm.setEnabled(true);
                cancel.setAlpha(1f);
                confirm.setAlpha(1f);
                cancel.requestFocus();
            }, actionDelayMs);
        } else if (focusCancel) {
            cancel.requestFocus();
        } else {
            confirm.requestFocus();
        }
        return dialog;
    }

    private static boolean isActionKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER;
    }

    /** A glass capsule at rest; focused, the section 4.2 near-white fill with dark ink. */
    private static TextView action(Activity activity, CharSequence text) {
        TextView view = label(activity, text, ConsolePanel.LABEL_SP, ConsolePanel.TEXT, true);
        view.setTextColor(ConsolePanel.rowColors(ConsolePanel.TEXT));
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setMinWidth(dp(activity, 132));
        view.setPadding(dp(activity, 22), 0, dp(activity, 22), 0);
        view.setFocusable(true);
        view.setClickable(true);
        view.setSoundEffectsEnabled(false);
        int radius = dp(activity, ACTION_HEIGHT_DP) / 2;
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(0x1EFFFFFF);
        rest.setCornerRadius(radius);
        rest.setStroke(hairline(activity), 0x28FFFFFF);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(ConsolePanel.FOCUS_FILL);
        focused.setCornerRadius(radius);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        background.addState(new int[0], rest);
        view.setBackground(background);
        return view;
    }

    private static TextView label(Activity activity, CharSequence text, float size,
                                  int color, boolean medium) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int hairline(Activity activity) {
        return Math.max(1, Math.round(ConsoleGlass.HAIRLINE_DP * density(activity)));
    }

    private static float density(Activity activity) {
        return activity.getResources().getDisplayMetrics().density;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * density(activity));
    }
}
