package com.limelight.diagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, best-effort local diagnostics. It must never affect application behavior. */
public final class MoonWakerDiagnostics {
    private static final int MAX_FILES = 3;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_QUEUE_ENTRIES = 512;
    private static final int MAX_FIELD_LENGTH = 256;
    private static final int MAX_CRASH_FRAMES = 16;
    private static final long PROCESS_START_NANOS = System.nanoTime();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final Set<String> ALLOWED_FIELDS = new HashSet<>(Arrays.asList(
            "transition_id", "stream_session_id", "request_id", "profile_id", "host_id",
            "game_id", "operation", "state", "from", "to", "stage", "status", "reason",
            "duration_ms", "http_status", "route", "method", "error_type", "milestone",
            "kind", "dropped", "app_id", "overlay_visible", "input_blocked",
            "operation_authorized", "reveal_authorized", "manual_reveal_available",
            "uncertain", "orchestration_id", "operation_id"));

    private static Writer writer;
    private static File directory;
    private static String runId = "";
    private static Thread.UncaughtExceptionHandler installedCrashHandler;
    private static Thread.UncaughtExceptionHandler previousCrashHandler;

    private MoonWakerDiagnostics() { }

    public static synchronized void initialize(File diagnosticsDirectory) {
        if (writer != null || diagnosticsDirectory == null) return;
        directory = diagnosticsDirectory;
        runId = UUID.randomUUID().toString();
        writer = new Writer(diagnosticsDirectory);
        writer.start();
    }

