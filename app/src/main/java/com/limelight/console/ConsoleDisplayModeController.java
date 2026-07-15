package com.limelight.console;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamView;

import java.util.Objects;

/** Applies the shared mode decision to Console's persistent StreamView. */
final class ConsoleDisplayModeController {
    private final Activity activity;
    private final StreamView streamView;

    ConsoleDisplayModeController(Activity activity, StreamView streamView) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.streamView = Objects.requireNonNull(streamView, "streamView");
    }

    float prepare(PreferenceConfiguration preferences) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        WindowManager.LayoutParams layout = activity.getWindow().getAttributes();
        float selectedRefreshRate;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode current = display.getMode();
            Display.Mode[] androidModes = display.getSupportedModes();
            StreamDisplayModePolicy.Mode[] modes =
                    new StreamDisplayModePolicy.Mode[androidModes.length];
            for (int i = 0; i < androidModes.length; i++) {
                Display.Mode mode = androidModes[i];
                modes[i] = new StreamDisplayModePolicy.Mode(
                        mode.getModeId(), mode.getPhysicalWidth(),
                        mode.getPhysicalHeight(), mode.getRefreshRate());
            }
            StreamDisplayModePolicy.Mode selected = StreamDisplayModePolicy.select(
                    new StreamDisplayModePolicy.Mode(
                            current.getModeId(), current.getPhysicalWidth(),
                            current.getPhysicalHeight(), current.getRefreshRate()),
                    modes,
                    preferences.width,
                    preferences.height,
                    preferences.fps,
                    preferences.framePacing,
                    preferences.reduceRefreshRate,
                    PreferenceConfiguration.isNativeResolution(
                            preferences.width, preferences.height),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);
            Display.Mode best = current;
            for (Display.Mode candidate : androidModes) {
                if (candidate.getModeId() == selected.id) {
                    best = candidate;
                    break;
                }
            }
            if (current.getModeId() != best.getModeId()) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        current.getPhysicalWidth() != best.getPhysicalWidth() ||
                        current.getPhysicalHeight() != best.getPhysicalHeight()) {
                    layout.preferredDisplayModeId = best.getModeId();
                    activity.getWindow().setAttributes(layout);
                }
            }
            selectedRefreshRate = best.getRefreshRate();
            LimeLog.info("MoonWaker unified display mode=" + best.getPhysicalWidth() +
                    "x" + best.getPhysicalHeight() + "@" + best.getRefreshRate());
        } else {
            float bestRefreshRate = display.getRefreshRate();
            for (float candidate : display.getSupportedRefreshRates()) {
                if (candidate > bestRefreshRate &&
                        (preferences.fps > 60 || candidate < 63)) {
                    bestRefreshRate = candidate;
                }
            }
            layout.preferredRefreshRate = bestRefreshRate;
            activity.getWindow().setAttributes(layout);
            selectedRefreshRate = bestRefreshRate;
        }

        boolean aspectRatioMatch = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Point screenSize = new Point();
            display.getSize(screenSize);
            double screenAspect = (double) screenSize.y / screenSize.x;
            double streamAspect = (double) preferences.height / preferences.width;
            aspectRatioMatch = Math.abs(screenAspect - streamAspect) < 0.001;
        }
        if (preferences.stretchVideo || aspectRatioMatch) {
            streamView.setDesiredAspectRatio(0);
            streamView.getHolder().setFixedSize(preferences.width, preferences.height);
        } else {
            streamView.getHolder().setSizeFromLayout();
            streamView.setDesiredAspectRatio(
                    (double) preferences.width / preferences.height);
        }

        if (activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return selectedRefreshRate;
        }
        return Math.min(display.getRefreshRate(), selectedRefreshRate);
    }
}
