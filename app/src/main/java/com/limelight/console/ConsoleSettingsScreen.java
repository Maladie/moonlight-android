package com.limelight.console;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Full-screen settings (docs/MoonWaker/UX_MENU_PL.md section 3.2): categories on the left,
 * the selected category's {@link ConsolePanel} rows on the right. The caller owns what the rows
 * do; this class owns layout, category selection, choice sub-pages and D-pad/Back movement.
 */
final class ConsoleSettingsScreen {
    static final int CATEGORY_WIDTH_DP = 240;
    static final int SELECTION_BAR_DP = 3;

    static final class Category {
        final String title;
        final Function<ConsolePanel, View[]> rows;

        Category(String title, Function<ConsolePanel, View[]> rows) {
            this.title = title;
            this.rows = rows;
        }
    }

    private final Activity activity;
    private final Dialog dialog;
    private final FrameLayout root;
    private final ConsoleMenuBackdrop backdrop;
    private final LinearLayout columns;
    private final LinearLayout categoryColumn;
    private final LockableScrollView contentScroll;
    private final LinearLayout content;
    private final ConsolePanel panel;
    private final int accent;
    /** Opaque base tone under the blurred Home until its copy is ready. */
    private final int background;
    private final List<TextView> categoryRows = new ArrayList<>();
    private List<Category> categories = new ArrayList<>();
    private int selected = -1;
    /** Content row that opened the visible choice page, or -1 when the category is shown. */
    private int choiceOrigin = -1;
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
        categoryColumn.setPadding(0, dp(25), dp(24), 0);
        TextView title = new TextView(activity);
        title.setText(R.string.console_settings_title);
        title.setTextSize(ConsolePanel.TITLE_SP);
        title.setTextColor(ConsolePanel.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        title.setPadding(dp(ConsolePanel.ROW_INSET_DP), 0, 0, dp(18));
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
        panel = new ConsolePanel(activity, contentScroll, backdrop, reducedMotion);
        panel.floatOnGlass(content);

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(owner -> backdrop.release());
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

    /** Opens with the first category selected and focused. */
    void show(List<Category> categories) {
        this.categories = categories;
        for (TextView row : categoryRows) categoryColumn.removeView(row);
        categoryRows.clear();
        for (int index = 0; index < categories.size(); index++) {
            int position = index;
            TextView row = panel.action(categories.get(index).title);
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
        select(0);
        if (!dialog.isShowing()) {
            backdrop.capture(activity.getWindow(), reducedMotion);
            dialog.show();
            makeOpaque();
            // Only the lists fade in; the background is opaque from the first frame and the
            // blurred Home fades in over it once its copy is ready.
            columns.setAlpha(reducedMotion ? 1f : 0f);
            if (!reducedMotion) columns.animate().alpha(1f).setDuration(150L).start();
        }
        if (!categoryRows.isEmpty()) categoryRows.get(0).post(categoryRows.get(0)::requestFocus);
    }

    /**
     * Replaces the content column with one choice per label; choosing applies {@code onChosen}
     * and returns to the category with the opening row focused. Back returns without a change.
     */
    void showChoices(String title, String details, CharSequence[] labels, int current,
                     IntConsumer onChosen) {
        choiceOrigin = Math.max(0, focusedContentIndex());
        List<View> rows = new ArrayList<>();
        for (int index = 0; index < labels.length; index++) {
            int position = index;
            TextView row = panel.action(labels[index]);
            mark(row, index == current);
            row.setOnClickListener(view -> {
                onChosen.accept(position);
                closeChoices();
            });
            rows.add(row);
        }
        panel.render(content, categories.get(selected).title, title, details, false,
                rows.toArray(new View[0]));
        wireContent();
        View first = rows.isEmpty() ? null : rows.get(Math.max(0, current));
        if (first != null) first.post(first::requestFocus);
    }

    private void select(int index) {
        if (index == selected || index < 0 || index >= categories.size()) return;
        selected = index;
        choiceOrigin = -1;
        for (int row = 0; row < categoryRows.size(); row++) {
            mark(categoryRows.get(row), row == index);
        }
        renderCategory();
    }

    private void renderCategory() {
        Category category = categories.get(selected);
        panel.render(content, activity.getString(R.string.console_settings_title), category.title,
                "", false, category.rows.apply(panel));
        contentScroll.scrollTo(0, 0);
        wireContent();
    }

    private void closeChoices() {
        int origin = choiceOrigin;
        choiceOrigin = -1;
        renderCategory();
        List<View> rows = contentRows();
        if (rows.isEmpty()) return;
        View target = rows.get(Math.min(origin, rows.size() - 1));
        target.post(target::requestFocus);
    }

    private void back() {
        if (choiceOrigin >= 0) {
            closeChoices();
        } else if (focusedContentIndex() >= 0) {
            TextView category = categoryRows.get(selected);
            category.requestFocus();
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

    private int focusedContentIndex() {
        View focused = dialog.getCurrentFocus();
        return focused == null ? -1 : contentRows().indexOf(focused);
    }

    private static void chainVertically(List<View> rows) {
        for (int index = 0; index < rows.size(); index++) {
            View row = rows.get(index);
            row.setNextFocusUpId(rows.get(Math.max(0, index - 1)).getId());
            row.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, index + 1)).getId());
        }
    }

    /**
     * Selected entries use the accent text colour and a 3 dp accent bar in the icon column, so
     * the focus fill keeps its inner padding on both sides.
     */
    private void mark(TextView row, boolean selected) {
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(selected ? accent : Color.TRANSPARENT);
        bar.setSize(dp(SELECTION_BAR_DP), dp(18));
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(bar, null, null, null);
        row.setCompoundDrawablePadding(dp(10));
        row.setTextColor(ConsolePanel.rowColors(selected ? accent : ConsolePanel.TEXT));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
