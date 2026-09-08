package com.limelight.console;

import android.content.Context;
import android.app.AlertDialog;
import android.widget.NumberPicker;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Controller friendly draft editor for one child profile.
 *
 * The view only owns the draft while it is visible. Persistence, authentication,
 * and navigation stay with the caller through {@link SaveCallback}.
 */
final class ConsoleChildProfileEditor extends LinearLayout {
    private static final int DAY_COUNT = 7;
    private static final int MINUTES_PER_DAY = 1440;
    private static final int MAX_DAILY_LIMIT_MINUTES = 1440;
    private static final String AVATAR_DEFAULT = "default";
    private static final String[] AVATAR_VALUES = {
            AVATAR_DEFAULT, "purple", "blue", "green", "orange"
    };
    private static final int[] AVATAR_LABELS = {
            R.string.console_child_avatar_default,
            R.string.console_child_avatar_purple,
            R.string.console_child_avatar_blue,
            R.string.console_child_avatar_green,
            R.string.console_child_avatar_orange
    };
    private static final String[] AVATAR_SYMBOLS = {"●", "◆", "■", "▲", "✦"};
    private static final int[] DAY_LABELS = {
            R.string.console_child_day_mon,
            R.string.console_child_day_tue,
            R.string.console_child_day_wed,
            R.string.console_child_day_thu,
            R.string.console_child_day_fri,
            R.string.console_child_day_sat,
            R.string.console_child_day_sun
    };

    interface SaveCallback {
        void save(Draft draft, Completion completion);
    }

    interface Completion {
        void onSaved();
        void onFailed(CharSequence message, boolean conflict);
    }

    static final class Day {
        final boolean enabled;
        final int startMinute;
        final int endMinute;
        final int dailyLimitSeconds;

        Day(boolean enabled, int startMinute, int endMinute, int dailyLimitSeconds) {
            this.enabled = enabled;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.dailyLimitSeconds = dailyLimitSeconds;
        }

        static Day disabled() {
            return new Day(false, 0, MINUTES_PER_DAY, 0);
        }
    }

    static final class Draft {
        final String profileId;
        final String name;
        final String avatar;
        final boolean enabled;
        final List<Day> weekdays;
        final List<String> allowedGameKeys;
        final int expectedRevision;
        final boolean grantCurrentDevice;

        Draft(String profileId, String name, String avatar, boolean enabled,
              List<Day> weekdays, List<String> allowedGameKeys, int expectedRevision) {
            this(profileId, name, avatar, enabled, weekdays, allowedGameKeys,
                    expectedRevision, false);
        }

        Draft(String profileId, String name, String avatar, boolean enabled,
              List<Day> weekdays, List<String> allowedGameKeys, int expectedRevision,
              boolean grantCurrentDevice) {
            if (weekdays == null || weekdays.size() != DAY_COUNT) {
                throw new IllegalArgumentException("weekdays must contain Monday through Sunday");
            }
            this.profileId = profileId == null ? "" : profileId;
            this.name = name == null ? "" : name;
            this.avatar = avatar == null ? "" : avatar;
            this.enabled = enabled;
            ArrayList<Day> days = new ArrayList<>(DAY_COUNT);
            for (Day day : weekdays) {
                if (day == null) throw new IllegalArgumentException("weekday must not be null");
                days.add(new Day(day.enabled, day.startMinute, day.endMinute,
                        day.dailyLimitSeconds));
            }
            this.weekdays = Collections.unmodifiableList(days);
            ArrayList<String> keys = new ArrayList<>();
            if (allowedGameKeys != null) {
                for (String key : allowedGameKeys) if (key != null) keys.add(key);
            }
            this.allowedGameKeys = Collections.unmodifiableList(keys);
            this.expectedRevision = expectedRevision;
            this.grantCurrentDevice = grantCurrentDevice && this.profileId.isEmpty();
        }

        Draft(String profileId, String name, String avatar, boolean enabled,
              Day[] weekdays, List<String> allowedGameKeys, int expectedRevision) {
            this(profileId, name, avatar, enabled, asList(weekdays), allowedGameKeys,
                    expectedRevision);
        }

        static Draft newChild(int expectedRevision) {
            ArrayList<Day> days = new ArrayList<>(DAY_COUNT);
            for (int index = 0; index < DAY_COUNT; index++) days.add(Day.disabled());
            return new Draft("", "", AVATAR_DEFAULT, true, days,
                    Collections.emptyList(), expectedRevision);
        }

        private static List<Day> asList(Day[] days) {
            if (days == null) return null;
            ArrayList<Day> result = new ArrayList<>(days.length);
            Collections.addAll(result, days);
            return result;
        }
    }

