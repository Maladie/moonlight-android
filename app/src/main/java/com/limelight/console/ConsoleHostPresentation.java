package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

final class ConsoleHostPresentation {
    enum State {
        ACTIVE_SESSION,
        ONLINE,
        WAKING,
        CONNECTING,
        ASLEEP,
        UNREACHABLE,
        OFFLINE,
        UNPAIRED
    }

    private ConsoleHostPresentation() { }

    static State state(ComputerDetails host, boolean waking) {
        if (host == null) return State.OFFLINE;
        if (host.state == ComputerDetails.State.ONLINE) {
            if (host.pairState != PairingManager.PairState.PAIRED) return State.UNPAIRED;
            return host.runningGameId != 0 ? State.ACTIVE_SESSION : State.ONLINE;
        }
        if (waking) return State.WAKING;
        if (host.state == ComputerDetails.State.UNKNOWN) return State.CONNECTING;
        if (hasWakeAddress(host)) return State.ASLEEP;
        if (hasKnownAddress(host)) return State.UNREACHABLE;
        return State.OFFLINE;
    }

    static boolean canWake(ComputerDetails host) {
        return host != null && host.state != ComputerDetails.State.ONLINE
                && hasWakeAddress(host);
    }

    static int color(State state) {
        switch (state) {
            case ACTIVE_SESSION:
                return 0xFF73D7FF;
            case ONLINE:
                return 0xFF62E68B;
            case WAKING:
            case CONNECTING:
                return 0xFFFFC857;
            case ASLEEP:
                return 0xFF8FA6BC;
            case UNPAIRED:
                return 0xFFFFB74D;
            case UNREACHABLE:
            case OFFLINE:
            default:
                return 0xFFFF6464;
        }
    }

    private static boolean hasWakeAddress(ComputerDetails host) {
        return host.macAddress != null
                && !host.macAddress.isEmpty()
                && !"00:00:00:00:00:00".equals(host.macAddress);
    }

    private static boolean hasKnownAddress(ComputerDetails host) {
        return host.activeAddress != null || host.localAddress != null
                || host.remoteAddress != null || host.manualAddress != null
                || host.ipv6Address != null;
    }
}
