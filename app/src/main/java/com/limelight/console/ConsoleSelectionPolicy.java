package com.limelight.console;

import java.util.List;

/** Pure index selection policy shared by initial render and explicit navigation. */
final class ConsoleSelectionPolicy {
    private ConsoleSelectionPolicy() { }

    static int hostIndex(List<String> hostUuids, String rememberedHostUuid) {
        if (hostUuids == null || hostUuids.isEmpty()) return -1;
        if (rememberedHostUuid != null) {
            int index = hostUuids.indexOf(rememberedHostUuid);
            if (index >= 0) return index;
        }
        return 0;
    }

    static int appIndex(List<Integer> appIds, int rememberedAppId) {
        if (appIds == null || appIds.isEmpty()) return -1;
        if (rememberedAppId >= 0) {
            int index = appIds.indexOf(rememberedAppId);
            if (index >= 0) return index;
        }
        return 0;
    }
}
