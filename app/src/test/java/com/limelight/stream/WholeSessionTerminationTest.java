package com.limelight.stream;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WholeSessionTerminationTest {
    @Test public void ownerKeepsTerminalStateOutOfActivity() {
        WholeSessionTermination.Owner owner = new WholeSessionTermination.Owner();
        assertTrue(owner.begin());
        assertTrue(owner.isRunning());
        assertFalse(owner.begin());
        owner.finish();
        assertFalse(owner.isRunning());
        assertTrue(owner.begin());
        try {
            owner.releaseAfter(success -> { throw new IllegalStateException("callback"); })
                    .complete(false);
        } catch (IllegalStateException expected) { }
        assertFalse(owner.isRunning());
    }

    @Test
    public void localPrivacyStopAndHomeDoNotWaitForHost() {
        List<String> effects = new ArrayList<>();
        AtomicReference<WholeSessionTermination.Completion> local = new AtomicReference<>();
        AtomicReference<WholeSessionTermination.Completion> host = new AtomicReference<>();
        AtomicReference<Boolean> terminal = new AtomicReference<>();

        WholeSessionTermination.start(() -> effects.add("privacy"),
                () -> effects.add("home"),
                completion -> { effects.add("local-stop"); local.set(completion); },
                completion -> { effects.add("host-rpc"); host.set(completion); },
                terminal::set);

        assertEquals(List.of("privacy", "local-stop", "home", "host-rpc"), effects);
        assertNull(terminal.get());
        host.get().complete(true);
        assertNull(terminal.get());
        local.get().complete(true);
        assertEquals(Boolean.TRUE, terminal.get());
    }

    @Test
    public void anyFailureIsTerminalFalseAndDuplicateCallbacksAreIgnored() {
        AtomicReference<WholeSessionTermination.Completion> local = new AtomicReference<>();
        AtomicReference<WholeSessionTermination.Completion> host = new AtomicReference<>();
        List<Boolean> terminal = new ArrayList<>();
        WholeSessionTermination.start(() -> { }, () -> { }, local::set, host::set,
                terminal::add);
        local.get().complete(false);
        local.get().complete(true);
        host.get().complete(true);
        assertEquals(List.of(false), terminal);
    }

    @Test
    public void failedPresentationActionCannotPreventLocalStopOrHostCleanup() {
        List<String> effects = new ArrayList<>();
        AtomicReference<Boolean> terminal = new AtomicReference<>();

        WholeSessionTermination.start(() -> { throw new IllegalStateException("view"); },
                () -> { effects.add("home"); throw new IllegalStateException("home"); },
                completion -> { effects.add("local-stop"); completion.complete(true); },
                completion -> { effects.add("host-rpc"); completion.complete(true); },
                terminal::set);

        assertEquals(List.of("local-stop", "home", "host-rpc"), effects);
        assertEquals(Boolean.FALSE, terminal.get());
    }

    @Test public void rejectedHostCorrelationStillStopsLocallyAndOpensHome() {
        List<String> effects = new ArrayList<>();
        AtomicReference<Boolean> terminal = new AtomicReference<>();
        WholeSessionTermination.start(() -> effects.add("privacy"),
                () -> effects.add("home"),
                completion -> { effects.add("local-stop"); completion.complete(true); },
                completion -> completion.complete(false), terminal::set);
        assertEquals(List.of("privacy", "local-stop", "home"), effects);
        assertEquals(Boolean.FALSE, terminal.get());
    }
}
