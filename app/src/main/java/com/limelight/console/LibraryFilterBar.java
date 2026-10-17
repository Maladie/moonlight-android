package com.limelight.console;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

/**
 * The row above the full-library grid (UX_MENU_PL.md 3.5): a "Filtry" button with the count of
 * active filters, chips for the active filters only, and "Wyszukaj" at the right end.
 */
final class LibraryFilterBar extends LinearLayout {
    private static final float TEXT_SP = 14f;
    private static final int PILL_HEIGHT_DP = 32;
    private static final int REST_FILL = 0x8C14161E;
    private static final int REST_STROKE = 0x2EFFFFFF;

    private final LinearLayout filters;
    private final TextView badge;
    private final TextView[] chips = new TextView[3];
    private final TextView search;
    private final int secondary;

    LibraryFilterBar(Context context, Runnable openFilters, Runnable openSearch,
                     Runnable onFocused) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        secondary = context.getResources().getColor(R.color.console_text_secondary);
        ColorStateList ink = new ColorStateList(new int[][]{
                {android.R.attr.state_focused}, {}},
                new int[]{ConsolePanel.INK, ConsolePanel.TEXT});

        filters = new LinearLayout(context);
        filters.setOrientation(HORIZONTAL);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = label(context.getString(R.string.playnite_legend_filters), ink);
        label.setDuplicateParentStateEnabled(true);
        icon(label, R.drawable.ic_console_filter, ink);
        ConsoleText.centreOnGlyphs(label);
        filters.addView(label, wrap());
        badge = new TextView(context);
        badge.setTextSize(12f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(ConsolePanel.INK);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        GradientDrawable badgeFill = new GradientDrawable();
        badgeFill.setCornerRadius(dp(10));
        badgeFill.setColor(context.getResources().getColor(R.color.console_accent));
        badge.setBackground(badgeFill);
        badge.setMinWidth(dp(20));
        badge.setPadding(dp(5), 0, dp(5), 0);
        ConsoleText.centreOnGlyphs(badge);
        LayoutParams badgeParams = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(20));
        badgeParams.leftMargin = dp(8);
        filters.addView(badge, badgeParams);
        focusable(filters, openFilters, onFocused);
        filters.setPadding(dp(14), 0, dp(12), 0);
        addView(filters, pill());

        // The chips share what Filtry and Wyszukaj leave; a long one ellipsizes, never the ends.
        LinearLayout chipRow = new LinearLayout(context);
        chipRow.setOrientation(HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int index = 0; index < chips.length; index++) {
            TextView chip = label("", null);
            chip.setTextColor(ConsolePanel.TEXT);
            // The row is only as wide as the covers: chips use the smallest allowed type.
            chip.setTextSize(12f);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setBackground(fill(false));
            chip.setPadding(dp(11), 0, dp(11), 0);
            ConsoleText.centreOnGlyphs(chip);
            chip.setVisibility(GONE);
            chips[index] = chip;
            chipRow.addView(chip, pill());
        }
        addView(chipRow, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        search = label(context.getString(R.string.playnite_search), ink);
        search.setSingleLine(true);
        search.setEllipsize(TextUtils.TruncateAt.END);
        search.setMaxWidth(dp(220));
        icon(search, R.drawable.ic_console_search, ink);
        focusable(search, openSearch, onFocused);
        search.setPadding(dp(14), 0, dp(16), 0);
        ConsoleText.centreOnGlyphs(search);
        LayoutParams searchParams = pill();
        searchParams.rightMargin = 0;
        addView(search, searchParams);

        // Up and the outer sides stay in the row; down enters the grid at its first visible card.
        filters.setNextFocusUpId(filters.getId());
        filters.setNextFocusLeftId(filters.getId());
        filters.setNextFocusRightId(search.getId());
        search.setNextFocusUpId(search.getId());
        search.setNextFocusLeftId(filters.getId());
        search.setNextFocusRightId(search.getId());
    }

    /** Shows the active filters and the search query; called after every library render. */
    void show(LibraryFilterSummary summary, String query) {
        int count = summary.activeCount();
        badge.setText(String.valueOf(count));
        badge.setVisibility(count > 0 ? VISIBLE : GONE);
        filters.setContentDescription(getContext().getString(
                R.string.playnite_filters_description, count));
        int chip = 0;
        if (summary.stateActive()) {
            showChip(chip++, R.string.playnite_filter_state,
                    getContext().getString(summary.filter.label));
        }
        if (!summary.platforms.isEmpty()) {
            showChip(chip++, R.string.playnite_filter_platforms,
                    TextUtils.join(", ", summary.platforms));
        }
        if (summary.sortActive()) {
            showChip(chip++, R.string.playnite_filter_sort,
                    getContext().getString(summary.sort.label));
        }
        for (; chip < chips.length; chip++) chips[chip].setVisibility(GONE);
        search.setText(query.isEmpty() ? getContext().getString(R.string.playnite_search)
                : getContext().getString(R.string.playnite_search_active, query));
    }

    void focusFilters() {
        filters.requestFocus();
    }

    private void showChip(int index, int type, String value) {
        String label = getContext().getString(type) + ": ";
        SpannableString text = new SpannableString(label + value);
        text.setSpan(new ForegroundColorSpan(secondary), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        chips[index].setText(text);
        chips[index].setVisibility(VISIBLE);
    }

    private void focusable(View view, Runnable onClick, Runnable onFocused) {
        view.setId(View.generateViewId());
        view.setFocusable(true);
        view.setClickable(true);
        view.setSoundEffectsEnabled(false);
        view.setBackground(fill(true));
        view.setOnClickListener(ignored -> onClick.run());
        view.setOnFocusChangeListener((ignored, focused) -> {
            if (focused) onFocused.run();
        });
    }

    private TextView label(String value, ColorStateList colors) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TEXT_SP);
        if (colors != null) view.setTextColor(colors);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        return view;
    }

    private void icon(TextView view, int icon, ColorStateList tint) {
        Drawable drawable = getContext().getDrawable(icon).mutate();
        drawable.setTintList(tint);
        int size = dp(16);
        drawable.setBounds(0, 0, size, size);
        view.setCompoundDrawablesRelative(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(8));
    }

    /** Section 4.2 text controls: a quiet pill at rest, the near-white fill with dark ink focused. */
    private Drawable fill(boolean focusable) {
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(dp(PILL_HEIGHT_DP / 2));
        rest.setColor(REST_FILL);
        rest.setStroke(dp(1), REST_STROKE);
        if (!focusable) return rest;
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(PILL_HEIGHT_DP / 2));
        focused.setColor(ConsolePanel.FOCUS_FILL);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[0], rest);
        return states;
    }

    private LayoutParams pill() {
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(PILL_HEIGHT_DP));
        params.rightMargin = dp(8);
        return params;
    }

    private LayoutParams wrap() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
