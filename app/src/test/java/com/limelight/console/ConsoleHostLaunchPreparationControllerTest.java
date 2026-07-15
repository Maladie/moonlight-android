package com.limelight.console;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConsoleHostLaunchPreparationControllerTest {
    @Test public void sleepingHostIsWokenThenLaunchesWhenProbeAnswers() {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger wakes = new AtomicInteger();
        AtomicInteger ready = new AtomicInteger();
        ConsoleHostLaunchPreparationController controller = controller(clock,
                host -> probes.incrementAndGet() >= 2,
                mac -> { wakes.incrementAndGet(); return true; });

        controller.prepare(host("00:11:22:33:44:55"), callback(ready, new AtomicInteger()));

        assertEquals(2, probes.get());
        assertEquals(1, wakes.get());
        assertEquals(1, ready.get());
    }

    @Test public void hostWithoutMacTimesOutWithoutWakePacket() {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger wakes = new AtomicInteger();
        AtomicInteger timeout = new AtomicInteger();
        ConsoleHostLaunchPreparationController controller = controller(clock,
                host -> false, mac -> { wakes.incrementAndGet(); return true; });

        controller.prepare(host(null), callback(new AtomicInteger(), timeout));

        assertEquals(0, wakes.get());
        assertEquals(1, timeout.get());
        assertTrue(clock.get() >= 91_000);
    }

    @Test public void destroyRejectsNewPreparationAndShutsDown() {
        AtomicBoolean shutdown = new AtomicBoolean();
        ConsoleHostLaunchPreparationController controller =
                new ConsoleHostLaunchPreparationController(System::currentTimeMillis,
                        milliseconds -> { }, host -> true, mac -> true,
                        action -> { }, Runnable::run, () -> shutdown.set(true));

        controller.destroy();

        assertTrue(shutdown.get());
        assertEquals(-1, controller.prepare(host(null),
                callback(new AtomicInteger(), new AtomicInteger())));
    }

    private static ConsoleHostLaunchPreparationController controller(
            AtomicLong clock, ConsoleHostLaunchPreparationController.Probe probe,
            ConsoleHostLaunchPreparationController.Wake wake) {
        return new ConsoleHostLaunchPreparationController(clock::get,
                clock::addAndGet, probe, wake, Runnable::run, Runnable::run, () -> { });
    }

    private static ConsoleHostLaunchPreparationController.Callback callback(
            AtomicInteger ready, AtomicInteger timeout) {
        return new ConsoleHostLaunchPreparationController.Callback() {
            @Override public void onStatus(String status) { }
            @Override public void onReady() { ready.incrementAndGet(); }
            @Override public void onTimeout() { timeout.incrementAndGet(); }
        };
    }

    private static ConsoleDataRepository.Host host(String mac) {
        return new ConsoleDataRepository.Host(
                "host", "Host", "192.168.1.2", 47989, mac);
    }
}
