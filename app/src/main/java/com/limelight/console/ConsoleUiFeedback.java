package com.limelight.console;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.inputmethod.EditorInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;

/** Branded, controller-first notifications and modal cards for the console shell. */
final class ConsoleUiFeedback {
    enum Kind { INFO, SUCCESS, WARNING, ERROR, PROGRESS }

    interface InputAction {
        /** Return an error message to keep the dialog open, or null to dismiss it. */
        String run(String value);
    }

    static final class Message {
        private final WeakReference<Context> context;
        private final CharSequence text;
        private final int duration;

        Message(Context context, CharSequence text, int duration) {
            this.context = new WeakReference<>(context);
            this.text = text;
            this.duration = duration;
        }

        void show() {
            Context current = context.get();
            ConsoleUiFeedback feedback = find(current);
            if (feedback != null) {
                feedback.notify(Kind.INFO, text, duration > 0);
            } else if (current != null) {
                android.widget.Toast.makeText(current, text, duration).show();
            }
        }
    }

    private static final Map<Activity, ConsoleUiFeedback> INSTANCES = new WeakHashMap<>();
    private static final long SHORT_DURATION_MS = 2_800L;
    private static final long LONG_DURATION_MS = 4_800L;
    private static final int MAX_VISIBLE_NOTIFICATIONS = 3;

    private final Activity activity;
    private final FrameLayout root;
    private final LinearLayout notificationStack;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<View> notifications = new ArrayDeque<>();
    private final ConsoleAudioEngine audio;
    private boolean reducedMotion;
    private Dialog modal;

    ConsoleUiFeedback(Activity activity, FrameLayout root, ConsoleAudioEngine audio,
                      boolean reducedMotion) {
        this.activity = activity;
        this.root = root;
        this.audio = audio;
        this.reducedMotion = reducedMotion;
        notificationStack = new LinearLayout(activity);
        notificationStack.setOrientation(LinearLayout.VERTICAL);
        notificationStack.setGravity(Gravity.END);
        notificationStack.setClipChildren(false);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(390), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.setMargins(dp(24), dp(24), dp(30), 0);
        root.addView(notificationStack, params);
        synchronized (INSTANCES) {
            INSTANCES.put(activity, this);
        }
    }

    static Message makeText(Context context, int textResource, int duration) {
        return new Message(context, context.getText(textResource), duration);
    }

    static Message makeText(Context context, CharSequence text, int duration) {
        return new Message(context, text, duration);
    }

