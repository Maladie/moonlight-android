package com.limelight.diagnostics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;

import com.limelight.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Debug-only evidence for a blocked Game main thread. It never changes stream behavior. */
public final class StartupThreadWatchdog {
    private static final String FILE_NAME = "startup-threads.json";
    private static final long HEARTBEAT_MS = 500L;
    private static final long POLL_MS = 100L;
    private static final long STALL_MS = 2_000L;
    private static final long MIN_SAMPLE_GAP_MS = 1_000L;
    private static final long MAX_LIFETIME_MS = 60_000L;
    private static final int MAX_SAMPLES = 3;
    private static final int MAX_THREADS = 8;
    private static final int MAX_FRAMES = 20;
    private static final int MAX_REPORT_BYTES = 256 * 1024;

    private static final AtomicBoolean CLAIMED = new AtomicBoolean();
    private static volatile Monitor active;

    private StartupThreadWatchdog() { }

    public static void start(Context context) {
        if (!BuildConfig.DEBUG || context == null || !CLAIMED.compareAndSet(false, true)) return;
        try {
            Monitor monitor = new Monitor(context.getApplicationContext());
            active = monitor;
            monitor.start();
        } catch (Throwable ignored) {
            // Diagnostics must never affect Game startup.
        }
    }

    public static void stop() {
        Monitor monitor = active;
        if (monitor != null) monitor.stop("activity_destroyed");
    }

    static String categoryForName(String name) {
        if (name == null) return "";
        String value = name.toLowerCase(Locale.US);
        if (value.equals("main")) return "main";
        if (value.contains("binder")) return "binder";
        if (value.contains("codec")) return "codec";
        if (value.contains("renderer") || value.contains("render")) return "renderer";
        if (value.contains("audio")) return "audio";
        if (value.contains("choreographer")) return "choreographer";
        if (value.contains("provider")) return "provider";
        if (value.contains("pool") || value.contains("executor")
                || value.contains("worker")) return "worker";
        return "";
    }

    static JSONArray threadRecords(Map<Thread, StackTraceElement[]> stacks)
            throws JSONException {
        List<Thread> selected = new ArrayList<>();
        if (stacks != null) {
            for (Thread thread : stacks.keySet()) {
                if (!categoryForName(thread.getName()).isEmpty()) selected.add(thread);
            }
        }
        Collections.sort(selected, Comparator
                .comparingInt((Thread thread) -> categoryOrder(categoryForName(thread.getName())))
                .thenComparingLong(Thread::getId));

        JSONArray result = new JSONArray();
        int count = Math.min(selected.size(), MAX_THREADS);
        for (int index = 0; index < count; index++) {
            Thread thread = selected.get(index);
            JSONObject value = new JSONObject();
            value.put("category", categoryForName(thread.getName()));
            value.put("thread_id", thread.getId());
            value.put("state", thread.getState().name());
            value.put("frames", frames(stacks.get(thread)));
            result.put(value);
        }
        return result;
    }

    static JSONArray frames(StackTraceElement[] stack) throws JSONException {
        JSONArray result = new JSONArray();
        if (stack == null) return result;
        for (int index = 0; index < Math.min(stack.length, MAX_FRAMES); index++) {
            StackTraceElement frame = stack[index];
            JSONObject value = new JSONObject();
            value.put("class", safeSymbol(frame.getClassName()));
            value.put("method", safeSymbol(frame.getMethodName()));
            value.put("line", frame.getLineNumber());
            value.put("native", frame.isNativeMethod());
            result.put(value);
        }
        return result;
    }

    private static int categoryOrder(String category) {
        switch (category) {
            case "main": return 0;
            case "binder": return 1;
            case "codec": return 2;
            case "renderer": return 3;
            case "audio": return 4;
            case "worker": return 5;
            case "choreographer": return 6;
            case "provider": return 7;
            default: return 8;
        }
    }