    public static synchronized void installCrashHandler() {
        if (installedCrashHandler != null) return;
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
        installedCrashHandler = (thread, error) -> {
            writeCrash(error);
            if (previousCrashHandler != null) {
                previousCrashHandler.uncaughtException(thread, error);
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(installedCrashHandler);
    }

    public static void record(String level, String component, String event, Object... fields) {
        try {
            Writer current;
            String currentRunId;
            synchronized (MoonWakerDiagnostics.class) {
                current = writer;
                currentRunId = runId;
            }
            if (current == null) {
                DROPPED.incrementAndGet();
                return;
            }
            String line = normalRecord(level, component, event, currentRunId, fields).toString()
                    + "\n";
            if (!current.offer(line)) DROPPED.incrementAndGet();
        } catch (Throwable ignored) {
            DROPPED.incrementAndGet();
        }
    }

    public static boolean exportTo(OutputStream output) {
        if (output == null) return false;
        try {
            Writer current;
            synchronized (MoonWakerDiagnostics.class) {
                current = writer;
            }
            return current != null && current.flush() && current.exportTo(output);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeCrash(Throwable error) {
        try {
            File currentDirectory;
            String currentRunId;
            synchronized (MoonWakerDiagnostics.class) {
                currentDirectory = directory;
                currentRunId = runId;
            }
            if (currentDirectory == null || error == null) {
                DROPPED.incrementAndGet();
                return;
            }
            if (!currentDirectory.isDirectory() && !currentDirectory.mkdirs()) {
                DROPPED.incrementAndGet();
                return;
            }
            JSONObject record = baseRecord("ERROR", "app", "uncaught_exception", currentRunId);
            record.put("error_type", safeCrashIdentifier(error.getClass().getName()));
            JSONArray frames = new JSONArray();
            StackTraceElement[] stack = error.getStackTrace();
            for (int index = 0; index < Math.min(stack.length, MAX_CRASH_FRAMES); index++) {
                StackTraceElement frame = stack[index];
                JSONObject value = new JSONObject();
                value.put("class", safeCrashIdentifier(frame.getClassName()));
                value.put("method", safeCrashIdentifier(frame.getMethodName()));
                value.put("line", frame.getLineNumber());
                frames.put(value);
            }
            record.put("frames", frames);
            File temporary = new File(currentDirectory, "last-crash.json.tmp");
            File target = new File(currentDirectory, "last-crash.json");
            writeBytes(temporary, record.toString().getBytes(StandardCharsets.UTF_8), false);
            if (target.exists() && !target.delete()) throw new IOException("Unable to replace crash record");
            if (!temporary.renameTo(target)) throw new IOException("Unable to publish crash record");
        } catch (Throwable ignored) {
            DROPPED.incrementAndGet();
        }
    }

    private static JSONObject normalRecord(String level, String component, String event,
                                           String currentRunId, Object... fields)
            throws JSONException {
        JSONObject record = baseRecord(level, component, event, currentRunId);
        if (fields == null) return record;
        for (int index = 0; index + 1 < fields.length; index += 2) {
            if (!(fields[index] instanceof String)) continue;
            String key = (String) fields[index];
            if (!ALLOWED_FIELDS.contains(key)) continue;
            Object value = safeValue(key, fields[index + 1]);
            if (value != null) record.put(key, value);
        }
        return record;
    }

    private static JSONObject baseRecord(String level, String component, String event,
                                         String currentRunId) throws JSONException {
        JSONObject record = new JSONObject();
        record.put("v", 1);
        record.put("ts", utcNow());
        record.put("mono_ms", Math.max(0L,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - PROCESS_START_NANOS)));
        record.put("level", safeLevel(level));
        record.put("component", safeName(component, "unknown"));
        record.put("event", safeName(event, "unknown"));
        record.put("run_id", limited(currentRunId));
        return record;
    }

    private static Object safeValue(String key, Object value) {
        if (value instanceof String) return safeStringValue(key, (String) value);
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof Float) {
            float number = (Float) value;
            return !Float.isNaN(number) && !Float.isInfinite(number) ? number : null;
        }
        if (value instanceof Double) {
            double number = (Double) value;
            return !Double.isNaN(number) && !Double.isInfinite(number) ? number : null;
        }
        return null;
    }

    private static String safeStringValue(String key, String value) {
        if (value == null) return null;
        if ("route".equals(key)) {
            if (!value.matches("/[A-Za-z0-9._:{}/-]*") || value.contains("://")) return null;
        } else if (!value.matches("[A-Za-z0-9._:$-]+")) {
            return null;
        }
        return limited(value);
    }

    private static String safeCrashIdentifier(String value) {
        String candidate = value == null ? "" : value;
        return candidate.matches("[A-Za-z0-9_.$<>-]+") ? limited(candidate) : "unknown";
    }

    private static String safeLevel(String level) {
        String value = level == null ? "" : level.trim().toUpperCase(Locale.US);
        return value.equals("DEBUG") || value.equals("INFO") || value.equals("WARN")
                || value.equals("ERROR") ? value : "INFO";
    }

    private static String safeName(String value, String fallback) {
        String candidate = value == null ? "" : value.trim();
        return candidate.matches("[A-Za-z0-9._-]{1,120}") ? candidate : fallback;
    }

    private static String limited(String value) {
        String candidate = value == null ? "" : value;
        return candidate.length() <= MAX_FIELD_LENGTH
                ? candidate : candidate.substring(0, MAX_FIELD_LENGTH);
    }

    private static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static void writeBytes(File file, byte[] bytes, boolean append) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, append)) {
            output.write(bytes);
            output.flush();
        }
    }

    static long droppedForTests() {
        return DROPPED.get();
    }

    static boolean flushForTests() throws InterruptedException {
        Writer current;
        synchronized (MoonWakerDiagnostics.class) {
            current = writer;
        }
        return current == null || current.flush();
    }

    static synchronized void resetForTests() throws InterruptedException {
        if (writer != null) writer.close();
        writer = null;
        directory = null;
        runId = "";
        DROPPED.set(0L);
        if (installedCrashHandler != null
                && Thread.getDefaultUncaughtExceptionHandler() == installedCrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousCrashHandler);
        }
        installedCrashHandler = null;
        previousCrashHandler = null;
    }

    private static final class Entry {
        final String line;
        final CountDownLatch flushed;

        Entry(String line, CountDownLatch flushed) {
            this.line = line;
            this.flushed = flushed;
        }
    }

    private static final class Writer implements Runnable {
        private final File directory;
        private final ArrayBlockingQueue<Entry> queue =
                new ArrayBlockingQueue<>(MAX_QUEUE_ENTRIES);
        private final Thread thread = new Thread(this, "MoonWaker-Diagnostics");
        private final Object ioLock = new Object();
        private volatile boolean closing;

        Writer(File directory) {
            this.directory = directory;
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        boolean offer(String line) {
            return !closing && queue.offer(new Entry(line, null));
        }

        boolean flush() throws InterruptedException {
            CountDownLatch flushed = new CountDownLatch(1);
            if (!queue.offer(new Entry(null, flushed))) return false;
            return flushed.await(5, TimeUnit.SECONDS);
        }

        void close() throws InterruptedException {
            closing = true;
            thread.interrupt();
            thread.join(5_000L);
        }

        @Override public void run() {
            while (!closing || !queue.isEmpty()) {
                Entry entry;
                try {
                    entry = queue.poll(250L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    continue;
                }
                if (entry == null) continue;
                if (entry.line == null) {
                    entry.flushed.countDown();
                    continue;
                }
                try {
                    append(entry.line);
                } catch (Throwable ignored) {
                    DROPPED.incrementAndGet();
                }
            }
        }

        private void append(String line) throws IOException {
            synchronized (ioLock) {
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Unable to create diagnostics directory");
                }
                byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                File active = new File(directory, "events.jsonl");
                if (active.isFile() && active.length() + bytes.length > MAX_FILE_BYTES) {
                    rotate(active);
                }
                writeBytes(active, bytes, true);
            }
        }

        private void rotate(File active) throws IOException {
            File oldest = new File(directory, "events.2.jsonl");
            if (oldest.exists() && !oldest.delete()) throw new IOException("Unable to rotate diagnostics");
            File middle = new File(directory, "events.1.jsonl");
            if (middle.exists() && !middle.renameTo(oldest)) {
                throw new IOException("Unable to rotate diagnostics");
            }
            if (!active.renameTo(middle)) throw new IOException("Unable to rotate diagnostics");
        }

        boolean exportTo(OutputStream output) {
            try {
                synchronized (ioLock) {
                    copyIfPresent(new File(directory, "events.2.jsonl"), output, false);
                    copyIfPresent(new File(directory, "events.1.jsonl"), output, false);
                    copyIfPresent(new File(directory, "events.jsonl"), output, false);
                    copyIfPresent(new File(directory, "last-crash.json"), output, true);
                    output.flush();
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static void copyIfPresent(File source, OutputStream output,
                                          boolean appendNewline) throws IOException {
            if (!source.isFile()) return;
            try (FileInputStream input = new FileInputStream(source)) {
                byte[] buffer = new byte[8 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            if (appendNewline) output.write('\n');
        }
    }
}