    void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
    }

    void notify(Kind kind, CharSequence message) {
        notify(kind, message, kind == Kind.ERROR || kind == Kind.WARNING);
    }

    void notify(Kind kind, CharSequence message, boolean longDuration) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> notify(kind, message, longDuration));
            return;
        }
        if (message == null || message.length() == 0 || activity.isFinishing()) return;
        if (audio != null) {
            if (kind == Kind.SUCCESS) audio.play(ConsoleAudioSynthesis.Cue.SUCCESS);
            else if (kind == Kind.ERROR) audio.play(ConsoleAudioSynthesis.Cue.ERROR);
        }
        View card = notificationCard(kind, message);
        while (notifications.size() >= MAX_VISIBLE_NOTIFICATIONS) {
            removeNotification(notifications.removeFirst(), true);
        }
        notifications.addLast(card);
        notificationStack.addView(card, notificationParams());
        card.setAlpha(reducedMotion ? 1f : 0f);
        card.setTranslationX(reducedMotion ? 0f : dp(70));
        if (!reducedMotion) {
            card.animate().alpha(1f).translationX(0f).setDuration(180L).start();
        }
        card.announceForAccessibility(message);
        handler.postDelayed(() -> removeNotification(card, false),
                longDuration ? LONG_DURATION_MS : SHORT_DURATION_MS);
    }

    void showMessage(CharSequence title, CharSequence message, CharSequence closeLabel,
                     Runnable onClose) {
        showModal(title, message, null,
                new ModalAction(closeLabel, false, value -> {
                    if (onClose != null) onClose.run();
                    return null;
                }));
    }

    void showConfirm(CharSequence title, CharSequence message,
                     CharSequence cancelLabel, CharSequence confirmLabel,
                     boolean destructive, Runnable onConfirm) {
        showModal(title, message, null,
                new ModalAction(cancelLabel, false, value -> null),
                new ModalAction(confirmLabel, destructive, value -> {
                    if (onConfirm != null) onConfirm.run();
                    return null;
                }));
    }

    void showInput(CharSequence title, CharSequence message, String initialValue,
                   CharSequence hint, int inputType, int maxLength,
                   CharSequence cancelLabel, CharSequence secondaryLabel,
                   InputAction secondaryAction, CharSequence confirmLabel,
                   InputAction confirmAction, Runnable onDismiss) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(initialValue == null ? "" : initialValue);
        input.setHint(hint);
        input.setHintTextColor(0xFF81909B);
        input.setTextColor(Color.WHITE);
        input.setTextSize(17);
        input.setInputType(inputType);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setSelectAllOnFocus(initialValue != null && !initialValue.isEmpty());
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setBackground(inputBackground(false));
        input.setSoundEffectsEnabled(false);
        if (maxLength > 0) {
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        }
        input.setOnFocusChangeListener((view, focused) ->
                input.setBackground(inputBackground(focused)));

        ArrayDeque<ModalAction> actions = new ArrayDeque<>();
        actions.add(new ModalAction(cancelLabel, false, value -> null));
        if (secondaryLabel != null && secondaryAction != null) {
            actions.add(new ModalAction(secondaryLabel, false, secondaryAction));
        }
        actions.add(new ModalAction(confirmLabel, false, confirmAction));
        showModal(title, message, input, actions.toArray(new ModalAction[0]));
        Dialog shown = modal;
        if (shown != null) {
            shown.setOnDismissListener(ignored -> {
                modal = null;
                if (onDismiss != null) onDismiss.run();
            });
            input.post(() -> {
                input.requestFocus();
                input.setSelection(input.length());
                Window window = shown.getWindow();
                if (window != null) window.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(
                        Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            });
            input.setOnEditorActionListener((view, actionId, event) -> {
                boolean submit = actionId == EditorInfo.IME_ACTION_DONE
                        || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                if (!submit) return false;
                View primary = findDialogView(shown, "console.modal.primary");
                if (primary != null) primary.performClick();
                return true;
            });
            input.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_BACK
                        || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                    moveInputFocusToActions(shown, input);
                    return true;
                }
                return false;
            });
        }
    }

    boolean isModalShowing() {
        return modal != null && modal.isShowing();
    }

    void dismissModal() {
        if (modal != null) modal.dismiss();
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        dismissModal();
        notifications.clear();
        notificationStack.removeAllViews();
        if (notificationStack.getParent() == root) root.removeView(notificationStack);
        synchronized (INSTANCES) {
            INSTANCES.remove(activity);
        }
    }

    private void showModal(CharSequence title, CharSequence message, EditText input,
                           ModalAction... actions) {
        dismissModal();
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(modalCard(title, message, input, actions, dialog));
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                if (dialog.getCurrentFocus() instanceof EditText) {
                    moveInputFocusToActions(dialog, (EditText) dialog.getCurrentFocus());
                    return true;
                }
                if (audio != null) audio.playSystemBack();
                dialog.dismiss();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (audio != null) audio.playSystemConfirm();
            }
            return false;
        });
        dialog.setOnDismissListener(ignored -> modal = null);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = dp(610);
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.dimAmount = 0.72f;
            attributes.gravity = input == null ? Gravity.CENTER
                    : Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            attributes.y = input == null ? 0 : dp(38);
            window.setAttributes(attributes);
            window.setSoftInputMode(input == null
                    ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        modal = dialog;
        dialog.show();
        if (window != null) {
            window.setLayout(dp(610), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        View content = dialog.findViewById(android.R.id.content);
        if (content != null && !reducedMotion) {
            content.setAlpha(0f);
            content.setScaleX(0.96f);
            content.setScaleY(0.96f);
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start();
        }
    }

    private View modalCard(CharSequence title, CharSequence message, EditText input,
                           ModalAction[] actions, Dialog dialog) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(30), dp(26), dp(30), dp(24));
        GradientDrawable background = gradient(0xFF1B252D, 0xFF0B1015, 22);
        background.setStroke(dp(1), 0x99768B99);
        card.setBackground(background);

        TextView titleView = label(title, 25, Color.WHITE, true);
        card.addView(titleView, linearMatchWrap());
        if (message != null && message.length() > 0) {
            TextView messageView = label(message, 15, 0xFFC4CED5, false);
            LinearLayout.LayoutParams messageParams = linearMatchWrap();
            messageParams.topMargin = dp(9);
            card.addView(messageView, messageParams);
        }
        if (input != null) {
            LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
            inputParams.topMargin = dp(22);
            card.addView(input, inputParams);
        }

        LinearLayout actionRow = new LinearLayout(activity);
        boolean verticalActions = actions.length > 2;
        actionRow.setOrientation(verticalActions
                ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        actionRow.setGravity(verticalActions
                ? Gravity.CENTER_HORIZONTAL : Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = linearMatchWrap();
        rowParams.topMargin = dp(24);
        card.addView(actionRow, rowParams);
        for (ModalAction action : actions) {
            if (action.label == null) continue;
            TextView button = modalButton(action.label, action.destructive);
            if (action == actions[actions.length - 1]) {
                button.setTag("console.modal.primary");
            }
            button.setOnClickListener(view -> {
                String value = input == null ? "" : input.getText().toString().trim();
                String error = action.action == null ? null : action.action.run(value);
                if (error != null && input != null) {
                    input.setError(error);
                    input.requestFocus();
                    if (audio != null) audio.play(ConsoleAudioSynthesis.Cue.ERROR);
                    return;
                }
                dialog.dismiss();
            });
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    verticalActions ? ViewGroup.LayoutParams.MATCH_PARENT
                            : ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            if (verticalActions) buttonParams.topMargin = dp(8);
            else buttonParams.leftMargin = dp(10);
            actionRow.addView(button, buttonParams);
        }
        if (actionRow.getChildCount() > 0) {
            View primary = actionRow.getChildAt(actionRow.getChildCount() - 1);
            primary.post(primary::requestFocus);
        }
        return card;
    }

    private TextView modalButton(CharSequence label, boolean destructive) {
        TextView button = label(label, 14, destructive ? 0xFFFFA099 : Color.WHITE, true);
        button.setId(View.generateViewId());
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setClickable(true);
        button.setSoundEffectsEnabled(false);
        button.setMinWidth(dp(112));
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(buttonBackground(false, destructive));
        button.setOnFocusChangeListener((view, focused) -> {
            button.setBackground(buttonBackground(focused, destructive));
            if (focused && audio != null) audio.play(ConsoleAudioSynthesis.Cue.NAVIGATE);
        });
        return button;
    }

    private void moveInputFocusToActions(Dialog dialog, EditText input) {
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        View primary = findDialogView(dialog, "console.modal.primary");
        if (primary != null) primary.requestFocus();
    }

    private View findDialogView(Dialog dialog, Object tag) {
        Window window = dialog == null ? null : dialog.getWindow();
        return window == null ? null : window.getDecorView().findViewWithTag(tag);
    }

    private View notificationCard(Kind kind, CharSequence message) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(15), dp(12), dp(17), dp(12));
        GradientDrawable background = gradient(0xF0222D35, 0xF00D1318, 16);
        background.setStroke(dp(1), kindColor(kind));
        card.setBackground(background);
        card.setElevation(dp(10));

        TextView icon = label(kindGlyph(kind), 16, kindColor(kind), true);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setShape(GradientDrawable.OVAL);
        iconBackground.setColor(withAlpha(kindColor(kind), 0x26));
        icon.setBackground(iconBackground);
        card.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView messageView = label(message, 14, Color.WHITE, false);
        messageView.setMaxLines(3);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        messageParams.leftMargin = dp(13);
        card.addView(messageView, messageParams);
        return card;
    }

    private LinearLayout.LayoutParams notificationParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(9);
        return params;
    }

    private void removeNotification(View card, boolean immediate) {
        if (card == null || card.getParent() != notificationStack) return;
        notifications.remove(card);
        handler.removeCallbacksAndMessages(card);
        if (immediate || reducedMotion) {
            notificationStack.removeView(card);
            return;
        }
        card.animate().alpha(0f).translationX(dp(55)).setDuration(150L)
                .withEndAction(() -> notificationStack.removeView(card)).start();
    }

    private TextView label(CharSequence value, float size, int color, boolean bold) {
        TextView label = new TextView(activity);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        return label;
    }

    private GradientDrawable buttonBackground(boolean focused, boolean destructive) {
        GradientDrawable background = gradient(
                focused ? 0xFF32444F : 0xC0232C33,
                focused ? 0xFF172129 : 0xD0141A1F, 12);
        background.setStroke(dp(focused ? 2 : 1), focused
                ? (destructive ? 0xFFFF8F88 : 0xFFDDF5FF) : 0x55788A96);
        return background;
    }

    private GradientDrawable inputBackground(boolean focused) {
        GradientDrawable background = gradient(0xFF111820, 0xFF0A0F14, 10);
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFFBDEBFF : 0x66788A96);
        return background;
    }

    private static GradientDrawable gradient(int top, int bottom, float radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int kindColor(Kind kind) {
        switch (kind) {
            case SUCCESS: return 0xFF75E5A4;
            case WARNING: return 0xFFFFCF72;
            case ERROR: return 0xFFFF8F88;
            case PROGRESS: return 0xFF8DDCFF;
            case INFO:
            default: return 0xFFBDEBFF;
        }
    }

    private static CharSequence kindGlyph(Kind kind) {
        switch (kind) {
            case SUCCESS: return "✓";
            case WARNING: return "!";
            case ERROR: return "×";
            case PROGRESS: return "…";
            case INFO:
            default: return "i";
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private LinearLayout.LayoutParams linearMatchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static ConsoleUiFeedback find(Context context) {
        Activity activity = unwrapActivity(context);
        if (activity == null) return null;
        synchronized (INSTANCES) {
            return INSTANCES.get(activity);
        }
    }

    private static Activity unwrapActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) break;
            current = next;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private static final class ModalAction {
        final CharSequence label;
        final boolean destructive;
        final InputAction action;

        ModalAction(CharSequence label, boolean destructive, InputAction action) {
            this.label = label;
            this.destructive = destructive;
            this.action = action;
        }
    }
}
