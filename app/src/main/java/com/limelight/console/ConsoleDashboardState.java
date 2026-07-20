package com.limelight.console;

import java.util.List;

/** Pure dashboard state rules shared by the UI and local unit tests. */
final class ConsoleDashboardState {
    enum HostState {
        DISCOVERING,
        ONLINE,
        OFFLINE,
        UNPAIRED,
        ACTIVE_SESSION
    }

    private ConsoleDashboardState() { }

    static HostState hostState(boolean online, boolean knownState,
                               boolean paired, int runningAppId) {
        if (runningAppId != 0) return HostState.ACTIVE_SESSION;
        if (!knownState) return HostState.DISCOVERING;
        if (!online) return HostState.OFFLINE;
        return paired ? HostState.ONLINE : HostState.UNPAIRED;
    }

    static String restoreSelection(String remembered, List<String> available) {
        if (remembered != null && available.contains(remembered)) return remembered;
        return available.isEmpty() ? null : available.get(0);
    }
}
