package com.limelight.diagnostics;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MoonWakerDiagnosticsTest {
    private File directory;

    @Before public void setUp() throws Exception {
        MoonWakerDiagnostics.resetForTests();
        directory = Files.createTempDirectory("moonwaker-diagnostics").toFile();
    }

    @After public void tearDown() throws Exception {
        MoonWakerDiagnostics.resetForTests();
    }

    @Test public void recordRestrictsStringsAndKeepsPlannedCorrelationValues()
            throws Exception {
        MoonWakerDiagnostics.initialize(directory);
        String reason = repeat("x", 400);

        MoonWakerDiagnostics.record("WARN", "android.gateway", "request.failed",
                "reason", reason,
                "route", "/api/v1/game/{id}",
                "transition_id", "host-id:game:123:456:1",
                "stream_session_id", "e2488e65-762c-4e64-8511-4de5fc655231",
                "request_id", "request:123",
                "error_type", "java.net.SocketTimeoutException",
                "status", "gateway_unavailable",
                "app_id", 42,
                "overlay_visible", true,
                "input_blocked", false,
                "operation_authorized", true,
                "reveal_authorized", false,
                "manual_reveal_available", true,
                "uncertain", false);
        MoonWakerDiagnostics.record("WARN", "android.gateway", "request.rejected",
                "reason", "unsafe\ntext",
                "profile_id", "Bearer secret-token",
                "route", "https://host/api/v1/game?token=secret-token",
                "authorization", "Bearer secret-token",
                "state", new Object());
        assertTrue(MoonWakerDiagnostics.flushForTests());

        String raw = read(new File(directory, "events.jsonl"));
        List<String> lines = Files.readAllLines(
                new File(directory, "events.jsonl").toPath(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        JSONObject record = new JSONObject(lines.get(0));
        assertEquals(1, record.getInt("v"));
        assertTrue(record.getString("ts").matches(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
        assertTrue(record.getLong("mono_ms") >= 0L);
        assertEquals("WARN", record.getString("level"));
        assertEquals("android.gateway", record.getString("component"));
        assertEquals("request.failed", record.getString("event"));
        assertFalse(record.getString("run_id").isEmpty());
        assertEquals(256, record.getString("reason").length());
        assertEquals("/api/v1/game/{id}", record.getString("route"));
        assertEquals("host-id:game:123:456:1", record.getString("transition_id"));
        assertEquals("e2488e65-762c-4e64-8511-4de5fc655231",
                record.getString("stream_session_id"));
        assertEquals("request:123", record.getString("request_id"));
        assertEquals("java.net.SocketTimeoutException", record.getString("error_type"));
        assertEquals("gateway_unavailable", record.getString("status"));
        assertEquals(42, record.getInt("app_id"));
        assertTrue(record.getBoolean("overlay_visible"));
        assertFalse(record.getBoolean("input_blocked"));
        assertTrue(record.getBoolean("operation_authorized"));
        assertFalse(record.getBoolean("reveal_authorized"));
        assertTrue(record.getBoolean("manual_reveal_available"));
        assertFalse(record.getBoolean("uncertain"));
        MoonWakerDiagnostics.record("INFO", "android.session", "orchestration.started",
                "orchestration_id", 17L, "operation_id", 23L);
        assertTrue(MoonWakerDiagnostics.flushForTests());
        lines = Files.readAllLines(
                new File(directory, "events.jsonl").toPath(), StandardCharsets.UTF_8);
        JSONObject identifiers = new JSONObject(lines.get(2));
        assertEquals(17L, identifiers.getLong("orchestration_id"));
        assertEquals(23L, identifiers.getLong("operation_id"));
        JSONObject rejected = new JSONObject(lines.get(1));
        assertFalse(rejected.has("reason"));
        assertFalse(rejected.has("profile_id"));
        assertFalse(rejected.has("route"));
        assertFalse(rejected.has("authorization"));
        assertFalse(rejected.has("state"));
        assertFalse(raw.contains("secret-token"));
        assertFalse(raw.contains("unsafe"));
    }

    @Test public void writerRotatesToExactlyThreeBoundedFiles() throws Exception {
        MoonWakerDiagnostics.initialize(directory);
        String value = repeat("r", 256);
        for (int index = 0; index < 7_000; index++) {
            MoonWakerDiagnostics.record("INFO", "test", "rotation.record",
                    "reason", value, "transition_id", value, "game_id", value);
            if (index % 200 == 199) assertTrue(MoonWakerDiagnostics.flushForTests());
        }
        assertTrue(MoonWakerDiagnostics.flushForTests());

        File[] files = directory.listFiles((ignored, name) -> name.startsWith("events"));
        assertNotNull(files);
        assertEquals(3, files.length);
        assertTrue(new File(directory, "events.jsonl").isFile());
        assertTrue(new File(directory, "events.1.jsonl").isFile());
        assertTrue(new File(directory, "events.2.jsonl").isFile());
        for (File file : files) assertTrue(file.length() <= 2L * 1024L * 1024L);
    }

    @Test public void unavailableTargetDropsRecordsWithoutThrowing() throws Exception {
        File notDirectory = new File(directory, "not-a-directory");
        assertTrue(notDirectory.createNewFile());
        MoonWakerDiagnostics.initialize(notDirectory);

        MoonWakerDiagnostics.record("ERROR", "test", "write.failed", "reason", "io");
        assertTrue(MoonWakerDiagnostics.flushForTests());

        assertTrue(MoonWakerDiagnostics.droppedForTests() > 0L);
    }

    @Test public void concurrentRecordsRemainCompleteJsonLines() throws Exception {
        MoonWakerDiagnostics.initialize(directory);
        int workers = 4;
        int recordsPerWorker = 75;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            final int workerId = worker;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int index = 0; index < recordsPerWorker; index++) {
                        MoonWakerDiagnostics.record("INFO", "test.concurrent", "record",
                                "operation", workerId + ":" + index);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertTrue(MoonWakerDiagnostics.flushForTests());

        List<String> lines = Files.readAllLines(
                new File(directory, "events.jsonl").toPath(), StandardCharsets.UTF_8);
        assertEquals(workers * recordsPerWorker, lines.size());
        for (String line : lines) {
            JSONObject record = new JSONObject(line);
            assertEquals("test.concurrent", record.getString("component"));
            assertEquals("record", record.getString("event"));
        }
        assertEquals(0L, MoonWakerDiagnostics.droppedForTests());
    }

    @Test public void crashRecordIsSynchronousSafeAndDelegatesThenResetRestoresHandler()
            throws Exception {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        AtomicBoolean delegated = new AtomicBoolean();
        Thread.UncaughtExceptionHandler previous = (thread, error) -> delegated.set(true);
        try {
            Thread.setDefaultUncaughtExceptionHandler(previous);
            MoonWakerDiagnostics.initialize(directory);
            MoonWakerDiagnostics.installCrashHandler();
            RuntimeException crash = new RuntimeException("SECRET_MESSAGE");
            StackTraceElement[] frames = new StackTraceElement[24];
            for (int index = 0; index < frames.length; index++) {
                frames[index] = new StackTraceElement(
                        "com.limelight.Safe" + index, "run", "SecretFile.java", index + 1);
            }
            crash.setStackTrace(frames);

            Thread.getDefaultUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), crash);

            assertTrue(delegated.get());
            File crashFile = new File(directory, "last-crash.json");
            assertTrue(crashFile.isFile());
            String raw = read(crashFile);
            JSONObject record = new JSONObject(raw);
            assertEquals("uncaught_exception", record.getString("event"));
            assertEquals(RuntimeException.class.getName(), record.getString("error_type"));
            assertEquals(16, record.getJSONArray("frames").length());
            assertFalse(raw.contains("SECRET_MESSAGE"));
            assertFalse(raw.contains(Thread.currentThread().getName()));
            assertFalse(raw.contains("SecretFile.java"));

            MoonWakerDiagnostics.resetForTests();
            assertSame(previous, Thread.getDefaultUncaughtExceptionHandler());
        } finally {
            MoonWakerDiagnostics.resetForTests();
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }

    @Test public void exportFlushesQueuedEventAndAppendsSafeCrashRecord() throws Exception {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        try {
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { });
            MoonWakerDiagnostics.initialize(directory);
            MoonWakerDiagnostics.installCrashHandler();
            MoonWakerDiagnostics.record("INFO", "test.export", "queued_event",
                    "request_id", "export-123");
            RuntimeException crash = new RuntimeException("SECRET_MESSAGE");
            crash.setStackTrace(new StackTraceElement[] {
                    new StackTraceElement("com.limelight.Safe", "run",
                            "SecretFile.java", 7)
            });
            Thread.getDefaultUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), crash);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(MoonWakerDiagnostics.exportTo(output));
            String raw = output.toString(StandardCharsets.UTF_8.name());
            String[] lines = raw.split("\\n");
            assertEquals(2, lines.length);
            assertEquals("queued_event", new JSONObject(lines[0]).getString("event"));
            assertEquals("uncaught_exception", new JSONObject(lines[1]).getString("event"));
            assertFalse(raw.contains("SECRET_MESSAGE"));
            assertFalse(raw.contains("SecretFile.java"));
        } finally {
            MoonWakerDiagnostics.resetForTests();
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
