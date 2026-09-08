package com.limelight.preferences;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.limelight.R;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.List;

public class AppStreamSettings extends Activity {
    public static final String EXTRA_APP_KEY = "AppKey";
    public static final String EXTRA_APP_NAME = "AppName";
    public static final String EXTRA_INHERIT_APP_SETTINGS = "InheritAppSettings";

    private String appKey;
    private String appName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        appKey = getIntent().getStringExtra(EXTRA_APP_KEY);
        appName = getIntent().getStringExtra(EXTRA_APP_NAME);

        if (appKey == null || appName == null) {
            finish();
            return;
        }

        UiHelper.setLocale(this);
        setContentView(R.layout.activity_stream_settings);
        setTitle(getString(R.string.app_stream_settings_title, appName));

        getFragmentManager().beginTransaction().replace(
                R.id.stream_settings, new AppSettingsFragment()
        ).commitAllowingStateLoss();

        UiHelper.notifyNewRootView(this);
    }

    public static class AppSettingsFragment extends PreferenceFragment {
        private static final String CUSTOM_VALUE = "__custom__";

        private String currentResolution;
        private String currentFps;
        private boolean isQuickLaunch;

        private void setupStandardPreference(ListPreference pref, int entriesResourceId,
                                             int valuesResourceId, String currentValue,
                                             boolean fps) {
            String[] standardEntries = getResources().getStringArray(entriesResourceId);
            String[] standardValues = getResources().getStringArray(valuesResourceId);
            List<CharSequence> entries = new ArrayList<>();
            List<CharSequence> values = new ArrayList<>();

            entries.add(getString(isQuickLaunch
                    ? R.string.app_stream_use_app_value
                    : R.string.app_stream_use_global_value));
            values.add("");
            for (int i = 0; i < standardEntries.length; i++) {
                entries.add(standardEntries[i]);
                values.add(standardValues[i]);
            }

            boolean standard = currentValue == null || currentValue.isEmpty();
            for (String value : standardValues) {
                standard |= value.equals(currentValue);
            }
            if (!standard) {
                String displayValue = fps ? currentValue + " FPS" : currentValue;
                entries.add(getString(R.string.app_stream_saved_custom_value, displayValue));
                values.add(currentValue);
            }

            entries.add(getString(R.string.app_stream_custom_value));
            values.add(CUSTOM_VALUE);
            pref.setEntries(entries.toArray(new CharSequence[0]));
            pref.setEntryValues(values.toArray(new CharSequence[0]));
            pref.setValue(currentValue == null ? "" : currentValue);
        }

        private void setupFramePacingPreference(ListPreference framePacingPref, String currentValue, boolean isQuickLaunch) {
            // Get original arrays from resources
            String[] originalEntries = getResources().getStringArray(R.array.video_frame_pacing_names);
            String[] originalValues = getResources().getStringArray(R.array.video_frame_pacing_values);

            // Create new arrays with default option at the beginning
            String[] newEntries = new String[originalEntries.length + 1];
            String[] newValues = new String[originalValues.length + 1];

            // Use different label depending on whether this is a quick launch item or app
            newEntries[0] = getString(isQuickLaunch
                    ? R.string.app_stream_use_app_value
                    : R.string.app_stream_use_global_value);
            newValues[0] = ""; // Empty string represents null/default

            // Copy original arrays starting from index 1
            System.arraycopy(originalEntries, 0, newEntries, 1, originalEntries.length);
            System.arraycopy(originalValues, 0, newValues, 1, originalValues.length);

            // Update the preference
            framePacingPref.setEntries(newEntries);
            framePacingPref.setEntryValues(newValues);

            // Set current value (null becomes empty string for default)
            framePacingPref.setValue(currentValue == null ? "" : currentValue);
        }

        private void setupBooleanPreference(ListPreference pref, int entriesResourceId, int valuesResourceId, String currentValue, boolean isQuickLaunch) {
            // Get original arrays from resources
            String[] originalEntries = getResources().getStringArray(entriesResourceId);
            String[] originalValues = getResources().getStringArray(valuesResourceId);

            // Create new arrays with default option at the beginning
            String[] newEntries = new String[originalEntries.length + 1];
            String[] newValues = new String[originalValues.length + 1];

            // Use different label depending on whether this is a quick launch item or app
            newEntries[0] = getString(isQuickLaunch
                    ? R.string.app_stream_use_app_value
                    : R.string.app_stream_use_global_value);
            newValues[0] = ""; // Empty string represents null/default

            // Copy original arrays starting from index 1
            System.arraycopy(originalEntries, 0, newEntries, 1, originalEntries.length);
            System.arraycopy(originalValues, 0, newValues, 1, originalValues.length);

            // Update the preference
            pref.setEntries(newEntries);
            pref.setEntryValues(newValues);

            // Set current value (null becomes empty string for default)
            pref.setValue(currentValue == null ? "" : currentValue);
        }

        private void showCustomResolutionDialog() {
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle(R.string.app_stream_resolution_dialog_title);
            
            LinearLayout layout = new LinearLayout(getActivity());
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(50, 40, 50, 10);
            
            final EditText widthInput = new EditText(getActivity());
            widthInput.setHint(R.string.app_stream_resolution_width_hint);
            widthInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            
            final EditText heightInput = new EditText(getActivity());
            heightInput.setHint(R.string.app_stream_resolution_height_hint);
            heightInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            
            // Pre-populate with current resolution if it exists
            if (currentResolution != null && currentResolution.contains("x")) {
                String[] parts = currentResolution.split("x");
                if (parts.length == 2) {
                    widthInput.setText(parts[0]);
                    heightInput.setText(parts[1]);
                }
            }
            
            layout.addView(widthInput);
            layout.addView(heightInput);
            builder.setView(layout);
            
            builder.setPositiveButton(android.R.string.ok, null);
            builder.setNeutralButton(R.string.app_stream_clear_value, null);
            builder.setNegativeButton(android.R.string.cancel, null);

            AlertDialog dialog = builder.create();
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String width = widthInput.getText().toString().trim();
                    String height = heightInput.getText().toString().trim();
                    if (!validateResolution(width, height)) {
                        heightInput.setError(getString(R.string.app_stream_invalid_resolution));
                        return;
                    }
                    currentResolution = width + "x" + height;
                    setupStandardPreference((ListPreference) findPreference("pref_app_resolution"),
                            R.array.resolution_names, R.array.resolution_values,
                            currentResolution, false);
                    updatePreferenceSummaries();
                    saveSettings();
                    dialog.dismiss();
                });
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    currentResolution = null;
                    setupStandardPreference((ListPreference) findPreference("pref_app_resolution"),
                            R.array.resolution_names, R.array.resolution_values, null, false);
                    updatePreferenceSummaries();
                    saveSettings();
                    dialog.dismiss();
                });
            });
            dialog.show();
        }

        private void showCustomFpsDialog() {
            final EditText input = new EditText(getActivity());
            input.setHint(R.string.app_stream_fps_hint);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            if (currentFps != null) input.setText(currentFps);

            AlertDialog dialog = new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.app_stream_fps_dialog_title)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.app_stream_clear_value, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String value = input.getText().toString().trim();
                    try {
                        if (Integer.parseInt(value) <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        input.setError(getString(R.string.app_stream_invalid_fps));
                        return;
                    }
                    currentFps = value;
                    setupStandardPreference((ListPreference) findPreference("text_app_fps"),
                            R.array.fps_names, R.array.fps_values, currentFps, true);
                    updatePreferenceSummaries();
                    saveSettings();
                    dialog.dismiss();
                });
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    currentFps = null;
                    setupStandardPreference((ListPreference) findPreference("text_app_fps"),
                            R.array.fps_names, R.array.fps_values, null, true);
                    updatePreferenceSummaries();
                    saveSettings();
                    dialog.dismiss();
                });
            });
            dialog.show();
        }
        
        private boolean validateResolution(String width, String height) {
            if (width.isEmpty() || height.isEmpty()) {
                return false;
            }
            
            try {
                int w = Integer.parseInt(width);
                int h = Integer.parseInt(height);
                return w > 0 && h > 0 && w <= 7680 && h <= 4320; // Reasonable limits
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = super.onCreateView(inflater, container, savedInstanceState);
            UiHelper.applyStatusBarPadding(view);
            return view;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            addPreferencesFromResource(R.xml.app_preferences);

            AppStreamSettings activity = (AppStreamSettings) getActivity();

            AppPreferences.AppSettings currentSettings = AppPreferences.getAppSettings(getActivity(), activity.appKey);

            // Detect if this is a quick launch item (key format: uuid:appid:timestamp vs uuid:appid)
            isQuickLaunch = activity.getIntent().getBooleanExtra(
                    EXTRA_INHERIT_APP_SETTINGS, false)
                    || activity.appKey != null && activity.appKey.split(":").length == 3;

            CheckBoxPreference useGlobalPref = (CheckBoxPreference) findPreference("checkbox_use_global_settings");
            ListPreference resolutionPref = (ListPreference) findPreference("pref_app_resolution");
            ListPreference fpsPref = (ListPreference) findPreference("text_app_fps");
            EditTextPreference bitratePref = (EditTextPreference) findPreference("text_app_bitrate_kbps");
            EditTextPreference actualDisplayRefreshRatePref = (EditTextPreference) findPreference("text_app_actual_display_refresh_rate");
            ListPreference enableHdrPref = (ListPreference) findPreference("list_app_enable_hdr");
            ListPreference enablePerfOverlayPref = (ListPreference) findPreference("list_app_enable_perf_overlay");
            ListPreference framePacingPref = (ListPreference) findPreference("list_app_frame_pacing");

            // Update checkbox label and summary based on whether this is a quick launch item
            if (isQuickLaunch) {
                useGlobalPref.setTitle(R.string.app_stream_use_app_settings_title);
                useGlobalPref.setSummary(R.string.app_stream_use_app_settings_summary);
            }

            // Initialize fields
            currentResolution = currentSettings.resolution;
            currentFps = currentSettings.fps > 0 ? String.valueOf(currentSettings.fps) : null;
            useGlobalPref.setChecked(currentSettings.useGlobalSettings);
            setupStandardPreference(resolutionPref, R.array.resolution_names,
                    R.array.resolution_values, currentResolution, false);
            setupStandardPreference(fpsPref, R.array.fps_names, R.array.fps_values,
                    currentFps, true);
            bitratePref.setText(currentSettings.bitrate > 0 ? String.valueOf(currentSettings.bitrate / 1000) : "");
            actualDisplayRefreshRatePref.setText(currentSettings.actualDisplayRefreshRate > 0 ? String.valueOf(currentSettings.actualDisplayRefreshRate) : "");
            setupFramePacingPreference(framePacingPref, currentSettings.framePacing, isQuickLaunch);
            setupBooleanPreference(enableHdrPref, R.array.hdr_setting_names, R.array.hdr_setting_values, currentSettings.enableHdr, isQuickLaunch);
            setupBooleanPreference(enablePerfOverlayPref, R.array.perf_overlay_setting_names, R.array.perf_overlay_setting_values, currentSettings.enablePerfOverlay, isQuickLaunch);

            updatePreferenceSummaries();
            updatePreferenceStates(useGlobalPref.isChecked());

            // Set the app-specific category title with the app name
            PreferenceCategory appCategory = (PreferenceCategory) findPreference("category_app_specific");
            if (appCategory != null) {
                appCategory.setTitle(getString(
                        R.string.app_stream_named_category_title, activity.appName));
            }

            resolutionPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    String value = String.valueOf(newValue);
                    if (CUSTOM_VALUE.equals(value)) {
                        showCustomResolutionDialog();
                        return false;
                    }
                    currentResolution = value.isEmpty() ? null : value;
                    new Handler().post(() -> {
                        updatePreferenceSummaries();
                        saveSettings();
                    });
                    return true;
                }
            });

            fpsPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    String value = String.valueOf(newValue);
                    if (CUSTOM_VALUE.equals(value)) {
                        showCustomFpsDialog();
                        return false;
                    }
                    currentFps = value.isEmpty() ? null : value;
                    new Handler().post(() -> {
                        updatePreferenceSummaries();
                        saveSettings();
                    });
                    return true;
                }
            });
            
            bitratePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    // newValue is now a String for EditTextPreference, not Integer
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            updatePreferenceSummaries();
                            saveSettings();
                        }
                    });
                    return true;
                }
            });
            
            framePacingPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            updatePreferenceSummaries();
                            saveSettings();
                        }
                    });
                    return true;
                }
            });

            actualDisplayRefreshRatePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            updatePreferenceSummaries();
                            saveSettings();
                        }
                    });
                    return true;
                }
            });

            enableHdrPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            updatePreferenceSummaries();
                            saveSettings();
                        }
                    });
                    return true;
                }
            });

            enablePerfOverlayPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            updatePreferenceSummaries();
                            saveSettings();
                        }
                    });
                    return true;
                }
            });

            useGlobalPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    new Handler().post(new Runnable() {
                        @Override
                        public void run() {
                            boolean useGlobal = (Boolean) newValue;
                            updatePreferenceStates(useGlobal);
                            saveSettings();
                        }
                    });
                    return true;
                }
            });
        }

        private void updatePreferenceStates(boolean useGlobal) {
            findPreference("pref_app_resolution").setEnabled(!useGlobal);
            findPreference("text_app_fps").setEnabled(!useGlobal);
            findPreference("text_app_bitrate_kbps").setEnabled(!useGlobal);
            findPreference("list_app_frame_pacing").setEnabled(!useGlobal);
            findPreference("text_app_actual_display_refresh_rate").setEnabled(!useGlobal);
            findPreference("list_app_enable_hdr").setEnabled(!useGlobal);
            findPreference("list_app_enable_perf_overlay").setEnabled(!useGlobal);
        }
        
        private void updatePreferenceSummaries() {
            ListPreference resolutionPref = (ListPreference) findPreference("pref_app_resolution");
            ListPreference fpsPref = (ListPreference) findPreference("text_app_fps");
            EditTextPreference bitratePref = (EditTextPreference) findPreference("text_app_bitrate_kbps");
            ListPreference framePacingPref = (ListPreference) findPreference("list_app_frame_pacing");
            EditTextPreference actualDisplayRefreshRatePref = (EditTextPreference) findPreference("text_app_actual_display_refresh_rate");
            ListPreference enableHdrPref = (ListPreference) findPreference("list_app_enable_hdr");
            ListPreference enablePerfOverlayPref = (ListPreference) findPreference("list_app_enable_perf_overlay");

            resolutionPref.setSummary(resolutionPref.getEntry());
            fpsPref.setSummary(fpsPref.getEntry());

            // Set bitrate summary
            String bitrateMbps = bitratePref.getText();
            if (bitrateMbps != null && !bitrateMbps.isEmpty() && !bitrateMbps.equals("0")) {
                bitratePref.setSummary(bitrateMbps + " Mbps");
            } else {
                bitratePref.setSummary(android.text.Html.fromHtml("<i>Not set</i>"));
            }

            // Set frame pacing summary - show the human readable name or default label
            String framePacing = framePacingPref.getValue();
            CharSequence[] framePacingEntries = framePacingPref.getEntries();
            CharSequence[] framePacingValues = framePacingPref.getEntryValues();

            // Find and display the matching entry (including the default option at index 0)
            for (int i = 0; i < framePacingValues.length; i++) {
                if ((framePacing == null && framePacingValues[i].toString().isEmpty()) ||
                    (framePacing != null && framePacing.equals(framePacingValues[i].toString()))) {
                    framePacingPref.setSummary(framePacingEntries[i]);
                    break;
                }
            }

            // Set actual display refresh rate summary
            String actualDisplayRefreshRate = actualDisplayRefreshRatePref.getText();
            if (actualDisplayRefreshRate != null && !actualDisplayRefreshRate.isEmpty() && !actualDisplayRefreshRate.equals("0")) {
                actualDisplayRefreshRatePref.setSummary(actualDisplayRefreshRate + "Hz");
            } else {
                actualDisplayRefreshRatePref.setSummary(android.text.Html.fromHtml("<i>Not set</i>"));
            }

            // Set HDR summary - show the human readable name or default label
            String enableHdr = enableHdrPref.getValue();
            CharSequence[] hdrEntries = enableHdrPref.getEntries();
            CharSequence[] hdrValues = enableHdrPref.getEntryValues();
            for (int i = 0; i < hdrValues.length; i++) {
                if ((enableHdr == null && hdrValues[i].toString().isEmpty()) ||
                    (enableHdr != null && enableHdr.equals(hdrValues[i].toString()))) {
                    enableHdrPref.setSummary(hdrEntries[i]);
                    break;
                }
            }

            // Set perf overlay summary - show the human readable name or default label
            String enablePerfOverlay = enablePerfOverlayPref.getValue();
            CharSequence[] perfOverlayEntries = enablePerfOverlayPref.getEntries();
            CharSequence[] perfOverlayValues = enablePerfOverlayPref.getEntryValues();
            for (int i = 0; i < perfOverlayValues.length; i++) {
                if ((enablePerfOverlay == null && perfOverlayValues[i].toString().isEmpty()) ||
                    (enablePerfOverlay != null && enablePerfOverlay.equals(perfOverlayValues[i].toString()))) {
                    enablePerfOverlayPref.setSummary(perfOverlayEntries[i]);
                    break;
                }
            }
        }

        public void saveSettings() {
            AppStreamSettings activity = (AppStreamSettings) getActivity();
            if (activity == null) return;

            CheckBoxPreference useGlobalPref = (CheckBoxPreference) findPreference("checkbox_use_global_settings");
            EditTextPreference bitratePref = (EditTextPreference) findPreference("text_app_bitrate_kbps");
            ListPreference framePacingPref = (ListPreference) findPreference("list_app_frame_pacing");
            EditTextPreference actualDisplayRefreshRatePref = (EditTextPreference) findPreference("text_app_actual_display_refresh_rate");
            ListPreference enableHdrPref = (ListPreference) findPreference("list_app_enable_hdr");
            ListPreference enablePerfOverlayPref = (ListPreference) findPreference("list_app_enable_perf_overlay");

            int fps = 0;
            if (currentFps != null && !currentFps.isEmpty()) {
                try {
                    fps = Integer.parseInt(currentFps);
                } catch (NumberFormatException ignored) {
                }
            }

            // Convert bitrate from Mbps back to kbps for storage
            int bitrateKbps = 0;
            String bitrateMbpsText = bitratePref.getText();
            if (bitrateMbpsText != null && !bitrateMbpsText.isEmpty()) {
                try {
                    int bitrateMbps = Integer.parseInt(bitrateMbpsText);
                    bitrateKbps = bitrateMbps * 1000;
                } catch (NumberFormatException ignored) {
                }
            }

            // Convert empty string back to null for frame pacing (represents default)
            String framePacingValue = framePacingPref.getValue();
            if (framePacingValue != null && framePacingValue.isEmpty()) {
                framePacingValue = null;
            }

            double actualDisplayRefreshRate = 0;
            String actualDisplayRefreshRateText = actualDisplayRefreshRatePref.getText();
            if (actualDisplayRefreshRateText != null && !actualDisplayRefreshRateText.isEmpty()) {
                try {
                    actualDisplayRefreshRate = Double.parseDouble(actualDisplayRefreshRateText);
                } catch (NumberFormatException ignored) {
                }
            }

            // Convert empty string back to null for HDR (represents default)
            String enableHdrValue = enableHdrPref.getValue();
            if (enableHdrValue != null && enableHdrValue.isEmpty()) {
                enableHdrValue = null;
            }

            // Convert empty string back to null for perf overlay (represents default)
            String enablePerfOverlayValue = enablePerfOverlayPref.getValue();
            if (enablePerfOverlayValue != null && enablePerfOverlayValue.isEmpty()) {
                enablePerfOverlayValue = null;
            }

            AppPreferences.AppSettings settings = new AppPreferences.AppSettings(
                currentResolution,
                fps,
                framePacingValue,
                bitrateKbps,
                actualDisplayRefreshRate,
                enableHdrValue,
                enablePerfOverlayValue,
                useGlobalPref.isChecked()
            );

            AppPreferences.saveAppSettings(getActivity(), activity.appKey, settings);
        }
    }
}
