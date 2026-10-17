package com.limelight.console;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * One child profile on the settings screen (mockups 20c-20e): "Profile" holds the name, avatar
 * and switches, "Play time" a row per weekday that opens the day's page. Times and limits step
 * by {@link #STEP_MINUTES} within bounds that follow the other values, so the draft is always
 * valid. Back from the screen hands the draft to {@link Host#closed}; the caller owns
 * authorization and persistence.
 */
final class ConsoleChildProfileEditor {
    static final int MINUTES_PER_DAY = 1440;
    static final int STEP_MINUTES = 5;
    private static final int DAY_COUNT = 7;
    private static final int WORKDAYS = 5;
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
    private static final int[] DAY_LABELS = {
            R.string.console_child_day_mon,
            R.string.console_child_day_tue,
            R.string.console_child_day_wed,
            R.string.console_child_day_thu,
            R.string.console_child_day_fri,
            R.string.console_child_day_sat,
            R.string.console_child_day_sun
    };

    interface Host {
        /** Asks for the child's name; {@code onName} gets a non-empty one. */
        void editName(String current, Consumer<String> onName);
        /** Asks to delete the profile being edited. */
        void delete(Draft draft);
        /** Back closed the editor; {@code changed}: the draft differs from the saved profile. */
        void closed(Draft draft, boolean changed);
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

        int limitMinutes() {
            return dailyLimitSeconds / 60;
        }

        int windowMinutes() {
            return endMinute - startMinute;
        }

        /** Whole-window windows count as "no limit"; a new day starts there. */
        boolean unlimited() {
            return limitMinutes() >= windowMinutes();
        }

        /**
         * The day with the window moved to {@code start}-{@code end}: a limit that covered the
         * old window covers the new one, any other is cut to fit it.
         */
        Day withWindow(int start, int end) {
            int limit = unlimited() ? end - start : Math.min(limitMinutes(), end - start);
            return new Day(enabled, start, end, Math.max(STEP_MINUTES, limit) * 60);
        }

        Day withEnabled(boolean allowed) {
            // A day without time (the host's disabled default) opens without a limit.
            return new Day(allowed, startMinute, endMinute,
                    dailyLimitSeconds == 0 ? windowMinutes() * 60 : dailyLimitSeconds);
        }

        Day withLimit(int minutes) {
            return new Day(enabled, startMinute, endMinute, minutes * 60);
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

        static Draft newChild(int expectedRevision) {
            ArrayList<Day> days = new ArrayList<>(DAY_COUNT);
            for (int index = 0; index < DAY_COUNT; index++) days.add(Day.disabled());
            return new Draft("", "", AVATAR_DEFAULT, true, days,
                    Collections.emptyList(), expectedRevision, true);
        }

        /** Every saved field, for change detection and the mutation's request ID. */
        String signature() {
            StringBuilder signature = new StringBuilder(profileId).append('|')
                    .append(expectedRevision).append('|').append(name).append('|')
                    .append(avatar).append('|').append(enabled).append('|')
                    .append(grantCurrentDevice);
            for (Day day : weekdays) {
                signature.append('|').append(day.enabled).append(':').append(day.startMinute)
                        .append(':').append(day.endMinute).append(':')
                        .append(day.dailyLimitSeconds);
            }
            return signature.toString();
        }
    }

    private final Activity activity;
    private final ConsoleSettingsScreen screen;
    private final Host host;
    private final Day[] days = new Day[DAY_COUNT];
    private String profileId;
    private String name;
    private String avatar;
    private boolean enabled;
    private boolean grantCurrentDevice;
    private int revision;
    private String savedSignature;

    ConsoleChildProfileEditor(Activity activity, ConsoleSettingsScreen screen, Host host) {
        this.activity = activity;
        this.screen = screen;
        this.host = host;
    }

    /** Opens {@code draft}; {@code unsaved}: it already differs from the saved profile. */
    void open(Draft draft, boolean unsaved) {
        profileId = draft.profileId;
        name = draft.name;
        avatar = isKnownAvatar(draft.avatar) ? draft.avatar : AVATAR_DEFAULT;
        enabled = draft.enabled;
        grantCurrentDevice = draft.grantCurrentDevice;
        revision = draft.expectedRevision;
        for (int index = 0; index < DAY_COUNT; index++) days[index] = draft.weekdays.get(index);
        savedSignature = unsaved ? "" : draft().signature();
        screen.show(s(R.string.console_child_management_title), title(),
                s(R.string.console_child_profile_title), Arrays.asList(
                        new ConsoleSettingsScreen.Category(
                                s(R.string.console_child_profile_category),
                                s(R.string.console_child_profile_shared_info), this::profileRows),
                        new ConsoleSettingsScreen.Category(
                                s(R.string.console_child_profile_step_schedule),
                                s(R.string.console_child_profile_time_warning), this::weekRows)),
                0, () -> {
                    Draft current = draft();
                    host.closed(current, !current.signature().equals(savedSignature));
                });
        if (name.isEmpty()) editName();
    }

    Draft draft() {
        return new Draft(profileId, name, avatar, enabled, Arrays.asList(days),
                Collections.emptyList(), revision, grantCurrentDevice);
    }

    private String title() {
        return name.isEmpty() ? s(R.string.console_child_management_add) : name;
    }

    private View[] profileRows(ConsolePanel panel) {
        List<View> rows = new ArrayList<>();
        LinearLayout nameRow = panel.value(s(R.string.console_child_profile_name),
                name.isEmpty() ? s(R.string.console_child_profile_name_missing) : name);
        nameRow.setOnClickListener(view -> editName());
        rows.add(nameRow);

        LinearLayout[] avatarRow = new LinearLayout[1];
        avatarRow[0] = panel.stepper(s(R.string.console_child_profile_avatar),
                avatarIndex(avatar), 0, AVATAR_VALUES.length - 1, 1,
                index -> s(AVATAR_LABELS[index]), s(R.string.console_child_avatar_step), null,
                index -> {
                    avatar = AVATAR_VALUES[index];
                    paintAvatar(label(avatarRow[0]), avatar);
                });
        paintAvatar(label(avatarRow[0]), avatar);
        rows.add(avatarRow[0]);

        LinearLayout enabledRow = panel.toggle(s(R.string.console_child_profile_enabled), enabled);
        enabledRow.setOnClickListener(view -> {
            enabled = !enabled;
            panel.setChecked(enabledRow, enabled);
        });
        rows.add(enabledRow);
        if (profileId.isEmpty()) {
            LinearLayout device = panel.toggle(s(R.string.console_child_profile_grant_device),
                    grantCurrentDevice);
            device.setOnClickListener(view -> {
                grantCurrentDevice = !grantCurrentDevice;
                panel.setChecked(device, grantCurrentDevice);
            });
            rows.add(device);
        } else {
            TextView delete = panel.destructive(panel.action(activity.getString(
                    R.string.console_child_management_delete_named, title())));
            delete.setOnClickListener(view -> host.delete(draft()));
            rows.add(delete);
        }
        return rows.toArray(new View[0]);
    }

    private static TextView label(ViewGroup row) {
        return (TextView) row.getChildAt(0);
    }

    /** The avatar's colour as a dot before a row's label. */
    static void paintAvatar(TextView label, String avatar) {
        float density = label.getResources().getDisplayMetrics().density;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(avatarColor(avatar));
        dot.setStroke(Math.round(density), 0x66FFFFFF);
        int size = Math.round(14 * density);
        dot.setSize(size, size);
        Drawable[] kept = label.getCompoundDrawablesRelative();
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, kept[1], kept[2], kept[3]);
        label.setCompoundDrawablePadding(Math.round(ConsolePanel.ICON_GAP_DP * density));
    }

    private View[] weekRows(ConsolePanel panel) {
        View[] rows = new View[DAY_COUNT];
        for (int index = 0; index < DAY_COUNT; index++) {
            int day = index;
            LinearLayout row = panel.value(s(DAY_LABELS[index]), summary(days[index]));
            row.setOnClickListener(view -> showDay(day, 0));
            rows[index] = row;
        }
        return rows;
    }

    /**
     * One day's page. A step moves the other rows' ranges in place (a rebuild flickers the whole
     * card); only switching the day on or off rebuilds it, since rows appear or go.
     */
    private void showDay(int index, int focus) {
        ConsolePanel panel = screen.panel();
        Day day = days[index];
        List<View> rows = new ArrayList<>();
        LinearLayout allowed = panel.toggle(s(R.string.console_child_day_enabled), day.enabled);
        allowed.setOnClickListener(view -> {
            days[index] = days[index].withEnabled(!days[index].enabled);
            showDay(index, 0);
        });
        rows.add(allowed);
        if (day.enabled) {
            String step = activity.getString(R.string.console_child_step, STEP_MINUTES);
            LinearLayout[] steppers = new LinearLayout[3];
            Runnable bound = () -> {
                Day current = days[index];
                int window = current.windowMinutes();
                ConsolePanel.setStepper(steppers[0], 0, current.endMinute - STEP_MINUTES,
                        current.startMinute);
                ConsolePanel.setStepper(steppers[1], current.startMinute + STEP_MINUTES,
                        MINUTES_PER_DAY, current.endMinute);
                ConsolePanel.setStepper(steppers[2], Math.min(STEP_MINUTES, window), window,
                        Math.min(current.limitMinutes(), window));
            };
            steppers[0] = panel.stepper(s(R.string.console_child_day_start), day.startMinute,
                    0, day.endMinute - STEP_MINUTES, STEP_MINUTES,
                    ConsoleChildProfileEditor::clock, step, new int[0], start -> {
                        days[index] = days[index].withWindow(start, days[index].endMinute);
                        bound.run();
                    });
            steppers[1] = panel.stepper(s(R.string.console_child_day_end), day.endMinute,
                    day.startMinute + STEP_MINUTES, MINUTES_PER_DAY, STEP_MINUTES,
                    ConsoleChildProfileEditor::clock, step, new int[0], end -> {
                        days[index] = days[index].withWindow(days[index].startMinute, end);
                        bound.run();
                    });
            int window = day.windowMinutes();
            steppers[2] = panel.stepper(s(R.string.console_child_day_limit),
                    Math.min(day.limitMinutes(), window), Math.min(STEP_MINUTES, window), window,
                    STEP_MINUTES, minutes -> minutes >= days[index].windowMinutes()
                            ? s(R.string.console_child_day_unlimited) : duration(minutes),
                    step, new int[0], limit -> days[index] = days[index].withLimit(limit));
            Collections.addAll(rows, steppers);
        }
        rows.add(panel.section(s(R.string.console_child_day_copy)));
        TextView workdays = panel.action(s(R.string.console_child_day_copy_workdays));
        workdays.setOnClickListener(view -> copy(index, WORKDAYS));
        rows.add(workdays);
        TextView week = panel.action(s(R.string.console_child_day_copy_week));
        week.setOnClickListener(view -> copy(index, DAY_COUNT));
        rows.add(week);
        screen.showPage(s(DAY_LABELS[index]), s(R.string.console_child_day_details),
                rows.toArray(new View[0]),
                focus, s(R.string.console_child_profile_step_schedule));
    }

    private void copy(int from, int through) {
        for (int index = 0; index < through; index++) days[index] = days[from];
        ConsoleUiFeedback.makeText(activity, R.string.console_child_day_copied,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void editName() {
        host.editName(name, value -> {
            name = value;
            screen.setTitle(title());
            screen.refresh();
        });
    }

    private String summary(Day day) {
        if (!day.enabled) return s(R.string.console_child_day_summary_disabled);
        return activity.getString(R.string.console_child_day_summary_enabled,
                clock(day.startMinute), clock(day.endMinute), day.unlimited()
                        ? s(R.string.console_child_day_unlimited) : duration(day.limitMinutes()));
    }

    /** Today's plan for the list of children, e.g. "Today 16:00-19:00 · 1 h". */
    static String todaySummary(android.content.Context context, Draft draft, int today) {
        if (!draft.enabled) return context.getString(R.string.console_child_profile_disabled);
        Day day = draft.weekdays.get(today);
        if (!day.enabled) return context.getString(R.string.console_child_day_today_disabled);
        return context.getString(R.string.console_child_day_today, clock(day.startMinute),
                clock(day.endMinute), day.unlimited()
                        ? context.getString(R.string.console_child_day_unlimited)
                        : duration(context, day.limitMinutes()));
    }

    static String clock(int minutes) {
        int value = Math.max(0, Math.min(MINUTES_PER_DAY, minutes));
        return String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60);
    }

    private String duration(int minutes) {
        return duration(activity, minutes);
    }

    private static String duration(android.content.Context context, int minutes) {
        return PlaynitePlaytimeFormatter.minutes(minutes, new PlaynitePlaytimeFormatter.Labels("",
                context.getString(R.string.playnite_hours_short),
                context.getString(R.string.playnite_minutes_short)));
    }

    static boolean validSchedule(int start, int end, int limitMinutes) {
        return start >= 0 && start < end && end <= MINUTES_PER_DAY
                && limitMinutes >= 0 && limitMinutes <= end - start;
    }

    static boolean isKnownAvatar(String value) {
        for (String known : AVATAR_VALUES) if (known.equals(value)) return true;
        return false;
    }

    private static int avatarIndex(String value) {
        for (int index = 0; index < AVATAR_VALUES.length; index++) {
            if (AVATAR_VALUES[index].equals(value)) return index;
        }
        return 0;
    }

    static int avatarColor(String value) {
        if ("purple".equals(value)) return 0xFF553B6E;
        if ("blue".equals(value)) return 0xFF245D86;
        if ("green".equals(value)) return 0xFF266044;
        if ("orange".equals(value)) return 0xFF86562D;
        return 0xFF273642;
    }

    private String s(int resource) {
        return activity.getString(resource);
    }
}
