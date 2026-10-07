package com.limelight.console;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.ui.ControllerGlyphs;

import java.util.ArrayList;
import java.util.List;

/**
 * Community screen legend (mockup "discord_legend"): key chips with a label, at most four in the
 * row. A longer list keeps its first three and ends with "☰ Więcej", which opens the whole list
 * on a glass card; the buttons themselves do the same either way.
 */
final class DiscordCommunityLegend {
    static final int MAX_ITEMS = 4;

    static final class Item {
        final String key;
        /** The key is a controller glyph drawn in the glyph font, not a text chip. */
        final boolean glyph;
        final String label;

        Item(String key, boolean glyph, String label) {
            this.key = key;
            this.glyph = glyph;
            this.label = label;
        }
    }

    private DiscordCommunityLegend() { }

    /**
     * "Więcej" in a chat is ☰ (Menu/Start), or Select while the DM shortcut holds Start: the
     * shortcut handler sees keys first and would swallow ☰. Select has no chat action.
     */
    static boolean moreOnSelect(String dmShortcutTrigger) {
        return "start".equals(dmShortcutTrigger);
    }

    static boolean isMoreKey(String dmShortcutTrigger, int keyCode) {
        if (moreOnSelect(dmShortcutTrigger)) return keyCode == KeyEvent.KEYCODE_BUTTON_SELECT;
        return keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_START;
    }

    /** The row for {@code all}: everything when it fits, else the first three and {@code more}. */
    static List<Item> visible(List<Item> all, Item more) {
        if (all.size() <= MAX_ITEMS) return all;
        List<Item> row = new ArrayList<>(all.subList(0, MAX_ITEMS - 1));
        row.add(more);
        return row;
    }

    /** Fills {@code row} (horizontal) with chips and labels: 40 px gaps between items. */
    static void fillRow(LinearLayout row, List<Item> items) {
        row.removeAllViews();
        for (Item item : items) addItem(row, item, row.getChildCount() == 0 ? 0 : 20);
    }

    /** Fills {@code column} (vertical) with one chip and label per line. */
    static void fillColumn(LinearLayout column, List<Item> items) {
        column.removeAllViews();
        for (Item item : items) {
            LinearLayout line = new LinearLayout(column.getContext());
            line.setGravity(Gravity.CENTER_VERTICAL);
            addItem(line, item, 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(column.getContext(), 26));
            column.addView(line, params);
        }
    }

    /** Mockup "key_chip": 40 px glass capsule, 22 px semibold key; label 24 px, 12 px after it. */
    private static void addItem(LinearLayout row, Item item, int leftMarginDp) {
        Context context = row.getContext();
        TextView key = new TextView(context);
        key.setText(item.key);
        key.setTextColor(ConsolePanel.TEXT);
        key.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams keyParams;
        if (item.glyph) {
            key.setTextSize(20);
            key.setTypeface(ControllerGlyphs.typeface(context));
            ConsoleText.centreOnInk(key, item.key);
            keyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            key.setTextSize(11);
            key.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            // Roboto's digits are tabular; the mockup's "L1/R1" chip uses proportional ones.
            key.setFontFeatureSettings("pnum");
            GradientDrawable chip = new GradientDrawable();
            chip.setCornerRadius(dp(context, 10));
            chip.setColor(0x1EFFFFFF);
            chip.setStroke(Math.max(1, Math.round(.75f * density(context))), 0x50FFFFFF);
            key.setBackground(chip);
            key.setMinWidth(dp(context, 20));
            key.setPadding(Math.round(5.5f * density(context)), 0,
                    Math.round(5.5f * density(context)), 0);
            ConsoleText.centreOnGlyphs(key);
            keyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(context, 20));
        }
        keyParams.leftMargin = dp(context, leftMarginDp);
        row.addView(key, keyParams);
        TextView label = new TextView(context);
        label.setText(item.label);
        label.setTextSize(12);
        label.setTextColor(DiscordCommunityStyle.TEXT_SECONDARY);
        label.setIncludeFontPadding(false);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.leftMargin = dp(context, 6);
        labelParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(label, labelParams);
    }

    private static float density(Context context) {
        return context.getResources().getDisplayMetrics().density;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * density(context));
    }
}
