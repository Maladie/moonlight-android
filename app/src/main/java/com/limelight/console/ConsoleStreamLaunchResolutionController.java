package com.limelight.console;

import java.util.Objects;
import java.util.concurrent.Executor;

/** Guards asynchronous saved-host resolution against stale Console selections. */
final class ConsoleStreamLaunchResolutionController {
    interface Loader {
        ConsoleStreamLaunchResolutionPolicy.Result load(
                ConsoleLaunchContract.Request request);
    }

    interface Dispatcher {
        void dispatch(Runnable command);
    }

    interface Listener {
        void onResolved(StreamLaunchParameters parameters);
        void onFailed(ConsoleStreamLaunchResolutionPolicy.Error error);
    }

    private final Loader loader;
    private final Executor worker;
    private final Dispatcher dispatcher;
    private int generation;

    ConsoleStreamLaunchResolutionController(Loader loader,
                                            Executor worker,
                                            Dispatcher dispatcher) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    synchronized void resolve(ConsoleLaunchContract.Request request,
                              Listener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        int requestGeneration = ++generation;
        worker.execute(() -> {
            ConsoleStreamLaunchResolutionPolicy.Result loadedResult;
            try {
                loadedResult = loader.load(request);
            } catch (RuntimeException ignored) {
                loadedResult = ConsoleStreamLaunchResolutionPolicy.Result.failed(
                        ConsoleStreamLaunchResolutionPolicy.Error.RESOLUTION_FAILED);
            }
            final ConsoleStreamLaunchResolutionPolicy.Result result = loadedResult;
            dispatcher.dispatch(() -> deliver(requestGeneration, result, listener));
        });
    }

    synchronized void cancel() {
        generation++;
    }

    private synchronized void deliver(
            int requestGeneration,
            ConsoleStreamLaunchResolutionPolicy.Result result,
            Listener listener) {
        if (requestGeneration != generation) {
            return;
        }
        if (result != null && result.isResolved()) {
            listener.onResolved(result.parameters);
        } else {
            listener.onFailed(result != null ? result.error :
                    ConsoleStreamLaunchResolutionPolicy.Error.HOST_NOT_FOUND);
        }
    }
}
