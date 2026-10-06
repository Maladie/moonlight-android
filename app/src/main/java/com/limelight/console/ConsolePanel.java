package com.limelight.console;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * The one console side-panel list (docs/MoonWaker/UX_MENU_PL.md section 4): an
 * eyebrow/title/details header, 72 dp rows with the label left and the value right, uppercase
 * section headers, destructive rows last behind a divider, and a footer legend. Rows are
 * plain views so callers keep their own click handlers and tags.
 */
final class ConsolePanel {
    static final int WIDTH_DP = 360;
    static final int ROW_HEIGHT_DP = 44;
    static final float TITLE_SP = 22f;
    static final float LABEL_SP = 16f;
    static final float SECONDARY_SP = 13f;
    static final int SECTION_TOP_DP = 14;
    static final int ROW_INSET_DP = 14;
    static final float FOCUS_SCALE = 1.03f;
    static final int STEPS = 10;
    /** One menu backdrop for side panels and settings: text and art underneath stay unreadable. */
    static final int SCRIM = 0xF2000000;

    private enum Kind { DESTRUCTIVE, STEPPER }

    private final Context context;
    private final ScrollView scroll;
    private final int accent;
    private final int danger;
    private final int secondary;
    private final Rect revealBounds = new Rect();
    private boolean reducedMotion;

    ConsolePanel(Context context, ScrollView scroll, boolean reducedMotion) {
        this.context = context;
        this.scroll = scroll;
        this.reducedMotion = reducedMotion;
        accent = context.getResources().getColor(R.color.console_accent);
        danger = context.getResources().getColor(R.color.console_state_error);
        secondary = context.getResources().getColor(R.color.console_text_secondary);
    }

