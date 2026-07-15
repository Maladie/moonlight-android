package com.limelight.console;

import java.util.Objects;

/** Adapts the in-Activity launch pipeline to Console Home's existing runtime contract. */
final class UnifiedConsoleStreamRuntimeAdapter implements ConsoleStreamRuntime, AutoCloseable {
    interface Listener {
        void onStage(UnifiedConsoleLaunchPipeline.Stage stage);
        void onFailure(UnifiedConsoleLaunchPipeline.Failure failure);
    }

    private final UnifiedConsoleLaunchPipeline pipeline;
    private final Listener listener;

    UnifiedConsoleStreamRuntimeAdapter(UnifiedConsoleLaunchPipeline pipeline,
                                       Listener listener) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override public void launch(ConsoleLaunchContract.Request request) {
        pipeline.launch(request, new UnifiedConsoleLaunchPipeline.Listener() {
            @Override public void onStage(UnifiedConsoleLaunchPipeline.Stage stage) {
                listener.onStage(stage);
            }

            @Override public void onFailed(UnifiedConsoleLaunchPipeline.Failure failure) {
                listener.onFailure(failure);
            }
        });
    }

    @Override public void reconnectAtBitrate(
            ConsoleLaunchContract.Request request, int bitrateKbps) {
        pipeline.reconnect(request.withRuntimeBitrate(bitrateKbps),
                new UnifiedConsoleLaunchPipeline.Listener() {
                    @Override public void onStage(UnifiedConsoleLaunchPipeline.Stage stage) {
                        listener.onStage(stage);
                    }

                    @Override public void onFailed(UnifiedConsoleLaunchPipeline.Failure failure) {
                        listener.onFailure(failure);
                    }
                });
    }

    @Override public void returnToActiveStream() {
        pipeline.showStream();
    }

    @Override public void disconnectTransport() {
        pipeline.disconnectTransport();
    }

    @Override public void quitHostApplication() {
        pipeline.quitHostApplication();
    }

    void showHome() {
        pipeline.showHome();
    }

    void cancelPendingLaunch() {
        pipeline.cancel();
    }

    @Override public void close() {
        pipeline.close();
    }
}
