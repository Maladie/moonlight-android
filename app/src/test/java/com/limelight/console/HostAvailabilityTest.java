package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HostAvailabilityTest {
    @Test public void availabilityLabelsMatchWakeHome() {
        assertEquals("CHECKING · 192.168.1.20",
                new HostAvailability(HostAvailability.State.CHECKING,
                        "192.168.1.20").label());
        assertEquals("● ONLINE · Host ready",
                new HostAvailability(HostAvailability.State.ONLINE, null).label());
        assertEquals("◐ SLEEPING · Wake-on-LAN ready",
                new HostAvailability(HostAvailability.State.SLEEPING, null).label());
        assertEquals("○ OFFLINE",
                new HostAvailability(HostAvailability.State.OFFLINE, null).label());
        assertEquals("● THIS TV · Host ready",
                new HostAvailability(HostAvailability.State.ACTIVE, null).label());
    }

    @Test public void stateColorsPreserveWakeSeverity() {
        assertEquals(0xFF69F0AE,
                new HostAvailability(HostAvailability.State.ONLINE, null).color());
        assertEquals(0xFFFFB74D,
                new HostAvailability(HostAvailability.State.SLEEPING, null).color());
        assertEquals(0xFFFF8A80,
                new HostAvailability(HostAvailability.State.OFFLINE, null).color());
    }
}
