package com.limelight.console;

/** Pure timing policy for Wake's bounded host preparation sequence. */
final class HostWakePolicy {
    static final long TIMEOUT_MS = 90_000L;
    static final long WAKE_INTERVAL_MS = 5_000L;
    static final long PROBE_INTERVAL_MS = 1_200L;

    enum Action { LAUNCH, SEND_WAKE, WAIT, TIMEOUT }

    static final class Step {
        final Action action;
        final long delayMs;
        final String status;

        private Step(Action action, long delayMs, String status) {
            this.action = action;
            this.delayMs = delayMs;
            this.status = status;
        }
    }

    private HostWakePolicy() { }

    static Step evaluate(long startedAt, long now, boolean hostOnline,
                         boolean wakeAvailable, long lastWakeAt) {
        if (hostOnline) {
            return new Step(Action.LAUNCH, 0, "Host ready · Starting stream");
        }
        if (now - startedAt >= TIMEOUT_MS) {
            return new Step(Action.TIMEOUT, 0,
                    "Host did not become ready within 90 seconds");
        }
        if (wakeAvailable && (lastWakeAt <= 0 || now - lastWakeAt >= WAKE_INTERVAL_MS)) {
            return new Step(Action.SEND_WAKE, PROBE_INTERVAL_MS,
                    "Sending Wake-on-LAN packet");
        }
        return new Step(Action.WAIT, PROBE_INTERVAL_MS,
                wakeAvailable ? "Waiting for Sunshine or Vibepollo" :
                        "Waiting for a compatible streaming host");
    }
}