    private static final class DayEditor {
        final int index;
        final View selector;
        final TextView summary;
        final LinearLayout card;
        final CheckBox enabled;
        final EditText start;
        final EditText end;
        final EditText limit;

        DayEditor(int index, View selector, TextView summary, LinearLayout card,
                  CheckBox enabled, EditText start, EditText end, EditText limit) {
            this.index = index;
            this.selector = selector;
            this.summary = summary;
            this.card = card;
            this.enabled = enabled;
            this.start = start;
            this.end = end;
            this.limit = limit;
        }
    }

    private final List<DayEditor> dayEditors = new ArrayList<>(DAY_COUNT);
    private final List<TextView> avatarChoices = new ArrayList<>(AVATAR_VALUES.length);
    private LinearLayout identityStep;
    private LinearLayout scheduleStep;
    private TextView stepLabel;
    private EditText nameInput;
    private CheckBox profileEnabledInput;
    private CheckBox grantCurrentDeviceInput;
    private TextView saveButton;
    private TextView cancelButton;
    private TextView avatarNotice;
    private TextView avatarSelectionName;
    private SaveCallback saveCallback;
    private Runnable cancelCallback;
    private List<String> allowedGameKeys = Collections.emptyList();
    private String profileId = "";
    private String avatarValue = AVATAR_DEFAULT;
    private int expectedRevision;
    private boolean submitting;
    private int selectedDayIndex;
    private int afterEditorFocusId = View.NO_ID;

    ConsoleChildProfileEditor(Context context) {
        super(context);
        setVisibility(GONE);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void show(Draft initial, SaveCallback save, Runnable cancel) {
        Draft draft = initial == null ? Draft.newChild(0) : initial;
        profileId = draft.profileId;
        expectedRevision = draft.expectedRevision;
        allowedGameKeys = draft.allowedGameKeys;
        avatarValue = draft.avatar.length() == 0 ? AVATAR_DEFAULT : draft.avatar;
        saveCallback = save;
        cancelCallback = cancel;
        submitting = false;
        selectedDayIndex = 0;
        afterEditorFocusId = View.NO_ID;
        dayEditors.clear();
        removeAllViews();

        stepLabel = label(getContext().getString(R.string.console_child_profile_step_identity),
                16, 0xFF83CAE9, true);
        addView(stepLabel, params(ViewGroup.LayoutParams.MATCH_PARENT, dp(28), 0, 0, 0, dp(5)));

        identityStep = stepContainer();
        scheduleStep = stepContainer();
        addView(identityStep, params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        addView(scheduleStep, params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));

        identityStep.addView(fieldLabel(R.string.console_child_profile_name),
                params(ViewGroup.LayoutParams.MATCH_PARENT, dp(22), 0, 0, 0, dp(2)));
        nameInput = textInput(draft.name, false);
        nameInput.setContentDescription(getContext().getString(R.string.console_child_profile_name));
        identityStep.addView(nameInput, params(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), 0, 0, 0, dp(8)));

        identityStep.addView(fieldLabel(R.string.console_child_profile_avatar),
                params(ViewGroup.LayoutParams.MATCH_PARENT, dp(22), 0, 0, 0, dp(2)));
        identityStep.addView(label(getContext().getString(R.string.console_child_profile_avatar_hint),
                11, 0xFF9EADBD, false), params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(4)));
        addAvatarChoices(identityStep);

