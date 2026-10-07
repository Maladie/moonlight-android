package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ConsoleHostEntryAttemptTest {
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicReference<String> frame = new AtomicReference<>();
    private final ConsoleHostEntryAttempt attempt =
            new ConsoleHostEntryAttempt(generation, frame::set);

    @Test
    public void okOnSameHostJoinsRunningFetch() {
        int a = attempt.start("host");
        assertEquals(-1, attempt.start("host"));
        assertEquals(a, generation.get());
        frame.set("host");
        assertTrue(attempt.finish(a));
        assertNull(frame.get());
        // Finished: the next OK starts a new fetch.
        assertTrue(attempt.start("host") > a);
    }

    @Test
    public void staleFetchDoesNotEndNewerAttemptsFrame() {
        int a = attempt.start("a");
        int b = attempt.start("b");
        frame.set("b");
        assertFalse(attempt.finish(a));
        assertEquals("b", frame.get());
        assertEquals(-1, attempt.start("b"));
        assertTrue(attempt.finish(b));
        assertNull(frame.get());
    }

    @Test
    public void invalidatedFetchIsNotJoined() {
        int a = attempt.start("host");
        generation.incrementAndGet();
        int b = attempt.start("host");
        assertTrue(b > a);
        assertFalse(attempt.finish(a));
        assertTrue(attempt.finish(b));
    }

    @Test
    public void okWaitingForPollSupersedesOtherHostsFetch() {
        int a = attempt.start("a");
        attempt.awaitPoll("b");
        assertFalse(attempt.finish(a));
        assertEquals("b", frame.get());
        assertTrue(attempt.pollAnswered("b"));
    }

    @Test
    public void pairingOkSupersedesRunningFetch() {
        int a = attempt.start("a");
        attempt.cancel();
        assertNull(frame.get());
        assertFalse(attempt.finish(a));
        assertTrue(attempt.start("a") > a);
    }

    @Test
    public void joiningOkKeepsFrameOnHost() {
        int a = attempt.start("host");
        attempt.press("host");
        assertEquals(-1, attempt.start("host"));
        assertEquals("host", frame.get());
        assertTrue(attempt.finish(a));
        assertNull(frame.get());
    }

    @Test
    public void pollAnswerResolvesWaitingOkOnce() {
        attempt.awaitPoll("host");
        assertEquals("host", frame.get());
        assertFalse(attempt.pollAnswered("other"));
        assertTrue(attempt.pollAnswered("host"));
        assertFalse(attempt.pollAnswered("host"));
        attempt.awaitPoll("host");
        attempt.end();
        assertNull(frame.get());
        assertFalse(attempt.pollAnswered("host"));
    }
}
