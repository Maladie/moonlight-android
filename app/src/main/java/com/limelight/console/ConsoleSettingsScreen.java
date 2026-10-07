package com.limelight.console;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Full-screen settings (docs/MoonWaker/UX_MENU_PL.md section 3.2): categories on the left,
 * the selected category's {@link ConsolePanel} rows on the right. The caller owns what the rows
 * do; this class owns layout, category selection, nested screens (Settings › Streaming ›
 * Stream), sub-pages, the legend and D-pad/Back movement.
 */
final class ConsoleSettingsScreen {
    static final int CATEGORY_WIDTH_DP = 240;
    /** Mockup 18: a 4 x 36 px accent bar, text 26 px after its left edge. */
    static final int SELECTION_BAR_DP = 2;
    static final int SELECTION_GAP_DP = 11;
    /** Mockup 18: category rows 20 px radius (card rows: ConsolePanel's card, 32 px). */
    static final int CATEGORY_RADIUS_DP = 10;
    private static final String LEGEND_SEPARATOR = "  ·  ";

    static final class Category {
        final String title;
        /** Mutable for a caller whose subtitle follows its rows; {@link #refresh} shows it. */
        CharSequence details;
        final Function<ConsolePanel, View[]> rows;

        Category(String title, Function<ConsolePanel, View[]> rows) {
            this(title, "", rows);
        }

        Category(String title, CharSequence details, Function<ConsolePanel, View[]> rows) {
            this.title = title;
            this.details = details;
            this.rows = rows;
        }
    }

    /** One screen of categories; nested screens keep their parents for Back. */
    private static final class Level {
        final String crumb;
        final String title;
        final String eyebrow;
        final List<Category> categories;
        int selected;
        int focusedRow = -1;

        Level(String crumb, String title, String eyebrow, List<Category> categories) {
            this.crumb = crumb;
            this.title = title;
            this.eyebrow = eyebrow;
            this.categories = categories;
        }
    }

    private final Activity activity;
    private final Dialog dialog;
    private final FrameLayout root;
    private final ConsoleMenuBackdrop backdrop;
    private final LinearLayout columns;
    private final LinearLayout categoryColumn;
    private final TextView crumb;
    private final TextView title;
    private final LockableScrollView contentScroll;
    private final LinearLayout content;
    private final ConsolePanel panel;
    private final int accent;
    /** Opaque base tone under the blurred Home until its copy is ready. */
    private final int background;
    private final List<TextView> categoryRows = new ArrayList<>();
    private final Deque<Level> parents = new ArrayDeque<>();
    private Level level = new Level("", "", "", new ArrayList<>());
    private int selected = -1;
    /** Content child that opened the visible sub-page, or -1 when the category is shown. */
    private int pageOrigin = -1;
    /** Counts opened sub-pages, so a choice can tell whether it opened another one. */
    private int pages;
    private boolean reducedMotion;

