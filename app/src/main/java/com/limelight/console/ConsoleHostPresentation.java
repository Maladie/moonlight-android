package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

final class ConsoleHostPresentation {
    enum State {
        ACTIVE_SESSION,
        ONLINE,
        PROFILE_ATTENTION,
        WAKING,
        CONNECTING,
        ASLEEP,
        UNREACHABLE,
        OFFLINE,
        UNPAIRED
    }

    private ConsoleHostPresentation() { }

    enum ProfileState {
        NONE,
        ACTIVE,
        OTHER_AUTHORIZED_ACTIVE,
        OTHER_ACTIVE,
        SIGN_IN_REQUIRED,
        UNKNOWN
    }

    static final class Profile {
        final ProfileState state;
        final String selectedName;
        final String activeName;

        Profile(ProfileState state, String selectedName, String activeName) {
            this.state = state;
            this.selectedName = selectedName;
            this.activeName = activeName;
        }

        boolean needsAttention() {
            return state != ProfileState.NONE && state != ProfileState.ACTIVE;
        }
    }

    static Profile profile(HostGatewayStore.ProfileSelection selection) {
        if (selection == null || selection.selected == null) {
            return new Profile(ProfileState.NONE, "", "");
        }
        HostGatewayClient.IntegrationProfile selected = selection.selected;
        switch (selected.sessionState) {
            case "active":
                return new Profile(ProfileState.ACTIVE, selected.name, "");
            case "other_user_active":
                for (HostGatewayClient.IntegrationProfile candidate : selection.profiles) {
                    if (!candidate.id.equals(selected.id)
                            && "active".equals(candidate.sessionState)) {
                        return new Profile(ProfileState.OTHER_AUTHORIZED_ACTIVE,
                                selected.name, candidate.name);
                    }
                }
                return new Profile(ProfileState.OTHER_ACTIVE, selected.name, "");
            case "locked":
            case "disconnected":
            case "signed_out":
                return new Profile(ProfileState.SIGN_IN_REQUIRED, selected.name, "");
            case "unknown":
                return new Profile(ProfileState.UNKNOWN, selected.name, "");
            default:
                return new Profile(ProfileState.NONE, selected.name, "");
        }
    }

    static State withProfile(State state, Profile profile) {
        return state == State.ONLINE && profile != null && profile.needsAttention()
                ? State.PROFILE_ATTENTION : state;
    }

    static State state(ComputerDetails host, boolean waking) {
        if (host == null) return State.OFFLINE;
        if (host.state == ComputerDetails.State.ONLINE) {
            if (host.pairState != PairingManager.PairState.PAIRED) return State.UNPAIRED;
            return host.runningGameId != 0 ? State.ACTIVE_SESSION : State.ONLINE;
        }
        if (waking) return State.WAKING;
        if (host.pairState != PairingManager.PairState.PAIRED) return State.UNPAIRED;
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
            case PROFILE_ATTENTION:
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
