package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StreamLaunchParametersTest {
    @Test public void capturesResolvedHostAndApplicationIdentity() {
        ComputerDetails computer = computer();

        StreamLaunchParameters parameters = StreamLaunchParameters.create(
                computer, new NvApp("Desktop", 7, true), "client-id", "quick", false);

        assertEquals("192.168.1.10", parameters.host);
        assertEquals(47989, parameters.port);
        assertEquals(47984, parameters.httpsPort);
        assertEquals("client-id", parameters.uniqueId);
        assertEquals("host-uuid", parameters.computerUuid);
        assertEquals("Living Room", parameters.computerName);
        assertEquals("Desktop", parameters.appName);
        assertEquals(7, parameters.appId);
        assertTrue(parameters.appSupportsHdr);
        assertNull(parameters.serverCertificate);
        assertEquals("quick", parameters.quickLaunchAppKey);
        assertFalse(parameters.applyPreferenceOverrides);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingActiveAddress() {
        ComputerDetails computer = computer();
        computer.activeAddress = null;
        StreamLaunchParameters.create(computer, new NvApp("Desktop", 7, false),
                "client-id", null, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidApplicationId() {
        StreamLaunchParameters.create(computer(), new NvApp("Desktop", 0, false),
                "client-id", null, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingClientIdentity() {
        StreamLaunchParameters.create(computer(), new NvApp("Desktop", 7, false),
                " ", null, true);
    }

    private static ComputerDetails computer() {
        ComputerDetails computer = new ComputerDetails();
        computer.activeAddress = new ComputerDetails.AddressTuple("192.168.1.10", 47989);
        computer.httpsPort = 47984;
        computer.uuid = "host-uuid";
        computer.name = "Living Room";
        return computer;
    }
}
