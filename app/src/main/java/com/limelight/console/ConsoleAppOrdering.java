package com.limelight.console;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Wake-compatible recent-first ordering without mutating repository results. */
final class ConsoleAppOrdering {
    interface History {
        long playedAt(String hostUuid, int appId);
    }

    private ConsoleAppOrdering() { }

    static List<ConsoleDataRepository.App> order(ConsoleDataRepository.Host host,
                                                 List<ConsoleDataRepository.App> apps,
                                                 History history) {
        List<ConsoleDataRepository.App> ordered = new ArrayList<>(apps);
        if (host == null) return ordered;
        ordered.sort(Comparator
                .comparingLong((ConsoleDataRepository.App app) ->
                        history.playedAt(host.uuid, app.id)).reversed()
                .thenComparing(app -> app.name, String.CASE_INSENSITIVE_ORDER));
        return ordered;
    }
}
