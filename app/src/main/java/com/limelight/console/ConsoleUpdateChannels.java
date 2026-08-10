package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;

import java.util.Objects;

/**
 * Classifies host polling changes into independent UI update channels.
 * Keeping this comparison outside the activity prevents a host status tick
 * from accidentally rebuilding applications, Playnite tiles, or integrations.
 */
final class ConsoleUpdateChannels {
    static final int NONE = 0;
    static final int HOST_SELECTION = 1;
    static final int SELECTED_HOST = 1 << 1;
    static final int APPLICATIONS = 1 << 2;
    static final int SESSION = 1 << 3;
    static final int INTEGRATIONS = 1 << 4;

    private ConsoleUpdateChannels() { }

    static int diff(ComputerDetails previous, ComputerDetails current,
                    String selectedHostUuid) {
        if (current == null) return NONE;
        boolean selected = current.uuid != null && current.uuid.equals(selectedHostUuid);
        boolean presentationChanged = previous == null
                || !Objects.equals(previous.name, current.name)
                || previous.state != current.state
                || previous.pairState != current.pairState;
        boolean sessionChanged = previous != null
                && previous.runningGameId != current.runningGameId;
        boolean addressChanged = previous == null
                || !Objects.equals(previous.activeAddress, current.activeAddress);
        boolean stableAppList = current.rawAppList != null && !current.rawAppList.isEmpty();
        boolean applicationsChanged = stableAppList && (previous == null
                || !Objects.equals(previous.rawAppList, current.rawAppList));

        int channels = presentationChanged ? HOST_SELECTION : NONE;
        if (!selected) return channels;
        if (presentationChanged || sessionChanged) channels |= SELECTED_HOST;
        if (applicationsChanged) channels |= APPLICATIONS;
        if (sessionChanged) channels |= SESSION;
        if (addressChanged) channels |= INTEGRATIONS;
        return channels;
    }

    static boolean has(int channels, int channel) {
        return (channels & channel) != 0;
    }
}
