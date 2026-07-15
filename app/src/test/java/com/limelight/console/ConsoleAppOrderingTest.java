package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ConsoleAppOrderingTest {
    @Test public void recentAppsPrecedeAlphabeticalFallback() {
        ConsoleDataRepository.Host host =
                new ConsoleDataRepository.Host("host-a", "Host", null);
        List<ConsoleDataRepository.App> ordered = ConsoleAppOrdering.order(host,
                Arrays.asList(app(1, "Zebra"), app(2, "Alpha"), app(3, "Beta")),
                (hostUuid, appId) -> appId == 3 ? 200 : appId == 1 ? 100 : 0);

        assertEquals(3, ordered.get(0).id);
        assertEquals(1, ordered.get(1).id);
        assertEquals(2, ordered.get(2).id);
    }

    @Test public void equalHistoryUsesCaseInsensitiveName() {
        ConsoleDataRepository.Host host =
                new ConsoleDataRepository.Host("host-a", "Host", null);
        List<ConsoleDataRepository.App> ordered = ConsoleAppOrdering.order(host,
                Arrays.asList(app(1, "zeta"), app(2, "Alpha")),
                (hostUuid, appId) -> 0);

        assertEquals("Alpha", ordered.get(0).name);
    }

    private static ConsoleDataRepository.App app(int id, String name) {
        return new ConsoleDataRepository.App(id, name, null);
    }
}
