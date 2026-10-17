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
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
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
import java.util.function.IntFunction;
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
    /** Mockup 18 value and stepper text (28 px) and the stepper arrows (32 px). */
    static final float VALUE_SP = 14f;
    static final float ARROW_SP = 16f;
    static final int SECTION_TOP_DP = 16;
    /** The focus fill spans 13 dp from the panel edge; text sits 14 dp inside it. */
    static final int PAD_X_DP = 13;
    static final int ROW_INSET_DP = 14;
    static final int ICON_GAP_DP = 8;
    /** Bottom padding of the scroll view kept for the pinned legend (1004 px baseline). */
    static final int LEGEND_AREA_DP = 50;
    static final int LEGEND_BOTTOM_DP = 22;
    /** Mockup 18 legend: centred 48 px above the card's bottom edge (side panels: 52 px). */
    static final int CARD_LEGEND_BOTTOM_DP = 19;
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
    /** Mockup 18: a disabled row's label and its reason line. */
    static final int DISABLED_TEXT = 0xFF6E7482;
    static final int DISABLED_REASON = 0xFF606674;
    /** Mockup 18 toggle: 60 x 34 px with a 24 px knob. */
    static final int TOGGLE_WIDTH_DP = 30;
    static final int TOGGLE_HEIGHT_DP = 17;
    /**
     * Mockup 18 slider under a focused stepper: a 72 px band (40 dp drawn 4 dp up into the
     * line), 6 px track 4 px below the line, 22 px knob, 24 px range labels.
     */
    static final int SLIDER_HEIGHT_DP = 40;

    private enum Kind { DESTRUCTIVE, STEPPER, TOGGLE }

    /** Moves a stepper's range and value in place, without its change callback. */
    private interface StepperRange {
        void set(int min, int max, int value);
    }

    private final Context context;
    private final LockableScrollView scroll;
    private final ConsoleGlass glass;
    /** Drawn by the scroll view, outside the scrolled rows. */
    private final TextView legend;
    private boolean legendStepper;
    /** Set by {@link #setLegend}; the next {@link #updateLegend} replaces it. */
    private boolean customLegend;
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
    private final boolean card;

    ConsolePanel(Context context, LockableScrollView scroll, ConsoleMenuBackdrop backdrop,
                 boolean reducedMotion) {
        this(context, scroll, backdrop, reducedMotion, false);
    }

    /**
     * {@code card}: the settings card of mockup 18 instead of a side panel (mockup 4): 32 px
     * focus radius instead of 20, its own header spacing and legend line.
     */
    ConsolePanel(Context context, LockableScrollView scroll, ConsoleMenuBackdrop backdrop,
                 boolean reducedMotion, boolean card) {
        this.context = context;
        this.scroll = scroll;
        this.reducedMotion = reducedMotion;
        this.card = card;
        glass = new ConsoleGlass(scroll, backdrop);
        accent = context.getResources().getColor(R.color.console_accent);
        danger = context.getResources().getColor(R.color.console_state_error);
        secondary = context.getResources().getColor(R.color.console_text_secondary);
        textColors = rowColors(TEXT);
        dangerColors = stateColors(danger, DANGER_INK);
        valueColors = stateColors(secondary, INK_SECONDARY);
        stepperColors = stateColors(accent, INK);
        focusFill = focusFill(card ? 16 : 10).getConstantState();
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
        scroll.setFooter(legend, dp(card ? CARD_LEGEND_BOTTOM_DP : LEGEND_BOTTOM_DP));
        content.setPadding(dp(PAD_X_DP), dp(25 - SCROLL_TOP_DP), dp(PAD_X_DP), dp(4));
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
        if (!customLegend && stepper == legendStepper && legend.getText().length() > 0) return;
        customLegend = false;
        legendStepper = stepper;
        legend.setText(stepper ? R.string.console_panel_legend_stepper
                : R.string.console_panel_legend);
        scroll.requestLayout();
    }

    /** A legend chosen by the caller, e.g. for the focused row of the settings screen. */
    void setLegend(CharSequence text) {
        customLegend = true;
        if (TextUtils.equals(legend.getText(), text)) return;
        legend.setText(text);
        scroll.requestLayout();
    }

    static boolean isStepper(View row) {
        return row.getTag(R.id.console_panel_row_kind) == Kind.STEPPER;
    }

    static boolean isToggle(View row) {
        return row.getTag(R.id.console_panel_row_kind) == Kind.TOGGLE;
    }

    /** The legend's "−/+ step" text of a stepper row. */
    static String stepLabel(View row) {
        Object label = row.getTag(R.id.console_panel_step_label);
        return label == null ? "" : label.toString();
    }

    /** Replaces the container content with header and rows (destructive ones last). */
    void render(LinearLayout container, String eyebrow, String title, CharSequence details,
                boolean scrollableDetails, View... rows) {
        container.removeAllViews();
        // Header text shares the rows' inner inset so every left edge lines up.
        container.addView(inset(header(eyebrow)), wrap(0, 0));
        TextView titleView = inset(text(title, TITLE_SP, TEXT));
        titleView.setTypeface(medium);
        // Mockup 18 card baselines: eyebrow, title +60 px, subtitle +44 px, rows 30 px below
        // the subtitle or 34 px below the title (the mockups use 26-32 and 36: 18-game starts
        // its rows 6 px above 18-video). Side panels (mockup 4): title +56, subtitle +42.
        container.addView(titleView, wrap(dp(card ? 7 : 4), 0));
        TextView detailView = inset(text(details, SECONDARY_SP, secondary));
        detailView.setVisibility(TextUtils.isEmpty(details) ? View.GONE : View.VISIBLE);
        if (scrollableDetails) makeScrollable(detailView);
        container.addView(detailView, wrap(dp(card ? 4 : 3), dp(card ? 0 : 12)));

        List<View> ordered = destructiveLast(Arrays.asList(rows), ConsolePanel::isDestructive);
        int firstDestructive = 0;
        while (firstDestructive < ordered.size()
                && !isDestructive(ordered.get(firstDestructive))) firstDestructive++;
        for (int index = 0; index < ordered.size(); index++) {
            View row = ordered.get(index);
            if (index == firstDestructive && index > 0) container.addView(divider(), dividerParams());
            container.addView(row, wrap(card && index == 0 ? dp(12) : 0, 0));
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
        row.setTag(R.id.console_panel_row_kind, Kind.TOGGLE);
        row.addView(new Toggle(context, accent), new LinearLayout.LayoutParams(
                dp(TOGGLE_WIDTH_DP), dp(TOGGLE_HEIGHT_DP)));
        setChecked(row, checked);
        return row;
    }

    /**
     * Mockup 18 "row disabled with a reason": dimmed label, the reason on a smaller second line,
     * a faded control; the row leaves the focus chain.
     */
    <T extends View> T disabled(T row, String reason) {
        row.setEnabled(false);
        row.setFocusable(false);
        TextView title = (TextView) ((ViewGroup) row).getChildAt(0);
        CharSequence label = title.getText();
        SpannableString text = new SpannableString(label + "\n" + reason);
        int start = label.length() + 1;
        text.setSpan(new AbsoluteSizeSpan(Math.round(CAPTION_SP
                        * context.getResources().getDisplayMetrics().scaledDensity)), start,
                text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(DISABLED_REASON), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(text);
        title.setLineSpacing(dp(3), 1f);
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
        // Mockup 18: values 28 px with the chevron 9 px after them.
        TextView valueView = text(value, VALUE_SP, secondary);
        valueView.setTextColor(valueColors);
        valueView.setDuplicateParentStateEnabled(true);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        valueView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                tinted(R.drawable.ic_console_chevron, valueColors), null);
        valueView.setCompoundDrawablePadding(dp(4));
        // Label and value both take their full width; only when together they do not fit do
        // both give up the overflow equally (the label wraps, the value ellipsizes).
        row.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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

    /** A 0–10 value stored as a 0–100 percentage. */
    LinearLayout stepper(String label, int percent, IntConsumer onPercentChanged) {
        return stepper(label, stepFromPercent(percent), 0, STEPS, 1,
                step -> step + "/" + STEPS, "1", null,
                step -> onPercentChanged.accept(percentFromStep(step)));
    }

    /**
     * A stepped value in {@code [min, max]}: left/right step it (held keys 5x, then 10x faster),
     * OK steps up to the maximum. Non-null {@code sliderTicks} adds mockup 18's slider under the
     * focused row, with dots at those values.
     */
    LinearLayout stepper(String label, int value, int min, int max, int step,
                         IntFunction<String> format, String stepLabel, int[] sliderTicks,
                         IntConsumer onChanged) {
        LinearLayout line = labelledRow(label);
        TextView valueView = text("", VALUE_SP, accent);
        valueView.setTypeface(medium);
        valueView.setTextColor(stepperColors);
        valueView.setDuplicateParentStateEnabled(true);
        line.addView(valueView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = line;
        Slider slider = null;
        if (sliderTicks != null) {
            // The focus fill and the keys move to a column holding the line and the slider.
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            styleRow(row);
            line.setFocusable(false);
            line.setClickable(false);
            line.setBackground(null);
            line.setOnFocusChangeListener(null);
            line.setPadding(0, 0, 0, 0);
            line.setMinimumHeight(dp(ROW_HEIGHT_DP - 8));
            line.setDuplicateParentStateEnabled(true);
            row.addView(line, wrap(0, 0));
            slider = new Slider(context, min, max, sliderTicks, format.apply(min),
                    format.apply(max));
            slider.setVisibility(View.GONE);
            row.addView(slider, wrap(-dp(4), 0));
            slider.getLayoutParams().height = dp(SLIDER_HEIGHT_DP);
            Slider shown = slider;
            View.OnFocusChangeListener styled = row.getOnFocusChangeListener();
            row.setOnFocusChangeListener((view, focused) -> {
                shown.setVisibility(focused ? View.VISIBLE : View.GONE);
                styled.onFocusChange(view, focused);
            });
        }
        LinearLayout result = row;
        Slider bar = slider;
        result.setTag(R.id.console_panel_row_kind, Kind.STEPPER);
        result.setTag(R.id.console_panel_step_label, stepLabel);
        int[] bounds = {min, max};
        int[] current = {Math.max(min, Math.min(max, value))};
        Runnable show = () -> {
            String formatted = format.apply(current[0]);
            SpannableString text = new SpannableString(
                    stepperText(current[0], bounds[0], bounds[1], formatted));
            int arrow = Math.round(ARROW_SP * context.getResources().getDisplayMetrics().scaledDensity);
            for (int index = 0; index < text.length(); index++) {
                char glyph = text.charAt(index);
                if (glyph == '‹' || glyph == '›') {
                    text.setSpan(new AbsoluteSizeSpan(arrow), index, index + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            valueView.setText(text);
            if (bar != null) bar.setValue(current[0]);
            result.setContentDescription(label + ". " + formatted);
        };
        IntConsumer set = next -> {
            if (next == current[0]) return;
            current[0] = next;
            show.run();
            onChanged.accept(next);
        };
        result.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                return false;
            }
            int direction = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            if (!handlesStepKey(current[0], direction, bounds[0], bounds[1],
                    event.getRepeatCount())) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int repeat = event.getRepeatCount();
                int stride = step * (repeat >= 20 ? 10 : repeat >= 6 ? 5 : 1);
                set.accept(stepValue(current[0], direction, bounds[0], bounds[1], stride));
            }
            return true;
        });
        // No wrap: none of the values is cyclic, and max → min would e.g. drop the bitrate.
        result.setOnClickListener(view -> set.accept(stepValue(current[0], 1, bounds[0],
                bounds[1], step)));
        result.setTag(R.id.console_panel_stepper_range, (StepperRange) (low, high, next) -> {
            bounds[0] = low;
            bounds[1] = high;
            current[0] = Math.max(low, Math.min(high, next));
            if (bar != null) bar.setRange(low, high, format.apply(low), format.apply(high));
            show.run();
        });
        show.run();
        return result;
    }

    /**
     * Gives a stepper row a new range and value, e.g. when another row bounds it; its change
     * callback does not run. The row stays in place, so focus and held keys are kept.
     */
    static void setStepper(View row, int min, int max, int value) {
        ((StepperRange) row.getTag(R.id.console_panel_stepper_range)).set(min, max, value);
    }

    /** A number field row; OK opens the TV's number keyboard. */
    EditText numberField(String hint, CharSequence value, boolean decimal) {
        EditText field = new EditText(context);
        field.setText(value);
        field.setHint(hint);
        field.setTextSize(LABEL_SP);
        field.setTextColor(textColors);
        field.setHintTextColor(LEGEND);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER
                | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setMinHeight(dp(ROW_HEIGHT_DP));
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        styleRow(field);
        return field;
    }

    TextView section(String label) {
        TextView header = header(label);
        // Mockup 18 card "head": 66 px, baseline 50 px down; side panels keep 16 + 6 dp.
        header.setPadding(dp(ROW_INSET_DP), dp(card ? 14 : SECTION_TOP_DP), dp(ROW_INSET_DP),
                dp(card ? 5 : 6));
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

    static boolean canStep(int value, int direction, int min, int max) {
        return direction > 0 ? value < max : value > min;
    }

    /**
     * Whether a stepper keeps a left/right key. At the end of the range a fresh press is
     * ordinary navigation (e.g. back to a column); a held key's repeats stay with the stepper.
     */
    static boolean handlesStepKey(int value, int direction, int min, int max, int repeatCount) {
        return canStep(value, direction, min, max) || repeatCount > 0;
    }

    /** One step of {@code step} towards {@code direction}, on multiples of it, kept in range. */
    static int stepValue(int value, int direction, int min, int max, int step) {
        int next = direction > 0 ? Math.floorDiv(value, step) * step + step
                : -Math.floorDiv(-value, step) * step - step;
        return Math.max(min, Math.min(max, next));
    }

    /** The value with a "‹" or "›" only on the sides where a step is still possible. */
    static String stepperText(int value, int min, int max, String formatted) {
        return (canStep(value, -1, min, max) ? "‹  " : "") + formatted
                + (canStep(value, 1, min, max) ? "  ›" : "");
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
        // No Resources here: InsetDrawable/GradientDrawable states built in code are 160 dpi, and
        // newDrawable(res) would rescale the px inset and radius again (70 px fill became 64).
        row.setBackground(focusFill.newDrawable());
        row.setPadding(dp(ROW_INSET_DP), dp(4), dp(ROW_INSET_DP), dp(4));
        row.setOnFocusChangeListener((view, hasFocus) -> {
            if (DiscordPanelViews.isTile(view)) DiscordPanelViews.styleTile(view, hasFocus);
            if (hasFocus) reveal(view);
        });
    }

    /** Section 4.2 text-row focus: a near-white pill, 1.5 dp inside the row's top and bottom. */
    Drawable focusFill(int radiusDp) {
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(radiusDp));
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

    /** Mockup 18 "sep": a hairline across the text span in a 25 px (12.5 dp) band. */
    View separator() {
        // A plain View measures to all the height left to it, which a fill-viewport card offers.
        View separator = new View(context) {
            @Override protected void onMeasure(int width, int height) {
                setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), width),
                        getSuggestedMinimumHeight());
            }
        };
        separator.setBackground(new InsetDrawable(new ColorDrawable(0x1EFFFFFF),
                dp(ROW_INSET_DP), dp(6), dp(ROW_INSET_DP), dp(6)));
        separator.setMinimumHeight(dp(12) + Math.max(1, dp(1) / 2));
        separator.setFocusable(false);
        return separator;
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
        int disabled = color == TEXT ? DISABLED_TEXT : (color & 0x00FFFFFF) | 0x73000000;
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

        /** Mockup 18 toggle_sprite: track accent/grey, ink knob; on the focus fill ink/light. */
        @Override protected void onDraw(Canvas canvas) {
            View row = (View) getParent();
            boolean focused = row != null && row.isFocused();
            int alpha = row == null || row.isEnabled() ? 255 : 102;
            float height = getHeight();
            float radius = height / 2f;
            track.set(0, 0, getWidth(), height);
            paint.setColor(checked ? focused ? INK : accent : focused ? 0xFFBEC2CC : 0xFF5A606E);
            paint.setAlpha(alpha);
            canvas.drawRoundRect(track, radius, radius, paint);
            paint.setColor(focused ? Color.WHITE : INK);
            paint.setAlpha(alpha);
            float knob = height * 12f / 34f;
            float cx = checked ? getWidth() - radius : radius;
            canvas.drawCircle(cx, radius, knob, paint);
        }
    }

    /** Mockup 18 slider band under a focused stepper; drawn with preallocated paint. */
    private static final class Slider extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private int min;
        private int max;
        private final int[] ticks;
        private String low;
        private String high;
        private int value;

        Slider(Context context, int min, int max, int[] ticks, String low, String high) {
            super(context);
            this.min = min;
            this.max = max;
            this.ticks = ticks;
            this.low = low;
            this.high = high;
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setTextSize(CAPTION_SP * context.getResources().getDisplayMetrics().scaledDensity);
        }

        void setValue(int value) {
            if (this.value == value) return;
            this.value = value;
            invalidate();
        }

        void setRange(int min, int max, String low, String high) {
            this.min = min;
            this.max = max;
            this.low = low;
            this.high = high;
            invalidate();
        }

        private float x(int at) {
            float fraction = max == min ? 0f : (at - min) / (float) (max - min);
            return Math.max(0f, Math.min(1f, fraction)) * getWidth();
        }

        @Override protected void onDraw(Canvas canvas) {
            float dp = getResources().getDisplayMetrics().density;
            // Mockup 18: the track 80 px below the row top (ROW_H + 4); the band starts 60 px down.
            float y = 10f * dp;
            float half = 1.5f * dp;
            track.set(0, y - half, getWidth(), y + half);
            paint.setColor(INK_SECONDARY);
            paint.setAlpha(70);
            canvas.drawRoundRect(track, half, half, paint);
            paint.setColor(INK);
            track.right = x(value);
            canvas.drawRoundRect(track, half, half, paint);
            paint.setColor(INK_SECONDARY);
            for (int tick : ticks) canvas.drawCircle(x(tick), y, half, paint);
            paint.setColor(INK);
            canvas.drawCircle(x(value), y, 5.5f * dp, paint);
            paint.setColor(INK_SECONDARY);
            float baseline = y + 17f * dp;
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(low, 0, baseline, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(high, getWidth(), baseline, paint);
        }
    }
}
