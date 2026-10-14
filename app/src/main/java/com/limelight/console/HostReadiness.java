package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.session.SessionEvent;
import com.limelight.session.SessionModel;
import com.limelight.session.SuspendedSessionStore;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Wake-on-LAN, bounded pre-launch port readiness check and a requested sleep's outcome. */
final class HostReadiness {
    private static final long TIMEOUT_MS = 90_000L;
    private static final long WAKE_INTERVAL_MS = 5_000L;
    private static final long DOWN_TIMEOUT_MS = 30_000L;
    /** Failed probes in a row that make a host down; one is a blip. */
    static final int DOWN_PROBES = 3;

    private HostReadiness() {}

    static void requestWake(ComputerDetails target) {
        try {
            WakeOnLanSender.sendWolPacket(target);
        } catch (IOException | RuntimeException ignored) { }
    }

    static ComputerDetails mergeFreshDetails(ComputerDetails known,
                                              ComputerDetails fresh) {
        ComputerDetails merged = new ComputerDetails(known);
        merged.update(fresh);
        return merged;
    }

    static ComputerDetails awaitAfterWakeDecision(
            Supplier<ComputerDetails> currentHost,
            ComputerDetails wakeTarget,
            boolean sendWakeOnce,
            BooleanSupplier cancelled,
            Consumer<String> status,
            String wakeStatus,
            String waitingStatus) {
        return awaitAfterWakeDecision(currentHost, wakeTarget, sendWakeOnce,
                cancelled, status, wakeStatus, waitingStatus,
                target -> {
                    requestWake(target);
                });
    }

    static ComputerDetails awaitAfterWakeDecision(
            Supplier<ComputerDetails> currentHost,
            ComputerDetails wakeTarget,
            boolean sendWakeOnce,
            BooleanSupplier cancelled,
            Consumer<String> status,
            String wakeStatus,
            String waitingStatus,
            Consumer<ComputerDetails> wakeSender) {
        if (cancelled.getAsBoolean()) return null;
        if (sendWakeOnce) {
            status.accept(wakeStatus);
            try {
                wakeSender.accept(wakeTarget);
            } catch (RuntimeException ignored) { }
        }
        return await(currentHost, null, cancelled, status, wakeStatus, waitingStatus);
    }

    static ComputerDetails await(Supplier<ComputerDetails> currentHost,
                                 ComputerDetails wakeTarget,
                                 BooleanSupplier cancelled,
                                 Consumer<String> status,
                                 String wakeStatus,
                                 String waitingStatus) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        long nextWake = 0L;
        while (!cancelled.getAsBoolean() && System.currentTimeMillis() < deadline) {
            ComputerDetails current = currentHost.get();
            if (current != null && current.state == ComputerDetails.State.ONLINE
                    && current.activeAddress != null && isStreamingPortReachable(current)) {
                return current;
            }

            long now = System.currentTimeMillis();
            if (wakeTarget != null && now >= nextWake) {
                status.accept(wakeStatus);
                requestWake(wakeTarget);
                nextWake = now + WAKE_INTERVAL_MS;
            } else {
                status.accept(waitingStatus);
            }

            if (!pause()) return null;
        }
        return null;
    }

    /**
     * Waits, bounded, for a host that was told to sleep to stop answering, so the wake that
     * follows is not ended by a host still going down. True once it failed {@link #DOWN_PROBES}
     * probes in a row; a host without an address is no evidence either way (the network, not
     * the host, may be gone).
     */
    static boolean awaitDown(Supplier<ComputerDetails> currentHost, BooleanSupplier cancelled) {
        return awaitDown(currentHost, cancelled, HostReadiness::isStreamingPortReachable,
                System::currentTimeMillis, HostReadiness::pause);
    }

    static boolean awaitDown(Supplier<ComputerDetails> currentHost, BooleanSupplier cancelled,
                             Predicate<ComputerDetails> reachable, LongSupplier clock,
                             BooleanSupplier pause) {
        long deadline = clock.getAsLong() + DOWN_TIMEOUT_MS;
        int failed = 0;
        while (!cancelled.getAsBoolean() && clock.getAsLong() < deadline) {
            ComputerDetails current = currentHost.get();
            if (current != null && current.activeAddress != null) {
                failed = current.state != ComputerDetails.State.ONLINE
                        || !reachable.test(current) ? failed + 1 : 0;
                if (failed >= DOWN_PROBES) return true;
            }
            if (!pause.getAsBoolean()) return false;
        }
        return false;
    }

    /**
     * What the host's answer leaves in the model, at once: a refused (or failed) request is
     * dropped; an accepted one keeps its suspended game, or has its record timed from the
     * acceptance ({@code acceptedAt}), which {@link #settleSleep} then names.
     */
    static void sleepAnswered(SessionModel model, String hostId, long requestedAt,
                              SuspendedSessionStore.Session suspended, boolean accepted,
                              long acceptedAt) {
        if (suspended != null) {
            if (accepted) model.post(new SessionEvent.SuspendedSaved(suspended));
        } else if (accepted) {
            model.post(new SessionEvent.HostSleepAccepted(hostId, requestedAt, acceptedAt));
        } else {
            model.post(new SessionEvent.HostSleepCleared(hostId, requestedAt));
        }
    }

    /**
     * Settles an accepted sleep from what {@link #awaitDown} saw. Down: the record reads asleep,
     * so a console that comes back shows it so before any poll. No such evidence: the record
     * stays for a poll that sees the host down, until it expires. A record cleared or replaced
     * meanwhile (a wake, a newer request) is left alone by the store.
     */
    static void settleSleep(SessionModel model, String hostId, long acceptedAt,
                            SuspendedSessionStore.Session suspended, boolean down) {
        if (!down) return;
        if (suspended != null) {
            model.post(new SessionEvent.SuspendedSleepObserved(suspended));
        } else {
            model.post(new SessionEvent.HostSleepObserved(hostId, acceptedAt));
        }
    }

    /**
     * A sleep record, timed from the host's acceptance, that no poll saw down within the bound
     * (or whose follow-up died with the process): an online host then shows as it is.
     */
    static boolean sleepRequestExpired(long requestedAtMs, long nowMs) {
        return nowMs < requestedAtMs || nowMs - requestedAtMs > 2 * DOWN_TIMEOUT_MS;
    }

    /** False when the wait was interrupted. */
    private static boolean pause() {
        try {
            Thread.sleep(1200L);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isStreamingPortReachable(ComputerDetails host) {
        Set<Integer> ports = new LinkedHashSet<>();
        ports.add(host.activeAddress.port);
        ports.add(47984);
        ports.add(47989);
        for (int port : ports) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host.activeAddress.address, port), 650);
                return true;
            } catch (IOException ignored) {}
        }
        return false;
    }
}
