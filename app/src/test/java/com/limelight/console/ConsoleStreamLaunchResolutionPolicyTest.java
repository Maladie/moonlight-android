package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleStreamLaunchResolutionPolicyTest {
    @Test public void resolvesPairedHostWithActiveAddress() {
        ConsoleStreamLaunchResolutionPolicy.Result result =
                ConsoleStreamLaunchResolutionPolicy.resolve(request(), computer(), "client-id");

        assertTrue(result.isResolved());
        assertEquals("192.168.1.10", result.parameters.host);
        assertEquals(17, result.parameters.appId);
        assertTrue(result.parameters.appSupportsHdr);
    }

    @Test public void reportsEachUnavailableHostBoundary() {
        assertFailure(ConsoleStreamLaunchResolutionPolicy.Error.HOST_NOT_FOUND,
                null, "client-id");

        ComputerDetails wrongHost = computer();
        wrongHost.uuid = "host-b";
        assertFailure(ConsoleStreamLaunchResolutionPolicy.Error.HOST_ID_MISMATCH,
                wrongHost, "client-id");

        ComputerDetails unpaired = computer();
        unpaired.pairState = PairingManager.PairState.NOT_PAIRED;
        assertFailure(ConsoleStreamLaunchResolutionPolicy.Error.HOST_NOT_PAIRED,
                unpaired, "client-id");

        ComputerDetails withoutAddress = computer();
        withoutAddress.activeAddress = null;
        assertFailure(ConsoleStreamLaunchResolutionPolicy.Error.HOST_ADDRESS_UNAVAILABLE,
                withoutAddress, "client-id");

        assertFailure(ConsoleStreamLaunchResolutionPolicy.Error.CLIENT_ID_UNAVAILABLE,
                computer(), " ");
    }

    private static void assertFailure(ConsoleStreamLaunchResolutionPolicy.Error error,
                                      ComputerDetails computer,
                                      String clientId) {
        ConsoleStreamLaunchResolutionPolicy.Result result =
                ConsoleStreamLaunchResolutionPolicy.resolve(request(), computer, clientId);
        assertFalse(result.isResolved());
        assertEquals(error, result.error);
    }

    private static ConsoleLaunchContract.Request request() {
        return ConsoleLaunchContract.create(
                new ConsoleDataRepository.Host("host-a", "Living room", null),
                new ConsoleDataRepository.App(17, "Baba Is You", null, true),
                "com.limelight.unofficial");
    }

    private static ComputerDetails computer() {
        ComputerDetails computer = new ComputerDetails();
        computer.uuid = "host-a";
        computer.name = "Living room";
        computer.activeAddress = new ComputerDetails.AddressTuple("192.168.1.10", 47989);
        computer.httpsPort = 47984;
        computer.pairState = PairingManager.PairState.PAIRED;
        return computer;
    }
}