    private static String safeSymbol(String value) {
        if (value == null || value.length() > 128
                || !value.matches("[A-Za-z0-9_.$<>-]+")
                || value.matches("(?i)(?:0x)?[0-9a-f]{8,16}")) return "unknown";
        return value;
    }

    private static final class Monitor implements Runnable {
        private final File directory;
        private final AtomicFile reportFile;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final AtomicLong lastHeartbeat = new AtomicLong();
        private final long startedAt = SystemClock.elapsedRealtime();
        private final int pid = Process.myPid();
        private final String runId = MoonWakerDiagnostics.currentRunId();
        private final JSONArray samples = new JSONArray();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Thread worker = new Thread(this, "MoonWaker-Startup-Watchdog");
        private volatile String closeReason = "lifetime_limit";
        private String errorType = "";
        private long lastSampleAt;
        private int sampleAttempts;

        private final Runnable heartbeat = new Runnable() {
            @Override public void run() {
                if (stopped.get()) return;
                lastHeartbeat.set(SystemClock.elapsedRealtime());
                mainHandler.postDelayed(this, HEARTBEAT_MS);
            }
        };

        Monitor(Context context) {
            directory = new File(context.getNoBackupFilesDir(), "diagnostics");
            reportFile = new AtomicFile(new File(directory, FILE_NAME));
            lastHeartbeat.set(startedAt);
            worker.setDaemon(true);
        }

        void start() {
            mainHandler.post(heartbeat);
            worker.start();
        }

        void stop(String reason) {
            closeReason = reason;
            stopped.set(true);
            mainHandler.removeCallbacks(heartbeat);
            worker.interrupt();
        }

        @Override public void run() {
            persist("ARMED");
            try {
                while (!stopped.get()) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - startedAt >= MAX_LIFETIME_MS) break;
                    long lag = Math.max(0L, now - lastHeartbeat.get());
                    if (lag >= STALL_MS && sampleAttempts < MAX_SAMPLES
                            && (lastSampleAt == 0L || now - lastSampleAt >= MIN_SAMPLE_GAP_MS)) {
                        sampleAttempts++;
                        capture(now, lag);
                    }
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException ignored) {
                        if (stopped.get()) break;
                    }
                }
            } finally {
                stopped.set(true);
                mainHandler.removeCallbacks(heartbeat);
                persist("CLOSED");
            }
        }

        private void capture(long now, long lag) {
            lastSampleAt = now;
            try {
                JSONObject sample = new JSONObject();
                sample.put("lag_ms", lag);
                sample.put("sample_time_ms", now);
                sample.put("threads", threadRecords(Thread.getAllStackTraces()));
                samples.put(sample);
                errorType = "";
                persist("CAPTURED");
            } catch (Throwable ignored) {
                errorType = "snapshot_failed";
                persist("ERROR");
            }
        }

        private void persist(String status) {
            FileOutputStream output = null;
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IllegalStateException("diagnostics_directory_unavailable");
                }
                JSONObject report = new JSONObject();
                report.put("kind", "startup_thread_watchdog");
                report.put("status", errorType.isEmpty() ? status : "ERROR");
                report.put("pid", pid);
                report.put("run_id", runId);
                report.put("sample_count", samples.length());
                report.put("sample_attempts", sampleAttempts);
                report.put("samples", samples);
                report.put("max_runtime_ms", MAX_LIFETIME_MS);
                report.put("native_unwind_available", false);
                report.put("monitor_owner_available", false);
                if ("CLOSED".equals(status)) report.put("close_reason", closeReason);
                if (!errorType.isEmpty()) report.put("error_type", errorType);
                byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_REPORT_BYTES) {
                    errorType = "report_limit_exceeded";
                    return;
                }
                output = reportFile.startWrite();
                output.write(bytes);
                reportFile.finishWrite(output);
                output = null;
            } catch (Throwable ignored) {
                errorType = "write_failed";
                if (output != null) {
                    try {
                        reportFile.failWrite(output);
                    } catch (Throwable ignoredAgain) {
                        // Best-effort diagnostics only.
                    }
                }
            }
        }
    }
}
