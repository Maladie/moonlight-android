package com.limelight.console;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.preferences.AppPreferences;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * A game's stream overrides (mockup 18-stream-settings-game): {@link AppPreferences} under the
 * game's key, every row showing the inherited value until it is overridden.
 *
 * <p>Sunshine apps inherit from Settings › Streaming. Playnite games and Quick Launch items
 * ("inherit" keys, passed to the stream as its second key) inherit from their Sunshine app's
 * overrides and then from Settings › Streaming, as {@link AppPreferences#getEffectivePreferences}
 * resolves them at launch.
 */
final class ConsoleAppStreamSettings {
    private final Activity activity;
    private final ConsoleSettingsScreen screen;
    private String appKey;
    private boolean inherit;
    private AppPreferences.AppSettings settings;
    private PreferenceConfiguration inherited;

    ConsoleAppStreamSettings(Activity activity, ConsoleSettingsScreen screen) {
        this.activity = activity;
        this.screen = screen;
    }

    /**
     * Opens the overrides of {@code appKey}. {@code parentKey} is the Sunshine app a Playnite
     * game launches through (null when unknown); Quick Launch keys ("host:app:id") name theirs.
     */
    void open(String crumb, String appKey, String appName, boolean inheritAppSettings,
              String parentKey) {
        this.appKey = appKey;
        String[] parts = appKey.split(":");
        // Quick Launch keys are host:app:timestamp, their app is host:app. A Playnite game
        // (playnite:host:game) without a known Sunshine app inherits the global settings.
        boolean quickLaunch = !inheritAppSettings && parts.length == 3;
        inherit = inheritAppSettings || quickLaunch;
        if (quickLaunch) parentKey = parts[0] + ":" + parts[1];
        settings = AppPreferences.getAppSettings(activity, appKey);
        // What the stream gets, global overrides (e.g. the bitrate override) included.
        inherited = parentKey != null
                ? AppPreferences.getEffectivePreferences(activity, parentKey)
                : AppPreferences.applyPreferenceOverrides(activity,
                        PreferenceConfiguration.readPreferences(activity));
        screen.show(crumb, appName, s(R.string.console_app_stream_eyebrow),
                Collections.singletonList(new ConsoleSettingsScreen.Category(
                        s(R.string.console_app_stream_category),
                        s(inherit ? R.string.console_app_stream_details_inherited
                                : R.string.console_app_stream_details_global), this::rows)), 0);
    }

    private View[] rows(ConsolePanel panel) {
        LinearLayout useGlobal = panel.toggle(s(inherit ? R.string.console_app_stream_use_inherited
                : R.string.console_app_stream_use_global), settings.useGlobalSettings);
        useGlobal.setOnClickListener(view -> {
            settings.useGlobalSettings = !settings.useGlobalSettings;
            save();
            screen.refresh();
        });
        String[] resolutionNames = array(R.array.resolution_names);
        String[] resolutionValues = array(R.array.resolution_values);
        String[] fpsNames = array(R.array.fps_names);
        String[] fpsValues = array(R.array.fps_values);
        String[] pacingNames = array(R.array.video_frame_pacing_names);
        String[] pacingValues = array(R.array.video_frame_pacing_values);
        String inheritedResolution = inherited.width + "x" + inherited.height;
        String inheritedPacing = pacingNames[Math.max(0,
                Math.min(pacingNames.length - 1, inherited.framePacing))];
        String[] onOff = {s(R.string.console_stream_on), s(R.string.console_stream_off)};

        LinearLayout resolution = value(panel, R.string.console_app_stream_resolution,
                settings.resolution == null ? inheritedLabel('f',
                        resolutionLabel(resolutionNames, resolutionValues, inheritedResolution))
                        : resolutionLabel(resolutionNames, resolutionValues, settings.resolution));
        resolution.setOnClickListener(view -> chooseStandardOrCustom(
                R.string.console_app_stream_resolution, resolutionNames, resolutionValues,
                settings.resolution, inheritedLabel('f', resolutionLabel(resolutionNames,
                        resolutionValues, inheritedResolution)),
                chosen -> settings.resolution = chosen, this::customResolution));
        String fpsValue = settings.fps > 0 ? String.valueOf(settings.fps) : null;
        LinearLayout fps = value(panel, R.string.console_stream_fps, fpsValue == null
                ? inheritedLabel('f', fpsLabel(fpsNames, fpsValues, inherited.fps))
                : fpsLabel(fpsNames, fpsValues, settings.fps));
        fps.setOnClickListener(view -> chooseStandardOrCustom(R.string.console_stream_fps,
                fpsNames, fpsValues, fpsValue,
                inheritedLabel('f', fpsLabel(fpsNames, fpsValues, inherited.fps)),
                chosen -> settings.fps = chosen == null ? 0 : Integer.parseInt(chosen),
                this::customFps));
        LinearLayout bitrate = value(panel, R.string.console_app_stream_bitrate,
                settings.bitrate > 0 ? megabits(settings.bitrate)
                        : inheritedLabel('m', megabits(inherited.bitrate)));
        bitrate.setOnClickListener(view -> chooseBitrate());
        LinearLayout pacing = value(panel, R.string.console_stream_frame_pacing,
                settings.framePacing == null ? inheritedLabel('n', inheritedPacing)
                        : ConsoleStreamSettings.label(pacingNames, pacingValues, settings.framePacing));
        pacing.setOnClickListener(view -> chooseOption(R.string.console_stream_frame_pacing,
                pacingNames, pacingValues, settings.framePacing, inheritedLabel('n', inheritedPacing),
                chosen -> settings.framePacing = chosen));
        LinearLayout refresh = value(panel, R.string.console_stream_actual_refresh,
                settings.actualDisplayRefreshRate > 0
                        ? activity.getString(R.string.console_stream_hz,
                        rate(settings.actualDisplayRefreshRate)) : inheritedRefreshLabel());
        refresh.setOnClickListener(view -> chooseRefreshRate());
        String[] booleans = {"true", "false"};
        LinearLayout hdr = value(panel, R.string.console_stream_hdr, settings.enableHdr == null
                ? inheritedLabel('n', onOff[inherited.enableHdr ? 0 : 1])
                : ConsoleStreamSettings.label(onOff, booleans, settings.enableHdr));
        hdr.setOnClickListener(view -> chooseOption(R.string.console_stream_hdr, onOff, booleans,
                settings.enableHdr, inheritedLabel('n', onOff[inherited.enableHdr ? 0 : 1]),
                chosen -> settings.enableHdr = chosen));
        LinearLayout stats = value(panel, R.string.console_stream_perf_overlay,
                settings.enablePerfOverlay == null
                        ? inheritedLabel('n', onOff[inherited.enablePerfOverlay ? 0 : 1])
                        : ConsoleStreamSettings.label(onOff, booleans, settings.enablePerfOverlay));
        stats.setOnClickListener(view -> chooseOption(R.string.console_stream_perf_overlay, onOff,
                booleans, settings.enablePerfOverlay,
                inheritedLabel('n', onOff[inherited.enablePerfOverlay ? 0 : 1]),
                chosen -> settings.enablePerfOverlay = chosen));

        View[] overrides = {resolution, fps, bitrate, pacing, refresh, hdr, stats};
        for (View row : overrides) {
            // The saved overrides stay. A Sunshine app ignores them while "use global" is on; a
            // Quick Launch item or Playnite game still applies them over its Sunshine app's own
            // overrides, and ignores them only when that app uses the global settings too
            // (AppPreferences.getEffectivePreferences).
            row.setEnabled(!settings.useGlobalSettings);
            row.setFocusable(!settings.useGlobalSettings);
        }
        List<View> rows = new ArrayList<>();
        rows.add(useGlobal);
        rows.add(panel.separator());
        rows.addAll(Arrays.asList(overrides));
        return rows.toArray(new View[0]);
    }

    private LinearLayout value(ConsolePanel panel, int label, String value) {
        return panel.value(s(label), value);
    }

    /** Inherited, the standard values, a saved custom value and "Custom…". */
    private void chooseStandardOrCustom(int title, String[] names, String[] values, String current,
                                        String inheritedLabel, Consumer<String> apply,
                                        Runnable custom) {
        List<CharSequence> labels = new ArrayList<>();
        List<String> choices = new ArrayList<>();
        labels.add(inheritedLabel);
        choices.add(null);
        labels.addAll(Arrays.asList(names));
        choices.addAll(Arrays.asList(values));
        if (current != null && !choices.contains(current)) {
            labels.add(activity.getString(R.string.console_stream_custom_saved,
                    title == R.string.console_stream_fps ? current + " FPS" : times(current)));
            choices.add(current);
        }
        labels.add(s(R.string.console_stream_custom));
        screen.showChoices(s(title), "", labels.toArray(new CharSequence[0]),
                choices.indexOf(current), index -> {
                    if (index == choices.size()) {
                        custom.run();
                        return;
                    }
                    apply.accept(choices.get(index));
                    save();
                });
    }

    private void chooseOption(int title, String[] names, String[] values, String current,
                              String inheritedLabel, Consumer<String> apply) {
        List<CharSequence> labels = new ArrayList<>();
        labels.add(inheritedLabel);
        labels.addAll(Arrays.asList(names));
        int chosen = current == null ? 0 : Arrays.asList(values).indexOf(current) + 1;
        screen.showChoices(s(title), "", labels.toArray(new CharSequence[0]), chosen, index -> {
            apply.accept(index == 0 ? null : values[index - 1]);
            save();
        });
    }

    private void customResolution() {
        ConsolePanel panel = screen.panel();
        String[] parts = settings.resolution == null ? new String[0] : settings.resolution.split("x");
        EditText width = panel.numberField(s(R.string.console_stream_width),
                parts.length == 2 ? parts[0] : "", false);
        EditText height = panel.numberField(s(R.string.console_stream_height),
                parts.length == 2 ? parts[1] : "", false);
        TextView save = panel.action(s(R.string.console_stream_save));
        save.setOnClickListener(view -> {
            int w = (int) ConsoleStreamSettings.parsePositive(width.getText().toString());
            int h = (int) ConsoleStreamSettings.parsePositive(height.getText().toString());
            if (!validResolution(w, h)) {
                height.setError(s(R.string.app_stream_invalid_resolution));
                return;
            }
            settings.resolution = w + "x" + h;
            save();
            screen.closePage();
        });
        screen.showPage(s(R.string.console_stream_custom), "", new View[]{
                panel.section(s(R.string.console_stream_width)), width,
                panel.section(s(R.string.console_stream_height)), height, save}, 1);
    }

    static boolean validResolution(int width, int height) {
        return width > 0 && height > 0 && width <= 7680 && height <= 4320;
    }

    private void customFps() {
        ConsolePanel panel = screen.panel();
        EditText field = panel.numberField(s(R.string.console_stream_fps),
                settings.fps > 0 ? String.valueOf(settings.fps) : "", false);
        TextView save = panel.action(s(R.string.console_stream_save));
        save.setOnClickListener(view -> {
            String text = field.getText().toString().trim();
            int fps;
            try {
                fps = Integer.parseInt(text);
            } catch (NumberFormatException invalid) {
                fps = 0;
            }
            if (fps <= 0) {
                field.setError(s(R.string.app_stream_invalid_fps));
                return;
            }
            settings.fps = fps;
            save();
            screen.closePage();
        });
        screen.showPage(s(R.string.console_stream_custom), "", new View[]{
                panel.section(s(R.string.console_stream_fps)), field, save}, 1);
    }

    private void chooseBitrate() {
        ConsolePanel panel = screen.panel();
        TextView inheritedRow = screen.choiceRow(inheritedLabel('m',
                megabits(inherited.bitrate)), settings.bitrate <= 0);
        inheritedRow.setOnClickListener(view -> {
            settings.bitrate = 0;
            save();
            screen.closePage();
        });
        int initial = settings.bitrate > 0 ? settings.bitrate : inherited.bitrate;
        // A higher value saved before the stepper had a limit stays until it is changed.
        LinearLayout stepper = panel.stepper(s(R.string.console_app_stream_bitrate), initial,
                ConsoleStreamSettings.BITRATE_MIN_KBPS,
                Math.max(ConsoleStreamSettings.BITRATE_MAX_KBPS, initial),
                ConsoleStreamSettings.BITRATE_STEP_KBPS, this::megabits,
                megabits(ConsoleStreamSettings.BITRATE_STEP_KBPS), new int[0], kbps -> {
                    settings.bitrate = kbps;
                    save();
                });
        screen.showPage(s(R.string.console_app_stream_bitrate), "",
                new View[]{inheritedRow, stepper}, settings.bitrate > 0 ? 1 : 0);
    }

    private void chooseRefreshRate() {
        ConsolePanel panel = screen.panel();
        TextView inheritedRow = screen.choiceRow(inheritedRefreshLabel(),
                settings.actualDisplayRefreshRate <= 0);
        inheritedRow.setOnClickListener(view -> {
            settings.actualDisplayRefreshRate = 0;
            save();
            screen.closePage();
        });
        EditText field = panel.numberField(s(R.string.console_stream_actual_refresh),
                settings.actualDisplayRefreshRate > 0 ? rate(settings.actualDisplayRefreshRate)
                        : "", true);
        TextView save = panel.action(s(R.string.console_stream_save));
        save.setOnClickListener(view -> {
            double value = ConsoleStreamSettings.parsePositive(field.getText().toString());
            if (value <= 0) {
                field.setError(s(R.string.console_stream_invalid_refresh));
                return;
            }
            settings.actualDisplayRefreshRate = value;
            save();
            screen.closePage();
        });
        screen.showPage(s(R.string.console_stream_actual_refresh),
                s(R.string.summary_actual_display_refresh_rate), new View[]{inheritedRow,
                        panel.section(s(R.string.console_stream_custom_hz)), field, save}, 0);
    }

    private String megabits(int kbps) {
        return ConsoleStreamSettings.megabits(activity, kbps);
    }

    private void save() {
        AppPreferences.saveAppSettings(activity, appKey, settings);
    }

    private String inheritedRefreshLabel() {
        String rate = inherited.actualDisplayRefreshRate;
        if (ConsoleStreamSettings.isBlankRate(rate)) {
            return s(inherit ? R.string.console_app_stream_inherited_plain
                    : R.string.console_app_stream_global_plain);
        }
        return inheritedLabel('f', activity.getString(R.string.console_stream_hz, rate.trim()));
    }

    /** "Globalna (1080p)": Polish needs the grammatical gender of the row (f, m, n). */
    private String inheritedLabel(char gender, String value) {
        int resource;
        switch (gender) {
            case 'f': resource = inherit ? R.string.console_app_stream_inherited_f
                    : R.string.console_app_stream_global_f; break;
            case 'm': resource = inherit ? R.string.console_app_stream_inherited_m
                    : R.string.console_app_stream_global_m; break;
            default: resource = inherit ? R.string.console_app_stream_inherited_n
                    : R.string.console_app_stream_global_n; break;
        }
        return activity.getString(resource, value);
    }

    private static String resolutionLabel(String[] names, String[] values, String resolution) {
        int index = Arrays.asList(values).indexOf(resolution);
        return index >= 0 ? names[index] : times(resolution);
    }

    private static String fpsLabel(String[] names, String[] values, int fps) {
        int index = Arrays.asList(values).indexOf(String.valueOf(fps));
        return index >= 0 ? names[index] : fps + " FPS";
    }

    private static String times(String resolution) {
        return resolution.replace('x', '×');
    }

    private static String rate(double rate) {
        return rate == Math.rint(rate) ? String.valueOf((long) rate) : String.valueOf(rate);
    }

    private String[] array(int resource) {
        return activity.getResources().getStringArray(resource);
    }

    private String s(int resource) {
        return activity.getString(resource);
    }
}
