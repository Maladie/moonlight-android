package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UnifiedConsoleStreamRuntimeAdapterTest {
    @Test public void mapsHomeContractOntoUnifiedPipeline() {
        RecordingRuntime runtime = new RecordingRuntime();
        RecordingListener listener = new RecordingListener();
        UnifiedConsoleStreamRuntimeAdapter adapter = adapter(runtime, listener);

        adapter.launch(request());
        runtime.listener.onConnected();
        adapter.showHome();
        adapter.returnToActiveStream();

        assertEquals(UnifiedConsoleLaunchPipeline.Stage.RESOLVING_HOST,
                listener.stages.get(0));
        assertEquals(UnifiedConsoleLaunchPipeline.Stage.CONNECTED,
                listener.stages.get(2));
        assertTrue(runtime.homeShown);
        assertTrue(runtime.streamShown);
    }

    @Test public void cancellationAndCloseReachUnifiedRuntime() {
        RecordingRuntime runtime = new RecordingRuntime();
        UnifiedConsoleStreamRuntimeAdapter adapter =
                adapter(runtime, new RecordingListener());

        adapter.launch(request());
        adapter.cancelPendingLaunch();
        adapter.close();

        assertTrue(runtime.cancelled);
        assertTrue(runtime.closed);
    }

    private static UnifiedConsoleStreamRuntimeAdapter adapter(
            RecordingRuntime runtime,
            RecordingListener listener) {
        ConsoleStreamLaunchResolutionController resolution =
                new ConsoleStreamLaunchResolutionController(
                        request -> ConsoleStreamLaunchResolutionPolicy.Result.resolved(
                                parameters()), Runnable::run, Runnable::run);
        return new UnifiedConsoleStreamRuntimeAdapter(
                new UnifiedConsoleLaunchPipeline(resolution, runtime), listener);
    }

    private static ConsoleLaunchContract.Request request() {
        return ConsoleLaunchContract.create(
                new ConsoleDataRepository.Host("host-a", "Host", null),
                new ConsoleDataRepository.App(7, "Game", null), "package");
    }

    private static StreamLaunchParameters parameters() {
        ComputerDetails computer = new ComputerDetails();
        computer.uuid = "host-a";
        computer.name = "Host";
        computer.activeAddress = new ComputerDetails.AddressTuple("host", 47989);
        return StreamLaunchParameters.create(
                computer, new NvApp("Game", 7, false), "client", null, true);
    }

    private static final class RecordingRuntime implements ConsoleResolvedStreamRuntime {
        Listener listener;
        boolean streamShown;
        boolean homeShown;
        boolean cancelled;
        boolean closed;

        @Override public void connect(StreamLaunchParameters parameters, Listener listener) {
            this.listener = listener;
        }

        @Override public void cancelPendingConnection() { cancelled = true; }
        @Override public void showStream() { streamShown = true; }
        @Override public void showHome() { homeShown = true; }
        @Override public void close() { closed = true; }
    }

    private static final class RecordingListener implements
            UnifiedConsoleStreamRuntimeAdapter.Listener {
        final List<UnifiedConsoleLaunchPipeline.Stage> stages = new ArrayList<>();
        UnifiedConsoleLaunchPipeline.Failure failure;
        @Override public void onStage(UnifiedConsoleLaunchPipeline.Stage stage) {
            stages.add(stage);
        }
        @Override public void onFailure(UnifiedConsoleLaunchPipeline.Failure failure) {
            this.failure = failure;
        }
    }
}