    ConsoleSettingsScreen(Activity activity, boolean reducedMotion, boolean overStream) {
        this.activity = activity;
        this.reducedMotion = reducedMotion;
        accent = activity.getResources().getColor(R.color.console_accent);
        background = 0xFF000000 | activity.getResources().getColor(R.color.console_background);
        root = new FrameLayout(activity);
        root.setBackgroundColor(background);
        backdrop = new ConsoleMenuBackdrop(activity, overStream);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        columns = new LinearLayout(activity);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        // Section 4.1 safe area: 96 px at the sides, 54 px at the top and bottom.
        columns.setPadding(dp(48), dp(27), dp(48), dp(27));
        root.addView(columns, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        categoryColumn = new LinearLayout(activity);
        categoryColumn.setOrientation(LinearLayout.VERTICAL);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        // Mockup 18 breadcrumb: 24 px tracked caps at baseline 84, above the 44 px title.
        crumb = new TextView(activity);
        crumb.setTextSize(ConsolePanel.CAPTION_SP);
        crumb.setTextColor(ConsolePanel.HEADER);
        crumb.setTypeface(medium);
        crumb.setLetterSpacing(.08f);
        crumb.setIncludeFontPadding(false);
        crumb.setPadding(dp(ConsolePanel.ROW_INSET_DP), 0, 0, dp(9));
        categoryColumn.addView(crumb);
        title = new TextView(activity);
        title.setTextSize(ConsolePanel.TITLE_SP);
        title.setTextColor(ConsolePanel.TEXT);
        title.setTypeface(medium);
        title.setIncludeFontPadding(false);
        // Mockup 18: first category row 46 px below the title's baseline.
        title.setPadding(dp(ConsolePanel.ROW_INSET_DP), 0, 0, dp(17));
        categoryColumn.addView(title);
        columns.addView(categoryColumn, new LinearLayout.LayoutParams(
                dp(CATEGORY_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT));

        contentScroll = new LockableScrollView(activity);
        contentScroll.setVerticalScrollBarEnabled(false);
        contentScroll.setFillViewport(true);
        contentScroll.setVerticalFadingEdgeEnabled(true);
        contentScroll.setFadingEdgeLength(dp(32));
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        columns.addView(contentScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        panel = new ConsolePanel(activity, contentScroll, backdrop, reducedMotion, true);
        panel.floatOnGlass(content);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener(
                (oldFocus, newFocus) -> updateLegend());

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(owner -> {
            backdrop.release();
            ConsoleUiFeedback.showNotificationsIn(activity, null);
        });
        dialog.setOnKeyListener((owner, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) back();
            return true;
        });
        makeOpaque();
    }

    /**
     * The dialog theme may bring a translucent window background, a dim or a window alpha; all
     * three are overridden so nothing of the Activity window below can show through.
     */
    private void makeOpaque() {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(background));
        window.setFormat(PixelFormat.OPAQUE);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.alpha = 1f;
        attributes.dimAmount = 0f;
        attributes.width = ViewGroup.LayoutParams.MATCH_PARENT;
        attributes.height = ViewGroup.LayoutParams.MATCH_PARENT;
        window.setAttributes(attributes);
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    /** The settings window, for menus that open over it and blur it. */
    Window window() {
        return dialog.getWindow();
    }

    void dismiss() {
        dialog.dismiss();
    }

    void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
        panel.setReducedMotion(reducedMotion);
    }

    /** Opens Settings with {@code category} selected and focused. */
    void show(List<Category> categories, int category) {
        String settings = activity.getString(R.string.console_settings_title);
        show("", settings, settings, categories, category);
    }

    /** Opens a top-level screen (e.g. a game's overrides); Back from it closes the window. */
    void show(String crumb, String title, String eyebrow, List<Category> categories,
              int category) {
        parents.clear();
        enter(new Level(crumb, title, eyebrow, categories), category, -1);
    }

    /** Opens a nested screen; Back from its categories returns to the focused row here. */
    void push(String crumb, String title, String eyebrow, List<Category> categories,
              int category) {
        level.focusedRow = focusedContentChild();
        parents.push(level);
        pageOrigin = -1;
        enter(new Level(crumb, title, eyebrow, categories), category, -1);
    }

    private void enter(Level next, int category, int focusRow) {
        level = next;
        crumb.setText(next.crumb.toUpperCase(Locale.ROOT));
        crumb.setVisibility(next.crumb.isEmpty() ? View.GONE : View.VISIBLE);
        // The crumb sits where the title's top padding was (baseline 84 px, title 146 px).
        categoryColumn.setPadding(0, dp(next.crumb.isEmpty() ? 25 : 3), dp(24), 0);
        title.setText(next.title);
        for (TextView row : categoryRows) categoryColumn.removeView(row);
        categoryRows.clear();
        for (int index = 0; index < next.categories.size(); index++) {
            int position = index;
            TextView row = panel.action(next.categories.get(index).title);
            // A background replaces the padding with its own (the fill's 1.5 dp insets), which
            // put the accent bar on the column edge, 28 px left of mockup 18's x 124.
            int left = row.getPaddingLeft(), top = row.getPaddingTop();
            int right = row.getPaddingRight(), bottom = row.getPaddingBottom();
            row.setBackground(panel.focusFill(CATEGORY_RADIUS_DP));
            row.setPadding(left, top, right, bottom);
            View.OnFocusChangeListener styled = row.getOnFocusChangeListener();
            row.setOnFocusChangeListener((view, focused) -> {
                styled.onFocusChange(view, focused);
                if (focused) select(position);
            });
            row.setOnClickListener(view -> focusContent());
            categoryRows.add(row);
            categoryColumn.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        chainVertically(new ArrayList<>(categoryRows));
        selected = -1;
        pageOrigin = -1;
        select(Math.max(0, Math.min(category, next.categories.size() - 1)));
        if (!dialog.isShowing()) {
            backdrop.capture(activity.getWindow(), reducedMotion);
            dialog.show();
            makeOpaque();
            // The opaque window would hide notifications drawn in the Activity below.
            ConsoleUiFeedback.showNotificationsIn(activity, root);
            // Only the lists fade in; the background is opaque from the first frame and the
            // blurred Home fades in over it once its copy is ready.
            columns.setAlpha(reducedMotion ? 1f : 0f);
            if (!reducedMotion) columns.animate().alpha(1f).setDuration(150L).start();
        }
        View target = null;
        if (focusRow >= 0 && focusRow < content.getChildCount()) target = content.getChildAt(focusRow);
        // A single category (a game's overrides) has nothing to pick on the left.
        else if (next.categories.size() == 1 && !contentRows().isEmpty()) target = contentRows().get(0);
        else if (!categoryRows.isEmpty()) target = categoryRows.get(selected);
        if (target != null) target.post(target::requestFocus);
        updateLegend();
    }

    /** Re-renders the shown category (e.g. after a dependency changed) keeping the focus. */
    void refresh() {
        if (pageOrigin >= 0 || selected < 0) return;
        int focused = focusedContentChild();
        int scrollY = contentScroll.getScrollY();
        renderCategory();
        contentScroll.scrollTo(0, scrollY);
        if (focused >= 0) focusContentChild(focused);
    }

    /**
     * Replaces the content column with one choice per label; choosing applies {@code onChosen}
     * and returns to the category with the opening row focused. Back returns without a change.
     */
    void showChoices(String title, String details, CharSequence[] labels, int current,
                     IntConsumer onChosen) {
        View[] rows = new View[labels.length];
        for (int index = 0; index < labels.length; index++) {
            int position = index;
            TextView row = choiceRow(labels[index], index == current);
            row.setOnClickListener(view -> {
                int shown = pages;
                onChosen.accept(position);
                // A choice may open a further page (e.g. "Custom…"); otherwise go back.
                if (shown == pages) closePage();
            });
            rows[index] = row;
        }
        showPage(title, details, rows, Math.max(0, current));
    }

    /** The row factory of the content column, for sub-pages built by callers. */
    ConsolePanel panel() {
        return panel;
    }

    /** A choice-page row: the accent bar and accent text when {@code chosen}. */
    TextView choiceRow(CharSequence label, boolean chosen) {
        TextView row = panel.action(label);
        mark(row, chosen);
        return row;
    }

    /** A sub-page of the current category (choices, a numeric entry); Back returns. */
    void showPage(String title, CharSequence details, View[] rows, int focus) {
        pages++;
        if (pageOrigin < 0) pageOrigin = Math.max(0, focusedContentChild());
        panel.render(content, level.categories.get(selected).title, title, details, false, rows);
        contentScroll.scrollTo(0, 0);
        wireContent();
        View first = rows.length == 0 ? null : rows[Math.min(focus, rows.length - 1)];
        if (first != null) first.post(first::requestFocus);
        updateLegend();
    }

    /** Leaves a sub-page for its category, focusing the row that opened it. */
    void closePage() {
        int origin = pageOrigin;
        pageOrigin = -1;
        renderCategory();
        focusContentChild(origin);
    }

    void select(int index) {
        if (index == selected || index < 0 || index >= level.categories.size()) return;
        selected = index;
        level.selected = index;
        pageOrigin = -1;
        for (int row = 0; row < categoryRows.size(); row++) {
            mark(categoryRows.get(row), row == index);
        }
        renderCategory();
    }

    private void renderCategory() {
        Category category = level.categories.get(selected);
        View[] rows = category.rows.apply(panel); // may update the details
        panel.render(content, level.eyebrow, category.title, category.details, false, rows);
        contentScroll.scrollTo(0, 0);
        wireContent();
        updateLegend();
    }

    private void back() {
        if (pageOrigin >= 0) {
            closePage();
        } else if (focusedContentChild() >= 0 && level.categories.size() > 1) {
            categoryRows.get(selected).requestFocus();
        } else if (!parents.isEmpty()) {
            Level parent = parents.pop();
            enter(parent, parent.selected, parent.focusedRow);
        } else {
            columns.animate().cancel();
            columns.setAlpha(1f);
            dialog.dismiss();
        }
    }

    private void focusContent() {
        List<View> rows = contentRows();
        if (!rows.isEmpty()) rows.get(0).requestFocus();
    }

    private void focusContentChild(int index) {
        List<View> rows = contentRows();
        if (rows.isEmpty()) return;
        View target = rows.get(rows.size() - 1);
        for (View row : rows) {
            if (content.indexOfChild(row) >= index) {
                target = row;
                break;
            }
        }
        View focus = target;
        focus.post(focus::requestFocus);
    }

    /** Left from any content row returns to the selected category; right from it enters. */
    private void wireContent() {
        List<View> rows = contentRows();
        chainVertically(rows);
        int categoryId = categoryRows.get(selected).getId();
        for (View row : rows) row.setNextFocusLeftId(categoryId);
        for (TextView category : categoryRows) {
            category.setNextFocusRightId(rows.isEmpty() ? category.getId() : rows.get(0).getId());
        }
    }

    private List<View> contentRows() {
        List<View> rows = new ArrayList<>();
        for (int index = 0; index < content.getChildCount(); index++) {
            View child = content.getChildAt(index);
            if (child.getVisibility() == View.VISIBLE && child.isFocusable()
                    && child.isEnabled()) rows.add(child);
        }
        return rows;
    }

    /** Index of the content child holding the focus, or -1 outside the content column. */
    private int focusedContentChild() {
        View focused = dialog.getCurrentFocus();
        while (focused != null) {
            ViewParent parent = focused.getParent();
            if (parent == content) return content.indexOfChild(focused);
            focused = parent instanceof View ? (View) parent : null;
        }
        return -1;
    }

    /** Mockup 18 legend: what OK and the arrows do on the focused row, and where Back goes. */
    private void updateLegend() {
        if (selected < 0) return;
        List<String> parts = new ArrayList<>();
        int child = focusedContentChild();
        String back;
        if (pageOrigin >= 0) {
            back = activity.getString(R.string.console_legend_back_unchanged);
        } else if (child >= 0 && level.categories.size() > 1) {
            back = activity.getString(R.string.console_legend_back_categories);
        } else if (!parents.isEmpty()) {
            Level parent = parents.peek();
            back = activity.getString(R.string.console_legend_back_to,
                    parent.categories.get(parent.selected).title);
        } else {
            back = activity.getString(R.string.console_legend_back_close);
        }
        View row = child >= 0 ? content.getChildAt(child) : null;
        if (row != null && ConsolePanel.isStepper(row)) {
            parts.add(activity.getString(R.string.console_legend_step, ConsolePanel.stepLabel(row)));
            parts.add(activity.getString(R.string.console_legend_step_faster));
        } else if (row != null && ConsolePanel.isToggle(row)) {
            parts.add(activity.getString(R.string.console_legend_ok_toggle));
        } else {
            parts.add(activity.getString(R.string.console_legend_ok_select));
            if (row != null && hasStepper()) parts.add(activity.getString(R.string.console_legend_change));
        }
        parts.add(back);
        panel.setLegend(TextUtils.join(LEGEND_SEPARATOR, parts));
    }

    private boolean hasStepper() {
        for (int index = 0; index < content.getChildCount(); index++) {
            if (ConsolePanel.isStepper(content.getChildAt(index))) return true;
        }
        return false;
    }

    private static void chainVertically(List<View> rows) {
        for (int index = 0; index < rows.size(); index++) {
            View row = rows.get(index);
            row.setNextFocusUpId(rows.get(Math.max(0, index - 1)).getId());
            row.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, index + 1)).getId());
        }
    }

    /**
     * Selected entries use the accent text colour and the accent bar in the icon column, so
     * the focus fill keeps its inner padding on both sides.
     */
    private void mark(TextView row, boolean selected) {
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(selected ? accent : Color.TRANSPARENT);
        bar.setCornerRadius(dp(1));
        bar.setSize(dp(SELECTION_BAR_DP), dp(18));
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(bar, null, null, null);
        row.setCompoundDrawablePadding(dp(SELECTION_GAP_DP));
        row.setTextColor(ConsolePanel.rowColors(selected ? accent : ConsolePanel.TEXT));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
