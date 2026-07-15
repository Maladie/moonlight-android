package com.limelight.console;

import android.content.Context;
import android.view.View;

import com.limelight.ui.ExternalFrontendLoadingView;

/** Adapts the established Wake/Moonlight hand-off loader for the unified console. */
final class ConsoleLoadingController {
    private final Context context;
    private ExternalFrontendLoadingView loadingView;

    ConsoleLoadingController(Context context) {
        this.context = context;
    }

    View build() {
        loadingView = new ExternalFrontendLoadingView(
                context, "MOONWAKER GAME APP", 0L, false);
        return loadingView;
    }

    void show(String appName) {
        if (loadingView != null) {
            loadingView.restartLoading(
                    appName == null || appName.isEmpty() ? "Preparing stream" : appName,
                    "Preparing wake sequence...");
        }
    }

    void updateStatus(String value) {
        if (loadingView != null) loadingView.setStatus(value);
    }

    void stop() {
        if (loadingView != null) loadingView.stop();
    }
}
