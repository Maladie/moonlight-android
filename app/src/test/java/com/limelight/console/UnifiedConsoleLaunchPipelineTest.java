package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UnifiedConsoleLaunchPipelineTest {
    @Test public void resolvesThenConnectsInsideRuntime() {
        RecordingRuntime runtime = new RecordingRuntime();
        RecordingListener listener = new RecordingListener();
        UnifiedConsoleLaunchPipeline pipeline = pipeline(runtime,
                ConsoleStreamLaunchResolutionPolicy.Result.resolved(parameters()));

        pipeline.launch(request(), listener);

        assertEquals(UnifiedConsoleLaunchPipeline.Stage.RESOLVING_HOST,
                listener.stages.get(0));
        assertEquals(UnifiedConsoleLaunchPipeline.Stage.PREPARING_SESSION,
                listener.stages.get(1));
        assertEquals(7, runtime.parameters.appId);
        runtime.listener.onConnected();
        assertEquals(UnifiedConsoleLaunchPipeline.Stage.CONNECTED,
                listener.stages.get(2));
    }

    @Test public void resolutionFailureNeverTouchesRuntime() {
        RecordingRuntime runtime = new RecordingRuntime();
        RecordingListener listener = new RecordingListener();
        UnifiedConsoleLaunchPipeline pipeline = pipeline(runtime,
                ConsoleStreamLaunchResolutionPolicy.Result.failed(
                        ConsoleStreamLaunchResolutionPolicy.Error.HOST_NOT_PAIRED));

        pipeline.launch(request(), listener);

        assertTrue(runtime.parameters == null);
        assertEquals(UnifiedConsoleLaunchPipeline.Stage.FAILED,
                listener.stages.get(1));
        assertEquals(ConsoleStreamLaunchResolutionPolicy.Error.HOST_NOT_PAIRED,
                listener.failure.resolutionError);
    }

    @Test public void cancelSuppressesLateRuntimeCallback() {
        RecordingRuntime runtime = new RecordingRuntime();
        RecordingListener listener = new RecordingListener();
        UnifiedConsoleLaunchPipeline pipeline = pipeline(runtime,
                ConsoleStreamLaunchResolutionPolicy.Result.resolved(parameters()));

        pipeline.launch(request(), listener);
        pipeline.cancel();
        runtime.listener.onConnected();

        assertTrue(runtime.cancelled);
        assertEquals(2, listener.stages.size());
    }

    @Test public void quitHostCommandRemainsDistinctFromDisconnect() {
        RecordingRuntime runtime = new RecordingRuntime();
        UnifiedConsoleLaunchPipeline pipeline = pipeline(runtime,
                ConsoleStreamLaunchResolutionPolicy.Result.resolved(parameters()));

        pipeline.quitHostApplication();

        assertTrue(runtime.hostQuit);
        assertTrue(!runtime.cancelled);
    }

    private static UnifiedConsoleLaunchPipeline pipeline(
            RecordingRuntime runtime,
            ConsoleStreamLaunchResolutionPolicy.Result result) {
        ConsoleStreamLaunchResolutionController resolution =
                new ConsoleStreamLaunchResolutionController(
                        request -> result, Runnable::run, Runnable::run);
        return new UnifiedConsoleLaunchPipeline(resolution, runtime);
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
        StreamLaunchParameters parameters;
        Listener listener;
        boolean cancelled;
        boolean hostQuit;

        @Override public void connect(StreamLaunchParameters parameters, Listener listener) {
            this.parameters = parameters;
            this.listener = listener;
        }

        @Override public void cancelPendingConnection() { cancelled = true; }
        @Override public void showStream() { }
        @Override public void showHome() { }
        @Override public void quitHostApplication() { hostQuit = true; }
        @Override public void close() { }
    }

    private static final class RecordingListener implements
            UnifiedConsoleLaunchPipeline.Listener {
        final List<UnifiedConsoleLaunchPipeline.Stage> stages = new ArrayList<>();
        UnifiedConsoleLaunchPipeline.Failure failure;

        @Override public void onStage(UnifiedConsoleLaunchPipeline.Stage stage) {
            stages.add(stage);
        }

        @Override public void onFailed(UnifiedConsoleLaunchPipeline.Failure failure) {
            this.failure = failure;
        }
    }
}
