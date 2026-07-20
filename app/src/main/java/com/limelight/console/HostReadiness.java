package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.wol.WakeOnLanSender;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Wake-on-LAN and bounded pre-launch port readiness check. */
final class HostReadiness {
    private static final long TIMEOUT_MS = 90_000L;
    private static final long WAKE_INTERVAL_MS = 5_000L;

    private HostReadiness() {}

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
            if (now >= nextWake) {
                status.accept(wakeStatus);
                try {
                    WakeOnLanSender.sendWolPacket(wakeTarget);
                } catch (IOException | RuntimeException ignored) {}
                nextWake = now + WAKE_INTERVAL_MS;
            } else {
                status.accept(waitingStatus);
            }

            try {
                Thread.sleep(1200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
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
