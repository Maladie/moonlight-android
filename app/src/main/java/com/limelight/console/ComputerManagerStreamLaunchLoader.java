package com.limelight.console;

import android.os.SystemClock;

import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Adapts Moonlight's saved-computer service to Console launch resolution. */
final class ComputerManagerStreamLaunchLoader implements
        ConsoleStreamLaunchResolutionController.Loader {
    interface Source {
        void awaitReady();
        ComputerDetails findComputer(String uuid);
        String clientUniqueId();
    }

    private static final class BinderSource implements Source {
        private static final long FRESH_POLL_TIMEOUT_MS = 10_000L;

        private final ComputerManagerService.ComputerManagerBinder binder;
        private final Object pollLock = new Object();
        private final Map<String, ComputerDetails> freshComputers = new HashMap<>();
        private boolean pollingStarted;

        BinderSource(ComputerManagerService.ComputerManagerBinder binder) {
            this.binder = Objects.requireNonNull(binder, "binder");
        }

        @Override public void awaitReady() {
            binder.waitForReady();
        }

        @Override public ComputerDetails findComputer(String uuid) {
            ComputerDetails cached = binder.getComputer(uuid);
            if (cached == null) return null;

            synchronized (pollLock) {
                String normalizedUuid = uuid.toLowerCase(Locale.ROOT);
                freshComputers.remove(normalizedUuid);
                binder.invalidateStateForComputer(uuid);
                if (!pollingStarted) {
                    pollingStarted = true;
                    binder.startPolling(new ComputerManagerListener() {
                        @Override public void notifyComputerUpdated(
                                ComputerDetails details, boolean isFreshPoll) {
                            if (!isFreshPoll || details == null || details.uuid == null) {
                                return;
                            }
                            synchronized (pollLock) {
                                freshComputers.put(
                                        details.uuid.toLowerCase(Locale.ROOT), details);
                                pollLock.notifyAll();
                            }
                        }
                    });
                }

                long deadline = SystemClock.elapsedRealtime() + FRESH_POLL_TIMEOUT_MS;
                while (!freshComputers.containsKey(normalizedUuid)) {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0) break;
                    try {
                        pollLock.wait(remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                ComputerDetails fresh = freshComputers.remove(normalizedUuid);
                return fresh != null ? fresh : binder.getComputer(uuid);
            }
        }

        @Override public String clientUniqueId() {
            return binder.getUniqueId();
        }
    }

    private final Source source;

    ComputerManagerStreamLaunchLoader(
            ComputerManagerService.ComputerManagerBinder binder) {
        this(new BinderSource(binder));
    }

    ComputerManagerStreamLaunchLoader(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override public ConsoleStreamLaunchResolutionPolicy.Result load(
            ConsoleLaunchContract.Request request) {
        source.awaitReady();
        return ConsoleStreamLaunchResolutionPolicy.resolve(
                request, source.findComputer(request.hostUuid), source.clientUniqueId());
    }
}
