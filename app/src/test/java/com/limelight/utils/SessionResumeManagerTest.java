package com.limelight.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SessionResumeManagerTest {
    @Test public void processLossReconnectPreservesStoredStreamSessionId() {
        assertEquals("stream-a", SessionResumeManager.resolveStreamSessionId(
                "stream-a", "host", 42, "client"));
    }

    @Test public void legacyRecordGetsStableCompatibleIdentityWithoutWrite() {
        String first = SessionResumeManager.resolveStreamSessionId(
                "", "HOST", 42, "client");
        String second = SessionResumeManager.resolveStreamSessionId(
                null, "HOST", 42, "client");

        assertFalse(first.isEmpty());
        assertEquals(first, second);
    }
}
