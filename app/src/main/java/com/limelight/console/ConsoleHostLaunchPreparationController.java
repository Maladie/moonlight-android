package com.limelight.console;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs Wake's bounded probe/WOL sequence without owning the eventual stream launch. */
final class ConsoleHostLaunchPreparationController {
    interface Clock { long now(); }
    interface Waiter { void waitFor(long milliseconds) throws InterruptedException; }
    interface Probe { boolean isOnline(ConsoleDataRepository.Host host); }
    interface Wake { boolean send(String macAddress); }
    interface Dispatcher { void dispatch(Runnable action); }

    interface Callback {
        void onStatus(String status);
        void onReady();
        void onTimeout();
    }

    private final Clock clock;
    private final Waiter waiter;
    private final Probe probe;
    private final Wake wake;
    private final Executor worker;
    private final Dispatcher main;
    private final Runnable shutdown;
    private int generation;
    private boolean destroyed;

    ConsoleHostLaunchPreparationController() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        WakeOnLanSender sender = new WakeOnLanSender();
        clock = System::currentTimeMillis;
        waiter = Thread::sleep;
        probe = HostAvailabilityProbeController::probeHost;
        wake = sender::send;
        worker = executor;
        main = action -> handler.post(action);
        shutdown = executor::shutdownNow;
    }

    ConsoleHostLaunchPreparationController(Clock clock, Waiter waiter, Probe probe,
                                           Wake wake, Executor worker, Dispatcher main,
                                           Runnable shutdown) {
        this.clock = clock;
        this.waiter = waiter;
        this.probe = probe;
        this.wake = wake;
        this.worker = worker;
        this.main = main;
        this.shutdown = shutdown;
    }

    synchronized int prepare(ConsoleDataRepository.Host host, Callback callback) {
        if (destroyed) return -1;
        int request = ++generation;
        worker.execute(() -> run(request, host, callback));
        return request;
    }

    synchronized void cancel() { generation++; }

    synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        generation++;
        shutdown.run();
    }

    private void run(int request, ConsoleDataRepository.Host host, Callback callback) {
        long startedAt = clock.now();
        long lastWakeAt = 0;
        while (isCurrent(request) && !Thread.currentThread().isInterrupted()) {
            long now = clock.now();
            HostWakePolicy.Step step = HostWakePolicy.evaluate(startedAt, now,
                    probe.isOnline(host), hasMac(host.macAddress), lastWakeAt);
            post(request, () -> callback.onStatus(step.status));
            switch (step.action) {
                case LAUNCH:
                    post(request, callback::onReady);
                    return;
                case TIMEOUT:
                    post(request, callback::onTimeout);
                    return;
                case SEND_WAKE:
                    wake.send(host.macAddress);
                    lastWakeAt = now;
                    break;
                case WAIT:
                    break;
            }
            try {
                waiter.waitFor(step.delayMs);
            }
            catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void post(int request, Runnable action) {
        main.dispatch(() -> {
            if (isCurrent(request)) action.run();
        });
    }

    private synchronized boolean isCurrent(int request) {
        return !destroyed && request == generation;
    }

    private static boolean hasMac(String mac) {
        return WakeOnLanSender.parseMac(mac) != null;
    }
}
