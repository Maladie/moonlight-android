package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;

import java.util.LinkedHashMap;
import java.util.Map;

/** Owns transient host presentation state that isn't part of Moonlight discovery. */
final class ConsoleHostStateController {
    interface Clock {
        long now();
    }

    private final Clock clock;
    private final Map<String, Long> waking = new LinkedHashMap<>();
    private final Map<String, ComputerDetails.State> lastConfirmed = new LinkedHashMap<>();

    ConsoleHostStateController(Clock clock) {
        this.clock = clock;
    }

    long beginWaking(String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty() || waking.containsKey(hostUuid)) return -1L;
        long token = clock.now();
        waking.put(hostUuid, token);
        return token;
    }

    boolean finishWaking(String hostUuid, long token) {
        Long current = waking.get(hostUuid);
        if (current == null || current.longValue() != token) return false;
        waking.remove(hostUuid);
        return true;
    }

    void remember(String hostUuid, ComputerDetails.State state) {
        if (hostUuid == null || hostUuid.isEmpty() || state == null
                || state == ComputerDetails.State.UNKNOWN) return;
        lastConfirmed.put(hostUuid, state);
    }

    boolean observe(ComputerDetails host) {
        return observe(host, true);
    }

    boolean observe(ComputerDetails host, boolean fresh) {
        if (host == null) return false;
        if (fresh) {
            remember(host.uuid, host.state);
            return host.state == ComputerDetails.State.ONLINE
                    && waking.remove(host.uuid) != null;
        }
        ComputerDetails.State remembered = lastConfirmed.get(host.uuid);
        if (remembered != null) host.state = remembered;
        return false;
    }

    boolean isWaking(String hostUuid) {
        return hostUuid != null && waking.containsKey(hostUuid);
    }

    ConsoleHostPresentation.State state(ComputerDetails host) {
        return ConsoleHostPresentation.state(host,
                host != null && isWaking(host.uuid));
    }
}
