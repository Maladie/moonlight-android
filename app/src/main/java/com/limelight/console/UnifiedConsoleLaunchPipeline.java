package com.limelight.console;

import java.util.Objects;

/** Coordinates host resolution and in-Activity connection without legacy Activities. */
final class UnifiedConsoleLaunchPipeline {
    enum Stage { RESOLVING_HOST, PREPARING_SESSION, CONNECTED, FAILED }

    static final class Failure {
        final ConsoleStreamLaunchResolutionPolicy.Error resolutionError;
        final String runtimeReason;

        private Failure(ConsoleStreamLaunchResolutionPolicy.Error resolutionError,
                        String runtimeReason) {
            this.resolutionError = resolutionError;
            this.runtimeReason = runtimeReason;
        }

        static Failure resolution(ConsoleStreamLaunchResolutionPolicy.Error error) {
            return new Failure(error, null);
        }

        static Failure runtime(String reason) {
            return new Failure(null, reason);
        }
    }

    interface Listener {
        void onStage(Stage stage);
        void onFailed(Failure failure);
    }

    private final ConsoleStreamLaunchResolutionController resolutionController;
    private final ConsoleResolvedStreamRuntime runtime;
    private int generation;

    UnifiedConsoleLaunchPipeline(
            ConsoleStreamLaunchResolutionController resolutionController,
            ConsoleResolvedStreamRuntime runtime) {
        this.resolutionController = Objects.requireNonNull(
                resolutionController, "resolutionController");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    synchronized void launch(ConsoleLaunchContract.Request request, Listener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        int launchGeneration = ++generation;
        listener.onStage(Stage.RESOLVING_HOST);
        resolutionController.resolve(request,
                new ConsoleStreamLaunchResolutionController.Listener() {
                    @Override public void onResolved(StreamLaunchParameters parameters) {
                        connect(launchGeneration, parameters, listener);
                    }

                    @Override public void onFailed(
                            ConsoleStreamLaunchResolutionPolicy.Error error) {
                        failResolution(launchGeneration, error, listener);
                    }
                });
    }

    synchronized void cancel() {
        generation++;
        resolutionController.cancel();
        runtime.cancelPendingConnection();
    }

    synchronized void showStream() {
        runtime.showStream();
    }

    synchronized void showHome() {
        runtime.showHome();
    }

    synchronized void disconnectTransport() {
        generation++;
        resolutionController.cancel();
        runtime.cancelPendingConnection();
    }

    synchronized void quitHostApplication() {
        generation++;
        resolutionController.cancel();
        runtime.quitHostApplication();
    }

    synchronized void close() {
        cancel();
        runtime.close();
    }

    private synchronized void connect(int launchGeneration,
                                      StreamLaunchParameters parameters,
                                      Listener listener) {
        if (launchGeneration != generation) {
            return;
        }
        listener.onStage(Stage.PREPARING_SESSION);
        runtime.connect(parameters, new ConsoleResolvedStreamRuntime.Listener() {
            @Override public void onConnected() {
                synchronized (UnifiedConsoleLaunchPipeline.this) {
                    if (launchGeneration != generation) return;
                    listener.onStage(Stage.CONNECTED);
                }
            }

            @Override public void onConnectionFailed(String reason) {
                synchronized (UnifiedConsoleLaunchPipeline.this) {
                    if (launchGeneration != generation) return;
                    listener.onStage(Stage.FAILED);
                    listener.onFailed(Failure.runtime(reason));
                }
            }
        });
    }

    private synchronized void failResolution(
            int launchGeneration,
            ConsoleStreamLaunchResolutionPolicy.Error error,
            Listener listener) {
        if (launchGeneration != generation) {
            return;
        }
        listener.onStage(Stage.FAILED);
        listener.onFailed(Failure.resolution(error));
    }
}
