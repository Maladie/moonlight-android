package com.limelight;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.limelight.binding.audio.UsbMicrophoneService;
import com.limelight.binding.audio.UsbMicrophonePermissionActivity;
import com.limelight.binding.audio.DiscordAudioDownlink;
import com.limelight.console.HostGatewayStore;
import com.limelight.gateway.GatewayConnection;

import com.limelight.diagnostics.MoonWakerDiagnostics;

import java.io.File;

public final class MoonWakerApplication extends Application implements
        Application.ActivityLifecycleCallbacks,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private Activity resumedActivity;
    private boolean audioPermissionRequested;
    private boolean notificationPermissionRequested;
    private SharedPreferences defaultPreferences;
    private SharedPreferences consolePreferences;
    private SharedPreferences gatewayPreferences;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final DiscordAudioDownlink discordAudio = new DiscordAudioDownlink();
    private final Runnable stopDiscordAudio = discordAudio::stop;

    @Override public void onCreate() {
        super.onCreate();
        MoonWakerDiagnostics.initialize(new File(getNoBackupFilesDir(), "diagnostics"));
        MoonWakerDiagnostics.installCrashHandler();
        MoonWakerDiagnostics.record("INFO", "app", "process.started");
        registerActivityLifecycleCallbacks(this);
        defaultPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        consolePreferences = getSharedPreferences(
                UsbMicrophoneService.CONSOLE_PREFS, MODE_PRIVATE);
        gatewayPreferences = getSharedPreferences(
                UsbMicrophoneService.GATEWAY_PREFS, MODE_PRIVATE);
        defaultPreferences.registerOnSharedPreferenceChangeListener(this);
        consolePreferences.registerOnSharedPreferenceChangeListener(this);
        gatewayPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    private void updateUsbMicrophoneService() {
        Activity activity = resumedActivity;
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(UsbMicrophoneService.PREF_ENABLED, false);
        if (!enabled) {
            notificationPermissionRequested = false;
            UsbMicrophoneService.stop(this);
            if (!UsbMicrophoneService.isDisabledAttentionState(
                    UsbMicrophoneService.state(this))) {
                UsbMicrophoneService.publishState(this, UsbMicrophoneService.STATE_OFF);
            }
            return;
        }
        if (activity == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            UsbMicrophoneService.disableWithState(
                    this, UsbMicrophoneService.STATE_UNSUPPORTED);
            return;
        }
        if (!UsbMicrophoneService.hasConfiguration(this)) {
            UsbMicrophoneService.disableWithState(
                    this, UsbMicrophoneService.STATE_GATEWAY_REQUIRED);
            Toast.makeText(activity, R.string.usb_microphone_gateway_required,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (!audioPermissionRequested) {
                audioPermissionRequested = true;
                UsbMicrophoneService.publishState(
                        this, UsbMicrophoneService.STATE_PERMISSION_REQUIRED);
                UsbMicrophonePermissionActivity.request(activity);
            } else {
                UsbMicrophoneService.disableWithState(
                        this, UsbMicrophoneService.STATE_PERMISSION_REQUIRED);
                Toast.makeText(activity, R.string.usb_microphone_permission_required,
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && !notificationPermissionRequested) {
            notificationPermissionRequested = true;
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7105);
        }
        UsbMicrophoneService.startFromVisibleActivity(activity);
    }

    private void updateDiscordAudio() {
        Activity activity = resumedActivity;
        if (activity == null) return;
        String selectedHost = consolePreferences.getString(
                UsbMicrophoneService.SELECTED_HOST, "");
        GatewayConnection connection = new HostGatewayStore(this)
                .loadForHost(selectedHost, null);
        int gain = defaultPreferences.getInt(
                DiscordAudioDownlink.GAIN_PREF, DiscordAudioDownlink.DEFAULT_GAIN);
        discordAudio.start(connection, gain, !(activity instanceof Game));
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        String selectedHost = consolePreferences.getString(
                UsbMicrophoneService.SELECTED_HOST, "");
        boolean micChanged = (preferences == defaultPreferences
                && UsbMicrophoneService.PREF_ENABLED.equals(key))
                || (preferences == consolePreferences
                && UsbMicrophoneService.SELECTED_HOST.equals(key))
                || (preferences == gatewayPreferences
                && UsbMicrophoneService.isGatewayKeyForSelectedHost(selectedHost, key));
        boolean discordAudioChanged = (preferences == defaultPreferences
                && DiscordAudioDownlink.GAIN_PREF.equals(key))
                || (preferences == consolePreferences
                && UsbMicrophoneService.SELECTED_HOST.equals(key))
                || (preferences == gatewayPreferences
                && UsbMicrophoneService.isGatewayKeyForSelectedHost(selectedHost, key));
        if (micChanged) updateUsbMicrophoneService();
        if (discordAudioChanged) updateDiscordAudio();
    }

    @Override public void onActivityResumed(Activity activity) {
        mainHandler.removeCallbacks(stopDiscordAudio);
        if (activity instanceof UsbMicrophonePermissionActivity) return;
        resumedActivity = activity;
        updateDiscordAudio();
        updateUsbMicrophoneService();
    }

    @Override public void onActivityPaused(Activity activity) {
        if (resumedActivity == activity) {
            resumedActivity = null;
            mainHandler.postDelayed(stopDiscordAudio, 750);
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
