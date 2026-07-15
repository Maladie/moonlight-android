package com.limelight.console;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ConsoleHomeSnapshotTest {
    @Test public void rememberedHostAndAppBecomeStableRenderSelection() {
        ConsoleDataRepository.Host first = host("host-a");
        ConsoleDataRepository.Host second = host("host-b");
        ConsoleDataRepository.App one = app(1);
        ConsoleDataRepository.App seven = app(7);

        ConsoleHomeSnapshot snapshot = ConsoleHomeSnapshot.create(null,
                Arrays.asList(first, second), "host-b", Arrays.asList(one, seven),
                7, null);

        assertEquals(1, snapshot.selectedHostIndex);
        assertSame(second, snapshot.selectedHost);
        assertEquals(1, snapshot.selectedAppIndex);
        assertFalse(snapshot.integrations.gatewayPaired);
    }

    @Test public void emptyHostsProduceEmptyAppRowAndNoSelection() {
        ConsoleHomeSnapshot snapshot = ConsoleHomeSnapshot.create(null,
                Collections.emptyList(), "removed", Collections.singletonList(app(9)),
                9, null);

        assertEquals(-1, snapshot.selectedHostIndex);
        assertNull(snapshot.selectedHost);
        assertEquals(-1, snapshot.selectedAppIndex);
        assertEquals(0, snapshot.apps.size());
    }

    @Test public void snapshotDefensivelyCopiesRows() {
        List<ConsoleDataRepository.Host> hosts = new ArrayList<>();
        hosts.add(host("host-a"));
        List<ConsoleDataRepository.App> apps = new ArrayList<>();
        apps.add(app(1));

        ConsoleHomeSnapshot snapshot = ConsoleHomeSnapshot.create(
                null, hosts, null, apps, -1, null);
        hosts.clear();
        apps.clear();

        assertEquals(1, snapshot.hosts.size());
        assertEquals(1, snapshot.apps.size());
    }

    private static ConsoleDataRepository.Host host(String uuid) {
        return new ConsoleDataRepository.Host(uuid, uuid, null);
    }

    private static ConsoleDataRepository.App app(int id) {
        return new ConsoleDataRepository.App(id, "app-" + id, null);
    }
}
