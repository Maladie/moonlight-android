package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HostAvailabilityProbeControllerTest {
    @Test public void probeMapsOnlineSleepingAndOfflineIndependently() {
        HostAvailabilityProbeController controller = controller(
                host -> "online".equals(host.uuid));
        AtomicReference<Map<String, HostAvailability>> result = new AtomicReference<>();

        controller.refresh(Arrays.asList(
                host("online", null), host("sleeping", "00:11:22:33:44:55"),
                host("offline", null)), result::set);

        assertEquals(HostAvailability.State.ONLINE, result.get().get("online").state);
        assertEquals(HostAvailability.State.SLEEPING, result.get().get("sleeping").state);
        assertEquals(HostAvailability.State.OFFLINE, result.get().get("offline").state);
    }

    @Test public void cancelledResultCannotRewriteNewerHostCards() {
        final Runnable[] queued = new Runnable[1];
        AtomicInteger deliveries = new AtomicInteger();
        HostAvailabilityProbeController controller = new HostAvailabilityProbeController(
                host -> true, Runnable::run, action -> queued[0] = action, () -> { });

        controller.refresh(Collections.singletonList(host("host", null)),
                value -> deliveries.incrementAndGet());
        controller.cancel();
        queued[0].run();

        assertEquals(0, deliveries.get());
    }

    @Test public void destroyRejectsRefreshAndShutsDown() {
        AtomicBoolean shutdown = new AtomicBoolean();
        HostAvailabilityProbeController controller = new HostAvailabilityProbeController(
                host -> true, action -> { }, Runnable::run, () -> shutdown.set(true));

        controller.destroy();

        assertTrue(shutdown.get());
        assertEquals(-1, controller.refresh(Collections.emptyList(), value -> { }));
    }

    private static HostAvailabilityProbeController controller(
            HostAvailabilityProbeController.Probe probe) {
        return new HostAvailabilityProbeController(
                probe, Runnable::run, Runnable::run, () -> { });
    }

    private static ConsoleDataRepository.Host host(String uuid, String mac) {
        return new ConsoleDataRepository.Host(uuid, uuid, "192.168.1.2", 47989, mac);
    }
}
