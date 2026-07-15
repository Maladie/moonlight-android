package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConsoleControllerRepositoryTest {
    @Test public void controllerNamesMatchWakeCompaction() {
        assertEquals("DualSense", ConsoleControllerRepository.compactName(
                "Sony Interactive Entertainment DualSense Wireless Controller"));
        assertEquals("Xbox Wireless Controller", ConsoleControllerRepository.compactName(
                "Xbox Wireless Controller"));
        assertEquals("Controller", ConsoleControllerRepository.compactName(null));
    }

    @Test public void batteryPresentationIsSecretFreeAndStable() {
        assertEquals("Battery unavailable",
                new ConsoleControllerRepository.Controller("Pad", -1, false).batteryLabel());
        assertEquals("Battery · 78%",
                new ConsoleControllerRepository.Controller("Pad", 78, false).batteryLabel());
        assertEquals("Charging · 12%",
                new ConsoleControllerRepository.Controller("Pad", 12, true).batteryLabel());
    }
}
