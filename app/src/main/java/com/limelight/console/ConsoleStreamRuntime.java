package com.limelight.console;

/** Replacement boundary between Console Home and the active stream runtime. */
interface ConsoleStreamRuntime {
    void launch(ConsoleLaunchContract.Request request);
    default void reconnectAtBitrate(ConsoleLaunchContract.Request request, int bitrateKbps) {
        disconnectTransport();
        launch(request.withRuntimeBitrate(bitrateKbps));
    }
    void returnToActiveStream();
    void disconnectTransport();
    void quitHostApplication();
}
