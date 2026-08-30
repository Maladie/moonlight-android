package com.limelight;

import android.app.Application;

import com.limelight.diagnostics.MoonWakerDiagnostics;

import java.io.File;

public final class MoonWakerApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        MoonWakerDiagnostics.initialize(new File(getNoBackupFilesDir(), "diagnostics"));
        MoonWakerDiagnostics.installCrashHandler();
        MoonWakerDiagnostics.record("INFO", "app", "process.started");
    }
}
