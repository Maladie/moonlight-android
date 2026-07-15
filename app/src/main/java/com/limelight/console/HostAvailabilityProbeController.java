package com.limelight.console;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Latest-only, read-only host availability probe matching Wake's port checks. */
final class HostAvailabilityProbeController {
    interface Probe {
        boolean isOnline(ConsoleDataRepository.Host host);
    }

    interface Dispatcher {
        void dispatch(Runnable action);
    }

    interface Callback {
        void onResult(Map<String, HostAvailability> availability);
    }

    private final Probe probe;
    private final Executor worker;
    private final Dispatcher main;
    private final Runnable shutdown;
    private int generation;
    private boolean destroyed;

    HostAvailabilityProbeController() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        probe = HostAvailabilityProbeController::probeHost;
        worker = executor;
        main = action -> handler.post(action);
        shutdown = executor::shutdownNow;
    }

    HostAvailabilityProbeController(Probe probe, Executor worker,
                                    Dispatcher main, Runnable shutdown) {
        this.probe = probe;
        this.worker = worker;
        this.main = main;
        this.shutdown = shutdown;
    }

    synchronized int refresh(List<ConsoleDataRepository.Host> hosts, Callback callback) {
        if (destroyed) return -1;
        int request = ++generation;
        List<ConsoleDataRepository.Host> snapshot = hosts == null ?
                Collections.emptyList() : new java.util.ArrayList<>(hosts);
        worker.execute(() -> {
            Map<String, HostAvailability> result = new LinkedHashMap<>();
            for (ConsoleDataRepository.Host host : snapshot) {
                if (Thread.currentThread().isInterrupted()) return;
                HostAvailability.State state = probe.isOnline(host) ?
                        HostAvailability.State.ONLINE : hasMac(host.macAddress) ?
                        HostAvailability.State.SLEEPING : HostAvailability.State.OFFLINE;
                result.put(host.uuid, new HostAvailability(state, host.address));
            }
            main.dispatch(() -> deliver(request, result, callback));
        });
        return request;
    }

    synchronized void cancel() { generation++; }

    synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        generation++;
        shutdown.run();
    }

    private synchronized void deliver(int request, Map<String, HostAvailability> result,
                                      Callback callback) {
        if (!destroyed && request == generation) {
            callback.onResult(Collections.unmodifiableMap(new LinkedHashMap<>(result)));
        }
    }

    private static boolean probeHost(ConsoleDataRepository.Host host) {
        if (host == null || host.address == null || host.address.isEmpty()) return false;
        int[] ports = {host.port > 0 ? host.port : 47989, 47984, 47989};
        for (int port : ports) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host.address, port), 650);
                return true;
            }
            catch (IOException unavailable) {
                // Try the next compatible GameStream/Sunshine port.
            }
        }
        return false;
    }

    private static boolean hasMac(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
