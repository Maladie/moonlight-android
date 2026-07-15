package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ConsoleStreamLaunchResolutionControllerTest {
    @Test public void deliversResolvedParameters() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RecordingListener listener = new RecordingListener();
        ConsoleStreamLaunchResolutionController controller = controller(worker, main);

        controller.resolve(request(), listener);
        worker.runNext();
        main.runNext();

        assertEquals(7, listener.parameters.appId);
        assertNull(listener.error);
    }

    @Test public void newerRequestSuppressesOlderResult() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        ConsoleStreamLaunchResolutionController controller = controller(worker, main);

        controller.resolve(request(), first);
        controller.resolve(request(), second);
        worker.runNext();
        main.runNext();
        worker.runNext();
        main.runNext();

        assertNull(first.parameters);
        assertEquals(7, second.parameters.appId);
    }

    @Test public void cancelSuppressesPendingResult() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RecordingListener listener = new RecordingListener();
        ConsoleStreamLaunchResolutionController controller = controller(worker, main);

        controller.resolve(request(), listener);
        worker.runNext();
        controller.cancel();
        main.runNext();

        assertNull(listener.parameters);
        assertNull(listener.error);
    }

    @Test public void deliversTypedFailure() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RecordingListener listener = new RecordingListener();
        ConsoleStreamLaunchResolutionController controller =
                new ConsoleStreamLaunchResolutionController(
                        request -> ConsoleStreamLaunchResolutionPolicy.Result.failed(
                                ConsoleStreamLaunchResolutionPolicy.Error.HOST_NOT_PAIRED),
                        worker, main::execute);

        controller.resolve(request(), listener);
        worker.runNext();
        main.runNext();

        assertEquals(ConsoleStreamLaunchResolutionPolicy.Error.HOST_NOT_PAIRED,
                listener.error);
    }

    @Test public void convertsLoaderExceptionToTypedFailure() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RecordingListener listener = new RecordingListener();
        ConsoleStreamLaunchResolutionController controller =
                new ConsoleStreamLaunchResolutionController(
                        request -> { throw new IllegalStateException("service lost"); },
                        worker, main::execute);

        controller.resolve(request(), listener);
        worker.runNext();
        main.runNext();

        assertEquals(ConsoleStreamLaunchResolutionPolicy.Error.RESOLUTION_FAILED,
                listener.error);
    }

    private static ConsoleStreamLaunchResolutionController controller(
            Executor worker, QueueExecutor main) {
        return new ConsoleStreamLaunchResolutionController(
                request -> ConsoleStreamLaunchResolutionPolicy.Result.resolved(parameters()),
                worker, main::execute);
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

    private static final class RecordingListener implements
            ConsoleStreamLaunchResolutionController.Listener {
        StreamLaunchParameters parameters;
        ConsoleStreamLaunchResolutionPolicy.Error error;

        @Override public void onResolved(StreamLaunchParameters parameters) {
            this.parameters = parameters;
        }

        @Override public void onFailed(ConsoleStreamLaunchResolutionPolicy.Error error) {
            this.error = error;
        }
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> commands = new ArrayDeque<>();

        @Override public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.remove().run();
        }
    }
}
