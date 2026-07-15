package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ComputerManagerStreamLaunchLoaderTest {
    @Test public void waitsForServiceAndResolvesRequestedComputer() {
        RecordingSource source = new RecordingSource();
        ComputerManagerStreamLaunchLoader loader =
                new ComputerManagerStreamLaunchLoader(source);

        ConsoleStreamLaunchResolutionPolicy.Result result = loader.load(request());

        assertTrue(source.readyAwaited);
        assertEquals("host-a", source.requestedUuid);
        assertTrue(result.isResolved());
        assertEquals("client-id", result.parameters.uniqueId);
    }

    @Test public void preservesTypedPolicyFailure() {
        RecordingSource source = new RecordingSource();
        source.computer.pairState = PairingManager.PairState.NOT_PAIRED;

        ConsoleStreamLaunchResolutionPolicy.Result result =
                new ComputerManagerStreamLaunchLoader(source).load(request());

        assertEquals(ConsoleStreamLaunchResolutionPolicy.Error.HOST_NOT_PAIRED,
                result.error);
    }

    private static ConsoleLaunchContract.Request request() {
        return ConsoleLaunchContract.create(
                new ConsoleDataRepository.Host("host-a", "Host", null),
                new ConsoleDataRepository.App(7, "Game", null), "package");
    }

    private static final class RecordingSource implements
            ComputerManagerStreamLaunchLoader.Source {
        final ComputerDetails computer = computer();
        boolean readyAwaited;
        String requestedUuid;

        @Override public void awaitReady() {
            readyAwaited = true;
        }

        @Override public ComputerDetails findComputer(String uuid) {
            requestedUuid = uuid;
            return computer;
        }

        @Override public String clientUniqueId() {
            return "client-id";
        }

        private static ComputerDetails computer() {
            ComputerDetails computer = new ComputerDetails();
            computer.uuid = "host-a";
            computer.name = "Host";
            computer.activeAddress = new ComputerDetails.AddressTuple("host", 47989);
            computer.pairState = PairingManager.PairState.PAIRED;
            return computer;
        }
    }
}
