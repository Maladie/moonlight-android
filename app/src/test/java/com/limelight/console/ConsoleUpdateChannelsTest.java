package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleUpdateChannelsTest {
    @Test public void initialActiveSessionInvalidatesSessionPresentation() {
        ComputerDetails active = host("selected");
        active.runningGameId = 42;

        int channels = ConsoleUpdateChannels.diff(null, active, "selected");

        assertTrue(ConsoleUpdateChannels.has(channels, ConsoleUpdateChannels.SESSION));
    }
    @Test public void unrelatedHostStatusOnlyUpdatesHostSelection() {
        ComputerDetails before = host("other");
        ComputerDetails after = new ComputerDetails(before);
        after.state = ComputerDetails.State.OFFLINE;

        int channels = ConsoleUpdateChannels.diff(before, after, "selected");

        assertTrue(ConsoleUpdateChannels.has(channels,
                ConsoleUpdateChannels.HOST_SELECTION));
        assertFalse(ConsoleUpdateChannels.has(channels,
                ConsoleUpdateChannels.SELECTED_HOST));
        assertFalse(ConsoleUpdateChannels.has(channels,
                ConsoleUpdateChannels.APPLICATIONS));
    }

    @Test public void selectedHostStatusDoesNotRebuildLibraryOrApps() {
        ComputerDetails before = host("selected");
        ComputerDetails after = new ComputerDetails(before);
        after.state = ComputerDetails.State.OFFLINE;

        int channels = ConsoleUpdateChannels.diff(before, after, "selected");

        assertTrue(ConsoleUpdateChannels.has(channels,
                ConsoleUpdateChannels.HOST_SELECTION));
        assertTrue(ConsoleUpdateChannels.has(channels,
                ConsoleUpdateChannels.SELECTED_HOST));
        assertFalse(ConsoleUpdateChannels.has(channels,
                ConsoleUpdateChannels.APPLICATIONS));
        assertFalse(ConsoleUpdateChannels.has(channels,
                ConsoleUpdateChannels.SESSION));
        assertFalse(ConsoleUpdateChannels.has(channels,
                ConsoleUpdateChannels.INTEGRATIONS));
    }

    @Test public void appSessionAndAddressChangesUseIndependentChannels() {
        ComputerDetails base = host("selected");

        ComputerDetails apps = new ComputerDetails(base);
        apps.rawAppList = "<apps><app id=\"1\"/></apps>";
        int appChannels = ConsoleUpdateChannels.diff(base, apps, "selected");
        assertTrue(ConsoleUpdateChannels.has(appChannels,
                ConsoleUpdateChannels.APPLICATIONS));
        assertFalse(ConsoleUpdateChannels.has(appChannels,
                ConsoleUpdateChannels.SESSION));

        ComputerDetails session = new ComputerDetails(base);
        session.runningGameId = 42;
        int sessionChannels = ConsoleUpdateChannels.diff(base, session, "selected");
        assertTrue(ConsoleUpdateChannels.has(sessionChannels,
                ConsoleUpdateChannels.SESSION));
        assertFalse(ConsoleUpdateChannels.has(sessionChannels,
                ConsoleUpdateChannels.APPLICATIONS));

        ComputerDetails address = new ComputerDetails(base);
        address.activeAddress = new ComputerDetails.AddressTuple("192.168.1.20", 47989);
        int addressChannels = ConsoleUpdateChannels.diff(base, address, "selected");
        assertTrue(ConsoleUpdateChannels.has(addressChannels,
                ConsoleUpdateChannels.INTEGRATIONS));
        assertFalse(ConsoleUpdateChannels.has(addressChannels,
                ConsoleUpdateChannels.APPLICATIONS));
        assertFalse(ConsoleUpdateChannels.has(addressChannels,
                ConsoleUpdateChannels.SESSION));
    }

    private static ComputerDetails host(String uuid) {
        ComputerDetails host = new ComputerDetails();
        host.uuid = uuid;
        host.name = "Host " + uuid;
        host.state = ComputerDetails.State.ONLINE;
        host.activeAddress = new ComputerDetails.AddressTuple("192.168.1.10", 47989);
        host.rawAppList = "<apps/>";
        return host;
    }
}
