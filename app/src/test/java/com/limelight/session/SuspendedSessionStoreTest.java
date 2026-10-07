package com.limelight.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SuspendedSessionStoreTest {
    private static SuspendedSessionStore.Session session(String suspendId) {
        return new SuspendedSessionStore.Session(suspendId, "host", "parent", 42,
                "game", "Title", "", 100L, "parent");
    }

    @Test public void onlyExactResumeIdentityCanComplete() {
        SuspendedSessionStore.Session session = session("suspend-a");
        assertTrue(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "HOST", "parent", 42, "GAME"));
        assertFalse(SuspendedSessionStore.canCompleteResume(
                session, "suspend-b", "host", "parent", 42, "game"));
        assertFalse(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "other", "parent", 42, "game"));
        assertFalse(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "host", "parent", 7, "game"));
        assertFalse(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "host", "parent", 42, "other"));
    }

    @Test public void failedAttemptLeavesSameRecordResumable() {
        SuspendedSessionStore.Session pending = session("suspend-a");
        assertEquals(0L, pending.resumedAt);
        assertTrue(SuspendedSessionStore.canCompleteResume(
                pending, "suspend-a", "host", "parent", 42, "game"));
    }

    @Test public void oldActivityCannotMatchNewerSuspendRecord() {
        SuspendedSessionStore.Session replacement = session("suspend-b");
        assertFalse(SuspendedSessionStore.matches(replacement, "suspend-a"));
        assertTrue(SuspendedSessionStore.matches(replacement, "suspend-b"));
    }

    @Test public void resumeIdentityCannotCrossProfilesOnTheSameHost() {
        SuspendedSessionStore.Session session = new SuspendedSessionStore.Session(
                "suspend-a", "host", "Basia", 42, "game", "Title", "", 100L, "Basia");

        assertTrue(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "host", "Basia", 42, "game"));
        assertFalse(SuspendedSessionStore.canCompleteResume(
                session, "suspend-a", "host", "Gry", 42, "game"));
    }

    @Test public void recordWithoutItsOwnSuspendIdIsNotARecord() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putString("host.profile.parent", "{\"profile_id\":\"parent\","
                + "\"host_id\":\"host\",\"sunshine_app_id\":42,\"suspended_at\":100}").apply();
        assertNull(SuspendedSessionStore.load(prefs.getAll(), "host", "parent"));
        assertNull(SuspendedSessionStore.loadForExecutionProfile(prefs.getAll(), "host",
                "parent"));
    }

    @Test public void modelSavesEndsAndRestoresRecordsAcrossProcessDeath() {
        SessionModelDriver driver = new SessionModelDriver();
        driver.post(new SessionEvent.SuspendedSaved(session("suspend-a")));
        assertEquals("suspend-a", driver.snapshot().records
                .suspendedForExecution("host", "parent").suspendId);

        // A new process restores the record from the store, not from a live connection.
        SessionModel restored = new SessionModel(Runnable::run, Runnable::run, () -> false,
                driver.resume, driver.suspended, driver.sleep);
        assertEquals("suspend-a", restored.records()
                .suspendedForExecution("host", "parent").suspendId);
        assertEquals(SessionModel.State.NONE, restored.state());

        assertTrue(driver.call(SessionEvent.SuspendedEnded.forExecution(
                "host", "parent", "suspend-a")));
        assertNull(driver.snapshot().records.suspendedForExecution("host", "parent"));
        assertTrue(driver.snapshot().records.recentlyEndedForExecution("host", "parent"));
    }

    @Test public void hostSelectionRequestIsConsumedOnce() {
        SessionModelDriver driver = new SessionModelDriver();
        driver.post(new SessionEvent.HostSelectionRequested("HOST"));
        assertEquals("host", driver.call(new SessionEvent.HostSelectionConsumed()));
        assertEquals("", driver.call(new SessionEvent.HostSelectionConsumed()));
    }

    @Test public void hostSleepRecordIsIdentifiedByItsRequestTime() {
        SessionModelDriver driver = new SessionModelDriver();
        driver.post(new SessionEvent.HostSleepRequested("host", 5L));
        assertEquals(5L, driver.snapshot().records.hostSleep("host").requestedAt);
        assertFalse(driver.call(new SessionEvent.HostSleepCleared("host", 4L)));
        assertTrue(driver.call(new SessionEvent.HostSleepCleared("host", 5L)));
        assertNull(driver.snapshot().records.hostSleep("host"));
    }
}
