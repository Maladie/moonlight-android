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
import android.graphics.drawable.InsetDrawable;
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
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * The one console side-panel list (docs/MoonWaker/UX_MENU_PL.md section 4): a floating glass
 * panel with an eyebrow/title/details header, rows with the label left and the value right,
 * uppercase section headers, destructive rows last behind a hairline, and a footer legend held
 * at the bottom. Focused rows get the light fill with dark text (section 4.2). Rows are plain
 * views so callers keep their own click handlers and tags.
 */
final class ConsolePanel {
    /** 700 px at 1080p, floating 24 px from the screen edges. */
    static final int WIDTH_DP = 350;
    static final int MARGIN_DP = 12;
    static final int ROW_HEIGHT_DP = 38;
    /** Section 4.6 at 2 px/dp: title 44 px, rows 30 px, subtitle 26 px, captions 24 px. */
    static final float TITLE_SP = 22f;
    static final float LABEL_SP = 15f;
    static final float SECONDARY_SP = 13f;
    static final float CAPTION_SP = 12f;
    static final int SECTION_TOP_DP = 16;
    /** The focus fill spans 13 dp from the panel edge; text sits 14 dp inside it. */
    static final int PAD_X_DP = 13;
    static final int ROW_INSET_DP = 14;
    static final int ICON_GAP_DP = 8;
    /** Bottom padding of the scroll view kept for the pinned legend (1004 px baseline). */
    static final int LEGEND_AREA_DP = 50;
    static final int LEGEND_BOTTOM_DP = 22;
    static final int SCROLL_TOP_DP = 18;
    static final int STEPS = 10;
    static final int TEXT = 0xFFF5F6F9;
    static final int HEADER = 0xFF949CAE;
    static final int LEGEND = 0xFFA0A6B4;
    /** Section 4.2 text focus: near-white fill, dark ink. */
    static final int FOCUS_FILL = 0xFCF6F7FA;
    static final int INK = 0xFF111319;
    static final int INK_SECONDARY = 0xFF4E5462;
    /** Destructive rows stay red on the light focus fill, deep enough to read on it. */
    static final int DANGER_INK = 0xFFC62833;

    private enum Kind { DESTRUCTIVE, STEPPER }

    private final Context context;
    private final LockableScrollView scroll;
    private final ConsoleGlass glass;
    /** Drawn by the scroll view, outside the scrolled rows. */
    private final TextView legend;
    private boolean legendStepper;
    private final int accent;
    private final int danger;
    private final int secondary;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Rect revealBounds = new Rect();
    // Shared per panel so building a long card allocates no per-row colour lists or fills.
    private final ColorStateList textColors;
    private final ColorStateList dangerColors;
    private final ColorStateList valueColors;
    private final ColorStateList stepperColors;
    private final Drawable.ConstantState focusFill;
    /** Tinted icon states by tint list, then icon: rows share them instead of mutating a copy. */
    private final Map<ColorStateList, Map<Integer, Drawable.ConstantState>> icons =
            new IdentityHashMap<>();
    private boolean reducedMotion;

    ConsolePanel(Context context, LockableScrollView scroll, ConsoleMenuBackdrop backdrop,
                 boolean reducedMotion) {
        this.context = context;
        this.scroll = scroll;
        this.reducedMotion = reducedMotion;
        glass = new ConsoleGlass(scroll, backdrop);
        accent = context.getResources().getColor(R.color.console_accent);
        danger = context.getResources().getColor(R.color.console_state_error);
        secondary = context.getResources().getColor(R.color.console_text_secondary);
        textColors = rowColors(TEXT);
        dangerColors = stateColors(danger, DANGER_INK);
        valueColors = stateColors(secondary, INK_SECONDARY);
        stepperColors = stateColors(accent, INK);
        focusFill = focusFill().getConstantState();
        // Parse the row icons once off the main thread; the first card then hits the cache.
        ConsoleMenuBackdrop.inBackground(() -> {
            context.getDrawable(R.drawable.ic_console_chevron);
            for (ConsoleHostPanel.Action action : ConsoleHostPanel.Action.values()) {
                context.getDrawable(action.icon);
            }
            for (ConsoleGameCard.Action action : ConsoleGameCard.Action.values()) {
                context.getDrawable(action.icon);
            }
        });
        legend = text("", CAPTION_SP, LEGEND);
        // The legend is drawn detached from any parent; TextView.setText reads its layout params.
        legend.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        legend.setSingleLine(true);
        legend.setPadding(dp(PAD_X_DP + ROW_INSET_DP), 0, dp(PAD_X_DP + ROW_INSET_DP), 0);
    }

