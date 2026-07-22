package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleActionCatalogTest {
    @Test
    public void offlineHostOnlyExposesOfflineActions() {
        assertTrue(host(ConsoleActionCatalog.HostCapability.WAKE,
                false, true, true, false, true, false));
        assertTrue(host(ConsoleActionCatalog.HostCapability.REFRESH,
                false, true, true, false, true, false));
        assertTrue(host(ConsoleActionCatalog.HostCapability.DETAILS,
                false, true, true, false, true, false));
        assertFalse(host(ConsoleActionCatalog.HostCapability.SELECT_APPS,
                false, true, true, false, true, false));
        assertFalse(host(ConsoleActionCatalog.HostCapability.NETWORK_TEST,
                false, true, true, false, true, false));
        assertFalse(host(ConsoleActionCatalog.HostCapability.HOST_INTEGRATIONS,
                false, true, true, false, true, false));
    }

    @Test
    public void unpairedHostOnlyExposesPairAndManagement() {
        assertTrue(host(ConsoleActionCatalog.HostCapability.PAIR,
                true, true, false, false, true, false));
        assertTrue(host(ConsoleActionCatalog.HostCapability.HOST_INTEGRATIONS,
                true, true, false, false, true, false));
        assertFalse(host(ConsoleActionCatalog.HostCapability.SELECT_APPS,
                true, true, false, false, true, false));
        assertFalse(host(ConsoleActionCatalog.HostCapability.UNPAIR,
                true, true, false, false, true, false));
    }

    @Test
    public void activeAppHasResumeAndQuitButNotLaunch() {
        assertTrue(app(ConsoleActionCatalog.AppCapability.RESUME,
                true, true, true, false, false, false, true, true));
        assertTrue(app(ConsoleActionCatalog.AppCapability.QUIT,
                true, true, true, false, false, false, true, true));
        assertFalse(app(ConsoleActionCatalog.AppCapability.LAUNCH,
                true, true, true, false, false, false, true, true));
        assertFalse(app(ConsoleActionCatalog.AppCapability.HIDE,
                true, true, true, false, false, false, true, true));
    }

    @Test
    public void quickLaunchUsesMutuallyExclusiveAddAndRemove() {
        assertTrue(app(ConsoleActionCatalog.AppCapability.QUICK_ADD,
                false, false, false, false, false, false, false, false));
        assertFalse(app(ConsoleActionCatalog.AppCapability.QUICK_REMOVE,
                false, false, false, false, false, false, false, false));
        assertFalse(app(ConsoleActionCatalog.AppCapability.QUICK_ADD,
                false, false, false, false, true, false, false, false));
        assertTrue(app(ConsoleActionCatalog.AppCapability.QUICK_REMOVE,
                false, false, false, false, true, false, false, false));
    }

    private static boolean host(ConsoleActionCatalog.HostCapability capability,
                                boolean online, boolean known, boolean paired,
                                boolean active, boolean mac, boolean gateway) {
        return ConsoleActionCatalog.hostActionVisible(capability, online, known,
                paired, active, mac, gateway);
    }

    private static boolean app(ConsoleActionCatalog.AppCapability capability,
                               boolean online, boolean paired, boolean current,
                               boolean another, boolean quick, boolean hidden,
                               boolean artwork, boolean shortcuts) {
        return ConsoleActionCatalog.appActionVisible(capability, online, paired,
                current, another, quick, hidden, artwork, shortcuts);
    }
}
