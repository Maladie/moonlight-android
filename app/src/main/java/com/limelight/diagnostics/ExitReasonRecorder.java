package com.limelight.diagnostics;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * Records why the previous processes ended (LMK, crash, ANR, kill...) so lab evidence can tell
 * process death from Activity recreation. Only allowlisted numeric fields are recorded; the
 * free-text description and traces may carry private data and are never read.
 */
public final class ExitReasonRecorder {
    private static final String PREFS = "moonwaker_exit_reasons";
    private static final String LAST_TIMESTAMP = "last_recorded_timestamp";
    private static final int MAX_REASONS = 16;

    /** Allowlisted view of one exit record. */
    public static final class Exit {
        final long timestamp;
        final int pid;
        final int reason;
        final int status;
        final int importance;

        public Exit(long timestamp, int pid, int reason, int status, int importance) {
            this.timestamp = timestamp;
            this.pid = pid;
            this.reason = reason;
            this.status = status;
            this.importance = importance;
        }
    }

    /** Diagnostic sink, so selection and formatting run in the JVM. */
    public interface Sink {
        void record(Object... fields);
    }

    private ExitReasonRecorder() { }

    public static void recordAtStart(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return;
        List<Exit> exits = new ArrayList<>();
        for (ApplicationExitInfo info : manager.getHistoricalProcessExitReasons(
                context.getPackageName(), 0, MAX_REASONS)) {
            exits.add(new Exit(info.getTimestamp(), info.getPid(), info.getReason(),
                    info.getStatus(), info.getImportance()));
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long newest = recordNew(exits, prefs.getLong(LAST_TIMESTAMP, 0L), fields ->
                MoonWakerDiagnostics.record("INFO", "app", "app.exit_reason", fields));
        prefs.edit().putLong(LAST_TIMESTAMP, newest).apply();
    }

    /** Records exits newer than {@code lastRecorded}, oldest first; returns the newest seen. */
    public static long recordNew(List<Exit> exits, long lastRecorded, Sink sink) {
        List<Exit> fresh = new ArrayList<>();
        long newest = lastRecorded;
        for (Exit exit : exits) {
            if (exit.timestamp <= lastRecorded) continue;
            fresh.add(exit);
            newest = Math.max(newest, exit.timestamp);
        }
        fresh.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        for (Exit exit : fresh) {
            sink.record("reason", reasonName(exit.reason), "status", exit.status,
                    "importance", exit.importance, "timestamp", exit.timestamp,
                    "pid", exit.pid);
        }
        return newest;
    }

    static String reasonName(int reason) {
        switch (reason) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "UNKNOWN";
        }
    }
}
