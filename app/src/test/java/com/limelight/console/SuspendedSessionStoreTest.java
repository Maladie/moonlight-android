package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SuspendedSessionStoreTest {
    private static SuspendedSessionStore.Session session(String suspendId) {
        return new SuspendedSessionStore.Session(suspendId, "host", 42,
                "game", "Title", "", 100L);
    }

    @Test public void onlyExactResumeIdentityCanComplete() {
        SuspendedSessionStore.Session session = session("suspend-a");
        assertTrue(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "HOST", 42, "GAME"));
        assertFalse(SuspendedSessionStore.canCompleteResume(
                session, "suspend-b", "host", 42, "game"));
        assertFalse(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "other", 42, "game"));
        assertFalse(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "host", 7, "game"));
        assertFalse(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "host", 42, "other"));
    }

    @Test public void failedAttemptLeavesSameRecordResumable() {
        SuspendedSessionStore.Session pending = session("suspend-a");
        assertEquals(0L, pending.resumedAt);
        assertTrue(SuspendedSessionStore.canCompleteResume(
                pending, "suspend-a", "host", 42, "game"));
    }

    @Test public void oldActivityCannotMatchNewerSuspendRecord() {
        SuspendedSessionStore.Session replacement = session("suspend-b");
        assertFalse(SuspendedSessionStore.matches(replacement, "suspend-a"));
        assertTrue(SuspendedSessionStore.matches(replacement, "suspend-b"));
    }

    @Test public void legacySuspendIdIsDeterministic() {
        assertEquals(SuspendedSessionStore.legacySuspendId("HOST", 42, 100L),
                SuspendedSessionStore.legacySuspendId("host", 42, 100L));
    }

    @Test public void resumeIdentityCannotCrossProfilesOnTheSameHost() {
        SuspendedSessionStore.Session session = new SuspendedSessionStore.Session(
                "suspend-a", "host", "Basia", 42, "game", "Title", "", 100L);

        assertTrue(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "host", "Basia", 42, "game"));
        assertFalse(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "host", "Gry", 42, "game"));
        assertFalse(SuspendedSessionStore.legacySuspendId(
                "host", "Basia", 42, 100L).equals(
                SuspendedSessionStore.legacySuspendId(
                        "host", "Gry", 42, 100L)));
    }
}