    void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
    }

    /** Replaces the container content with header, rows (destructive ones last) and legend. */
    void render(LinearLayout container, String eyebrow, String title, CharSequence details,
                boolean scrollableDetails, View... rows) {
        container.removeAllViews();
        // The 1.03 focus scale must not be clipped by the panel's side padding.
        container.setClipToPadding(false);
        // Header text shares the rows' inner inset so every left edge lines up.
        container.addView(inset(text(eyebrow, SECONDARY_SP, secondary, true)), wrap(0, 0));
        container.addView(inset(text(title, TITLE_SP, Color.WHITE, true)), wrap(dp(6), 0));
        TextView detailView = inset(text(details, SECONDARY_SP, secondary, false));
        detailView.setVisibility(TextUtils.isEmpty(details) ? View.GONE : View.VISIBLE);
        if (scrollableDetails) makeScrollable(detailView);
        container.addView(detailView, wrap(dp(6), dp(12)));

        List<View> ordered = destructiveLast(Arrays.asList(rows), ConsolePanel::isDestructive);
        int firstDestructive = 0;
        while (firstDestructive < ordered.size()
                && !isDestructive(ordered.get(firstDestructive))) firstDestructive++;
        boolean stepper = false;
        for (int index = 0; index < ordered.size(); index++) {
            View row = ordered.get(index);
            if (index == firstDestructive && index > 0) container.addView(divider(), dividerParams());
            container.addView(row, wrap(0, dp(2)));
            stepper |= row.getTag(R.id.console_panel_row_kind) == Kind.STEPPER;
        }
        TextView legend = text(context.getString(stepper ? R.string.console_panel_legend_stepper
                : R.string.console_panel_legend), SECONDARY_SP, 0x99FFFFFF, false);
        container.addView(inset(legend), wrap(dp(20), 0));
    }

    private TextView inset(TextView view) {
        view.setPadding(dp(ROW_INSET_DP), view.getPaddingTop(), dp(ROW_INSET_DP), view.getPaddingBottom());
        return view;
    }

    /** An action row; {@link #navigation} adds a chevron for rows that open another panel. */
    TextView action(CharSequence label) {
        TextView row = text(label, LABEL_SP, Color.WHITE, false);
        row.setTextColor(stateColors(Color.WHITE));
        row.setTag("panel.action:" + label);
        row.setMinHeight(dp(ROW_HEIGHT_DP));
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        styleRow(row);
        return row;
    }

    TextView action(CharSequence label, boolean enabled) {
        TextView row = action(label);
        row.setEnabled(enabled);
        return row;
    }

    TextView navigation(CharSequence label) {
        TextView row = action(label);
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                chevron(stateColors(Color.WHITE)), null);
        return row;
    }

    /** Marks a row destructive: red text, rendered after all other rows behind a divider. */
    <T extends TextView> T destructive(T row) {
        row.setTag(R.id.console_panel_row_kind, Kind.DESTRUCTIVE);
        row.setTextColor(stateColors(danger));
        return row;
    }

    /** A label with a smaller secondary line, e.g. why an action is unavailable. */
    CharSequence withDetail(String label, String detail) {
        SpannableString value = new SpannableString(label + "\n" + detail);
        int size = Math.round(SECONDARY_SP * context.getResources().getDisplayMetrics().scaledDensity);
        value.setSpan(new AbsoluteSizeSpan(size), label.length() + 1, value.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return value;
    }

    LinearLayout toggle(String label, boolean checked) {
        LinearLayout row = labelledRow(label);
        row.setTag("settings.toggle:" + label);
        row.addView(new Toggle(context, accent), new LinearLayout.LayoutParams(dp(38), dp(22)));
        setChecked(row, checked);
        return row;
    }

    void setChecked(View row, boolean checked) {
        ViewGroup group = (ViewGroup) row;
        ((Toggle) group.getChildAt(1)).setChecked(checked);
        row.setContentDescription(((TextView) group.getChildAt(0)).getText() + ". "
                + context.getString(checked ? R.string.console_on : R.string.console_off));
    }

    /** A value picker row: label, current value and a chevron to the choice panel. */
    LinearLayout value(String label, CharSequence value) {
        LinearLayout row = labelledRow(label);
        TextView valueView = text(value, SECONDARY_SP, secondary, false);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setMaxWidth(dp(WIDTH_DP / 2));
        valueView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                chevron(ColorStateList.valueOf(secondary)), null);
        valueView.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(16);
        row.addView(valueView, params);
        setValue(row, value);
        return row;
    }

    static void setValue(View row, CharSequence value) {
        ViewGroup group = (ViewGroup) row;
        ((TextView) group.getChildAt(1)).setText(value);
        row.setContentDescription(((TextView) group.getChildAt(0)).getText() + ". " + value);
    }

    /** A 0–10 value stored as a 0–100 percentage; left/right step it, OK steps up and wraps. */
    LinearLayout stepper(String label, int percent, IntConsumer onPercentChanged) {
        LinearLayout row = labelledRow(label);
        row.setTag(R.id.console_panel_row_kind, Kind.STEPPER);
        TextView valueView = text("", SECONDARY_SP, accent, true);
        row.addView(valueView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int[] step = {stepFromPercent(percent)};
        Runnable show = () -> {
            valueView.setText(stepperText(step[0]));
            row.setContentDescription(label + ". " + step[0] + "/" + STEPS);
        };
        IntConsumer set = next -> {
            int clamped = Math.max(0, Math.min(STEPS, next));
            if (clamped == step[0]) return;
            step[0] = clamped;
            show.run();
            onPercentChanged.accept(percentFromStep(clamped));
        };
        row.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                return false;
            }
            int direction = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            // At the end of the range the key is ordinary navigation (e.g. back to a column).
            if (!canStep(step[0], direction)) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) set.accept(step[0] + direction);
            return true;
        });
        row.setOnClickListener(view -> set.accept(step[0] >= STEPS ? 0 : step[0] + 1));
        show.run();
        return row;
    }

    TextView section(String label) {
        TextView header = text(label.toUpperCase(Locale.ROOT), SECONDARY_SP, accent, true);
        header.setPadding(dp(ROW_INSET_DP), dp(SECTION_TOP_DP), dp(ROW_INSET_DP), dp(6));
        header.setFocusable(false);
        return header;
    }

    /** Non-focusable secondary text inside the row list. */
    TextView note(String value) {
        TextView note = text(value, SECONDARY_SP, secondary, false);
        note.setPadding(dp(ROW_INSET_DP), dp(8), dp(ROW_INSET_DP), dp(8));
        note.setFocusable(false);
        return note;
    }

    static boolean canStep(int step, int direction) {
        int next = step + direction;
        return next >= 0 && next <= STEPS;
    }

    /** "6/10" with a "‹" or "›" only on the sides where a step is still possible. */
    static String stepperText(int step) {
        return (canStep(step, -1) ? "‹  " : "") + step + "/" + STEPS
                + (canStep(step, 1) ? "  ›" : "");
    }

    static int stepFromPercent(int percent) {
        return Math.max(0, Math.min(STEPS, Math.round(percent * STEPS / 100f)));
    }

    static int percentFromStep(int step) {
        return Math.max(0, Math.min(STEPS, step)) * 100 / STEPS;
    }

    /** Stable reorder that keeps relative order and moves destructive rows to the end. */
    static <T> List<T> destructiveLast(List<T> rows, Predicate<T> destructive) {
        List<T> ordered = new ArrayList<>(rows.size());
        for (T row : rows) if (!destructive.test(row)) ordered.add(row);
        for (T row : rows) if (destructive.test(row)) ordered.add(row);
        return ordered;
    }

    private static boolean isDestructive(View row) {
        return row.getTag(R.id.console_panel_row_kind) == Kind.DESTRUCTIVE;
    }

    private LinearLayout labelledRow(String label) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ROW_HEIGHT_DP));
        TextView title = text(label, LABEL_SP, Color.WHITE, false);
        title.setTextColor(stateColors(Color.WHITE));
        title.setDuplicateParentStateEnabled(true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        styleRow(row);
        return row;
    }

    private void styleRow(View row) {
        row.setId(View.generateViewId());
        row.setFocusable(true);
        row.setClickable(true);
        row.setSoundEffectsEnabled(false);
        row.setPadding(dp(ROW_INSET_DP), dp(4), dp(ROW_INSET_DP), dp(4));
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(8));
        focused.setColor(0x1FFFFFFF);
        focused.setStroke(context.getResources().getDimensionPixelSize(
                R.dimen.console_focus_stroke_width), accent);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        background.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        row.setBackground(background);
        row.setOnFocusChangeListener((view, hasFocus) -> {
            if (DiscordPanelViews.isTile(view)) {
                DiscordPanelViews.styleTile(view, hasFocus);
            } else {
                view.setScaleX(hasFocus ? FOCUS_SCALE : 1f);
                view.setScaleY(hasFocus ? FOCUS_SCALE : 1f);
            }
            if (hasFocus) reveal(view);
        });
    }

    private void makeScrollable(TextView detailView) {
        detailView.setId(View.generateViewId());
        detailView.setTag("panel.scrollable.details");
        detailView.setFocusable(true);
        detailView.setPadding(dp(ROW_INSET_DP), dp(4), dp(ROW_INSET_DP), dp(4));
        detailView.setOnFocusChangeListener((view, focused) ->
                detailView.setTextColor(focused ? Color.WHITE : secondary));
        detailView.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int direction;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) direction = 1;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) direction = -1;
            else return false;
            if (!scroll.canScrollVertically(direction)) return false;
            if (reducedMotion) scroll.scrollBy(0, direction * dp(160));
            else scroll.smoothScrollBy(0, direction * dp(160));
            return true;
        });
    }

    /** Keeps the focused row inside the panel viewport with a small margin. */
    private void reveal(View view) {
        view.post(() -> {
            if (!view.isAttachedToWindow() || !isInside(scroll, view)) return;
            view.getDrawingRect(revealBounds);
            scroll.offsetDescendantRectToMyCoords(view, revealBounds);
            int margin = dp(16);
            int top = scroll.getScrollY() + margin;
            int bottom = scroll.getScrollY() + scroll.getHeight() - margin;
            int target = scroll.getScrollY();
            if (revealBounds.bottom > bottom) target += revealBounds.bottom - bottom;
            else if (revealBounds.top < top) target -= top - revealBounds.top;
            target = Math.max(0, target);
            if (target == scroll.getScrollY()) return;
            if (reducedMotion) scroll.scrollTo(0, target);
            else scroll.smoothScrollTo(0, target);
        });
    }

    private static boolean isInside(View ancestor, View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private View divider() {
        View divider = new View(context);
        divider.setBackgroundColor(0x33FFFFFF);
        divider.setFocusable(false);
        return divider;
    }

    private LinearLayout.LayoutParams dividerParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.topMargin = dp(8);
        params.bottomMargin = dp(8);
        return params;
    }

    private Drawable chevron(ColorStateList tint) {
        Drawable chevron = context.getDrawable(R.drawable.ic_console_chevron).mutate();
        chevron.setTintList(tint);
        return chevron;
    }

    private static ColorStateList stateColors(int color) {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{(color & 0x00FFFFFF) | 0x73000000, color});
    }

    private TextView text(CharSequence value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        view.setSoundEffectsEnabled(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams wrap(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = top;
        params.bottomMargin = bottom;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Two-state switch drawn with preallocated paint; it follows the row's enabled state. */
    private static final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private final int accent;
        private boolean checked;

        Toggle(Context context, int accent) {
            super(context);
            this.accent = accent;
            setFocusable(false);
            setDuplicateParentStateEnabled(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setChecked(boolean checked) {
            if (this.checked == checked) return;
            this.checked = checked;
            invalidate();
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            View row = (View) getParent();
            int alpha = row == null || row.isEnabled() ? 255 : 115;
            float height = getHeight();
            float radius = height / 2f;
            track.set(0, 0, getWidth(), height);
            paint.setColor(checked ? accent : 0xFF3A434C);
            paint.setAlpha(alpha);
            canvas.drawRoundRect(track, radius, radius, paint);
            paint.setColor(checked ? 0xFF05060A : 0xFFE6EAF0);
            paint.setAlpha(alpha);
            float inset = height * .14f;
            float knob = radius - inset;
            float cx = checked ? getWidth() - radius : radius;
            canvas.drawCircle(cx, radius, knob, paint);
        }
    }
}
