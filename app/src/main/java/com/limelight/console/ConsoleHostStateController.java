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

    boolean observe(ComputerDetails host) {
        return host != null && host.state == ComputerDetails.State.ONLINE
                && waking.remove(host.uuid) != null;
    }

    boolean isWaking(String hostUuid) {
        return hostUuid != null && waking.containsKey(hostUuid);
    }

    ConsoleHostPresentation.State state(ComputerDetails host) {
        return ConsoleHostPresentation.state(host,
                host != null && isWaking(host.uuid));
    }
}
