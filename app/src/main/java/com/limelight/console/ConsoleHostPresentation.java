package com.limelight.console;

import com.limelight.R;
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
        UNPAIRED,
        CHECKING
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

    /**
     * {@code polled}: a poll has answered for this host since the app started. Until then its
     * state is a remembered one and its pairing is unknown (the database does not keep it), so
     * it is checking, not unpaired. Only a host that answers reports its pairing: a host
     * offline since the start is unavailable, not unpaired.
     */
    static State state(ComputerDetails host, boolean waking, boolean polled) {
        if (host == null) return State.OFFLINE;
        if (!polled) return waking ? State.WAKING : State.CHECKING;
        if (host.state == ComputerDetails.State.ONLINE) {
            if (host.pairState != PairingManager.PairState.PAIRED) return State.UNPAIRED;
            return host.runningGameId != 0 ? State.ACTIVE_SESSION : State.ONLINE;
        }
        if (waking) return State.WAKING;
        if (host.pairState != null && host.pairState != PairingManager.PairState.PAIRED) {
            return State.UNPAIRED;
        }
        return State.OFFLINE;
    }

    /**
     * An online host holds a saved session to resume, so its status names that session instead
     * of a bare "Online". RECONNECT_REQUIRED comes from the saved record; for a record without
     * autoResume it also needs host.runningGameId from the serverinfo poll, so once the session
     * is closed or the host restarts the status reads "Online" again.
     */
    static boolean holdsPausedSession(State state, SessionSnapshot snapshot) {
        return (state == State.ONLINE || state == State.PROFILE_ATTENTION)
                && snapshot.state == SessionSnapshot.State.RECONNECT_REQUIRED;
    }

    /**
     * An online host without a session of this client runs a game (started on the host, or
     * left running by a closed stream): its status names that game instead of a bare
     * "Online". The fact comes from the session projection owner, for display only.
     */
    static boolean showsRunningGame(State state, SessionSnapshot snapshot) {
        return (state == State.ONLINE || state == State.PROFILE_ATTENTION)
                && !snapshot.display.runningGameId.isEmpty();
    }

    /**
     * Whole minutes a live session's status shows. With no game named ({@code shownGameId}
     * empty: the desktop or an application) the stream's; beside a named game only that
     * game's own, never the stream's. -1 when unknown or the named game is not the held one.
     */
    static long sessionMinutes(SessionSnapshot snapshot, String shownGameId) {
        SessionSnapshot.Display display = snapshot.display;
        return shownGameId.isEmpty() ? display.streamMinutes
                : shownGameId.equals(display.runningGameId) ? display.gameMinutes : -1L;
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
            case CHECKING:
                return 0xFF8FA6BC;
            case UNPAIRED:
                return 0xFFFFB74D;
            case UNREACHABLE:
            case OFFLINE:
            default:
                return 0xFFFF6464;
        }
    }

    /** The host's status line for a state that has no more specific text. */
    static int status(State state) {
        switch (state) {
            case WAKING:
                return R.string.console_status_waking;
            case ASLEEP:
                return R.string.console_status_asleep;
            case UNREACHABLE:
                return R.string.console_status_unreachable;
            case CONNECTING:
                return R.string.console_status_connecting;
            case UNPAIRED:
                return R.string.console_status_unpaired;
            case OFFLINE:
                return R.string.console_status_offline;
            case CHECKING:
                return R.string.console_status_checking;
            default:
                return R.string.console_status_online;
        }
    }

    static boolean hasWakeAddress(ComputerDetails host) {
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
