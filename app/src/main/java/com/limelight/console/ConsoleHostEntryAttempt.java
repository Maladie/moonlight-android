package com.limelight.console;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Owns the host-entry attempt behind a tile's "Connecting…" frame: the profile fetch an OK starts
 * or joins, and the host whose OK waits for its first poll answer. A superseded fetch never ends
 * the frame of the newer attempt. The token is the profile gate's generation, which other gate
 * transitions also advance to invalidate a running fetch.
 */
final class ConsoleHostEntryAttempt {
    private final AtomicInteger generation;
    private final Consumer<String> frame;
    private String fetchingUuid;
    private int fetchToken;
    private String awaitingPollUuid;

    /** {@code frame} shows the frame on a host's tile, or on none for null. */
    ConsoleHostEntryAttempt(AtomicInteger generation, Consumer<String> frame) {
        this.generation = generation;
        this.frame = frame;
    }

    /** An OK on a host whose entry needs no wait for a poll: its tile shows the frame. */
    void press(String uuid) {
        frame.accept(uuid);
    }

    /**
     * The token of a new fetch for the host, or -1 when its fetch is running and OK joins it. A
     * joining OK inherits the first call's entry parameters (focusApps=false for the automatic
     * entry): only the focus after entering differs.
     */
    int start(String uuid) {
        awaitingPollUuid = null;
        if (uuid.equals(fetchingUuid) && fetchToken == generation.get()) return -1;
        fetchingUuid = uuid;
        fetchToken = generation.incrementAndGet();
        return fetchToken;
    }

    /** Ends the fetch; when it is still the current attempt it ends the frame and returns true. */
    boolean finish(int token) {
        if (token == fetchToken) fetchingUuid = null;
        if (token != generation.get()) return false;
        end();
        return true;
    }

    /**
     * An OK on a host not polled yet: a running fetch (another host's automatic entry) no longer
     * decides, and the frame stays until the poll answers.
     */
    void awaitPoll(String uuid) {
        invalidateFetch();
        awaitingPollUuid = uuid;
        frame.accept(uuid);
    }

    /** An OK that leaves the entry (pairing): a running fetch no longer decides, the frame ends. */
    void cancel() {
        invalidateFetch();
        end();
    }

    private void invalidateFetch() {
        fetchingUuid = null;
        fetchToken = generation.incrementAndGet();
    }

    /** True once, for the fresh poll answer an OK on this host waited for. */
    boolean pollAnswered(String uuid) {
        if (uuid == null || !uuid.equals(awaitingPollUuid)) return false;
        awaitingPollUuid = null;
        return true;
    }

    /** Ends the frame and any wait for a poll answer. */
    void end() {
        awaitingPollUuid = null;
        frame.accept(null);
    }
}