        profileEnabledInput = checkBox(R.string.console_child_profile_enabled);
        profileEnabledInput.setChecked(draft.enabled);
        identityStep.addView(profileEnabledInput, params(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), 0, 0, 0, dp(8)));
        if (profileId.isEmpty()) {
            grantCurrentDeviceInput = checkBox(R.string.console_child_profile_grant_device);
            grantCurrentDeviceInput.setChecked(draft.grantCurrentDevice);
            identityStep.addView(grantCurrentDeviceInput, params(ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(46), 0, 0, 0, dp(8)));
        } else {
            grantCurrentDeviceInput = null;
        }

        scheduleStep.addView(label(getContext().getString(
                R.string.console_child_profile_step_schedule), 16, 0xFF83CAE9, true),
                params(ViewGroup.LayoutParams.MATCH_PARENT, dp(28), 0, 0, 0, dp(2)));
        scheduleStep.addView(label(getContext().getString(R.string.console_child_profile_shared_info),
                12, 0xFF9EADBD, false), params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(8)));
        scheduleStep.addView(label(getContext().getString(R.string.console_child_profile_time_warning),
                12, 0xFFFFD38A, false), params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(8)));

        for (int index = 0; index < DAY_COUNT; index++) {
            Day day = draft.weekdays.get(index);
            DayEditor editor = addDay(scheduleStep, index, day);
            dayEditors.add(editor);
        }

        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        cancelButton = actionButton(R.string.console_cancel, false);
        saveButton = actionButton(R.string.console_child_profile_save, false);
        cancelButton.setOnClickListener(view -> {
            if (!submitting && cancelCallback != null) cancelCallback.run();
        });
        saveButton.setOnClickListener(view -> submit());
        actions.addView(cancelButton, params(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), dp(8), dp(8), 0, 0));
        actions.addView(saveButton, params(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), dp(8), dp(8), 0, 0));
        addView(actions, params(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), 0, 0, 0, 0));

        setVisibility(VISIBLE);
        identityStep.setVisibility(VISIBLE);
        scheduleStep.setVisibility(VISIBLE);
        wireFocusOrder();
        nameInput.post(nameInput::requestFocus);
    }

    Draft currentDraft() {
        return readDraft(false);
    }

    void setAfterEditorFocus(View view) {
        if (saveButton != null && view != null) {
            afterEditorFocusId = view.getId();
            saveButton.setNextFocusDownId(afterEditorFocusId);
            view.setNextFocusUpId(saveButton.getId());
        }
    }

    private LinearLayout stepContainer() {
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(VERTICAL);
        container.setClipChildren(false);
        container.setClipToPadding(false);
        return container;
    }

    private void addAvatarChoices(LinearLayout parent) {
        avatarChoices.clear();
        boolean known = isKnownAvatar(avatarValue);
        LinearLayout choices = new LinearLayout(getContext());
        choices.setGravity(Gravity.CENTER_VERTICAL);
        for (int index = 0; index < AVATAR_VALUES.length; index++) {
            String value = AVATAR_VALUES[index];
            TextView choice = label(getContext().getString(AVATAR_LABELS[index]),
                    24, Color.WHITE, true);
            choice.setText(value.equals(avatarValue) ? "✓" : AVATAR_SYMBOLS[index]);
            choice.setSelected(value.equals(avatarValue));
            choice.setId(View.generateViewId());
            choice.setGravity(Gravity.CENTER);
            choice.setFocusable(true);
            choice.setFocusableInTouchMode(true);
            choice.setClickable(true);
            choice.setSoundEffectsEnabled(false);
            choice.setMaxLines(2);
            choice.setEllipsize(android.text.TextUtils.TruncateAt.END);
            choice.setContentDescription(getContext().getString(AVATAR_LABELS[index]));
            choice.setTag("child.avatar:" + value);
            choice.setBackground(avatarBackground(value,
                    value.equals(avatarValue), false));
            choice.setOnClickListener(view -> {
                avatarValue = value;
                if (avatarNotice != null) avatarNotice.setVisibility(GONE);
                updateAvatarSelectionName(value);
                updateAvatarChoices();
                wireFocusOrder();
            });
            choice.setOnFocusChangeListener((view, focused) -> {
                choice.setBackground(avatarBackground(value,
                        value.equals(avatarValue), focused));
                updateAvatarSelectionName(avatarValue);
            });
            choices.addView(choice, params(0, dp(56), dp(2), 0, dp(2), 0, 1f));
            avatarChoices.add(choice);
        }
        parent.addView(choices, params(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 0, 0, 0, 0));
        avatarSelectionName = label(getContext().getString(
                AVATAR_LABELS[Math.max(0, avatarIndex(avatarValue))]),
                14, 0xFFC9D9E4, true);
        avatarSelectionName.setSingleLine(true);
        avatarSelectionName.setContentDescription(getContext().getString(
                R.string.console_child_profile_avatar));
        parent.addView(avatarSelectionName, params(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(24), dp(3), 0, 0, dp(3)));
        if (!known) {
            avatarNotice = label(getContext().getString(
                    R.string.console_child_profile_avatar_kept), 11, 0xFF9EADBD, false);
            avatarNotice.setPadding(dp(3), dp(2), dp(3), dp(4));
            parent.addView(avatarNotice, params(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(6)));
        } else {
            avatarNotice = null;
        }
    }

    private void updateAvatarChoices() {
        for (TextView choice : avatarChoices) {
            String value = String.valueOf(choice.getTag()).substring("child.avatar:".length());
            choice.setSelected(value.equals(avatarValue));
            choice.setText(value.equals(avatarValue) ? "✓" : AVATAR_SYMBOLS[avatarIndex(value)]);
            choice.setBackground(avatarBackground(value, value.equals(avatarValue), choice.hasFocus()));
        }
        updateAvatarSelectionName(avatarValue);
    }

    private int avatarIndex(String value) {
        for (int index = 0; index < AVATAR_VALUES.length; index++) {
            if (AVATAR_VALUES[index].equals(value)) return index;
        }
        return 0;
    }

    private void updateAvatarSelectionName(String value) {
        if (avatarSelectionName == null) return;
        avatarSelectionName.setText(getContext().getString(
                AVATAR_LABELS[avatarIndex(value)]));
    }

    static boolean isKnownAvatar(String value) {
        for (String known : AVATAR_VALUES) if (known.equals(value)) return true;
        return false;
    }

    private GradientDrawable avatarBackground(String value, boolean selected, boolean focused) {
        int color = avatarColor(value);
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? color : (color & 0xBFFFFFFF));
        background.setCornerRadius(dp(9));
        background.setStroke(dp(focused ? 2 : selected ? 2 : 1), focused
                ? 0xFFDDF5FF : selected ? 0xFF9FE4FF : 0x425E7189);
        return background;
    }

    static int avatarColor(String value) {
        int color;
        if (AVATAR_DEFAULT.equals(value)) color = 0xFF273642;
        else if ("purple".equals(value)) color = 0xFF553B6E;
        else if ("blue".equals(value)) color = 0xFF245D86;
        else if ("green".equals(value)) color = 0xFF266044;
        else if ("orange".equals(value)) color = 0xFF86562D;
        else color = 0xFF273642;
        return color;
    }

    private DayEditor addDay(LinearLayout parent, int index, Day day) {
        LinearLayout selector = new LinearLayout(getContext());
        selector.setOrientation(HORIZONTAL);
        selector.setGravity(Gravity.CENTER_VERTICAL);
        selector.setId(View.generateViewId());
        selector.setPadding(dp(12), 0, dp(12), 0);
        selector.setFocusable(true);
        selector.setFocusableInTouchMode(true);
        selector.setClickable(true);
        selector.setSoundEffectsEnabled(false);
        selector.setBackground(daySelectorBackground(false, index == selectedDayIndex));
        TextView dayName = label(getContext().getString(DAY_LABELS[index]),
                16, Color.WHITE, true);
        dayName.setGravity(Gravity.CENTER_VERTICAL);
        dayName.setSingleLine(true);
        dayName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selector.addView(dayName, params(0, dp(48), 0, 0, dp(8), 0, 1f));
        TextView summary = label(formatDaySummary(day), 14, 0xFFB8C9D5, false);
        summary.setSingleLine(true);
        summary.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        selector.addView(summary, params(0, dp(48), 0, 0, 0, 0, 1.35f));
        parent.addView(selector, params(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52), 0, 0, 0, dp(5)));

        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setPadding(dp(10), dp(5), dp(10), dp(7));
        card.setBackground(cardBackground());
        card.setVisibility(index == selectedDayIndex ? VISIBLE : GONE);
        LinearLayout.LayoutParams cardParams = params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(7));
        parent.addView(card, cardParams);

        LinearLayout heading = new LinearLayout(getContext());
        heading.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox dayEnabled = checkBox(R.string.console_child_day_enabled);
        dayEnabled.setChecked(day.enabled);
        heading.addView(dayEnabled, params(ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(48), 0, 0, 0, 0));
        card.addView(heading, params(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48), 0, 0, 0, dp(3)));

        LinearLayout values = new LinearLayout(getContext());
        values.setGravity(Gravity.CENTER_VERTICAL);
        EditText start = timeInput(day.startMinute);
        EditText end = timeInput(day.endMinute);
        EditText limit = limitInput(day.dailyLimitSeconds);
        values.addView(fieldGroup(R.string.console_child_day_start, start),
                params(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, dp(4), 0, 1f));
        values.addView(fieldGroup(R.string.console_child_day_end, end),
                params(0, ViewGroup.LayoutParams.WRAP_CONTENT, dp(4), 0, dp(4), 0, 1f));
        values.addView(fieldGroup(R.string.console_child_day_limit, limit),
                params(0, ViewGroup.LayoutParams.WRAP_CONTENT, dp(4), 0, 0, 0, 1f));
        card.addView(values, params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));

        DayEditor editor = new DayEditor(index, selector, summary, card,
                dayEnabled, start, end, limit);
        start.setOnClickListener(view -> showDayPicker(editor, start));
        end.setOnClickListener(view -> showDayPicker(editor, end));
        limit.setOnClickListener(view -> showDayPicker(editor, limit));
        start.setContentDescription(getContext().getString(R.string.console_child_day_start));
        end.setContentDescription(getContext().getString(R.string.console_child_day_end));
        limit.setContentDescription(getContext().getString(R.string.console_child_day_limit));
        selector.setOnClickListener(view -> selectDay(index));
        selector.setOnFocusChangeListener((view, focused) ->
                selector.setBackground(daySelectorBackground(focused, index == selectedDayIndex)));
        dayEnabled.setOnCheckedChangeListener((button, checked) -> {
            updateDayEnabled(editor, checked);
            updateDaySummary(editor);
            wireFocusOrder();
        });
        TextWatcher summaryWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start,
                                                     int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start,
                                                 int before, int count) { updateDaySummary(editor); }
            @Override public void afterTextChanged(Editable value) { }
        };
        start.addTextChangedListener(summaryWatcher);
        end.addTextChangedListener(summaryWatcher);
        limit.addTextChangedListener(summaryWatcher);
        updateDayEnabled(editor, day.enabled);
        return editor;
    }

    private void selectDay(int index) {
        selectDay(index, true);
    }

    private void selectDay(int index, boolean requestFocus) {
        if (index < 0 || index >= dayEditors.size()) return;
        selectedDayIndex = index;
        for (DayEditor editor : dayEditors) {
            boolean selected = editor.index == index;
            editor.card.setVisibility(selected ? VISIBLE : GONE);
            editor.selector.setBackground(daySelectorBackground(editor.selector.hasFocus(), selected));
        }
        wireFocusOrder();
        if (requestFocus) {
            DayEditor selected = dayEditors.get(index);
            selected.enabled.post(selected.enabled::requestFocus);
        }
    }

    private void updateDaySummary(DayEditor editor) {
        String start = editor.start.getText().toString().trim();
        String end = editor.end.getText().toString().trim();
        String limit = editor.limit.getText().toString().trim();
        if (!editor.enabled.isChecked()) {
            editor.summary.setText(R.string.console_child_day_summary_disabled);
        } else {
            editor.summary.setText(getContext().getString(
                    R.string.console_child_day_summary_enabled,
                    start.isEmpty() ? "00:00" : start,
                    end.isEmpty() ? "24:00" : end,
                    limit.isEmpty() ? "0" : limit));
        }
    }

    private String formatDaySummary(Day day) {
        if (!day.enabled) return getContext().getString(
                R.string.console_child_day_summary_disabled);
        return getContext().getString(R.string.console_child_day_summary_enabled,
                formatTime(day.startMinute), formatTime(day.endMinute),
                String.valueOf(Math.max(0, day.dailyLimitSeconds / 60)));
    }

    private GradientDrawable daySelectorBackground(boolean focused, boolean selected) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{focused ? 0xFF324653 : selected ? 0xAA294252 : 0x76202D38,
                        focused ? 0xFF172129 : 0x44101721});
        background.setCornerRadius(dp(9));
        background.setStroke(dp(focused || selected ? 2 : 1),
                focused ? 0xFFDDF5FF : selected ? 0xFF83CAE9 : 0x425E7189);
        return background;
    }

    private LinearLayout fieldGroup(int label, EditText input) {
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(VERTICAL);
        TextView caption = fieldLabel(label);
        caption.setLabelFor(input.getId());
        group.addView(caption, params(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(6)));
        group.addView(input, params(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0, 0, 0, 0));
        return group;
    }

    private void submit() {
        if (submitting) return;
        Draft draft = readDraft(true);
        if (draft == null) return;
        if (saveCallback == null) {
            finishFailed(null, false);
            return;
        }
        submitting = true;
        setFormEnabled(false);
        SaveCallback callback = saveCallback;
        try {
            callback.save(draft, new Completion() {
                @Override
                public void onSaved() {
                    finishSaved();
                }

                @Override
                public void onFailed(CharSequence message, boolean conflict) {
                    finishFailed(message, conflict);
                }
            });
        } catch (RuntimeException error) {
            finishFailed(null, false);
        }
    }

    private Draft readDraft(boolean reportErrors) {
        if (nameInput == null) return null;
        String name = nameInput.getText().toString().trim();
        if (name.length() == 0) {
            if (reportErrors) showError(getContext().getString(
                    R.string.console_child_profile_invalid_name), nameInput);
            return null;
        }
        ArrayList<Day> days = new ArrayList<>(DAY_COUNT);
        for (int index = 0; index < DAY_COUNT; index++) {
            DayEditor editor = dayEditors.get(index);
            if (!editor.enabled.isChecked()) {
                days.add(Day.disabled());
                continue;
            }
            Integer start = readTime(editor.start, false);
            if (start == null) {
                if (reportErrors) showDayError(index, getContext().getString(
                        R.string.console_child_profile_invalid_time), editor.start);
                return null;
            }
            Integer end = readTime(editor.end, false);
            if (end == null) {
                if (reportErrors) showDayError(index, getContext().getString(
                        R.string.console_child_profile_invalid_time), editor.end);
                return null;
            }
            Integer limitMinutes = readNumber(editor.limit, false);
            if (limitMinutes == null) {
                if (reportErrors) showDayError(index, getContext().getString(
                        R.string.console_child_profile_invalid_number), editor.limit);
                return null;
            }
            long limitSeconds = (long) limitMinutes * 60L;
            if (limitMinutes < 0 || limitMinutes > MAX_DAILY_LIMIT_MINUTES
                    || !validSchedule(start, end, limitMinutes)) {
                if (reportErrors) showDayError(index, getContext().getString(
                        R.string.console_child_profile_invalid_limit), editor.limit);
                return null;
            }
            if (start < 0 || start > MINUTES_PER_DAY
                    || end < 0 || end > MINUTES_PER_DAY
                    || (editor.enabled.isChecked() && start >= end)) {
                if (reportErrors) showDayError(index, getContext().getString(
                        R.string.console_child_profile_invalid_day,
                        getContext().getString(DAY_LABELS[index])), editor.start);
                return null;
            }
            days.add(new Day(editor.enabled.isChecked(), start, end, (int) limitSeconds));
        }
        return new Draft(profileId, name, avatarValue,
                profileEnabledInput.isChecked(), days, allowedGameKeys, expectedRevision,
                grantCurrentDeviceInput != null && grantCurrentDeviceInput.isChecked());
    }

    static boolean validSchedule(int start, int end, int limitMinutes) {
        return start >= 0 && start < end && end <= MINUTES_PER_DAY
                && limitMinutes >= 0 && limitMinutes <= end - start;
    }

    private Integer readTime(EditText input, boolean reportErrors) {
        String value = input.getText().toString().trim();
        if (value.length() == 0) return 0;
        String[] parts = value.split(":", -1);
        if (parts.length != 2) {
            if (reportErrors) showError(getContext().getString(
                    R.string.console_child_profile_invalid_time), input);
            return null;
        }
        try {
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            if (hours < 0 || hours > 24 || minutes < 0 || minutes > 59
                    || (hours == 24 && minutes != 0)) throw new NumberFormatException();
            return hours * 60 + minutes;
        } catch (NumberFormatException error) {
            if (reportErrors) showError(getContext().getString(
                    R.string.console_child_profile_invalid_time), input);
            return null;
        }
    }

    private Integer readNumber(EditText input, boolean reportErrors) {
        String value = input.getText().toString().trim();
        if (value.length() == 0) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            if (reportErrors) showError(getContext().getString(
                    R.string.console_child_profile_invalid_number), input);
            return null;
        }
    }

    private void finishSaved() {
        runOnUiThread(() -> {
            submitting = false;
            setFormEnabled(true);
        });
    }

    private void finishFailed(CharSequence message, boolean conflict) {
        runOnUiThread(() -> {
            submitting = false;
            setFormEnabled(true);
            CharSequence shown = message;
            if (shown == null || shown.length() == 0) {
                shown = getContext().getString(conflict
                        ? R.string.console_child_profile_save_conflict
                        : R.string.console_child_profile_save_failed);
            }
            ConsoleUiFeedback.makeText(getContext(), shown, Toast.LENGTH_LONG).show();
        });
    }

    private void runOnUiThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else post(action);
    }

    private void setFormEnabled(boolean enabled) {
        if (nameInput == null) return;
        nameInput.setEnabled(enabled);
        profileEnabledInput.setEnabled(enabled);
        if (grantCurrentDeviceInput != null) grantCurrentDeviceInput.setEnabled(enabled);
        for (TextView choice : avatarChoices) choice.setEnabled(enabled);
        for (DayEditor editor : dayEditors) {
            editor.selector.setEnabled(enabled);
            editor.enabled.setEnabled(enabled);
            updateDayEnabled(editor, editor.enabled.isChecked());
        }
        saveButton.setEnabled(enabled);
        cancelButton.setEnabled(enabled);
        saveButton.setAlpha(enabled ? 1f : .55f);
        cancelButton.setAlpha(enabled ? 1f : .55f);
        wireFocusOrder();
    }

    private void updateDayEnabled(DayEditor editor, boolean enabled) {
        boolean active = enabled && !submitting;
        editor.start.setEnabled(active);
        editor.end.setEnabled(active);
        editor.limit.setEnabled(active);
        float alpha = active ? 1f : .55f;
        editor.start.setAlpha(alpha);
        editor.end.setAlpha(alpha);
        editor.limit.setAlpha(alpha);
    }

    private void showError(CharSequence message, View focus) {
        ConsoleUiFeedback.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        if (focus != null) focus.requestFocus();
    }

    private void showDayError(int index, CharSequence message, View focus) {
        selectDay(index, false);
        ConsoleUiFeedback.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        if (focus != null) focus.post(focus::requestFocus);
    }

    private void wireFocusOrder() {
        ArrayList<View> order = new ArrayList<>();
        appendFocusable(order, nameInput);
        appendFocusable(order, avatarChoices.get(avatarIndex(avatarValue)));
        appendFocusable(order, profileEnabledInput);
        appendFocusable(order, grantCurrentDeviceInput);
        for (DayEditor editor : dayEditors) {
            appendFocusable(order, editor.selector);
            if (editor.index == selectedDayIndex) {
                appendFocusable(order, editor.enabled);
                appendFocusable(order, editor.start);
                appendFocusable(order, editor.end);
                appendFocusable(order, editor.limit);
                editor.start.setNextFocusRightId(editor.end.getId());
                editor.end.setNextFocusLeftId(editor.start.getId());
                editor.end.setNextFocusRightId(editor.limit.getId());
                editor.limit.setNextFocusLeftId(editor.end.getId());
            }
        }
        for (int index = 0; index < avatarChoices.size(); index++) {
            TextView choice = avatarChoices.get(index);
            choice.setNextFocusLeftId(avatarChoices.get(Math.max(0, index - 1)).getId());
            choice.setNextFocusRightId(avatarChoices.get(
                    Math.min(avatarChoices.size() - 1, index + 1)).getId());
        }
        appendFocusable(order, cancelButton);
        appendFocusable(order, saveButton);
        if (order.isEmpty()) return;
        for (int index = 0; index < order.size(); index++) {
            View current = order.get(index);
            current.setNextFocusDownId(order.get(Math.min(index + 1, order.size() - 1)).getId());
            current.setNextFocusUpId(order.get(Math.max(0, index - 1)).getId());
        }
        for (TextView choice : avatarChoices) {
            choice.setNextFocusUpId(nameInput.getId());
            choice.setNextFocusDownId(profileEnabledInput.getId());
        }
        cancelButton.setNextFocusLeftId(cancelButton.getId());
        cancelButton.setNextFocusRightId(saveButton.getId());
        saveButton.setNextFocusLeftId(cancelButton.getId());
        saveButton.setNextFocusRightId(saveButton.getId());
        if (afterEditorFocusId != View.NO_ID) {
            saveButton.setNextFocusDownId(afterEditorFocusId);
        }
    }

    private void appendFocusable(List<View> order, View view) {
        if (view != null && view.getVisibility() == VISIBLE
                && view.isEnabled() && view.isFocusable()) order.add(view);
    }

    private CheckBox checkBox(int textResource) {
        CheckBox box = new CheckBox(getContext());
        box.setId(View.generateViewId());
        box.setText(textResource);
        box.setTextColor(0xFFD7E4EA);
        box.setTextSize(12);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        box.setSoundEffectsEnabled(false);
        return box;
    }

    private EditText textInput(String value, boolean number) {
        EditText input = new EditText(getContext());
        input.setId(View.generateViewId());
        input.setSingleLine(true);
        input.setText(value == null ? "" : value);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x88788A96);
        input.setTextSize(14);
        input.setPadding(dp(11), 0, dp(11), 0);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setSoundEffectsEnabled(false);
        if (number) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setBackground(inputBackground(false));
        input.setOnFocusChangeListener((view, focused) -> input.setBackground(inputBackground(focused)));
        return input;
    }

    private EditText timeInput(int value) {
        EditText input = textInput(formatTime(value), false);
        input.setTextSize(16);
        input.setHint("HH:mm");
        input.setInputType(InputType.TYPE_CLASS_DATETIME
                | InputType.TYPE_DATETIME_VARIATION_TIME);
        input.setSelectAllOnFocus(true);
        input.setKeyListener(null);
        input.setCursorVisible(false);
        input.setFocusable(true);
        input.setClickable(true);
        return input;
    }

    private EditText limitInput(int seconds) {
        EditText input = textInput(String.valueOf(Math.max(0, seconds / 60)), true);
        input.setTextSize(16);
        input.setSelectAllOnFocus(true);
        input.setKeyListener(null);
        input.setCursorVisible(false);
        input.setFocusable(true);
        input.setClickable(true);
        return input;
    }

    private void showDayPicker(DayEditor editor, EditText target) {
        boolean duration = target == editor.limit;
        int start = readTime(editor.start, false);
        int end = readTime(editor.end, false);
        int min = target == editor.end ? start + 1 : 0;
        int max = duration ? end - start : target == editor.start ? end - 1 : MINUTES_PER_DAY;
        if (max < min) {
            showError(getContext().getString(R.string.console_child_profile_invalid_time), target);
            return;
        }
        int current = duration ? readNumber(target, false) : readTime(target, false);
        current = Math.max(min, Math.min(max, current));
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        NumberPicker hours = new NumberPicker(getContext());
        NumberPicker minutes = new NumberPicker(getContext());
        hours.setMinValue(min / 60);
        hours.setMaxValue(max / 60);
        hours.setValue(current / 60);
        hours.setWrapSelectorWheel(false);
        minutes.setWrapSelectorWheel(false);
        hours.setContentDescription(getContext().getString(R.string.console_child_picker_hours));
        minutes.setContentDescription(getContext().getString(R.string.console_child_picker_minutes));
        // Spinner-only input keeps keyboard entry from bypassing dependent bounds.
        hours.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        minutes.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        Runnable updateMinutes = () -> {
            int lower = hours.getValue() == min / 60 ? min % 60 : 0;
            int upper = hours.getValue() == max / 60 ? max % 60 : 59;
            int value = Math.max(lower, Math.min(upper, minutes.getValue()));
            minutes.setMinValue(0);
            minutes.setMaxValue(upper);
            minutes.setMinValue(lower);
            minutes.setValue(value);
        };
        updateMinutes.run();
        minutes.setValue(current % 60);
        hours.setOnValueChangedListener((picker, oldValue, newValue) -> updateMinutes.run());
        for (NumberPicker picker : new NumberPicker[]{hours, minutes}) {
            LinearLayout column = new LinearLayout(getContext());
            column.setOrientation(VERTICAL);
            TextView caption = label(picker.getContentDescription(), 14, Color.WHITE, true);
            caption.setGravity(Gravity.CENTER);
            column.addView(caption);
            column.addView(picker, params(dp(96), dp(160), 0, 0, 0, 0));
            row.addView(column, params(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(8), 0, dp(8), 0));
        }
        new AlertDialog.Builder(getContext())
                .setTitle(target.getContentDescription())
                .setMessage(R.string.console_child_picker_hint)
                .setView(row)
                .setNegativeButton(R.string.console_cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (submitting || !editor.enabled.isChecked()) return;
                    int value = hours.getValue() * 60 + minutes.getValue();
                    target.setText(duration ? String.valueOf(value) : formatTime(value));
                    int window = readTime(editor.end, false) - readTime(editor.start, false);
                    int limit = readNumber(editor.limit, false);
                    if (limit > window) {
                        editor.limit.setText(String.valueOf(window));
                        ConsoleUiFeedback.makeText(getContext(),
                                R.string.console_child_limit_adjusted, Toast.LENGTH_LONG).show();
                    }
                    updateDaySummary(editor);
                }).show();
    }

    private String formatTime(int minutes) {
        int value = Math.max(0, Math.min(MINUTES_PER_DAY, minutes));
        return String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60);
    }

    private TextView actionButton(int textResource, boolean destructive) {
        TextView button = label(getContext().getString(textResource), 14,
                destructive ? 0xFFFFA099 : 0xFFF0E9FF, true);
        button.setId(View.generateViewId());
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setClickable(true);
        button.setSoundEffectsEnabled(false);
        button.setMinWidth(dp(112));
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(actionBackground(false, destructive));
        button.setOnFocusChangeListener((view, focused) ->
                button.setBackground(actionBackground(focused, destructive)));
        return button;
    }

    private TextView fieldLabel(int textResource) {
        return label(getContext().getString(textResource).toUpperCase(Locale.ROOT),
                10, 0xFF8EA7C5, true);
    }

    private TextView label(CharSequence value, float size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0x76202D38, 0x44101721});
        background.setCornerRadius(dp(9));
        background.setStroke(dp(1), 0x425E7189);
        return background;
    }

    private GradientDrawable inputBackground(boolean focused) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF111820, 0xFF0A0F14});
        background.setCornerRadius(dp(8));
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFFBDEBFF : 0x66788A96);
        return background;
    }

    private GradientDrawable actionBackground(boolean focused, boolean destructive) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{focused ? 0xFF32444F : 0xC0232C33,
                        focused ? 0xFF172129 : 0xD0141A1F});
        background.setCornerRadius(dp(12));
        background.setStroke(dp(focused ? 2 : 1), focused
                ? (destructive ? 0xFFFF8F88 : 0xFFDDF5FF) : 0x55788A96);
        return background;
    }

    private LinearLayout.LayoutParams params(int width, int height,
                                             int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(width, height);
        result.setMargins(left, top, right, bottom);
        return result;
    }

    private LinearLayout.LayoutParams params(int width, int height,
                                             int left, int top, int right, int bottom,
                                             float weight) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(width, height, weight);
        result.setMargins(left, top, right, bottom);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