    void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
    }

    /**
     * Gives the panel's scroll view the glass surface and the legend pinned in its bottom
     * padding, so rows scroll above the legend and never push it out of the panel.
     */
    void floatOnGlass(LinearLayout content) {
        scroll.setBackground(glass);
        scroll.setClipToOutline(true);
        // The top inset keeps scrolled-away header text fading out inside the glass instead of
        // being cut by its rounded top edge.
        scroll.setPadding(0, dp(SCROLL_TOP_DP), 0, dp(LEGEND_AREA_DP));
        scroll.setFooter(legend, dp(LEGEND_BOTTOM_DP));
        content.setPadding(dp(PAD_X_DP), dp(25 - SCROLL_TOP_DP), dp(PAD_X_DP), dp(4));
    }

    /** Undoes {@link #floatOnGlass} for a full-screen composition that brings its own surface. */
    void leaveGlass(LinearLayout content) {
        scroll.setPadding(0, 0, 0, 0);
        scroll.setFooter(null, 0);
        content.setPadding(0, 0, 0, 0);
    }

    /**
     * Picks the legend for the rows now in {@code container}; {@link #render} calls it once, and
     * callers that put saved rows back without rendering call it after restoring them.
     */
    void updateLegend(ViewGroup container) {
        boolean stepper = false;
        for (int index = 0; index < container.getChildCount(); index++) {
            stepper |= container.getChildAt(index).getTag(R.id.console_panel_row_kind)
                    == Kind.STEPPER;
        }
        if (stepper == legendStepper && legend.getText().length() > 0) return;
        legendStepper = stepper;
        legend.setText(stepper ? R.string.console_panel_legend_stepper
                : R.string.console_panel_legend);
        scroll.requestLayout();
    }

    /** Replaces the container content with header and rows (destructive ones last). */
    void render(LinearLayout container, String eyebrow, String title, CharSequence details,
                boolean scrollableDetails, View... rows) {
        container.removeAllViews();
        // Header text shares the rows' inner inset so every left edge lines up.
        container.addView(inset(header(eyebrow)), wrap(0, 0));
        TextView titleView = inset(text(title, TITLE_SP, TEXT));
        titleView.setTypeface(medium);
        container.addView(titleView, wrap(dp(4), 0));
        TextView detailView = inset(text(details, SECONDARY_SP, secondary));
        detailView.setVisibility(TextUtils.isEmpty(details) ? View.GONE : View.VISIBLE);
        if (scrollableDetails) makeScrollable(detailView);
        container.addView(detailView, wrap(dp(3), dp(12)));

        List<View> ordered = destructiveLast(Arrays.asList(rows), ConsolePanel::isDestructive);
        int firstDestructive = 0;
        while (firstDestructive < ordered.size()
                && !isDestructive(ordered.get(firstDestructive))) firstDestructive++;
        for (int index = 0; index < ordered.size(); index++) {
            View row = ordered.get(index);
            if (index == firstDestructive && index > 0) container.addView(divider(), dividerParams());
            container.addView(row, wrap(0, 0));
        }
        updateLegend(container);
    }

    private TextView inset(TextView view) {
        view.setPadding(dp(ROW_INSET_DP), view.getPaddingTop(), dp(ROW_INSET_DP), view.getPaddingBottom());
        return view;
    }

    /** An action row; {@link #navigation} adds a chevron for rows that open another panel. */
    TextView action(CharSequence label) {
        TextView row = text(label, LABEL_SP, TEXT);
        row.setTextColor(textColors);
        row.setTag("panel.action:" + label);
        row.setMinHeight(dp(ROW_HEIGHT_DP));
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        styleRow(row);
        ConsoleText.centreOnGlyphs(row);
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
                tinted(R.drawable.ic_console_chevron, row.getTextColors()), null);
        return row;
    }

    /**
     * A choice row with its mark on the right: a radio for a single choice, a check box for one
     * of several. A chosen check keeps its accent fill on the focus fill.
     */
    TextView choice(CharSequence label, boolean chosen, boolean multiple) {
        TextView row = action(label);
        int mark = multiple ? chosen ? R.drawable.ic_console_check_on : R.drawable.ic_console_check_off
                : chosen ? R.drawable.ic_console_radio_on : R.drawable.ic_console_radio_off;
        // A chosen radio is accent at rest and ink on the focus fill.
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, multiple && chosen
                ? context.getDrawable(mark)
                : tinted(mark, chosen ? stepperColors : row.getTextColors()), null);
        // Read as "selected" by TalkBack; the row colours do not depend on it.
        row.setSelected(chosen);
        return row;
    }

    /** Puts a line icon before the label, tinted like the label in every state. */
    <T extends TextView> T icon(T row, int icon) {
        Drawable[] current = row.getCompoundDrawablesRelative();
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(icon, row.getTextColors()),
                current[1], current[2], current[3]);
        row.setCompoundDrawablePadding(dp(ICON_GAP_DP));
        return row;
    }

    /**
     * Marks a row destructive: red text and icon, also on the light focus fill, rendered after
     * all other rows behind a hairline.
     */
    <T extends TextView> T destructive(T row) {
        row.setTag(R.id.console_panel_row_kind, Kind.DESTRUCTIVE);
        row.setTextColor(dangerColors);
        return row;
    }

    /** A fresh drawable per row (each row has its own state) over one shared tinted state. */
    private Drawable tinted(int icon, ColorStateList tint) {
        Map<Integer, Drawable.ConstantState> byIcon = icons.get(tint);
        if (byIcon == null) icons.put(tint, byIcon = new HashMap<>());
        Drawable.ConstantState state = byIcon.get(icon);
        if (state == null) {
            Drawable drawable = context.getDrawable(icon).mutate();
            drawable.setTintList(tint);
            state = drawable.getConstantState();
            byIcon.put(icon, state);
        }
        return state.newDrawable(context.getResources());
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
        TextView valueView = text(value, SECONDARY_SP, secondary);
        valueView.setTextColor(valueColors);
        valueView.setDuplicateParentStateEnabled(true);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setMaxWidth(dp(WIDTH_DP / 2));
        valueView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                tinted(R.drawable.ic_console_chevron, valueColors), null);
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
        TextView valueView = text("", SECONDARY_SP, accent);
        valueView.setTypeface(medium);
        valueView.setTextColor(stepperColors);
        valueView.setDuplicateParentStateEnabled(true);
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
        TextView header = header(label);
        header.setPadding(dp(ROW_INSET_DP), dp(SECTION_TOP_DP), dp(ROW_INSET_DP), dp(6));
        header.setFocusable(false);
        return header;
    }

    /** Non-focusable secondary text inside the row list. */
    TextView note(String value) {
        TextView note = text(value, SECONDARY_SP, secondary);
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
        TextView title = text(label, LABEL_SP, TEXT);
        title.setTextColor(textColors);
        title.setDuplicateParentStateEnabled(true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        ConsoleText.centreOnGlyphs(title);
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
        // Background first: the inset fill would otherwise replace the row padding with its own.
        row.setBackground(focusFill.newDrawable(context.getResources()));
        row.setPadding(dp(ROW_INSET_DP), dp(4), dp(ROW_INSET_DP), dp(4));
        row.setOnFocusChangeListener((view, hasFocus) -> {
            if (DiscordPanelViews.isTile(view)) DiscordPanelViews.styleTile(view, hasFocus);
            if (hasFocus) reveal(view);
        });
    }

    /** Section 4.2 text-row focus: a near-white pill, 1.5 dp inside the row's top and bottom. */
    private Drawable focusFill() {
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(10));
        fill.setColor(FOCUS_FILL);
        int inset = Math.round(1.5f * context.getResources().getDisplayMetrics().density);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused},
                new InsetDrawable(fill, 0, inset, 0, inset));
        background.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return background;
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
            // The padding holds the pinned legend; rows must stay above it.
            int top = scroll.getScrollY() + scroll.getPaddingTop() + margin;
            int bottom = scroll.getScrollY() + scroll.getHeight() - scroll.getPaddingBottom()
                    - margin;
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
        divider.setBackgroundColor(0x1EFFFFFF);
        divider.setFocusable(false);
        return divider;
    }

    private LinearLayout.LayoutParams dividerParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        params.setMargins(dp(ROW_INSET_DP), dp(6), dp(ROW_INSET_DP), dp(6));
        return params;
    }

    /** Row text: {@code color} at rest, dark ink on the focus fill, dimmed when disabled. */
    static ColorStateList rowColors(int color) {
        return stateColors(color, INK);
    }

    /**
     * Discord tiles (activated rows) keep their own dark focus surface, so only plain rows
     * switch to {@code focused} ink.
     */
    private static ColorStateList stateColors(int color, int focused) {
        int disabled = (color & 0x00FFFFFF) | 0x73000000;
        return new ColorStateList(new int[][]{
                {android.R.attr.state_activated, -android.R.attr.state_enabled},
                {android.R.attr.state_activated},
                {-android.R.attr.state_enabled, android.R.attr.state_focused},
                {-android.R.attr.state_enabled},
                {android.R.attr.state_focused},
                {}}, new int[]{disabled, color, (focused & 0x00FFFFFF) | 0x73000000,
                disabled, focused, color});
    }

    /** Uppercase, tracked, grey: eyebrow and section headers. */
    private TextView header(CharSequence label) {
        TextView header = text(label == null ? "" : label.toString().toUpperCase(Locale.ROOT),
                CAPTION_SP, HEADER);
        header.setTypeface(medium);
        header.setLetterSpacing(.08f);
        return header;
    }

    private TextView text(CharSequence value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        view.setSoundEffectsEnabled(false);
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
