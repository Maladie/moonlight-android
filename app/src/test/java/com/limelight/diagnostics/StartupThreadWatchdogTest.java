package com.limelight.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StartupThreadWatchdogTest {
    @Test public void stackRecordsAreBoundedAndRejectPathsAndAddressLikeSymbols()
            throws Exception {
        StackTraceElement[] stack = new StackTraceElement[25];
        stack[0] = new StackTraceElement("com.limelight.VideoRenderer", "render",
                "private-session-source.java", 17);
        stack[1] = new StackTraceElement("C:\\private\\session", "0xdeadbeef",
                "secret.java", 18);
        for (int index = 2; index < stack.length; index++) {
            stack[index] = new StackTraceElement("com.limelight.Worker", "run",
                    "Worker.java", index);
        }

        JSONArray frames = StartupThreadWatchdog.frames(stack);
        String raw = frames.toString();
        assertEquals(20, frames.length());
        assertEquals("com.limelight.VideoRenderer",
                frames.getJSONObject(0).getString("class"));
        assertEquals("unknown", frames.getJSONObject(1).getString("class"));
        assertEquals("unknown", frames.getJSONObject(1).getString("method"));
        assertFalse(raw.contains("private-session"));
        assertFalse(raw.contains("secret.java"));
        assertFalse(raw.contains("deadbeef"));
    }

    @Test public void threadRecordsKeepOnlyEightClassifiedThreadsAndOmitNames()
            throws Exception {
        Map<Thread, StackTraceElement[]> stacks = new HashMap<>();
        for (int index = 0; index < 11; index++) {
            stacks.put(new Thread("Binder: secret-session-" + index),
                    new StackTraceElement[] {
                            new StackTraceElement("android.os.Binder", "execTransact",
                                    "Binder.java", 42)
                    });
        }

        JSONArray threads = StartupThreadWatchdog.threadRecords(stacks);
        String raw = threads.toString();
        assertEquals(8, threads.length());
        assertEquals("binder", threads.getJSONObject(0).getString("category"));
        assertTrue(threads.getJSONObject(0).getLong("thread_id") > 0L);
        assertFalse(raw.contains("secret-session"));
        assertFalse(raw.contains("Binder.java"));
    }

    @Test public void commonDiagnosticWorkerGroupsUseFixedSafeLabels() {
        assertEquals("renderer", StartupThreadWatchdog.categoryForName("MoonWaker Renderer"));
        assertEquals("choreographer", StartupThreadWatchdog.categoryForName("Choreographer"));
        assertEquals("provider", StartupThreadWatchdog.categoryForName("MediaProvider"));
        assertEquals("", StartupThreadWatchdog.categoryForName("secret-session-123"));
    }
}
