package com.limelight.console;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

/**
 * Library search (UX_MENU_PL.md 3.5): the MoonWaker on-screen keyboard over the grid with the
 * query above it. Each edit reports the new query so the grid filters live; R2, the Gotowe key
 * or Back finish. While open it takes every key it understands, so focus stays on itself.
 */
final class LibrarySearchKeyboard extends LinearLayout {
    interface Listener {
        void onQueryChanged(String query);

        void onDone();
    }

    private static final String CARET = "│";

    private final Listener listener;
    private final TextView query;
    private final EmbeddedTvKeyboardView keyboard;
    private final String hint;
    private final int hintColor;
    private String text = "";
    private int cursor;

    LibrarySearchKeyboard(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setVisibility(GONE);
        setFocusable(true);
        setFocusableInTouchMode(false);
        int padding = dp(10);
        setPadding(padding, padding, padding, dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(18));
        background.setColor(0xF0101219);
        background.setStroke(dp(1), 0x2EFFFFFF);
        setBackground(background);
        hint = context.getString(R.string.playnite_search_hint);
        hintColor = context.getResources().getColor(R.color.console_text_secondary);

        query = new TextView(context);
        query.setTextSize(15f);
        query.setTextColor(ConsolePanel.TEXT);
        query.setSingleLine(true);
        // The keys are plain views driven by this focused container: it is named for screen
        // readers and every edit of the query is announced.
        setContentDescription(context.getString(R.string.playnite_search));
        query.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        query.setGravity(Gravity.CENTER_VERTICAL);
        query.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_console_search, 0, 0, 0);
        query.setCompoundDrawablePadding(dp(10));
        query.setPadding(dp(6), 0, dp(6), 0);
        addView(query, new LayoutParams(LayoutParams.MATCH_PARENT, dp(34)));

        keyboard = new EmbeddedTvKeyboardView(context, new EmbeddedTvKeyboardView.Callback() {
            @Override public void onText(String value) {
                edit(EmbeddedTvKeyboardModel.insert(text, cursor, cursor, value));
            }

            @Override public void onBackspace() {
                edit(EmbeddedTvKeyboardModel.backspace(text, cursor, cursor));
            }

            @Override public void onMoveCursor(int direction) {
                cursor = EmbeddedTvKeyboardModel.moveCursorByCodePoints(text, cursor, direction);
                showQuery();
            }

            @Override public void onMicrophone() { }

            @Override public void onSend() {
                listener.onDone();
            }

            @Override public void onOpenVisibleAdditionalContent() { }
        }, "", context.getString(R.string.discord_dm_keyboard_space),
                context.getString(R.string.playnite_search_done),
                context.getString(R.string.discord_dm_keyboard_shift_legend),
                context.getString(R.string.discord_dm_keyboard_backspace_legend),
                context.getString(R.string.discord_dm_keyboard_cursor_legend),
                context.getString(R.string.playnite_search_done_legend), "", "", false);
        addView(keyboard, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    void open(String current) {
        text = current;
        cursor = current.length();
        keyboard.reset();
        showQuery();
        setVisibility(VISIBLE);
        requestFocus();
    }

    void close() {
        keyboard.cancelKeyboardHold();
        setVisibility(GONE);
    }

    /** Back finishes (unless it cancels an accent choice); other keys go to the keyboard. */
    boolean handleKey(KeyEvent event) {
        if (keyboard.handleAccentCancel(event)) return true;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) listener.onDone();
            return true;
        }
        return keyboard.handleNavigationKey(event);
    }

    private void edit(EmbeddedTvKeyboardModel.Edit edit) {
        cursor = edit.selection;
        if (!edit.text.equals(text)) {
            text = edit.text;
            listener.onQueryChanged(text);
        }
        showQuery();
    }

    private void showQuery() {
        if (text.isEmpty()) {
            SpannableString empty = new SpannableString(CARET + hint);
            empty.setSpan(new ForegroundColorSpan(hintColor), 1, empty.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            query.setText(empty);
        } else {
            query.setText(text.substring(0, cursor) + CARET + text.substring(cursor));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
