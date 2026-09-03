package com.limelight.console;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/** Small controller-first MoonWaker confirmation card usable from the stream Activity. */
public final class ConsoleConfirmDialog {
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
        card.setPadding(dp(activity, 30), dp(activity, 26), dp(activity, 30), dp(activity, 24));
        card.setBackground(roundRect(0xF21A222C, 22, 0x557DD8FF, 1));

        TextView heading = label(activity, title, 24, Color.WHITE, true);
        card.addView(heading, matchWrap());
        TextView body = label(activity, message, 15, 0xFFC4CCD6, false);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(activity, 12);
        bodyParams.bottomMargin = dp(activity, 24);
        card.addView(body, bodyParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        TextView cancel = action(activity, cancelLabel, false);
        TextView confirm = action(activity, confirmLabel, true);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                dp(activity, 190), dp(activity, 52));
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                dp(activity, 230), dp(activity, 52));
        confirmParams.leftMargin = dp(activity, 12);
        actions.addView(cancel, actionParams);
        actions.addView(confirm, confirmParams);
        card.addView(actions, matchWrap());

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
            window.setDimAmount(.72f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(dp(activity, 720), ViewGroup.LayoutParams.WRAP_CONTENT);
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

    private static TextView action(Activity activity, CharSequence text, boolean primary) {
        TextView view = label(activity, text, 16, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setSoundEffectsEnabled(false);
        view.setBackground(roundRect(primary ? 0xFF287FA8 : 0xFF303A46, 14,
                primary ? 0xFF8DDEFF : 0x447A8794, 1));
        view.setOnFocusChangeListener((button, focused) -> button.setBackground(roundRect(
                focused ? 0xFF459EC4 : primary ? 0xFF287FA8 : 0xFF303A46,
                14, focused ? Color.WHITE : primary ? 0xFF8DDEFF : 0x447A8794,
                focused ? 2 : 1)));
        return view;
    }

    private static TextView label(Activity activity, CharSequence text, float size,
                                  int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        return view;
    }

    private static GradientDrawable roundRect(int color, int radiusDp,
                                              int strokeColor, int strokeDp) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(radiusDp * 2f);
        result.setStroke(strokeDp, strokeColor);
        return result;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
