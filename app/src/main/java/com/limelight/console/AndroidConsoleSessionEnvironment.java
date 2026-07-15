package com.limelight.console;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Build;
import android.view.Display;

import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;
import com.limelight.ui.StreamView;
import com.limelight.utils.ServerHelper;

import java.util.Objects;

/** Android dependencies required by the inactive unified session factory. */
final class AndroidConsoleSessionEnvironment implements
        MoonlightConsoleSessionFactory.Environment {
    interface Callbacks {
        void onInputReady(ConsoleSessionInput input);
        void onFirstFrameRendered();
        void onPerformanceUpdate(String text);
        void onStatus(String status);
        void onConnectionStatus(int status);
        void onMessage(String message, boolean transientMessage);
        void onConfigurationPlanned(StreamSessionConfigurationPlanner.Plan plan);
        void toggleKeyboard();
        void onOverlayOpen();
    }

    private static final String TOMBSTONE_PREFS = "DecoderTombstone";
    private static final String CRASH_COUNT = "CrashCount";

    private final Activity activity;
    private final ConsoleDisplayModeController displayModeController;
    private final Callbacks callbacks;
    private final SharedPreferences tombstonePreferences;

    AndroidConsoleSessionEnvironment(Activity activity,
                                     StreamView streamView,
                                     Callbacks callbacks) {
        this.activity = Objects.requireNonNull(activity, "activity");
        displayModeController = new ConsoleDisplayModeController(activity, streamView);
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        tombstonePreferences = activity.getSharedPreferences(TOMBSTONE_PREFS, 0);
    }

    @Override public StreamRendererConfiguration rendererConfiguration(
            PreferenceConfiguration preferences,
            StreamLaunchParameters parameters) {
        ConnectivityManager connectivity = (ConnectivityManager)
                activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean metered = connectivity != null && connectivity.isActiveNetworkMetered();
        int[] supportedHdrTypes = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Display.HdrCapabilities capabilities = activity.getWindowManager()
                    .getDefaultDisplay().getHdrCapabilities();
            if (capabilities != null) {
                supportedHdrTypes = capabilities.getSupportedHdrTypes();
            }
        }
        StreamHdrDisplayPolicy.Result hdr = StreamHdrDisplayPolicy.evaluate(
                preferences.enableHdr,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
                supportedHdrTypes,
                Display.HdrCapabilities.HDR_TYPE_HDR10);
        GlPreferences glPreferences = GlPreferences.readPreferences(activity);
        return new StreamRendererConfiguration(
                preferences,
                tombstonePreferences.getInt(CRASH_COUNT, 0),
                metered,
                hdr.enabled,
                glPreferences.glRenderer);
    }

    @Override public float prepareDisplayForRendering(
            PreferenceConfiguration preferences) {
        return displayModeController.prepare(preferences);
    }

    @Override public CrashListener crashListener() {
        return error -> tombstonePreferences.edit()
                .putInt(CRASH_COUNT, tombstonePreferences.getInt(CRASH_COUNT, 0) + 1)
                .commit();
    }

    @Override public PerfOverlayListener performanceListener() {
        return text -> activity.runOnUiThread(() -> callbacks.onPerformanceUpdate(text));
    }

    @Override public Runnable firstFrameRenderedCallback() {
        return () -> activity.runOnUiThread(callbacks::onFirstFrameRendered);
    }

    @Override public ConsoleSessionInput createInput(
            StreamInputSender inputSender,
            MediaCodecDecoderRenderer renderer,
            PreferenceConfiguration preferences) {
        GameGestures gestures = callbacks::toggleKeyboard;
        AndroidConsoleSessionInput.Presentation presentation =
                new AndroidConsoleSessionInput.Presentation() {
                    @Override public void onStatus(String status) {
                        activity.runOnUiThread(() -> callbacks.onStatus(status));
                    }

                    @Override public void onConnectionStatus(int status) {
                        activity.runOnUiThread(() -> callbacks.onConnectionStatus(status));
                    }

                    @Override public void onMessage(
                            String message, boolean transientMessage) {
                        activity.runOnUiThread(() -> callbacks.onMessage(
                                message, transientMessage));
                    }

                    @Override public void onOverlayOpen() {
                        activity.runOnUiThread(callbacks::onOverlayOpen);
                    }
                };
        return new AndroidConsoleSessionInput(
                activity, inputSender, gestures, preferences, renderer, presentation);
    }

    @Override public void onInputReady(ConsoleSessionInput input) {
        activity.runOnUiThread(() -> callbacks.onInputReady(input));
    }

    @Override public void onConfigurationPlanned(
            StreamSessionConfigurationPlanner.Plan plan) {
        activity.runOnUiThread(() -> callbacks.onConfigurationPlanned(plan));
    }

    @Override public Runnable quitHostApplication(StreamLaunchParameters parameters) {
        return () -> ServerHelper.doQuit(
                activity,
                new ComputerDetails.AddressTuple(parameters.host, parameters.port),
                parameters.httpsPort,
                parameters.serverCertificate,
                parameters.appName,
                parameters.uniqueId,
                null);
    }
}
